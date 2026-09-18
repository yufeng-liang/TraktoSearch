// JWT 签名与验证（HMAC-SHA256）

import { timingSafeEqual } from './crypto.ts';

const alg = { name: 'HMAC', hash: 'SHA-256' };

export interface JWTPayload {
    sub: string;        // 朋友 ID
    device: string;     // 设备 ID
    iat: number;        // 签发时间
    exp: number;        // 过期时间
    scope: string[];    // 权限范围
}

function base64urlEncode(data: Uint8Array): string {
    const base64 = btoa(String.fromCharCode(...data));
    return base64.replace(/\+/g, '-').replace(/\//g, '_').replace(/=+$/, '');
}

function base64urlDecode(str: string): Uint8Array {
    if (!/^[A-Za-z0-9_-]*$/.test(str) || str.length % 4 === 1) {
        throw new Error('Invalid base64url segment');
    }
    const base64 = str.replace(/-/g, '+').replace(/_/g, '/');
    const pad = base64.length % 4 === 0 ? '' : '='.repeat(4 - base64.length % 4);
    const binary = atob(base64 + pad);
    const decoded = Uint8Array.from(binary, c => c.charCodeAt(0));
    if (base64urlEncode(decoded) !== str) {
        throw new Error('Non-canonical base64url segment');
    }
    return decoded;
}

async function importKey(secret: string): Promise<CryptoKey> {
    const encoder = new TextEncoder();
    return crypto.subtle.importKey(
        'raw',
        encoder.encode(secret),
        alg,
        false,
        ['sign', 'verify']
    );
}

// 签发访问 JWT（15 分钟有效期）
export async function signAccessToken(
    secret: string,
    friendId: string,
    deviceId: string,
    scope: string[] = ['api'],
    expiresIn: number = 900 // 15 分钟
): Promise<string> {
    const header = { alg: 'HS256', typ: 'JWT' };
    const now = Math.floor(Date.now() / 1000);
    const payload: JWTPayload = {
        sub: friendId,
        device: deviceId,
        iat: now,
        exp: now + expiresIn,
        scope,
    };

    const encoder = new TextEncoder();
    const headerB64 = base64urlEncode(encoder.encode(JSON.stringify(header)));
    const payloadB64 = base64urlEncode(encoder.encode(JSON.stringify(payload)));
    const signingInput = `${headerB64}.${payloadB64}`;

    const key = await importKey(secret);
    const signature = await crypto.subtle.sign('HMAC', key, encoder.encode(signingInput));
    const signatureB64 = base64urlEncode(new Uint8Array(signature));

    return `${signingInput}.${signatureB64}`;
}

// 验证访问 JWT
export async function verifyAccessToken(
    secret: string,
    token: string
): Promise<JWTPayload | null> {
    try {
        const parts = token.split('.');
        if (parts.length !== 3) return null;

        const [headerB64, payloadB64, signatureB64] = parts;
        const signingInput = `${headerB64}.${payloadB64}`;

        // 验证签名
        const key = await importKey(secret);
        const signature = base64urlDecode(signatureB64);
        const encoder = new TextEncoder();
        const valid = await crypto.subtle.verify('HMAC', key, signature, encoder.encode(signingInput));
        if (!valid) return null;

        // 解析 payload
        const payload: JWTPayload = JSON.parse(new TextDecoder().decode(base64urlDecode(payloadB64)));

        // 检查过期
        const now = Math.floor(Date.now() / 1000);
        if (payload.exp < now) return null;

        return payload;
    } catch {
        return null;
    }
}
