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
    display_id: string;
    last_read_at: number;
}

interface ConversationRow {
    id: string;
    author_role: string;
    content: string;
    screenshots: string | null;
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
               app_version, os_version, device_model, status, created_at,
               display_id, last_read_at
        FROM feedbacks
        WHERE id = ?
    `).bind(feedbackId).first<FeedbackRow>();

    if (!feedback) {
        throw new AppError('NOT_FOUND', 'Feedback not found', 404);
    }
    if (feedback.friend_id !== payload.sub) {
        throw new AppError('FORBIDDEN', 'Cannot view others feedback', 403);
    }

    const { results } = await env.DB.prepare(`
        SELECT id, author_role, content, screenshots, created_at
        FROM feedback_conversations
        WHERE feedback_id = ?
        ORDER BY created_at ASC
    `).bind(feedbackId).all<ConversationRow>();

    const replies = results.map(r => ({
        id: r.id,
        authorRole: r.author_role,
        content: r.content,
        screenshots: r.screenshots ? JSON.parse(r.screenshots) : [],
        createdAt: r.created_at,
    }));

    return successResponse({
        feedback: {
            ...feedback,
            displayId: feedback.display_id,
            lastReadAt: feedback.last_read_at,
        },
        replies,
    }, requestId);
}
