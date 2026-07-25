// Admin API — 朋友/设备/邀请码/审计日志 CRUD

import { AppError, successResponse, now } from '../util/errors';
import { generateId, sha256, generateInviteCode } from '../util/crypto';

// === 朋友管理 ===

// GET /admin/friends — 朋友列表
export async function listFriends(request: Request, env: { DB: D1Database }, requestId: string): Promise<Response> {
    const url = new URL(request.url);
    const query = (url.searchParams.get('q') || '').trim().slice(0, 64);
    const requestedStatus = (url.searchParams.get('status') || '').toUpperCase();
    if (requestedStatus && requestedStatus !== 'ACTIVE' && requestedStatus !== 'DISABLED') {
        throw new AppError('INVALID_REQUEST', 'Invalid friend status', 400);
    }
    const requestedLimit = Number.parseInt(url.searchParams.get('limit') || '50', 10);
    const requestedOffset = Number.parseInt(url.searchParams.get('offset') || '0', 10);
    const limit = Number.isInteger(requestedLimit) && requestedLimit > 0 ? Math.min(requestedLimit, 50) : 50;
    const offset = Number.isInteger(requestedOffset) && requestedOffset >= 0 ? requestedOffset : 0;
    const queryPattern = `%${query}%`;
    const conditions = `
        (? = '' OR f.status = ?)
        AND (? = '' OR LOWER(f.nickname) LIKE LOWER(?) OR LOWER(COALESCE(f.note, '')) LIKE LOWER(?))
    `;
    const values = [requestedStatus, requestedStatus, query, queryPattern, queryPattern];

    const { results } = await env.DB.prepare(`
        SELECT f.id, f.nickname, f.note, f.status, f.max_devices, f.expires_at,
               f.created_at, f.updated_at,
               (SELECT COUNT(*) FROM devices d WHERE d.friend_id = f.id AND d.status = 'ACTIVE') as active_devices,
               (SELECT MAX(d.last_seen_at) FROM devices d WHERE d.friend_id = f.id) as last_seen
        FROM friends f
        WHERE ${conditions}
        ORDER BY f.created_at DESC
        LIMIT ? OFFSET ?
    `).bind(...values, limit, offset).all();

    const countResult = await env.DB.prepare(`
        SELECT COUNT(*) AS count FROM friends f WHERE ${conditions}
    `).bind(...values).first<{ count: number }>();
    const total = Number(countResult?.count || 0);

    return successResponse({
        friends: results,
        limit,
        offset,
        total,
        hasMore: offset + results.length < total,
    }, requestId);
}

// GET /admin/friends/:id/detail - 按需返回朋友与设备详情
export async function getFriendDetail(
    env: { DB: D1Database },
    requestId: string,
    friendId: string,
): Promise<Response> {
    const friend = await env.DB.prepare(`
        SELECT f.id, f.nickname, f.note, f.status, f.max_devices, f.expires_at,
               (SELECT COUNT(*) FROM devices d WHERE d.friend_id = f.id AND d.status = 'ACTIVE') AS active_devices,
               (SELECT MAX(d.last_seen_at) FROM devices d WHERE d.friend_id = f.id) AS last_seen
        FROM friends f
        WHERE f.id = ?
    `).bind(friendId).first();
    if (!friend) throw new AppError('NOT_FOUND', 'Friend not found', 404);

    const { results: devices } = await env.DB.prepare(`
        SELECT id, device_name, status, app_version, last_seen_at, activated_at, revoked_at
        FROM devices
        WHERE friend_id = ?
        ORDER BY activated_at DESC
    `).bind(friendId).all();

    return successResponse({ friend, devices }, requestId);
}

// POST /admin/friends - 创建朋友
export async function createFriend(
    request: Request,
    env: { DB: D1Database },
    requestId: string
): Promise<Response> {
    const body = await readJson<{
        nickname: string;
        note?: string;
        maxDevices?: number;
    }>(request);

    const nickname = typeof body.nickname === 'string' ? body.nickname.trim() : '';
    const note = typeof body.note === 'string' ? body.note.trim() : '';
    const maxDevices = body.maxDevices ?? 2;

    if (!nickname) {
        throw new AppError('INVALID_REQUEST', 'nickname is required', 400);
    }
    if (nickname.length > 32 || note.length > 64) {
        throw new AppError('INVALID_REQUEST', 'nickname or note is too long', 400);
    }
    if (!Number.isInteger(maxDevices) || maxDevices < 1 || maxDevices > 10) {
        throw new AppError('INVALID_REQUEST', 'maxDevices must be an integer between 1 and 10', 400);
    }

    const id = generateId();
    const currentTime = now();

    await env.DB.prepare(`
        INSERT INTO friends (id, nickname, note, status, max_devices, expires_at, created_at, updated_at)
        VALUES (?, ?, ?, 'ACTIVE', ?, NULL, ?, ?)
    `).bind(id, nickname, note || null, maxDevices, currentTime, currentTime).run();

    await env.DB.prepare(`
        INSERT INTO audit_logs (event_type, friend_id, request_id, result, detail, created_at)
        VALUES ('FRIEND_CREATE', ?, ?, 'SUCCESS', 'friend_created', ?)
    `).bind(id, requestId, currentTime).run();

    return successResponse({ id, nickname, status: 'ACTIVE' }, requestId);
}

// PATCH /admin/friends/:id — 更新朋友
export async function updateFriend(
    request: Request,
    env: { DB: D1Database },
    requestId: string,
    friendId: string
): Promise<Response> {
    const existing = await env.DB.prepare(`
        SELECT id,
               (SELECT COUNT(*) FROM devices d WHERE d.friend_id = friends.id AND d.status = 'ACTIVE') AS active_devices
        FROM friends
        WHERE id = ?
    `).bind(friendId).first<{ id: string; active_devices: number }>();
    if (!existing) throw new AppError('NOT_FOUND', 'Friend not found', 404);

    const body = await readJson<{
        nickname?: string;
        note?: string;
        maxDevices?: number;
        expiresAt?: number | null;
    }>(request);

    const updates: string[] = [];
    const values: (string | number | null)[] = [];

    if (body.nickname !== undefined) {
        if (typeof body.nickname !== 'string') throw new AppError('INVALID_REQUEST', 'Invalid nickname', 400);
        const nickname = body.nickname.trim();
        if (!nickname || nickname.length > 32) throw new AppError('INVALID_REQUEST', 'Invalid nickname', 400);
        updates.push('nickname = ?');
        values.push(nickname);
    }
    if (body.note !== undefined) {
        if (typeof body.note !== 'string') throw new AppError('INVALID_REQUEST', 'Invalid note', 400);
        const note = body.note.trim();
        if (note.length > 64) throw new AppError('INVALID_REQUEST', 'note is too long', 400);
        updates.push('note = ?');
        values.push(note || null);
    }
    if (body.maxDevices !== undefined) {
        if (!Number.isInteger(body.maxDevices) || body.maxDevices < 1 || body.maxDevices > 10) {
            throw new AppError('INVALID_REQUEST', 'maxDevices must be an integer between 1 and 10', 400);
        }
        if (body.maxDevices < Number(existing.active_devices || 0)) {
            throw new AppError('MAX_DEVICES_BELOW_ACTIVE', 'maxDevices cannot be less than active devices', 400);
        }
        updates.push('max_devices = ?');
        values.push(body.maxDevices);
    }
    if (body.expiresAt !== undefined) {
        if (body.expiresAt !== null && (!Number.isInteger(body.expiresAt) || body.expiresAt <= 0)) {
            throw new AppError('INVALID_REQUEST', 'expiresAt must be a positive Unix timestamp or null', 400);
        }
        updates.push('expires_at = ?');
        values.push(body.expiresAt);
    }

    if (updates.length === 0) {
        throw new AppError('INVALID_REQUEST', 'No fields to update', 400);
    }

    const changedFields = updates.map((update) => update.split(' = ')[0]).join(',');
    updates.push('updated_at = ?');
    values.push(now());
    values.push(friendId);

    await env.DB.prepare(`
        UPDATE friends SET ${updates.join(', ')} WHERE id = ?
    `).bind(...values).run();

    await env.DB.prepare(`
        INSERT INTO audit_logs (event_type, friend_id, request_id, result, detail, created_at)
        VALUES ('FRIEND_UPDATE', ?, ?, 'SUCCESS', ?, ?)
    `).bind(friendId, requestId, `fields:${changedFields}`, now()).run();

    return successResponse({ id: friendId, updated: true }, requestId);
}

// POST /admin/friends/:id/disable — 禁用朋友
export async function disableFriend(
    env: { DB: D1Database },
    requestId: string,
    friendId: string
): Promise<Response> {
    const friend = await env.DB.prepare('SELECT id, status FROM friends WHERE id = ?').bind(friendId).first<{ id: string; status: string }>();
    if (!friend) throw new AppError('NOT_FOUND', 'Friend not found', 404);
    if (friend.status === 'DISABLED') throw new AppError('FRIEND_DISABLED', 'Friend already disabled', 400);

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
        INSERT INTO audit_logs (event_type, friend_id, request_id, result, detail, created_at)
        VALUES ('FRIEND_DISABLE', ?, ?, 'SUCCESS', 'friend_disabled', ?)
    `).bind(friendId, requestId, currentTime).run();

    return successResponse({ id: friendId, status: 'DISABLED' }, requestId);
}

// POST /admin/friends/:id/enable - 恢复朋友资格，不恢复已撤销设备与会话
export async function enableFriend(
    env: { DB: D1Database },
    requestId: string,
    friendId: string,
): Promise<Response> {
    const friend = await env.DB.prepare('SELECT id, status FROM friends WHERE id = ?')
        .bind(friendId)
        .first<{ id: string; status: string }>();
    if (!friend) throw new AppError('NOT_FOUND', 'Friend not found', 404);
    if (friend.status === 'ACTIVE') {
        return successResponse({ id: friendId, status: 'ACTIVE', changed: false }, requestId);
    }

    const currentTime = now();
    await env.DB.prepare(`
        UPDATE friends SET status = 'ACTIVE', updated_at = ?
        WHERE id = ? AND status = 'DISABLED'
    `).bind(currentTime, friendId).run();

    await env.DB.prepare(`
        INSERT INTO audit_logs (event_type, friend_id, request_id, result, detail, created_at)
        VALUES ('FRIEND_ENABLE', ?, ?, 'SUCCESS', 'friend_enabled', ?)
    `).bind(friendId, requestId, currentTime).run();

    return successResponse({ id: friendId, status: 'ACTIVE', changed: true }, requestId);
}

// GET /admin/friends/:id/devices — 设备列表
export async function listDevices(
    env: { DB: D1Database },
    requestId: string,
    friendId: string
): Promise<Response> {
    const friend = await env.DB.prepare('SELECT id FROM friends WHERE id = ?').bind(friendId).first<{ id: string }>();
    if (!friend) throw new AppError('NOT_FOUND', 'Friend not found', 404);

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
        INSERT INTO audit_logs (event_type, device_id, request_id, result, detail, created_at)
        VALUES ('DEVICE_REVOKE', ?, ?, 'SUCCESS', 'device_revoked', ?)
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
    const body = await readJson<{
        kind?: 'ACTIVATION' | 'MIGRATION';
        expiresInDays?: number;
    }>(request);

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
    const expiresInDays = body.expiresInDays ?? 7;
    if (kind !== 'ACTIVATION' && kind !== 'MIGRATION') {
        throw new AppError('INVALID_REQUEST', 'kind must be ACTIVATION or MIGRATION', 400);
    }
    if (kind === 'MIGRATION') {
        const activeDevice = await env.DB.prepare(`
            SELECT id FROM devices
            WHERE friend_id = ? AND status = 'ACTIVE'
            LIMIT 1
        `).bind(friendId).first<{ id: string }>();
        if (!activeDevice) {
            throw new AppError(
                'MIGRATION_DEVICE_NOT_FOUND',
                'Migration invite requires an existing active device',
                400
            );
        }
    }
    if (!Number.isInteger(expiresInDays) || expiresInDays < 1 || expiresInDays > 365) {
        throw new AppError('INVALID_REQUEST', 'expiresInDays must be an integer between 1 and 365', 400);
    }
    const code = generateInviteCode();
    const codeHash = await sha256(code);
    const codeMask = maskInviteCode(code);
    const id = generateId();
    const currentTime = now();
    const expiresAt = currentTime + expiresInDays * 24 * 60 * 60;

    await env.DB.prepare(`
        INSERT INTO invites (id, friend_id, kind, code_hash, code_mask, expires_at, created_at)
        VALUES (?, ?, ?, ?, ?, ?, ?)
    `).bind(id, friendId, kind, codeHash, codeMask, expiresAt, currentTime).run();

    await env.DB.prepare(`
        INSERT INTO audit_logs (event_type, friend_id, request_id, result, detail, created_at)
        VALUES ('INVITE_CREATE', ?, ?, 'SUCCESS', ?, ?)
    `).bind(friendId, requestId, `invite_kind:${kind};invite_id:${id};invite_mask:${codeMask}`, currentTime).run();

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
        SELECT id, used_at, revoked_at, expires_at FROM invites WHERE id = ?
    `).bind(inviteId).first<{ id: string; used_at: number | null; revoked_at: number | null; expires_at: number }>();

    if (!invite) {
        throw new AppError('NOT_FOUND', 'Invite not found', 404);
    }
    if (invite.used_at !== null) {
        throw new AppError('INVITE_ALREADY_USED', 'Invite already used', 400);
    }
    if (invite.revoked_at !== null) {
        throw new AppError('INVITE_REVOKED', 'Invite already revoked', 400);
    }
    if (invite.expires_at < currentTime) {
        throw new AppError('INVITE_EXPIRED', 'Invite already expired', 400);
    }

    const updateResult = await env.DB.prepare(`
        UPDATE invites
        SET revoked_at = ?
        WHERE id = ? AND used_at IS NULL AND revoked_at IS NULL AND expires_at >= ?
    `).bind(currentTime, inviteId, currentTime).run();
    if (updateResult.meta.changes !== 1) {
        const latest = await env.DB.prepare(`
            SELECT used_at, revoked_at, expires_at FROM invites WHERE id = ?
        `).bind(inviteId).first<{ used_at: number | null; revoked_at: number | null; expires_at: number }>();
        if (latest?.used_at !== null) throw new AppError('INVITE_ALREADY_USED', 'Invite already used', 400);
        if (latest?.revoked_at !== null) throw new AppError('INVITE_REVOKED', 'Invite already revoked', 400);
        throw new AppError('INVITE_EXPIRED', 'Invite already expired', 400);
    }

    await env.DB.prepare(`
        INSERT INTO audit_logs (event_type, friend_id, request_id, result, detail, created_at)
        VALUES ('INVITE_REVOKE', (SELECT friend_id FROM invites WHERE id = ?), ?, 'SUCCESS', 'invite_revoked', ?)
    `).bind(inviteId, requestId, currentTime).run();

    return successResponse({ id: inviteId, revoked: true }, requestId);
}

// GET /admin/friends/:id/invites - 邀请码历史及状态
export async function listInvites(
    request: Request,
    env: { DB: D1Database },
    requestId: string,
    friendId: string
): Promise<Response> {
    const friend = await env.DB.prepare('SELECT id FROM friends WHERE id = ?').bind(friendId).first<{ id: string }>();
    if (!friend) throw new AppError('NOT_FOUND', 'Friend not found', 404);

    const url = new URL(request.url);
    const requestedStatus = (url.searchParams.get('status') || 'ALL').toUpperCase();
    const statuses = new Set(['ALL', 'AVAILABLE', 'USED', 'EXPIRED', 'REVOKED']);
    if (!statuses.has(requestedStatus)) {
        throw new AppError('INVALID_REQUEST', 'Invalid invite status', 400);
    }
    const requestedLimit = Number.parseInt(url.searchParams.get('limit') || '50', 10);
    const requestedOffset = Number.parseInt(url.searchParams.get('offset') || '0', 10);
    const limit = Number.isInteger(requestedLimit) && requestedLimit > 0 ? Math.min(requestedLimit, 50) : 50;
    const offset = Number.isInteger(requestedOffset) && requestedOffset >= 0 ? requestedOffset : 0;
    const currentTime = now();

    const statusWhere = (status: string): { sql: string; values: (string | number)[] } => {
        switch (status) {
            case 'AVAILABLE': return { sql: 'revoked_at IS NULL AND used_at IS NULL AND expires_at >= ?', values: [currentTime] };
            case 'USED': return { sql: 'used_at IS NOT NULL', values: [] };
            case 'EXPIRED': return { sql: 'revoked_at IS NULL AND used_at IS NULL AND expires_at < ?', values: [currentTime] };
            case 'REVOKED': return { sql: 'revoked_at IS NOT NULL', values: [] };
            default: return { sql: '', values: [] };
        }
    };

    const selected = statusWhere(requestedStatus);
    const filteredClause = selected.sql ? `AND ${selected.sql}` : '';
    const listQuery = `
        SELECT id, kind, code_mask, expires_at, used_at, revoked_at, created_at,
               CASE
                   WHEN revoked_at IS NOT NULL THEN 'REVOKED'
                   WHEN used_at IS NOT NULL THEN 'USED'
                   WHEN expires_at < ? THEN 'EXPIRED'
                   ELSE 'AVAILABLE'
               END AS status
        FROM invites
        WHERE friend_id = ? ${filteredClause}
        ORDER BY created_at DESC
        LIMIT ? OFFSET ?
    `;
    const listValues: (string | number)[] = [currentTime, friendId, ...selected.values, limit, offset];
    const countQuery = `SELECT COUNT(*) AS count FROM invites WHERE friend_id = ? ${filteredClause}`;
    const countValues: (string | number)[] = [friendId, ...selected.values];
    const summaryQuery = `
        SELECT
            COUNT(*) AS total,
            SUM(CASE WHEN revoked_at IS NULL AND used_at IS NULL AND expires_at >= ? THEN 1 ELSE 0 END) AS available,
            SUM(CASE WHEN used_at IS NOT NULL THEN 1 ELSE 0 END) AS used,
            SUM(CASE WHEN revoked_at IS NULL AND used_at IS NULL AND expires_at < ? THEN 1 ELSE 0 END) AS expired,
            SUM(CASE WHEN revoked_at IS NOT NULL THEN 1 ELSE 0 END) AS revoked
        FROM invites WHERE friend_id = ?
    `;

    const [{ results }, countRow, summaryRow] = await Promise.all([
        env.DB.prepare(listQuery).bind(...listValues).all(),
        env.DB.prepare(countQuery).bind(...countValues).first<{ count: number }>(),
        env.DB.prepare(summaryQuery).bind(currentTime, currentTime, friendId).first<{
            total: number; available: number; used: number; expired: number; revoked: number;
        }>(),
    ]);

    const totalFiltered = Number(countRow?.count || 0);
    return successResponse({
        friendId,
        invites: results,
        summary: {
            total: Number(summaryRow?.total || 0),
            available: Number(summaryRow?.available || 0),
            used: Number(summaryRow?.used || 0),
            expired: Number(summaryRow?.expired || 0),
            revoked: Number(summaryRow?.revoked || 0),
        },
        limit,
        offset,
        hasMore: offset + results.length < totalFiltered,
    }, requestId);
}

function maskInviteCode(code: string): string {
    return code.length >= 8 ? `${code.slice(0, 4)}****${code.slice(-4)}` : '****';
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
    const requestedLimit = Number.parseInt(url.searchParams.get('limit') || '50', 10);
    const requestedOffset = Number.parseInt(url.searchParams.get('offset') || '0', 10);
    const limit = Number.isInteger(requestedLimit) && requestedLimit > 0 ? Math.min(requestedLimit, 100) : 50;
    const offset = Number.isInteger(requestedOffset) && requestedOffset >= 0 ? requestedOffset : 0;

    const conditions: string[] = [];
    const values: (string | number)[] = [];

    // 审计日志按产品约定只展示最近 90 天，避免历史数据无限膨胀影响后台查询。
    conditions.push('a.created_at >= ?');
    values.push(now() - 90 * 24 * 60 * 60);

    if (eventType) { conditions.push('a.event_type = ?'); values.push(eventType); }
    if (friendId) { conditions.push('COALESCE(a.friend_id, d.friend_id) = ?'); values.push(friendId); }
    if (deviceId) { conditions.push('a.device_id = ?'); values.push(deviceId); }
    if (result) { conditions.push('a.result = ?'); values.push(result); }
    const fromTimestamp = fromTime ? Number.parseInt(fromTime, 10) : NaN;
    const toTimestamp = toTime ? Number.parseInt(toTime, 10) : NaN;
    if (Number.isInteger(fromTimestamp)) { conditions.push('a.created_at >= ?'); values.push(fromTimestamp); }
    if (Number.isInteger(toTimestamp)) { conditions.push('a.created_at <= ?'); values.push(toTimestamp); }

    const whereClause = conditions.length > 0 ? `WHERE ${conditions.join(' AND ')}` : '';

    const { results } = await env.DB.prepare(`
        SELECT a.id, a.event_type, a.friend_id, a.device_id, a.request_id, a.result,
               a.error_code, a.detail, a.created_at,
               f.nickname AS friend_nickname,
               d.device_name AS device_name
        FROM audit_logs a
        LEFT JOIN devices d ON d.id = a.device_id
        LEFT JOIN friends f ON f.id = COALESCE(a.friend_id, d.friend_id)
        ${whereClause}
        ORDER BY a.created_at DESC, a.id DESC
        LIMIT ? OFFSET ?
    `).bind(...values, limit, offset).all();

    const countResult = await env.DB.prepare(`
        SELECT COUNT(*) AS count
        FROM audit_logs a
        LEFT JOIN devices d ON d.id = a.device_id
        ${whereClause}
    `).bind(...values).first<{ count: number }>();
    const total = Number(countResult?.count || 0);

    return successResponse({ logs: results, limit, offset, total, hasMore: offset + results.length < total }, requestId);
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

    // 统计查询失败时仍返回可读的降级状态，避免后台页面永远停在加载骨架。
    let stats: { active_friends: number; active_devices: number; recent_failures: number } | null = null;
    try {
        stats = await env.DB.prepare(`
            SELECT
                (SELECT COUNT(*) FROM friends WHERE status = 'ACTIVE') as active_friends,
                (SELECT COUNT(*) FROM devices WHERE status = 'ACTIVE') as active_devices,
                (SELECT COUNT(*) FROM audit_logs WHERE result = 'FAILURE' AND created_at > ?) as recent_failures
        `).bind(currentTime - 86400).first<{ active_friends: number; active_devices: number; recent_failures: number }>();
    } catch {
        dbHealthy = false;
    }

    return successResponse({
        status: dbHealthy ? 'ok' : 'degraded',
        timestamp: currentTime,
        stats: stats || { active_friends: 0, active_devices: 0, recent_failures: 0 },
    }, requestId);
}

async function readJson<T>(request: Request): Promise<T> {
    try {
        const body = await request.json();
        if (!body || typeof body !== 'object' || Array.isArray(body)) {
            throw new Error('body must be an object');
        }
        return body as T;
    } catch {
        throw new AppError('INVALID_REQUEST', 'Invalid JSON body', 400);
    }
}
