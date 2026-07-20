/**
 * Auth middleware for /api/*
 * Verifies Bearer token against CRASH_LOG_TOKEN env var.
 */
export async function onRequest(context) {
    const { request, env } = context;
    const url = new URL(request.url);

    // GET /api/crash-logs 允许不带 token（返回空列表也安全）
    // 但最好还是验证。这里统一验证。
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
