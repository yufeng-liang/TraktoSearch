// POST /api/auth/check — 每日在线校验

import { AppError, successResponse, now } from '../util/errors';
import { firstRow } from '../util/db';
import { hmacDeviceContinuityId } from '../util/crypto';

interface CheckRequest {
    androidId?: string;
}

interface CheckResponse {
    authorized: boolean;
    friendId: string;
    deviceId: string;
    nickname: string;
    deviceStatus: string;
    nextCheckAt: number;
    configVersion: number;
}

export async function handleCheck(
    request: Request,
    env: { DB: D1Database; DEVICE_RECOVERY_HMAC_KEY: string },
    requestId: string,
    payload: { sub: string; device: string }
): Promise<Response> {
    const currentTime = now();
    const body = await readOptionalBody(request);

    // 查询朋友 + 设备状态
    const result = await firstRow<{
        friend_id: string; nickname: string; friend_status: string;
        device_id: string; device_status: string;
        friend_expires_at: number | null;
        recovery_id_hmac: string | null;
    }>(env.DB.prepare(`
        SELECT f.id as friend_id, f.nickname, f.status as friend_status,
               f.expires_at as friend_expires_at,
               d.recovery_id_hmac,
               d.id as device_id, d.status as device_status
        FROM friends f
        JOIN devices d ON d.friend_id = f.id
        WHERE f.id = ? AND d.id = ?
    `).bind(payload.sub, payload.device));

    if (!result) {
        throw new AppError('UNAUTHORIZED', 'Friend or device not found', 401);
    }

    if (result.friend_status !== 'ACTIVE' || result.device_status !== 'ACTIVE') {
        throw new AppError('DEVICE_REVOKED', 'Account or device is disabled', 403);
    }
    if (result.friend_expires_at !== null && result.friend_expires_at < currentTime) {
        throw new AppError('FRIEND_EXPIRED', 'Friend account has expired', 403);
    }

    // 更新设备最后活动时间
    const statements = [env.DB.prepare(`
        UPDATE devices SET last_seen_at = ? WHERE id = ?
    `).bind(currentTime, payload.device)];
    if (body.androidId && !result.recovery_id_hmac) {
        const recoveryIdHmac = await hmacDeviceContinuityId(body.androidId, env.DEVICE_RECOVERY_HMAC_KEY);
        statements.push(env.DB.prepare(`
            UPDATE devices
            SET recovery_id_hmac = ?, recovery_id_version = 1, recovery_updated_at = ?
            WHERE id = ? AND recovery_id_hmac IS NULL
        `).bind(recoveryIdHmac, currentTime, payload.device));
    }
    // === IP 上报：从 Cloudflare request.cf 读取 ===
    const cf = (request as any).cf as {
        country?: string;
        region?: string;
        city?: string;
        latitude?: string;
        longitude?: string;
        asOrganization?: string;
    } | null;
    const clientIp = request.headers.get('CF-Connecting-IP') || '';
    if (clientIp && cf) {
        const geoParts = [cf.country, cf.region, cf.city].filter(Boolean);
        const ipGeo = geoParts.join(' ') || null;
        statements.push(
            env.DB.prepare(`
                INSERT INTO friend_ip_logs (friend_id, ip, country, region, city, latitude, longitude, isp, created_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
            `).bind(payload.sub, clientIp, cf.country || null, cf.region || null,
                    cf.city || null, cf.latitude || null, cf.longitude || null,
                    cf.asOrganization || null, currentTime),
            env.DB.prepare(`
                UPDATE friends SET last_ip = ?, last_ip_geo = ?, ip_updated_at = ? WHERE id = ?
            `).bind(clientIp, ipGeo, currentTime, payload.sub)
        );
    }
    await env.DB.batch(statements);

    const nextCheckAt = currentTime + 24 * 60 * 60; // 24 小时后

    const response: CheckResponse = {
        authorized: true,
        friendId: result.friend_id,
        deviceId: result.device_id,
        nickname: result.nickname,
        deviceStatus: result.device_status,
        nextCheckAt,
        configVersion: 1, // 预留配置版本号
    };

    return successResponse(response, requestId);
}

async function readOptionalBody(request: Request): Promise<CheckRequest> {
    const raw = await request.text();
    if (!raw.trim()) return {};
    try {
        return JSON.parse(raw) as CheckRequest;
    } catch {
        return {};
    }
}
