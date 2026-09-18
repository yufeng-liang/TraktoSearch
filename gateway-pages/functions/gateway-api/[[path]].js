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

    // CORS 预检必须先于一切路由分支处理：tmdb-image 等分支对非 GET 返回 405，
    // 若放在 OPTIONS 之前，浏览器预检会拿到 405 而失败
    if (request.method === 'OPTIONS') {
        return new Response(null, {
            status: 204,
            headers: { ...corsHeaders(), ...SECURITY_HEADERS },
        });
    }

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

    const worker = env?.[bindingName];
    if (!worker || typeof worker.fetch !== 'function') {
        return jsonResponse({
            code: 'UPSTREAM_BINDING_UNAVAILABLE',
            message: 'Gateway service unavailable',
        }, 503);
    }

    // ?purge=1 是 auth-worker 侧豆瓣热榜等公开数据的强制刷新入口：必须穿透边缘缓存
    // 直连回源；回源成功后用「剥掉 purge 的 cacheKey」把新数据写回边缘，
    // 否则普通请求会继续命中旧条目，手动刷新在 TTL 窗口内对任何人都不生效
    const isPurge = url.searchParams.has('purge');
    const cachePlan = getPublicCachePlan(request, upstreamPath);
    const cache = cachePlan && !isPurge ? globalThis.caches?.default : undefined;
    // 缓存 key 只保留路径与查询串，剥离 Authorization / Cookie 等请求头：
    // 登录用户与访客请求同一公开资源时应共享同一份边缘缓存。
    const cacheKey = cachePlan
        ? new Request(publicCacheKey(purgelessUrl(url)), { method: 'GET' })
        : null;
    if (cache && cacheKey) {
        try {
            const cached = await cache.match(cacheKey);
            if (cached) {
                const headers = new Headers(cached.headers);
                headers.set('X-Gateway-Cache', 'HIT');
                // 边缘命中后，响应里保存的 X-Media-Cache 只是首次回源时的值；
                // 明确覆盖为 EDGE，避免把旧 MISS/D1 当成当前请求的真实缓存状态。
                headers.set('X-Media-Cache', 'EDGE');
                if (!headers.has('X-Media-Partial')) {
                    headers.set('X-Media-Partial', 'false');
                }
                // 缓存条目里存的是边缘 TTL；回给客户端时仍按「客户端不缓存」处理（App 有自建缓存层）
                headers.set('Cache-Control', 'public, max-age=0');
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
        // 上游 service binding 调用必须有超时：挂起时 App 请求会跟随挂到平台超时
        const upstream = await worker.fetch(new Request(upstreamUrl, {
            method: request.method,
            headers,
            body: request.method === 'GET' || request.method === 'HEAD' ? undefined : request.body,
            duplex: 'half',
            signal: AbortSignal.timeout(30_000),
        }));

        const responseHeaders = new Headers(upstream.headers);
        // 客户端响应维持「不缓存」，真正省请求靠 App 自建缓存 + 下面的边缘缓存条目。
        responseHeaders.set(
            'Cache-Control',
            cachePlan && upstream.status === 200 ? 'public, max-age=0' : 'no-store'
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

        // 只有存在真实回源失败时才跳过共享边缘缓存；纯缓存混合命中不算 partial。
        const mediaPartial = upstreamPath.startsWith('/api/media/') &&
            response.headers.get('X-Media-Partial') === 'true';
        const canCacheUpstream = cachePlan && upstream.status === 200 && !mediaPartial;
        // purge 回源也写回（cache 为 undefined 时手动取 caches.default）
        const writeCache = cacheKey && canCacheUpstream
            ? (cache || globalThis.caches?.default)
            : undefined;
        if (writeCache) {
            // 写入边缘缓存的条目单独覆盖 TTL：Workers Cache API 的 put() 只识别
            // Cache-Control（不识别 CDN-Cache-Control），且 max-age=0 / no-store 的响应会被拒绝写入。
            // 因此条目用 cachePlan 的 TTL，回给客户端的头仍保持 max-age=0。
            const cacheEntrySource = response.clone();
            const cacheEntryHeaders = new Headers(cacheEntrySource.headers);
            cacheEntryHeaders.set('Cache-Control', `public, max-age=${cachePlan.ttlSeconds}`);
            const cacheEntry = new Response(cacheEntrySource.body, {
                status: cacheEntrySource.status,
                statusText: cacheEntrySource.statusText,
                headers: cacheEntryHeaders,
            });
            const cacheWrite = writeCache.put(cacheKey, cacheEntry).catch(() => undefined);
            if (typeof context.waitUntil === 'function') {
                context.waitUntil(cacheWrite);
            } else {
                await cacheWrite;
            }
        }

        return response;
    } catch (err) {
        // 记录上游错误摘要（不含请求头/响应体，避免敏感信息进日志），502 才有得排障
        console.error(JSON.stringify({
            event: 'gateway_upstream_error',
            path: upstreamPath,
            message: err instanceof Error ? err.message : String(err),
        }));
        return jsonResponse({ code: 'UPSTREAM_UNAVAILABLE', message: 'Gateway unavailable' }, 502);
    }
}

/**
 * 仅缓存「公开只读」资源。
 *
 * 是否可缓存由路径与方法决定，与请求是否携带 Authorization 无关：App 的
 * AuthInterceptor 会给所有网关请求统一注入 Authorization，若因为请求头就绕过缓存，
 * 登录用户就拿不到任何边缘缓存；而 TMDB / Trakt 公开端点 / OMDb / 豆瓣热榜对所有人返回同一份数据。
 *
 * 私有路径（Trakt sync/recommendations/users、oauth、progress、comments）
 * 不在白名单内，永远不会写入共享缓存，因此不会串号。
 */
function getPublicCachePlan(request, path) {
    if (request.method !== 'GET') return null;
    // TMDB 账号态端点靠 session_id / guest_session_id 标识身份（查询串或路径段），
    // 这类响应属于单个用户，绝不能写进所有人共享的边缘缓存（App 不使用这些端点）。
    if (hasUserScopedQuery(request.url) || hasUserScopedPath(path)) return null;
    // ?purge=1 不在这里判定：主流程会穿透读缓存（isPurge），但仍按本 plan
    // 把回源结果写回「剥掉 purge 的 key」，让后续普通请求拿到刷新后的数据。

    if (path.startsWith('/api/tmdb/')) return { ttlSeconds: 600 };
    if (path === '/api/media/summaries') return { ttlSeconds: 3600 };
    // detail 响应含 summary 易变字段（评分/时长，内部 volatile TTL 24h）；
    // 边缘 TTL 校准到与 summaries 一致，避免边缘缓存把易变数据滞后放大到 24h+6h。
    if (path === '/api/media/detail') return { ttlSeconds: 3600 };
    if (path.startsWith('/api/douban/')) {
        // 热榜单点（api/chart、api/weekly、api/nowplaying、api/top250）由 auth-worker
        // 直接抓豆瓣网页，内部 caches.default 已缓存 12~24h；边缘给 1h 挡重复回源、
        // 降低 worker 允许量消耗，新鲜度仍由 App 端 6h 客户端缓存主导。
        // 其余豆瓣端点（搜索/详情等，透传 douban-movie-api）动态性高，维持短缓存。
        const doubanPath = path.slice('/api/douban/'.length);
        const hot = doubanPath === 'api/chart' || doubanPath === 'api/weekly'
            || doubanPath === 'api/nowplaying' || doubanPath === 'api/top250';
        return { ttlSeconds: hot ? 3600 : 300 };
    }
    if (path.startsWith('/api/omdb/')) return { ttlSeconds: 600 };

    // 排除集与 auth-worker 的 isTraktPublicPath 保持一致（唯一事实来源），改动需两端同步；
    // 公开端点对所有人返回同一份数据，缓存 300s；私有路径绝不写入共享缓存，避免串号。
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

/** 判断请求是否携带会改变响应归属的用户级查询凭据。 */
function hasUserScopedQuery(rawUrl) {
    const params = new URL(rawUrl).searchParams;
    return params.has('session_id') || params.has('guest_session_id');
}

/** 判断路径本身是否含用户级凭据段，如 /api/tmdb/guest_session/{id}/rated/movies。 */
function hasUserScopedPath(path) {
    return /^\/api\/tmdb\/(?:guest_session|account)\//.test(path);
}

/** 构造公开缓存 key：只保留路径与查询串，剥离所有凭据类请求头。 */
function publicCacheKey(url) {
    return url.origin + url.pathname + url.search;
}

/** 缓存 key 用的 URL：剥掉 ?purge 强刷参数，普通请求与 purge 回源写回共用同一份条目。
 * 必须字符串级过滤——走 URLSearchParams 会触发 query 重新序列化（`:` 变 `%3A` 等），
 * 缓存 key 会与普通请求的原始 URL 对不上。 */
function purgelessUrl(url) {
    if (!url.search) return url;
    const pairs = url.search.slice(1).split('&')
        .filter((pair) => pair.split('=')[0] !== 'purge');
    const search = pairs.length ? `?${pairs.join('&')}` : '';
    return { origin: url.origin, pathname: url.pathname, search };
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
