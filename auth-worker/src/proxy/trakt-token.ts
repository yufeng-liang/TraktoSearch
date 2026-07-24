export const TRAKT_OAUTH_REDIRECT_URI = 'tracktosearch://oauth/callback';

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
