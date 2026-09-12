import { AppError, successResponse } from '../util/errors.ts';
import {
    PROFILE_CONSENT_VERSION,
    deleteProfileScope,
    getProfileSettings,
    readProfile,
    syncProfile,
    updateProfileSettings,
    type NormalizedBehavior,
    type NormalizedMediaSource,
    type NormalizedProfileMedia,
    type NormalizedProfileSync,
    type ProfileStoreEnvironment,
} from './profile-store.ts';

const MAX_PROFILE_REQUEST_BYTES = 512 * 1024;
const MAX_MEDIA = 200;
const MAX_BEHAVIOR = 365;
const MAX_TEXT_LENGTH = 2000;
const SAFE_ID = /^[A-Za-z0-9._:-]{1,96}$/;
const MEDIA_KEY = /^(movie|show):[A-Za-z0-9._:-]{1,96}$/;
const MEDIA_TYPES = new Set(['movie', 'show']);
const DWELL_BUCKETS = new Set([
    'UNDER_TEN_SECONDS',
    'TEN_TO_THIRTY_SECONDS',
    'THIRTY_TO_ONE_HUNDRED_TWENTY_SECONDS',
    'OVER_ONE_HUNDRED_TWENTY_SECONDS',
]);

export async function handleAiProfileApi(
    request: Request,
    env: ProfileStoreEnvironment,
    requestId: string,
    path: string,
    friendId: string,
): Promise<Response> {
    if (path === '/api/ai/profile/settings' && request.method === 'GET') {
        return successResponse(await getProfileSettings(env, friendId), requestId);
    }
    if (path === '/api/ai/profile/settings' && request.method === 'PUT') {
        const body = await readJsonBody(request);
        return successResponse(
            await updateProfileSettings(env, friendId, readSettings(body)),
            requestId,
        );
    }
    if (path === '/api/ai/profile' && request.method === 'GET') {
        return successResponse(await readProfile(env, friendId), requestId);
    }
    if (path === '/api/ai/profile/sync' && request.method === 'POST') {
        const body = await readJsonBody(request);
        const payload = normalizeSync(body);
        return successResponse(await syncProfile(env, friendId, payload), requestId);
    }
    const deleteMatch = path.match(/^\/api\/ai\/profile\/([^/]+)$/);
    if (deleteMatch && request.method === 'DELETE') {
        const scope = deleteMatch[1];
        if (scope !== 'behavior' && scope !== 'profile' && scope !== 'all') {
            throw new AppError('INVALID_PROFILE_SCOPE', 'Unsupported profile deletion scope', 400);
        }
        return successResponse(await deleteProfileScope(env, friendId, scope), requestId);
    }
    throw new AppError('NOT_FOUND', 'Not found', 404);
}

function readSettings(body: Record<string, unknown>) {
    const consentVersion = body.consentVersion;
    if (typeof consentVersion !== 'string' || consentVersion.length > 16) {
        throw new AppError('INVALID_CONSENT_VERSION', 'Consent version is required', 400);
    }
    return {
        consentVersion,
        profileConsent: optionalBoolean(body.profileConsent),
        behaviorConsent: optionalBoolean(body.behaviorConsent),
        personalizationEnabled: optionalBoolean(body.personalizationEnabled),
        syncEnabled: optionalBoolean(body.syncEnabled),
    };
}

function normalizeSync(body: Record<string, unknown>): NormalizedProfileSync {
    const batchId = readSafeId(body.batchId, 'batchId');
    if (body.schemaVersion !== 1) {
        throw new AppError('INVALID_PROFILE_INPUT', 'Unsupported profile schema version', 400);
    }
    const mediaValue = body.media === undefined ? [] : body.media;
    const behaviorValue = body.behavior === undefined ? [] : body.behavior;
    if (!Array.isArray(mediaValue) || mediaValue.length > MAX_MEDIA) {
        throw new AppError('INVALID_PROFILE_INPUT', 'Media changes are invalid', 400);
    }
    if (!Array.isArray(behaviorValue) || behaviorValue.length > MAX_BEHAVIOR) {
        throw new AppError('INVALID_PROFILE_INPUT', 'Behavior changes are invalid', 400);
    }
    return {
        batchId,
        schemaVersion: 1,
        media: mediaValue.map((item, index) => normalizeMedia(item, index)),
        behavior: behaviorValue.map((item, index) => normalizeBehavior(item, index)),
    };
}

function normalizeMedia(value: unknown, index: number): NormalizedProfileMedia {
    const object = requireRecord(value, `media ${index + 1}`);
    rejectRawInput(object, ['overview', 'latitude', 'longitude', 'city', 'location', 'rawEvents', 'searchQuery']);
    const mediaKey = readMediaKey(object.mediaKey);
    const mediaType = object.mediaType;
    if (typeof mediaType !== 'string' || !MEDIA_TYPES.has(mediaType)) {
        throw new AppError('INVALID_PROFILE_INPUT', 'Media type is invalid', 400);
    }
    const title = readText(object.title, 'title', 200);
    const year = optionalInteger(object.year, 1800, 3000);
    const mediaIds = readMediaIds(object.mediaIds ?? object.ids);
    if (!hasMediaId(mediaIds)) {
        throw new AppError('INVALID_PROFILE_INPUT', 'A reliable media id is required', 400);
    }
    const genres = readStringArray(object.genres, 8, 48);
    const sourcesValue = object.sources;
    let sources: NormalizedMediaSource[];
    if (sourcesValue !== undefined) {
        if (!Array.isArray(sourcesValue) || sourcesValue.length > 8) {
            throw new AppError('INVALID_PROFILE_INPUT', 'Media sources are invalid', 400);
        }
        sources = sourcesValue.map((item, sourceIndex) => normalizeSource(item, sourceIndex));
    } else {
        sources = [normalizeSource(object, 0)];
    }
    return {
        mediaKey,
        mediaType: mediaType as 'movie' | 'show',
        title,
        year,
        posterUrl: optionalUrl(object.posterUrl),
        genres,
        mediaIds,
        sources,
    };
}

function normalizeSource(value: unknown, index: number): NormalizedMediaSource {
    const object = requireRecord(value, `source ${index + 1}`);
    rejectRawInput(object, ['overview', 'latitude', 'longitude', 'city', 'location', 'rawEvents', 'searchQuery']);
    const source = readSafeId(object.source ?? 'local', 'source');
    const sourceMediaId = optionalSafeId(object.sourceMediaId);
    const sourceUpdatedAt = requiredInteger(object.sourceUpdatedAt, 'sourceUpdatedAt', 0, 9_999_999_999);
    const rating = optionalNumber(object.rating, 0, 100);
    const ratingScale = optionalInteger(object.ratingScale, 1, 100);
    if (rating !== null && ratingScale === null) {
        throw new AppError('INVALID_PROFILE_INPUT', 'ratingScale is required with rating', 400);
    }
    const tombstone = optionalTombstone(object.tombstone);
    return {
        source,
        sourceMediaId,
        watchlist: optionalBooleanValueOrDefault(object.watchlist ?? object.isWatchlist, false),
        watched: optionalBooleanValueOrDefault(object.watched ?? object.isWatched, false),
        rating,
        ratingScale,
        comment: optionalText(object.comment ?? object.shortReview, 'comment', 1000),
        watchedAt: optionalDate(object.watchedAt),
        sourceUpdatedAt,
        clientUpdatedAt: optionalInteger(object.clientUpdatedAt, 0, 9_999_999_999),
        tombstone,
    };
}

function normalizeBehavior(value: unknown, index: number): NormalizedBehavior {
    const object = requireRecord(value, `behavior ${index + 1}`);
    rejectRawInput(object, ['latitude', 'longitude', 'city', 'location', 'rawEvents', 'searchQuery', 'query']);
    const mediaKey = readMediaKey(object.mediaKey);
    const eventDay = object.eventDay;
    if (typeof eventDay !== 'string' || !/^\d{4}-\d{2}-\d{2}$/.test(eventDay)) {
        throw new AppError('INVALID_PROFILE_INPUT', 'eventDay is invalid', 400);
    }
    const dwell = object.detailDwellBucket;
    if (dwell !== undefined && dwell !== null && (typeof dwell !== 'string' || !DWELL_BUCKETS.has(dwell))) {
        throw new AppError('INVALID_PROFILE_INPUT', 'detailDwellBucket is invalid', 400);
    }
    const lastEventAt = optionalInteger(object.lastEventAt, 0, 9_999_999_999);
    return {
        mediaKey,
        eventDay,
        detailDwellBucket: dwell === null || dwell === undefined ? null : dwell,
        searchClickCount: boundedCount(object.searchClickCount, 'searchClickCount'),
        playerProgressBuckets: readProgressBuckets(object.playerProgressBuckets),
        episodeStartedCount: boundedCount(object.episodeStartedCount, 'episodeStartedCount'),
        episodeCompletedCount: boundedCount(object.episodeCompletedCount, 'episodeCompletedCount'),
        lastEventAt: lastEventAt === 0 ? null : lastEventAt,
    };
}

function readMediaIds(value: unknown) {
    const object = requireRecord(value, 'mediaIds');
    const tmdbValue = object.tmdbId ?? object.tmdb;
    const tmdbId = typeof tmdbValue === 'number' && Number.isInteger(tmdbValue) && tmdbValue > 0
        ? tmdbValue
        : typeof tmdbValue === 'string' && /^\d{1,12}$/.test(tmdbValue)
            ? Number(tmdbValue)
            : undefined;
    const traktValue = object.traktId ?? object.trakt;
    const imdbValue = object.imdbId ?? object.imdb;
    const doubanValue = object.doubanId ?? object.douban;
    const ids = {
        ...(tmdbId !== undefined ? { tmdbId } : {}),
        ...(typeof traktValue === 'string' && SAFE_ID.test(traktValue) ? { traktId: traktValue } : {}),
        ...(typeof imdbValue === 'string' && /^tt\d{1,12}$/i.test(imdbValue) ? { imdbId: imdbValue } : {}),
        ...(typeof doubanValue === 'string' && SAFE_ID.test(doubanValue) ? { doubanId: doubanValue } : {}),
    };
    return ids;
}

function hasMediaId(ids: Record<string, unknown>): boolean {
    return Object.keys(ids).length > 0;
}

function readProgressBuckets(value: unknown): Record<string, number> {
    if (value === undefined) return {};
    const object = requireRecord(value, 'playerProgressBuckets');
    const allowed = new Set(['25', '50', '75', 'completed']);
    const entries = Object.entries(object);
    if (entries.length > 4 || entries.some(([key]) => !allowed.has(key))) {
        throw new AppError('INVALID_PROFILE_INPUT', 'playerProgressBuckets is invalid', 400);
    }
    return Object.fromEntries(entries
        .map(([key, item]) => [key, boundedCount(item, `progress ${key}`)] as const)
        .filter(([, count]) => count > 0));
}

function readStringArray(value: unknown, maxItems: number, maxLength: number): string[] {
    if (value === undefined) return [];
    if (!Array.isArray(value) || value.length > maxItems) {
        throw new AppError('INVALID_PROFILE_INPUT', 'String list is invalid', 400);
    }
    return value.map((item, index) => readText(item, `list item ${index + 1}`, maxLength));
}

function optionalTombstone(value: unknown): Record<string, unknown> | null {
    if (value === undefined || value === null) return null;
    const object = requireRecord(value, 'tombstone');
    if (Object.keys(object).length > 8) throw new AppError('INVALID_PROFILE_INPUT', 'tombstone is too large', 400);
    for (const [key, item] of Object.entries(object)) {
        if (!['fields', 'deletedAt', 'reason'].includes(key)) {
            throw new AppError('INVALID_PROFILE_INPUT', 'tombstone contains unsupported data', 400);
        }
        if (typeof item === 'string' && item.length > 100) {
            throw new AppError('INVALID_PROFILE_INPUT', 'tombstone text is too long', 400);
        }
    }
    return object;
}

function rejectRawInput(object: Record<string, unknown>, fields: string[]): void {
    if (fields.some(field => field in object)) {
        throw new AppError('INVALID_PROFILE_INPUT', 'Raw behavior or location data is not accepted', 400);
    }
}

function readMediaKey(value: unknown): string {
    if (typeof value !== 'string' || !MEDIA_KEY.test(value)) {
        throw new AppError('INVALID_PROFILE_INPUT', 'mediaKey is invalid', 400);
    }
    return value;
}

function readSafeId(value: unknown, field: string): string {
    if (typeof value !== 'string' || !SAFE_ID.test(value)) {
        throw new AppError('INVALID_PROFILE_INPUT', `${field} is invalid`, 400);
    }
    return value;
}

function optionalSafeId(value: unknown): string | null {
    if (value === undefined || value === null || value === '') return null;
    return readSafeId(value, 'sourceMediaId');
}

function readText(value: unknown, field: string, maxLength: number): string {
    if (typeof value !== 'string') throw new AppError('INVALID_PROFILE_INPUT', `${field} is invalid`, 400);
    const text = value.trim();
    if (!text || text.length > maxLength) throw new AppError('INVALID_PROFILE_INPUT', `${field} is invalid`, 400);
    return text;
}

function optionalText(value: unknown, field: string, maxLength: number): string | null {
    if (value === undefined || value === null || value === '') return null;
    return readText(value, field, maxLength);
}

function optionalUrl(value: unknown): string | null {
    if (value === undefined || value === null || value === '') return null;
    if (typeof value !== 'string' || value.length > 2048) {
        throw new AppError('INVALID_PROFILE_INPUT', 'posterUrl is invalid', 400);
    }
    try {
        const url = new URL(value);
        if (url.protocol !== 'http:' && url.protocol !== 'https:') throw new Error('protocol');
        return value;
    } catch {
        throw new AppError('INVALID_PROFILE_INPUT', 'posterUrl is invalid', 400);
    }
}

function optionalDate(value: unknown): string | null {
    if (value === undefined || value === null || value === '') return null;
    if (typeof value !== 'string' || value.length > 40) {
        throw new AppError('INVALID_PROFILE_INPUT', 'watchedAt is invalid', 400);
    }
    return value;
}

function boundedCount(value: unknown, field: string): number {
    if (value === undefined) return 0;
    return requiredInteger(value, field, 0, 10000);
}

function requiredInteger(value: unknown, field: string, min: number, max: number): number {
    if (typeof value !== 'number' || !Number.isInteger(value) || value < min || value > max) {
        throw new AppError('INVALID_PROFILE_INPUT', `${field} is invalid`, 400);
    }
    return value;
}

function optionalInteger(value: unknown, min: number, max: number): number | null {
    if (value === undefined || value === null) return null;
    return requiredInteger(value, 'integer', min, max);
}

function optionalNumber(value: unknown, min: number, max: number): number | null {
    if (value === undefined || value === null) return null;
    if (typeof value !== 'number' || !Number.isFinite(value) || value < min || value > max) {
        throw new AppError('INVALID_PROFILE_INPUT', 'number is invalid', 400);
    }
    return value;
}

function optionalBoolean(value: unknown): boolean | undefined {
    if (value === undefined) return undefined;
    return optionalBooleanValue(value);
}

function optionalBooleanValue(value: unknown): boolean {
    if (typeof value !== 'boolean') throw new AppError('INVALID_PROFILE_INPUT', 'Boolean field is invalid', 400);
    return value;
}

function optionalBooleanValueOrDefault(value: unknown, fallback: boolean): boolean {
    return value === undefined || value === null ? fallback : optionalBooleanValue(value);
}

async function readJsonBody(request: Request): Promise<Record<string, unknown>> {
    const declaredLength = Number(request.headers.get('Content-Length') || 0);
    if (declaredLength > MAX_PROFILE_REQUEST_BYTES) {
        throw new AppError('REQUEST_TOO_LARGE', 'Request body is too large', 413);
    }
    const raw = await request.text();
    if (new TextEncoder().encode(raw).byteLength > MAX_PROFILE_REQUEST_BYTES) {
        throw new AppError('REQUEST_TOO_LARGE', 'Request body is too large', 413);
    }
    if (!raw.trim()) throw new AppError('INVALID_JSON', 'Request body must be valid JSON', 400);
    try {
        return requireRecord(JSON.parse(raw), 'request body');
    } catch (error) {
        if (error instanceof AppError) throw error;
        throw new AppError('INVALID_JSON', 'Request body must be valid JSON', 400);
    }
}

function requireRecord(value: unknown, label: string): Record<string, unknown> {
    if (!value || typeof value !== 'object' || Array.isArray(value)) {
        throw new AppError('INVALID_PROFILE_INPUT', `${label} is invalid`, 400);
    }
    return value as Record<string, unknown>;
}
