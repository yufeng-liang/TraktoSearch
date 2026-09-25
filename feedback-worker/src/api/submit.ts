// POST /feedback-api/submit — 提交反馈

import { AppError, successResponse, now, readJson } from '../util/errors.ts';
import { generateId } from '../util/crypto.ts';
import { checkRateLimit } from '../util/rate-limit.ts';
import { generateDisplayId } from '../util/display-id.ts';
import { buildNotifyPayload, notifyNewFeedback } from '../notify/feedback-notify.ts';

interface Env {
    DB: D1Database;
    KV: KVNamespace;
    AUTH_WORKER?: Fetcher;
    JWT_SIGNING_KEY: string;
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
const MAX_SCREENSHOTS = 5;

export async function handleSubmit(
    request: Request,
    env: Env,
    requestId: string,
    payload: JWTPayload,
    ctx?: ExecutionContext
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
    // 类型防御：非字符串值直接 400，不能让 .trim is not a function 变 500
    // （且 500 发生在 feedback_seq 自增之后，会空耗一个序号）
    if (body.contact !== undefined && body.contact !== null && typeof body.contact !== 'string') {
        throw new AppError('INVALID_REQUEST', 'contact must be a string', 400);
    }
    const contact = body.contact ? body.contact.trim().slice(0, 128) : null;
    const friendNickname = typeof body.friendNickname === 'string' ? body.friendNickname.trim() : '';
    if (!friendNickname || friendNickname.length > 64) {
        throw new AppError('INVALID_REQUEST', 'friendNickname is required', 400);
    }
    // username 与其它字段同一校验口径：非字符串/超长直接 400，防 D1 bind 类型错与超长落库
    for (const field of ['traktUsername', 'doubanUsername'] as const) {
        const value = body[field];
        if (value !== undefined && value !== null && (typeof value !== 'string' || value.length > 64)) {
            throw new AppError('INVALID_REQUEST', `${field} must be a string of at most 64 chars`, 400);
        }
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

    // 从 feedback_seq 原子获取该类型的下一个序号，用于生成 display_id
    const seqResult = await env.DB.prepare(`
        UPDATE feedback_seq SET seq = seq + 1 WHERE type = ?
        RETURNING seq
    `).bind(body.type).first<{ seq: number }>();

    if (!seqResult) {
        throw new AppError('INTERNAL_ERROR', 'Failed to allocate display_id', 500);
    }
    const displayId = generateDisplayId(body.type, seqResult.seq);

    await env.DB.prepare(`
        INSERT INTO feedbacks (id, display_id, friend_id, friend_nickname, device_id, trakt_username, douban_username,
                               type, content, contact, screenshots,
                               app_version, os_version, device_model, status, created_at, last_read_at)
        VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, 'PENDING', ?, ?)
    `).bind(
        id, displayId, payload.sub, friendNickname, payload.device,
        body.traktUsername || null, body.doubanUsername || null,
        body.type, content, contact, screenshotsJson,
        body.appVersion, body.osVersion, body.deviceModel,
        currentTime, currentTime
    ).run();

    // 邮件提醒是旁路：落库已成功，发信失败不能影响提交结果，也不让响应等它
    if (ctx) {
        ctx.waitUntil(notifyNewFeedback(env, buildNotifyPayload({
            id,
            displayId,
            type: body.type,
            content,
            friendNickname,
            contact,
            traktUsername: body.traktUsername || null,
            doubanUsername: body.doubanUsername || null,
            appVersion: body.appVersion,
            osVersion: body.osVersion,
            deviceModel: body.deviceModel,
            screenshotCount: screenshots.length,
            createdAt: currentTime,
        })));
    }

    return successResponse({ id, displayId, createdAt: currentTime }, requestId);
}
