// GET /feedback-api/unread-count — 未读开发者回复计数 + 每个反馈最新未读预览

import { successResponse } from '../util/errors';

interface Env {
    DB: D1Database;
    KV: KVNamespace;
}

interface JWTPayload {
    sub: string;
    device: string;
}

interface UnreadItem {
    feedback_id: string;
    display_id: string;
    type: string;
    last_reply_id: string;
    last_reply_content: string;
    last_reply_created_at: number;
    has_screenshot: number;  // SQLite 返回 0/1
}

export async function handleUnreadCount(
    env: Env,
    requestId: string,
    payload: JWTPayload
): Promise<Response> {
    // 未读开发者回复总数
    const countRow = await env.DB.prepare(`
        SELECT COUNT(*) AS cnt
        FROM feedback_conversations c
        JOIN feedbacks f ON c.feedback_id = f.id
        WHERE f.friend_id = ?
          AND c.author_role = 'developer'
          AND c.created_at > f.last_read_at
    `).bind(payload.sub).first<{ cnt: number }>();
    const count = Number(countRow?.cnt || 0);

    if (count === 0) {
        return successResponse({ count, items: [] }, requestId);
    }

    // 每个有未读开发者回复的反馈，取最新一条未读开发者回复
    const { results } = await env.DB.prepare(`
        SELECT f.id AS feedback_id, f.display_id, f.type,
               c.id AS last_reply_id, c.content AS last_reply_content,
               c.created_at AS last_reply_created_at,
               (c.screenshots IS NOT NULL) AS has_screenshot
        FROM feedbacks f
        JOIN feedback_conversations c ON c.feedback_id = f.id
        WHERE f.friend_id = ?
          AND c.author_role = 'developer'
          AND c.created_at > f.last_read_at
          AND c.id = (
              SELECT id FROM feedback_conversations
              WHERE feedback_id = f.id AND author_role = 'developer' AND created_at > f.last_read_at
              ORDER BY created_at DESC LIMIT 1
          )
        ORDER BY c.created_at DESC
    `).bind(payload.sub).all<UnreadItem>();

    const items = results.map(r => ({
        feedbackId: r.feedback_id,
        displayId: r.display_id,
        type: r.type,
        lastReply: {
            id: r.last_reply_id,
            content: r.last_reply_content,
            createdAt: r.last_reply_created_at,
            hasScreenshot: r.has_screenshot === 1,
        },
    }));

    return successResponse({ count, items }, requestId);
}
