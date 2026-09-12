import { AppError, successResponse } from '../util/errors.ts';
import {
    callMimoJson,
    isTestFallback,
    parseAssistantJson,
    type MimoMessage,
} from './mimo.ts';
import {
    readAiCache,
    reserveAiQuota,
    writeAiCache,
    type AiStoreEnvironment,
} from './store.ts';
import { readProfile, type MediaIds, type ProfileStoreEnvironment } from './profile-store.ts';

const MAX_DETAIL_REQUEST_BYTES = 256 * 1024;
const MAX_CANDIDATES = 60;
const MAX_TEXT = 1200;
const MAX_REASON_COUNT = 4;
const SCENES = new Set(['UNMARKED', 'WATCHED_NEEDS_REVIEW', 'WATCHED_REVIEWED', 'WATCHLIST_CONTEXT']);
const LEVELS = new Set(['HIGH', 'MEDIUM', 'LOW']);
const TIME_LEVELS = new Set(['NOW', 'SOON', 'LATER', 'UNKNOWN']);

export interface DetailEnvironment extends ProfileStoreEnvironment {
    MIMO_API_KEY?: string;
}

interface DetailMedia {
    mediaKey: string;
    mediaType: 'movie' | 'show';
    title: string;
    year: number | null;
    genres: string[];
    overview: string | null;
    directors: string[];
    cast: string[];
    publicRating: number | null;
    mediaIds: MediaIds;
}

interface DetailEnvironmentInput {
    localDate: string;
    weekday: number;
    timeOfDay: string;
    season: string;
    weatherTag: string | null;
}

interface DetailRequestInput {
    media: DetailMedia;
    scene: string;
    environment: DetailEnvironmentInput | null;
    forceRefresh: boolean;
}

interface DetailResponse {
    scene: string;
    interestLevel: 'HIGH' | 'MEDIUM' | 'LOW';
    confidence: 'HIGH' | 'MEDIUM' | 'LOW';
    spoilerFreeSummary: string;
    publicRatingInterpretation: string;
    watchAdvice: string;
    reasons: string[];
    watchTiming?: {
        level: 'NOW' | 'SOON' | 'LATER' | 'UNKNOWN';
        basis: string[];
    } | null;
    profileVersion: number;
}

interface Candidate {
    mediaKey: string;
    mediaType: 'movie' | 'show';
    title: string;
    year: number | null;
    genres: string[];
    mediaIds: MediaIds;
}

export async function handleAiDetailApi(
    request: Request,
    env: DetailEnvironment,
    requestId: string,
    path: string,
    friendId: string,
    deviceId: string,
): Promise<Response> {
    if (path === '/api/ai/detail/analyze' && request.method === 'POST') {
        const body = await readJsonBody(request);
        const input = normalizeDetailRequest(body);
        const profile = await readProfile(env, friendId);
        const cacheKey = `ai:v2:detail:${friendId}:${input.media.mediaKey}:${input.scene}:${profile.settings.profileVersion}:${environmentKey(input)}`;
        if (!input.forceRefresh) {
            const cached = await readAiCache(env, cacheKey);
            if (cached) return successResponse(cached, requestId);
        }
        const quota = await reserveAiQuota(env, friendId, deviceId, 'detail');
        const response = await analyzeDetail(env, input, profile);
        await writeAiCache(env, cacheKey, response, 24 * 60 * 60, friendId, 'detail');
        return successResponse(response, requestId, quota);
    }

    if (path === '/api/ai/recommendations/rank' && request.method === 'POST') {
        const body = await readJsonBody(request);
        const profile = await readProfile(env, friendId);
        const result = await rankRecommendations(env, body, profile, friendId);
        return successResponse(result, requestId);
    }
    throw new AppError('NOT_FOUND', 'Not found', 404);
}

async function analyzeDetail(
    env: DetailEnvironment,
    input: DetailRequestInput,
    profile: Awaited<ReturnType<typeof readProfile>>,
): Promise<DetailResponse> {
    if (!isTestFallback(env) && env.MIMO_API_KEY) {
        try {
            const upstream = await callMimoJson(
                env,
                'mimo-v2.5',
                detailMessages(input, profile),
                { maxCompletionTokens: 700, temperature: 0.2 },
            );
            if (upstream) return normalizeDetailOutput(parseAssistantJson<unknown>(upstream), input, profile.settings.profileVersion);
        } catch {
            // 上游输出不符合协议时使用保守的结构化回退，不透传模型原文。
        }
    }
    return fallbackDetail(input, profile.settings.profileVersion);
}

async function rankRecommendations(
    env: DetailEnvironment,
    body: Record<string, unknown>,
    profile: Awaited<ReturnType<typeof readProfile>>,
    friendId: string,
): Promise<{ recommendations: Array<Candidate & { reason: string }>; profileVersion: number }> {
    const current = normalizeCurrentMedia(body.currentMedia);
    const candidateValue = body.candidates;
    if (!Array.isArray(candidateValue) || candidateValue.length > MAX_CANDIDATES) {
        throw new AppError('INVALID_DETAIL_INPUT', 'Candidates are invalid', 400);
    }
    const existingKeys = new Set<string>();
    for (const item of profile.media) {
        if (item.watched || item.watchlist || item.rating !== null || item.commentPresent) {
            const key = reliableMediaKey(item.mediaIds);
            if (key) existingKeys.add(key);
        }
    }
    const currentKey = reliableMediaKey(current.mediaIds);
    const candidates: Candidate[] = [];
    const seen = new Set<string>();
    for (const [index, value] of candidateValue.entries()) {
        let candidate: Candidate;
        try {
            candidate = normalizeCandidate(value, `candidate ${index + 1}`);
        } catch (error) {
            if (error instanceof AppError && error.code === 'INVALID_DETAIL_INPUT') continue;
            throw error;
        }
        const key = reliableMediaKey(candidate.mediaIds);
        if (!key || key === currentKey || existingKeys.has(key) || seen.has(key)) continue;
        seen.add(key);
        candidates.push(candidate);
    }

    let ordered = candidates;
    if (!isTestFallback(env) && env.MIMO_API_KEY && candidates.length > 0) {
        try {
            const upstream = await callMimoJson(env, 'mimo-v2.5', recommendationMessages(candidates, profile), {
                maxCompletionTokens: 500,
                temperature: 0.2,
            });
            if (upstream) ordered = applyModelOrder(parseAssistantJson<unknown>(upstream), candidates);
        } catch {
            ordered = candidates;
        }
    }
    return {
        recommendations: ordered.map(candidate => ({
            ...candidate,
            reason: recommendationReason(candidate, profile),
        })),
        profileVersion: profile.settings.profileVersion,
    };
}

function fallbackDetail(input: DetailRequestInput, profileVersion: number): DetailResponse {
    const hasProfile = profileVersion > 0;
    const highSignal = input.media.genres.some(genre => /drama|comedy|romance|animation|fantasy/i.test(genre));
    const interestLevel = hasProfile && highSignal ? 'HIGH' : highSignal ? 'MEDIUM' : 'LOW';
    const reasons = hasProfile
        ? ['结合你已授权的观影记录进行初步匹配', '当前影视的类型和公开信息已通过结构化字段核对']
        : ['当前没有足够的个人口味数据', '先根据类型、简介和公开评分给出保守判断'];
    const response: DetailResponse = {
        scene: input.scene,
        interestLevel,
        confidence: hasProfile ? 'MEDIUM' : 'LOW',
        spoilerFreeSummary: input.media.overview
            ? trimText(input.media.overview, MAX_TEXT)
            : '这是一部可以从类型、公开简介和整体气质开始了解的影视作品。',
        publicRatingInterpretation: input.media.publicRating === null
            ? '暂无足够的公共评分信息，建议结合简介和你的当下观影状态判断。'
            : `公共评分约为 ${input.media.publicRating.toFixed(1)}，说明它在公开观众中有一定认可度，但不等同于你的个人评分。`,
        watchAdvice: input.scene === 'WATCHED_NEEDS_REVIEW'
            ? '如果你还记得观影感受，可以补充评分或短评，帮助之后的推荐更准确。'
            : '可以先看简介和预告；如果当下想看轻松或有情绪余韵的作品，它值得加入候选。',
        reasons,
        profileVersion,
    };
    if (input.scene === 'WATCHLIST_CONTEXT') {
        response.watchTiming = {
            level: timingLevel(input.environment),
            basis: timingBasis(input.environment),
        };
    } else {
        response.watchTiming = null;
    }
    return response;
}

function normalizeDetailOutput(value: unknown, input: DetailRequestInput, profileVersion: number): DetailResponse {
    const object = requireRecord(value, 'AI detail output');
    const interestLevel = readLevel(object.interestLevel);
    const confidence = readLevel(object.confidence);
    const response: DetailResponse = {
        scene: input.scene,
        interestLevel,
        confidence,
        spoilerFreeSummary: readText(object.spoilerFreeSummary, 'spoilerFreeSummary', MAX_TEXT),
        publicRatingInterpretation: readText(object.publicRatingInterpretation, 'publicRatingInterpretation', MAX_TEXT),
        watchAdvice: readText(object.watchAdvice, 'watchAdvice', MAX_TEXT),
        reasons: readReasons(object.reasons),
        profileVersion,
    };
    if (input.scene === 'WATCHLIST_CONTEXT') {
        const timing = object.watchTiming;
        response.watchTiming = normalizeTiming(timing, input.environment);
    } else {
        response.watchTiming = null;
    }
    return response;
}

function normalizeTiming(value: unknown, environment: DetailEnvironmentInput | null) {
    if (!value || typeof value !== 'object' || Array.isArray(value)) {
        return { level: timingLevel(environment), basis: timingBasis(environment) };
    }
    const object = value as Record<string, unknown>;
    const level = typeof object.level === 'string' && TIME_LEVELS.has(object.level)
        ? object.level as 'NOW' | 'SOON' | 'LATER' | 'UNKNOWN'
        : timingLevel(environment);
    return { level, basis: readReasons(object.basis ?? timingBasis(environment)) };
}

function normalizeCandidate(value: unknown, label: string): Candidate {
    const object = requireRecord(value, label);
    rejectLocationAndRawData(object);
    const mediaKey = readMediaKey(object.mediaKey);
    const mediaType = object.mediaType;
    if (mediaType !== 'movie' && mediaType !== 'show') {
        throw new AppError('INVALID_DETAIL_INPUT', `${label} mediaType is invalid`, 400);
    }
    const mediaIds = readMediaIds(object.mediaIds ?? object.ids);
    if (!reliableMediaKey(mediaIds)) {
        throw new AppError('INVALID_DETAIL_INPUT', `${label} needs a reliable media id`, 400);
    }
    return {
        mediaKey,
        mediaType,
        title: readText(object.title, `${label} title`, 200),
        year: optionalInteger(object.year, 1800, 3000),
        genres: readStringArray(object.genres, 8, 48),
        mediaIds,
    };
}

function normalizeCurrentMedia(value: unknown): { mediaKey: string; mediaIds: MediaIds } {
    const object = requireRecord(value, 'currentMedia');
    rejectLocationAndRawData(object);
    const mediaKey = readMediaKey(object.mediaKey);
    const mediaIds = readMediaIds(object.mediaIds ?? object.ids);
    if (!reliableMediaKey(mediaIds)) {
        throw new AppError('INVALID_DETAIL_INPUT', 'currentMedia needs a reliable media id', 400);
    }
    return { mediaKey, mediaIds };
}

function normalizeDetailRequest(body: Record<string, unknown>): DetailRequestInput {
    const mediaObject = requireRecord(body.media, 'media');
    rejectLocationAndRawData(mediaObject);
    const candidate = normalizeCandidate(mediaObject, 'media');
    const media: DetailMedia = {
        ...candidate,
        overview: null,
        directors: [],
        cast: [],
        publicRating: null,
    };
    media.overview = optionalText(mediaObject.overview, 'overview', MAX_TEXT);
    media.directors = readStringArray(mediaObject.directors ?? mediaObject.director, 8, 80);
    media.cast = readStringArray(mediaObject.cast ?? mediaObject.actors, 12, 80);
    media.publicRating = optionalNumber(mediaObject.publicRating ?? mediaObject.rating, 0, 10);
    const scene = body.scene;
    if (typeof scene !== 'string' || !SCENES.has(scene)) {
        throw new AppError('INVALID_DETAIL_INPUT', 'scene is invalid', 400);
    }
    const environment = body.environment === undefined || body.environment === null
        ? null
        : normalizeEnvironment(body.environment);
    return {
        media,
        scene,
        environment,
        forceRefresh: body.forceRefresh === true,
    };
}

function normalizeEnvironment(value: unknown): DetailEnvironmentInput {
    const object = requireRecord(value, 'environment');
    rejectLocationAndRawData(object);
    if (typeof object.localDate !== 'string' || !/^\d{4}-\d{2}-\d{2}$/.test(object.localDate)) {
        throw new AppError('INVALID_DETAIL_INPUT', 'localDate is invalid', 400);
    }
    if (typeof object.weekday !== 'number' || !Number.isInteger(object.weekday) || object.weekday < 0 || object.weekday > 6) {
        throw new AppError('INVALID_DETAIL_INPUT', 'weekday is invalid', 400);
    }
    const timeOfDay = readEnumText(object.timeOfDay, 'timeOfDay', 32);
    const season = readEnumText(object.season, 'season', 32);
    const weatherTag = object.weatherTag === undefined || object.weatherTag === null
        ? null
        : readEnumText(object.weatherTag, 'weatherTag', 32);
    return { localDate: object.localDate, weekday: object.weekday, timeOfDay, season, weatherTag };
}

function rejectLocationAndRawData(object: Record<string, unknown>): void {
    const forbidden = ['friendId', 'city', 'latitude', 'longitude', 'location', 'coordinates', 'searchQuery', 'rawEvents', 'spoiler'];
    if (forbidden.some(key => key in object)) {
        throw new AppError('INVALID_DETAIL_INPUT', 'Only structured media and coarse environment data are accepted', 400);
    }
}

function detailMessages(input: DetailRequestInput, profile: Awaited<ReturnType<typeof readProfile>>): MimoMessage[] {
    return [
        {
            role: 'system',
            content: '你是无剧透影视助理。只返回 JSON，字段必须为 interestLevel、confidence、spoilerFreeSummary、publicRatingInterpretation、watchAdvice、reasons、watchTiming。interestLevel/confidence 只能是 HIGH、MEDIUM、LOW。不要生成个人数字评分，不要输出结局、反转、关键关系、生死、秘密、spoiler 或任何敏感属性。只使用输入的结构化事实。',
        },
        {
            role: 'user',
            content: `<MEDIA>${JSON.stringify(input.media)}</MEDIA><SCENE>${input.scene}</SCENE><ENVIRONMENT>${JSON.stringify(input.environment)}</ENVIRONMENT><PROFILE>${JSON.stringify(profile.media.slice(0, 80))}</PROFILE>`,
        },
    ];
}

function recommendationMessages(candidates: Candidate[], profile: Awaited<ReturnType<typeof readProfile>>): MimoMessage[] {
    return [
        {
            role: 'system',
            content: '你是影视候选排序器。只返回 JSON，字段为 orderedMediaKeys 和 reasons。只能使用输入中的 mediaKey，不能创建标题、ID或候选。理由必须无剧透且每条不超过120字。',
        },
        {
            role: 'user',
            content: `<CANDIDATES>${JSON.stringify(candidates)}</CANDIDATES><PROFILE>${JSON.stringify(profile.media.slice(0, 80))}</PROFILE>`,
        },
    ];
}

function applyModelOrder(value: unknown, candidates: Candidate[]): Candidate[] {
    const object = requireRecord(value, 'AI recommendation output');
    if (!Array.isArray(object.orderedMediaKeys)) return candidates;
    const byKey = new Map(candidates.map(candidate => [candidate.mediaKey, candidate]));
    const result: Candidate[] = [];
    for (const key of object.orderedMediaKeys) {
        if (typeof key !== 'string') continue;
        const candidate = byKey.get(key);
        if (candidate && !result.includes(candidate)) result.push(candidate);
    }
    return [...result, ...candidates.filter(candidate => !result.includes(candidate))];
}

function recommendationReason(candidate: Candidate, profile: Awaited<ReturnType<typeof readProfile>>): string {
    const matchingGenre = candidate.genres.find(genre => profile.media.some(item => item.genres.includes(genre)));
    return matchingGenre ? `它与已授权片单中的${matchingGenre}偏好有重合。` : '它来自当前详情页已有的候选池，先按公开类型和媒体信息保留。';
}

function timingLevel(environment: DetailEnvironmentInput | null): 'NOW' | 'SOON' | 'LATER' | 'UNKNOWN' {
    if (!environment) return 'UNKNOWN';
    if (environment.weatherTag && /COLD|SNOW|RAIN/i.test(environment.weatherTag)) return 'NOW';
    if (/EVENING|NIGHT/i.test(environment.timeOfDay)) return 'SOON';
    return 'LATER';
}

function timingBasis(environment: DetailEnvironmentInput | null): string[] {
    if (!environment) return ['暂时没有足够的时间或环境信息'];
    const basis = [`${environment.season}季`, `${environment.timeOfDay}时段`];
    if (environment.weatherTag) basis.push(`天气标签为${environment.weatherTag}`);
    return basis.slice(0, 3);
}

function environmentKey(input: DetailRequestInput): string {
    if (input.scene !== 'WATCHLIST_CONTEXT' || !input.environment) return 'none';
    return [
        input.environment.localDate,
        input.environment.weekday,
        input.environment.timeOfDay,
        input.environment.season,
        input.environment.weatherTag ?? 'none',
    ].join(':');
}

function reliableMediaKey(ids: MediaIds): string | null {
    if (ids.tmdbId !== undefined) return `tmdb:${ids.tmdbId}`;
    if (ids.traktId) return `trakt:${ids.traktId}`;
    if (ids.imdbId) return `imdb:${ids.imdbId.toLowerCase()}`;
    if (ids.doubanId) return `douban:${ids.doubanId}`;
    return null;
}

function readMediaKey(value: unknown): string {
    if (typeof value !== 'string' || !/^(movie|show):[A-Za-z0-9._:-]{1,96}$/.test(value)) {
        throw new AppError('INVALID_DETAIL_INPUT', 'mediaKey is invalid', 400);
    }
    return value;
}

function readMediaIds(value: unknown): MediaIds {
    const object = requireRecord(value, 'mediaIds');
    const tmdb = object.tmdbId ?? object.tmdb;
    const trakt = object.traktId ?? object.trakt;
    const imdb = object.imdbId ?? object.imdb;
    const douban = object.doubanId ?? object.douban;
    return {
        ...(typeof tmdb === 'number' && Number.isInteger(tmdb) && tmdb > 0 ? { tmdbId: tmdb } : {}),
        ...(typeof trakt === 'string' && /^[A-Za-z0-9._:-]{1,96}$/.test(trakt) ? { traktId: trakt } : {}),
        ...(typeof imdb === 'string' && /^tt\d{1,12}$/i.test(imdb) ? { imdbId: imdb } : {}),
        ...(typeof douban === 'string' && /^[A-Za-z0-9._:-]{1,96}$/.test(douban) ? { doubanId: douban } : {}),
    };
}

function readLevel(value: unknown): 'HIGH' | 'MEDIUM' | 'LOW' {
    if (typeof value !== 'string' || !LEVELS.has(value)) throw new AppError('INVALID_AI_OUTPUT', 'AI level is invalid', 502);
    return value as 'HIGH' | 'MEDIUM' | 'LOW';
}

function readReasons(value: unknown): string[] {
    if (!Array.isArray(value) || value.length > MAX_REASON_COUNT) throw new AppError('INVALID_AI_OUTPUT', 'AI reasons are invalid', 502);
    return value.map((item, index) => readText(item, `reason ${index + 1}`, 180));
}

function readText(value: unknown, field: string, maxLength: number): string {
    if (typeof value !== 'string' || !value.trim() || value.length > maxLength) {
        throw new AppError('INVALID_AI_OUTPUT', `AI ${field} is invalid`, 502);
    }
    return value.trim();
}

function readEnumText(value: unknown, field: string, maxLength: number): string {
    if (typeof value !== 'string' || !value.trim() || value.length > maxLength) {
        throw new AppError('INVALID_DETAIL_INPUT', `${field} is invalid`, 400);
    }
    return value.trim();
}

function optionalText(value: unknown, field: string, maxLength: number): string | null {
    if (value === undefined || value === null || value === '') return null;
    if (typeof value !== 'string' || value.length > maxLength) throw new AppError('INVALID_DETAIL_INPUT', `${field} is invalid`, 400);
    return value.trim();
}

function readStringArray(value: unknown, maxItems: number, maxLength: number): string[] {
    if (value === undefined || value === null) return [];
    if (!Array.isArray(value) || value.length > maxItems) throw new AppError('INVALID_DETAIL_INPUT', 'String list is invalid', 400);
    return value.map((item, index) => {
        if (typeof item !== 'string' || !item.trim() || item.length > maxLength) throw new AppError('INVALID_DETAIL_INPUT', `List item ${index + 1} is invalid`, 400);
        return item.trim();
    });
}

function optionalInteger(value: unknown, min: number, max: number): number | null {
    if (value === undefined || value === null) return null;
    if (typeof value !== 'number' || !Number.isInteger(value) || value < min || value > max) throw new AppError('INVALID_DETAIL_INPUT', 'Integer is invalid', 400);
    return value;
}

function optionalNumber(value: unknown, min: number, max: number): number | null {
    if (value === undefined || value === null) return null;
    if (typeof value !== 'number' || !Number.isFinite(value) || value < min || value > max) throw new AppError('INVALID_DETAIL_INPUT', 'Number is invalid', 400);
    return value;
}

function requireRecord(value: unknown, label: string): Record<string, unknown> {
    if (!value || typeof value !== 'object' || Array.isArray(value)) throw new AppError('INVALID_DETAIL_INPUT', `${label} is invalid`, 400);
    return value as Record<string, unknown>;
}

async function readJsonBody(request: Request): Promise<Record<string, unknown>> {
    const declaredLength = Number(request.headers.get('Content-Length') || 0);
    if (declaredLength > MAX_DETAIL_REQUEST_BYTES) throw new AppError('REQUEST_TOO_LARGE', 'Request body is too large', 413);
    const raw = await request.text();
    if (new TextEncoder().encode(raw).byteLength > MAX_DETAIL_REQUEST_BYTES) throw new AppError('REQUEST_TOO_LARGE', 'Request body is too large', 413);
    if (!raw.trim()) throw new AppError('INVALID_JSON', 'Request body must be valid JSON', 400);
    try {
        return requireRecord(JSON.parse(raw), 'request body');
    } catch (error) {
        if (error instanceof AppError) throw error;
        throw new AppError('INVALID_JSON', 'Request body must be valid JSON', 400);
    }
}

function trimText(value: string, maxLength: number): string {
    return value.length <= maxLength ? value : `${value.slice(0, maxLength - 1)}…`;
}
