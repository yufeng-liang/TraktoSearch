// POST /api/auth/activate — 邀请码激活 + 设备绑定 + JWT 发放

import { AppError, successResponse, now } from '../util/errors.ts';
import { sha256, generateSecureToken, generateId, hmacDeviceContinuityId } from '../util/crypto.ts';
import { signAccessToken } from '../util/jwt.ts';
import { firstRow } from '../util/db.ts';
import { clientIp } from '../util/client-ip.ts';
import { consumeRateLimit } from '../util/rate-limit.ts';

// 激活尝试限流：窗口 300 秒 / 单 IP 5 次（与 recover.ts 的恢复端点同档）。
//
// 邀请码从 12 位字母数字（32^12 ≈ 1.2e18）改成 6 位纯数字后空间只剩 10^6，
// 不限速的话单机就能在数小时内枚举完整个空间，撞到任意一个未使用的码即可
// 绑定设备并拿到 JWT。按 60 次/小时/IP 计，枚举 10^6 需约 1.7 万 IP·小时。
// 正常用户一次成功即止，输错重试 5 次也够用。
const ACTIVATION_RATE_WINDOW_SECONDS = 300;
const ACTIVATION_RATE_MAX_ATTEMPTS = 5;

export function isActivationBindingConflict(
    existingDevice: { status: string; deleted_at: number | null } | null,
    inviteKind: string,
): boolean {
    return inviteKind !== 'MIGRATION'
        && existingDevice !== null
        && existingDevice.status === 'ACTIVE'
        && existingDevice.deleted_at === null;
}

interface ActivateRequest {
    inviteCode: string;
    publicKey: string;
    deviceName?: string;
    appVersion?: string;
    packageName?: string;
    androidId?: string;
}

interface ActivateResponse {
    deviceId: string;
    // 激活成功那一刻客户端就要显示昵称，此时 check 还没跑，只能由 activate 带回。
    nickname: string;
    accessToken: string;
    refreshToken: string;
    accessExpiresAt: number;
    refreshExpiresAt: number;
    nextCheckAt: number;
}

// 批次内语句下标：核销邀请码、写设备、写刷新会话（用于事后校验 changes）
const INVITE_CONSUME_INDEX = 0;
const DEVICE_WRITE_INDEX = 1;
const SESSION_WRITE_INDEX = 3;

// 同一批次内后续语句的共同守卫：只有第一条语句成功核销了邀请码才真正写入。
// 绑定参数为 (inviteId, usedAt)。
const CONSUMED_GUARD = 'EXISTS (SELECT 1 FROM invites WHERE id = ? AND used_at = ?)';

export async function handleActivate(
    request: Request,
    env: { DB: D1Database; JWT_SIGNING_KEY: string; DEVICE_RECOVERY_HMAC_KEY: string },
    requestId: string
): Promise<Response> {
    const body = await request.json() as ActivateRequest;

    // 参数校验
    if (!body.inviteCode || !body.publicKey) {
        return logAndThrowActivationFailure(env, requestId, 'INVALID_INVITE', 'inviteCode and publicKey are required');
    }

    // 先限流再查库：6 位纯数字码可暴破，这道闸必须挡在邀请码查询之前。
    await enforceActivationRateLimit(env, request);

    const codeHash = await sha256(body.inviteCode);
    const currentTime = now();

    // 查找有效邀请码
    const invite = await firstRow<{
        id: string; friend_id: string; kind: string; expires_at: number;
        used_at: number | null; revoked_at: number | null; device_id: string | null;
        code_mask: string | null;
        friend_status: string; max_devices: number; friend_expires_at: number | null;
        friend_nickname: string;
    }>(env.DB.prepare(`
        SELECT i.id, i.friend_id, i.kind, i.expires_at, i.used_at, i.revoked_at, i.code_mask, i.device_id,
               f.status as friend_status, f.max_devices, f.expires_at as friend_expires_at,
               f.nickname as friend_nickname
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
    let existingDevice = await firstRow<{ id: string; friend_id: string; status: string; deleted_at: number | null }>(env.DB.prepare(`
        SELECT id, friend_id, status, deleted_at
        FROM devices
        WHERE public_key = ?
    `).bind(body.publicKey));

    if (invite.kind === 'MIGRATION' && invite.device_id && existingDevice && existingDevice.id !== invite.device_id) {
        return logAndThrowActivationFailure(env, requestId, 'MIGRATION_DEVICE_MISMATCH', 'Public key belongs to another device', invite, existingDevice.id);
    }

    if (invite.kind === 'MIGRATION' && !existingDevice && invite.device_id) {
        existingDevice = await firstRow<{ id: string; friend_id: string; status: string; deleted_at: number | null }>(env.DB.prepare(`
            SELECT id, friend_id, status, deleted_at
            FROM devices
            WHERE id = ?
    `).bind(invite.device_id));
    }

    if (invite.kind === 'MIGRATION' && !existingDevice && body.androidId) {
        const recoveryIdHmac = await hmacDeviceContinuityId(body.androidId, env.DEVICE_RECOVERY_HMAC_KEY);
        existingDevice = await firstRow<{ id: string; friend_id: string; status: string; deleted_at: number | null }>(env.DB.prepare(`
            SELECT id, friend_id, status, deleted_at
            FROM devices
            WHERE recovery_id_hmac = ?
        `).bind(recoveryIdHmac));
    }

    if (invite.kind !== 'MIGRATION' && !existingDevice && body.androidId) {
        const recoveryIdHmac = await hmacDeviceContinuityId(body.androidId, env.DEVICE_RECOVERY_HMAC_KEY);
        existingDevice = await firstRow<{ id: string; friend_id: string; status: string; deleted_at: number | null }>(env.DB.prepare(`
            SELECT id, friend_id, status, deleted_at
            FROM devices
            WHERE recovery_id_hmac = ?
        `).bind(recoveryIdHmac));
    }

    if (invite.kind === 'MIGRATION' && !existingDevice) {
        return logAndThrowActivationFailure(env, requestId, 'MIGRATION_DEVICE_NOT_FOUND', 'Migration invite requires an existing device', invite);
    }
    if (invite.kind === 'MIGRATION' && existingDevice && existingDevice.friend_id !== invite.friend_id) {
        return logAndThrowActivationFailure(env, requestId, 'MIGRATION_DEVICE_MISMATCH', 'Device belongs to another friend', invite, existingDevice.id);
    }
    if (isActivationBindingConflict(existingDevice, invite.kind) && existingDevice) {
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

    // 事务：核销邀请码 + 创建设备 + 创建刷新会话 + 审计
    //
    // 竞态防护：上面的校验全部是事务外 SELECT，两路并发请求可能同时读到
    // used_at IS NULL、设备数未满，然后各自提交——同一邀请码就能绑定多台设备。
    // 因此把全部前置条件折叠进第一条 UPDATE：单行 UPDATE 是原子的，只有真正抢到
    // 核销的一方 changes 为 1；其余语句统一用「本批次已核销该邀请码」自守，
    // 落败方整批空转，不留设备、不留刷新会话。模式同 invitations.ts:issueInvitation。
    const statements = [
        env.DB.prepare(`
            UPDATE invites
            SET used_at = ?
            WHERE id = ?
              AND used_at IS NULL
              AND revoked_at IS NULL
              AND expires_at >= ?
              AND EXISTS (
                  SELECT 1 FROM friends f
                  WHERE f.id = invites.friend_id
                    AND f.status = 'ACTIVE'
                    AND (f.expires_at IS NULL OR f.expires_at >= ?)
              )
              AND (
                  SELECT COUNT(*) FROM devices d
                  WHERE d.friend_id = invites.friend_id
                    AND d.status = 'ACTIVE'
                    AND (? IS NULL OR d.id != ?)
              ) < ?
        `).bind(
            currentTime,
            invite.id,
            currentTime,
            currentTime,
            existingDevice?.id || null,
            existingDevice?.id || null,
            invite.max_devices,
        ),

        // 后续语句都带同一守卫：只有本批次成功核销邀请码时才真正写入。
        existingDevice
            ? env.DB.prepare(`
            UPDATE devices
            SET friend_id = ?, public_key = ?, device_name = ?, status = 'ACTIVE', app_version = ?, activated_at = ?, revoked_at = NULL, deleted_at = NULL
            WHERE id = ? AND ${CONSUMED_GUARD}
        `).bind(invite.friend_id, body.publicKey, body.deviceName || null, body.appVersion || null, currentTime, deviceId, invite.id, currentTime)
            : env.DB.prepare(`
            INSERT INTO devices (id, friend_id, public_key, device_name, status, app_version, activated_at)
            SELECT ?, ?, ?, ?, 'ACTIVE', ?, ?
            WHERE ${CONSUMED_GUARD}
        `).bind(deviceId, invite.friend_id, body.publicKey, body.deviceName || null, body.appVersion || null, currentTime, invite.id, currentTime),

        env.DB.prepare(`
            UPDATE refresh_sessions SET revoked_at = ?
            WHERE device_id = ? AND revoked_at IS NULL AND ${CONSUMED_GUARD}
        `).bind(currentTime, deviceId, invite.id, currentTime),

        env.DB.prepare(`
            INSERT INTO refresh_sessions (id, device_id, token_hash, expires_at, created_at)
            SELECT ?, ?, ?, ?, ?
            WHERE ${CONSUMED_GUARD}
        `).bind(refreshSessionId, deviceId, refreshTokenHash, refreshExpiresAt, currentTime, invite.id, currentTime),

        // 将授权审计与邀请码消费放进同一批处理，避免出现“邀请码已使用但没有审计记录”。
        env.DB.prepare(`
            INSERT INTO audit_logs (event_type, friend_id, device_id, request_id, result, detail, created_at)
            SELECT ?, ?, ?, ?, 'SUCCESS', ?, ?
            WHERE ${CONSUMED_GUARD}
        `).bind(
            invite.kind === 'MIGRATION' ? 'MIGRATE' : 'ACTIVATE',
            invite.friend_id,
            deviceId,
            requestId,
            `invite_kind:${invite.kind};invite_id:${invite.id};invite_mask:${invite.code_mask || ''}`,
            currentTime,
            invite.id,
            currentTime,
        ),
    ];

    if (body.androidId) {
        const recoveryIdHmac = await hmacDeviceContinuityId(body.androidId, env.DEVICE_RECOVERY_HMAC_KEY);
        statements.push(env.DB.prepare(`
            UPDATE devices
            SET recovery_id_hmac = ?, recovery_id_version = 1, recovery_updated_at = ?
            WHERE id = ? AND ${CONSUMED_GUARD}
        `).bind(recoveryIdHmac, currentTime, deviceId, invite.id, currentTime));
    }

    const results = await env.DB.batch(statements);

    // 核销失败：并发的另一方先抢到了邀请码，或设备上限/有效期在提交时已发生变化。
    // 此时后续语句全部空转，没有留下设备与刷新会话，可以安全返回错误。
    if (Number(results?.[INVITE_CONSUME_INDEX]?.meta?.changes || 0) !== 1) {
        return throwActivationRaceFailure(env, requestId, invite, currentTime, existingDevice?.id);
    }
    // 邀请码已核销但设备或刷新会话写入条数不符，属数据层异常，绝不能发放令牌。
    if (Number(results?.[DEVICE_WRITE_INDEX]?.meta?.changes || 0) !== 1
        || Number(results?.[SESSION_WRITE_INDEX]?.meta?.changes || 0) !== 1) {
        throw new AppError('ACTIVATION_INCOMPLETE', 'Activation could not be completed', 500);
    }

    const response: ActivateResponse = {
        deviceId,
        // 昵称随主查询的 JOIN friends 一并取回，不额外查库。
        nickname: invite.friend_nickname,
        accessToken,
        refreshToken,
        accessExpiresAt,
        refreshExpiresAt,
        nextCheckAt,
    };

    return successResponse(response, requestId);
}

// 核销失败后重新读取一次状态，把原因映射回与并发前一致的错误码。
// 此时邀请码未被本请求消耗，也没有留下设备与刷新会话。
async function throwActivationRaceFailure(
    env: { DB: D1Database },
    requestId: string,
    invite: { id: string; kind: string; friend_id: string },
    currentTime: number,
    deviceId?: string,
): Promise<never> {
    const latest = await env.DB.prepare(`
        SELECT i.used_at, i.revoked_at, i.expires_at,
               f.status as friend_status, f.expires_at as friend_expires_at, f.max_devices
        FROM invites i
        JOIN friends f ON f.id = i.friend_id
        WHERE i.id = ?
    `).bind(invite.id).first<{
        used_at: number | null; revoked_at: number | null; expires_at: number;
        friend_status: string; friend_expires_at: number | null; max_devices: number;
    }>();

    if (!latest) {
        return logAndThrowActivationFailure(env, requestId, 'INVALID_INVITE', 'Invite code not found');
    }
    if (latest.used_at !== null) {
        return logAndThrowActivationFailure(env, requestId, 'INVITE_ALREADY_USED', 'Invite code already used', invite);
    }
    if (latest.revoked_at !== null) {
        return logAndThrowActivationFailure(env, requestId, 'INVITE_REVOKED', 'Invite code has been revoked', invite);
    }
    if (latest.expires_at < currentTime) {
        return logAndThrowActivationFailure(env, requestId, 'INVITE_EXPIRED', 'Invite code has expired', invite);
    }
    if (latest.friend_status !== 'ACTIVE'
        || (latest.friend_expires_at !== null && latest.friend_expires_at < currentTime)) {
        return logAndThrowActivationFailure(env, requestId, 'FRIEND_DISABLED', 'Friend account is not active', invite);
    }

    const activeDeviceCount = await env.DB.prepare(`
        SELECT COUNT(*) as count FROM devices
        WHERE friend_id = ? AND status = 'ACTIVE' AND (? IS NULL OR id != ?)
    `).bind(invite.friend_id, deviceId || null, deviceId || null).first<{ count: number }>();
    if (activeDeviceCount && activeDeviceCount.count >= latest.max_devices) {
        return logAndThrowActivationFailure(
            env,
            requestId,
            'DEVICE_LIMIT_REACHED',
            `Device limit reached (max ${latest.max_devices})`,
            invite,
            deviceId,
        );
    }

    return logAndThrowActivationFailure(env, requestId, 'INVITE_UNAVAILABLE', 'Invite code is no longer available', invite);
}

async function enforceActivationRateLimit(
    env: { DB: D1Database },
    request: Request,
): Promise<void> {
    // 经 gateway 转发时 CF-Connecting-IP 已被改写为边缘出口 IP，须优先取 X-Real-IP；
    // 公网直连只信 CF-Connecting-IP。统一由 clientIp 处理。
    const ip = clientIp(request) || 'unknown';
    // D1 条件 UPSERT 原子限流；存储故障时 consumeRateLimit 走 fail-open。
    const allowed = await consumeRateLimit(
        env.DB,
        `activate-rate:${await sha256(ip)}`,
        ACTIVATION_RATE_MAX_ATTEMPTS,
        ACTIVATION_RATE_WINDOW_SECONDS,
    );
    if (!allowed) {
        throw new AppError('RATE_LIMITED', 'Too many activation attempts', 429);
    }
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
