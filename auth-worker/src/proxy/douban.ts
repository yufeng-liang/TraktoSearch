// 豆瓣代理

import { Env } from '../index';
import { AppError } from '../util/errors';

const DOUBAN_BASE_URL = 'https://api.douban.com/v2';

const DOUBAN_PREFIX = '/api/douban/';

export async function handleDoubanProxy(
    request: Request,
    env: Env,
    path: string
): Promise<Response> {
    const doubanPath = path.replace(DOUBAN_PREFIX, '');

    const url = new URL(`${DOUBAN_BASE_URL}/${doubanPath}`);
    url.searchParams.set('apikey', env.DOUBAN_API_KEY);

    // 复制客户端查询参数
    const clientUrl = new URL(request.url);
    for (const [key, value] of clientUrl.searchParams) {
        if (key !== 'apikey') {
            url.searchParams.set(key, value);
        }
    }

    const upstreamResponse = await fetch(url.toString(), {
        method: request.method,
        headers: {
            'Accept': 'application/json',
        },
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
