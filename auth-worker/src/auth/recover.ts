// 重装恢复：用设备连续性指纹匹配原设备，并轮换当前安装的公钥与会话。

import { AppError, successResponse, now } from '../util/errors.ts';
import { generateId, generateSecureToken, hmacDeviceContinuityId, sha256 } from '../util/crypto.ts';
import { signAccessToken } from '../util/jwt.ts';
import { firstRow } from '../util/db.ts';
import { clientIp } from '../util/client-ip.ts';
import { verifyClientSignature } from './refresh.ts';
import { buildRecoveryChallengeSubject, consumeAuthChallenge, createAuthChallenge } from './challenge.ts';

const EXPECTED_PACKAGE_NAME = 'com.tracktosearch';
const RATE_LIMIT_WINDOW_SECONDS = 300;
const RATE_LIMIT_MAX_REQUESTS = 5;

interface RecoveryRequest {
    androidId: string;
    publicKey: string;
    nonce: string;
    signature: string;
    deviceName?: string;
    appVersion?: string;
    packageName?: string;
}

interface RecoveryChallengeRequest {
    androidId: string;
    publicKey: string;
    packageName?: string;
}

interface RecoveryEnv {
    DB: D1Database;
    KV: KVNamespace;
    JWT_SIGNING_KEY: string;
    DEVICE_RECOVERY_HMAC_KEY: string;
}

export async function handleRecoveryChallenge(
    request: Request,
    env: RecoveryEnv,
    requestId: string,
): Promise<Response> {
    const body = await readJson<RecoveryChallengeRequest>(request);
    validateCommonRequest(body);
    await enforceRateLimit(env, request, 'challenge');

    const challengeSubject = await buildRecoveryChallengeSubject(body.androidId, body.publicKey);
    const { nonce, expiresAt } = await createAuthChallenge(env.DB, 'RECOVERY', challengeSubject);

    return successResponse({ nonce, expiresAt }, requestId);
}

export async function handleRecover(
    request: Request,
    env: RecoveryEnv,
    requestId: string,
): Promise<Response> {
    const body = await readJson<RecoveryRequest>(request);
    validateCommonRequest(body);
    if (!body.nonce || !body.signature) {
        throw new AppError('INVALID_SIGNATURE', 'nonce and signature are required', 400);
    }
    await enforceRateLimit(env, request, 'recover');

    const challengeSubject = await buildRecoveryChallengeSubject(body.androidId, body.publicKey);
    const challengeConsumed = await consumeAuthChallenge(
        env.DB,
        body.nonce,
        'RECOVERY',
        challengeSubject,
    );
    if (!challengeConsumed) {
        throw new AppError('INVALID_SIGNATURE', 'Invalid recovery challenge', 400);
    }
    if (!(await verifyClientSignature(body.publicKey, body.signature, body.nonce))) {
        throw new AppError('INVALID_SIGNATURE', 'Invalid recovery signature', 400);
    }

    const recoveryIdHmac = await hmacDeviceContinuityId(body.androidId, env.DEVICE_RECOVERY_HMAC_KEY);
    const matches = await env.DB.prepare(`
        SELECT d.id as device_id, d.friend_id, d.status as device_status,
               f.status as friend_status, f.expires_at as friend_expires_at
        FROM devices d
        JOIN friends f ON f.id = d.friend_id
        WHERE d.recovery_id_hmac = ?
    `).bind(recoveryIdHmac).all<{
        device_id: string;
        friend_id: string;
        device_status: string;
        friend_status: string;
        friend_expires_at: number | null;
    }>();

    const currentTime = now();
    const activeMatches = (matches.results || []).filter(match => (
        match.device_status === 'ACTIVE'
        && match.friend_status === 'ACTIVE'
        && (match.friend_expires_at === null || match.friend_expires_at >= currentTime)
    ));
    if (activeMatches.length === 0) {
        throw new AppError('RECOVERY_NOT_FOUND', 'No active device matches this installation', 400);
    }
    if (activeMatches.length !== 1) {
        throw new AppError('RECOVERY_AMBIGUOUS', 'Multiple active devices match this installation', 409);
    }

    const device = activeMatches[0];
    const existingKey = await firstRow<{ id: string }>(env.DB.prepare(`
        SELECT id FROM devices WHERE public_key = ? AND id != ?
    `).bind(body.publicKey, device.device_id));
    if (existingKey) {
        throw new AppError('RECOVERY_REJECTED', 'Recovery key is already bound to another device', 409);
    }

    const refreshToken = generateSecureToken(32);
    const refreshTokenHash = await sha256(refreshToken);
    const refreshSessionId = generateId();
    const accessExpiresIn = 900;
    const refreshExpiresIn = 30 * 24 * 60 * 60;
    const accessExpiresAt = currentTime + accessExpiresIn;
    const refreshExpiresAt = currentTime + refreshExpiresIn;
    const nextCheckAt = currentTime + 24 * 60 * 60;
    const accessToken = await signAccessToken(
        env.JWT_SIGNING_KEY,
        device.friend_id,
        device.device_id,
        ['api'],
        accessExpiresIn,
    );

    await env.DB.batch([
        env.DB.prepare(`
            UPDATE devices
            SET public_key = ?, device_name = ?, app_version = ?, last_seen_at = ?, recovery_updated_at = ?
            WHERE id = ?
        `).bind(body.publicKey, body.deviceName || null, body.appVersion || null, currentTime, currentTime, device.device_id),
        env.DB.prepare(`
            UPDATE refresh_sessions SET revoked_at = ?
            WHERE device_id = ? AND revoked_at IS NULL
        `).bind(currentTime, device.device_id),
        env.DB.prepare(`
            INSERT INTO refresh_sessions (id, device_id, token_hash, expires_at, created_at)
            VALUES (?, ?, ?, ?, ?)
        `).bind(refreshSessionId, device.device_id, refreshTokenHash, refreshExpiresAt, currentTime),
        env.DB.prepare(`
            INSERT INTO audit_logs (event_type, friend_id, device_id, request_id, result, detail, created_at)
            VALUES ('REINSTALL_RECOVER', ?, ?, ?, 'SUCCESS', 'recovery_id_version:1', ?)
        `).bind(device.friend_id, device.device_id, requestId, currentTime),
    ]);

    return successResponse({
        deviceId: device.device_id,
        accessToken,
        refreshToken,
        accessExpiresAt,
        refreshExpiresAt,
        nextCheckAt,
    }, requestId);
}

function validateCommonRequest(body: { androidId?: string; publicKey?: string; packageName?: string }): void {
    if (!body.androidId || body.androidId.length > 256 || !body.publicKey || body.publicKey.length > 4096) {
        throw new AppError('INVALID_REQUEST', 'androidId and publicKey are required', 400);
    }
    if (body.packageName !== EXPECTED_PACKAGE_NAME) {
        throw new AppError('RECOVERY_REJECTED', 'Unsupported application package', 400);
    }
}

async function enforceRateLimit(env: RecoveryEnv, request: Request, operation: string): Promise<void> {
    // 经 gateway 转发时 CF-Connecting-IP 已被覆盖为边缘出口 IP，须优先取 X-Real-IP；
    // 公网直连只信 CF-Connecting-IP。统一由 clientIp 处理。
    const ip = clientIp(request) || 'unknown';
    const ipHash = await sha256(`${operation}:${ip}`);
    const key = `recover-rate:${ipHash}`;
    const current = Number(await env.KV.get(key) || '0');
    if (current >= RATE_LIMIT_MAX_REQUESTS) {
        throw new AppError('RATE_LIMITED', 'Too many recovery attempts', 429);
    }
    await env.KV.put(key, String(current + 1), { expirationTtl: RATE_LIMIT_WINDOW_SECONDS });
}

async function readJson<T>(request: Request): Promise<T> {
    try {
        return await request.json() as T;
    } catch {
        throw new AppError('INVALID_REQUEST', 'Invalid JSON request', 400);
    }
}
