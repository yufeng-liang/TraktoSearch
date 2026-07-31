/**
 * Auth middleware for /api/*
 * 兼容旧版客户端直连的崩溃日志接口，验证历史 CRASH_LOG_TOKEN。
 * 当前客户端统一走 gateway -> auth-worker -> CRASH_LOGS KV。
 */
export async function onRequest(context) {
    const { request, env } = context;

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
