// POST /admin/reply — 后台回复（可附截图）

import { AppError, successResponse, now, readJson } from '../util/errors';
import { generateId } from '../util/crypto';

interface Env {
    DB: D1Database;
}

interface ReplyRequest {
    feedbackId: string;
    content: string;
    screenshots?: string[];
}

const MAX_SCREENSHOTS = 5;
const MAX_SCREENSHOT_KEY_LENGTH = 512;

export async function handleAdminReply(
    request: Request,
    env: Env,
    requestId: string
): Promise<Response> {
    const body = await readJson<ReplyRequest>(request);
    if (!body.feedbackId || !body.content) {
        throw new AppError('INVALID_REQUEST', 'feedbackId and content are required', 400);
    }
    const content = body.content.trim();
    if (content.length < 1 || content.length > 5000) {
        throw new AppError('INVALID_REQUEST', 'Content must be 1-5000 characters', 400);
    }

    const screenshots = Array.isArray(body.screenshots) ? body.screenshots.slice(0, MAX_SCREENSHOTS) : [];
    if (screenshots.some(k => typeof k !== 'string' || k.length > MAX_SCREENSHOT_KEY_LENGTH)) {
        throw new AppError('INVALID_REQUEST', 'Invalid screenshot key', 400);
    }
    // 校验截图归属：admin 上传的 key 必须以 admin/ 开头
    for (const k of screenshots) {
        if (!k.startsWith('admin/')) {
            throw new AppError('INVALID_REQUEST', 'Screenshot does not belong to admin', 400);
        }
    }

    const feedback = await env.DB.prepare(`
        SELECT id, status FROM feedbacks WHERE id = ?
    `).bind(body.feedbackId).first<{ id: string; status: string }>();

    if (!feedback) {
        throw new AppError('NOT_FOUND', 'Feedback not found', 404);
    }
    if (feedback.status === 'CLOSED') {
        throw new AppError('INVALID_REQUEST', 'Cannot reply to closed feedback', 400);
    }

    const replyId = generateId();
    const currentTime = now();
    const screenshotsJson = screenshots.length > 0 ? JSON.stringify(screenshots) : null;

    // 事务：插入对话 + PENDING → REPLIED
    await env.DB.batch([
        env.DB.prepare(`
            INSERT INTO feedback_conversations (id, feedback_id, author_role, content, screenshots, parent_reply_id, created_at)
            VALUES (?, ?, 'developer', ?, ?, NULL, ?)
        `).bind(replyId, body.feedbackId, content, screenshotsJson, currentTime),
        // PENDING -> REPLIED；REPLIED 保持 REPLIED
        env.DB.prepare(`
            UPDATE feedbacks SET status = 'REPLIED' WHERE id = ? AND status = 'PENDING'
        `).bind(body.feedbackId),
    ]);

    return successResponse({ replyId, createdAt: currentTime }, requestId);
}
