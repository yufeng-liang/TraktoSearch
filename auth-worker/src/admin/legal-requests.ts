// 管理员查看和关闭法律/隐私请求。

import { AppError, now, successResponse } from '../util/errors';

const LEGAL_REQUEST_STATUSES = new Set(['ALL', 'OPEN', 'CLOSED']);

export async function listLegalRequests(
    request: Request,
    env: { DB: D1Database },
    requestId: string,
): Promise<Response> {
    const url = new URL(request.url);
    const status = (url.searchParams.get('status') || 'OPEN').toUpperCase();
    if (!LEGAL_REQUEST_STATUSES.has(status)) {
        throw new AppError('INVALID_REQUEST', 'Invalid legal request status', 400);
    }
    const requestedLimit = Number.parseInt(url.searchParams.get('limit') || '50', 10);
    const requestedOffset = Number.parseInt(url.searchParams.get('offset') || '0', 10);
    const limit = Number.isInteger(requestedLimit) && requestedLimit > 0 ? Math.min(requestedLimit, 50) : 50;
    const offset = Number.isInteger(requestedOffset) && requestedOffset >= 0 ? requestedOffset : 0;
    const where = status === 'ALL' ? '' : 'WHERE status = ?';
    const values = status === 'ALL' ? [] : [status];

    const { results } = await env.DB.prepare(`
        SELECT id, request_type, email, account_reference, description,
               status, created_at, updated_at, closed_at, closed_note
        FROM legal_requests
        ${where}
        ORDER BY created_at DESC
        LIMIT ? OFFSET ?
    `).bind(...values, limit, offset).all();
    const count = await env.DB.prepare(`
        SELECT COUNT(*) AS count FROM legal_requests ${where}
    `).bind(...values).first<{ count: number }>();
    const total = Number(count?.count || 0);

    return successResponse({
        requests: results || [],
        limit,
        offset,
        total,
        hasMore: offset + (results?.length || 0) < total,
    }, requestId);
}

export async function closeLegalRequest(
    request: Request,
    env: { DB: D1Database },
    requestId: string,
    legalRequestId: string,
): Promise<Response> {
    const existing = await env.DB.prepare(`
        SELECT id, status FROM legal_requests WHERE id = ?
    `).bind(legalRequestId).first<{ id: string; status: string }>();
    if (!existing) throw new AppError('NOT_FOUND', 'Legal request not found', 404);
    if (existing.status === 'CLOSED') {
        return successResponse({ id: legalRequestId, status: 'CLOSED' }, requestId);
    }

    let body: unknown = {};
    try {
        body = await request.json();
    } catch {
        // 关闭请求允许省略正文。
    }
    const note = body && typeof body === 'object' && typeof (body as Record<string, unknown>).note === 'string'
        ? ((body as Record<string, unknown>).note as string).trim()
        : '';
    if (note.length > 500 || /[\u0000-\u001f\u007f]/.test(note)) {
        throw new AppError('INVALID_REQUEST', 'close note is invalid', 400);
    }

    const currentTime = now();
    const result = await env.DB.prepare(`
        UPDATE legal_requests
        SET status = 'CLOSED', closed_at = ?, closed_note = ?, updated_at = ?
        WHERE id = ? AND status = 'OPEN'
    `).bind(currentTime, note || null, currentTime, legalRequestId).run();
    if (Number(result.meta.changes || 0) !== 1) {
        throw new AppError('LEGAL_REQUEST_ALREADY_CLOSED', 'Legal request is already closed', 409);
    }

    await env.DB.prepare(`
        INSERT INTO audit_logs (event_type, request_id, result, detail, created_at)
        VALUES ('LEGAL_REQUEST_CLOSE', ?, 'SUCCESS', ?, ?)
    `).bind(requestId, `legal_request_id:${legalRequestId}`, currentTime).run();

    return successResponse({ id: legalRequestId, status: 'CLOSED', closedAt: currentTime }, requestId);
}
