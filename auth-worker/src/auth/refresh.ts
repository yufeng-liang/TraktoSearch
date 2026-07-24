// POST /api/auth/refresh — 刷新令牌轮换 + 重放检测

import { AppError, successResponse, now } from '../util/errors';
import { sha256, generateSecureToken, generateId } from '../util/crypto';
import { signAccessToken } from '../util/jwt';
import { firstRow } from '../util/db';

interface RefreshRequest {
    deviceId: string;
    refreshToken: string;
    nonce: string;
    signature: string; // 客户端用 Keystore 私钥签名 challenge nonce
}

interface RefreshResponse {
    accessToken: string;
    refreshToken: string;
    accessExpiresAt: number;
    refreshExpiresAt: number;
}

export async function handleRefresh(
    request: Request,
    env: { DB: D1Database; KV: KVNamespace; JWT_SIGNING_KEY: string },
    requestId: string
): Promise<Response> {
    const body = await request.json() as RefreshRequest;

    if (!body.deviceId || !body.refreshToken || !body.nonce || !body.signature) {
        throw new AppError('INVALID_REQUEST', 'deviceId, refreshToken, nonce, signature are required', 400);
    }

    const currentTime = now();
    const tokenHash = await sha256(body.refreshToken);
    const nonceHash = await sha256(body.nonce);

    // 验证 nonce 存在且未过期
    const nonceDeviceId = await env.KV.get(`challenge:${nonceHash}`);
    if (!nonceDeviceId || nonceDeviceId !== body.deviceId) {
        await logSecurityEvent(env, requestId, 'REFRESH', body.deviceId, 'FAILURE', 'INVALID_SIGNATURE', null, 'refresh:invalid_challenge');
        throw new AppError('INVALID_SIGNATURE', 'Invalid or expired challenge', 400);
    }
    // 消费 nonce（一次性）
    await env.KV.delete(`challenge:${nonceHash}`);

    // 查找刷新会话
    const session = await firstRow<{
        id: string; device_id: string; expires_at: number; revoked_at: number | null;
        token_hash: string; device_status: string; public_key: string;
        friend_id: string; friend_status: string; friend_expires_at: number | null;
    }>(env.DB.prepare(`
        SELECT rs.id, rs.device_id, rs.expires_at, rs.revoked_at, rs.token_hash,
               d.status as device_status, d.public_key, d.friend_id, f.status as friend_status,
               f.expires_at as friend_expires_at
        FROM refresh_sessions rs
        JOIN devices d ON rs.device_id = d.id
        JOIN friends f ON d.friend_id = f.id
        WHERE rs.device_id = ? AND rs.token_hash = ?
    `).bind(body.deviceId, tokenHash));

    if (!session) {
        // 令牌不存在 — 可能是重放攻击
        await logSecurityEvent(env, requestId, 'REFRESH_REPLAY', body.deviceId, 'FAILURE', 'TOKEN_NOT_FOUND', null, 'refresh:token_not_found');
        throw new AppError('INVALID_TOKEN', 'Invalid refresh token', 401);
    }

    // 检查设备/朋友状态
    if (session.device_status !== 'ACTIVE' || session.friend_status !== 'ACTIVE') {
        await logSecurityEvent(env, requestId, 'REFRESH', body.deviceId, 'FAILURE', 'DEVICE_REVOKED', session.friend_id, 'refresh:device_or_friend_revoked');
        throw new AppError('DEVICE_REVOKED', 'Device or friend is disabled', 403);
    }
    if (session.friend_expires_at !== null && session.friend_expires_at < currentTime) {
        await logSecurityEvent(env, requestId, 'REFRESH', body.deviceId, 'FAILURE', 'FRIEND_EXPIRED', session.friend_id, 'refresh:friend_expired');
        throw new AppError('FRIEND_EXPIRED', 'Friend account has expired', 403);
    }

    // 检查会话是否已撤销
    if (session.revoked_at !== null) {
        // 令牌已被撤销但又被使用 → 重放攻击
        await revokeAllDeviceSessions(env, body.deviceId);
        await logSecurityEvent(env, requestId, 'REFRESH_REPLAY', body.deviceId, 'FAILURE', 'TOKEN_REPLAY', null, 'refresh:replay_revoked_all');
        throw new AppError('REFRESH_REPLAY', 'Token replay detected, all sessions revoked', 401);
    }

    // 检查过期
    if (session.expires_at < currentTime) {
        await logSecurityEvent(env, requestId, 'REFRESH', body.deviceId, 'FAILURE', 'TOKEN_EXPIRED', session.friend_id, 'refresh:expired');
        throw new AppError('TOKEN_EXPIRED', 'Refresh token has expired', 401);
    }

    // 使用客户端 Keystore 签名校验 nonce，防止刷新请求被伪造。

    // 轮换：撤销旧会话 + 创建新会话
    if (!(await verifyClientSignature(session.public_key, body.signature, body.nonce))) {
        await logSecurityEvent(env, requestId, 'REFRESH', body.deviceId, 'FAILURE', 'INVALID_SIGNATURE', session.friend_id, 'refresh:invalid_signature');
        throw new AppError('INVALID_SIGNATURE', 'Invalid client signature', 400);
    }

    const newRefreshToken = generateSecureToken(32);
    const newRefreshTokenHash = await sha256(newRefreshToken);
    const newSessionId = generateId();
    const accessExpiresIn = 900; // 15 分钟
    const refreshExpiresIn = 30 * 24 * 60 * 60; // 30 天
    const accessExpiresAt = currentTime + accessExpiresIn;
    const refreshExpiresAt = currentTime + refreshExpiresIn;

    const statements = [
        // 撤销旧会话
        env.DB.prepare(`UPDATE refresh_sessions SET revoked_at = ? WHERE id = ?`)
            .bind(currentTime, session.id),
        // 创建新会话
        env.DB.prepare(`
            INSERT INTO refresh_sessions (id, device_id, token_hash, expires_at, created_at)
            VALUES (?, ?, ?, ?, ?)
        `).bind(newSessionId, body.deviceId, newRefreshTokenHash, refreshExpiresAt, currentTime),
        // 更新设备最后活动时间
        env.DB.prepare(`UPDATE devices SET last_seen_at = ? WHERE id = ?`)
            .bind(currentTime, body.deviceId),
    ];

    await env.DB.batch(statements);

    // 签发新访问 JWT
    const accessToken = await signAccessToken(
        env.JWT_SIGNING_KEY,
        session.friend_id,
        body.deviceId,
        ['api'],
        accessExpiresIn
    );

    await logSecurityEvent(env, requestId, 'REFRESH', body.deviceId, 'SUCCESS', null, session.friend_id, 'refresh:rotated');

    const response: RefreshResponse = {
        accessToken,
        refreshToken: newRefreshToken,
        accessExpiresAt,
        refreshExpiresAt,
    };

    return successResponse(response, requestId);
}

// 撤销设备全部刷新会话（重放攻击响应）
async function revokeAllDeviceSessions(env: { DB: D1Database }, deviceId: string): Promise<void> {
    const currentTime = now();
    await env.DB.prepare(`
        UPDATE refresh_sessions SET revoked_at = ?
        WHERE device_id = ? AND revoked_at IS NULL
    `).bind(currentTime, deviceId).run();
}

// 记录安全事件
async function logSecurityEvent(
    env: { DB: D1Database },
    requestId: string,
    eventType: string,
    deviceId: string,
    result: string,
    errorCode: string | null,
    friendId: string | null = null,
    detail: string | null = null,
): Promise<void> {
    await env.DB.prepare(`
        INSERT INTO audit_logs (event_type, friend_id, device_id, request_id, result, error_code, detail, created_at)
        VALUES (?, ?, ?, ?, ?, ?, ?, ?)
    `).bind(eventType, friendId, deviceId, requestId, result, errorCode, detail, now()).run();
}

async function verifyClientSignature(publicKeyBase64: string, signatureBase64: string, nonce: string): Promise<boolean> {
    try {
        const publicKey = await crypto.subtle.importKey(
            'spki',
            decodeBase64(publicKeyBase64),
            { name: 'ECDSA', namedCurve: 'P-256' },
            false,
            ['verify']
        );
        const derSignature = decodeBase64(signatureBase64);
        const rawSignature = derSignature.length === 64 ? derSignature : derToP1363(derSignature);
        if (rawSignature.length !== 64) return false;
        return await crypto.subtle.verify(
            { name: 'ECDSA', hash: 'SHA-256' },
            publicKey,
            rawSignature,
            new TextEncoder().encode(nonce)
        );
    } catch {
        return false;
    }
}

function decodeBase64(value: string): Uint8Array {
    const normalized = value.replace(/-/g, '+').replace(/_/g, '/').padEnd(Math.ceil(value.length / 4) * 4, '=');
    const binary = atob(normalized);
    return Uint8Array.from(binary, char => char.charCodeAt(0));
}

function derToP1363(der: Uint8Array): Uint8Array {
    let offset = 0;
    if (der[offset++] !== 0x30) return new Uint8Array();
    const sequenceLength = readDerLength(der, offset);
    offset += sequenceLength.bytes;
    if (sequenceLength.value !== der.length - offset) return new Uint8Array();
    const r = readDerInteger(der, offset);
    if (!r) return new Uint8Array();
    const s = readDerInteger(der, r.nextOffset);
    if (!s || s.nextOffset !== der.length) return new Uint8Array();
    const result = new Uint8Array(64);
    if (!copyScalar(r.value, result, 0) || !copyScalar(s.value, result, 32)) return new Uint8Array();
    return result;
}

function readDerLength(bytes: Uint8Array, offset: number): { value: number; bytes: number } {
    const first = bytes[offset];
    if (first === undefined) return { value: -1, bytes: 0 };
    if ((first & 0x80) === 0) return { value: first, bytes: 1 };
    const count = first & 0x7f;
    if (count === 0 || count > 2 || offset + count >= bytes.length) return { value: -1, bytes: 0 };
    let value = 0;
    for (let i = 1; i <= count; i++) value = (value << 8) | bytes[offset + i];
    return { value, bytes: count + 1 };
}

function readDerInteger(bytes: Uint8Array, offset: number): { value: Uint8Array; nextOffset: number } | null {
    if (bytes[offset++] !== 0x02) return null;
    const length = readDerLength(bytes, offset);
    if (length.value < 0) return null;
    offset += length.bytes;
    const end = offset + length.value;
    if (end > bytes.length || length.value === 0) return null;
    return { value: bytes.slice(offset, end), nextOffset: end };
}

function copyScalar(value: Uint8Array, output: Uint8Array, offset: number): boolean {
    let start = 0;
    while (start < value.length - 1 && value[start] === 0) start++;
    const scalar = value.slice(start);
    if (scalar.length > 32) return false;
    output.set(scalar, offset + 32 - scalar.length);
    return true;
}
