// OMDb 代理

import { Env } from '../index';
import { AppError } from '../util/errors';

const OMDB_BASE_URL = 'https://www.omdbapi.com/';

const OMDB_PREFIX = '/api/omdb/';

export async function handleOmdbProxy(
    request: Request,
    env: Env,
    path: string
): Promise<Response> {
    const url = new URL(OMDB_BASE_URL);
    url.searchParams.set('apikey', env.OMDB_API_KEY);

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
