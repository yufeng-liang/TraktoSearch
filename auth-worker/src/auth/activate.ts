// POST /api/auth/activate — 邀请码激活 + 设备绑定 + JWT 发放

import { AppError, successResponse, now } from '../util/errors';
import { sha256, generateSecureToken, generateId } from '../util/crypto';
import { signAccessToken } from '../util/jwt';

interface ActivateRequest {
    inviteCode: string;
    publicKey: string;
    deviceName?: string;
    appVersion?: string;
    packageName?: string;
}

interface ActivateResponse {
    deviceId: string;
    accessToken: string;
    refreshToken: string;
    accessExpiresAt: number;
    refreshExpiresAt: number;
    nextCheckAt: number;
}

export async function handleActivate(
    request: Request,
    env: { DB: D1Database; JWT_SIGNING_KEY: string },
    requestId: string
): Promise<Response> {
    const body = await request.json() as ActivateRequest;

    // 参数校验
    if (!body.inviteCode || !body.publicKey) {
        throw new AppError('INVALID_INVITE', 'inviteCode and publicKey are required', 400);
    }

    const codeHash = await sha256(body.inviteCode);
    const currentTime = now();

    // 查找有效邀请码
    const invite = await env.DB.prepare(`
        SELECT i.id, i.friend_id, i.kind, i.expires_at, i.used_at, i.revoked_at,
               f.status as friend_status, f.max_devices, f.expires_at as friend_expires_at
        FROM invites i
        JOIN friends f ON i.friend_id = f.id
        WHERE i.code_hash = ?
    `).bind(codeHash).first<{
        id: string; friend_id: string; kind: string; expires_at: number;
        used_at: number | null; revoked_at: number | null;
        friend_status: string; max_devices: number; friend_expires_at: number | null;
    }>();

    if (!invite) {
        throw new AppError('INVALID_INVITE', 'Invite code not found', 400);
    }

    // 检查邀请码状态
    if (invite.used_at !== null) {
        throw new AppError('INVITE_ALREADY_USED', 'Invite code already used', 400);
    }
    if (invite.revoked_at !== null) {
        throw new AppError('INVITE_REVOKED', 'Invite code has been revoked', 400);
    }
    if (invite.expires_at < currentTime) {
        throw new AppError('INVITE_EXPIRED', 'Invite code has expired', 400);
    }

    // 检查朋友状态
    if (invite.friend_status !== 'ACTIVE') {
        throw new AppError('FRIEND_DISABLED', 'Friend account is disabled', 400);
    }
    if (invite.friend_expires_at !== null && invite.friend_expires_at < currentTime) {
        throw new AppError('FRIEND_DISABLED', 'Friend account has expired', 400);
    }

    // 检查设备上限（事务中）
    const existingDevice = await env.DB.prepare(`
        SELECT id, friend_id, status
        FROM devices
        WHERE public_key = ?
    `).bind(body.publicKey).first<{ id: string; friend_id: string; status: string }>();

    if (invite.kind === 'MIGRATION' && !existingDevice) {
        throw new AppError('MIGRATION_DEVICE_NOT_FOUND', 'Migration invite requires an existing device', 400);
    }
    if (invite.kind === 'MIGRATION' && existingDevice && existingDevice.friend_id !== invite.friend_id) {
        throw new AppError('MIGRATION_DEVICE_MISMATCH', 'Device belongs to another friend', 400);
    }
    if (invite.kind !== 'MIGRATION' && existingDevice) {
        throw new AppError('DEVICE_ALREADY_BOUND', 'Device is already bound', 400);
    }

    const activeDeviceCount = await env.DB.prepare(`
        SELECT COUNT(*) as count FROM devices
        WHERE friend_id = ? AND status = 'ACTIVE' AND (? IS NULL OR id != ?)
    `).bind(invite.friend_id, existingDevice?.id || null, existingDevice?.id || null).first<{ count: number }>();

    if (activeDeviceCount && activeDeviceCount.count >= invite.max_devices) {
        throw new AppError('DEVICE_LIMIT_REACHED',
            `Device limit reached (max ${invite.max_devices})`, 400);
    }

    // 生成设备 ID 和刷新令牌
    const deviceId = existingDevice?.id || generateId();
    const refreshToken = generateSecureToken(32);
    const refreshTokenHash = await sha256(refreshToken);
    const refreshSessionId = generateId();

    // 有效期
    const accessExpiresIn = 900; // 15 分钟
    const refreshExpiresIn = 30 * 24 * 60 * 60; // 30 天
    const accessExpiresAt = currentTime + accessExpiresIn;
    const refreshExpiresAt = currentTime + refreshExpiresIn;
    const nextCheckAt = currentTime + 24 * 60 * 60; // 24 小时后校验

    // 事务：创建设备 + 创建刷新会话 + 标记邀请码已使用
    const statements = [
        existingDevice
            ? env.DB.prepare(`
            UPDATE devices
            SET friend_id = ?, device_name = ?, status = 'ACTIVE', app_version = ?, revoked_at = NULL
            WHERE id = ?
        `).bind(invite.friend_id, body.deviceName || null, body.appVersion || null, deviceId)
            : env.DB.prepare(`
            INSERT INTO devices (id, friend_id, public_key, device_name, status, app_version, activated_at)
            VALUES (?, ?, ?, ?, 'ACTIVE', ?, ?)
        `).bind(deviceId, invite.friend_id, body.publicKey, body.deviceName || null, body.appVersion || null, currentTime),

        env.DB.prepare(`
            UPDATE refresh_sessions SET revoked_at = ?
            WHERE device_id = ? AND revoked_at IS NULL
        `).bind(currentTime, deviceId),

        env.DB.prepare(`
            INSERT INTO refresh_sessions (id, device_id, token_hash, expires_at, created_at)
            VALUES (?, ?, ?, ?, ?)
        `).bind(refreshSessionId, deviceId, refreshTokenHash, refreshExpiresAt, currentTime),

        env.DB.prepare(`
            UPDATE invites SET used_at = ? WHERE id = ?
        `).bind(currentTime, invite.id),
    ];

    await env.DB.batch(statements);

    // 签发访问 JWT
    const accessToken = await signAccessToken(
        env.JWT_SIGNING_KEY,
        invite.friend_id,
        deviceId,
        ['api'],
        accessExpiresIn
    );

    // 写入审计日志
    await env.DB.prepare(`
        INSERT INTO audit_logs (event_type, friend_id, device_id, request_id, result, created_at)
        VALUES (?, ?, ?, ?, 'SUCCESS', ?)
    `).bind(invite.kind === 'MIGRATION' ? 'MIGRATE' : 'ACTIVATE', invite.friend_id, deviceId, requestId, currentTime).run();

    const response: ActivateResponse = {
        deviceId,
        accessToken,
        refreshToken,
        accessExpiresAt,
        refreshExpiresAt,
        nextCheckAt,
    };

    return successResponse(response, requestId);
}
