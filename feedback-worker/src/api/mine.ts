// GET /feedback-api/mine — 我的反馈列表

import { successResponse } from '../util/errors';

interface Env {
    DB: D1Database;
}

interface JWTPayload {
    sub: string;
    device: string;
}

export async function handleMine(
    request: Request,
    env: Env,
    requestId: string,
    payload: JWTPayload
): Promise<Response> {
    const url = new URL(request.url);
    const requestedLimit = Number.parseInt(url.searchParams.get('limit') || '20', 10);
    const requestedOffset = Number.parseInt(url.searchParams.get('offset') || '0', 10);
    const limit = Number.isInteger(requestedLimit) && requestedLimit > 0 ? Math.min(requestedLimit, 50) : 20;
    const offset = Number.isInteger(requestedOffset) && requestedOffset >= 0 ? requestedOffset : 0;

    const { results } = await env.DB.prepare(`
        SELECT id, type, content, screenshots, status, created_at
        FROM feedbacks
        WHERE friend_id = ?
        ORDER BY created_at DESC
        LIMIT ? OFFSET ?
    `).bind(payload.sub, limit, offset).all();

    const countResult = await env.DB.prepare(`
        SELECT COUNT(*) AS count FROM feedbacks WHERE friend_id = ?
    `).bind(payload.sub).first<{ count: number }>();
    const total = Number(countResult?.count || 0);

    return successResponse({
        feedbacks: results,
        limit, offset, total,
        hasMore: offset + results.length < total,
    }, requestId);
}
