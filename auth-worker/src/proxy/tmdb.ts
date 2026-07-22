// TMDB 代理

import { Env } from '../index';
import { AppError, now } from '../util/errors';

const TMDB_BASE_URL = 'https://api.themoviedb.org/3';

// TMDB 路由前缀
const TMDB_PREFIX = '/api/tmdb/';

export async function handleTmdbProxy(
    request: Request,
    env: Env,
    path: string
): Promise<Response> {
    // 移除前缀，获取 TMDB 实际路径
    const tmdbPath = path.replace(TMDB_PREFIX, '');

    // 构建上游 URL（注入 API Key）
    const url = new URL(`${TMDB_BASE_URL}/${tmdbPath}`);
    url.searchParams.set('api_key', env.TMDB_API_KEY);

    // 复制客户端查询参数（排除 api_key）
    const clientUrl = new URL(request.url);
    for (const [key, value] of clientUrl.searchParams) {
        if (key !== 'api_key') {
            url.searchParams.set(key, value);
        }
    }

    // 代理请求
    const upstreamResponse = await fetch(url.toString(), {
        method: request.method,
        headers: {
            'Accept': 'application/json',
            'Content-Type': 'application/json',
        },
        body: request.method !== 'GET' ? await request.blob() : undefined,
    });

    if (!upstreamResponse.ok) {
        // 失败关闭：返回稳定错误，不回退
        return new Response(JSON.stringify({
            code: 'UPSTREAM_ERROR',
            message: `TMDB upstream error: ${upstreamResponse.status}`,
            requestId: crypto.randomUUID(),
        }), { status: upstreamResponse.status, headers: { 'Content-Type': 'application/json' } });
    }

    const data = await upstreamResponse.json();

    return new Response(JSON.stringify({
        code: 'SUCCESS',
        message: 'OK',
        requestId: crypto.randomUUID(),
        data,
    }), { status: 200, headers: { 'Content-Type': 'application/json' } });
}
