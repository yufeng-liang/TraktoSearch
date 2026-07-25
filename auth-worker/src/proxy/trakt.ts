// Trakt 代理（Worker Secret + D1 用户凭据）

import { Env } from '../index';
import { AppError } from '../util/errors';
import { decryptSecret, encryptSecret } from '../util/crypto';
import { now } from '../util/errors';
import {
    TRAKT_OAUTH_REDIRECT_URI,
    buildTraktApiHeaders,
    buildTraktProxyUrl,
    buildAuthorizationCodePayload,
    buildRefreshTokenPayload,
    classifyTraktOAuthError,
    readTraktProxyBody,
} from './trakt-token';
import { KeyPool, fetchWithKeyRotation } from '../util/key-pool';
import { firstRow } from '../util/db';

const TRAKT_BASE_URL = 'https://api.trakt.tv';

const TRAKT_PREFIX = '/api/trakt/';

// 同一朋友的多个请求可能同时发现 access token 过期。
// 在同一个 Worker isolate 内共享刷新 Promise，避免并发消费同一个 refresh token。
const refreshInFlight = new Map<string, Promise<Response>>();

// Trakt OAuth 端点
export async function handleTraktOAuth(
    request: Request,
    env: Env,
    path: string,
    friendId: string,
    requestId: string
): Promise<Response> {
    const subPath = path.replace('/api/trakt/oauth/', '');

    if (subPath === 'authorize' && request.method === 'GET') {
        const clientId = await new KeyPool('trakt', env.TRAKT_CLIENT_ID, env.KV).pickKey();
        const url = new URL('https://trakt.tv/oauth/authorize');
        url.searchParams.set('response_type', 'code');
        url.searchParams.set('client_id', clientId);
        url.searchParams.set('redirect_uri', TRAKT_OAUTH_REDIRECT_URI);
        return new Response(JSON.stringify({
            code: 'SUCCESS',
            message: 'OK',
            requestId,
            data: { url: url.toString() },
        }), { status: 200, headers: { 'Content-Type': 'application/json' } });
    }

    if (subPath === 'exchange' && request.method === 'POST') {
        return traktExchange(request, env, friendId);
    }
    if (subPath === 'refresh' && request.method === 'POST') {
        return traktRefresh(request, env, friendId);
    }
    if (subPath === 'disconnect' && request.method === 'POST') {
        await env.DB.prepare('DELETE FROM trakt_credentials WHERE friend_id = ?').bind(friendId).run();
        return new Response(JSON.stringify({
            code: 'SUCCESS',
            message: 'OK',
            requestId,
            data: { success: true },
        }), { status: 200, headers: { 'Content-Type': 'application/json' } });
    }

    throw new AppError('NOT_FOUND', 'Not found', 404);
}

// OAuth 授权码交换
async function traktExchange(
    request: Request,
    env: Env,
    friendId: string
): Promise<Response> {
    const body = await request.json() as { code: string };

    if (!body.code) {
        throw new AppError('INVALID_REQUEST', 'code is required', 400);
    }

    // 用 Worker Secret 中的 client_secret 交换
    const pool = new KeyPool('trakt', env.TRAKT_CLIENT_ID, env.KV);
    const tokenResponse = await fetchWithKeyRotation(
        pool,
        (clientId) => fetch(`${TRAKT_BASE_URL}/oauth/token`, {
            method: 'POST',
            headers: traktOAuthHeaders(clientId),
            body: JSON.stringify(buildAuthorizationCodePayload(
                body.code,
                clientId,
                env.TRAKT_CLIENT_SECRET,
            )),
        }),
        [401, 403, 429],
    );

    if (!tokenResponse.ok) {
        const classification = await classifyTokenResponse(tokenResponse);
        throw new AppError(classification.code, classification.message, 400);
    }

    const tokens = await tokenResponse.json() as {
        access_token: string;
        refresh_token: string;
        expires_in: number;
    };

    // 加密保存到 D1
    await saveTraktCredentials(env, friendId, tokens);

    return new Response(JSON.stringify({
        code: 'SUCCESS',
        message: 'OK',
        requestId: crypto.randomUUID(),
        data: { success: true },
    }), { status: 200, headers: { 'Content-Type': 'application/json' } });
}

// Trakt 令牌刷新
async function traktRefresh(
    request: Request,
    env: Env,
    friendId: string
): Promise<Response> {
    const credentials = await getTraktCredentials(env, friendId);
    if (!credentials?.refresh_token) {
        throw new AppError('INVALID_REQUEST', 'No Trakt refresh token available', 400);
    }

    const pool = new KeyPool('trakt', env.TRAKT_CLIENT_ID, env.KV);
    const tokenResponse = await fetchWithKeyRotation(
        pool,
        (clientId) => fetch(`${TRAKT_BASE_URL}/oauth/token`, {
            method: 'POST',
            headers: traktOAuthHeaders(clientId),
            body: JSON.stringify(buildRefreshTokenPayload(
                credentials.refresh_token,
                clientId,
                env.TRAKT_CLIENT_SECRET,
            )),
        }),
        [401, 403, 429],
    );

    if (!tokenResponse.ok) {
        const classification = await classifyTokenResponse(tokenResponse);
        throw new AppError(classification.code, classification.message, 400);
    }

    const tokens = await tokenResponse.json() as {
        access_token: string;
        refresh_token: string;
        expires_in: number;
    };

    await saveTraktCredentials(env, friendId, tokens);

    return new Response(JSON.stringify({
        code: 'SUCCESS',
        message: 'OK',
        requestId: crypto.randomUUID(),
        data: { success: true },
    }), { status: 200, headers: { 'Content-Type': 'application/json' } });
}

// Trakt API 代理（自动注入用户 access_token）
export async function handleTraktProxy(
    request: Request,
    env: Env,
    path: string,
    friendId: string
): Promise<Response> {
    const traktPath = path.replace(TRAKT_PREFIX, '');

    // 获取用户 Trakt 凭据
    const credentials = await getTraktCredentials(env, friendId);
    if (!credentials?.access_token) {
        throw new AppError('UNAUTHORIZED', 'Trakt not connected', 401);
    }

    const url = buildTraktProxyUrl(request.url, traktPath);
    const body = await readTraktProxyBody(request);
    const pool = new KeyPool('trakt', env.TRAKT_CLIENT_ID, env.KV);
    // 业务 API 的 401 代表用户 token 过期，不能拿它误判 client ID 失效。
    const upstreamResponse = await fetchWithKeyRotation(
        pool,
        (clientId) => fetch(url, {
            method: request.method,
            headers: buildTraktApiHeaders(credentials.access_token, clientId),
            body,
        }),
        [403, 429],
    );

    // 401 → Trakt token 过期，尝试刷新
    if (upstreamResponse.status === 401) {
        const refreshResult = await refreshTraktCredentials(request, env, friendId);
        if (refreshResult.status === 200) {
            // 重试
            const newCredentials = await getTraktCredentials(env, friendId);
            const retryResponse = await fetchWithKeyRotation(
                pool,
                (clientId) => fetch(url, {
                    method: request.method,
                    headers: buildTraktApiHeaders(newCredentials?.access_token || '', clientId),
                    body,
                }),
                [403, 429],
            );
            return proxyResponse(retryResponse);
        }
    }

    return proxyResponse(upstreamResponse);
}

/** 合并同一朋友在同一 isolate 内并发触发的 Trakt token 刷新。 */
async function refreshTraktCredentials(
    request: Request,
    env: Env,
    friendId: string,
): Promise<Response> {
    const existing = refreshInFlight.get(friendId);
    if (existing) return existing;

    const refreshPromise = traktRefresh(request, env, friendId).finally(() => {
        refreshInFlight.delete(friendId);
    });
    refreshInFlight.set(friendId, refreshPromise);
    return refreshPromise;
}

function proxyResponse(response: Response): Response {
    return new Response(response.body, {
        status: response.status,
        headers: {
            'Content-Type': 'application/json',
            ...Object.fromEntries(response.headers),
        },
    });
}

// === 凭据加密存储 ===

interface TraktTokens {
    access_token: string;
    refresh_token: string;
    expires_in: number;
}

// 加密保存 Trakt 凭据
async function saveTraktCredentials(
    env: Env,
    friendId: string,
    tokens: TraktTokens
): Promise<void> {
    const ciphertext = await encryptSecret(
        JSON.stringify(tokens),
        env.TRAKT_CREDENTIALS_ENCRYPTION_KEY
    );

    await env.DB.prepare(`
        INSERT OR REPLACE INTO trakt_credentials (friend_id, ciphertext, updated_at)
        VALUES (?, ?, ?)
    `).bind(friendId, ciphertext, now()).run();
}

// 获取 Trakt 凭据
async function getTraktCredentials(
    env: Env,
    friendId: string
): Promise<TraktTokens | null> {
    const result = await firstRow<{ ciphertext: string }>(env.DB.prepare(`
        SELECT ciphertext FROM trakt_credentials WHERE friend_id = ?
    `).bind(friendId));

    if (!result) return null;

    try {
        return JSON.parse(
            await decryptSecret(result.ciphertext, env.TRAKT_CREDENTIALS_ENCRYPTION_KEY)
        ) as TraktTokens;
    } catch {
        throw new AppError('CREDENTIALS_INVALID', 'Stored Trakt credentials are invalid', 401);
    }
}

function traktOAuthHeaders(clientId: string): HeadersInit {
    return {
        'Accept': 'application/json',
        'Content-Type': 'application/json',
        'User-Agent': 'TrackToSearch/3.0',
        'trakt-api-version': '2',
        'trakt-api-key': clientId,
    };
}

async function classifyTokenResponse(response: Response) {
    let body: unknown = null;
    try {
        body = await response.clone().json();
    } catch {
        // Trakt 可能返回空响应或非 JSON，按状态码分类。
    }
    const classification = classifyTraktOAuthError(response.status, body);
    console.warn('Trakt OAuth request rejected', {
        status: response.status,
        code: classification.code,
    });
    return classification;
}
