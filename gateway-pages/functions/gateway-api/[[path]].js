const AUTH_WORKER_ORIGIN = 'https://auth-worker.douban-movie-api-peak.workers.dev';
const FEEDBACK_WORKER_ORIGIN = 'https://feedback-worker.douban-movie-api-peak.workers.dev';
const PUBLIC_PREFIX = '/gateway-api';

/**
 * 安全响应头：网关纯 API 代理，CSP 收紧到完全无资源加载。
 * 上游 Worker 已设置同样的头，这里做兜底防止未来某些路径漏设。
 */
const SECURITY_HEADERS = {
    'X-Content-Type-Options': 'nosniff',
    'X-Frame-Options': 'DENY',
    'Referrer-Policy': 'strict-origin-when-cross-origin',
    'Permissions-Policy': 'geolocation=(), microphone=(), camera=(), payment=()',
    'Strict-Transport-Security': 'max-age=31536000; includeSubDomains',
    'X-XSS-Protection': '0',
    'Content-Security-Policy': "default-src 'none'; frame-ancestors 'none'; base-uri 'none'",
};

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
            headers: { ...corsHeaders(), ...SECURITY_HEADERS },
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
        // 兜底安全响应头：上游 Worker 通常已设置，这里覆盖性补齐防止漏设
        for (const [name, value] of Object.entries(SECURITY_HEADERS)) {
            responseHeaders.set(name, value);
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
            ...SECURITY_HEADERS,
            'Content-Type': 'application/json',
            'Cache-Control': 'no-store',
        },
    });
}
