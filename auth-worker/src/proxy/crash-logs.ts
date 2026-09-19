// 崩溃日志写入器：直接写入与 app-config 共用的 KV，避免 auth-worker 再调用 Pages。
//
// 鉴权：仍要求 auth-worker 的 JWT（与其它 /api/* 端点一致）。
// 崩溃可能发生在 JWT 失效时，此时本地保留日志，下次启动认证恢复后重试。

import type { Env } from '../index.ts';
import type { JWTPayload } from '../util/jwt.ts';
import { AppError } from '../util/errors.ts';
import { consumeRateLimit } from '../util/rate-limit.ts';

// 单条崩溃日志上限：崩溃堆栈含系统帧很少超过几十 KB，超大的必是滥用
const MAX_STACK_TRACE_LENGTH = 64 * 1024;

export async function handleCrashLogProxy(
    request: Request,
    env: Env,
    payload: JWTPayload
): Promise<Response> {
    // 仅允许 POST
    if (request.method !== 'POST') {
        throw new AppError('METHOD_NOT_ALLOWED', 'Method not allowed', 405);
    }

    // 每条日志一次 KV.put，无限流会被单账号刷爆 KV 每日写配额
    const subject = payload?.sub || 'anonymous';
    const allowed = await consumeRateLimit(env.DB, `crash-log:${subject}`, 30, 3600);
    if (!allowed) {
        throw new AppError('RATE_LIMITED', 'Too many crash log submissions', 429);
    }

    let raw: unknown;
    try {
        raw = await request.json();
    } catch {
        throw new AppError('INVALID_REQUEST', 'Invalid crash log payload', 400);
    }
    if (!raw || typeof raw !== 'object') {
        throw new AppError('INVALID_REQUEST', 'Invalid crash log payload', 400);
    }

    const payloadRecord = raw as Record<string, unknown>;
    const stackTrace = readString(payloadRecord, 'stackTrace');
    if (!stackTrace) {
        throw new AppError('INVALID_REQUEST', 'stackTrace is required', 400);
    }
    if (stackTrace.length > MAX_STACK_TRACE_LENGTH) {
        throw new AppError('INVALID_REQUEST', 'stackTrace is too large', 413);
    }

    const id = `crash_${Date.now()}_${crypto.randomUUID().replaceAll('-', '').slice(0, 8)}`;
    // 全字段统一截断：单靠 stackTrace 限长，其余字段仍可塞数 MB 进 KV.put
    const entry = {
        id,
        timestamp: readString(payloadRecord, 'timestamp').slice(0, 64) || new Date().toISOString(),
        appVersion: readString(payloadRecord, 'appVersion').slice(0, 64),
        androidVersion: readString(payloadRecord, 'androidVersion').slice(0, 64),
        device: readString(payloadRecord, 'device').slice(0, 128),
        currentPage: readString(payloadRecord, 'currentPage').slice(0, 256),
        recentActions: readString(payloadRecord, 'recentActions').slice(0, 2048),
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
