// POST /api/auth/challenge — 生成挑战码（用于刷新令牌签名）

import { AppError, successResponse, now } from '../util/errors.ts';
import { generateSecureToken, sha256 } from '../util/crypto.ts';
import { firstRow } from '../util/db.ts';

export type AuthChallengeType = 'REFRESH' | 'RECOVERY';

const CHALLENGE_TTL_SECONDS = 600;

/** 将一次性 challenge 写入 D1，避免 KV 最终一致性导致刚生成的 nonce 读不到。 */
export async function createAuthChallenge(
    db: D1Database,
    challengeType: AuthChallengeType,
    subject: string,
    currentTime: number = now(),
): Promise<{ nonce: string; expiresAt: number }> {
    const nonce = generateSecureToken(16);
    const nonceHash = await sha256(nonce);
    const expiresAt = currentTime + CHALLENGE_TTL_SECONDS;

    await db.prepare(`
        INSERT INTO auth_challenges (nonce_hash, challenge_type, subject, expires_at)
        VALUES (?, ?, ?, ?)
    `).bind(nonceHash, challengeType, subject, expiresAt).run();

    return { nonce, expiresAt };
}

/** 使用带条件的 UPDATE 原子消费 challenge，只有一个并发请求可以成功。 */
export async function consumeAuthChallenge(
    db: D1Database,
    nonce: string,
    challengeType: AuthChallengeType,
    subject: string,
    currentTime: number = now(),
): Promise<boolean> {
    const nonceHash = await sha256(nonce);
    const result = await db.prepare(`
        UPDATE auth_challenges
        SET consumed_at = ?
        WHERE nonce_hash = ?
          AND challenge_type = ?
          AND subject = ?
          AND consumed_at IS NULL
          AND expires_at >= ?
    `).bind(currentTime, nonceHash, challengeType, subject, currentTime).run();

    return result.meta.changes === 1;
}

export async function buildRecoveryChallengeSubject(
    androidId: string,
    publicKey: string,
): Promise<string> {
    return sha256(`${androidId}\u0000${publicKey}`);
}

interface ChallengeRequest {
    deviceId: string;
}

interface ChallengeResponse {
    nonce: string;
    expiresAt: number;
}

export async function handleChallenge(
    request: Request,
    env: { DB: D1Database },
    requestId: string
): Promise<Response> {
    const body = await request.json() as ChallengeRequest;

    if (!body.deviceId) {
        throw new AppError('INVALID_REQUEST', 'deviceId is required', 400);
    }

    // 验证设备存在且活跃
    const device = await firstRow<{ id: string; status: string }>(env.DB.prepare(`
        SELECT id, status FROM devices WHERE id = ?
    `).bind(body.deviceId));

    if (!device) {
        throw new AppError('DEVICE_NOT_FOUND', 'Device not found', 404);
    }
    if (device.status !== 'ACTIVE') {
        throw new AppError('DEVICE_REVOKED', 'Device has been revoked', 403);
    }

    const { nonce, expiresAt } = await createAuthChallenge(env.DB, 'REFRESH', body.deviceId);

    const response: ChallengeResponse = { nonce, expiresAt };
    return successResponse(response, requestId);
}
