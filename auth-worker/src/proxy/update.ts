import type { Env } from '../index';
import { AppError } from '../util/errors.ts';

const PUBLIC_UPDATE_RELEASE_PATH = '/api/gitee/repos/yufeng-liang/TraktoSearch-release/releases';
const GITEE_RELEASE_URL = 'https://gitee.com/api/v5/repos/yufeng-liang/TraktoSearch-release/releases';
const ALLOWED_QUERY_PARAMS = ['per_page', 'direction', 'page'] as const;

export function isPublicUpdateReleasePath(path: string): boolean {
    return path === PUBLIC_UPDATE_RELEASE_PATH;
}

/** 只代理固定发布仓库的只读 release 列表，避免公开通用 Gitee 代理。 */
export async function handlePublicUpdateReleaseProxy(
    request: Request,
    env: Pick<Env, 'GITEE_ACCESS_TOKEN'>,
    path: string,
): Promise<Response> {
    if (!isPublicUpdateReleasePath(path) || request.method !== 'GET') {
        throw new AppError('NOT_FOUND', 'Not found', 404);
    }

    const clientUrl = new URL(request.url);
    const upstreamUrl = new URL(GITEE_RELEASE_URL);
    for (const name of ALLOWED_QUERY_PARAMS) {
        const value = clientUrl.searchParams.get(name);
        if (isAllowedQueryParam(name, value)) {
            upstreamUrl.searchParams.set(name, value);
        }
    }

    const headers = new Headers({
        Accept: 'application/json',
        'User-Agent': 'TrackToSearch-Worker/3.0',
    });
    if (env.GITEE_ACCESS_TOKEN) {
        headers.set('Authorization', `token ${env.GITEE_ACCESS_TOKEN}`);
    }

    const upstream = await fetch(upstreamUrl.toString(), { method: 'GET', headers });
    const responseHeaders = new Headers(upstream.headers);
    responseHeaders.set('Content-Type', responseHeaders.get('Content-Type') || 'application/json');
    return new Response(upstream.body, {
        status: upstream.status,
        statusText: upstream.statusText,
        headers: responseHeaders,
    });
}

function isAllowedQueryParam(
    name: (typeof ALLOWED_QUERY_PARAMS)[number],
    value: string | null,
): value is string {
    if (value === null) return false;
    if (name === 'direction') return value === 'asc' || value === 'desc';
    if (name === 'per_page') return isPositiveIntegerInRange(value, 1, 100);
    return isPositiveIntegerInRange(value, 1, 10000);
}

function isPositiveIntegerInRange(value: string, min: number, max: number): boolean {
    if (!/^\d+$/.test(value)) return false;
    const number = Number(value);
    return number >= min && number <= max;
}
