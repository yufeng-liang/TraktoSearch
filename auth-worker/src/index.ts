// auth-worker 主入口

import { successResponse, errorResponse, AppError, generateRequestId } from './util/errors';
import { verifyAccessToken } from './util/jwt';
import { handleActivate } from './auth/activate';
import { handleChallenge } from './auth/challenge';
import { handleRefresh } from './auth/refresh';
import { handleCheck } from './auth/check';
import { verifyAccessJWT } from './admin/access';
import { handleTmdbProxy } from './proxy/tmdb';
import { handleTraktProxy, handleTraktOAuth } from './proxy/trakt';
import { handleDoubanProxy } from './proxy/douban';
import { handleOmdbProxy } from './proxy/omdb';

export interface Env {
    DB: D1Database;
    KV: KVNamespace;
    JWT_SIGNING_KEY: string;
    ADMIN_EMAIL: string;
    ACCESS_TEAM_DOMAIN: string;
    ACCESS_AUDIENCE: string;
    ENVIRONMENT: string;
}

export default {
    async fetch(request: Request, env: Env): Promise<Response> {
        const requestId = generateRequestId();

        try {
            // CORS 预检
            if (request.method === 'OPTIONS') {
                return handleCors();
            }

            // 路由分发
            const url = new URL(request.url);
            const path = url.pathname;

            let response: Response;

            // App API（需 JWT）
            if (path.startsWith('/api/auth/')) {
                response = await handleAuthApi(request, env, requestId, path);
            }
            // Admin API（需 Access JWT）
            else if (path.startsWith('/admin/')) {
                response = await handleAdminApi(request, env, requestId, path);
            }
            // 健康检查
            else if (path === '/health') {
                response = await healthCheck(env, requestId);
            }
            else {
                throw new AppError('NOT_FOUND', 'Not found', 404);
            }

            // 添加 CORS 头
            return addCorsHeaders(response);
        } catch (err) {
            if (err instanceof AppError) {
                return addCorsHeaders(errorResponse(err, requestId));
            }
            console.error('Unhandled error:', err);
            return addCorsHeaders(errorResponse(
                new AppError('INTERNAL_ERROR', 'Internal server error', 500),
                requestId
            ));
        }
    },
};

// CORS 处理
function handleCors(): Response {
    return new Response(null, {
        status: 204,
        headers: {
            'Access-Control-Allow-Origin': '*',
            'Access-Control-Allow-Methods': 'GET, POST, PATCH, OPTIONS',
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

// App API 路由
async function handleAuthApi(
    request: Request,
    env: Env,
    requestId: string,
    path: string
): Promise<Response> {
    // 公开端点（无需 JWT）
    if (path === '/api/auth/activate' && request.method === 'POST') {
        return handleActivate(request, env, requestId);
    }
    if (path === '/api/auth/challenge' && request.method === 'POST') {
        return handleChallenge(request, env, requestId);
    }
    if (path === '/api/auth/refresh' && request.method === 'POST') {
        return handleRefresh(request, env, requestId);
    }

    // 需要 JWT 的端点
    const authHeader = request.headers.get('Authorization');
    if (!authHeader?.startsWith('Bearer ')) {
        throw new AppError('UNAUTHORIZED', 'Missing or invalid authorization header', 401);
    }
    const token = authHeader.slice(7);
    const payload = await verifyAccessToken(env.JWT_SIGNING_KEY, token);
    if (!payload) {
        throw new AppError('INVALID_TOKEN', 'Invalid or expired token', 401);
    }

    if (path === '/api/auth/check' && request.method === 'POST') {
        return handleCheck(request, env, requestId, payload);
    }

    // 敏感 API 代理（需 JWT）
    if (path.startsWith('/api/tmdb/')) {
        return handleTmdbProxy(request, env, path);
    }
    if (path.startsWith('/api/trakt/oauth/')) {
        return handleTraktOAuth(request, env, path, payload.sub);
    }
    if (path.startsWith('/api/trakt/')) {
        return handleTraktProxy(request, env, path, payload.sub);
    }
    if (path.startsWith('/api/douban/')) {
        return handleDoubanProxy(request, env, path);
    }
    if (path.startsWith('/api/omdb/')) {
        return handleOmdbProxy(request, env, path);
    }

    throw new AppError('NOT_FOUND', 'Not found', 404);
}

// Admin API 路由（需 Access JWT）
async function handleAdminApi(
    request: Request,
    env: Env,
    requestId: string,
    path: string
): Promise<Response> {
    // 验证 Cloudflare Access JWT
    await verifyAccessJWT(request, env);

    // 朋友管理
    if (path === '/admin/friends' && request.method === 'GET') {
        return listFriends(env, requestId);
    }
    if (path === '/admin/friends' && request.method === 'POST') {
        return createFriend(request, env, requestId);
    }

    // 朋友详情操作（/:id）
    const friendMatch = path.match(/^\/admin\/friends\/([^/]+)$/);
    if (friendMatch && request.method === 'PATCH') {
        return updateFriend(request, env, requestId, friendMatch[1]);
    }
    if (friendMatch && request.method === 'GET') {
        return listDevices(env, requestId, friendMatch[1]);
    }

    // 禁用朋友
    const disableMatch = path.match(/^\/admin\/friends\/([^/]+)\/disable$/);
    if (disableMatch && request.method === 'POST') {
        return disableFriend(env, requestId, disableMatch[1]);
    }

    // 设备撤销
    const deviceMatch = path.match(/^\/admin\/devices\/([^/]+)\/revoke$/);
    if (deviceMatch && request.method === 'POST') {
        return revokeDevice(env, requestId, deviceMatch[1]);
    }

    // 邀请码创建
    const inviteCreateMatch = path.match(/^\/admin\/friends\/([^/]+)\/invites$/);
    if (inviteCreateMatch && request.method === 'POST') {
        return createInvite(request, env, requestId, inviteCreateMatch[1]);
    }

    // 邀请码撤销
    const inviteRevokeMatch = path.match(/^\/admin\/invites\/([^/]+)\/revoke$/);
    if (inviteRevokeMatch && request.method === 'POST') {
        return revokeInvite(env, requestId, inviteRevokeMatch[1]);
    }

    // 审计日志
    if (path === '/admin/audit-logs' && request.method === 'GET') {
        return listAuditLogs(request, env, requestId);
    }

    // 健康检查
    if (path === '/admin/health' && request.method === 'GET') {
        return healthCheck(env, requestId);
    }

    throw new AppError('NOT_FOUND', 'Not found', 404);
}
