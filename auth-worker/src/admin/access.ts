// Cloudflare Access JWT 验证

import { AppError } from '../util/errors';

interface AccessPayload {
    iss: string;      // https://<team>.cloudflareaccess.com
    sub: string;      // 用户 ID
    aud: string;      // audience (应用 ID)
    exp: number;      // 过期时间
    iat: number;      // 签发时间
    email: string;    // 管理员邮箱
    name?: string;
    common_name?: string;
}

// Access JWT 验证（验证签名 + issuer + audience + 邮箱白名单）
export async function verifyAccessJWT(
    request: Request,
    env: { ACCESS_TEAM_DOMAIN: string; ACCESS_AUDIENCE: string; ADMIN_EMAIL: string }
): Promise<AccessPayload> {
    const authHeader = request.headers.get('Authorization');
    if (!authHeader?.startsWith('Bearer ')) {
        throw new AppError('UNAUTHORIZED', 'Missing or invalid authorization header', 401);
    }
    const token = authHeader.slice(7);

    // 获取 Access 公钥（JWK）
    const certsUrl = `https://${env.ACCESS_TEAM_DOMAIN}/cdn-cgi/access/certs`;
    const certsResponse = await fetch(certsUrl);
    if (!certsResponse.ok) {
        throw new AppError('INTERNAL_ERROR', 'Failed to fetch Access certificates', 500);
    }
    const { keys } = await certsResponse.json() as { keys: JsonWebKey[] };

    // 解析 JWT header 获取 kid
    const parts = token.split('.');
    if (parts.length !== 3) {
        throw new AppError('UNAUTHORIZED', 'Invalid token format', 401);
    }
    const header = JSON.parse(atob(parts[0].replace(/-/g, '+').replace(/_/g, '/')));
    const key = keys.find(k => k.kid === header.kid);
    if (!key) {
        throw new AppError('UNAUTHORIZED', 'Invalid token key ID', 401);
    }

    // 导入公钥
    const publicKey = await crypto.subtle.importKey(
        'jwk',
        key,
        { name: 'ECDSA', namedCurve: 'P-256' },
        false,
        ['verify']
    );

    // 验证签名
    const encoder = new TextEncoder();
    const signingInput = encoder.encode(`${parts[0]}.${parts[1]}`);
    const signature = Uint8Array.from(atob(parts[2].replace(/-/g, '+').replace(/_/g, '/')), c => c.charCodeAt(0));

    const valid = await crypto.subtle.verify(
        { name: 'ECDSA', hash: 'SHA-256' },
        publicKey,
        signature,
        signingInput
    );
    if (!valid) {
        throw new AppError('UNAUTHORIZED', 'Invalid token signature', 401);
    }

    // 解析 payload
    const payload: AccessPayload = JSON.parse(
        new TextDecoder().decode(Uint8Array.from(atob(parts[1].replace(/-/g, '+').replace(/_/g, '/')), c => c.charCodeAt(0)))
    );

    // 验证 issuer
    const expectedIss = `https://${env.ACCESS_TEAM_DOMAIN}/`;
    if (payload.iss !== expectedIss) {
        throw new AppError('UNAUTHORIZED', 'Invalid token issuer', 401);
    }

    // 验证 audience
    if (payload.aud !== env.ACCESS_AUDIENCE) {
        throw new AppError('UNAUTHORIZED', 'Invalid token audience', 401);
    }

    // 验证过期
    const now = Math.floor(Date.now() / 1000);
    if (payload.exp < now) {
        throw new AppError('UNAUTHORIZED', 'Token has expired', 401);
    }

    // 验证管理员邮箱白名单
    if (payload.email !== env.ADMIN_EMAIL) {
        throw new AppError('FORBIDDEN', 'Email not authorized', 403);
    }

    return payload;
}

interface JsonWebKey {
    kid: string;
    kty: string;
    crv: string;
    x: string;
    y: string;
    [key: string]: unknown;
}
