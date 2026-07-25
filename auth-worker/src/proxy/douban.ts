// 豆瓣代理

import { Env } from '../index';
import { buildDoubanHeaders } from './douban-token';
import { KeyPool, fetchWithKeyRotation } from '../util/key-pool';

const DOUBAN_BASE_URL = 'https://douban-movie-api.pages.dev';

const DOUBAN_PREFIX = '/api/douban/';

export async function handleDoubanProxy(
    request: Request,
    env: Env,
    path: string
): Promise<Response> {
    // 保留原热榜服务的 `api/chart`、`api/weekly` 等路径。
    const doubanPath = path.replace(DOUBAN_PREFIX, '');

    const clientUrl = new URL(request.url);
    const body = request.method === 'GET' || request.method === 'HEAD'
        ? undefined
        : await request.arrayBuffer();
    const pool = new KeyPool('douban', env.DOUBAN_API_KEY, env.KV);
    const upstreamResponse = await fetchWithKeyRotation(
        pool,
        async (key) => {
            const url = new URL(`${DOUBAN_BASE_URL}/${doubanPath}`);
            for (const [queryKey, value] of clientUrl.searchParams) {
                url.searchParams.set(queryKey, value);
            }
            return fetch(url.toString(), {
                method: request.method,
                headers: buildDoubanHeaders(key),
                body,
            });
        },
        [401, 403, 429],
    );

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
