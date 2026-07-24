// OMDb 代理

import { Env } from '../index';
import { KeyPool, fetchWithKeyRotation } from '../util/key-pool';

const OMDB_BASE_URL = 'https://www.omdbapi.com/';

const OMDB_PREFIX = '/api/omdb/';

export async function handleOmdbProxy(
    request: Request,
    env: Env,
    path: string
): Promise<Response> {
    const clientUrl = new URL(request.url);
    const body = request.method === 'GET' || request.method === 'HEAD'
        ? undefined
        : await request.arrayBuffer();
    const pool = new KeyPool('omdb', env.OMDB_API_KEY, env.KV);
    const upstreamResponse = await fetchWithKeyRotation(
        pool,
        async (key) => {
            const url = new URL(OMDB_BASE_URL);
            url.searchParams.set('apikey', key);
            for (const [queryKey, value] of clientUrl.searchParams) {
                if (queryKey !== 'apikey') url.searchParams.set(queryKey, value);
            }
            return fetch(url.toString(), {
                method: request.method,
                headers: { 'Accept': 'application/json' },
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
