// Cloudflare Access JWT 验证
import { AppError } from '../util/errors';

interface AccessPayload {
    iss: string;
    sub: string;
    aud: string | string[];
    exp: number;
    iat: number;
    email: string;
    name?: string;
    common_name?: string;
}

interface AccessEnv {
    ACCESS_TEAM_DOMAIN: string;
    ACCESS_AUDIENCE: string;
    ADMIN_EMAIL: string;
    KV: KVNamespace;
}

interface AccessJwk {
    kid: string;
    kty: 'RSA';
    alg?: 'RS256';
    use?: string;
    e: string;
    n: string;
    [key: string]: unknown;
}

const ACCESS_CERTS_CACHE_KEY = 'access:certs';
const ACCESS_CERTS_CACHE_TTL = 24 * 60 * 60;

// 验证签名、issuer、audience、过期时间和管理员邮箱白名单。
export async function verifyAccessJWT(
    request: Request,
    env: AccessEnv
): Promise<string> {
    const authHeader = request.headers.get('Authorization');
    if (!authHeader?.startsWith('Bearer ')) {
        throw new AppError('UNAUTHORIZED', 'Missing or invalid authorization header', 401);
    }

    const token = authHeader.slice(7);
    const parts = token.split('.');
    if (parts.length !== 3) {
        throw new AppError('UNAUTHORIZED', 'Invalid token format', 401);
    }

    let header: { kid?: string; alg?: string };
    let payload: AccessPayload;
    try {
        header = JSON.parse(decodeBase64Url(parts[0])) as { kid?: string; alg?: string };
        payload = JSON.parse(decodeBase64Url(parts[1])) as AccessPayload;
    } catch {
        throw new AppError('UNAUTHORIZED', 'Invalid token encoding', 401);
    }

    if (header.alg !== 'RS256' || !header.kid) {
        throw new AppError('UNAUTHORIZED', 'Invalid token algorithm', 401);
    }

    let keys = await getCachedKeys(env);
    let key = keys.find(candidate => candidate.kid === header.kid);
    if (!key) {
        keys = await refreshKeysCache(env);
        key = keys.find(candidate => candidate.kid === header.kid);
    }
    if (!key) {
        throw new AppError('UNAUTHORIZED', 'Invalid token key ID', 401);
    }

    let publicKey: CryptoKey;
    try {
        // Cloudflare Access 使用 RSA-SHA256，不是 ECDSA/P-256。
        publicKey = await crypto.subtle.importKey(
            'jwk',
            key,
            { name: 'RSASSA-PKCS1-v1_5', hash: 'SHA-256' },
            false,
            ['verify']
        );
    } catch {
        throw new AppError('UNAUTHORIZED', 'Invalid Access certificate', 401);
    }

    let valid = false;
    try {
        valid = await crypto.subtle.verify(
            { name: 'RSASSA-PKCS1-v1_5', hash: 'SHA-256' },
            publicKey,
            decodeBase64UrlBytes(parts[2]),
            new TextEncoder().encode(`${parts[0]}.${parts[1]}`)
        );
    } catch {
        throw new AppError('UNAUTHORIZED', 'Invalid token signature', 401);
    }
    if (!valid) {
        throw new AppError('UNAUTHORIZED', 'Invalid token signature', 401);
    }

    const teamDomain = normalizeTeamDomain(env.ACCESS_TEAM_DOMAIN);
    if (!teamDomain) {
        throw new AppError('INTERNAL_ERROR', 'Access team domain is not configured', 500);
    }
    const expectedIss = `https://${teamDomain}`;
    if (payload.iss !== expectedIss) {
        throw new AppError('UNAUTHORIZED', 'Invalid token issuer', 401);
    }
    const audiences = Array.isArray(payload.aud) ? payload.aud : [payload.aud];
    if (!audiences.includes((env.ACCESS_AUDIENCE || '').trim())) {
        throw new AppError('UNAUTHORIZED', 'Invalid token audience', 401);
    }
    if (!Number.isFinite(payload.exp) || payload.exp < Math.floor(Date.now() / 1000)) {
        throw new AppError('UNAUTHORIZED', 'Token has expired', 401);
    }
    if (!payload.email) {
        throw new AppError('UNAUTHORIZED', 'Email not found in token', 401);
    }
    if (payload.email !== (env.ADMIN_EMAIL || '').trim()) {
        throw new AppError('FORBIDDEN', 'Email not authorized', 403);
    }

    return payload.email;
}

async function getCachedKeys(env: AccessEnv): Promise<AccessJwk[]> {
    try {
        const cached = await env.KV.get<unknown>(ACCESS_CERTS_CACHE_KEY, 'json');
        if (!Array.isArray(cached)) return [];
        return cached.filter(isAccessJwk);
    } catch {
        return [];
    }
}

async function refreshKeysCache(env: AccessEnv): Promise<AccessJwk[]> {
    const teamDomain = normalizeTeamDomain(env.ACCESS_TEAM_DOMAIN);
    if (!teamDomain) {
        throw new AppError('INTERNAL_ERROR', 'Access team domain is not configured', 500);
    }

    const certsUrl = `https://${teamDomain}/cdn-cgi/access/certs`;
    let response: Response;
    try {
        response = await fetch(certsUrl);
    } catch {
        throw new AppError('ACCESS_CERTS_UNAVAILABLE', 'Failed to fetch Access certificates', 503);
    }
    if (!response.ok) {
        throw new AppError('ACCESS_CERTS_UNAVAILABLE', 'Failed to fetch Access certificates', 503);
    }

    let data: { keys?: unknown };
    try {
        data = await response.json() as { keys?: unknown };
    } catch {
        throw new AppError('ACCESS_CERTS_UNAVAILABLE', 'Invalid Access certificates response', 503);
    }
    const keys = Array.isArray(data.keys) ? data.keys.filter(isAccessJwk) : [];
    if (keys.length === 0) {
        throw new AppError('INTERNAL_ERROR', 'No valid Access certificates', 500);
    }

    try {
        await env.KV.put(ACCESS_CERTS_CACHE_KEY, JSON.stringify(keys), {
            expirationTtl: ACCESS_CERTS_CACHE_TTL,
        });
    } catch {
        // KV 暂时不可用时仍可使用本次拉取的证书完成验证。
    }
    return keys;
}

function normalizeTeamDomain(value: string): string {
    if (typeof value !== 'string') return '';

    const domain = value.trim()
        .replace(/^https?:\/\//i, '')
        .split('/')[0]
        .replace(/\.+$/, '');

    return domain && domain.includes('.') ? domain : (domain ? `${domain}.cloudflareaccess.com` : '');
}

function isAccessJwk(value: unknown): value is AccessJwk {
    if (!value || typeof value !== 'object') return false;
    const key = value as Partial<AccessJwk>;
    return key.kty === 'RSA' && typeof key.kid === 'string' && typeof key.n === 'string' && typeof key.e === 'string';
}

function decodeBase64Url(value: string): string {
    return new TextDecoder().decode(decodeBase64UrlBytes(value));
}

function decodeBase64UrlBytes(value: string): Uint8Array {
    const base64 = value.replace(/-/g, '+').replace(/_/g, '/')
        .padEnd(Math.ceil(value.length / 4) * 4, '=');
    return Uint8Array.from(atob(base64), char => char.charCodeAt(0));
}
