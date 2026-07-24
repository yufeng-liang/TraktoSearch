// TMDB 代理

import { Env } from '../index';
import { AppError, now } from '../util/errors';
import { buildTmdbAuth } from './tmdb-token';

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

    // 构建上游 URL，并按凭据版本选择认证方式
    const url = new URL(`${TMDB_BASE_URL}/${tmdbPath}`);
    const auth = buildTmdbAuth(env.TMDB_API_KEY);
    if (auth.apiKey) url.searchParams.set('api_key', auth.apiKey);

    // 复制客户端查询参数（排除 api_key）
    const clientUrl = new URL(request.url);
    for (const [key, value] of clientUrl.searchParams) {
        if (key !== 'api_key') {
            url.searchParams.set(key, value);
        }
    }

    // 代理请求；成功响应保持 TMDB 原始 DTO 结构，不能额外包 GatewayResponse
    const upstreamResponse = await fetch(url.toString(), {
        method: request.method,
        headers: {
            'Accept': 'application/json',
            'Content-Type': 'application/json',
            'User-Agent': TMDB_USER_AGENT,
            ...auth.headers,
        },
        body: request.method !== 'GET' ? await request.blob() : undefined,
    });

    return proxyResponse(upstreamResponse);
}

function proxyResponse(response: Response): Response {
    const headers = new Headers(response.headers);
    headers.set('Content-Type', headers.get('Content-Type') || 'application/json');
    return new Response(response.body, { status: response.status, headers });
}
