// TMDB 代理

import { Env } from '../index';
import { buildTmdbAuth } from './tmdb-token';
import { KeyPool, fetchWithKeyRotation } from '../util/key-pool';

const TMDB_BASE_URL = 'https://api.tmdb.org/3';
const TMDB_USER_AGENT = 'TrackToSearch/3.0';

// TMDB 路由前缀
const TMDB_PREFIX = '/api/tmdb/';

export async function handleTmdbProxy(
    request: Request,
    env: Env,
    path: string
): Promise<Response> {
    // 移除前缀，获取 TMDB 实际路径
    const tmdbPath = path.replace(TMDB_PREFIX, '');

    const clientUrl = new URL(request.url);
    const body = request.method === 'GET' || request.method === 'HEAD'
        ? undefined
        : await request.arrayBuffer();
    const pool = new KeyPool('tmdb', env.TMDB_API_KEY, env.KV);
    const upstreamResponse = await fetchWithKeyRotation(
        pool,
        async (key) => {
            const url = new URL(`${TMDB_BASE_URL}/${tmdbPath}`);
            const auth = buildTmdbAuth(key);
            if (auth.apiKey) url.searchParams.set('api_key', auth.apiKey);
            for (const [queryKey, value] of clientUrl.searchParams) {
                if (queryKey !== 'api_key') url.searchParams.set(queryKey, value);
            }
            return fetch(url.toString(), {
                method: request.method,
                headers: {
                    'Accept': 'application/json',
                    'Content-Type': 'application/json',
                    'User-Agent': TMDB_USER_AGENT,
                    ...auth.headers,
                },
                body,
            });
        },
        [401, 403, 429],
    );

    return proxyResponse(upstreamResponse);
}

function proxyResponse(response: Response): Response {
    const headers = new Headers(response.headers);
    headers.set('Content-Type', headers.get('Content-Type') || 'application/json');
    return new Response(response.body, { status: response.status, headers });
}
