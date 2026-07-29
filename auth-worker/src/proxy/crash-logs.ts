// 崩溃日志代理（转发到 app-config Pages Functions）
//
// 客户端原直连 app-config-1qe.pages.dev/api/crash-logs 并自带 Bearer token，
// 现改为走网关 /api/crash-logs ，由 worker 注入 CRASH_LOG_TOKEN，
// 避免密钥编译进 APK 被反编译泄露。
//
// 鉴权：仍要求 auth-worker 的 JWT（与其它 /api/* 端点一致）。
// 崩溃可能发生在 token 失效时，此时本地保留日志，下次启动 token 恢复后重试。

import { Env } from '../index';
import { AppError } from '../util/errors';

const CRASH_LOG_UPSTREAM = 'https://app-config-1qe.pages.dev/api/crash-logs';

export async function handleCrashLogProxy(
    request: Request,
    env: Env
): Promise<Response> {
    if (!env.CRASH_LOG_TOKEN) {
        throw new AppError('SERVICE_UNAVAILABLE', 'Crash log proxy not configured', 503);
    }

    // 仅允许 POST
    if (request.method !== 'POST') {
        throw new AppError('METHOD_NOT_ALLOWED', 'Method not allowed', 405);
    }

    const body = await request.arrayBuffer();

    const upstream = await fetch(CRASH_LOG_UPSTREAM, {
        method: 'POST',
        headers: {
            'Content-Type': 'application/json',
            'Authorization': `Bearer ${env.CRASH_LOG_TOKEN}`,
        },
        body,
    });

    return new Response(upstream.body, {
        status: upstream.status,
        headers: {
            'Content-Type': 'application/json',
        },
    });
}
