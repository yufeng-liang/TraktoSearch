// GET /feedback-api/{id} — 单条反馈详情（仅作者可查）

import { AppError, successResponse } from '../util/errors';

interface Env {
    DB: D1Database;
}

interface JWTPayload {
    sub: string;
    device: string;
}

interface FeedbackRow {
    id: string;
    friend_id: string;
    friend_nickname: string;
    device_id: string | null;
    trakt_username: string | null;
    douban_username: string | null;
    type: string;
    content: string;
    contact: string | null;
    screenshots: string | null;
    app_version: string;
    os_version: string;
    device_model: string;
    status: string;
    created_at: number;
}

export async function handleDetail(
    env: Env,
    requestId: string,
    payload: JWTPayload,
    feedbackId: string
): Promise<Response> {
    const feedback = await env.DB.prepare(`
        SELECT id, friend_id, friend_nickname, device_id, trakt_username, douban_username,
               type, content, contact, screenshots,
               app_version, os_version, device_model, status, created_at
        FROM feedbacks
        WHERE id = ?
    `).bind(feedbackId).first<FeedbackRow>();

    if (!feedback) {
        throw new AppError('NOT_FOUND', 'Feedback not found', 404);
    }
    if (feedback.friend_id !== payload.sub) {
        throw new AppError('FORBIDDEN', 'Cannot view others feedback', 403);
    }

    const { results: replies } = await env.DB.prepare(`
        SELECT id, content, created_at
        FROM feedback_replies
        WHERE feedback_id = ?
        ORDER BY created_at ASC
    `).bind(feedbackId).all();

    return successResponse({ feedback, replies }, requestId);
}
