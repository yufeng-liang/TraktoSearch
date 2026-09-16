import assert from 'node:assert/strict';
import test from 'node:test';

import {
    handleMediaDetail,
    handleMediaSummaries,
} from '../src/proxy/media-cache.ts';

class MemoryD1 {
    constructor() {
        this.summary = new Map();
        this.manifest = new Map();
        this.leases = new Map();
        this.runCalls = [];
    }

    prepare(sql) {
        const normalized = sql.replace(/\s+/g, ' ').trim();
        return {
            bind: (...values) => ({
                first: async () => this.first(normalized, values),
                run: async () => this.run(normalized, values),
                all: async () => ({ results: [] }),
            }),
        };
    }

    async first(sql, values) {
        if (sql.startsWith('SELECT payload_json, title_refreshed_at, volatile_refreshed_at FROM media_summary')) {
            const row = this.summary.get(`${values[0]}:${values[1]}:${values[2]}:${values[3]}`);
            return row ? { ...row } : null;
        }
        if (sql.startsWith('SELECT object_key, refreshed_at FROM media_detail_manifest')) {
            const row = this.manifest.get(`${values[0]}:${values[1]}:${values[2]}:${values[3]}:${values[4]}`);
            return row ? { ...row } : null;
        }
        if (sql.startsWith('SELECT title_refreshed_at, volatile_refreshed_at FROM media_summary')) {
            const row = this.summary.get(`${values[0]}:${values[1]}:${values[2]}:${values[3]}`);
            return row
                ? {
                    title_refreshed_at: row.title_refreshed_at,
                    volatile_refreshed_at: row.volatile_refreshed_at,
                }
                : null;
        }
        return null;
    }

    async run(sql, values) {
        this.runCalls.push({ sql, values });
        if (sql.startsWith('INSERT INTO media_summary')) {
            const [mediaType, tmdbId, locale, schemaVersion, payload, titleAt, volatileAt, updatedAt] = values;
            this.summary.set(`${mediaType}:${tmdbId}:${locale}:${schemaVersion}`, {
                payload_json: payload,
                title_refreshed_at: titleAt,
                volatile_refreshed_at: volatileAt,
                updated_at: updatedAt,
            });
            return { meta: { changes: 1 } };
        }
        if (sql.startsWith('INSERT INTO media_detail_manifest')) {
            const [mediaType, tmdbId, locale, schemaVersion, section, objectKey, refreshedAt] = values;
            this.manifest.set(`${mediaType}:${tmdbId}:${locale}:${schemaVersion}:${section}`, {
                object_key: objectKey,
                refreshed_at: refreshedAt,
            });
            return { meta: { changes: 1 } };
        }
        if (sql.startsWith('INSERT INTO media_refresh_lease')) {
            const [key, expiresAt, owner, now] = values;
            const current = this.leases.get(key);
            if (current !== undefined && current.expiresAt > now) {
                return { meta: { changes: 0 } };
            }
            this.leases.set(key, { expiresAt, owner });
            return { meta: { changes: 1 } };
        }
        if (sql.startsWith('DELETE FROM media_refresh_lease')) {
            const current = this.leases.get(values[0]);
            const deleted = current !== undefined && current.owner === values[1];
            if (deleted) this.leases.delete(values[0]);
            return { meta: { changes: deleted ? 1 : 0 } };
        }
        throw new Error(`Unhandled SQL: ${sql}`);
    }
}

class MemoryR2 {
    constructor() {
        this.objects = new Map();
    }

    async put(key, value) {
        this.objects.set(key, String(value));
    }

    async get(key) {
        const value = this.objects.get(key);
        if (value === undefined) return null;
        return {
            text: async () => value,
        };
    }
}

function createContext() {
    const tasks = [];
    return {
        tasks,
        ctx: {
            waitUntil(promise) {
                tasks.push(promise);
            },
        },
    };
}

function createEnv(fetcher, overrides = {}) {
    return {
        DB: new MemoryD1(),
        MEDIA_DB: new MemoryD1(),
        MEDIA_CACHE: new MemoryR2(),
        TMDB_API_KEY: 'test-key',
        KV: {
            get: async () => null,
            put: async () => undefined,
        },
        fetch: fetcher,
        ...overrides,
    };
}

function jsonResponse(body, status = 200) {
    return new Response(JSON.stringify(body), {
        status,
        headers: { 'Content-Type': 'application/json' },
    });
}

function movieDetail(overrides = {}) {
    return {
        id: 550,
        title: '搏击俱乐部',
        original_title: 'Fight Club',
        overview: 'overview',
        poster_path: '/poster.jpg',
        release_date: '1999-10-15',
        genres: [{ id: 18, name: '剧情' }],
        vote_average: 8.4,
        runtime: 139,
        production_countries: [{ name: '美国' }],
        status: 'Released',
        imdb_id: 'tt0137523',
        external_ids: { imdb_id: 'tt0137523' },
        alternative_titles: {
            titles: [
                { title: '搏击俱乐部', iso_3166_1: 'CN', iso_639_1: 'zh' },
                { title: '斗阵俱乐部', iso_3166_1: 'TW', iso_639_1: 'zh' },
            ],
        },
        ...overrides,
    };
}

function tvDetail(overrides = {}) {
    return {
        id: 1396,
        name: '绝命毒师',
        original_name: 'Breaking Bad',
        poster_path: '/tv.jpg',
        first_air_date: '2008-01-20',
        genres: [{ id: 18, name: '剧情' }],
        vote_average: 8.9,
        episode_run_time: [47],
        origin_country: ['US'],
        status: 'Ended',
        alternative_titles: {
            results: [
                { title: '绝命毒师', iso_3166_1: 'CN', iso_639_1: 'zh' },
            ],
        },
        external_ids: { imdb_id: 'tt0903747' },
        ...overrides,
    };
}

function installFetch(handler) {
    globalThis.fetch = handler;
}

test('电影标题链优先详情标题，并保留稳定摘要字段', async () => {
    installFetch(async () => jsonResponse(movieDetail()));
    const env = createEnv(fetch);
    const response = await handleMediaSummaries(
        new Request('https://worker.test/api/media/summaries?locale=zh-CN&ids=movie:550'),
        env,
    );
    const body = await response.json();

    assert.equal(response.status, 200);
    assert.equal(response.headers.get('X-Media-Cache'), 'MISS');
    assert.deepEqual(body.data.missing, []);
    assert.equal(body.data.items[0].title, '搏击俱乐部');
    assert.equal(body.data.items[0].titleSource, 'DETAIL');
    assert.equal(body.data.items[0].runtime, 139);
    assert.equal(body.data.items[0].imdbId, 'tt0137523');
});

test('剧集 alternative_titles.results 映射为 ALTERNATIVE，且详情标题同原名时走别名', async () => {
    installFetch(async () => jsonResponse(tvDetail({
        name: 'Breaking Bad',
        alternative_titles: {
            results: [{ title: '绝命毒师', iso_3166_1: 'CN', iso_639_1: 'zh' }],
        },
    })));
    const env = createEnv(fetch);
    const response = await handleMediaSummaries(
        new Request('https://worker.test/api/media/summaries?locale=zh-CN&ids=tv:1396'),
        env,
    );
    const body = await response.json();

    assert.equal(body.data.items[0].title, '绝命毒师');
    assert.equal(body.data.items[0].titleSource, 'ALTERNATIVE');
});

test('批量摘要去重、非法格式、超过 20 条与部分失败都不返回整批 500', async () => {
    installFetch(async (url) => {
        const parsed = new URL(url);
        const id = Number(parsed.pathname.split('/').pop());
        if (id === 999) return jsonResponse({}, 500);
        return jsonResponse(movieDetail({ id }));
    });
    const env = createEnv(fetch);
    const ids = [
        'movie:1',
        'movie:1',
        'bad',
        ...Array.from({ length: 21 }, (_, index) => `movie:${index + 10}`),
        'movie:999',
    ];
    const response = await handleMediaSummaries(
        new Request(`https://worker.test/api/media/summaries?locale=zh-CN&ids=${ids.join(',')}`),
        env,
    );
    const body = await response.json();

    assert.equal(response.status, 200);
    assert.equal(body.data.items.length, 20);
    assert.equal(body.data.partial, true);
    assert.ok(body.data.missing.includes('bad'));
    assert.ok(body.data.missing.includes('movie:30'));
    assert.ok(body.data.missing.includes('movie:999'));
    assert.equal(body.data.items.filter((item) => item.tmdbId === 1).length, 1);
});

test('第二个请求命中 D1 后不再访问 TMDB', async () => {
    let fetchCount = 0;
    installFetch(async () => {
        fetchCount += 1;
        return jsonResponse(movieDetail());
    });
    const env = createEnv(fetch);
    const request = new Request('https://worker.test/api/media/summaries?locale=zh-CN&ids=movie:550');

    const first = await handleMediaSummaries(request, env);
    const second = await handleMediaSummaries(request, env);
    const firstBody = await first.json();
    const secondBody = await second.json();

    assert.equal(fetchCount, 1);
    assert.equal(first.headers.get('X-Media-Cache'), 'MISS');
    assert.equal(second.headers.get('X-Media-Cache'), 'D1');
    assert.deepEqual(secondBody.data.items, firstBody.data.items);
});

test('混合缓存与回源不是 partial，真实失败才标记 partial', async () => {
    let fetchCount = 0;
    installFetch(async (url) => {
        fetchCount += 1;
        const id = Number(new URL(url).pathname.split('/').pop());
        if (id === 999) return jsonResponse({}, 500);
        return jsonResponse(movieDetail({ id }));
    });
    const env = createEnv(fetch);

    const first = await handleMediaSummaries(
        new Request('https://worker.test/api/media/summaries?locale=zh-CN&ids=movie:550'),
        env,
    );
    assert.equal(first.headers.get('X-Media-Partial'), 'false');

    const mixed = await handleMediaSummaries(
        new Request('https://worker.test/api/media/summaries?locale=zh-CN&ids=movie:550,movie:551'),
        env,
    );
    assert.equal(mixed.headers.get('X-Media-Cache'), 'PARTIAL');
    assert.equal(mixed.headers.get('X-Media-Partial'), 'false');

    const failed = await handleMediaSummaries(
        new Request('https://worker.test/api/media/summaries?locale=zh-CN&ids=movie:999'),
        env,
    );
    assert.equal(failed.headers.get('X-Media-Partial'), 'true');
    assert.equal(fetchCount, 3);
});

test('过期摘要先返回旧值，后台刷新只允许一个租约回源', async () => {
    let fetchCount = 0;
    installFetch(async () => {
        fetchCount += 1;
        return jsonResponse(movieDetail({ title: '搏击俱乐部（新）' }));
    });
    const env = createEnv(fetch);
    const now = Date.now();
    env.MEDIA_DB.summary.set('movie:550:zh-CN:3', {
        payload_json: JSON.stringify({
            mediaType: 'movie',
            tmdbId: 550,
            locale: 'zh-CN',
            title: '搏击俱乐部（旧）',
            originalTitle: 'Fight Club',
            posterPath: '/poster.jpg',
            year: 1999,
            genres: ['剧情'],
            voteAverage: 8.4,
            runtime: 139,
            countries: ['美国'],
            status: 'Released',
            imdbId: 'tt0137523',
            collectionId: null,
            titleSource: 'DETAIL',
        }),
        title_refreshed_at: now - 2 * 24 * 60 * 60 * 1000,
        volatile_refreshed_at: now - 2 * 24 * 60 * 60 * 1000,
    });
    const { ctx, tasks } = createContext();
    const request = new Request('https://worker.test/api/media/summaries?locale=zh-CN&ids=movie:550');

    const first = await handleMediaSummaries(request, env, ctx);
    const second = await handleMediaSummaries(request, env, ctx);
    const firstBody = await first.json();
    const secondBody = await second.json();

    assert.equal(firstBody.data.items[0].title, '搏击俱乐部（旧）');
    assert.equal(secondBody.data.items[0].title, '搏击俱乐部（旧）');
    await Promise.all(tasks);
    assert.equal(fetchCount, 1);
});

test('详情接口从 R2 manifest 读取已有 section，不重复访问 TMDB', async () => {
    let fetchCount = 0;
    installFetch(async () => {
        fetchCount += 1;
        return jsonResponse(movieDetail());
    });
    const env = createEnv(fetch);
    const now = Date.now();
    env.MEDIA_DB.summary.set('movie:550:zh-CN:3', {
        payload_json: JSON.stringify({
            mediaType: 'movie',
            tmdbId: 550,
            locale: 'zh-CN',
            title: '搏击俱乐部',
            originalTitle: 'Fight Club',
            posterPath: '/poster.jpg',
            year: 1999,
            genres: ['剧情'],
            voteAverage: 8.4,
            runtime: 139,
            countries: ['美国'],
            status: 'Released',
            imdbId: 'tt0137523',
            collectionId: null,
            titleSource: 'DETAIL',
        }),
        title_refreshed_at: now,
        volatile_refreshed_at: now,
    });
    const objectKey = 'v1/movie/550/zh-CN/credits.json';
    env.MEDIA_DB.manifest.set('movie:550:zh-CN:3:credits', {
        object_key: objectKey,
        refreshed_at: now,
    });
    await env.MEDIA_CACHE.put(objectKey, JSON.stringify({ cast: [], crew: [] }));

    const response = await handleMediaDetail(
        new Request('https://worker.test/api/media/detail?type=movie&id=550&locale=zh-CN&sections=credits'),
        env,
    );
    const body = await response.json();

    assert.equal(fetchCount, 0);
    assert.equal(response.headers.get('X-Media-Cache'), 'D1');
    assert.deepEqual(body.data.credits, { cast: [], crew: [] });
});

test('详情 section 缺失时回源一次并写入 R2，section 失败则返回 null', async () => {
    installFetch(async (url) => {
        const parsed = new URL(url);
        if (parsed.pathname.endsWith('/credits')) {
            return jsonResponse({ cast: [], crew: [] }, 500);
        }
        return jsonResponse(movieDetail({
            credits: { cast: [{ id: 1, name: '演员', character: '角色', profile_path: null, order: 0 }], crew: [] },
            videos: {
                results: [{ id: 'v1', key: 'abc', name: '预告', site: 'YouTube', type: 'Trailer', official: true, size: 1080 }],
            },
            images: {
                backdrops: [{ file_path: '/still.jpg', width: 1920, height: 1080, iso_639_1: null }],
            },
            similar: {
                results: [{ id: 2, title: '相似电影', original_title: 'Similar', poster_path: '/s.jpg', release_date: '2000-01-01', vote_average: 7.1 }],
            },
        }));
    });
    const env = createEnv(fetch);
    const response = await handleMediaDetail(
        new Request('https://worker.test/api/media/detail?type=movie&id=550&locale=zh-CN&sections=credits,videos,images,similar'),
        env,
    );
    const body = await response.json();

    assert.equal(response.status, 200);
    assert.equal(body.data.summary.title, '搏击俱乐部');
    assert.equal(body.data.credits.cast[0].name, '演员');
    assert.equal(body.data.videos[0].key, 'abc');
    assert.equal(body.data.images[0].filePath, '/still.jpg');
    assert.equal(body.data.similar[0].tmdbId, 2);
    assert.ok(env.MEDIA_CACHE.objects.has('v1/movie/550/zh-CN/credits.json'));
});

test('已有摘要和部分 section 时，缺失 section 回源失败仍返回旧缓存并标记 partial', async () => {
    installFetch(async () => jsonResponse({}, 500));
    const env = createEnv(fetch);
    const now = Date.now();
    env.MEDIA_DB.summary.set('movie:550:zh-CN:3', {
        payload_json: JSON.stringify({
            mediaType: 'movie',
            tmdbId: 550,
            locale: 'zh-CN',
            title: '搏击俱乐部',
            originalTitle: 'Fight Club',
            posterPath: '/poster.jpg',
            year: 1999,
            genres: ['剧情'],
            voteAverage: 8.4,
            runtime: 139,
            countries: ['美国'],
            status: 'Released',
            imdbId: 'tt0137523',
            collectionId: null,
            titleSource: 'DETAIL',
        }),
        title_refreshed_at: now,
        volatile_refreshed_at: now,
    });
    const objectKey = 'v1/movie/550/zh-CN/images.json';
    env.MEDIA_DB.manifest.set('movie:550:zh-CN:3:images', {
        object_key: objectKey,
        refreshed_at: now,
    });
    await env.MEDIA_CACHE.put(objectKey, JSON.stringify([
        { filePath: '/still.jpg', width: 1920, height: 1080, iso6391: null, source: 'tmdb' },
    ]));

    const response = await handleMediaDetail(
        new Request('https://worker.test/api/media/detail?type=movie&id=550&locale=zh-CN&sections=videos,images'),
        env,
    );
    const body = await response.json();

    assert.equal(response.status, 200);
    assert.equal(response.headers.get('X-Media-Partial'), 'true');
    assert.equal(body.data.summary.title, '搏击俱乐部');
    assert.equal(body.data.images[0].filePath, '/still.jpg');
    assert.equal(body.data.videos, null);
    // 失败不能把空数组写成长期缓存；否则 30 天内都会稳定显示“无预告片”。
    assert.equal(env.MEDIA_CACHE.objects.has('v1/movie/550/zh-CN/videos.json'), false);
    assert.equal(
        env.MEDIA_DB.manifest.has('movie:550:zh-CN:3:videos'),
        false,
    );
});

test('摘要缺失但 R2 已有 section 时返回部分 bundle，不把可用缓存变成 502', async () => {
    installFetch(async () => jsonResponse({}, 500));
    const env = createEnv(fetch);
    const now = Date.now();
    const objectKey = 'v1/movie/550/zh-CN/images.json';
    env.MEDIA_DB.manifest.set('movie:550:zh-CN:3:images', {
        object_key: objectKey,
        refreshed_at: now,
    });
    await env.MEDIA_CACHE.put(objectKey, JSON.stringify([
        { filePath: '/still.jpg', width: 1920, height: 1080, iso6391: null, source: 'tmdb' },
    ]));

    const response = await handleMediaDetail(
        new Request('https://worker.test/api/media/detail?type=movie&id=550&locale=zh-CN&sections=images'),
        env,
    );
    const body = await response.json();

    assert.equal(response.status, 200);
    assert.equal(response.headers.get('X-Media-Cache'), 'R2');
    assert.equal(response.headers.get('X-Media-Partial'), 'true');
    assert.equal(body.data.summary, null);
    assert.equal(body.data.images[0].filePath, '/still.jpg');
});

test('无系列电影的空 collection 会写入缓存，后续请求不再访问 TMDB', async () => {
    let fetchCount = 0;
    installFetch(async () => {
        fetchCount += 1;
        return jsonResponse(movieDetail({ belongs_to_collection: null }));
    });
    const env = createEnv(fetch);
    const request = new Request(
        'https://worker.test/api/media/detail?type=movie&id=550&locale=zh-CN&sections=collection',
    );

    const first = await handleMediaDetail(request, env);
    const firstBody = await first.json();
    const second = await handleMediaDetail(request, env);
    const secondBody = await second.json();

    assert.equal(first.status, 200);
    assert.equal(firstBody.data.collection, null);
    assert.equal(fetchCount, 1);
    assert.ok(env.MEDIA_CACHE.objects.has('v1/movie/550/zh-CN/collection.json'));
    assert.equal(second.status, 200);
    assert.equal(secondBody.data.collection, null);
    assert.equal(fetchCount, 1);
});

test('详情已有摘要时回源不替换稳定标题字段', async () => {
    let fetchCount = 0;
    installFetch(async () => {
        fetchCount += 1;
        return jsonResponse(movieDetail({
            title: '搏击俱乐部（新）',
            poster_path: '/new-poster.jpg',
        }));
    });
    const env = createEnv(fetch);
    const now = Date.now();
    env.MEDIA_DB.summary.set('movie:550:zh-CN:3', {
        payload_json: JSON.stringify({
            mediaType: 'movie',
            tmdbId: 550,
            locale: 'zh-CN',
            title: '搏击俱乐部',
            originalTitle: 'Fight Club',
            overview: '旧简介',
            posterPath: '/poster.jpg',
            year: 1999,
            genres: ['剧情'],
            voteAverage: 8.4,
            runtime: 139,
            countries: ['美国'],
            status: 'Released',
            imdbId: 'tt0137523',
            collectionId: null,
            titleSource: 'DETAIL',
        }),
        title_refreshed_at: now,
        volatile_refreshed_at: now,
    });

    const response = await handleMediaDetail(
        new Request('https://worker.test/api/media/detail?type=movie&id=550&locale=zh-CN&sections=credits'),
        env,
    );
    const body = await response.json();

    assert.equal(fetchCount, 1);
    assert.equal(body.data.summary.title, '搏击俱乐部');
    assert.equal(body.data.summary.posterPath, '/poster.jpg');
});

test('详情 section 刷新保留原摘要时间戳，不提前续期 24 小时 TTL', async () => {
    const oldTitleRefreshedAt = Date.now() - 23 * 60 * 60 * 1000;
    const oldVolatileRefreshedAt = Date.now() - 23 * 60 * 60 * 1000;
    installFetch(async () => jsonResponse(movieDetail({
        credits: { cast: [], crew: [] },
    })));
    const env = createEnv(fetch);
    env.MEDIA_DB.summary.set('movie:550:zh-CN:3', {
        payload_json: JSON.stringify({
            mediaType: 'movie',
            tmdbId: 550,
            locale: 'zh-CN',
            title: '搏击俱乐部',
            originalTitle: 'Fight Club',
            overview: '旧简介',
            posterPath: '/poster.jpg',
            year: 1999,
            genres: ['剧情'],
            voteAverage: 8.4,
            runtime: 139,
            countries: ['美国'],
            status: 'Released',
            imdbId: 'tt0137523',
            collectionId: null,
            titleSource: 'DETAIL',
        }),
        title_refreshed_at: oldTitleRefreshedAt,
        volatile_refreshed_at: oldVolatileRefreshedAt,
    });

    await handleMediaDetail(
        new Request('https://worker.test/api/media/detail?type=movie&id=550&locale=zh-CN&sections=credits'),
        env,
    );

    const saved = env.MEDIA_DB.summary.get('movie:550:zh-CN:3');
    assert.equal(saved.title_refreshed_at, oldTitleRefreshedAt);
    assert.equal(saved.volatile_refreshed_at, oldVolatileRefreshedAt);
});

test('详情 section 后台刷新不重写摘要稳定字段', async () => {
    const env = createEnv(fetch);
    const oldPayload = JSON.stringify({
        mediaType: 'movie',
        tmdbId: 550,
        locale: 'zh-CN',
        title: '搏击俱乐部',
        originalTitle: 'Fight Club',
        overview: '旧简介',
        posterPath: '/poster.jpg',
        year: 1999,
        genres: ['剧情'],
        voteAverage: 8.4,
        runtime: 139,
        countries: ['美国'],
        status: 'Released',
        imdbId: 'tt0137523',
        collectionId: null,
        titleSource: 'DETAIL',
    });
    env.MEDIA_DB.summary.set('movie:550:zh-CN:3', {
        payload_json: oldPayload,
        title_refreshed_at: Date.now() - 2 * 24 * 60 * 60 * 1000,
        volatile_refreshed_at: Date.now() - 2 * 24 * 60 * 60 * 1000,
    });
    const objectKey = 'v1/movie/550/zh-CN/credits.json';
    env.MEDIA_DB.manifest.set('movie:550:zh-CN:3:credits', {
        object_key: objectKey,
        refreshed_at: Date.now() - 400 * 24 * 60 * 60 * 1000,
    });
    await env.MEDIA_CACHE.put(objectKey, JSON.stringify({ cast: [], crew: [] }));
    installFetch(async () => jsonResponse(movieDetail({
        title: '搏击俱乐部（新）',
        poster_path: '/new-poster.jpg',
        credits: { cast: [], crew: [] },
    })));
    const { ctx, tasks } = createContext();

    await handleMediaDetail(
        new Request('https://worker.test/api/media/detail?type=movie&id=550&locale=zh-CN&sections=credits'),
        env,
        ctx,
    );
    await Promise.all(tasks);

    assert.equal(env.MEDIA_DB.summary.get('movie:550:zh-CN:3').payload_json, oldPayload);
});

test('collection 请求失败不写负缓存，后续请求仍会重试', async () => {
    let fetchCount = 0;
    installFetch(async (url) => {
        const path = new URL(url).pathname;
        if (path.includes('/collection/')) return jsonResponse({}, 500);
        fetchCount += 1;
        return jsonResponse(movieDetail({
            belongs_to_collection: { id: 120, name: '系列' },
        }));
    });
    const env = createEnv(fetch);
    const request = new Request(
        'https://worker.test/api/media/detail?type=movie&id=550&locale=zh-CN&sections=collection',
    );

    const first = await handleMediaDetail(request, env);
    const firstBody = await first.json();
    assert.equal(first.status, 200);
    assert.equal(firstBody.data.collection, null);
    assert.equal(first.headers.get('X-Media-Partial'), 'true');
    assert.equal(env.MEDIA_DB.manifest.has('movie:550:zh-CN:3:collection'), false);

    const second = await handleMediaDetail(request, env);
    assert.equal(second.headers.get('X-Media-Partial'), 'true');
    assert.equal(fetchCount, 2);
});

test('过期空 collection 刷新会写回负缓存并推进 TTL', async () => {
    let fetchCount = 0;
    installFetch(async () => {
        fetchCount += 1;
        return jsonResponse(movieDetail({ belongs_to_collection: null }));
    });
    const env = createEnv(fetch);
    const now = Date.now();
    env.MEDIA_DB.summary.set('movie:550:zh-CN:3', {
        payload_json: JSON.stringify({
            mediaType: 'movie',
            tmdbId: 550,
            locale: 'zh-CN',
            title: '搏击俱乐部',
            originalTitle: 'Fight Club',
            posterPath: '/poster.jpg',
            year: 1999,
            genres: ['剧情'],
            voteAverage: 8.4,
            runtime: 139,
            countries: ['美国'],
            status: 'Released',
            imdbId: 'tt0137523',
            collectionId: null,
            titleSource: 'DETAIL',
        }),
        title_refreshed_at: now,
        volatile_refreshed_at: now,
    });
    const objectKey = 'v1/movie/550/zh-CN/collection.json';
    env.MEDIA_DB.manifest.set('movie:550:zh-CN:3:collection', {
        object_key: objectKey,
        refreshed_at: Date.now() - 400 * 24 * 60 * 60 * 1000,
    });
    await env.MEDIA_CACHE.put(objectKey, JSON.stringify(null));
    const { ctx, tasks } = createContext();

    await handleMediaDetail(
        new Request('https://worker.test/api/media/detail?type=movie&id=550&locale=zh-CN&sections=collection'),
        env,
        ctx,
    );
    await Promise.all(tasks);

    const manifest = env.MEDIA_DB.manifest.get('movie:550:zh-CN:3:collection');
    assert.equal(fetchCount, 1);
    assert.ok(manifest.refreshed_at > now - 1000);
});

test('摘要后台刷新推进 volatile TTL，不因旧时间戳反复回源', async () => {
    const oldAt = Date.now() - 2 * 24 * 60 * 60 * 1000;
    let fetchCount = 0;
    installFetch(async () => {
        fetchCount += 1;
        return jsonResponse(movieDetail({ vote_average: 9.1 }));
    });
    const env = createEnv(fetch);
    env.MEDIA_DB.summary.set('movie:550:zh-CN:3', {
        payload_json: JSON.stringify({
            mediaType: 'movie',
            tmdbId: 550,
            locale: 'zh-CN',
            title: '搏击俱乐部',
            originalTitle: 'Fight Club',
            posterPath: '/poster.jpg',
            year: 1999,
            genres: ['剧情'],
            voteAverage: 8.4,
            runtime: 139,
            countries: ['美国'],
            status: 'Released',
            imdbId: 'tt0137523',
            collectionId: null,
            titleSource: 'DETAIL',
        }),
        title_refreshed_at: oldAt,
        volatile_refreshed_at: oldAt,
    });
    const { ctx, tasks } = createContext();
    const request = new Request('https://worker.test/api/media/summaries?locale=zh-CN&ids=movie:550');

    await handleMediaSummaries(request, env, ctx);
    await Promise.all(tasks);
    const saved = env.MEDIA_DB.summary.get('movie:550:zh-CN:3');
    assert.equal(saved.title_refreshed_at, oldAt);
    assert.ok(saved.volatile_refreshed_at > oldAt);

    await handleMediaSummaries(request, env, ctx);
    await Promise.all(tasks);
    assert.equal(fetchCount, 1);
});

test('同一影片不同 section 的过期刷新互不吞掉', async () => {
    const oldAt = Date.now() - 400 * 24 * 60 * 60 * 1000;
    let detailFetch = 0;
    installFetch(async (url) => {
        detailFetch += 1;
        return jsonResponse(movieDetail({
            credits: { cast: [], crew: [] },
            videos: {
                results: [{ id: 'v1', key: 'abc', name: 'Trailer', site: 'YouTube', type: 'Trailer', official: true, size: 1080 }],
            },
        }));
    });
    const env = createEnv(fetch);
    const now = Date.now();
    env.MEDIA_DB.summary.set('movie:550:zh-CN:3', {
        payload_json: JSON.stringify({
            mediaType: 'movie',
            tmdbId: 550,
            locale: 'zh-CN',
            title: '搏击俱乐部',
            originalTitle: 'Fight Club',
            posterPath: '/poster.jpg',
            year: 1999,
            genres: ['剧情'],
            voteAverage: 8.4,
            runtime: 139,
            countries: ['美国'],
            status: 'Released',
            imdbId: 'tt0137523',
            collectionId: null,
            titleSource: 'DETAIL',
        }),
        title_refreshed_at: now,
        volatile_refreshed_at: now,
    });
    for (const section of ['credits', 'videos']) {
        const objectKey = `v1/movie/550/zh-CN/${section}.json`;
        env.MEDIA_DB.manifest.set(`movie:550:zh-CN:3:${section}`, {
            object_key: objectKey,
            refreshed_at: oldAt,
        });
        await env.MEDIA_CACHE.put(
            objectKey,
            JSON.stringify(section === 'credits' ? { cast: [], crew: [] } : []),
        );
    }
    const { ctx, tasks } = createContext();

    await handleMediaDetail(
        new Request('https://worker.test/api/media/detail?type=movie&id=550&locale=zh-CN&sections=credits,videos'),
        env,
        ctx,
    );
    await Promise.all(tasks);

    assert.equal(detailFetch, 2);
});

test('videos 回源携带 include_video_language，避免中文详情漏掉英文预告片', async () => {
    const calls = [];
    installFetch(async (url) => {
        const parsed = new URL(url);
        calls.push(parsed);
        return jsonResponse(movieDetail({
            videos: {
                results: [{ id: 'v1', key: 'abc', name: 'Trailer', site: 'YouTube', type: 'Trailer', official: true, size: 1080 }],
            },
        }));
    });
    const env = createEnv(fetch);
    const response = await handleMediaDetail(
        new Request('https://worker.test/api/media/detail?type=movie&id=550&locale=zh-CN&sections=videos'),
        env,
    );
    const body = await response.json();

    assert.equal(response.status, 200);
    assert.equal(body.data.videos[0].key, 'abc');
    assert.equal(calls.length, 1);
    assert.equal(calls[0].searchParams.get('language'), 'zh-CN');
    assert.equal(calls[0].searchParams.get('include_video_language'), 'zh,en,null');
});

test('非法详情参数返回 400，非法批量项只进入 missing', async () => {
    installFetch(async () => jsonResponse(movieDetail()));
    const env = createEnv(fetch);
    const detail = await handleMediaDetail(
        new Request('https://worker.test/api/media/detail?type=person&id=550'),
        env,
    );
    assert.equal(detail.status, 400);

    const summaries = await handleMediaSummaries(
        new Request('https://worker.test/api/media/summaries?locale=zh-CN&ids=person:1, tv:-2, movie:550'),
        env,
    );
    const body = await summaries.json();
    assert.deepEqual(body.data.missing, ['person:1', 'tv:-2']);
    assert.equal(body.data.items.length, 1);
});
