// POST /admin/list — 后台反馈列表（含筛选）

import { successResponse } from '../util/errors';

interface Env {
    DB: D1Database;
}

interface ListRequest {
    type?: 'FEATURE' | 'BUG' | 'UX' | 'OTHER' | '';
    status?: 'PENDING' | 'REPLIED' | 'CLOSED' | '';
    friendNickname?: string;
    startTime?: number;
    endTime?: number;
    limit?: number;
    offset?: number;
}

const VALID_TYPES = new Set(['FEATURE', 'BUG', 'UX', 'OTHER']);
const VALID_STATUS = new Set(['PENDING', 'REPLIED', 'CLOSED']);

export async function handleAdminList(
    request: Request,
    env: Env,
    requestId: string
): Promise<Response> {
    const body = await request.json().catch(() => ({})) as ListRequest;

    const type = body.type && VALID_TYPES.has(body.type) ? body.type : '';
    const status = body.status && VALID_STATUS.has(body.status) ? body.status : '';
    const friendNickname = (body.friendNickname || '').trim().slice(0, 64);
    const startTime = Number.isFinite(body.startTime) && (body.startTime as number) > 0 ? (body.startTime as number) : 0;
    const endTime = Number.isFinite(body.endTime) && (body.endTime as number) > 0 ? (body.endTime as number) : 0;
    const requestedLimit = body.limit ?? 50;
    const requestedOffset = body.offset ?? 0;
    const limit = Number.isInteger(requestedLimit) && requestedLimit > 0 ? Math.min(requestedLimit, 100) : 50;
    const offset = Number.isInteger(requestedOffset) && requestedOffset >= 0 ? requestedOffset : 0;

    const conditions: string[] = [];
    const values: (string | number)[] = [];
    if (type) { conditions.push('type = ?'); values.push(type); }
    if (status) { conditions.push('status = ?'); values.push(status); }
    if (friendNickname) { conditions.push('LOWER(friend_nickname) LIKE LOWER(?)'); values.push(`%${friendNickname}%`); }
    if (startTime > 0) { conditions.push('created_at >= ?'); values.push(startTime); }
    if (endTime > 0) { conditions.push('created_at <= ?'); values.push(endTime); }
    const where = conditions.length > 0 ? `WHERE ${conditions.join(' AND ')}` : '';

    const { results } = await env.DB.prepare(`
        SELECT id, friend_id, friend_nickname, type, content, screenshots, status, created_at
        FROM feedbacks
        ${where}
        ORDER BY
            CASE status WHEN 'PENDING' THEN 0 WHEN 'REPLIED' THEN 1 ELSE 2 END,
            created_at DESC
        LIMIT ? OFFSET ?
    `).bind(...values, limit, offset).all();

    const countResult = await env.DB.prepare(`
        SELECT COUNT(*) AS count FROM feedbacks ${where}
    `).bind(...values).first<{ count: number }>();
    const total = Number(countResult?.count || 0);

    return successResponse({
        feedbacks: results,
        limit, offset, total,
        hasMore: offset + results.length < total,
    }, requestId);
}
