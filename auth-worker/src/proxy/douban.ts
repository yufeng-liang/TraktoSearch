// 豆瓣代理

import { Env } from '../index';
import { AppError } from '../util/errors';
import { buildDoubanHeaders } from './douban-token';

const DOUBAN_BASE_URL = 'https://douban-movie-api.pages.dev';

const DOUBAN_PREFIX = '/api/douban/';

export async function handleDoubanProxy(
    request: Request,
    env: Env,
    path: string
): Promise<Response> {
    // 保留原热榜服务的 `api/chart`、`api/weekly` 等路径。
    const doubanPath = path.replace(DOUBAN_PREFIX, '');

    const url = new URL(`${DOUBAN_BASE_URL}/${doubanPath}`);
    // 复制客户端查询参数
    const clientUrl = new URL(request.url);
    for (const [key, value] of clientUrl.searchParams) {
        url.searchParams.set(key, value);
    }

    const upstreamResponse = await fetch(url.toString(), {
        method: request.method,
        headers: buildDoubanHeaders(env.DOUBAN_API_KEY),
        body: request.method !== 'GET' ? await request.blob() : undefined,
    });

    return proxyResponse(upstreamResponse);
}

function proxyResponse(response: Response): Response {
    return new Response(response.body, {
        status: response.status,
        headers: {
            'Content-Type': 'application/json',
            ...Object.fromEntries(response.headers),
        },
    });
}
