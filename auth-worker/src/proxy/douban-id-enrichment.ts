import type { Env } from '../index.ts';
import { KeyPool, fetchWithKeyRotation } from '../util/key-pool.ts';
import { buildTmdbAuth } from './tmdb-token.ts';
import { buildTraktPublicApiHeaders } from './trakt-token.ts';

export const DOUBAN_ID_MATCHER_VERSION = 1;
const ENRICHMENT_CONCURRENCY = 5;
const NEGATIVE_MAPPING_TTL_SECONDS = 7 * 24 * 60 * 60;

export interface TmdbMovieCandidate {
    id: number;
    title?: string;
    original_title?: string;
    release_date?: string;
}

export interface DoubanMatchInput {
    title: string;
    year?: string;
}

export interface DoubanEnrichableItem {
    id: string;
    title: string;
    year?: string;
    release?: string;
    tmdbId?: number;
    traktId?: number;
    imdbId?: string;
    mediaType?: string;
}

export type DoubanMappingStatus = 'matched' | 'unmatched' | 'ambiguous';

export interface DoubanIdMapping {
    status: DoubanMappingStatus;
    sourceId: string;
    sourceTitle: string;
    matchedTitle: string;
    year: string;
    mediaType: string;
    tmdbId: number;
    traktId: number;
    imdbId: string;
    matcherVersion: number;
    updatedAt: string;
}

export function buildDoubanMappingKey(sourceId: string, mediaType: string): string {
    return `douban:id:${sourceId}:${mediaType}:v${DOUBAN_ID_MATCHER_VERSION}`;
}

export function normalizeMediaTitle(value: string): string {
    return value
        .normalize('NFKC')
        .toLocaleLowerCase()
        .replace(/[^\p{L}\p{N}]+/gu, '');
}

export function chooseStrictTmdbMovie(
    source: DoubanMatchInput,
    candidates: TmdbMovieCandidate[],
): TmdbMovieCandidate | null {
    return inspectStrictTmdbMovie(source, candidates).candidate;
}

export function inspectStrictTmdbMovie(
    source: DoubanMatchInput,
    candidates: TmdbMovieCandidate[],
): { status: DoubanMappingStatus; candidate: TmdbMovieCandidate | null } {
    const sourceTitle = normalizeMediaTitle(source.title);
    if (!sourceTitle) return { status: 'unmatched', candidate: null };

    const titleMatches = candidates.filter((candidate) => {
        const titles = [candidate.title, candidate.original_title]
            .filter((title): title is string => Boolean(title))
            .map(normalizeMediaTitle);
        return titles.includes(sourceTitle);
    });

    const yearMatches = source.year
        ? titleMatches.filter((candidate) => candidate.release_date?.slice(0, 4) === source.year)
        : titleMatches;

    if (yearMatches.length === 1) return { status: 'matched', candidate: yearMatches[0] };
    return {
        status: yearMatches.length > 1 ? 'ambiguous' : 'unmatched',
        candidate: null,
    };
}

export async function applyCachedDoubanMappings<T extends DoubanEnrichableItem>(
    items: T[],
    kv: Pick<KVNamespace, 'get'>,
): Promise<{ items: T[]; pending: T[] }> {
    const results = await Promise.all(items.map(async (item) => {
        if (item.tmdbId && item.tmdbId > 0 && item.traktId && item.traktId > 0) {
            return { item: { ...item, mediaType: item.mediaType || 'movie' }, pending: false };
        }

        const mapping = await readMapping(kv, buildDoubanMappingKey(item.id, 'movie'));
        if (!mapping) return { item, pending: true };
        if (mapping.status === 'matched') return { item: applyMapping(item, mapping), pending: false };
        return { item, pending: false };
    }));

    return {
        items: results.map(({ item }) => item),
        pending: results.filter(({ pending }) => pending).map(({ item }) => item),
    };
}

export async function enrichDoubanItems<T extends DoubanEnrichableItem>(
    items: T[],
    env: Pick<Env, 'KV' | 'TMDB_API_KEY' | 'TRAKT_CLIENT_ID'>,
): Promise<T[]> {
    return mapWithConcurrency(items, ENRICHMENT_CONCURRENCY, async (item) => {
        if (item.tmdbId && item.tmdbId > 0 && item.traktId && item.traktId > 0) {
            return { ...item, mediaType: item.mediaType || 'movie' };
        }

        const key = buildDoubanMappingKey(item.id, 'movie');
        const cached = await readMapping(env.KV, key);
        if (cached) return cached.status === 'matched' ? applyMapping(item, cached) : item;

        try {
            const year = item.year || item.release?.match(/\d{4}/)?.[0] || '';
            const tmdbCandidates = await searchTmdbMovies(item.title, year, env);
            const decision = inspectStrictTmdbMovie({ title: item.title, year }, tmdbCandidates);
            const now = new Date().toISOString();

            if (!decision.candidate) {
                const mapping: DoubanIdMapping = {
                    status: decision.status,
                    sourceId: item.id,
                    sourceTitle: item.title,
                    matchedTitle: '',
                    year,
                    mediaType: 'movie',
                    tmdbId: 0,
                    traktId: 0,
                    imdbId: '',
                    matcherVersion: DOUBAN_ID_MATCHER_VERSION,
                    updatedAt: now,
                };
                await saveMapping(env.KV, key, mapping);
                return item;
            }

            const traktMatch = await searchTraktByTmdb(decision.candidate.id, env);
            if (!traktMatch) {
                const mapping: DoubanIdMapping = {
                    status: 'unmatched',
                    sourceId: item.id,
                    sourceTitle: item.title,
                    matchedTitle: decision.candidate.title || decision.candidate.original_title || '',
                    year,
                    mediaType: 'movie',
                    tmdbId: 0,
                    traktId: 0,
                    imdbId: '',
                    matcherVersion: DOUBAN_ID_MATCHER_VERSION,
                    updatedAt: now,
                };
                await saveMapping(env.KV, key, mapping);
                return item;
            }

            const mapping: DoubanIdMapping = {
                status: 'matched',
                sourceId: item.id,
                sourceTitle: item.title,
                matchedTitle: decision.candidate.title || decision.candidate.original_title || '',
                year,
                mediaType: 'movie',
                tmdbId: decision.candidate.id,
                traktId: traktMatch.traktId,
                imdbId: traktMatch.imdbId,
                matcherVersion: DOUBAN_ID_MATCHER_VERSION,
                updatedAt: now,
            };
            await saveMapping(env.KV, key, mapping);
            return applyMapping(item, mapping);
        } catch (error) {
            console.warn(JSON.stringify({
                event: 'douban_id_enrichment_failed',
                sourceId: item.id,
                error: error instanceof Error ? error.message : String(error),
            }));
            return item;
        }
    });
}

async function searchTmdbMovies(
    title: string,
    year: string,
    env: Pick<Env, 'KV' | 'TMDB_API_KEY'>,
): Promise<TmdbMovieCandidate[]> {
    const pool = new KeyPool('tmdb', env.TMDB_API_KEY, env.KV);
    const response = await fetchWithKeyRotation(pool, async (key) => {
        const auth = buildTmdbAuth(key);
        const url = new URL('https://api.tmdb.org/3/search/movie');
        url.searchParams.set('query', title);
        url.searchParams.set('language', 'zh-CN');
        url.searchParams.set('region', 'CN');
        url.searchParams.set('include_adult', 'false');
        url.searchParams.set('page', '1');
        if (year) url.searchParams.set('year', year);
        if (auth.apiKey) url.searchParams.set('api_key', auth.apiKey);
        return fetch(url, {
            headers: {
                Accept: 'application/json',
                'User-Agent': 'TrackToSearch/3.0',
                ...auth.headers,
            },
        });
    }, [401, 403, 429]);

    if (!response.ok) throw new Error(`TMDB search failed: ${response.status}`);
    const data = await response.json() as { results?: TmdbMovieCandidate[] };
    return data.results || [];
}

async function searchTraktByTmdb(
    tmdbId: number,
    env: Pick<Env, 'KV' | 'TRAKT_CLIENT_ID'>,
): Promise<{ traktId: number; imdbId: string } | null> {
    const pool = new KeyPool('trakt', env.TRAKT_CLIENT_ID, env.KV);
    const response = await fetchWithKeyRotation(pool, async (clientId) => {
        const url = new URL(`https://api.trakt.tv/search/tmdb/${tmdbId}`);
        url.searchParams.set('type', 'movie');
        url.searchParams.set('limit', '5');
        return fetch(url, {
            headers: buildTraktPublicApiHeaders(clientId),
        });
    }, [401, 403, 429]);

    if (!response.ok) throw new Error(`Trakt lookup failed: ${response.status}`);
    const results = await response.json() as Array<{
        type?: string;
        movie?: { ids?: { trakt?: number; tmdb?: number; imdb?: string | null } };
    }>;
    const match = results.find((result) => (
        result.type === 'movie'
        && result.movie?.ids?.trakt
        && result.movie.ids.trakt > 0
        && (!result.movie.ids.tmdb || result.movie.ids.tmdb === tmdbId)
    ));
    if (!match?.movie?.ids?.trakt) return null;
    return {
        traktId: match.movie.ids.trakt,
        imdbId: match.movie.ids.imdb || '',
    };
}

async function readMapping(
    kv: Pick<KVNamespace, 'get'>,
    key: string,
): Promise<DoubanIdMapping | null> {
    const raw = await kv.get(key);
    if (!raw) return null;
    try {
        const parsed = JSON.parse(raw) as DoubanIdMapping;
        if (parsed.matcherVersion !== DOUBAN_ID_MATCHER_VERSION) return null;
        if (!['matched', 'unmatched', 'ambiguous'].includes(parsed.status)) return null;
        return parsed;
    } catch {
        return null;
    }
}

async function saveMapping(kv: KVNamespace, key: string, mapping: DoubanIdMapping): Promise<void> {
    await kv.put(key, JSON.stringify(mapping), mapping.status === 'matched'
        ? undefined
        : { expirationTtl: NEGATIVE_MAPPING_TTL_SECONDS });
}

function applyMapping<T extends DoubanEnrichableItem>(item: T, mapping: DoubanIdMapping): T {
    return {
        ...item,
        tmdbId: mapping.tmdbId,
        traktId: mapping.traktId,
        imdbId: mapping.imdbId,
        mediaType: mapping.mediaType,
    };
}

async function mapWithConcurrency<T, R>(
    values: T[],
    concurrency: number,
    mapper: (value: T) => Promise<R>,
): Promise<R[]> {
    const results = new Array<R>(values.length);
    let nextIndex = 0;
    const worker = async (): Promise<void> => {
        while (true) {
            const index = nextIndex++;
            if (index >= values.length) return;
            results[index] = await mapper(values[index]);
        }
    };
    await Promise.all(Array.from({ length: Math.min(concurrency, values.length) }, worker));
    return results;
}
