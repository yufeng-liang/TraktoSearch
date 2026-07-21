/**
 * Auth middleware for /api/*
 * Verifies Bearer token against CRASH_LOG_TOKEN env var.
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
