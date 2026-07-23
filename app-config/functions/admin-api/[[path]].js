/**
 * 后台 API 同源代理。
 * Cloudflare Access 会把 JWT 放在 Cf-Access-Jwt-Assertion 请求头中，
 * 代理将其转换为 Worker 需要的 Authorization Bearer 头，避免前端读取 Cookie。
 */
const WORKER_ORIGIN = 'https://auth-worker.douban-movie-api-peak.workers.dev';

export async function onRequest(context) {
    const { request } = context;
    const url = new URL(request.url);

    if (request.method === 'OPTIONS') {
        return new Response(null, {
            status: 204,
            headers: {
                'Access-Control-Allow-Origin': url.origin,
                'Access-Control-Allow-Methods': 'GET, POST, PATCH, OPTIONS',
                'Access-Control-Allow-Headers': 'Content-Type, Authorization',
            },
        });
    }

    const upstreamPath = url.pathname.slice('/admin-api'.length) || '/';
    const upstreamUrl = new URL(`${WORKER_ORIGIN}${upstreamPath}`);
    upstreamUrl.search = url.search;

    const headers = new Headers(request.headers);
    const accessToken = headers.get('Cf-Access-Jwt-Assertion') || readCookie(headers.get('Cookie'));
    if (accessToken) {
        headers.set('Authorization', `Bearer ${accessToken}`);
    }
    headers.delete('Cookie');
    headers.delete('Cf-Access-Jwt-Assertion');
    headers.set('Origin', WORKER_ORIGIN);

    const upstream = await fetch(new Request(upstreamUrl, {
        method: request.method,
        headers,
        body: request.method === 'GET' || request.method === 'HEAD' ? undefined : request.body,
    }));

    const responseHeaders = new Headers(upstream.headers);
    responseHeaders.set('Cache-Control', 'no-store');
    return new Response(upstream.body, {
        status: upstream.status,
        statusText: upstream.statusText,
        headers: responseHeaders,
    });
}

function readCookie(cookieHeader) {
    const match = (cookieHeader || '').match(/(?:^|;\s*)CF_Authorization=([^;]+)/);
    if (!match) return null;
    try {
        return decodeURIComponent(match[1]);
    } catch {
        return null;
    }
}
