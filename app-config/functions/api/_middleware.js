/**
 * Auth middleware for /api/*
 *
 * 两条鉴权路径：
 * 1. 后台管理读取（GET 列表/详情、PATCH 状态更新）：页面在 Cloudflare Access
 *    之后，校验 Access 注入的 JWT（Cf-Access-Jwt-Assertion）或 CF_Authorization
 *    cookie。前端无法持有服务端 CRASH_LOG_TOKEN，用 Access 凭据替代。
 * 2. 旧版客户端直连上报（POST）：兼容历史客户端，验证 CRASH_LOG_TOKEN。
 *    当前客户端已统一走 gateway -> auth-worker -> CRASH_LOGS KV。
 */
export async function onRequest(context) {
    const { request, env } = context;
    const url = new URL(request.url);

    if (url.pathname.startsWith('/api/crash-logs') && (request.method === 'GET' || request.method === 'PATCH')) {
        // Access 认证通过后注入 Cf-Access-Jwt-Assertion；cookie 作兜底
        const accessJwt = request.headers.get('Cf-Access-Jwt-Assertion');
        const cookie = request.headers.get('Cookie') || '';
        const hasAccessCookie = /(?:^|;\s*)CF_Authorization=/.test(cookie);
        if (!accessJwt && !hasAccessCookie) {
            return new Response(JSON.stringify({ error: 'Unauthorized' }), {
                status: 401,
                headers: { 'Content-Type': 'application/json' }
            });
        }
        return context.next();
    }

    const authHeader = request.headers.get('Authorization') || '';
    const token = authHeader.replace('Bearer ', '').trim();

    if (!token || token !== env.CRASH_LOG_TOKEN) {
        return new Response(JSON.stringify({ error: 'Unauthorized' }), {
            status: 401,
            headers: { 'Content-Type': 'application/json' }
        });
    }

    return context.next();
}
