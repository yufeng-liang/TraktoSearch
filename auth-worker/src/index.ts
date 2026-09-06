// auth-worker 主入口

import { successResponse, errorResponse, AppError, generateRequestId, isAppError } from './util/errors';
import { applySecurityHeaders } from './util/security-headers';
import { verifyAccessToken } from './util/jwt';
import { handleActivate } from './auth/activate';
import { handleChallenge } from './auth/challenge';
import { handleRefresh } from './auth/refresh';
import { handleCheck } from './auth/check';
import { handleRecover, handleRecoveryChallenge } from './auth/recover';
import { verifyAccessJWT } from './admin/access';
import {
    createFriend,
    createInvite,
    deleteDevice,
    disableFriend,
    enableFriend,
    getFriendDetail,
    healthCheck,
    listAuditLogs,
    listDevices,
    listFriendIpLogs,
    listFriends,
    listInvites,
    revokeDevice,
    revokeInvite,
    updateFriend,
} from './admin/admin';
import { handleTmdbProxy } from './proxy/tmdb';
import { handleTraktProxy, handleTraktOAuth, handleTraktPublicProxy } from './proxy/trakt';
import { isTraktPublicPath } from './proxy/trakt-token';
import { handleDoubanProxy } from './proxy/douban';
import { refreshPublicDoubanLists } from './proxy/douban-scrape';
import { handleOmdbProxy } from './proxy/omdb';
import { handleGiteeProxy } from './proxy/gitee';
import { handleGithubProxy } from './proxy/github';
import { handleTranslateProxy } from './proxy/translate';
import { handleCrashLogProxy } from './proxy/crash-logs';
import { handleConfigProxy } from './proxy/config';
import { handlePublicUpdateReleaseProxy, isPublicUpdateReleasePath } from './proxy/update';
import { handleInviteRequest, handleInviteResend, handleInviteVerification } from './invitations';
import { handleLegalRequest } from './legal-requests';
import { closeLegalRequest, listLegalRequests } from './admin/legal-requests';
import { cleanupRetention } from './retention';
import { handleAiApi } from './ai/handler';
import { handleAiIllustration } from './ai/daily-illustration';
import { handleAiAudio } from './ai/tts';

export interface Env {
    [key: string]: unknown;
    DB: D1Database;
    KV: KVNamespace;
    CRASH_LOGS: KVNamespace;
    DOUBAN_WORKER: Fetcher;
    JWT_SIGNING_KEY: string;
    DEVICE_RECOVERY_HMAC_KEY: string;
    TRAKT_CLIENT_ID: string;
    TRAKT_CLIENT_SECRET: string;
    TRAKT_CREDENTIALS_ENCRYPTION_KEY: string;
    TMDB_API_KEY: string;
    DOUBAN_API_KEY: string;
    OMDB_API_KEY: string;
    ADMIN_EMAIL: string;
    ACCESS_TEAM_DOMAIN: string;
    ACCESS_AUDIENCE: string;
    ENVIRONMENT: string;
    // 密钥代理（原客户端 BuildConfig 内嵌，现迁移到 worker secrets）
    GITEE_ACCESS_TOKEN: string;
    GITHUB_UPDATE_TOKEN: string;
    BAIDU_APP_ID: string;
    BAIDU_SECRET_KEY: string;
    BAIDU_API_KEY: string;
    // 云端配置服务端解密（原客户端 BuildConfig.CONFIG_AES_KEY）
    CONFIG_AES_KEY: string;
    CONFIG_BASE_URL: string;
    // 云端同步数据服务端加密（原客户端 AesCrypto 硬编码密钥）
    CLOUD_SYNC_AES_KEY: string;
    EMAIL?: SendEmail;
    BREVO_API_KEY?: string;
    EMAIL_FROM: string;
    EMAIL_REPLY_TO?: string;
    PUBLIC_SITE_ORIGIN: string;
    AUDIO_PUBLIC_BASE_URL?: string;
    INVITE_TEST_BYPASS_KEY?: string;
    // MiMo 仅由 Worker 读取，生产环境通过 wrangler secret 注入。
    MIMO_API_KEY?: string;
    // 私有 R2 音色样本，仅供 Worker 读取，不返回原始样本。
    AI_VOICE_SAMPLES?: R2Bucket;
    // 私有 R2 MP3 缓存，仅通过 10 分钟签名 URL 播放。
    AI_AUDIO_CACHE?: R2Bucket;
    // 可选概念插图专用 R2 绑定；未配置时代码复用 AI_AUDIO_CACHE。
    AI_IMAGE_CACHE?: R2Bucket;
}

export default {
    async fetch(request: Request, env: Env, ctx: ExecutionContext): Promise<Response> {
        const requestId = generateRequestId();

        try {
            // CORS 预检
            if (request.method === 'OPTIONS') {
                return applySecurityHeaders(handleCors());
            }

            // 路由分发
            const url = new URL(request.url);
            const path = url.pathname;

            let response: Response;

            // App API（需 JWT）
            if (path.startsWith('/api/')) {
                response = await handleAuthApi(request, env, requestId, path, ctx);
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

            // 添加 CORS 头 + 安全响应头
            return applySecurityHeaders(addCorsHeaders(response));
        } catch (err) {
            if (isAppError(err)) {
                // 安全告警：鉴权失败（401/403）记录到 console + KV 计数，便于排查异常访问
                if (err.statusCode === 401 || err.statusCode === 403) {
                    const clientIp = request.headers.get('CF-Connecting-IP') || 'unknown';
                    const failPath = new URL(request.url).pathname.replace(
                        /^\/api\/ai\/audio\/[^/]+$/,
                        '/api/ai/audio/:token',
                    );
                    console.warn(`[AUTH_FAIL] ${err.code} status=${err.statusCode} ip=${clientIp} path=${failPath} method=${request.method} requestId=${requestId}`);
                    // KV 计数：按 IP+路径维度，1 小时 TTL，超阈值可在 admin 面板查看。
                    // 写入放到 waitUntil 并按窗口合并，避免突发 401/403 打满每日 KV 写配额。
                    recordAuthFailure(env, ctx, `auth_fail:${clientIp}:${failPath}`);
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

    async scheduled(_event: ScheduledEvent, env: Env, ctx: ExecutionContext): Promise<void> {
        await Promise.all([
            refreshPublicDoubanLists(env, ctx),
            cleanupRetention(env),
        ]);
    },
};

/**
 * 鉴权失败计数窗口：同一 (IP, 路径) 在窗口内的失败先在 isolate 内累加，
 * 窗口到期后由下一次失败一次性写回，把「每次 401/403 一读一写」降到每分钟一次。
 */
const AUTH_FAIL_FLUSH_WINDOW_MS = 60 * 1000;
/** isolate 内累积的待写计数；isolate 回收时丢弃（安全告警计数容许少量丢失）。 */
const authFailBuffer = new Map<string, { pending: number; lastFlushMs: number }>();

function recordAuthFailure(env: Env, ctx: ExecutionContext, counterKey: string): void {
    const nowMs = Date.now();
    // 键基数由请求方的 IP/路径组合决定，超过上限整体丢弃，避免 isolate 内存无界增长
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

// CORS 处理
function handleCors(): Response {
    return new Response(null, {
        status: 204,
        headers: {
            'Access-Control-Allow-Origin': '*',
            'Access-Control-Allow-Methods': 'GET, POST, PATCH, OPTIONS',
            'Access-Control-Allow-Headers': 'Content-Type, Authorization, X-Invite-Test-Key',
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
    path: string,
    ctx: ExecutionContext,
): Promise<Response> {
    // 公开端点（无需 JWT）
    if (path === '/api/invite-requests' && request.method === 'POST') {
        return handleInviteRequest(request, env, requestId);
    }
    if (path === '/api/invite-requests/resend' && request.method === 'POST') {
        return handleInviteResend(request, env, requestId);
    }
    if (path === '/api/invite-requests/verify' && request.method === 'GET') {
        return handleInviteVerification(request, env, requestId);
    }
    if (path === '/api/legal-requests' && request.method === 'POST') {
        return handleLegalRequest(request, env, requestId, ctx);
    }
    if (path === '/api/auth/activate' && request.method === 'POST') {
        return handleActivate(request, env, requestId);
    }
    if (path === '/api/auth/recover/challenge' && request.method === 'POST') {
        return handleRecoveryChallenge(request, env, requestId);
    }
    if (path === '/api/auth/recover' && request.method === 'POST') {
        return handleRecover(request, env, requestId);
    }
    if (path === '/api/auth/challenge' && request.method === 'POST') {
        return handleChallenge(request, env, requestId);
    }
    if (path === '/api/auth/refresh' && request.method === 'POST') {
        return handleRefresh(request, env, requestId);
    }

    const audioMatch = path.match(/^\/api\/ai\/audio\/([^/]+)$/);
    if (audioMatch && request.method === 'GET') {
        return handleAiAudio(request, env, audioMatch[1]);
    }

    // 概念插图只暴露短期签名 URL，不透出供应商临时地址或 R2 原始对象。
    const illustrationMatch = path.match(/^\/api\/ai\/illustration\/([^/]+)$/);
    if (illustrationMatch && request.method === 'GET') {
        return handleAiIllustration(env, illustrationMatch[1]);
    }

    // AI 角色目录和试听是公开体验入口；真正激活和四项能力仍在下方统一校验 JWT。
    if (
        (path === '/api/ai/characters' && request.method === 'GET')
        || (path === '/api/ai/tts' && request.method === 'POST' && !request.headers.has('Authorization'))
    ) {
        return handleAiApi(request, env, requestId, path, null);
    }

    // Trakt 的 client_id 本身是公开标识；授权地址不依赖用户凭据。
    // 将该入口保持公开，避免 access token 过期时连登录页都无法重新打开。
    if (path === '/api/trakt/oauth/authorize' && request.method === 'GET') {
        return handleTraktOAuth(request, env, path, '', requestId);
    }

    // 公开数据端点（无需 JWT）：访客模式下发现页/搜索页也能正常浏览
    // worker 仍注入上游 API Key，安全层从「用户鉴权」下沉到「网关密钥代理」
    // 豆瓣热榜（chart/weekly/nowplaying/top250）——纯公开榜单数据
    if (path.startsWith('/api/douban/api/') && request.method === 'GET') {
        return handleDoubanProxy(request, env, path, ctx);
    }
    // TMDB —— 电影/剧集元数据、搜索、海报等全为公开数据
    if (path.startsWith('/api/tmdb/') && request.method === 'GET') {
        return handleTmdbProxy(request, env, path);
    }
    // OMDb —— 评分等公开数据
    if (path.startsWith('/api/omdb/') && request.method === 'GET') {
        return handleOmdbProxy(request, env, path);
    }
    // Trakt 公开端点（trending/anticipated/search/movies/{id}/shows/{id}/people/* 等）
    // 仅需 client_id，不需要用户 access_token；sync/recommendations/users 仍需 JWT
    if (path.startsWith('/api/trakt/') && !path.startsWith('/api/trakt/oauth/') && request.method === 'GET') {
        const traktPath = path.replace('/api/trakt/', '');
        // 动态判断是否为公开路径（isTraktPublicPath 内部排除 sync/recommendations/users 等）
        if (isTraktPublicPath(traktPath)) {
            return handleTraktPublicProxy(request, env, path);
        }
    }

    // 更新检查和更新日志只读取固定的公开 release 仓库，不应依赖用户 JWT。
    if (isPublicUpdateReleasePath(path) && request.method === 'GET') {
        return handlePublicUpdateReleaseProxy(request, env, path);
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

    // AI 角色与能力统一由 Worker 代理，客户端不接触 MiMo 密钥或音色样本。
    if (path === '/api/ai' || path.startsWith('/api/ai/')) {
        return handleAiApi(request, env, requestId, path, payload, ctx);
    }

    if (path === '/api/auth/check' && request.method === 'POST') {
        return handleCheck(request, env, requestId, payload);
    }

    // 敏感 API 代理（需 JWT）
    if (path.startsWith('/api/tmdb/')) {
        return handleTmdbProxy(request, env, path);
    }
    if (path.startsWith('/api/trakt/oauth/')) {
        return handleTraktOAuth(request, env, path, payload.sub, requestId);
    }
    if (path.startsWith('/api/trakt/')) {
        return handleTraktProxy(request, env, path, payload.sub);
    }
    if (path.startsWith('/api/douban/')) {
        return handleDoubanProxy(request, env, path, ctx);
    }
    if (path.startsWith('/api/omdb/')) {
        return handleOmdbProxy(request, env, path);
    }

    // 密钥代理端点（需 JWT）：Gitee / GitHub / 百度翻译 / 崩溃日志
    // 客户端原直连第三方服务并自带密钥，现统一走网关由 worker 注入密钥。
    if (path.startsWith('/api/gitee/')) {
        return handleGiteeProxy(request, env, path);
    }
    if (path.startsWith('/api/github/')) {
        return handleGithubProxy(request, env, path);
    }
    if (path.startsWith('/api/translate/')) {
        return handleTranslateProxy(request, env, path, ctx);
    }
    // 兼容客户端 Retrofit baseUrl 尾斜杠拼出的 /api/crash-logs/ 路径
    if ((path === '/api/crash-logs' || path === '/api/crash-logs/') && request.method === 'POST') {
        return handleCrashLogProxy(request, env);
    }

    // 云端配置代理（需 JWT）：worker 服务端解密后返回明文
    if (path === '/api/config' && request.method === 'GET') {
        return handleConfigProxy(request, env, requestId);
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
        return listFriends(request, env, requestId);
    }
    if (path === '/admin/friends' && request.method === 'POST') {
        return createFriend(request, env, requestId);
    }

    // 朋友详情操作（/:id）
    const friendMatch = path.match(/^\/admin\/friends\/([^/]+)$/);
    const friendDetailMatch = path.match(/^\/admin\/friends\/([^/]+)\/detail$/);
    if (friendDetailMatch && request.method === 'GET') {
        return getFriendDetail(env, requestId, friendDetailMatch[1]);
    }
    if (friendMatch && request.method === 'PATCH') {
        return updateFriend(request, env, requestId, friendMatch[1]);
    }
    if (friendMatch && request.method === 'GET') {
        return listDevices(env, requestId, friendMatch[1]);
    }

    const devicesMatch = path.match(/^\/admin\/friends\/([^/]+)\/devices$/);
    if (devicesMatch && request.method === 'GET') {
        return listDevices(env, requestId, devicesMatch[1]);
    }

    // 禁用朋友
    const disableMatch = path.match(/^\/admin\/friends\/([^/]+)\/disable$/);
    if (disableMatch && request.method === 'POST') {
        return disableFriend(env, requestId, disableMatch[1]);
    }

    // 重新启用朋友（不会恢复已撤销设备）
    const enableMatch = path.match(/^\/admin\/friends\/([^/]+)\/enable$/);
    if (enableMatch && request.method === 'POST') {
        return enableFriend(env, requestId, enableMatch[1]);
    }

    // 设备撤销
    const deviceMatch = path.match(/^\/admin\/devices\/([^/]+)\/revoke$/);
    if (deviceMatch && request.method === 'POST') {
        return revokeDevice(env, requestId, deviceMatch[1]);
    }

    const deviceDeleteMatch = path.match(/^\/admin\/devices\/([^/]+)$/);
    if (deviceDeleteMatch && request.method === 'DELETE') {
        return deleteDevice(env, requestId, deviceDeleteMatch[1]);
    }

    // 邀请码创建
    const inviteCreateMatch = path.match(/^\/admin\/friends\/([^/]+)\/invites$/);
    if (inviteCreateMatch && request.method === 'GET') {
        return listInvites(request, env, requestId, inviteCreateMatch[1]);
    }
    if (inviteCreateMatch && request.method === 'POST') {
        return createInvite(request, env, requestId, inviteCreateMatch[1]);
    }

    // 邀请码撤销
    const inviteRevokeMatch = path.match(/^\/admin\/invites\/([^/]+)\/revoke$/);
    if (inviteRevokeMatch && request.method === 'POST') {
        return revokeInvite(env, requestId, inviteRevokeMatch[1]);
    }

    // 朋友 IP 历史
    const ipLogsMatch = path.match(/^\/admin\/friends\/([^/]+)\/ip-logs$/);
    if (ipLogsMatch && request.method === 'GET') {
        return listFriendIpLogs(request, env, requestId, ipLogsMatch[1]);
    }

    // 审计日志
    if (path === '/admin/audit-logs' && request.method === 'GET') {
        return listAuditLogs(request, env, requestId);
    }

    // 法律与隐私请求
    if (path === '/admin/legal-requests' && request.method === 'GET') {
        return listLegalRequests(request, env, requestId);
    }
    const legalRequestCloseMatch = path.match(/^\/admin\/legal-requests\/([^/]+)\/close$/);
    if (legalRequestCloseMatch && request.method === 'POST') {
        return closeLegalRequest(request, env, requestId, legalRequestCloseMatch[1]);
    }

    // 健康检查
    if (path === '/admin/health' && request.method === 'GET') {
        return healthCheck(env, requestId);
    }

    throw new AppError('NOT_FOUND', 'Not found', 404);
}
