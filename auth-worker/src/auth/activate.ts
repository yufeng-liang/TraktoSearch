// POST /api/auth/activate — 邀请码激活 + 设备绑定 + JWT 发放

import { AppError, successResponse, now } from '../util/errors';
import { sha256, generateSecureToken, generateId } from '../util/crypto';
import { signAccessToken } from '../util/jwt';
import { firstRow } from '../util/db';

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
        return logAndThrowActivationFailure(env, requestId, 'INVALID_INVITE', 'inviteCode and publicKey are required');
    }

    const codeHash = await sha256(body.inviteCode);
    const currentTime = now();

    // 查找有效邀请码
    const invite = await firstRow<{
        id: string; friend_id: string; kind: string; expires_at: number;
        used_at: number | null; revoked_at: number | null;
        code_mask: string | null;
        friend_status: string; max_devices: number; friend_expires_at: number | null;
    }>(env.DB.prepare(`
        SELECT i.id, i.friend_id, i.kind, i.expires_at, i.used_at, i.revoked_at, i.code_mask,
               f.status as friend_status, f.max_devices, f.expires_at as friend_expires_at
        FROM invites i
        JOIN friends f ON i.friend_id = f.id
        WHERE i.code_hash = ?
    `).bind(codeHash));

    if (!invite) {
        return logAndThrowActivationFailure(env, requestId, 'INVALID_INVITE', 'Invite code not found');
    }

    // 检查邀请码状态
    if (invite.used_at !== null) {
        return logAndThrowActivationFailure(env, requestId, 'INVITE_ALREADY_USED', 'Invite code already used', invite);
    }
    if (invite.revoked_at !== null) {
        return logAndThrowActivationFailure(env, requestId, 'INVITE_REVOKED', 'Invite code has been revoked', invite);
    }
    if (invite.expires_at < currentTime) {
        return logAndThrowActivationFailure(env, requestId, 'INVITE_EXPIRED', 'Invite code has expired', invite);
    }

    // 检查朋友状态
    if (invite.friend_status !== 'ACTIVE') {
        return logAndThrowActivationFailure(env, requestId, 'FRIEND_DISABLED', 'Friend account is disabled', invite);
    }
    if (invite.friend_expires_at !== null && invite.friend_expires_at < currentTime) {
        return logAndThrowActivationFailure(env, requestId, 'FRIEND_DISABLED', 'Friend account has expired', invite);
    }

    // 检查设备上限（事务中）
    const existingDevice = await firstRow<{ id: string; friend_id: string; status: string }>(env.DB.prepare(`
        SELECT id, friend_id, status
        FROM devices
        WHERE public_key = ?
    `).bind(body.publicKey));

    if (invite.kind === 'MIGRATION' && !existingDevice) {
        return logAndThrowActivationFailure(env, requestId, 'MIGRATION_DEVICE_NOT_FOUND', 'Migration invite requires an existing device', invite);
    }
    if (invite.kind === 'MIGRATION' && existingDevice && existingDevice.friend_id !== invite.friend_id) {
        return logAndThrowActivationFailure(env, requestId, 'MIGRATION_DEVICE_MISMATCH', 'Device belongs to another friend', invite, existingDevice.id);
    }
    if (invite.kind !== 'MIGRATION' && existingDevice) {
        return logAndThrowActivationFailure(env, requestId, 'DEVICE_ALREADY_BOUND', 'Device is already bound', invite, existingDevice.id);
    }

    const activeDeviceCount = await env.DB.prepare(`
        SELECT COUNT(*) as count FROM devices
        WHERE friend_id = ? AND status = 'ACTIVE' AND (? IS NULL OR id != ?)
    `).bind(invite.friend_id, existingDevice?.id || null, existingDevice?.id || null).first<{ count: number }>();

    if (activeDeviceCount && activeDeviceCount.count >= invite.max_devices) {
        return logAndThrowActivationFailure(
            env,
            requestId,
            'DEVICE_LIMIT_REACHED',
            `Device limit reached (max ${invite.max_devices})`,
            invite,
            existingDevice?.id,
        );
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

    // 先签发 JWT，再提交数据库批次；签名配置异常时不得消耗邀请码或修改设备状态。
    const accessToken = await signAccessToken(
        env.JWT_SIGNING_KEY,
        invite.friend_id,
        deviceId,
        ['api'],
        accessExpiresIn
    );

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

        // 将授权审计与邀请码消费放进同一批处理，避免出现“邀请码已使用但没有审计记录”。
        env.DB.prepare(`
            INSERT INTO audit_logs (event_type, friend_id, device_id, request_id, result, detail, created_at)
            VALUES (?, ?, ?, ?, 'SUCCESS', ?, ?)
        `).bind(
            invite.kind === 'MIGRATION' ? 'MIGRATE' : 'ACTIVATE',
            invite.friend_id,
            deviceId,
            requestId,
            `invite_kind:${invite.kind};invite_id:${invite.id};invite_mask:${invite.code_mask || ''}`,
            currentTime,
        ),
    ];

    await env.DB.batch(statements);

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

async function logAndThrowActivationFailure(
    env: { DB: D1Database },
    requestId: string,
    errorCode: string,
    message: string,
    invite?: { kind: string; friend_id: string },
    deviceId?: string,
): Promise<never> {
    try {
        await env.DB.prepare(`
            INSERT INTO audit_logs (event_type, friend_id, device_id, request_id, result, error_code, detail, created_at)
            VALUES (?, ?, ?, ?, 'FAILURE', ?, ?, ?)
        `).bind(
            invite?.kind === 'MIGRATION' ? 'MIGRATE' : 'ACTIVATE',
            invite?.friend_id || null,
            deviceId || null,
            requestId,
            errorCode,
            invite ? `invite_kind:${invite.kind}` : null,
            now(),
        ).run();
    } catch {
        // 审计写入失败时仍返回原始激活错误，不能掩盖用户可处理的原因。
    }
    throw new AppError(errorCode, message, 400);
}
