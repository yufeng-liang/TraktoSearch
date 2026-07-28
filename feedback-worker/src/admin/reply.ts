// POST /admin/reply — 后台回复

import { AppError, successResponse, now } from '../util/errors';
import { generateId } from '../util/crypto';

interface Env {
    DB: D1Database;
}

interface ReplyRequest {
    feedbackId: string;
    content: string;
}

export async function handleAdminReply(
    request: Request,
    env: Env,
    requestId: string
): Promise<Response> {
    const body = await request.json() as ReplyRequest;
    if (!body.feedbackId || !body.content) {
        throw new AppError('INVALID_REQUEST', 'feedbackId and content are required', 400);
    }
    const content = body.content.trim();
    if (content.length < 1 || content.length > 5000) {
        throw new AppError('INVALID_REQUEST', 'Content must be 1-5000 characters', 400);
    }

    const feedback = await env.DB.prepare(`
        SELECT id, status FROM feedbacks WHERE id = ?
    `).bind(body.feedbackId).first<{ id: string; status: string }>();

    if (!feedback) {
        throw new AppError('NOT_FOUND', 'Feedback not found', 404);
    }

    const replyId = generateId();
    const currentTime = now();

    await env.DB.batch([
        env.DB.prepare(`
            INSERT INTO feedback_replies (id, feedback_id, content, created_at)
            VALUES (?, ?, ?, ?)
        `).bind(replyId, body.feedbackId, content, currentTime),
        // PENDING -> REPLIED；CLOSED 保持 CLOSED（不允许回复已关闭）
        env.DB.prepare(`
            UPDATE feedbacks SET status = 'REPLIED' WHERE id = ? AND status = 'PENDING'
        `).bind(body.feedbackId),
    ]);

    return successResponse({ replyId, createdAt: currentTime }, requestId);
}
