// 崩溃日志写入器：直接写入与 app-config 共用的 KV，避免 auth-worker 再调用 Pages。
//
// 鉴权：仍要求 auth-worker 的 JWT（与其它 /api/* 端点一致）。
// 崩溃可能发生在 JWT 失效时，此时本地保留日志，下次启动认证恢复后重试。

import type { Env } from '../index.ts';
import { AppError } from '../util/errors.ts';

export async function handleCrashLogProxy(
    request: Request,
    env: Env
): Promise<Response> {
    // 仅允许 POST
    if (request.method !== 'POST') {
        throw new AppError('METHOD_NOT_ALLOWED', 'Method not allowed', 405);
    }

    const raw: unknown = await request.json();
    if (!raw || typeof raw !== 'object') {
        throw new AppError('INVALID_REQUEST', 'Invalid crash log payload', 400);
    }

    const payload = raw as Record<string, unknown>;
    const stackTrace = readString(payload, 'stackTrace');
    if (!stackTrace) {
        throw new AppError('INVALID_REQUEST', 'stackTrace is required', 400);
    }

    const id = `crash_${Date.now()}_${crypto.randomUUID().replaceAll('-', '').slice(0, 8)}`;
    const entry = {
        id,
        timestamp: readString(payload, 'timestamp') || new Date().toISOString(),
        appVersion: readString(payload, 'appVersion'),
        androidVersion: readString(payload, 'androidVersion'),
        device: readString(payload, 'device'),
        currentPage: readString(payload, 'currentPage'),
        recentActions: readString(payload, 'recentActions'),
        stackTrace,
    };

    await env.CRASH_LOGS.put(id, JSON.stringify(entry));

    return new Response(JSON.stringify({ ok: true, id }), {
        status: 201,
        headers: {
            'Content-Type': 'application/json',
        },
    });
}

function readString(payload: Record<string, unknown>, key: string): string {
    const value = payload[key];
    return typeof value === 'string' ? value : '';
}
