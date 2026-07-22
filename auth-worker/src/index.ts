// auth-worker 主入口

import { successResponse, errorResponse, AppError, generateRequestId } from './util/errors';
import { verifyAccessToken } from './util/jwt';

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
                response = successResponse({ status: 'ok' }, requestId);
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

    throw new AppError('NOT_FOUND', 'Not found', 404);
}

// Admin API 路由（需 Access JWT）
async function handleAdminApi(
    request: Request,
    env: Env,
    requestId: string,
    path: string
): Promise<Response> {
    // TODO: 实现 Access JWT 验证
    // TODO: 实现 Admin API 端点
    throw new AppError('NOT_IMPLEMENTED', 'Admin API not yet implemented', 501);
}

// === App API 处理器 ===

async function handleActivate(request: Request, env: Env, requestId: string): Promise<Response> {
    // TODO: 实现激活逻辑
    throw new AppError('NOT_IMPLEMENTED', 'Activate not yet implemented', 501);
}

async function handleChallenge(request: Request, env: Env, requestId: string): Promise<Response> {
    // TODO: 实现挑战码生成
    throw new AppError('NOT_IMPLEMENTED', 'Challenge not yet implemented', 501);
}

async function handleRefresh(request: Request, env: Env, requestId: string): Promise<Response> {
    // TODO: 实现刷新令牌
    throw new AppError('NOT_IMPLEMENTED', 'Refresh not yet implemented', 501);
}

async function handleCheck(
    request: Request,
    env: Env,
    requestId: string,
    payload: { sub: string; device: string }
): Promise<Response> {
    // TODO: 实现状态检查
    throw new AppError('NOT_IMPLEMENTED', 'Check not yet implemented', 501);
}
