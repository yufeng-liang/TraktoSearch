/**
 * Pages Functions 全局中间件：把 Cloudflare Access JWT 注入后台页面。
 *
 * Access 登录后会在当前域名的 CF_Authorization Cookie 中保存 JWT，
 * 后台前端再把它作为 Bearer 令牌转发给授权 Worker。
 */
export async function onRequest(context) {
    const { request, next } = context;
    const url = new URL(request.url);

    // 仅处理后台页面，避免改写配置和其他 API 响应。
    if (!url.pathname.startsWith('/admin/')) {
        return next();
    }

    const cookie = request.headers.get('Cookie') || '';
    const tokenMatch = cookie.match(/(?:^|;\s*)CF_Authorization=([^;]+)/);
    const accessToken = request.headers.get('Cf-Access-Jwt-Assertion') || decodeCookieToken(tokenMatch?.[1]);
    const response = await next();

    if (!accessToken || !response.headers.get('content-type')?.includes('text/html')) {
        return response;
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
    return new Response(injectedHtml, {
        status: response.status,
        statusText: response.statusText,
        headers,
    });
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
