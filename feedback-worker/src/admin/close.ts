// POST /admin/close — 关闭反馈

import { AppError, successResponse } from '../util/errors';

interface Env {
    DB: D1Database;
}

interface CloseRequest {
    feedbackId: string;
}

export async function handleAdminClose(
    request: Request,
    env: Env,
    requestId: string
): Promise<Response> {
    const body = await request.json() as CloseRequest;
    if (!body.feedbackId) {
        throw new AppError('INVALID_REQUEST', 'feedbackId is required', 400);
    }

    const result = await env.DB.prepare(`
        UPDATE feedbacks SET status = 'CLOSED' WHERE id = ?
    `).bind(body.feedbackId).run();

    if (!result.success) {
        throw new AppError('INTERNAL_ERROR', 'Failed to close feedback', 500);
    }
    return successResponse({ ok: true }, requestId);
}
