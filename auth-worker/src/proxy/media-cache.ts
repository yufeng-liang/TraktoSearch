// 公开影视元数据聚合与持久化缓存。
//
// 该模块只处理公开 TMDB 数据：摘要存 D1，较大的 section 存 R2，
// 详情 manifest 存 D1。客户端批量读取摘要，详情按 section 扩展，
// 从而避免列表逐条请求 TMDB，并让后续用户复用同一份服务端缓存。

import { KeyPool, fetchWithKeyRotation } from '../util/key-pool.ts';
import { buildTmdbAuth } from './tmdb-token.ts';

const TMDB_BASE_URL = 'https://api.tmdb.org/3';
const TMDB_USER_AGENT = 'TrackToSearch/3.0';
// v4: countries 由 TMDB 英文名改为按 locale 本地化译名，隔离 v3 中写死的英文国名。
// v3: videos 请求补 include_video_language，彻底隔离 v2 中可能已写入的空 videos 记录。
const SCHEMA_VERSION = 4;
const MAX_SUMMARY_IDS = 20;
const SUMMARY_CONCURRENCY = 4;
const VOLATILE_SUMMARY_TTL_MS = 24 * 60 * 60 * 1000;
const CREDITS_TTL_MS = 365 * 24 * 60 * 60 * 1000;
const VIDEOS_TTL_MS = 30 * 24 * 60 * 60 * 1000;
const IMAGES_TTL_MS = 30 * 24 * 60 * 60 * 1000;
const SIMILAR_TTL_MS = 7 * 24 * 60 * 60 * 1000;
const COLLECTION_TTL_MS = 30 * 24 * 60 * 60 * 1000;
const REFRESH_LEASE_MS = 60 * 1000;
// TMDB 详情带 append_to_response 较重，给足超时余量，避免上游无响应拖死整个请求。
const TMDB_FETCH_TIMEOUT_MS = 20 * 1000;

export type MediaType = 'movie' | 'tv';
export type MediaSection = 'credits' | 'videos' | 'images' | 'similar' | 'collection';
export type TitleSource = 'DETAIL' | 'ALTERNATIVE' | 'NONE';
export type MediaCacheState = 'EDGE' | 'D1' | 'R2' | 'MISS';

export interface MediaSummary {
    mediaType: MediaType;
    tmdbId: number;
    locale: string;
    title: string;
    originalTitle: string;
    overview: string;
    posterPath: string | null;
    year: number | null;
    genres: string[];
    voteAverage: number | null;
    runtime: number | null;
    countries: string[];
    status: string;
    imdbId: string | null;
    collectionId: number | null;
    titleSource: TitleSource;
}

export interface MediaDetailBundle {
    summary: MediaSummary | null;
    credits: MediaCredits | null;
    videos: MediaVideo[] | null;
    images: MediaImage[] | null;
    similar: MediaSimilar[] | null;
    collection: MediaCollection | null;
}

export interface MediaCredits {
    cast: Array<{
        id: number;
        name: string;
        character: string;
        profilePath: string | null;
        order: number;
    }>;
    crew: Array<{
        id: number;
        name: string;
        job: string;
        department: string;
        profilePath: string | null;
    }>;
}

export interface MediaVideo {
    id: string;
    key: string;
    name: string;
    site: string;
    type: string;
    official: boolean;
    size: number | null;
}

export interface MediaImage {
    filePath: string;
    width: number | null;
    height: number | null;
    iso6391: string | null;
    source: 'tmdb';
}

export interface MediaSimilar {
    tmdbId: number;
    title: string;
    originalTitle: string;
    posterPath: string | null;
    releaseDate: string | null;
    voteAverage: number | null;
}

export interface MediaCollection {
    id: number;
    name: string;
    overview: string;
    posterPath: string | null;
    backdropPath: string | null;
    parts: MediaSimilar[];
}

export interface MediaCacheEnv {
    DB: D1Database;
    MEDIA_DB: D1Database;
    MEDIA_CACHE: R2Bucket;
    TMDB_API_KEY: string;
    KV: KVNamespace;
}

export type MediaBackgroundContext = Pick<ExecutionContext, 'waitUntil'> | undefined;

interface SummaryRow {
    payload_json: string;
    title_refreshed_at: number;
    volatile_refreshed_at: number;
}

interface ManifestRow {
    object_key: string;
    refreshed_at: number;
}

interface MediaId {
    mediaType: MediaType;
    tmdbId: number;
    key: string;
}

interface ParsedSummaryRequest {
    locale: string;
    ids: MediaId[];
    missing: string[];
}

interface ParsedDetailRequest {
    mediaType: MediaType;
    tmdbId: number;
    locale: string;
    sections: MediaSection[];
}

interface RawMediaDetail {
    [key: string]: unknown;
}

interface FetchedMediaBase {
    raw: RawMediaDetail;
    summary: MediaSummary;
}

interface CachedSection {
    value: unknown;
    refreshedAt: number;
    objectKey: string;
}

/**
 * 批量摘要接口。
 *
 * 冷缓存时仍会逐条回源，因为 TMDB 没有批量详情接口；但客户端只发一个请求，
 * 首次回源结果会写入共享 D1/R2，后续用户可直接命中。
 */
export async function handleMediaSummaries(
    request: Request,
    env: MediaCacheEnv,
    ctx?: MediaBackgroundContext,
): Promise<Response> {
    const requestId = createRequestId();
    const parsed = parseSummaryRequest(request);
    const staleIds: MediaId[] = [];
    const results = await mapLimit(parsed.ids, SUMMARY_CONCURRENCY, async (id) => {
        try {
            const resolved = await resolveSummary(env, id, parsed.locale, staleIds);
            return { id, ...resolved };
        } catch {
            return { id, error: true as const };
        }
    });
    if (staleIds.length > 0) {
        const refreshTask = mapLimit(staleIds, SUMMARY_CONCURRENCY, async (id) => {
            await refreshSummary(env, id, parsed.locale);
        }).then(() => undefined);
        runInBackground(ctx, refreshTask);
    }

    const items: MediaSummary[] = [];
    const missing = [...parsed.missing];
    const sources: Array<'cache' | 'upstream'> = [];
    for (const result of results) {
        if ('error' in result) {
            missing.push(result.id.key);
            continue;
        }
        items.push(result.summary);
        sources.push(result.source);
    }

    return jsonResponse({
        code: 'SUCCESS',
        message: 'OK',
        requestId,
        data: {
            items,
            missing,
            partial: missing.length > 0 || items.length !== parsed.ids.length,
        },
    }, 200, {
        'X-Media-Cache': summaryCacheHeader(sources, parsed.ids.length),
        'X-Media-Partial': String(missing.length > 0),
    });
}

/**
 * 详情接口。
 *
 * summary 先读 D1；section 先读 manifest + R2。缺失 section 才回源，
 * 过期 section 先返回旧值，再用租约在 waitUntil 中单飞刷新。
 */
export async function handleMediaDetail(
    request: Request,
    env: MediaCacheEnv,
    ctx?: MediaBackgroundContext,
): Promise<Response> {
    const requestId = createRequestId();
    const parsed = parseDetailRequest(request);
    if (!parsed) {
        return jsonResponse({
            code: 'INVALID_REQUEST',
            message: 'Invalid type or id',
            requestId,
            data: null,
        }, 400, { 'X-Media-Cache': 'MISS' });
    }

    try {
        const { cacheHeader, partial, ...bundle } = await resolveDetail(env, parsed, ctx);
        return jsonResponse({
            code: 'SUCCESS',
            message: 'OK',
            requestId,
            data: bundle,
        }, 200, {
            'X-Media-Cache': cacheHeader,
            'X-Media-Partial': String(partial),
        });
    } catch {
        return jsonResponse({
            code: 'UPSTREAM_ERROR',
            message: 'Failed to load media detail',
            requestId,
            data: null,
        }, 502, { 'X-Media-Cache': 'MISS' });
    }
}

async function resolveSummary(
    env: MediaCacheEnv,
    id: MediaId,
    locale: string,
    staleIds: MediaId[],
): Promise<{ summary: MediaSummary; source: 'cache' | 'upstream' }> {
    const row = await safeReadSummaryRow(env, id, locale);
    if (row) {
        const summary = parseSummaryRow(row);
        if (summary) {
            if (isSummaryStale(row, Date.now())) {
                staleIds.push(id);
            }
            return { summary, source: 'cache' };
        }
    }

    const fetched = await fetchMediaBase(env, id.mediaType, id.tmdbId, locale, []);
    await safeSaveSummary(env, fetched.summary, Date.now(), Date.now());
    return { summary: fetched.summary, source: 'upstream' };
}

interface DetailResolution extends MediaDetailBundle {
    cacheHeader: string;
    partial: boolean;
}

async function resolveDetail(
    env: MediaCacheEnv,
    request: ParsedDetailRequest,
    ctx?: MediaBackgroundContext,
): Promise<DetailResolution> {
    const { mediaType, tmdbId, locale, sections } = request;
    const row = await safeReadSummaryRow(env, {
        mediaType,
        tmdbId,
        key: mediaIdKey(mediaType, tmdbId),
    }, locale);
    let summary = row ? parseSummaryRow(row) : null;
    const summaryFromCache = summary !== null;
    if (row && summary && isSummaryStale(row, Date.now())) {
        scheduleSummaryRefresh(env, {
            mediaType,
            tmdbId,
            key: mediaIdKey(mediaType, tmdbId),
        }, locale, ctx);
    }

    const cachedSections = new Map<MediaSection, CachedSection>();
    const missingSections: MediaSection[] = [];
    const expiredSections: MediaSection[] = [];
    let partial = false;
    // 各 section 的 manifest+R2 读取互相独立，并发加载避免 5 个 section 串行拉满
    // 延迟（快路径整体耗时从 O(n) 变 O(1)）；读写路径仍全程串行，无竞态。
    const sectionResults = await Promise.all(sections.map(async (section) => {
        const cached = await safeReadSection(env, mediaType, tmdbId, locale, section);
        return { section, cached };
    }));
    for (const { section, cached } of sectionResults) {
        if (!cached) {
            missingSections.push(section);
            continue;
        }
        cachedSections.set(section, cached);
        if (isSectionStale(section, cached.refreshedAt, Date.now())) {
            expiredSections.push(section);
        }
    }
    if (expiredSections.length > 0) {
        scheduleDetailRefresh(env, mediaType, tmdbId, locale, expiredSections, ctx);
    }

    const sectionsToFetch = new Set(missingSections);
    const appendSections = [...sectionsToFetch].filter((section) => section !== 'collection');
    let fetchedBase: FetchedMediaBase | null = null;
    if (!summary || sectionsToFetch.size > 0) {
        try {
            fetchedBase = await fetchMediaBase(env, mediaType, tmdbId, locale, appendSections);
            if (!summary) {
                summary = fetchedBase.summary;
                await safeSaveSummary(env, summary, Date.now(), Date.now());
            } else if (row && isSummaryStale(row, Date.now())) {
                // 详情请求只负责补齐 section；已有摘要即使过期，也不能用本次
                // 详情响应覆盖稳定标题/海报，否则客户端可能看到二次跳变。
                await safeSaveSummary(
                    env,
                    mergeSummaryVolatile(summary, fetchedBase.summary),
                    Date.now(),
                    Date.now(),
                    { preserveTitleRefreshedAt: true },
                );
            }
        } catch {
            // 已有摘要或已有 section 时，上游失败不能把已缓存结果整体变成 502。
            if (!summary && cachedSections.size === 0) throw new Error('TMDB summary unavailable');
            partial = true;
        }
    }

    const sectionValues = new Map<MediaSection, unknown>();
    for (const [section, cached] of cachedSections) {
        sectionValues.set(section, cached.value);
    }

    for (const section of sectionsToFetch) {
        let value: unknown = null;
        if (section === 'collection') {
            const collectionId = summary?.collectionId ?? null;
            if (collectionId !== null) {
                try {
                    value = await fetchCollection(env, collectionId, locale);
                } catch {
                    value = null;
                    partial = true;
                }
            }
        } else if (fetchedBase !== null) {
            // 只有主详情回源成功才允许映射 section；回源失败时不能用 {} 生成空数组，
            // 否则会把一次 TMDB 故障固化成长达 30~365 天的空缓存。
            value = mapSectionValue(section, fetchedBase?.raw ?? {});
        }
        sectionValues.set(section, value);
        // collection=null 是有效结果（无系列），必须缓存，避免每次详情都重新回源。
        // 其他 section 只有拿到 TMDB 数据时才写；回源失败不能把 null 当成负缓存。
        if (value !== null) {
            await safeSaveSection(env, mediaType, tmdbId, locale, section, value);
        } else if (
            section === 'collection' &&
            fetchedBase !== null &&
            summary?.collectionId === null
        ) {
            // 只有 TMDB 明确返回“无系列”时才能写负缓存；collection 请求失败不能伪装成无系列。
            await safeSaveSection(env, mediaType, tmdbId, locale, section, null);
        }
    }

    const bundle: DetailResolution = {
        summary,
        credits: asOptionalValue<MediaCredits>(sectionValues.get('credits')),
        videos: asOptionalValue<MediaVideo[]>(sectionValues.get('videos')),
        images: asOptionalValue<MediaImage[]>(sectionValues.get('images')),
        similar: asOptionalValue<MediaSimilar[]>(sectionValues.get('similar')),
        collection: asOptionalValue<MediaCollection>(sectionValues.get('collection')),
        cacheHeader: detailCacheHeader(
            summaryFromCache,
            sections,
            cachedSections,
        ),
        partial,
    };
    return bundle;
}

function detailCacheHeader(
    summaryFromCache: boolean,
    requestedSections: MediaSection[],
    cachedSections: Map<MediaSection, CachedSection>,
): string {
    if (requestedSections.length === 0) {
        return summaryFromCache ? 'D1' : 'MISS';
    }
    const cachedCount = cachedSections.size;
    if (summaryFromCache && cachedCount === requestedSections.length) return 'D1';
    if (summaryFromCache && cachedCount > 0) return 'PARTIAL';
    if (!summaryFromCache && cachedCount === requestedSections.length) return 'R2';
    if (cachedCount > 0) return 'PARTIAL';
    return 'MISS';
}

function summaryCacheHeader(
    sources: Array<'cache' | 'upstream'>,
    itemCount: number,
): string {
    if (itemCount === 0) return 'MISS';
    const cacheCount = sources.filter((source) => source === 'cache').length;
    if (cacheCount === itemCount) return 'D1';
    if (cacheCount > 0) return 'PARTIAL';
    return 'MISS';
}

async function fetchMediaBase(
    env: MediaCacheEnv,
    mediaType: MediaType,
    tmdbId: number,
    locale: string,
    sections: MediaSection[],
): Promise<FetchedMediaBase> {
    const append = ['alternative_titles', 'external_ids'];
    for (const section of sections) {
        if (section !== 'collection' && !append.includes(section)) append.push(section);
    }
    const path = mediaType === 'movie' ? `/movie/${tmdbId}` : `/tv/${tmdbId}`;
    const params: Record<string, string> = {
        language: locale,
        include_image_language: `${locale.split('-')[0]},null,en`,
        append_to_response: append.join(','),
    };
    // TMDB 追加 videos 时仍会按 language 过滤；预告片多为 en/null，
    // 必须显式 include_video_language，否则中文请求会稳定得到空数组。
    if (sections.includes('videos')) {
        params.include_video_language = `${locale.split('-')[0]},en,null`;
    }
    const response = await tmdbGet(env, path, params);
    if (!response.ok) {
        throw new Error(`TMDB detail failed: ${response.status}`);
    }
    const raw = await response.json() as RawMediaDetail;
    return {
        raw,
        summary: mapMediaSummary(raw, mediaType, tmdbId, locale),
    };
}

async function fetchCollection(
    env: MediaCacheEnv,
    collectionId: number,
    locale: string,
): Promise<MediaCollection> {
    const response = await tmdbGet(env, `/collection/${collectionId}`, {
        language: locale,
        include_image_language: `${locale.split('-')[0]},null,en`,
    });
    if (!response.ok) {
        throw new Error(`TMDB collection failed: ${response.status}`);
    }
    const raw = await response.json() as RawMediaDetail;
    const parts = Array.isArray(raw.parts)
        ? raw.parts.map((part) => mapSimilar(part)).filter((part): part is MediaSimilar => part !== null)
        : [];
    return {
        id: numberOrZero(raw.id),
        name: stringOrEmpty(raw.name),
        overview: stringOrEmpty(raw.overview),
        posterPath: nullableString(raw.poster_path),
        backdropPath: nullableString(raw.backdrop_path),
        parts,
    };
}

async function tmdbGet(
    env: MediaCacheEnv,
    path: string,
    params: Record<string, string>,
): Promise<Response> {
    const pool = new KeyPool('tmdb', env.TMDB_API_KEY, env.KV);
    return fetchWithKeyRotation(pool, async (key) => {
        const auth = buildTmdbAuth(key);
        const url = new URL(`${TMDB_BASE_URL}${path}`);
        if (auth.apiKey) url.searchParams.set('api_key', auth.apiKey);
        for (const [name, value] of Object.entries(params)) {
            url.searchParams.set(name, value);
        }
        return fetch(url.toString(), {
            headers: {
                Accept: 'application/json',
                'User-Agent': TMDB_USER_AGENT,
                ...auth.headers,
            },
            // 无界 fetch 一旦上游挂起，请求会在 CPU 时间片里空转浪费配额；
            // 超时让 fetchWithKeyRotation 快速失败，走缓存降级或错误返回。
            signal: AbortSignal.timeout(TMDB_FETCH_TIMEOUT_MS),
        });
    }, [401, 403, 429]);
}

function mapMediaSummary(
    raw: RawMediaDetail,
    mediaType: MediaType,
    tmdbId: number,
    locale: string,
): MediaSummary {
    const originalTitle = mediaType === 'movie'
        ? stringOrEmpty(raw.original_title)
        : stringOrEmpty(raw.original_name);
    const detailTitle = mediaType === 'movie'
        ? stringOrEmpty(raw.title)
        : stringOrEmpty(raw.name);
    const alternativeTitles = extractAlternativeTitles(raw.alternative_titles);
    const resolved = resolveTitle(detailTitle, originalTitle, alternativeTitles, locale);
    const genres = Array.isArray(raw.genres)
        ? raw.genres.map((genre) => stringOrEmpty((genre as Record<string, unknown>).name)).filter(Boolean)
        : [];
    const runtime = mediaType === 'movie'
        ? numberOrNull(raw.runtime)
        : firstRuntime(raw.episode_run_time);
    const countries = normalizeCountryNames(raw, locale);
    const externalIds = isRecord(raw.external_ids) ? raw.external_ids : {};
    const collection = isRecord(raw.belongs_to_collection) ? raw.belongs_to_collection : null;
    const releaseDate = mediaType === 'movie'
        ? stringOrEmpty(raw.release_date)
        : stringOrEmpty(raw.first_air_date);

    return {
        mediaType,
        tmdbId,
        locale,
        title: resolved.title,
        originalTitle,
        overview: stringOrEmpty(raw.overview),
        posterPath: nullableString(raw.poster_path),
        year: yearFromDate(releaseDate),
        genres,
        voteAverage: numberOrNull(raw.vote_average),
        runtime,
        countries,
        status: stringOrEmpty(raw.status),
        imdbId: nullableString(raw.imdb_id) ?? nullableString(externalIds.imdb_id),
        collectionId: collection ? numberOrNull(collection.id) : null,
        titleSource: resolved.source,
    };
}

function resolveTitle(
    detailTitle: string,
    originalTitle: string,
    alternativeTitles: Array<{ title: string; country: string; language: string }>,
    locale: string,
): { title: string; source: TitleSource } {
    if (detailTitle && detailTitle !== originalTitle) {
        return { title: detailTitle, source: 'DETAIL' };
    }

    const language = locale.split('-')[0].toLowerCase();
    const country = locale.includes('-') ? locale.split('-')[1].toUpperCase() : '';
    const title = alternativeTitles.find((item) => (
        country
            ? item.country.toUpperCase() === country
            : item.language.toLowerCase() === language
    ))?.title;
    if (title) return { title, source: 'ALTERNATIVE' };
    return { title: originalTitle, source: 'NONE' };
}

function extractAlternativeTitles(value: unknown): Array<{
    title: string;
    country: string;
    language: string;
}> {
    if (!isRecord(value)) return [];
    const source = Array.isArray(value.titles)
        ? value.titles
        : Array.isArray(value.results)
            ? value.results
            : [];
    return source
        .map((item) => {
            if (!isRecord(item)) return null;
            const title = stringOrEmpty(item.title);
            if (!title) return null;
            return {
                title,
                country: stringOrEmpty(item.iso_3166_1),
                language: stringOrEmpty(item.iso_639_1),
            };
        })
        .filter((item): item is { title: string; country: string; language: string } => item !== null);
}

function mapSectionValue(section: MediaSection, raw: RawMediaDetail): unknown {
    switch (section) {
        case 'credits':
            return mapCredits(raw.credits);
        case 'videos':
            return mapVideos(raw.videos);
        case 'images':
            return mapImages(raw.images);
        case 'similar':
            return mapSimilarList(raw.similar);
        case 'collection':
            return null;
    }
}

function mapCredits(value: unknown): MediaCredits {
    if (!isRecord(value)) return { cast: [], crew: [] };
    const cast = Array.isArray(value.cast)
        ? value.cast.map((item) => {
            if (!isRecord(item)) return null;
            return {
                id: numberOrZero(item.id),
                name: stringOrEmpty(item.name),
                character: stringOrEmpty(item.character),
                profilePath: nullableString(item.profile_path),
                order: numberOrZero(item.order),
            };
        }).filter((item): item is MediaCredits['cast'][number] => item !== null)
        : [];
    const crew = Array.isArray(value.crew)
        ? value.crew.map((item) => {
            if (!isRecord(item)) return null;
            return {
                id: numberOrZero(item.id),
                name: stringOrEmpty(item.name),
                job: stringOrEmpty(item.job),
                department: stringOrEmpty(item.department),
                profilePath: nullableString(item.profile_path),
            };
        }).filter((item): item is MediaCredits['crew'][number] => item !== null)
        : [];
    return { cast, crew };
}

function mapVideos(value: unknown): MediaVideo[] {
    if (!isRecord(value) || !Array.isArray(value.results)) return [];
    return value.results
        .map((item) => {
            if (!isRecord(item)) return null;
            const id = stringOrEmpty(item.id);
            const key = stringOrEmpty(item.key);
            if (!id || !key) return null;
            return {
                id,
                key,
                name: stringOrEmpty(item.name),
                site: stringOrEmpty(item.site),
                type: stringOrEmpty(item.type),
                official: item.official === true,
                size: numberOrNull(item.size),
            };
        })
        .filter((item): item is MediaVideo => item !== null);
}

function mapImages(value: unknown): MediaImage[] {
    if (!isRecord(value) || !Array.isArray(value.backdrops)) return [];
    return value.backdrops
        .map((item) => {
            if (!isRecord(item)) return null;
            const filePath = stringOrEmpty(item.file_path);
            if (!filePath) return null;
            return {
                filePath,
                width: numberOrNull(item.width),
                height: numberOrNull(item.height),
                iso6391: nullableString(item.iso_639_1),
                source: 'tmdb' as const,
            };
        })
        .filter((item): item is MediaImage => item !== null);
}

function mapSimilarList(value: unknown): MediaSimilar[] {
    if (!isRecord(value) || !Array.isArray(value.results)) return [];
    return value.results
        .map((item) => mapSimilar(item))
        .filter((item): item is MediaSimilar => item !== null);
}

function mapSimilar(value: unknown): MediaSimilar | null {
    if (!isRecord(value)) return null;
    const tmdbId = numberOrZero(value.id);
    if (tmdbId <= 0) return null;
    return {
        tmdbId,
        title: stringOrEmpty(value.title ?? value.name),
        originalTitle: stringOrEmpty(value.original_title ?? value.original_name),
        posterPath: nullableString(value.poster_path),
        releaseDate: nullableString(value.release_date ?? value.first_air_date),
        voteAverage: numberOrNull(value.vote_average),
    };
}

function parseSummaryRequest(request: Request): ParsedSummaryRequest {
    const url = new URL(request.url);
    const locale = normalizeLocale(url.searchParams.get('locale'));
    const missing: string[] = [];
    const ids: MediaId[] = [];
    const seen = new Set<string>();
    const rawIds = (url.searchParams.get('ids') ?? '')
        .split(',')
        .map((item) => item.trim())
        .filter(Boolean);

    for (const raw of rawIds) {
        const parsed = parseMediaId(raw);
        if (!parsed) {
            missing.push(raw);
            continue;
        }
        if (seen.has(parsed.key)) continue;
        seen.add(parsed.key);
        if (ids.length >= MAX_SUMMARY_IDS) {
            missing.push(parsed.key);
            continue;
        }
        ids.push(parsed);
    }
    return { locale, ids, missing };
}

function parseDetailRequest(request: Request): ParsedDetailRequest | null {
    const url = new URL(request.url);
    const mediaType = url.searchParams.get('type')?.trim().toLowerCase();
    if (mediaType !== 'movie' && mediaType !== 'tv') return null;
    const tmdbId = Number(url.searchParams.get('id'));
    if (!Number.isSafeInteger(tmdbId) || tmdbId <= 0) return null;
    const locale = normalizeLocale(url.searchParams.get('locale'));
    const sections = parseSections(url.searchParams.get('sections'));
    return { mediaType, tmdbId, locale, sections };
}

function parseMediaId(raw: string): MediaId | null {
    const match = raw.match(/^(movie|tv)\s*:\s*(\d+)$/i);
    if (!match) return null;
    const mediaType = match[1].toLowerCase() as MediaType;
    const tmdbId = Number(match[2]);
    if (!Number.isSafeInteger(tmdbId) || tmdbId <= 0) return null;
    return { mediaType, tmdbId, key: mediaIdKey(mediaType, tmdbId) };
}

function parseSections(raw: string | null): MediaSection[] {
    if (!raw) return [];
    const allowed = new Set<MediaSection>(['credits', 'videos', 'images', 'similar', 'collection']);
    const result: MediaSection[] = [];
    for (const item of raw.split(',').map((value) => value.trim().toLowerCase())) {
        if (allowed.has(item as MediaSection) && !result.includes(item as MediaSection)) {
            result.push(item as MediaSection);
        }
    }
    return result;
}

/**
 * 把 TMDB 的制片国家/地区归一化成当前请求 locale 的译名。
 *
 * TMDB 详情接口的 `production_countries[].name` 不随 `language` 参数本地化，
 * 中文请求同样拿回 "Germany" / "United States of America"。这里改用 ISO 3166-1
 * 代码配 `Intl.DisplayNames` 生成译名；缺代码或代码非法时退回 TMDB 原值，
 * 保证不会因为认不出而把已有信息抹成空。
 */
function normalizeCountryNames(raw: RawMediaDetail, locale: string): string[] {
    const display = createRegionDisplayNames(locale);
    const fromProduction = Array.isArray(raw.production_countries)
        ? raw.production_countries.map((country) => {
            const record = country as Record<string, unknown>;
            return localizeRegion(stringOrEmpty(record.iso_3166_1), stringOrEmpty(record.name), display);
        })
        : [];
    const source = fromProduction.length > 0
        ? fromProduction
        : arrayOfStrings(raw.origin_country).map((code) => localizeRegion(code, '', display));
    return source.filter(Boolean);
}

/** 单国代码优先取本地化译名，取不到则保留 TMDB 原值，最后才退回代码本身。 */
function localizeRegion(code: string, fallback: string, display: Intl.DisplayNames | null): string {
    const region = code.trim().toUpperCase();
    if (display && /^[A-Z]{2}$/.test(region)) {
        try {
            const localized = display.of(region);
            // DisplayNames 对无法识别的代码可能原样回传代码或返回 undefined，两种都不算有效译名。
            if (localized && localized !== region) return localized;
        } catch {
            // 非法代码会让 DisplayNames 抛 RangeError；这里降级到原值，不能让整批摘要失败。
        }
    }
    return fallback || region;
}

/** 构造地区显示名解析器；运行时不支持时返回 null，由调用方回退 TMDB 原值。 */
function createRegionDisplayNames(locale: string): Intl.DisplayNames | null {
    try {
        return new Intl.DisplayNames([locale], { type: 'region' });
    } catch {
        return null;
    }
}

function normalizeLocale(raw: string | null): string {
    const value = raw?.trim() || 'zh-CN';
    return /^[a-zA-Z]{2,3}(?:-[a-zA-Z]{2})?$/.test(value) ? value : 'zh-CN';
}

function mediaIdKey(mediaType: MediaType, tmdbId: number): string {
    return `${mediaType}:${tmdbId}`;
}

function summaryObjectKey(mediaType: MediaType, tmdbId: number, locale: string, section: MediaSection): string {
    return `v1/${mediaType}/${tmdbId}/${locale}/${section}.json`;
}

function collectionObjectKey(collectionId: number, locale: string): string {
    return `v1/collection/${collectionId}/${locale}.json`;
}

function leaseKey(
    kind: 'summary' | 'detail',
    mediaType: MediaType,
    tmdbId: number,
    locale: string,
    section?: MediaSection,
): string {
    const suffix = section ? `:${section}` : '';
    return `${kind}:${mediaType}:${tmdbId}:${locale}:${SCHEMA_VERSION}${suffix}`;
}

async function safeReadSummaryRow(
    env: MediaCacheEnv,
    id: MediaId,
    locale: string,
): Promise<SummaryRow | null> {
    try {
        return await env.MEDIA_DB.prepare(`
            SELECT payload_json, title_refreshed_at, volatile_refreshed_at
            FROM media_summary
            WHERE media_type = ? AND tmdb_id = ? AND locale = ? AND schema_version = ?
        `).bind(id.mediaType, id.tmdbId, locale, SCHEMA_VERSION).first<SummaryRow>();
    } catch {
        return null;
    }
}

function parseSummaryRow(row: SummaryRow): MediaSummary | null {
    try {
        const parsed = JSON.parse(row.payload_json) as Partial<MediaSummary>;
        if (!parsed || typeof parsed !== 'object') return null;
        if (parsed.mediaType !== 'movie' && parsed.mediaType !== 'tv') return null;
        if (!Number.isSafeInteger(parsed.tmdbId) || typeof parsed.locale !== 'string') return null;
        return parsed as MediaSummary;
    } catch {
        return null;
    }
}

function mergeSummaryVolatile(current: MediaSummary, fetched: MediaSummary): MediaSummary {
    return {
        ...current,
        // 详情回源可能带更新的评分/时长/状态，但标题、海报、原名和系列归属保持稳定。
        voteAverage: fetched.voteAverage ?? current.voteAverage,
        runtime: fetched.runtime ?? current.runtime,
        status: fetched.status || current.status,
    };
}

function isSummaryStale(row: SummaryRow, now: number): boolean {
    const titleFresh = Number(row.title_refreshed_at) > 0;
    const volatileFresh = now - Number(row.volatile_refreshed_at) < VOLATILE_SUMMARY_TTL_MS;
    return !titleFresh || !volatileFresh;
}

async function safeSaveSummary(
    env: MediaCacheEnv,
    summary: MediaSummary,
    titleRefreshedAt: number,
    volatileRefreshedAt: number,
    options: {
        preserveRefreshedAt?: boolean;
        preserveTitleRefreshedAt?: boolean;
    } = {},
): Promise<void> {
    try {
        let nextTitleRefreshedAt = titleRefreshedAt;
        let nextVolatileRefreshedAt = volatileRefreshedAt;
        if (options.preserveRefreshedAt) {
            const existing = await env.MEDIA_DB.prepare(`
                SELECT title_refreshed_at, volatile_refreshed_at
                FROM media_summary
                WHERE media_type = ? AND tmdb_id = ? AND locale = ? AND schema_version = ?
            `).bind(
                summary.mediaType,
                summary.tmdbId,
                summary.locale,
                SCHEMA_VERSION,
            ).first<{ title_refreshed_at: number; volatile_refreshed_at: number }>();
            nextTitleRefreshedAt = existing?.title_refreshed_at != null
                ? Number(existing.title_refreshed_at)
                : titleRefreshedAt;
            nextVolatileRefreshedAt = existing?.volatile_refreshed_at != null
                ? Number(existing.volatile_refreshed_at)
                : volatileRefreshedAt;
        } else if (options.preserveTitleRefreshedAt) {
            const existing = await env.MEDIA_DB.prepare(`
                SELECT title_refreshed_at, volatile_refreshed_at
                FROM media_summary
                WHERE media_type = ? AND tmdb_id = ? AND locale = ? AND schema_version = ?
            `).bind(
                summary.mediaType,
                summary.tmdbId,
                summary.locale,
                SCHEMA_VERSION,
            ).first<{ title_refreshed_at: number; volatile_refreshed_at: number }>();
            nextTitleRefreshedAt = existing?.title_refreshed_at != null
                ? Number(existing.title_refreshed_at)
                : titleRefreshedAt;
            // 只保留稳定字段的刷新时间；易变字段本次确实已回源成功，必须推进 TTL。
        }
        await env.MEDIA_DB.prepare(`
            INSERT INTO media_summary (
                media_type, tmdb_id, locale, schema_version, payload_json,
                title_refreshed_at, volatile_refreshed_at, updated_at
            ) VALUES (?, ?, ?, ?, ?, ?, ?, ?)
            ON CONFLICT(media_type, tmdb_id, locale, schema_version) DO UPDATE SET
                payload_json = excluded.payload_json,
                title_refreshed_at = excluded.title_refreshed_at,
                volatile_refreshed_at = excluded.volatile_refreshed_at,
                updated_at = excluded.updated_at
        `).bind(
            summary.mediaType,
            summary.tmdbId,
            summary.locale,
            SCHEMA_VERSION,
            JSON.stringify(summary),
            nextTitleRefreshedAt,
            nextVolatileRefreshedAt,
            Date.now(),
        ).run();
    } catch {
        // 已获取到的公开数据优先返回；落盘失败不阻断请求。
    }
}

async function safeReadSection(
    env: MediaCacheEnv,
    mediaType: MediaType,
    tmdbId: number,
    locale: string,
    section: MediaSection,
): Promise<CachedSection | null> {
    try {
        const manifest = await env.MEDIA_DB.prepare(`
            SELECT object_key, refreshed_at
            FROM media_detail_manifest
            WHERE media_type = ? AND tmdb_id = ? AND locale = ? AND schema_version = ? AND section = ?
        `).bind(mediaType, tmdbId, locale, SCHEMA_VERSION, section).first<ManifestRow>();
        if (!manifest?.object_key) return null;
        const object = await env.MEDIA_CACHE.get(manifest.object_key);
        if (!object) return null;
        const value = JSON.parse(await object.text()) as unknown;
        return {
            value,
            refreshedAt: Number(manifest.refreshed_at) || 0,
            objectKey: manifest.object_key,
        };
    } catch {
        return null;
    }
}

async function safeSaveSection(
    env: MediaCacheEnv,
    mediaType: MediaType,
    tmdbId: number,
    locale: string,
    section: MediaSection,
    value: unknown,
): Promise<void> {
    try {
        // 无系列的 collection=null 也必须能落缓存；使用条目自身的 section key 作为负缓存，
        // 有系列时仍共享 collection/{id} 对象，避免重复存储同一系列。
        const objectKey = section === 'collection' && isRecord(value)
            ? collectionObjectKey(numberOrZero(value.id), locale)
            : summaryObjectKey(mediaType, tmdbId, locale, section);
        await env.MEDIA_CACHE.put(objectKey, JSON.stringify(value));
        await env.MEDIA_DB.prepare(`
            INSERT INTO media_detail_manifest (
                media_type, tmdb_id, locale, schema_version, section, object_key, refreshed_at
            ) VALUES (?, ?, ?, ?, ?, ?, ?)
            ON CONFLICT(media_type, tmdb_id, locale, schema_version, section) DO UPDATE SET
                object_key = excluded.object_key,
                refreshed_at = excluded.refreshed_at
        `).bind(
            mediaType,
            tmdbId,
            locale,
            SCHEMA_VERSION,
            section,
            objectKey,
            Date.now(),
        ).run();
    } catch {
        // R2/D1 写失败不阻断当前响应。
    }
}

function isSectionStale(section: MediaSection, refreshedAt: number, now: number): boolean {
    if (!refreshedAt) return true;
    return now - refreshedAt >= sectionTtlMs(section);
}

function sectionTtlMs(section: MediaSection): number {
    switch (section) {
        case 'credits':
            return CREDITS_TTL_MS;
        case 'videos':
            return VIDEOS_TTL_MS;
        case 'images':
            return IMAGES_TTL_MS;
        case 'similar':
            return SIMILAR_TTL_MS;
        case 'collection':
            return COLLECTION_TTL_MS;
    }
}

function scheduleSummaryRefresh(
    env: MediaCacheEnv,
    id: MediaId,
    locale: string,
    ctx?: MediaBackgroundContext,
): void {
    runInBackground(ctx, refreshSummary(env, id, locale));
}

function scheduleDetailRefresh(
    env: MediaCacheEnv,
    mediaType: MediaType,
    tmdbId: number,
    locale: string,
    sections: MediaSection[],
    ctx?: MediaBackgroundContext,
): void {
    runInBackground(ctx, refreshDetailSections(env, mediaType, tmdbId, locale, sections));
}

function runInBackground(ctx: MediaBackgroundContext, task: Promise<void>): void {
    const safeTask = task.catch(() => undefined);
    if (ctx) {
        ctx.waitUntil(safeTask);
    }
}

async function refreshSummary(
    env: MediaCacheEnv,
    id: MediaId,
    locale: string,
): Promise<void> {
    const key = leaseKey('summary', id.mediaType, id.tmdbId, locale);
    const lease = await tryAcquireLease(env, key);
    if (!lease) return;
    try {
        const fetched = await fetchMediaBase(env, id.mediaType, id.tmdbId, locale, []);
        const existingRow = await safeReadSummaryRow(env, id, locale);
        const existing = existingRow ? parseSummaryRow(existingRow) : null;
        if (existing) {
            // 标题、海报、原名等稳定字段长期保留；后台刷新只更新评分、时长、状态等易变字段。
            await safeSaveSummary(
                env,
                mergeSummaryVolatile(existing, fetched.summary),
                Date.now(),
                Date.now(),
                { preserveTitleRefreshedAt: true },
            );
        } else {
            await safeSaveSummary(env, fetched.summary, Date.now(), Date.now());
        }
    } finally {
        await releaseLease(env, key, lease.owner);
    }
}

async function refreshDetailSections(
    env: MediaCacheEnv,
    mediaType: MediaType,
    tmdbId: number,
    locale: string,
    sections: MediaSection[],
): Promise<void> {
    // 租约按 section 粒度领取：多个 section 同时过期时不能互相吞掉刷新任务，
    // 同一 section 的并发刷新仍由各自的 lease 单飞。
    for (const section of sections) {
        const key = leaseKey('detail', mediaType, tmdbId, locale, section);
        const lease = await tryAcquireLease(env, key);
        if (!lease) continue;
        try {
            await refreshDetailSection(env, mediaType, tmdbId, locale, section);
        } finally {
            await releaseLease(env, key, lease.owner);
        }
    }
}

/** 刷新单个 section，不触碰摘要稳定字段。 */
async function refreshDetailSection(
    env: MediaCacheEnv,
    mediaType: MediaType,
    tmdbId: number,
    locale: string,
    section: MediaSection,
): Promise<void> {
    const appendSections = section === 'collection' ? [] : [section];
    const fetched = await fetchMediaBase(env, mediaType, tmdbId, locale, appendSections);
    if (section === 'collection') {
        if (fetched.summary.collectionId !== null) {
            const value = await fetchCollection(env, fetched.summary.collectionId, locale);
            await safeSaveSection(env, mediaType, tmdbId, locale, section, value);
        } else {
            // 本次回源已确认“无系列”，同样要写负缓存推进 refreshed_at，
            // 否则空系列条目每个过期周期都会重复回源。
            await safeSaveSection(env, mediaType, tmdbId, locale, section, null);
        }
        return;
    }
    const value = mapSectionValue(section, fetched.raw);
    if (value !== null) {
        await safeSaveSection(env, mediaType, tmdbId, locale, section, value);
    }
}

async function tryAcquireLease(
    env: MediaCacheEnv,
    key: string,
): Promise<{ owner: string } | null> {
    const owner = crypto.randomUUID();
    try {
        const now = Date.now();
        const result = await env.MEDIA_DB.prepare(`
            INSERT INTO media_refresh_lease (lease_key, expires_at, owner)
            VALUES (?, ?, ?)
            ON CONFLICT(lease_key) DO UPDATE SET
                expires_at = excluded.expires_at,
                owner = excluded.owner
            WHERE media_refresh_lease.expires_at <= ?
        `).bind(key, now + REFRESH_LEASE_MS, owner, now).run();
        return Number(result.meta.changes) > 0 ? { owner } : null;
    } catch {
        return null;
    }
}

async function releaseLease(env: MediaCacheEnv, key: string, owner: string): Promise<void> {
    try {
        await env.MEDIA_DB.prepare(
            'DELETE FROM media_refresh_lease WHERE lease_key = ? AND owner = ?',
        )
            .bind(key, owner)
            .run();
    } catch {
        // 租约自然过期即可，不影响结果。
    }
}

async function mapLimit<T, R>(
    items: T[],
    limit: number,
    task: (item: T) => Promise<R>,
): Promise<R[]> {
    const results = new Array<R>(items.length);
    let nextIndex = 0;
    const workers = Array.from({ length: Math.min(limit, items.length) }, async () => {
        while (true) {
            const index = nextIndex;
            nextIndex += 1;
            if (index >= items.length) return;
            results[index] = await task(items[index]);
        }
    });
    await Promise.all(workers);
    return results;
}

function jsonResponse(
    body: unknown,
    status: number,
    headers: Record<string, string>,
): Response {
    return new Response(JSON.stringify(body), {
        status,
        headers: {
            'Content-Type': 'application/json; charset=utf-8',
            ...headers,
        },
    });
}

function createRequestId(): string {
    if (typeof crypto !== 'undefined' && 'randomUUID' in crypto) {
        return crypto.randomUUID();
    }
    return `media-${Date.now()}-${Math.random().toString(16).slice(2)}`;
}

function isRecord(value: unknown): value is Record<string, unknown> {
    return typeof value === 'object' && value !== null && !Array.isArray(value);
}

function stringOrEmpty(value: unknown): string {
    return typeof value === 'string' ? value : '';
}

function nullableString(value: unknown): string | null {
    return typeof value === 'string' && value ? value : null;
}

function numberOrNull(value: unknown): number | null {
    return typeof value === 'number' && Number.isFinite(value) ? value : null;
}

function numberOrZero(value: unknown): number {
    return numberOrNull(value) ?? 0;
}

function arrayOfStrings(value: unknown): string[] {
    return Array.isArray(value)
        ? value.filter((item): item is string => typeof item === 'string' && item.length > 0)
        : [];
}

function firstRuntime(value: unknown): number | null {
    if (!Array.isArray(value)) return null;
    for (const item of value) {
        const runtime = numberOrNull(item);
        if (runtime !== null) return runtime;
    }
    return null;
}

function yearFromDate(value: string): number | null {
    const match = value.match(/^(\d{4})/);
    return match ? Number(match[1]) : null;
}

function asOptionalValue<T>(value: unknown): T | null {
    return value === null || value === undefined ? null : value as T;
}
