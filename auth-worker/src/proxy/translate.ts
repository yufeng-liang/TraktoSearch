// 百度翻译代理（Worker Secret 注入鉴权 + 代签名）
//
// 客户端原直连百度翻译 API 并自带 APP_ID / SECRET_KEY / API_KEY，
// 现改为走网关 /api/translate/* ，由 worker 持有密钥并代为签名，
// 避免密钥编译进 APK 被反编译泄露。
//
// 两条路径：
//   POST /api/translate/ai      —— 百度大模型翻译（Bearer API_KEY）
//   POST /api/translate/general —— 百度通用翻译（MD5 签名）
//
// 客户端请求体统一为 { q, from, to } ，worker 注入 appid / api_key / sign。

import type { Env } from '../index.ts';
import { AppError } from '../util/errors.ts';

const BAIDU_AI_URL = 'https://fanyi-api.baidu.com/ait/api/aiTextTranslate';
const BAIDU_GENERAL_URL = 'https://fanyi-api.baidu.com/api/trans/vip/translate';

interface TranslateRequest {
    q: string;
    from?: string;
    to?: string;
    reference?: string;
}

const TRANSLATION_CACHE_PREFIX = 'translation:v1:';

export async function handleTranslateProxy(
    request: Request,
    env: Env,
    path: string,
    ctx: ExecutionContext,
): Promise<Response> {
    if (path === '/api/translate/ai' && request.method === 'POST') {
        return translateWithBaiduAI(request, env, ctx);
    }
    if (path === '/api/translate/general' && request.method === 'POST') {
        return translateWithBaiduGeneral(request, env, ctx);
    }

    throw new AppError('NOT_FOUND', 'Not found', 404);
}

// 百度大模型翻译
async function translateWithBaiduAI(
    request: Request,
    env: Env,
    ctx: ExecutionContext,
): Promise<Response> {
    const body = await request.json() as TranslateRequest;
    if (!body.q) {
        throw new AppError('INVALID_REQUEST', 'q is required', 400);
    }

    const from = body.from || 'en';
    const to = body.to || 'zh';
    const cacheKey = await buildTranslationCacheKey('ai', body.q, from, to, body.reference || '');
    const cached = await readCachedTranslation(env, cacheKey);
    if (cached) return normalizedResponse(cached, true);

    if (!env.BAIDU_API_KEY || !env.BAIDU_APP_ID) {
        throw new AppError('SERVICE_UNAVAILABLE', 'Translate proxy not configured', 503);
    }

    const payload = {
        appid: env.BAIDU_APP_ID,
        q: body.q,
        from,
        to,
        model_type: 'llm',
        reference: body.reference || '',
    };

    const upstream = await fetch(BAIDU_AI_URL, {
        method: 'POST',
        headers: {
            'Content-Type': 'application/json; charset=utf-8',
            'User-Agent': 'Mozilla/5.0 (Linux; Android 14)',
            'Authorization': `Bearer ${env.BAIDU_API_KEY}`,
        },
        body: JSON.stringify(payload),
    });

    return await cacheTranslationResponse(upstream, env, ctx, cacheKey);
}

// 百度通用翻译（降级路径）
async function translateWithBaiduGeneral(
    request: Request,
    env: Env,
    ctx: ExecutionContext,
): Promise<Response> {
    const body = await request.json() as TranslateRequest;
    if (!body.q) {
        throw new AppError('INVALID_REQUEST', 'q is required', 400);
    }

    const from = body.from || 'en';
    const to = body.to || 'zh';
    const cacheKey = await buildTranslationCacheKey('general', body.q, from, to, '');
    const cached = await readCachedTranslation(env, cacheKey);
    if (cached) return normalizedResponse(cached, true);

    if (!env.BAIDU_SECRET_KEY || !env.BAIDU_APP_ID) {
        throw new AppError('SERVICE_UNAVAILABLE', 'Translate proxy not configured', 503);
    }

    const salt = String(Date.now());
    const sign = await md5Hex(env.BAIDU_APP_ID + body.q + salt + env.BAIDU_SECRET_KEY);

    const url = new URL(BAIDU_GENERAL_URL);
    url.searchParams.set('q', body.q);
    url.searchParams.set('from', from);
    url.searchParams.set('to', to);
    url.searchParams.set('appid', env.BAIDU_APP_ID);
    url.searchParams.set('salt', salt);
    url.searchParams.set('sign', sign);

    const upstream = await fetch(url.toString(), {
        method: 'GET',
        headers: { 'User-Agent': 'Mozilla/5.0 (Linux; Android 14)' },
    });

    return await cacheTranslationResponse(upstream, env, ctx, cacheKey);
}

async function cacheTranslationResponse(
    response: Response,
    env: Env,
    ctx: ExecutionContext,
    cacheKey: string,
): Promise<Response> {
    const body = await response.text();
    if (response.status !== 200) return proxyResponse(response.status, response.headers, body);

    const translation = extractTranslation(body);
    if (!translation) return proxyResponse(response.status, response.headers, body);

    // KV 写入不影响当前翻译结果，但必须交给 waitUntil，避免留下未处理的 Promise。
    ctx.waitUntil(
        env.KV.put(cacheKey, translation).catch((error) => {
            console.warn('Translation cache write failed', error);
        }),
    );
    return normalizedResponse(translation, false);
}

function normalizedResponse(translation: string, cached: boolean): Response {
    return new Response(JSON.stringify({
        translation,
        cached,
        // 保留旧版 App 使用的百度响应字段，兼容客户端渐进升级。
        trans_result: [{ dst: translation }],
    }), {
        status: 200,
        headers: { 'Content-Type': 'application/json; charset=utf-8' },
    });
}

function proxyResponse(status: number, sourceHeaders: Headers, body: string): Response {
    const headers = new Headers(sourceHeaders);
    headers.set('Content-Type', headers.get('Content-Type') || 'application/json');
    headers.delete('Content-Encoding');
    return new Response(body, {
        status,
        headers,
    });
}

async function readCachedTranslation(env: Env, key: string): Promise<string | null> {
    try {
        return await env.KV.get(key);
    } catch (error) {
        console.warn('Translation cache read failed', error);
        return null;
    }
}

async function buildTranslationCacheKey(
    provider: string,
    text: string,
    from: string,
    to: string,
    reference: string,
): Promise<string> {
    const input = JSON.stringify({ provider, text, from, to, reference });
    const digest = await crypto.subtle.digest('SHA-256', new TextEncoder().encode(input));
    const hex = Array.from(new Uint8Array(digest))
        .map((byte) => byte.toString(16).padStart(2, '0'))
        .join('');
    return `${TRANSLATION_CACHE_PREFIX}${hex}`;
}

function extractTranslation(body: string): string | null {
    let root: unknown;
    try {
        root = JSON.parse(body);
    } catch {
        return null;
    }

    if (!isRecord(root) || hasError(root)) return null;
    const direct = extractFromRecord(root);
    if (direct) return direct;

    for (const key of ['result', 'data']) {
        const nested = root[key];
        if (isRecord(nested) && !hasError(nested)) {
            const translation = extractFromRecord(nested);
            if (translation) return translation;
        }
    }
    return null;
}

function extractFromRecord(value: Record<string, unknown>): string | null {
    const direct = typeof value.translation === 'string' ? value.translation.trim() : '';
    if (direct) return direct;

    if (!Array.isArray(value.trans_result)) return null;
    const translation = value.trans_result
        .map((item) => isRecord(item) && typeof item.dst === 'string' ? item.dst : '')
        .join('')
        .trim();
    return translation || null;
}

function hasError(value: Record<string, unknown>): boolean {
    return value.error_code !== undefined && value.error_code !== null && value.error_code !== '';
}

function isRecord(value: unknown): value is Record<string, unknown> {
    return typeof value === 'object' && value !== null && !Array.isArray(value);
}

// MD5 哈希（使用 Web Crypto API 的 SubtleCrypto 不支持 MD5，需手写或用第三方）。
// Cloudflare Workers 运行时内置了 crypto.createHash('md5')（Node.js 兼容层）。
async function md5Hex(input: string): Promise<string> {
    // @ts-expect-error -- Cloudflare Workers 提供的 Node.js 兼容 API
    const { createHash } = await import('node:crypto');
    return createHash('md5').update(input, 'utf8').digest('hex');
}
