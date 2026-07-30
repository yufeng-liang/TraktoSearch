// 豆瓣热榜直接抓取（HTMLRewriter 流式解析，绕过 douban-movie-api worker 的 CPU 限制）
//
// 背景：douban-movie-api worker 在免费版 10ms CPU 限制下用 regex 解析 50KB+ HTML 会超限（error 1042）。
// HTMLRewriter 是 Workers 运行时内置的 C++ 流式 HTML 解析器，CPU 开销远低于 JS regex，
// 可在 10ms 内完成解析。缓存命中时直接返回，不触发抓取。

import { AppError } from '../util/errors';

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

interface ChartItem {
    rank: number;
    title: string;
    subtitle: string;
    id: string;
    url: string;
    poster: string;
    rating: string;
    ratingCount: string;
}

interface WeeklyItem {
    rank: number;
    title: string;
    id: string;
    url: string;
    poster: string;
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
}

/** 根据豆瓣代理子路径直接抓取豆瓣网页并返回 JSON。 */
export async function scrapeDouban(
    doubanPath: string,
    searchParams: URLSearchParams,
): Promise<Response> {
    const cacheKey = buildCacheKey(doubanPath, searchParams);

    // 缓存命中时直接返回
    const cached = await caches.default.match(cacheKey);
    if (cached) {
        const data: Record<string, unknown> = await cached.json();
        data.cached = true;
        return json(data);
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

    return json(result);
}

function buildCacheKey(doubanPath: string, searchParams: URLSearchParams): string {
    const page = searchParams.get('page');
    const pageSuffix = page ? `?page=${page}` : '';
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
    const response = await fetch('https://movie.douban.com/chart', { headers: DOUBAN_HEADERS });

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
    };
}

// ==================== 一周口碑榜 ====================

async function scrapeWeekly(): Promise<{ code: number; data: WeeklyItem[]; total: number; cached: boolean; updatedAt: string }> {
    const response = await fetch('https://movie.douban.com/chart', { headers: DOUBAN_HEADERS });

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

    return {
        code: 0,
        data: items,
        total: items.length,
        cached: false,
        updatedAt: new Date().toISOString(),
    };
}

function finalizeWeeklyItem(item: Partial<WeeklyItem>): WeeklyItem {
    return {
        rank: item.rank || 0,
        title: item.title || '',
        id: item.id || '',
        url: item.url || '',
        poster: item.poster || '',
    };
}

// ==================== 正在热映 ====================

async function scrapeNowPlaying(): Promise<{ code: number; data: NowPlayingItem[]; total: number; cached: boolean; updatedAt: string }> {
    const response = await fetch('https://movie.douban.com/', { headers: DOUBAN_HEADERS });

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
    };
}

// ==================== Top250 ====================

async function scrapeTop250(page: number): Promise<{ code: number; data: Top250Item[]; total: number; page: number; cached: boolean; updatedAt: string }> {
    const start = (page - 1) * 25;
    const url = `https://movie.douban.com/top250?start=${start}&filter=`;
    const response = await fetch(url, { headers: DOUBAN_HEADERS });

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

function pushTop250Item(
    items: Top250Item[],
    item: Partial<Top250Item>,
    buffers: { title: string; rating: string; count: string; director: string; quote: string; info: string },
): void {
    // 标题可能有多个（中文/英文），取第一个
    const titles = buffers.title.split(/\/\s*/).map(s => s.trim()).filter(Boolean);
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
    });
}
