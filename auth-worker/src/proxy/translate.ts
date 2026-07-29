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

import { Env } from '../index';
import { AppError } from '../util/errors';

const BAIDU_AI_URL = 'https://fanyi-api.baidu.com/ait/api/aiTextTranslate';
const BAIDU_GENERAL_URL = 'https://fanyi-api.baidu.com/api/trans/vip/translate';

interface TranslateRequest {
    q: string;
    from: string;
    to: string;
    reference?: string;
}

export async function handleTranslateProxy(
    request: Request,
    env: Env,
    path: string
): Promise<Response> {
    if (!env.BAIDU_API_KEY || !env.BAIDU_SECRET_KEY || !env.BAIDU_APP_ID) {
        throw new AppError('SERVICE_UNAVAILABLE', 'Translate proxy not configured', 503);
    }

    if (path === '/api/translate/ai' && request.method === 'POST') {
        return translateWithBaiduAI(request, env);
    }
    if (path === '/api/translate/general' && request.method === 'POST') {
        return translateWithBaiduGeneral(request, env);
    }

    throw new AppError('NOT_FOUND', 'Not found', 404);
}

// 百度大模型翻译
async function translateWithBaiduAI(request: Request, env: Env): Promise<Response> {
    const body = await request.json() as TranslateRequest;
    if (!body.q) {
        throw new AppError('INVALID_REQUEST', 'q is required', 400);
    }

    const payload = {
        appid: env.BAIDU_APP_ID,
        q: body.q,
        from: body.from || 'en',
        to: body.to || 'zh',
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

    return proxyResponse(upstream);
}

// 百度通用翻译（降级路径）
async function translateWithBaiduGeneral(request: Request, env: Env): Promise<Response> {
    const body = await request.json() as TranslateRequest;
    if (!body.q) {
        throw new AppError('INVALID_REQUEST', 'q is required', 400);
    }

    const salt = String(Date.now());
    const sign = await md5Hex(env.BAIDU_APP_ID + body.q + salt + env.BAIDU_SECRET_KEY);

    const url = new URL(BAIDU_GENERAL_URL);
    url.searchParams.set('q', body.q);
    url.searchParams.set('from', body.from || 'en');
    url.searchParams.set('to', body.to || 'zh');
    url.searchParams.set('appid', env.BAIDU_APP_ID);
    url.searchParams.set('salt', salt);
    url.searchParams.set('sign', sign);

    const upstream = await fetch(url.toString(), {
        method: 'GET',
        headers: { 'User-Agent': 'Mozilla/5.0 (Linux; Android 14)' },
    });

    return proxyResponse(upstream);
}

function proxyResponse(response: Response): Response {
    const headers = new Headers(response.headers);
    headers.set('Content-Type', headers.get('Content-Type') || 'application/json');
    return new Response(response.body, {
        status: response.status,
        headers,
    });
}

// MD5 哈希（使用 Web Crypto API 的 SubtleCrypto 不支持 MD5，需手写或用第三方）。
// Cloudflare Workers 运行时内置了 crypto.createHash('md5')（Node.js 兼容层）。
async function md5Hex(input: string): Promise<string> {
    // @ts-expect-error -- Cloudflare Workers 提供的 Node.js 兼容 API
    const { createHash } = await import('node:crypto');
    return createHash('md5').update(input, 'utf8').digest('hex');
}
