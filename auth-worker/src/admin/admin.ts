// Admin API — 朋友/设备/邀请码/审计日志 CRUD

import { AppError, successResponse, now } from '../util/errors';
import { generateId, sha256, generateInviteCode } from '../util/crypto';

// === 朋友管理 ===

// GET /admin/friends — 朋友列表
export async function listFriends(env: { DB: D1Database }, requestId: string): Promise<Response> {
    const { results } = await env.DB.prepare(`
        SELECT f.id, f.nickname, f.note, f.status, f.max_devices, f.expires_at,
               f.created_at, f.updated_at,
               (SELECT COUNT(*) FROM devices d WHERE d.friend_id = f.id AND d.status = 'ACTIVE') as active_devices,
               (SELECT MAX(d.last_seen_at) FROM devices d WHERE d.friend_id = f.id) as last_seen
        FROM friends f
        ORDER BY f.created_at DESC
    `).all();

    return successResponse({ friends: results }, requestId);
}

// POST /admin/friends — 创建朋友
export async function createFriend(
    request: Request,
    env: { DB: D1Database },
    requestId: string
): Promise<Response> {
    const body = await request.json() as {
        nickname: string;
        note?: string;
        maxDevices?: number;
        expiresAt?: number | null;
    };

    if (!body.nickname) {
        throw new AppError('INVALID_REQUEST', 'nickname is required', 400);
    }

    const id = generateId();
    const currentTime = now();

    await env.DB.prepare(`
        INSERT INTO friends (id, nickname, note, status, max_devices, expires_at, created_at, updated_at)
        VALUES (?, ?, ?, 'ACTIVE', ?, ?, ?, ?)
    `).bind(id, body.nickname, body.note || null, body.maxDevices || 2, body.expiresAt || null, currentTime, currentTime).run();

    await env.DB.prepare(`
        INSERT INTO audit_logs (event_type, friend_id, request_id, result, created_at)
        VALUES ('FRIEND_CREATE', ?, ?, 'SUCCESS', ?)
    `).bind(id, requestId, currentTime).run();

    return successResponse({ id, nickname: body.nickname, status: 'ACTIVE' }, requestId);
}

// PATCH /admin/friends/:id — 更新朋友
export async function updateFriend(
    request: Request,
    env: { DB: D1Database },
    requestId: string,
    friendId: string
): Promise<Response> {
    const body = await request.json() as {
        nickname?: string;
        note?: string;
        maxDevices?: number;
        expiresAt?: number | null;
    };

    const updates: string[] = [];
    const values: (string | number | null)[] = [];

    if (body.nickname !== undefined) { updates.push('nickname = ?'); values.push(body.nickname); }
    if (body.note !== undefined) { updates.push('note = ?'); values.push(body.note); }
    if (body.maxDevices !== undefined) { updates.push('max_devices = ?'); values.push(body.maxDevices); }
    if (body.expiresAt !== undefined) { updates.push('expires_at = ?'); values.push(body.expiresAt); }

    if (updates.length === 0) {
        throw new AppError('INVALID_REQUEST', 'No fields to update', 400);
    }

    updates.push('updated_at = ?');
    values.push(now());
    values.push(friendId);

    await env.DB.prepare(`
        UPDATE friends SET ${updates.join(', ')} WHERE id = ?
    `).bind(...values).run();

    return successResponse({ id: friendId, updated: true }, requestId);
}

// POST /admin/friends/:id/disable — 禁用朋友
export async function disableFriend(
    env: { DB: D1Database },
    requestId: string,
    friendId: string
): Promise<Response> {
    const currentTime = now();

    // 禁用朋友 + 撤销全部设备 + 撤销全部刷新会话
    const statements = [
        env.DB.prepare(`UPDATE friends SET status = 'DISABLED', updated_at = ? WHERE id = ?`)
            .bind(currentTime, friendId),
        env.DB.prepare(`UPDATE devices SET status = 'REVOKED', revoked_at = ? WHERE friend_id = ? AND status = 'ACTIVE'`)
            .bind(currentTime, friendId),
        env.DB.prepare(`
            UPDATE refresh_sessions SET revoked_at = ?
            WHERE device_id IN (SELECT id FROM devices WHERE friend_id = ?) AND revoked_at IS NULL
        `).bind(currentTime, friendId),
    ];

    await env.DB.batch(statements);

    await env.DB.prepare(`
        INSERT INTO audit_logs (event_type, friend_id, request_id, result, created_at)
        VALUES ('FRIEND_DISABLE', ?, ?, 'SUCCESS', ?)
    `).bind(friendId, requestId, currentTime).run();

    return successResponse({ id: friendId, status: 'DISABLED' }, requestId);
}

// GET /admin/friends/:id/devices — 设备列表
export async function listDevices(
    env: { DB: D1Database },
    requestId: string,
    friendId: string
): Promise<Response> {
    const { results } = await env.DB.prepare(`
        SELECT id, device_name, status, app_version, last_seen_at, activated_at, revoked_at
        FROM devices
        WHERE friend_id = ?
        ORDER BY activated_at DESC
    `).bind(friendId).all();

    return successResponse({ friendId, devices: results }, requestId);
}

// === 设备管理 ===

// POST /admin/devices/:id/revoke — 撤销设备
export async function revokeDevice(
    env: { DB: D1Database },
    requestId: string,
    deviceId: string
): Promise<Response> {
    const currentTime = now();

    const device = await env.DB.prepare(`
        SELECT id, status FROM devices WHERE id = ?
    `).bind(deviceId).first<{ id: string; status: string }>();

    if (!device) {
        throw new AppError('DEVICE_NOT_FOUND', 'Device not found', 404);
    }
    if (device.status === 'REVOKED') {
        throw new AppError('DEVICE_REVOKED', 'Device already revoked', 400);
    }

    const statements = [
        env.DB.prepare(`UPDATE devices SET status = 'REVOKED', revoked_at = ? WHERE id = ?`)
            .bind(currentTime, deviceId),
        env.DB.prepare(`UPDATE refresh_sessions SET revoked_at = ? WHERE device_id = ? AND revoked_at IS NULL`)
            .bind(currentTime, deviceId),
    ];

    await env.DB.batch(statements);

    await env.DB.prepare(`
        INSERT INTO audit_logs (event_type, device_id, request_id, result, created_at)
        VALUES ('DEVICE_REVOKE', ?, ?, 'SUCCESS', ?)
    `).bind(deviceId, requestId, currentTime).run();

    return successResponse({ id: deviceId, status: 'REVOKED' }, requestId);
}

// === 邀请码管理 ===

// POST /admin/friends/:id/invites — 创建邀请码
export async function createInvite(
    request: Request,
    env: { DB: D1Database },
    requestId: string,
    friendId: string
): Promise<Response> {
    const body = await request.json() as {
        kind?: 'ACTIVATION' | 'MIGRATION';
        expiresInDays?: number;
    };

    // 验证朋友存在且活跃
    const friend = await env.DB.prepare(`
        SELECT id, status FROM friends WHERE id = ?
    `).bind(friendId).first<{ id: string; status: string }>();

    if (!friend) {
        throw new AppError('NOT_FOUND', 'Friend not found', 404);
    }
    if (friend.status !== 'ACTIVE') {
        throw new AppError('FRIEND_DISABLED', 'Friend is disabled', 400);
    }

    const kind = body.kind || 'ACTIVATION';
    const expiresInDays = body.expiresInDays || 7;
    const code = generateInviteCode();
    const codeHash = await sha256(code);
    const id = generateId();
    const currentTime = now();
    const expiresAt = currentTime + expiresInDays * 24 * 60 * 60;

    await env.DB.prepare(`
        INSERT INTO invites (id, friend_id, kind, code_hash, expires_at, created_at)
        VALUES (?, ?, ?, ?, ?, ?)
    `).bind(id, friendId, kind, codeHash, expiresAt, currentTime).run();

    await env.DB.prepare(`
        INSERT INTO audit_logs (event_type, friend_id, request_id, result, created_at)
        VALUES ('INVITE_CREATE', ?, ?, 'SUCCESS', ?)
    `).bind(friendId, requestId, currentTime).run();

    // 注意：明文邀请码只在创建响应中返回一次
    return successResponse({
        id,
        inviteCode: code, // 仅此一次
        kind,
        expiresAt,
    }, requestId);
}

// POST /admin/invites/:id/revoke — 撤销邀请码
export async function revokeInvite(
    env: { DB: D1Database },
    requestId: string,
    inviteId: string
): Promise<Response> {
    const currentTime = now();

    const invite = await env.DB.prepare(`
        SELECT id, used_at, revoked_at FROM invites WHERE id = ?
    `).bind(inviteId).first<{ id: string; used_at: number | null; revoked_at: number | null }>();

    if (!invite) {
        throw new AppError('NOT_FOUND', 'Invite not found', 404);
    }
    if (invite.used_at !== null) {
        throw new AppError('INVITE_ALREADY_USED', 'Invite already used', 400);
    }
    if (invite.revoked_at !== null) {
        throw new AppError('INVITE_REVOKED', 'Invite already revoked', 400);
    }

    await env.DB.prepare(`
        UPDATE invites SET revoked_at = ? WHERE id = ?
    `).bind(currentTime, inviteId).run();

    await env.DB.prepare(`
        INSERT INTO audit_logs (event_type, friend_id, request_id, result, created_at)
        VALUES ('INVITE_REVOKE', (SELECT friend_id FROM invites WHERE id = ?), ?, 'SUCCESS', ?)
    `).bind(inviteId, requestId, currentTime).run();

    return successResponse({ id: inviteId, revoked: true }, requestId);
}

// === 审计日志 ===

// GET /admin/audit-logs — 审计日志查询
export async function listAuditLogs(
    request: Request,
    env: { DB: D1Database },
    requestId: string
): Promise<Response> {
    const url = new URL(request.url);
    const eventType = url.searchParams.get('eventType');
    const friendId = url.searchParams.get('friendId');
    const deviceId = url.searchParams.get('deviceId');
    const result = url.searchParams.get('result');
    const fromTime = url.searchParams.get('from');
    const toTime = url.searchParams.get('to');
    const limit = Math.min(parseInt(url.searchParams.get('limit') || '50'), 100);
    const offset = parseInt(url.searchParams.get('offset') || '0');

    const conditions: string[] = [];
    const values: (string | number)[] = [];

    if (eventType) { conditions.push('event_type = ?'); values.push(eventType); }
    if (friendId) { conditions.push('friend_id = ?'); values.push(friendId); }
    if (deviceId) { conditions.push('device_id = ?'); values.push(deviceId); }
    if (result) { conditions.push('result = ?'); values.push(result); }
    if (fromTime) { conditions.push('created_at >= ?'); values.push(parseInt(fromTime)); }
    if (toTime) { conditions.push('created_at <= ?'); values.push(parseInt(toTime)); }

    const whereClause = conditions.length > 0 ? `WHERE ${conditions.join(' AND ')}` : '';

    const { results } = await env.DB.prepare(`
        SELECT id, event_type, friend_id, device_id, request_id, result, error_code, created_at
        FROM audit_logs
        ${whereClause}
        ORDER BY created_at DESC
        LIMIT ? OFFSET ?
    `).bind(...values, limit, offset).all();

    return successResponse({ logs: results, limit, offset }, requestId);
}

// GET /admin/health — 网关健康状态
export async function healthCheck(env: { DB: D1Database }, requestId: string): Promise<Response> {
    const currentTime = now();

    // 检查 D1 连通性
    let dbHealthy = false;
    try {
        await env.DB.prepare('SELECT 1').first();
        dbHealthy = true;
    } catch {
        dbHealthy = false;
    }

    // 统计
    const stats = await env.DB.prepare(`
        SELECT
            (SELECT COUNT(*) FROM friends WHERE status = 'ACTIVE') as active_friends,
            (SELECT COUNT(*) FROM devices WHERE status = 'ACTIVE') as active_devices,
            (SELECT COUNT(*) FROM audit_logs WHERE result = 'FAILURE' AND created_at > ?) as recent_failures
    `).bind(currentTime - 86400).first<{ active_friends: number; active_devices: number; recent_failures: number }>();

    return successResponse({
        status: dbHealthy ? 'ok' : 'degraded',
        timestamp: currentTime,
        stats: stats || { active_friends: 0, active_devices: 0, recent_failures: 0 },
    }, requestId);
}
