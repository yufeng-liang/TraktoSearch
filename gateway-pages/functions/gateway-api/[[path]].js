const WORKER_ORIGIN = 'https://auth-worker.douban-movie-api-peak.workers.dev';
const PUBLIC_PREFIX = '/gateway-api';

/**
 * App 网关同源代理。
 * 只转发 Worker 的健康检查和 /api/*，避免把 Admin 接口变成公开代理。
 */
export async function onRequest(context) {
    const { request } = context;
    const url = new URL(request.url);
    const upstreamPath = url.pathname.slice(PUBLIC_PREFIX.length) || '/';

    if (upstreamPath !== '/health' && !upstreamPath.startsWith('/api/')) {
        return jsonResponse({ code: 'NOT_FOUND', message: 'Not found' }, 404);
    }

    if (request.method === 'OPTIONS') {
        return new Response(null, {
            status: 204,
            headers: corsHeaders(),
        });
    }

    const upstreamUrl = new URL(`${WORKER_ORIGIN}${upstreamPath}`);
    upstreamUrl.search = url.search;

    const headers = new Headers(request.headers);
    headers.delete('Host');
    headers.delete('Content-Length');
    headers.delete('Cookie');
    headers.delete('Cf-Access-Jwt-Assertion');
    headers.set('Origin', WORKER_ORIGIN);

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
