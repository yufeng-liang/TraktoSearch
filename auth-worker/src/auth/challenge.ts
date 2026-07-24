// POST /api/auth/challenge — 生成挑战码（用于刷新令牌签名）

import { AppError, successResponse, now } from '../util/errors';
import { generateSecureToken, generateId, sha256 } from '../util/crypto';
import { firstRow } from '../util/db';

interface ChallengeRequest {
    deviceId: string;
}

interface ChallengeResponse {
    nonce: string;
    expiresAt: number;
}

export async function handleChallenge(
    request: Request,
    env: { DB: D1Database; KV: KVNamespace },
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

    // 生成一次性 nonce（10 分钟有效）
    const nonce = generateSecureToken(16);
    const nonceHash = await sha256(nonce);
    const expiresAt = now() + 600; // 10 分钟

    // 存入 KV（短期）
    await env.KV.put(`challenge:${nonceHash}`, body.deviceId, { expirationTtl: 600 });

    const response: ChallengeResponse = { nonce, expiresAt };
    return successResponse(response, requestId);
}
