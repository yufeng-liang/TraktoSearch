// GET /admin/detail/{id} — 后台反馈详情

import { AppError, successResponse } from '../util/errors';

interface Env {
    DB: D1Database;
}

interface ConversationRow {
    id: string;
    author_role: string;
    content: string;
    screenshots: string | null;
    created_at: number;
}

export async function handleAdminDetail(
    env: Env,
    requestId: string,
    feedbackId: string
): Promise<Response> {
    const feedback = await env.DB.prepare(`
        SELECT id, display_id, friend_id, friend_nickname, device_id, trakt_username, douban_username,
               type, content, contact, screenshots,
               app_version, os_version, device_model, status, created_at, last_read_at
        FROM feedbacks
        WHERE id = ?
    `).bind(feedbackId).first();

    if (!feedback) {
        throw new AppError('NOT_FOUND', 'Feedback not found', 404);
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
            ...(feedback as any),
            displayId: (feedback as any).display_id,
            lastReadAt: (feedback as any).last_read_at,
        },
        replies,
    }, requestId);
}
