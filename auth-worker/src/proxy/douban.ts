// 豆瓣代理

import type { Env } from '../index.ts';
import { buildDoubanHeaders } from './douban-token.ts';
import { KeyPool, fetchWithKeyRotation } from '../util/key-pool.ts';
import { scrapeDouban } from './douban-scrape.ts';
import { AppError } from '../util/errors.ts';

const DOUBAN_PREFIX = '/api/douban/';

// 热榜单点：直接抓取 movie.douban.com，用 HTMLRewriter 流式解析，绕过 douban-movie-api worker 的 10ms CPU 限制
const DIRECT_SCRAPE_PATHS = new Set(['api/chart', 'api/weekly', 'api/nowplaying', 'api/top250']);

export async function handleDoubanProxy(
    request: Request,
    env: Env,
    path: string
): Promise<Response> {
    // 保留原热榜服务的 `api/chart`、`api/weekly` 等路径。
    const doubanPath = path.replace(DOUBAN_PREFIX, '');
    const clientUrl = new URL(request.url);

    // 热榜单点：直接抓取豆瓣网页，HTMLRewriter 解析
    if (request.method === 'GET' && DIRECT_SCRAPE_PATHS.has(doubanPath)) {
        return scrapeDouban(doubanPath, clientUrl.searchParams);
    }

    // 其他豆瓣端点：通过 Service Binding 调用 douban-movie-api，避免公网二次计费请求
    if (!env.DOUBAN_WORKER || typeof env.DOUBAN_WORKER.fetch !== 'function') {
        throw new AppError('SERVICE_UNAVAILABLE', 'Douban service binding is not configured', 503);
    }

    const body = request.method === 'GET' || request.method === 'HEAD'
        ? undefined
        : await request.arrayBuffer();
    const pool = new KeyPool('douban', env.DOUBAN_API_KEY, env.KV);
    const upstreamResponse = await fetchWithKeyRotation(
        pool,
        async (key) => {
            const url = new URL(`https://douban-movie-api.internal/${doubanPath}`);
            for (const [queryKey, value] of clientUrl.searchParams) {
                url.searchParams.set(queryKey, value);
            }
            return env.DOUBAN_WORKER.fetch(new Request(url, {
                method: request.method,
                headers: buildDoubanHeaders(key),
                body,
            }));
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
