// feedback-worker 主入口

import { successResponse, errorResponse, AppError, generateRequestId, isAppError } from './util/errors';
import { applySecurityHeaders } from './util/security-headers';
import { verifyAccessToken } from './util/jwt';
import { handleSubmit } from './api/submit';
import { handleUploadScreenshot } from './api/upload-screenshot';
import { handleScreenshot } from './api/screenshot';
import { handleMine } from './api/mine';
import { handleDetail } from './api/detail';
import { handleAdminList } from './admin/list';
import { handleAdminDetail } from './admin/detail';
import { handleAdminReply } from './admin/reply';
import { handleAdminClose } from './admin/close';
import { verifyAccessJWT } from './admin/access';
import { handleCleanupScreenshots } from './cron/cleanup-screenshots';
import { handleMarkRead, handleMarkAllRead } from './api/read';
import { handleUnreadCount } from './api/unread-count';
import { handleMessages } from './api/messages';
import { handleUserReply } from './api/reply';
import { handleAdminUploadScreenshot } from './admin/upload-screenshot';

export interface Env {
    DB: D1Database;
    KV: KVNamespace;
    SCREENSHOTS: R2Bucket;
    JWT_SIGNING_KEY: string;
    ACCESS_TEAM_DOMAIN: string;
    ACCESS_AUDIENCE: string;
    ADMIN_EMAIL: string;
    ENVIRONMENT: string;
    // 邮件提醒走 auth-worker（发信能力与模板集中在那里）
    AUTH_WORKER?: Fetcher;
}

export default {
    async fetch(request: Request, env: Env, ctx: ExecutionContext): Promise<Response> {
        const requestId = generateRequestId();
        try {
            if (request.method === 'OPTIONS') return applySecurityHeaders(handleCors());

            const url = new URL(request.url);
            const path = url.pathname;
            let response: Response;

            // 截图读取（无需 JWT，key 含随机 token 难穷举）
            if (path.startsWith('/feedback-api/screenshot/')) {
                // 畸形编码（如结尾 %）会抛 URIError，应归为 400 而不是 500
                const rawKey = path.slice('/feedback-api/screenshot/'.length);
                if (/%(?![0-9A-Fa-f]{2})/.test(rawKey)) {
                    response = new Response(JSON.stringify({ code: 'INVALID_REQUEST', message: 'Invalid key encoding' }), {
                        status: 400,
                        headers: { 'Content-Type': 'application/json' },
                    });
                } else {
                    response = await handleScreenshot(request, env, decodeURIComponent(rawKey));
                }
            }
            // App API（需 App JWT）
            else if (path.startsWith('/feedback-api/')) {
                response = await handleAppApi(request, env, requestId, path, ctx);
            }
            // Admin API（需 Access JWT）
            else if (path.startsWith('/admin/')) {
                response = await handleAdminApi(request, env, requestId, path);
            }
            // 健康检查
            else if (path === '/health') {
                response = successResponse({ ok: true }, requestId);
            }
            else {
                throw new AppError('NOT_FOUND', 'Not found', 404);
            }
            return applySecurityHeaders(addCorsHeaders(response));
        } catch (err) {
            if (isAppError(err)) {
                // 安全告警：鉴权失败（401/403）记录到 console + KV 计数
                if (err.statusCode === 401 || err.statusCode === 403) {
                    const clientIp = request.headers.get('CF-Connecting-IP') || 'unknown';
                    const url = new URL(request.url);
                    console.warn(`[AUTH_FAIL] ${err.code} status=${err.statusCode} ip=${clientIp} path=${url.pathname} method=${request.method} requestId=${requestId}`);
                    recordAuthFailure(env, ctx, `auth_fail:${clientIp}:${url.pathname}`);
                }
                return applySecurityHeaders(addCorsHeaders(errorResponse(err, requestId)));
            }
            console.error('Unhandled error:', err);
            return applySecurityHeaders(addCorsHeaders(errorResponse(
                new AppError('INTERNAL_ERROR', 'Internal server error', 500),
                requestId
            )));
        }
    },

    // Cron：每天清理 30 天前截图
    async scheduled(event: ScheduledEvent, env: Env): Promise<void> {
        try {
            await handleCleanupScreenshots(env);
        } catch (err) {
            console.error('[scheduled] cleanup failed:', err);
        }
    },
};

/**
 * 鉴权失败计数窗口：同一 (IP, 路径) 在窗口内的失败先在 isolate 内累加，
 * 窗口到期后由下一次失败一次性写回，把「每次 401/403 一读一写」降到每分钟一次。
 * 与 auth-worker 使用同一策略；isolate 回收时少量计数会丢失，安全告警可接受。
 */
const AUTH_FAIL_FLUSH_WINDOW_MS = 60 * 1000;
const authFailBuffer = new Map<string, { pending: number; lastFlushMs: number }>();

function recordAuthFailure(env: Env, ctx: ExecutionContext, counterKey: string): void {
    const nowMs = Date.now();
    // 键基数由请求方 IP/路径组合决定，超过上限整体丢弃，避免 isolate 内存无界增长
    if (authFailBuffer.size > 500) authFailBuffer.clear();
    const entry = authFailBuffer.get(counterKey) ?? { pending: 0, lastFlushMs: 0 };
    entry.pending += 1;
    authFailBuffer.set(counterKey, entry);
    // lastFlushMs 初始为 0，首次失败立即写入，保证异常访问能被立刻看到
    if (nowMs - entry.lastFlushMs < AUTH_FAIL_FLUSH_WINDOW_MS) return;

    const delta = entry.pending;
    entry.pending = 0;
    entry.lastFlushMs = nowMs;
    ctx.waitUntil((async () => {
        try {
            const current = parseInt(await env.KV.get(counterKey) || '0', 10);
            await env.KV.put(counterKey, String(current + delta), { expirationTtl: 3600 });
        } catch { /* KV 写入失败不影响响应 */ }
    })());
}

function handleCors(): Response {
    return new Response(null, {
        status: 204,
        headers: {
            'Access-Control-Allow-Origin': '*',
            'Access-Control-Allow-Methods': 'GET, POST, OPTIONS',
            'Access-Control-Allow-Headers': 'Content-Type, Authorization',
            'Access-Control-Max-Age': '86400',
        },
    });
}

function addCorsHeaders(response: Response): Response {
    const headers = new Headers(response.headers);
    headers.set('Access-Control-Allow-Origin', '*');
    return new Response(response.body, { status: response.status, headers });
}

async function handleAppApi(
    request: Request,
    env: Env,
    requestId: string,
    path: string,
    ctx: ExecutionContext
): Promise<Response> {
    // 验证 App JWT
    const authHeader = request.headers.get('Authorization');
    if (!authHeader?.startsWith('Bearer ')) {
        throw new AppError('UNAUTHORIZED', 'Missing or invalid authorization header', 401);
    }
    const token = authHeader.slice(7);
    const payload = await verifyAccessToken(env.JWT_SIGNING_KEY, token);
    if (!payload) {
        throw new AppError('INVALID_TOKEN', 'Invalid or expired token', 401);
    }

    if (path === '/feedback-api/submit' && request.method === 'POST') {
        return handleSubmit(request, env, requestId, payload, ctx);
    }
    if (path === '/feedback-api/upload-screenshot' && request.method === 'POST') {
        return handleUploadScreenshot(request, env, requestId, payload);
    }
    if (path === '/feedback-api/mine' && request.method === 'GET') {
        return handleMine(request, env, requestId, payload);
    }
    if (path === '/feedback-api/unread-count' && request.method === 'GET') {
        return handleUnreadCount(env, requestId, payload);
    }
    if (path === '/feedback-api/messages' && request.method === 'GET') {
        return handleMessages(request, env, requestId, payload);
    }
    if (path === '/feedback-api/read-all' && request.method === 'POST') {
        return handleMarkAllRead(env, requestId, payload);
    }
    // /feedback-api/{id}/read 和 /feedback-api/{id}/reply
    const readMatch = path.match(/^\/feedback-api\/([^/]+)\/read$/);
    if (readMatch && request.method === 'POST') {
        return handleMarkRead(env, requestId, payload, readMatch[1]);
    }
    const replyMatch = path.match(/^\/feedback-api\/([^/]+)\/reply$/);
    if (replyMatch && request.method === 'POST') {
        return handleUserReply(request, env, requestId, payload, replyMatch[1]);
    }
    const detailMatch = path.match(/^\/feedback-api\/([^/]+)$/);
    if (detailMatch && request.method === 'GET') {
        return handleDetail(env, requestId, payload, detailMatch[1]);
    }
    throw new AppError('NOT_FOUND', 'Not found', 404);
}

async function handleAdminApi(
    request: Request,
    env: Env,
    requestId: string,
    path: string
): Promise<Response> {
    const adminEmail = await verifyAccessJWT(request, env);

    if (path === '/admin/list' && request.method === 'POST') {
        return handleAdminList(request, env, requestId);
    }
    const detailMatch = path.match(/^\/admin\/detail\/([^/]+)$/);
    if (detailMatch && request.method === 'GET') {
        return handleAdminDetail(env, requestId, detailMatch[1]);
    }
    if (path === '/admin/reply' && request.method === 'POST') {
        return handleAdminReply(request, env, requestId);
    }
    if (path === '/admin/close' && request.method === 'POST') {
        return handleAdminClose(request, env, requestId);
    }
    if (path === '/admin/upload-screenshot' && request.method === 'POST') {
        return handleAdminUploadScreenshot(request, env, requestId, { email: adminEmail });
    }
    throw new AppError('NOT_FOUND', 'Not found', 404);
}
