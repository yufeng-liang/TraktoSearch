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

export interface Env {
    DB: D1Database;
    KV: KVNamespace;
    SCREENSHOTS: R2Bucket;
    JWT_SIGNING_KEY: string;
    ACCESS_TEAM_DOMAIN: string;
    ACCESS_AUDIENCE: string;
    ADMIN_EMAIL: string;
    ENVIRONMENT: string;
}

export default {
    async fetch(request: Request, env: Env): Promise<Response> {
        const requestId = generateRequestId();
        try {
            if (request.method === 'OPTIONS') return applySecurityHeaders(handleCors());

            const url = new URL(request.url);
            const path = url.pathname;
            let response: Response;

            // 截图读取（无需 JWT，key 含随机 token 难穷举）
            if (path.startsWith('/feedback-api/screenshot/')) {
                const key = decodeURIComponent(path.slice('/feedback-api/screenshot/'.length));
                response = await handleScreenshot(request, env, key);
            }
            // App API（需 App JWT）
            else if (path.startsWith('/feedback-api/')) {
                response = await handleAppApi(request, env, requestId, path);
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
                    try {
                        const counterKey = `auth_fail:${clientIp}:${url.pathname}`;
                        const current = parseInt(await env.KV.get(counterKey) || '0', 10);
                        await env.KV.put(counterKey, String(current + 1), { expirationTtl: 3600 });
                    } catch { /* KV 写入失败不影响响应 */ }
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
    path: string
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
        return handleSubmit(request, env, requestId, payload);
    }
    if (path === '/feedback-api/upload-screenshot' && request.method === 'POST') {
        return handleUploadScreenshot(request, env, requestId, payload);
    }
    if (path === '/feedback-api/mine' && request.method === 'GET') {
        return handleMine(request, env, requestId, payload);
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
    await verifyAccessJWT(request, env);

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
    throw new AppError('NOT_FOUND', 'Not found', 404);
}
