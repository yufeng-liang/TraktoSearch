const AUTH_WORKER_ORIGIN = 'https://auth-worker.douban-movie-api-peak.workers.dev';
const FEEDBACK_WORKER_ORIGIN = 'https://feedback-worker.douban-movie-api-peak.workers.dev';
const PUBLIC_PREFIX = '/gateway-api';

/**
 * App 网关同源代理。
 * /api/* 和 /health → auth-worker，/feedback-api/* → feedback-worker。
 * 不转发 Admin 接口，避免变成公开代理。
 */
export async function onRequest(context) {
    const { request } = context;
    const url = new URL(request.url);
    const upstreamPath = url.pathname.slice(PUBLIC_PREFIX.length) || '/';

    // 路由：/feedback-api/* → feedback-worker，其余 → auth-worker
    let workerOrigin = AUTH_WORKER_ORIGIN;
    if (upstreamPath.startsWith('/feedback-api/')) {
        workerOrigin = FEEDBACK_WORKER_ORIGIN;
    } else if (upstreamPath !== '/health' && !upstreamPath.startsWith('/api/')) {
        return jsonResponse({ code: 'NOT_FOUND', message: 'Not found' }, 404);
    }

    if (request.method === 'OPTIONS') {
        return new Response(null, {
            status: 204,
            headers: corsHeaders(),
        });
    }

    const upstreamUrl = new URL(`${workerOrigin}${upstreamPath}`);
    upstreamUrl.search = url.search;

    const headers = new Headers(request.headers);
    headers.delete('Host');
    headers.delete('Content-Length');
    headers.delete('Cookie');
    headers.delete('Cf-Access-Jwt-Assertion');
    headers.set('Origin', workerOrigin);

    try {
        const upstream = await fetch(new Request(upstreamUrl, {
            method: request.method,
            headers,
            body: request.method === 'GET' || request.method === 'HEAD' ? undefined : request.body,
            duplex: 'half',
        }));

        const responseHeaders = new Headers(upstream.headers);
        responseHeaders.set('Cache-Control', 'no-store');
        for (const [name, value] of Object.entries(corsHeaders())) {
            if (!responseHeaders.has(name)) responseHeaders.set(name, value);
        }

        return new Response(upstream.body, {
            status: upstream.status,
            statusText: upstream.statusText,
            headers: responseHeaders,
        });
    } catch {
        return jsonResponse({ code: 'UPSTREAM_UNAVAILABLE', message: 'Gateway unavailable' }, 502);
    }
}

function corsHeaders() {
    return {
        'Access-Control-Allow-Origin': '*',
        'Access-Control-Allow-Methods': 'GET, POST, PATCH, OPTIONS',
        'Access-Control-Allow-Headers': 'Content-Type, Authorization',
        'Access-Control-Max-Age': '86400',
    };
}

function jsonResponse(body, status) {
    return new Response(JSON.stringify(body), {
        status,
        headers: {
            ...corsHeaders(),
            'Content-Type': 'application/json',
            'Cache-Control': 'no-store',
        },
    });
}
