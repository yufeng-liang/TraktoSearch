// GET /feedback-api/messages — 当前用户所有反馈的回复扁平列表（含开发者+用户）

import { successResponse } from '../util/errors';

interface Env {
    DB: D1Database;
}

interface JWTPayload {
    sub: string;
    device: string;
}

interface MessageRow {
    id: string;
    feedback_id: string;
    display_id: string;
    type: string;
    author_role: string;
    content: string;
    screenshots: string | null;
    created_at: number;
    last_read_at: number;
}

export async function handleMessages(
    request: Request,
    env: Env,
    requestId: string,
    payload: JWTPayload
): Promise<Response> {
    const url = new URL(request.url);
    const limitParam = Number.parseInt(url.searchParams.get('limit') || '50', 10);
    const offsetParam = Number.parseInt(url.searchParams.get('offset') || '0', 10);
    const filter = url.searchParams.get('filter') || 'ALL';  // ALL / UNREAD / DEVELOPER / USER

    const limit = Number.isInteger(limitParam) && limitParam > 0 ? Math.min(limitParam, 100) : 50;
    const offset = Number.isInteger(offsetParam) && offsetParam >= 0 ? offsetParam : 0;

    // 构造过滤条件
    const conditions: string[] = ['f.friend_id = ?'];
    const values: (string | number)[] = [payload.sub];

    if (filter === 'DEVELOPER') {
        conditions.push('c.author_role = ?');
        values.push('developer');
    } else if (filter === 'USER') {
        conditions.push('c.author_role = ?');
        values.push('user');
    } else if (filter === 'UNREAD') {
        // 未读 = 开发者回复且 created_at > last_read_at
        conditions.push('c.author_role = ?');
        conditions.push('c.created_at > f.last_read_at');
        values.push('developer');
    }
    // ALL 不过滤

    const where = `WHERE ${conditions.join(' AND ')}`;

    const { results } = await env.DB.prepare(`
        SELECT c.id, c.feedback_id, f.display_id, f.type,
               c.author_role, c.content, c.screenshots,
               c.created_at, f.last_read_at
        FROM feedback_conversations c
        JOIN feedbacks f ON c.feedback_id = f.id
        ${where}
        ORDER BY c.created_at DESC
        LIMIT ? OFFSET ?
    `).bind(...values, limit, offset).all<MessageRow>();

    const countResult = await env.DB.prepare(`
        SELECT COUNT(*) AS cnt
        FROM feedback_conversations c
        JOIN feedbacks f ON c.feedback_id = f.id
        ${where}
    `).bind(...values).first<{ cnt: number }>();
    const total = Number(countResult?.cnt || 0);

    const messages = results.map(r => ({
        id: r.id,
        feedbackId: r.feedback_id,
        displayId: r.display_id,
        type: r.type,
        authorRole: r.author_role,
        content: r.content,
        screenshots: r.screenshots ? JSON.parse(r.screenshots) : [],
        createdAt: r.created_at,
        isUnread: r.author_role === 'developer' && r.created_at > r.last_read_at,
    }));

    return successResponse({
        messages,
        filter,
        limit, offset, total,
        hasMore: offset + messages.length < total,
    }, requestId);
}
