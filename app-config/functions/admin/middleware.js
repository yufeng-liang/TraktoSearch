/**
 * Pages Function 中间件：提取 Access JWT 并注入页面
 *
 * Cloudflare Access 认证后会在 cookie 中设置 CF_Authorization
 * 本中间件从 cookie 提取 JWT 并注入到页面，供前端 fetch 使用
 */

export async function onRequest(context) {
    const { request, next } = context;
    const url = new URL(request.url);

    // 只对 admin 页面生效
    if (!url.pathname.startsWith('/admin')) {
        return next();
    }

    // 获取 Access JWT（从 cookie）
    const cookie = request.headers.get('Cookie') || '';
    const tokenMatch = cookie.match(/CF_Authorization=([^;]+)/);
    const accessToken = tokenMatch ? decodeURIComponent(tokenMatch[1]) : null;

    // 如果没有 token，Access 会自动拦截并跳转登录（Access 配置决定）
    // 这里只是注入 token 到页面
    const response = await next();
    if (!accessToken) return response;

    // 注入 token 到 HTML
    const html = await response.text();
    const injectedHtml = html.replace(
        '<head>',
        `<head><script>localStorage.setItem('tts-access-token', '${accessToken}');</script>`
    );

    return new Response(injectedHtml, {
        status: response.status,
        headers: {
            'Content-Type': 'text/html',
            ...Object.fromEntries(response.headers),
        },
    });
}
