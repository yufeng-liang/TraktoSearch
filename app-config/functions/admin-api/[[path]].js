/**
 * 后台 API 同源代理。
 * Cloudflare Access 会把 JWT 放在 Cf-Access-Jwt-Assertion 请求头中，
 * 代理将其转换为 Worker 需要的 Authorization Bearer 头，避免前端读取 Cookie。
 */
export async function onRequest(context) {
    const { request, env } = context;
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

    // 按路径前缀选择 worker：/fb/* → feedback-worker（去掉 /fb 前缀），其余 → auth-worker
    let actualPath = upstreamPath;
    let worker;
    if (upstreamPath.startsWith('/fb/')) {
        worker = env?.FEEDBACK_WORKER;
        actualPath = upstreamPath.slice(3); // 去掉 /fb 前缀
    } else {
        worker = env?.AUTH_WORKER;
    }

    if (!worker || typeof worker.fetch !== 'function') {
        return jsonResponse({ code: 'UPSTREAM_BINDING_UNAVAILABLE', message: 'Admin service unavailable' }, 503);
    }

    const upstreamUrl = new URL(`https://app-config.internal${actualPath}`);
    upstreamUrl.search = url.search;

    const headers = new Headers(request.headers);
    // 前端会在本地保存最新的 Access JWT。优先使用显式 Authorization，避免
    // Cloudflare 注入的旧 Cf-Access-Jwt-Assertion 覆盖刚刷新过的令牌。
    const accessToken = readBearerToken(headers.get('Authorization'))
        || headers.get('Cf-Access-Jwt-Assertion')
        || readCookie(headers.get('Cookie'));
    headers.delete('Authorization');
    if (accessToken) {
        headers.set('Authorization', `Bearer ${accessToken}`);
    }
    headers.delete('Cookie');
    headers.delete('Cf-Access-Jwt-Assertion');
    headers.delete('Host');
    headers.delete('Content-Length');
    headers.set('Origin', url.origin);

    let upstream;
    try {
        upstream = await worker.fetch(new Request(upstreamUrl, {
            method: request.method,
            headers,
            body: request.method === 'GET' || request.method === 'HEAD' ? undefined : request.body,
            duplex: 'half',
        }));
    } catch {
        return jsonResponse({ code: 'UPSTREAM_UNAVAILABLE', message: 'Admin service unavailable' }, 502);
    }

    const responseHeaders = new Headers(upstream.headers);
    const isImmutableScreenshot = actualPath.startsWith('/feedback-api/screenshot/');
    responseHeaders.set(
        'Cache-Control',
        isImmutableScreenshot ? 'private, max-age=2592000' : 'no-store'
    );
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

function readBearerToken(header) {
    return header?.startsWith('Bearer ') ? header.slice(7).trim() : null;
}

function jsonResponse(body, status) {
    return new Response(JSON.stringify(body), {
        status,
        headers: {
            'Content-Type': 'application/json',
            'Cache-Control': 'no-store',
        },
    });
}
