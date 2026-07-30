export const TRAKT_OAUTH_REDIRECT_URI = 'tracktosearch://oauth/callback';
export const TRAKT_USER_AGENT = 'TrackToSearch/3.0';
const TRAKT_BASE_URL = 'https://api.trakt.tv';

/** 构造 Trakt 上游 URL，并保留 Retrofit 传入的查询参数。 */
export function buildTraktProxyUrl(requestUrl: string, traktPath: string): string {
    const upstreamUrl = new URL(`${TRAKT_BASE_URL}/${traktPath}`);
    upstreamUrl.search = new URL(requestUrl).search;
    return upstreamUrl.toString();
}

/** 将请求体读入可重复使用的缓冲区，供 401 刷新后的 POST 重试复用。 */
export async function readTraktProxyBody(request: Request): Promise<ArrayBuffer | undefined> {
    if (request.method === 'GET' || request.method === 'HEAD') return undefined;
    return request.arrayBuffer();
}

/** 构造 Trakt API 代理请求头，保持与原生客户端的标识一致。 */
export function buildTraktApiHeaders(
    accessToken: string,
    clientId: string,
): HeadersInit {
    return {
        'Content-Type': 'application/json',
        'User-Agent': TRAKT_USER_AGENT,
        'Authorization': `Bearer ${accessToken}`,
        'trakt-api-version': '2',
        'trakt-api-key': clientId,
    };
}

/** 构造 Trakt 公开端点请求头（仅需 client_id，不需要用户 access_token）。
 *  用于 trending / anticipated / search / movies/{id} / shows/{id} 等公开数据。
 */
export function buildTraktPublicApiHeaders(
    clientId: string,
): HeadersInit {
    return {
        'Content-Type': 'application/json',
        'User-Agent': TRAKT_USER_AGENT,
        'trakt-api-version': '2',
        'trakt-api-key': clientId,
    };
}

/** 判断 Trakt 子路径是否为公开端点（无需用户 access_token）。
 *  - sync/* 、recommendations/* 、users/* 、shows/{id}/progress/* 需要用户凭据
 *  - 其余端点（movies/* 、shows/* 、lists/* 、search/* 、people/* 等）仅需 client_id
 */
export function isTraktPublicPath(traktPath: string): boolean {
    if (traktPath.startsWith('sync/')) return false;
    if (traktPath.startsWith('recommendations/')) return false;
    if (traktPath.startsWith('users/')) return false;
    // shows/{id}/progress/* 需要用户 token
    if (/^shows\/\d+\/progress\//.test(traktPath)) return false;
    // comments POST 需要用户 token（GET comments 走 movies/{id}/comments 和 shows/{id}/comments）
    if (traktPath === 'comments') return false;
    return true;
}

export interface TraktAuthorizationCodePayload {
    code: string;
    client_id: string;
    client_secret: string;
    redirect_uri: string;
    grant_type: 'authorization_code';
}

export interface TraktRefreshTokenPayload {
    refresh_token: string;
    client_id: string;
    client_secret: string;
    redirect_uri: string;
    grant_type: 'refresh_token';
}

export function buildAuthorizationCodePayload(
    code: string,
    clientId: string,
    clientSecret: string,
): TraktAuthorizationCodePayload {
    return {
        code,
        client_id: clientId,
        client_secret: clientSecret,
        redirect_uri: TRAKT_OAUTH_REDIRECT_URI,
        grant_type: 'authorization_code',
    };
}

export function buildRefreshTokenPayload(
    refreshToken: string,
    clientId: string,
    clientSecret: string,
): TraktRefreshTokenPayload {
    return {
        refresh_token: refreshToken,
        client_id: clientId,
        client_secret: clientSecret,
        redirect_uri: TRAKT_OAUTH_REDIRECT_URI,
        grant_type: 'refresh_token',
    };
}

export interface TraktOAuthErrorClassification {
    code: string;
    message: string;
}

export function classifyTraktOAuthError(
    status: number,
    body: unknown,
): TraktOAuthErrorClassification {
    const error = typeof body === 'object' && body !== null && 'error' in body
        ? String((body as { error?: unknown }).error || '').toLowerCase()
        : '';

    if (error === 'invalid_grant') {
        return {
            code: 'TRAKT_AUTH_CODE_INVALID',
            message: 'Trakt authorization code is invalid or expired',
        };
    }
    if (error === 'invalid_client' || status === 401) {
        return {
            code: 'TRAKT_CLIENT_INVALID',
            message: 'Trakt client configuration was rejected',
        };
    }
    if (error === 'invalid_request' || error.includes('redirect')) {
        return {
            code: 'TRAKT_REDIRECT_URI_INVALID',
            message: 'Trakt redirect URI was rejected',
        };
    }
    return {
        code: 'TRAKT_UPSTREAM_ERROR',
        message: `Trakt OAuth request failed (${status})`,
    };
}
