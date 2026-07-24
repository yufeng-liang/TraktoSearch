// Trakt 代理（Worker Secret + D1 用户凭据）

import { Env } from '../index';
import { AppError } from '../util/errors';
import { decryptSecret, encryptSecret } from '../util/crypto';
import { now } from '../util/errors';
import {
    TRAKT_OAUTH_REDIRECT_URI,
    buildAuthorizationCodePayload,
    buildRefreshTokenPayload,
    classifyTraktOAuthError,
} from './trakt-token';

const TRAKT_BASE_URL = 'https://api.trakt.tv';

const TRAKT_PREFIX = '/api/trakt/';

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
        const url = new URL('https://trakt.tv/oauth/authorize');
        url.searchParams.set('response_type', 'code');
        url.searchParams.set('client_id', env.TRAKT_CLIENT_ID);
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
    const tokenResponse = await fetch(`${TRAKT_BASE_URL}/oauth/token`, {
        method: 'POST',
        headers: traktOAuthHeaders(env.TRAKT_CLIENT_ID),
        body: JSON.stringify(buildAuthorizationCodePayload(
            body.code,
            env.TRAKT_CLIENT_ID,
            env.TRAKT_CLIENT_SECRET,
        )),
    });

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

    const tokenResponse = await fetch(`${TRAKT_BASE_URL}/oauth/token`, {
        method: 'POST',
        headers: traktOAuthHeaders(env.TRAKT_CLIENT_ID),
        body: JSON.stringify(buildRefreshTokenPayload(
            credentials.refresh_token,
            env.TRAKT_CLIENT_ID,
            env.TRAKT_CLIENT_SECRET,
        )),
    });

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

    const url = `${TRAKT_BASE_URL}/${traktPath}`;

    const upstreamResponse = await fetch(url, {
        method: request.method,
        headers: {
            'Content-Type': 'application/json',
            'Authorization': `Bearer ${credentials.access_token}`,
            'trakt-api-version': '2',
            'trakt-api-key': env.TRAKT_CLIENT_ID,
        },
        body: request.method !== 'GET' ? await request.blob() : undefined,
    });

    // 401 → Trakt token 过期，尝试刷新
    if (upstreamResponse.status === 401) {
        const refreshResult = await traktRefresh(request, env, friendId);
        if (refreshResult.status === 200) {
            // 重试
            const newCredentials = await getTraktCredentials(env, friendId);
            const retryResponse = await fetch(url, {
                method: request.method,
                headers: {
                    'Content-Type': 'application/json',
                    'Authorization': `Bearer ${newCredentials?.access_token}`,
                    'trakt-api-version': '2',
                    'trakt-api-key': env.TRAKT_CLIENT_ID,
                },
                body: request.method !== 'GET' ? await request.blob() : undefined,
            });
            return proxyResponse(retryResponse);
        }
    }

    return proxyResponse(upstreamResponse);
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
    const result = await env.DB.prepare(`
        SELECT ciphertext FROM trakt_credentials WHERE friend_id = ?
    `).bind(friendId).first<{ ciphertext: string }>();

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
