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
    const { request, env } = context;
    const url = new URL(request.url);
    const upstreamPath = url.pathname.slice(PUBLIC_PREFIX.length) || '/';

    // 路由：/feedback-api/* → feedback-worker，其余 → auth-worker
    let bindingName = 'AUTH_WORKER';
    if (upstreamPath.startsWith('/feedback-api/')) {
        bindingName = 'FEEDBACK_WORKER';
    } else if (upstreamPath !== '/health' && !upstreamPath.startsWith('/api/')) {
        return jsonResponse({ code: 'NOT_FOUND', message: 'Not found' }, 404);
    }

    if (request.method === 'OPTIONS') {
        return new Response(null, {
            status: 204,
            headers: { ...corsHeaders(), ...SECURITY_HEADERS },
        });
    }

    const worker = env?.[bindingName];
    if (!worker || typeof worker.fetch !== 'function') {
        return jsonResponse({
            code: 'UPSTREAM_BINDING_UNAVAILABLE',
            message: 'Gateway service unavailable',
        }, 503);
    }

    const cachePlan = getPublicCachePlan(request, upstreamPath);
    const cache = cachePlan ? globalThis.caches?.default : undefined;
    const cacheKey = cache ? new Request(url.toString(), { method: 'GET' }) : null;
    if (cache && cacheKey) {
        try {
            const cached = await cache.match(cacheKey);
            if (cached) {
                const headers = new Headers(cached.headers);
                headers.set('X-Gateway-Cache', 'HIT');
                return new Response(cached.body, {
                    status: cached.status,
                    statusText: cached.statusText,
                    headers,
                });
            }
        } catch {
            // 缓存不可用时继续走 Service Binding，不影响 API 正常请求。
        }
    }

    const upstreamUrl = new URL(`https://gateway.internal${upstreamPath}`);
    upstreamUrl.search = url.search;

    const headers = new Headers(request.headers);
    headers.delete('Host');
    headers.delete('Content-Length');
    headers.delete('Cookie');
    headers.delete('Cf-Access-Jwt-Assertion');
    headers.set('Origin', url.origin);

    // 保留原始客户端 IP 与地理信息：service binding 调用下游 Worker 时，
    // Cloudflare 会用当前边缘节点出口 IP 覆盖 CF-Connecting-IP，
    // 且 request.cf 也会反映调用方（gateway）的边缘节点而非原始客户端。
    // 这里在 gateway 层把原始 CF-Connecting-IP 写入 X-Real-IP，
    // 并把 request.cf 的地理信息序列化到 X-Client-Geo，供下游 Worker 读取。
    const clientIp = request.headers.get('CF-Connecting-IP');
    if (clientIp) {
        headers.set('X-Real-IP', clientIp);
    }
    // Pages Functions 中 request.cf 才是标准属性（context.cf 不一定可用）
    const cf = request.cf;
    if (cf) {
        // 仅转发 IP 上报所需的地理字段，避免 header 过大
        const geo = {
            country: cf.country || '',
            region: cf.region || '',
            city: cf.city || '',
            latitude: cf.latitude || '',
            longitude: cf.longitude || '',
            asOrganization: cf.asOrganization || ''
        };
        headers.set('X-Client-Geo', JSON.stringify(geo));
    }

    try {
        const upstream = await worker.fetch(new Request(upstreamUrl, {
            method: request.method,
            headers,
            body: request.method === 'GET' || request.method === 'HEAD' ? undefined : request.body,
            duplex: 'half',
        }));

        const responseHeaders = new Headers(upstream.headers);
        responseHeaders.set(
            'Cache-Control',
            cachePlan && upstream.status === 200
                ? `public, max-age=0, s-maxage=${cachePlan.ttlSeconds}, stale-while-revalidate=60`
                : 'no-store'
        );
        responseHeaders.set('X-Gateway-Cache', cachePlan ? 'MISS' : 'BYPASS');
        for (const [name, value] of Object.entries(corsHeaders())) {
            if (!responseHeaders.has(name)) responseHeaders.set(name, value);
        }
        // 兜底安全响应头：上游 Worker 通常已设置，这里覆盖性补齐防止漏设
        for (const [name, value] of Object.entries(SECURITY_HEADERS)) {
            responseHeaders.set(name, value);
        }

        const response = new Response(upstream.body, {
            status: upstream.status,
            statusText: upstream.statusText,
            headers: responseHeaders,
        });

        if (cache && cacheKey && cachePlan && upstream.status === 200) {
            const cacheWrite = cache.put(cacheKey, response.clone()).catch(() => undefined);
            if (typeof context.waitUntil === 'function') {
                context.waitUntil(cacheWrite);
            } else {
                await cacheWrite;
            }
        }

        return response;
    } catch {
        return jsonResponse({ code: 'UPSTREAM_UNAVAILABLE', message: 'Gateway unavailable' }, 502);
    }
}

/**
 * 仅缓存不带用户凭据的只读资源。带 Authorization 的请求永远绕过缓存，
 * 因此不会把 watchlist、历史、评分或同步结果写入共享边缘缓存。
 */
function getPublicCachePlan(request, path) {
    if (request.method !== 'GET' || request.headers.has('Authorization') || request.headers.has('Cookie')) return null;

    if (path.startsWith('/api/tmdb/')) return { ttlSeconds: 600 };
    if (path.startsWith('/api/douban/')) return { ttlSeconds: 300 };
    if (path.startsWith('/api/omdb/')) return { ttlSeconds: 600 };

    if (path.startsWith('/api/trakt/')) {
        const traktPath = path.slice('/api/trakt/'.length);
        if (traktPath.startsWith('oauth/')) return null;
        if (traktPath.startsWith('sync/')) return null;
        if (traktPath.startsWith('recommendations/')) return null;
        if (traktPath.startsWith('users/')) return null;
        if (/^shows\/\d+\/progress\//.test(traktPath)) return null;
        if (traktPath === 'comments') return null;
        return { ttlSeconds: 300 };
    }

    return null;
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
