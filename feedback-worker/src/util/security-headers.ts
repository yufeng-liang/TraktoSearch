// 安全响应头工具：为 API 响应统一注入安全头，缓解 XSS、点击劫持、MIME 嗅探、
// 协议降级等常见 Web 风险。
//
// 适用对象：纯 API Worker（不返回 HTML 页面）。CSP 比浏览器站点更严格：
// default-src 'none' 禁止加载任何资源，frame-ancestors 'none' 阻止任何嵌入。
//
// HSTS：Cloudflare 默认在边缘启用 HTTPS，但客户端到 CF 的连接仍可能受中间人降级，
// 显式声明 HSTS 让支持的用户代理强制后续走 HTTPS（max-age 一年，含子域）。

const SECURITY_HEADERS: Record<string, string> = {
    'X-Content-Type-Options': 'nosniff',
    'X-Frame-Options': 'DENY',
    'Referrer-Policy': 'strict-origin-when-cross-origin',
    'Permissions-Policy': 'geolocation=(), microphone=(), camera=(), payment=()',
    'Strict-Transport-Security': 'max-age=31536000; includeSubDomains',
    'X-XSS-Protection': '0',
    // API Worker 不渲染 HTML，CSP 收紧到完全无资源加载
    'Content-Security-Policy': "default-src 'none'; frame-ancestors 'none'; base-uri 'none'",
    'Cache-Control': 'no-store',
};

/**
 * 给响应叠加安全响应头。
 *
 * - 不覆盖已设置的头（除非 `force` = true），保留业务侧自定义的 CSP / Cache-Control。
 * - 返回新 Response 实例（不可变性，避免污染上游 Response）。
 */
export function applySecurityHeaders(
    response: Response,
    options: { force?: boolean } = {}
): Response {
    const headers = new Headers(response.headers);
    for (const [key, value] of Object.entries(SECURITY_HEADERS)) {
        if (options.force || !headers.has(key)) {
            headers.set(key, value);
        }
    }
    return new Response(response.body, {
        status: response.status,
        statusText: response.statusText,
        headers,
    });
}

/** 仅返回安全头对象，便于在 new Response 时直接合并。 */
export function securityHeaders(): Record<string, string> {
    return { ...SECURITY_HEADERS };
}
