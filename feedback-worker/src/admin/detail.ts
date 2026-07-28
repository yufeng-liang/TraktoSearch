// GET /admin/detail/{id} — 后台反馈详情

import { AppError, successResponse } from '../util/errors';

interface Env {
    DB: D1Database;
}

export async function handleAdminDetail(
    env: Env,
    requestId: string,
    feedbackId: string
): Promise<Response> {
    const feedback = await env.DB.prepare(`
        SELECT id, friend_id, friend_nickname, device_id, trakt_username, douban_username,
               type, content, contact, screenshots,
               app_version, os_version, device_model, status, created_at
        FROM feedbacks
        WHERE id = ?
    `).bind(feedbackId).first();

    if (!feedback) {
        throw new AppError('NOT_FOUND', 'Feedback not found', 404);
    }

    const { results: replies } = await env.DB.prepare(`
        SELECT id, content, created_at
        FROM feedback_replies
        WHERE feedback_id = ?
        ORDER BY created_at ASC
    `).bind(feedbackId).all();

    return successResponse({ feedback, replies }, requestId);
}
