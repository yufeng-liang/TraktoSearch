// 豆瓣代理

import { Env } from '../index';
import { buildDoubanHeaders } from './douban-token';
import { KeyPool, fetchWithKeyRotation } from '../util/key-pool';
import { scrapeDouban } from './douban-scrape';

// 上游豆瓣热榜服务（仅作为非热榜单点的 fallback）
const DOUBAN_BASE_URL = 'https://douban-movie-api.douban-movie-api-peak.workers.dev';

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

    // 其他豆瓣端点：继续走 douban-movie-api worker 代理
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
