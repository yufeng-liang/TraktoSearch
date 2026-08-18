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

    // 路由：/api/tmdb-image(-v2)/* → 直接代理 TMDB 图片（不经过 Service Binding）
    // v2：回源请求 WebP（Accept 协商），缓存 key 版本隔离，避免旧 JPEG 缓存（30 天 TTL）挡住新格式
    if (/^\/api\/tmdb-image(-v2)?\//.test(upstreamPath)) {
        return handleTmdbImage(request, upstreamPath, context);
    }

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
            cachePlan && upstream.status === 200 ? 'public, max-age=0' : 'no-store'
        );
        // 边缘缓存 TTL 用 CDN-Cache-Control 单独控制：s-maxage 隐含 proxy-revalidate，
        // 与 stale-while-revalidate 混用会使其失效（Cloudflare 官方文档明确禁止）；
        // 客户端 max-age=0 不缓存 API 响应（App 有自建缓存层）。
        if (cachePlan && upstream.status === 200) {
            responseHeaders.set('CDN-Cache-Control', `max-age=${cachePlan.ttlSeconds}, stale-while-revalidate=60`);
        }
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

/**
 * TMDB 图片代理：App 国内直连 image.tmdb.org 慢/不稳定，改为走 Cloudflare Pages 边缘节点。
 * 图片 URL 内容不变，长 TTL 边缘缓存（30 天），命中后零回源。
 * v2 起回源请求 WebP（Accept 协商），比 JPEG 平均小 30-50%。
 * 路径白名单严格校验，防止网关变成开放代理被滥用。
 */
const TMDB_IMAGE_ALLOWED_SIZES = new Set(['w92', 'w185', 'w342', 'w500', 'w780', 'original', 'h632']);

async function handleTmdbImage(request, upstreamPath, context) {
    if (request.method !== 'GET' && request.method !== 'HEAD') {
        return jsonResponse({ code: 'METHOD_NOT_ALLOWED', message: 'Method not allowed' }, 405);
    }

    // 兼容 v1（JPEG）与 v2（WebP）前缀，v2 缓存 key 独立
    const v2 = upstreamPath.startsWith('/api/tmdb-image-v2/');
    const rest = upstreamPath.slice(v2 ? '/api/tmdb-image-v2'.length : '/api/tmdb-image'.length); // /t/p/...
    const parts = rest.split('/');
    if (parts.length < 5 || parts[0] !== '' || parts[1] !== 't' || parts[2] !== 'p') {
        return jsonResponse({ code: 'INVALID_IMAGE_PATH', message: 'Invalid image path' }, 400);
    }
    const size = parts[3];
    if (!TMDB_IMAGE_ALLOWED_SIZES.has(size)) {
        return jsonResponse({ code: 'INVALID_IMAGE_SIZE', message: 'Invalid image size' }, 400);
    }
    const filePart = parts.slice(4).join('/');
    if (!/^[A-Za-z0-9_\-./]+\.(jpg|jpeg|png|webp)$/.test(filePart)) {
        return jsonResponse({ code: 'INVALID_IMAGE_FILE', message: 'Invalid image file' }, 400);
    }

    // v2 回源 URL 带版本参数：CF CDN 层（cf.cacheTtl 边缘缓存）缓存 key 不含 Accept，
    // 不加参数会命中 v1 缓存的 JPEG，回源永远拿不到 WebP。TMDB CloudFront 忽略该参数。
    const upstreamUrl = 'https://image.tmdb.org/t/p/' + size + '/' + filePart + (v2 ? '?v2=1' : '');

    // 边缘缓存（30 天）：用网关内部 URL 作 key，与 API 缓存隔离。
    // v2 再加 /v2 前缀：缓存 key 版本隔离，避免上轮部署写入的 JPEG 缓存挡住 WebP。
    const cache = globalThis.caches?.default;
    const cacheKey = new Request('https://gateway.internal' + (v2 ? '/v2' : '') + upstreamPath, { method: 'GET' });
    if (cache) {
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
            // 缓存不可用继续回源，不影响可用性
        }
    }

    try {
        const upstream = await fetch(upstreamUrl, {
            headers: {
                'User-Agent': 'TrackToSearch-ImageProxy/1.0',
                // 请求 WebP：TMDB CloudFront 按 Accept 协商返回 webp，比 JPEG 小 30-50%。
                // 固定单一格式（App 端原生支持 webp 解码），缓存无需 Vary 分片。
                'Accept': 'image/webp,image/*',
            },
            cf: { cacheTtl: 2592000, cacheEverything: true },
        });

        if (!upstream.ok) {
            // 上游错误（4xx/5xx）不缓存，原样转发状态码
            const headers = new Headers(upstream.headers);
            headers.set('Content-Type', upstream.headers.get('Content-Type') || 'image/webp');
            headers.set('Cache-Control', 'no-store');
            return new Response(upstream.body, { status: upstream.status, headers });
        }

        const headers = new Headers(upstream.headers);
        // stale-if-error：TMDB 故障时边缘仍可服务 stale 内容兜底（30 天缓存窗口内几乎无感，仅防边界 MISS 暴露）
        headers.set('Cache-Control', 'public, max-age=86400, s-maxage=2592000, immutable, stale-if-error=86400');
        headers.set('Content-Type', upstream.headers.get('Content-Type') || 'image/jpeg');
        headers.set('X-Gateway-Cache', 'MISS');
        for (const [name, value] of Object.entries(corsHeaders())) {
            if (!headers.has(name)) headers.set(name, value);
        }

        const response = new Response(upstream.body, { status: 200, statusText: 'OK', headers });
        if (cache) {
            const cacheWrite = cache.put(cacheKey, response.clone()).catch(() => undefined);
            if (typeof context.waitUntil === 'function') {
                context.waitUntil(cacheWrite);
            } else {
                await cacheWrite;
            }
        }
        return response;
    } catch {
        return new Response('Upstream unavailable', {
            status: 502,
            headers: { 'Content-Type': 'text/plain', 'Cache-Control': 'no-store' },
        });
    }
}
