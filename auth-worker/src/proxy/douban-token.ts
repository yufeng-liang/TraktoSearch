/** 构造豆瓣热榜服务请求头。 */
export function buildDoubanHeaders(apiKey: string): HeadersInit {
    return {
        'Accept': 'application/json',
        'User-Agent': 'TrackToSearch/3.0',
        'X-API-Key': apiKey,
    };
}
