// POST /api/auth/check — 每日在线校验

import { AppError, successResponse, now } from '../util/errors';
import { firstRow } from '../util/db';

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
    env: { DB: D1Database },
    requestId: string,
    payload: { sub: string; device: string }
): Promise<Response> {
    const currentTime = now();

    // 查询朋友 + 设备状态
    const result = await firstRow<{
        friend_id: string; nickname: string; friend_status: string;
        device_id: string; device_status: string;
    }>(env.DB.prepare(`
        SELECT f.id as friend_id, f.nickname, f.status as friend_status,
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

    // 更新设备最后活动时间
    await env.DB.prepare(`
        UPDATE devices SET last_seen_at = ? WHERE id = ?
    `).bind(currentTime, payload.device).run();

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
