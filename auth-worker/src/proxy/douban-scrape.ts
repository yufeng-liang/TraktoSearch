// 豆瓣热榜直接抓取（HTMLRewriter 流式解析，绕过 douban-movie-api worker 的 CPU 限制）
//
// 背景：douban-movie-api worker 在免费版 10ms CPU 限制下用 regex 解析 50KB+ HTML 会超限（error 1042）。
// HTMLRewriter 是 Workers 运行时内置的 C++ 流式 HTML 解析器，CPU 开销远低于 JS regex，
// 可在 10ms 内完成解析。缓存命中时直接返回，不触发抓取。

import type { Env } from '../index.ts';
import { AppError } from '../util/errors.ts';
import {
    applyCachedDoubanMappings,
    enrichDoubanItems,
    type DoubanEnrichableItem,
} from './douban-id-enrichment.ts';

const DOUBAN_HEADERS = {
    'User-Agent': 'Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36',
    'Accept': 'text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8',
    'Accept-Language': 'zh-CN,zh;q=0.9,en;q=0.8',
    'Referer': 'https://movie.douban.com/',
};

const CACHE_TTL: Record<string, number> = {
    nowplaying: 12 * 60 * 60,  // 12 小时
    chart: 12 * 60 * 60,
    weekly: 12 * 60 * 60,
    top250: 24 * 60 * 60,      // 1 天
};
const SCRAPE_CACHE_VERSION = 3;

// 豆瓣网页抓取可能因网络/反爬响应慢而挂起；超时避免 CPU 时间片空转，超时后走 MISS 返回。
const SCRAPE_FETCH_TIMEOUT_MS = 20 * 1000;

interface ChartItem {
    rank: number;
    title: string;
    subtitle: string;
    id: string;
    url: string;
    poster: string;
    rating: string;
    ratingCount: string;
    tmdbId: number;
    traktId: number;
    imdbId: string;
    mediaType: 'movie';
}

interface WeeklyItem {
    rank: number;
    title: string;
    id: string;
    url: string;
    poster: string;
    tmdbId: number;
    traktId: number;
    imdbId: string;
    mediaType: 'movie';
}

interface NowPlayingItem {
    title: string;
    id: string;
    url: string;
    poster: string;
    rating: string;
    ratingCount: string;
    release: string;
    duration: string;
    region: string;
    director: string;
    actors: string;
    tmdbId: number;
    traktId: number;
    imdbId: string;
    mediaType: 'movie';
}

interface Top250Item {
    rank: number;
    title: string;
    otherTitle: string;
    id: string;
    url: string;
    poster: string;
    rating: string;
    ratingCount: string;
    director: string;
    year: string;
    region: string;
    quote: string;
    tmdbId: number;
    traktId: number;
    imdbId: string;
    mediaType: 'movie';
}

/** 根据豆瓣代理子路径直接抓取豆瓣网页并返回 JSON。 */
export async function scrapeDouban(
    doubanPath: string,
    searchParams: URLSearchParams,
    env: Env,
    ctx?: Pick<ExecutionContext, 'waitUntil'>,
    options?: { allowPurge?: boolean },
): Promise<Response> {
    const cacheKey = buildCacheKey(doubanPath, searchParams);

    // ?purge=1 强制刷新缓存：仅限鉴权路径使用。匿名公开端点必须忽略 purge——
    // 否则任何人循环 ?purge=1 可绕过 12h 缓存反复触发 4 类 HTML 抓取，
    // 烧 CPU 配额并给豆瓣反爬递刀。App 端不使用 purge（下拉刷新由网关边缘缓存负责）。
    const purge = options?.allowPurge === true && searchParams.get('purge') === '1';
    if (purge) {
        try {
            await caches.default.delete(cacheKey);
        } catch {
            // 删除失败不影响后续抓取
        }
    } else {
        // 缓存命中时直接返回
        const cached = await caches.default.match(cacheKey);
        if (cached) {
            const data: Record<string, unknown> = await cached.json();
            const cachedItems = (data.data || []) as DoubanEnrichableItem[];
            const mapped = await applyCachedDoubanMappings(cachedItems, env.KV);
            data.data = mapped.items;
            data.cached = true;
            if (mapped.pending.length > 0 && ctx) {
                ctx.waitUntil(enrichAndRefreshCachedResult(cacheKey, doubanPath, data, mapped.pending, env));
            }
            return json(data);
        }
    }

    let result: Record<string, unknown>;
    if (doubanPath === 'api/chart') {
        result = await scrapeChart();
    } else if (doubanPath === 'api/weekly') {
        result = await scrapeWeekly();
    } else if (doubanPath === 'api/nowplaying') {
        result = await scrapeNowPlaying();
    } else if (doubanPath === 'api/top250') {
        const page = Math.max(1, Math.min(10, parseInt(searchParams.get('page') || '1', 10)));
        result = await scrapeTop250(page);
    } else {
        throw new AppError('NOT_FOUND', `Unknown douban endpoint: ${doubanPath}`, 404);
    }

    const rawItems = (result.data || []) as DoubanEnrichableItem[];
    const mapped = await applyCachedDoubanMappings(rawItems, env.KV);
    result.data = mapped.items;

    // 写入缓存（异步不阻塞响应）
    const ttl = CACHE_TTL[doubanPath.replace('api/', '')] || CACHE_TTL.chart;
    const cacheResponse = new Response(JSON.stringify(result), {
        headers: {
            'Content-Type': 'application/json',
            'Cache-Control': `public, max-age=${ttl}`,
        },
    });
    // 使用 waitUntil 确保缓存写入完成（由调用方注入 ctx）
    // 这里直接 await，因为 caches.default.put 在 Workers 中很快
    try {
        await caches.default.put(cacheKey, cacheResponse.clone());
    } catch {
        // 缓存写入失败不影响响应
    }

    if (mapped.pending.length > 0 && ctx) {
        ctx.waitUntil(enrichAndRefreshCachedResult(cacheKey, doubanPath, result, mapped.pending, env));
    }

    return json(result);
}

/**
 * 定时预热公开榜单首屏。缓存必须先失效再抓取，否则定时任务只会读到旧缓存。
 * Top250 后续页面按需抓取，避免每轮定时任务重复请求全部 250 条目。
 */
export async function refreshPublicDoubanLists(
    env: Env,
    ctx?: Pick<ExecutionContext, 'waitUntil'>,
): Promise<void> {
    const paths = ['api/chart', 'api/weekly', 'api/nowplaying', 'api/top250'];
    const results = await Promise.allSettled(paths.map((path) => scrapeDouban(
        path,
        new URLSearchParams([['purge', '1'], ...(path === 'api/top250' ? [['page', '1']] : [])]),
        env,
        ctx,
    )));

    results.forEach((result, index) => {
        if (result.status === 'rejected') {
            console.warn(JSON.stringify({
                event: 'douban_public_list_refresh_failed',
                path: paths[index],
                error: result.reason instanceof Error ? result.reason.message : String(result.reason),
            }));
        }
    });
}

async function enrichAndRefreshCachedResult(
    cacheKey: string,
    doubanPath: string,
    data: Record<string, unknown>,
    pending: DoubanEnrichableItem[],
    env: Env,
): Promise<void> {
    try {
        const enriched = await enrichDoubanItems(pending, env);
        const enrichedById = new Map(enriched.map((item) => [item.id, item]));
        const currentItems = (data.data || []) as DoubanEnrichableItem[];
        data.data = currentItems.map((item) => enrichedById.get(item.id) || item);

        const ttl = CACHE_TTL[doubanPath.replace('api/', '')] || CACHE_TTL.chart;
        await caches.default.put(cacheKey, new Response(JSON.stringify(data), {
            headers: {
                'Content-Type': 'application/json',
                'Cache-Control': `public, max-age=${ttl}`,
            },
        }));
    } catch (error) {
        console.warn(JSON.stringify({
            event: 'douban_id_cache_refresh_failed',
            path: doubanPath,
            error: error instanceof Error ? error.message : String(error),
        }));
    }
}

function buildCacheKey(doubanPath: string, searchParams: URLSearchParams): string {
    const page = searchParams.get('page');
    const pageSuffix = page
        ? `?page=${encodeURIComponent(page)}&v=${SCRAPE_CACHE_VERSION}`
        : `?v=${SCRAPE_CACHE_VERSION}`;
    return `https://douban-scrape.internal/${doubanPath}${pageSuffix}`;
}

function json(data: unknown): Response {
    return new Response(JSON.stringify(data), {
        status: 200,
        headers: { 'Content-Type': 'application/json; charset=utf-8' },
    });
}

// ==================== 新片榜 ====================

async function scrapeChart(): Promise<{ code: number; data: ChartItem[]; total: number; cached: boolean; updatedAt: string }> {
    const response = await fetch('https://movie.douban.com/chart', {
        headers: DOUBAN_HEADERS,
        signal: AbortSignal.timeout(SCRAPE_FETCH_TIMEOUT_MS),
    });

    const items: ChartItem[] = [];
    const state: { current: Partial<ChartItem> | null } = { current: null };
    let rank = 0;
    const ratingState = { capture: false, buffer: '' };

    const transformed = new HTMLRewriter()
        .on('tr.item', {
            element() {
                if (state.current && state.current.id) {
                    items.push(finalizeChartItem(state.current));
                }
                rank++;
                state.current = { rank, title: '', subtitle: '', id: '', url: '', poster: '', rating: '暂无评分', ratingCount: '' };
            },
        })
        .on('tr.item a', {
            element(el) {
                if (!state.current) return;
                const href = el.getAttribute('href') || '';
                const idMatch = href.match(/subject\/(\d+)/);
                if (idMatch) {
                    state.current.id = idMatch[1];
                    state.current.url = href;
                }
                const title = el.getAttribute('title');
                if (title && !state.current.title) {
                    state.current.title = title;
                }
            },
        })
        .on('tr.item img', {
            element(el) {
                if (!state.current) return;
                const src = el.getAttribute('src');
                if (src && !state.current.poster) {
                    state.current.poster = src;
                }
            },
        })
        .on('tr.item .rating_nums', {
            element() {
                ratingState.capture = true;
                ratingState.buffer = '';
            },
            text(text) {
                if (ratingState.capture) {
                    ratingState.buffer += text.text;
                }
            },
        })
        .transform(response);

    // 消费 transformed body 以触发流式解析
    await transformed.text();

    // 最后一个 item
    if (state.current && state.current.id) {
        if (ratingState.buffer.trim()) {
            state.current.rating = ratingState.buffer.trim();
        }
        items.push(finalizeChartItem(state.current));
    }

    return {
        code: 0,
        data: items,
        total: items.length,
        cached: false,
        updatedAt: new Date().toISOString(),
    };
}

function finalizeChartItem(item: Partial<ChartItem>): ChartItem {
    return {
        rank: item.rank || 0,
        title: item.title || '',
        subtitle: item.subtitle || '',
        id: item.id || '',
        url: item.url || '',
        poster: item.poster || '',
        rating: item.rating || '暂无评分',
        ratingCount: item.ratingCount || '',
        tmdbId: item.tmdbId || 0,
        traktId: item.traktId || 0,
        imdbId: item.imdbId || '',
        mediaType: 'movie',
    };
}

// ==================== 一周口碑榜 ====================

async function scrapeWeekly(): Promise<{ code: number; data: WeeklyItem[]; total: number; cached: boolean; updatedAt: string }> {
    const response = await fetch('https://movie.douban.com/chart', {
        headers: DOUBAN_HEADERS,
        signal: AbortSignal.timeout(SCRAPE_FETCH_TIMEOUT_MS),
    });

    const items: WeeklyItem[] = [];
    const state: { current: Partial<WeeklyItem> | null } = { current: null };
    const sectionState = { inWeekly: false };
    const titleState = { capture: false, buffer: '' };

    const transformed = new HTMLRewriter()
        .on('ul#listCont2', {
            element() { sectionState.inWeekly = true; },
        })
        .on('ul#listCont2 li', {
            element() {
                if (!sectionState.inWeekly) return;
                if (state.current && state.current.id) {
                    state.current.title = titleState.buffer.trim();
                    items.push(finalizeWeeklyItem(state.current));
                }
                state.current = { rank: 0, title: '', id: '', url: '', poster: '' };
                titleState.buffer = '';
                titleState.capture = false;
            },
        })
        .on('ul#listCont2 li .no', {
            text(text) {
                if (!state.current) return;
                const num = parseInt(text.text.trim(), 10);
                if (!isNaN(num)) state.current.rank = num;
            },
        })
        .on('ul#listCont2 li a', {
            element(el) {
                if (!state.current) return;
                const href = el.getAttribute('href') || '';
                const idMatch = href.match(/subject\/(\d+)/);
                if (idMatch && !state.current.id) {
                    state.current.id = idMatch[1];
                    state.current.url = href;
                    titleState.capture = true;
                }
            },
            text(text) {
                if (titleState.capture) {
                    titleState.buffer += text.text;
                }
            },
        })
        .on('ul#listCont2 li img', {
            element(el) {
                if (!state.current) return;
                const src = el.getAttribute('src');
                if (src && !state.current.poster) {
                    state.current.poster = src;
                }
            },
        })
        .transform(response);

    await transformed.text();

    if (state.current && state.current.id) {
        state.current.title = titleState.buffer.trim();
        items.push(finalizeWeeklyItem(state.current));
    }

    // 豆瓣口碑榜页面 HTML 本身不含海报图片(只有排名/标题/升降趋势)
    // 用豆瓣 suggest API 公开接口补全海报,每批 5 个并发避免限流
    await enrichWeeklyPosters(items);

    return {
        code: 0,
        data: items,
        total: items.length,
        cached: false,
        updatedAt: new Date().toISOString(),
    };
}

/**
 * 豆瓣 suggest API 补全口碑榜海报
 * 豆瓣口碑榜页面 HTML 不含 img 标签,需通过 suggest API 搜索条目标题获取海报
 * 优先匹配 type=movie 的条目,避免匹配到 celebrity 类型
 */
async function enrichWeeklyPosters(items: WeeklyItem[]): Promise<void> {
    for (let i = 0; i < items.length; i += 5) {
        const batch = items.slice(i, i + 5);
        const posters = await Promise.all(batch.map(async (item) => {
            if (item.poster) return item.poster;
            try {
                const resp = await fetch(
                    `https://movie.douban.com/j/subject_suggest?q=${encodeURIComponent(item.title)}`,
                    {
                        headers: DOUBAN_HEADERS,
                        signal: AbortSignal.timeout(SCRAPE_FETCH_TIMEOUT_MS),
                    },
                );
                if (!resp.ok) return '';
                const data = await resp.json() as Array<{ type?: string; img?: string }>;
                const movie = data.find(it => it.type === 'movie' && it.img);
                return movie?.img || data.find(it => it.img)?.img || '';
            } catch {
                return '';
            }
        }));
        batch.forEach((item, j) => { item.poster = posters[j]; });
    }
}

function finalizeWeeklyItem(item: Partial<WeeklyItem>): WeeklyItem {
    return {
        rank: item.rank || 0,
        title: item.title || '',
        id: item.id || '',
        url: item.url || '',
        poster: item.poster || '',
        tmdbId: item.tmdbId || 0,
        traktId: item.traktId || 0,
        imdbId: item.imdbId || '',
        mediaType: 'movie',
    };
}

// ==================== 正在热映 ====================

async function scrapeNowPlaying(): Promise<{ code: number; data: NowPlayingItem[]; total: number; cached: boolean; updatedAt: string }> {
    const response = await fetch('https://movie.douban.com/', {
        headers: DOUBAN_HEADERS,
        signal: AbortSignal.timeout(SCRAPE_FETCH_TIMEOUT_MS),
    });

    const items: NowPlayingItem[] = [];
    const state: { current: Partial<NowPlayingItem> | null } = { current: null };

    const transformed = new HTMLRewriter()
        .on('li.ui-slide-item', {
            element(el) {
                if (state.current && state.current.id) {
                    items.push(finalizeNowPlayingItem(state.current));
                }
                state.current = {
                    title: '',
                    id: '',
                    url: '',
                    poster: '',
                    rating: '暂无评分',
                    ratingCount: '',
                    release: '',
                    duration: '',
                    region: '',
                    director: '',
                    actors: '',
                };
                // 从 data-* 属性提取
                const title = el.getAttribute('data-title');
                if (title && state.current) state.current.title = title;
                const release = el.getAttribute('data-release');
                if (release && state.current) state.current.release = release;
                const rate = el.getAttribute('data-rate');
                if (rate && state.current) state.current.rating = rate;
                const rater = el.getAttribute('data-rater');
                if (rater && state.current) state.current.ratingCount = rater + '人评价';
                const duration = el.getAttribute('data-duration');
                if (duration && state.current) state.current.duration = duration;
                const region = el.getAttribute('data-region');
                if (region && state.current) state.current.region = region;
                const director = el.getAttribute('data-director');
                if (director && state.current) state.current.director = director;
                const actors = el.getAttribute('data-actors');
                if (actors && state.current) state.current.actors = actors;
            },
        })
        .on('li.ui-slide-item a', {
            element(el) {
                if (!state.current) return;
                const href = el.getAttribute('href') || '';
                const idMatch = href.match(/subject\/(\d+)/);
                if (idMatch && !state.current.id) {
                    state.current.id = idMatch[1];
                    state.current.url = href;
                }
            },
        })
        .on('li.ui-slide-item img', {
            element(el) {
                if (!state.current) return;
                const src = el.getAttribute('src');
                if (src && src.includes('doubanio.com') && !state.current.poster) {
                    state.current.poster = src;
                }
            },
        })
        .transform(response);

    await transformed.text();

    if (state.current && state.current.id) {
        items.push(finalizeNowPlayingItem(state.current));
    }

    return {
        code: 0,
        data: items,
        total: items.length,
        cached: false,
        updatedAt: new Date().toISOString(),
    };
}

function finalizeNowPlayingItem(item: Partial<NowPlayingItem>): NowPlayingItem {
    return {
        title: item.title || '',
        id: item.id || '',
        url: item.url || '',
        poster: item.poster || '',
        rating: item.rating || '暂无评分',
        ratingCount: item.ratingCount || '',
        release: item.release || '',
        duration: item.duration || '',
        region: item.region || '',
        director: item.director || '',
        actors: item.actors || '',
        tmdbId: item.tmdbId || 0,
        traktId: item.traktId || 0,
        imdbId: item.imdbId || '',
        mediaType: 'movie',
    };
}

// ==================== Top250 ====================

async function scrapeTop250(page: number): Promise<{ code: number; data: Top250Item[]; total: number; page: number; cached: boolean; updatedAt: string }> {
    const start = (page - 1) * 25;
    const url = `https://movie.douban.com/top250?start=${start}&filter=`;
    const response = await fetch(url, {
        headers: DOUBAN_HEADERS,
        signal: AbortSignal.timeout(SCRAPE_FETCH_TIMEOUT_MS),
    });

    const items: Top250Item[] = [];
    const state: { current: Partial<Top250Item> | null } = { current: null };
    const buf = {
        title: '', rating: '', count: '', director: '', quote: '', info: '',
    };
    const flags = {
        captureTitle: false, captureRating: false, captureCount: false,
        captureQuote: false, brCount: 0,
    };

    const transformed = new HTMLRewriter()
        .on('ol li .item', {
            element() {
                if (state.current && state.current.id) {
                    pushTop250Item(items, state.current, { ...buf });
                }
                state.current = {
                    rank: 0, title: '', otherTitle: '', id: '', url: '',
                    poster: '', rating: '', ratingCount: '', director: '',
                    year: '', region: '', quote: '',
                };
                buf.title = ''; buf.rating = ''; buf.count = '';
                buf.director = ''; buf.quote = ''; buf.info = '';
                flags.brCount = 0;
            },
        })
        .on('ol li .item em', {
            text(text) {
                if (!state.current) return;
                const num = parseInt(text.text.trim(), 10);
                if (!isNaN(num)) state.current.rank = num;
            },
        })
        .on('ol li .item a', {
            element(el) {
                if (!state.current) return;
                const href = el.getAttribute('href') || '';
                const idMatch = href.match(/subject\/(\d+)/);
                if (idMatch && !state.current.id) {
                    state.current.id = idMatch[1];
                    state.current.url = href;
                }
            },
        })
        .on('ol li .item img', {
            element(el) {
                if (!state.current) return;
                const src = el.getAttribute('src');
                if (src && !state.current.poster) {
                    state.current.poster = src;
                }
            },
        })
        .on('ol li .item span.title', {
            element() { flags.captureTitle = true; },
            text(text) {
                if (flags.captureTitle) {
                    buf.title += text.text;
                }
            },
        })
        .on('ol li .item span.rating_num', {
            element() { flags.captureRating = true; },
            text(text) {
                if (flags.captureRating) {
                    buf.rating += text.text;
                }
            },
        })
        .on('ol li .item span', {
            element(el) {
                // 评价人数 span 无 class，靠内容判断
                const cls = el.getAttribute('class') || '';
                if (!cls) {
                    flags.captureCount = true;
                }
            },
            text(text) {
                if (flags.captureCount && text.text.includes('人评价')) {
                    buf.count += text.text;
                }
            },
        })
        .on('ol li .item .bd p', {
            text(text) {
                if (!state.current) return;
                // 导演信息在第一个 p 标签
                if (text.text.includes('导演')) {
                    buf.director += text.text;
                }
                // 年份/地区信息也在 p 标签中，跟在 <br> 后
                if (flags.brCount > 0) {
                    buf.info += text.text;
                }
            },
        })
        .on('ol li .item .bd p br', {
            element() { flags.brCount++; },
        })
        .on('ol li .item span.inq', {
            element() { flags.captureQuote = true; },
            text(text) {
                if (flags.captureQuote) {
                    buf.quote += text.text;
                }
            },
        })
        .transform(response);

    await transformed.text();

    if (state.current && state.current.id) {
        pushTop250Item(items, state.current, { ...buf });
    }

    return {
        code: 0,
        data: items,
        total: 250,
        page,
        cached: false,
        updatedAt: new Date().toISOString(),
    };
}

export function normalizeTop250Title(rawTitle: string): string {
    return rawTitle
        .replace(/(?:&nbsp;|&#160;|&#xA0;|\u00a0)/gi, ' ')
        .replace(/\s+/g, ' ')
        .trim();
}

function pushTop250Item(
    items: Top250Item[],
    item: Partial<Top250Item>,
    buffers: { title: string; rating: string; count: string; director: string; quote: string; info: string },
): void {
    // 标题可能有多个（中文/英文），取第一个
    const titles = normalizeTop250Title(buffers.title)
        .split(/\s*\/\s*/)
        .map(s => s.trim())
        .filter(Boolean);
    const title = titles[0] || '';
    const otherTitle = titles.slice(1).join(' / ');

    // 从 count 提取数字
    const countMatch = buffers.count.match(/(\d+)人评价/);
    const ratingCount = countMatch ? countMatch[1] + '人评价' : '';

    // 从 director 提取
    const directorMatch = buffers.director.match(/导演:\s*([^\n<]+)/);
    const director = directorMatch ? directorMatch[1].replace(/\s+/g, ' ').trim() : '';

    // 从 info 提取年份和地区
    const infoMatch = buffers.info.match(/(\d{4})\s*\/\s*([^<]+)/);
    const year = infoMatch ? infoMatch[1] : '';
    const region = infoMatch ? infoMatch[2].trim() : '';

    items.push({
        rank: item.rank || 0,
        title,
        otherTitle,
        id: item.id || '',
        url: item.url || '',
        poster: item.poster || '',
        rating: buffers.rating.trim(),
        ratingCount,
        director,
        year,
        region,
        quote: buffers.quote.trim(),
        tmdbId: item.tmdbId || 0,
        traktId: item.traktId || 0,
        imdbId: item.imdbId || '',
        mediaType: 'movie',
    });
}
