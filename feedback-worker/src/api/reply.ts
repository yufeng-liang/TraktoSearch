// POST /feedback-api/{id}/reply — 用户追问
// 用户在反馈详情页追加回复，author_role='user'，状态 REPLIED→PENDING

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

interface ReplyRequest {
    content: string;
    screenshots?: string[];
}

const MAX_SCREENSHOTS = 5;
const MAX_CONTENT_LENGTH = 2000;
const MIN_CONTENT_LENGTH = 1;

export async function handleUserReply(
    request: Request,
    env: Env,
    requestId: string,
    payload: JWTPayload,
    feedbackId: string
): Promise<Response> {
    // 限流：复用 submit 配额（每分钟 1 条 + 每天 10 条）
    const minuteKey = `feedback:submit:${payload.sub}:minute`;
    if (!await checkRateLimit(env, minuteKey, 1, 60)) {
        throw new AppError('RATE_LIMITED', 'Too many submissions, try again later', 429);
    }
    const dayKey = `feedback:submit:${payload.sub}:day`;
    if (!await checkRateLimit(env, dayKey, 10, 86400)) {
        throw new AppError('RATE_LIMITED', 'Daily limit reached', 429);
    }

    const body = await readJson<ReplyRequest>(request);
    const content = (body.content || '').trim();
    if (content.length < MIN_CONTENT_LENGTH || content.length > MAX_CONTENT_LENGTH) {
        throw new AppError('INVALID_REQUEST', `Content must be ${MIN_CONTENT_LENGTH}-${MAX_CONTENT_LENGTH} characters`, 400);
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

    // 查反馈，校验归属与状态
    const feedback = await env.DB.prepare(`
        SELECT id, status FROM feedbacks WHERE id = ? AND friend_id = ?
    `).bind(feedbackId, payload.sub).first<{ id: string; status: string }>();

    if (!feedback) {
        throw new AppError('NOT_FOUND', 'Feedback not found', 404);
    }
    if (feedback.status === 'CLOSED') {
        throw new AppError('INVALID_REQUEST', 'Cannot reply to closed feedback', 400);
    }

    const replyId = generateId();
    const currentTime = now();
    const screenshotsJson = screenshots.length > 0 ? JSON.stringify(screenshots) : null;

    // 事务：插入回复 + REPLIED→PENDING
    await env.DB.batch([
        env.DB.prepare(`
            INSERT INTO feedback_conversations (id, feedback_id, author_role, content, screenshots, parent_reply_id, created_at)
            VALUES (?, ?, 'user', ?, ?, NULL, ?)
        `).bind(replyId, feedbackId, content, screenshotsJson, currentTime),
        // REPLIED → PENDING；PENDING 保持 PENDING
        env.DB.prepare(`
            UPDATE feedbacks SET status = 'PENDING' WHERE id = ? AND status = 'REPLIED'
        `).bind(feedbackId),
    ]);

    return successResponse({ replyId, createdAt: currentTime }, requestId);
}
