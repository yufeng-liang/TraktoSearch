// GitHub API 代理（Worker Secret 注入鉴权）
//
// 客户端原直连 https://api.github.com/ 并自带 Bearer token 查询 releases，
// 现改为走网关 /api/github/* ，由 worker 注入 GITHUB_UPDATE_TOKEN，
// 避免密钥编译进 APK 被反编译泄露。
//
// 透明转发：路径、query 保持原语义，仅替换鉴权头。

import { Env } from '../index';
import { AppError } from '../util/errors';

const GITHUB_BASE_URL = 'https://api.github.com';
const GITHUB_PREFIX = '/api/github/';

export async function handleGithubProxy(
    request: Request,
    env: Env,
    path: string
): Promise<Response> {
    // token 为空时降级为匿名请求（受 GitHub 60次/小时限流），
    // 不报错以保持与原客户端行为一致。
    const githubPath = path.replace(GITHUB_PREFIX, '');
    const clientUrl = new URL(request.url);
    const upstreamUrl = new URL(`${GITHUB_BASE_URL}/${githubPath}`);
    upstreamUrl.search = clientUrl.search;

    const headers = new Headers();
    headers.set('Accept', 'application/vnd.github+json');
    headers.set('User-Agent', 'TrackToSearch-Worker/3.0');
    if (env.GITHUB_UPDATE_TOKEN) {
        headers.set('Authorization', `Bearer ${env.GITHUB_UPDATE_TOKEN}`);
    }

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
    headers.set('Content-Type', headers.get('Content-Type') || 'application/json');
    return new Response(response.body, {
        status: response.status,
        headers,
    });
}
