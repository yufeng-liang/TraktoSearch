// Gitee API 代理（Worker Secret 注入鉴权）
//
// 客户端原直连 https://gitee.com/api/v5/ 并自带 Bearer token，
// 现改为走网关 /api/gitee/* ，由 worker 注入 GITEE_ACCESS_TOKEN，
// 避免密钥编译进 APK 被反编译泄露。
//
// 透明转发：路径、query、请求体保持原语义，仅替换鉴权头。

import { Env } from '../index';
import { AppError } from '../util/errors';

const GITEE_BASE_URL = 'https://gitee.com/api/v5';
const GITEE_PREFIX = '/api/gitee/';

export async function handleGiteeProxy(
    request: Request,
    env: Env,
    path: string
): Promise<Response> {
    if (!env.GITEE_ACCESS_TOKEN) {
        throw new AppError('SERVICE_UNAVAILABLE', 'Gitee proxy not configured', 503);
    }

    const giteePath = path.replace(GITEE_PREFIX, '');
    const clientUrl = new URL(request.url);
    const upstreamUrl = new URL(`${GITEE_BASE_URL}/${giteePath}`);
    // 透传 query 参数（如 ref、per_page、direction、page）
    upstreamUrl.search = clientUrl.search;

    const headers = new Headers();
    headers.set('Authorization', `token ${env.GITEE_ACCESS_TOKEN}`);
    headers.set('Accept', 'application/json');
    headers.set('User-Agent', 'TrackToSearch-Worker/3.0');
    // 透传 Content-Type（POST/PUT 带 JSON 请求体时需要）
    const clientContentType = request.headers.get('Content-Type');
    if (clientContentType) headers.set('Content-Type', clientContentType);

    const body = request.method === 'GET' || request.method === 'HEAD'
        ? undefined
        : await request.arrayBuffer();

    const upstream = await fetch(upstreamUrl.toString(), {
        method: request.method,
        headers,
        body,
    });

    return proxyResponse(upstream);
}

function proxyResponse(response: Response): Response {
    const headers = new Headers(response.headers);
    // 统一 JSON 响应（Gitee 错误也是 JSON）
    headers.set('Content-Type', headers.get('Content-Type') || 'application/json');
    return new Response(response.body, {
        status: response.status,
        headers,
    });
}
