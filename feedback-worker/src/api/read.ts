// POST /feedback-api/{id}/read — 标记单条反馈为已读
// POST /feedback-api/read-all — 标记当前用户所有未读反馈为已读

import { AppError, successResponse, now } from '../util/errors';

interface Env {
    DB: D1Database;
    KV: KVNamespace;
}

interface JWTPayload {
    sub: string;
    device: string;
}

/**
 * 标记单条反馈为已读（更新 last_read_at = now）。
 * 仅作者可标记自己的反馈。
 */
export async function handleMarkRead(
    env: Env,
    requestId: string,
    payload: JWTPayload,
    feedbackId: string
): Promise<Response> {
    const currentTime = now();

    const result = await env.DB.prepare(`
        UPDATE feedbacks SET last_read_at = ?
        WHERE id = ? AND friend_id = ?
    `).bind(currentTime, feedbackId, payload.sub).run();

    // D1 run() 不返回 affectedRows 字段（取决于版本），用查询验证
    const feedback = await env.DB.prepare(`
        SELECT id FROM feedbacks WHERE id = ? AND friend_id = ?
    `).bind(feedbackId, payload.sub).first();

    if (!feedback) {
        throw new AppError('NOT_FOUND', 'Feedback not found', 404);
    }

    return successResponse({ feedbackId, lastReadAt: currentTime }, requestId);
}

/**
 * 标记当前用户所有有未读开发者回复的反馈为已读。
 * 一次性更新 last_read_at = now（只更新 last_read_at < 当前时间的）。
 */
export async function handleMarkAllRead(
    env: Env,
    requestId: string,
    payload: JWTPayload
): Promise<Response> {
    const currentTime = now();

    // 只更新那些存在未读开发者回复的反馈（last_read_at < conversations.created_at）
    const result = await env.DB.prepare(`
        UPDATE feedbacks
        SET last_read_at = ?
        WHERE friend_id = ?
          AND last_read_at < ?
          AND EXISTS (
            SELECT 1 FROM feedback_conversations c
            WHERE c.feedback_id = feedbacks.id
              AND c.author_role = 'developer'
              AND c.created_at > feedbacks.last_read_at
          )
    `).bind(currentTime, payload.sub, currentTime).run();

    return successResponse({ markedAt: currentTime }, requestId);
}
