// POST /feedback-api/submit — 提交反馈

import { AppError, successResponse, now, readJson } from '../util/errors';
import { generateId } from '../util/crypto';
import { checkRateLimit } from '../util/rate-limit';

interface Env {
    DB: D1Database;
    KV: KVNamespace;
}

interface JWTPayload {
    sub: string;
    device: string;
}

interface SubmitRequest {
    type: 'FEATURE' | 'BUG' | 'UX' | 'OTHER';
    content: string;
    contact?: string;
    screenshots?: string[];
    friendNickname: string;       // App 端已知，直接传
    traktUsername?: string;
    doubanUsername?: string;
    appVersion: string;
    osVersion: string;
    deviceModel: string;
}

const VALID_TYPES = new Set(['FEATURE', 'BUG', 'UX', 'OTHER']);
const MAX_SCREENSHOTS = 3;

export async function handleSubmit(
    request: Request,
    env: Env,
    requestId: string,
    payload: JWTPayload
): Promise<Response> {
    // 限流：每分钟 1 条
    const minuteKey = `feedback:submit:${payload.sub}:minute`;
    if (!await checkRateLimit(env, minuteKey, 1, 60)) {
        throw new AppError('RATE_LIMITED', 'Too many submissions, try again later', 429);
    }
    // 限流：每天 10 条
    const dayKey = `feedback:submit:${payload.sub}:day`;
    if (!await checkRateLimit(env, dayKey, 10, 86400)) {
        throw new AppError('RATE_LIMITED', 'Daily limit reached', 429);
    }

    const body = await readJson<SubmitRequest>(request);

    if (!VALID_TYPES.has(body.type)) {
        throw new AppError('INVALID_REQUEST', 'Invalid feedback type', 400);
    }
    const content = (body.content || '').trim();
    if (content.length < 5 || content.length > 2000) {
        throw new AppError('INVALID_REQUEST', 'Content must be 5-2000 characters', 400);
    }
    const screenshots = Array.isArray(body.screenshots) ? body.screenshots.slice(0, MAX_SCREENSHOTS) : [];
    if (screenshots.some(url => typeof url !== 'string' || url.length > 512)) {
        throw new AppError('INVALID_REQUEST', 'Invalid screenshot URL', 400);
    }
    // 校验截图归属：key 必须以当前 friendId/ 开头
    for (const k of screenshots) {
        if (!k.startsWith(`${payload.sub}/`)) {
            throw new AppError('INVALID_REQUEST', 'Screenshot does not belong to user', 400);
        }
    }
    const contact = body.contact ? body.contact.trim().slice(0, 128) : null;
    const friendNickname = (body.friendNickname || '').trim();
    if (!friendNickname || friendNickname.length > 64) {
        throw new AppError('INVALID_REQUEST', 'friendNickname is required', 400);
    }
    if (!body.appVersion || typeof body.appVersion !== 'string' || body.appVersion.length > 64) {
        throw new AppError('INVALID_REQUEST', 'appVersion is required', 400);
    }
    if (!body.osVersion || typeof body.osVersion !== 'string' || body.osVersion.length > 64) {
        throw new AppError('INVALID_REQUEST', 'osVersion is required', 400);
    }
    if (!body.deviceModel || typeof body.deviceModel !== 'string' || body.deviceModel.length > 128) {
        throw new AppError('INVALID_REQUEST', 'deviceModel is required', 400);
    }

    const id = generateId();
    const currentTime = now();
    const screenshotsJson = screenshots.length > 0 ? JSON.stringify(screenshots) : null;

    await env.DB.prepare(`
        INSERT INTO feedbacks (id, friend_id, friend_nickname, device_id, trakt_username, douban_username,
                               type, content, contact, screenshots,
                               app_version, os_version, device_model, status, created_at)
        VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, 'PENDING', ?)
    `).bind(
        id, payload.sub, friendNickname, payload.device,
        body.traktUsername || null, body.doubanUsername || null,
        body.type, content, contact, screenshotsJson,
        body.appVersion, body.osVersion, body.deviceModel,
        currentTime
    ).run();

    return successResponse({ id, createdAt: currentTime }, requestId);
}
