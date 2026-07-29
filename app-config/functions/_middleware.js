/**
 * Pages Functions 全局中间件：
 * 1. 为所有响应注入安全响应头（X-Content-Type-Options / CSP / HSTS 等）
 * 2. 把 Cloudflare Access JWT 注入后台页面
 *
 * Access 登录后会在当前域名的 CF_Authorization Cookie 中保存 JWT，
 * 后台前端再把它作为 Bearer 令牌转发给授权 Worker。
 */

/** 安全响应头：纯 API/静态站点收紧 CSP，禁止第三方嵌入和资源加载 */
const SECURITY_HEADERS = {
    'X-Content-Type-Options': 'nosniff',
    'X-Frame-Options': 'DENY',
    'Referrer-Policy': 'strict-origin-when-cross-origin',
    'Permissions-Policy': 'geolocation=(), microphone=(), camera=(), payment=()',
    'Strict-Transport-Security': 'max-age=31536000; includeSubDomains',
    'X-XSS-Protection': '0',
    // app-config 含静态 HTML 后台，允许同源脚本与样式，禁止第三方资源加载
    'Content-Security-Policy': "default-src 'self'; script-src 'self' 'unsafe-inline'; style-src 'self' 'unsafe-inline'; img-src 'self' data:; frame-ancestors 'none'; base-uri 'self'; object-src 'none'",
};

function applySecurityHeaders(response) {
    const headers = new Headers(response.headers);
    for (const [key, value] of Object.entries(SECURITY_HEADERS)) {
        if (!headers.has(key)) headers.set(key, value);
    }
    return new Response(response.body, {
        status: response.status,
        statusText: response.statusText,
        headers,
    });
}

export async function onRequest(context) {
    const { request, next } = context;
    const url = new URL(request.url);

    // 非后台页面：仅叠加安全响应头
    if (!url.pathname.startsWith('/admin/')) {
        const response = await next();
        return applySecurityHeaders(response);
    }

    const cookie = request.headers.get('Cookie') || '';
    const tokenMatch = cookie.match(/(?:^|;\s*)CF_Authorization=([^;]+)/);
    const accessToken = request.headers.get('Cf-Access-Jwt-Assertion') || decodeCookieToken(tokenMatch?.[1]);
    const response = await next();

    if (!accessToken || !response.headers.get('content-type')?.includes('text/html')) {
        return applySecurityHeaders(response);
    }

    const html = await response.text();
    const email = readJwtEmail(accessToken);
    const injectedHtml = html.replace(
        '<head>',
        `<head><script>window.__ADMIN_EMAIL__ = ${JSON.stringify(email || '管理员')};</script>`
    );

    const headers = new Headers(response.headers);
    headers.set('Content-Type', 'text/html; charset=UTF-8');
    headers.delete('Content-Length');
    // 注入业务头后再叠加安全头
    const rebuilt = new Response(injectedHtml, {
        status: response.status,
        statusText: response.statusText,
        headers,
    });
    return applySecurityHeaders(rebuilt);
}

function readJwtEmail(token) {
    if (!token) return null;
    try {
        const payload = token.split('.')[1];
        const base64 = payload.replace(/-/g, '+').replace(/_/g, '/').padEnd(Math.ceil(payload.length / 4) * 4, '=');
        return JSON.parse(atob(base64)).email || null;
    } catch {
        return null;
    }
}

function decodeCookieToken(value) {
    if (!value) return null;
    try {
        return decodeURIComponent(value);
    } catch {
        return null;
    }
}
