import { AppError, now } from '../util/errors.ts';
import { cacheKeyDigest, isTestStore, type AiStoreEnvironment } from './store.ts';

export const PROFILE_CONSENT_VERSION = '1';

export type ProfileStatus = 'PENDING' | 'READY' | 'FAILED';

export interface ProfileStoreEnvironment extends AiStoreEnvironment {
    AI_TEST_PROFILE?: Map<string, unknown>;
}

export interface ProfileSettings {
    profileConsent: boolean;
    behaviorConsent: boolean;
    personalizationEnabled: boolean;
    syncEnabled: boolean;
    consentVersion: string;
    profileVersion: number;
    profileStatus: ProfileStatus;
}

export interface NormalizedMediaSource {
    source: string;
    sourceMediaId: string | null;
    watchlist: boolean;
    watched: boolean;
    rating: number | null;
    ratingScale: number | null;
    comment: string | null;
    watchedAt: string | null;
    sourceUpdatedAt: number;
    clientUpdatedAt: number | null;
    tombstone: Record<string, unknown> | null;
}

export interface NormalizedProfileMedia {
    mediaKey: string;
    mediaType: 'movie' | 'show';
    title: string;
    year: number | null;
    posterUrl: string | null;
    genres: string[];
    mediaIds: MediaIds;
    sources: NormalizedMediaSource[];
}

export interface NormalizedBehavior {
    mediaKey: string;
    eventDay: string;
    detailDwellBucket: string | null;
    searchClickCount: number;
    playerProgressBuckets: Record<string, number>;
    episodeStartedCount: number;
    episodeCompletedCount: number;
    lastEventAt: number | null;
}

export interface NormalizedProfileSync {
    batchId: string;
    schemaVersion: 1;
    media: NormalizedProfileMedia[];
    behavior: NormalizedBehavior[];
}

export interface ProfileMediaView {
    mediaKey: string;
    mediaType: 'movie' | 'show';
    title: string;
    year: number | null;
    posterUrl: string | null;
    genres: string[];
    mediaIds: MediaIds;
    watchlist: boolean;
    watched: boolean;
    rating: number | null;
    commentPresent: boolean;
}

export interface ProfileBehaviorView {
    mediaKey: string;
    eventDay: string;
    detailDwellBucket: string | null;
    searchClickCount: number;
    playerProgressBuckets: Record<string, number>;
    episodeStartedCount: number;
    episodeCompletedCount: number;
    lastEventAt: number | null;
}

export interface ProfileView {
    settings: ProfileSettings;
    media: ProfileMediaView[];
    behavior: ProfileBehaviorView[];
}

export interface MediaIds {
    tmdbId?: number;
    traktId?: string;
    imdbId?: string;
    doubanId?: string;
}

interface SettingsRow {
    profile_consent: number;
    behavior_consent: number;
    personalization_enabled: number;
    sync_enabled: number;
    consent_version: string;
}

interface SnapshotRow {
    profile_version: number;
    status: ProfileStatus;
}

interface MediaRow {
    __friend_id?: string;
    media_key: string;
    media_type: 'movie' | 'show';
    tmdb_id: number | null;
    imdb_id: string | null;
    trakt_id: string | null;
    douban_id: string | null;
    title: string;
    year: number | null;
    poster_url: string | null;
    genres_json: string;
    effective_watchlist: number;
    effective_watched: number;
    effective_rating: number | null;
    effective_comment_present: number;
}

interface BehaviorRow {
    __friend_id?: string;
    media_key: string;
    event_day: string;
    detail_dwell_bucket: string | null;
    search_click_count: number;
    player_progress_buckets_json: string;
    episode_started_count: number;
    episode_completed_count: number;
    last_event_at: number | null;
}

interface StoredSource {
    friendId: string;
    mediaKey: string;
    source: NormalizedMediaSource;
}

interface StoredBatch {
    digest: string;
    result: SyncResult;
}

interface MemoryState {
    settings: Map<string, SettingsRow>;
    media: Map<string, MediaRow>;
    sources: Map<string, StoredSource>;
    behavior: Map<string, BehaviorRow>;
    snapshots: Map<string, SnapshotRow>;
    batches: Map<string, StoredBatch>;
}

export interface SyncResult {
    accepted: true;
    batchId: string;
    profileVersion: number;
    profileStatus: ProfileStatus;
    mediaAccepted: number;
    behaviorAccepted: number;
}

const testStateRoots = new WeakMap<object, Map<string, unknown>>();

export async function getProfileSettings(
    env: ProfileStoreEnvironment,
    friendId: string,
): Promise<ProfileSettings> {
    const snapshot = await readSnapshot(env, friendId);
    if (isTestStore(env)) {
        const row = getState(env).settings.get(friendId);
        return toPublicSettings(row ?? defaultSettingsRow(), snapshot);
    }

    const row = await first<SettingsRow>(
        env,
        `SELECT profile_consent, behavior_consent, personalization_enabled,
                sync_enabled, consent_version
         FROM ai_profile_settings
         WHERE friend_id = ? LIMIT 1`,
        friendId,
    );
    return toPublicSettings(row ?? defaultSettingsRow(), snapshot);
}

export async function updateProfileSettings(
    env: ProfileStoreEnvironment,
    friendId: string,
    input: {
        profileConsent?: boolean;
        behaviorConsent?: boolean;
        personalizationEnabled?: boolean;
        syncEnabled?: boolean;
        consentVersion: string;
    },
): Promise<ProfileSettings> {
    if (input.consentVersion !== PROFILE_CONSENT_VERSION) {
        throw new AppError('INVALID_CONSENT_VERSION', 'Unsupported consent version', 400);
    }
    const current = await getProfileSettings(env, friendId);
    const next: SettingsRow = {
        profile_consent: boolNumber(input.profileConsent ?? current.profileConsent),
        behavior_consent: boolNumber(input.behaviorConsent ?? current.behaviorConsent),
        personalization_enabled: boolNumber(
            input.personalizationEnabled ?? current.personalizationEnabled,
        ),
        sync_enabled: boolNumber(input.syncEnabled ?? current.syncEnabled),
        consent_version: input.consentVersion,
    };
    if (!next.profile_consent) next.personalization_enabled = 0;

    if (isTestStore(env)) {
        getState(env).settings.set(friendId, next);
        return getProfileSettings(env, friendId);
    }

    await run(
        env,
        `INSERT INTO ai_profile_settings (
            friend_id, profile_consent, behavior_consent, personalization_enabled,
            sync_enabled, consent_version, created_at, updated_at
        ) VALUES (?, ?, ?, ?, ?, ?, ?, ?)
        ON CONFLICT(friend_id) DO UPDATE SET
            profile_consent = excluded.profile_consent,
            behavior_consent = excluded.behavior_consent,
            personalization_enabled = excluded.personalization_enabled,
            sync_enabled = excluded.sync_enabled,
            consent_version = excluded.consent_version,
            updated_at = excluded.updated_at`,
        friendId,
        next.profile_consent,
        next.behavior_consent,
        next.personalization_enabled,
        next.sync_enabled,
        next.consent_version,
        now(),
        now(),
    );
    return getProfileSettings(env, friendId);
}

export async function readProfile(
    env: ProfileStoreEnvironment,
    friendId: string,
): Promise<ProfileView> {
    const settings = await getProfileSettings(env, friendId);
    if (isTestStore(env)) {
        const state = getState(env);
        const media = [...state.media.values()]
            .filter(row => row.__friend_id === friendId)
            .map(toMediaView);
        const behavior = [...state.behavior.values()]
            .filter(row => row.__friend_id === friendId)
            .map(toBehaviorView);
        return { settings, media, behavior };
    }

    const mediaRows = await all<MediaRow>(
        env,
        `SELECT media_key, media_type, tmdb_id, imdb_id, trakt_id, douban_id,
                title, year, poster_url, genres_json, effective_watchlist,
                effective_watched, effective_rating, effective_comment_present
         FROM ai_profile_media
         WHERE friend_id = ?
         ORDER BY updated_at DESC LIMIT 200`,
        friendId,
    );
    const behaviorRows = await all<BehaviorRow>(
        env,
        `SELECT media_key, event_day, detail_dwell_bucket, search_click_count,
                player_progress_buckets_json, episode_started_count,
                episode_completed_count, last_event_at
         FROM ai_profile_behavior_daily
         WHERE friend_id = ?
         ORDER BY event_day DESC LIMIT 365`,
        friendId,
    );
    return {
        settings,
        media: mediaRows.map(toMediaView),
        behavior: behaviorRows.map(toBehaviorView),
    };
}

export async function syncProfile(
    env: ProfileStoreEnvironment,
    friendId: string,
    payload: NormalizedProfileSync,
): Promise<SyncResult> {
    const settings = await getProfileSettings(env, friendId);
    if (payload.media.length > 0 && !settings.profileConsent) {
        throw new AppError('PROFILE_CONSENT_REQUIRED', 'Profile consent is required', 403);
    }
    if (payload.behavior.length > 0 && !settings.behaviorConsent) {
        throw new AppError('BEHAVIOR_CONSENT_REQUIRED', 'Behavior consent is required', 403);
    }

    const digest = await cacheKeyDigest(JSON.stringify(payload));
    if (isTestStore(env)) return syncInMemory(env, friendId, payload, digest);

    const existing = await first<{ payload_digest: string; result_json: string }>(
        env,
        `SELECT payload_digest, result_json
         FROM ai_profile_sync_batches
         WHERE friend_id = ? AND batch_id = ? LIMIT 1`,
        friendId,
        payload.batchId,
    );
    if (existing) {
        if (existing.payload_digest !== digest) {
            throw new AppError('BATCH_ID_CONFLICT', 'Batch id was already used with another payload', 409);
        }
        return parseSyncResult(existing.result_json);
    }

    const currentSnapshot = await readSnapshot(env, friendId);
    const nextVersion = currentSnapshot.profile_version + 1;
    const statements: D1PreparedStatement[] = [];
    for (const media of payload.media) {
        statements.push(mediaUpsert(env, friendId, media));
        for (const source of media.sources) {
            statements.push(sourceUpsert(env, friendId, media.mediaKey, source));
        }
        statements.push(mediaEffectiveUpdate(env, friendId, media.mediaKey));
    }
    for (const event of payload.behavior) statements.push(behaviorUpsert(env, friendId, event));
    statements.push(
        env.DB!.prepare(`
            INSERT INTO ai_profile_snapshot (
                friend_id, profile_version, status, profile_json,
                source_watermark, generated_at, updated_at
            ) VALUES (?, ?, 'PENDING', NULL, ?, NULL, ?)
            ON CONFLICT(friend_id) DO UPDATE SET
                profile_version = excluded.profile_version,
                status = 'PENDING',
                profile_json = NULL,
                source_watermark = excluded.source_watermark,
                generated_at = NULL,
                updated_at = excluded.updated_at
        `).bind(friendId, nextVersion, payload.batchId, now()),
    );
    const result: SyncResult = {
        accepted: true,
        batchId: payload.batchId,
        profileVersion: nextVersion,
        profileStatus: 'PENDING',
        mediaAccepted: payload.media.length,
        behaviorAccepted: payload.behavior.length,
    };
    statements.push(
        env.DB!.prepare(`
            INSERT INTO ai_profile_sync_batches (
                friend_id, batch_id, schema_version, payload_digest, status,
                result_json, created_at, processed_at
            ) VALUES (?, ?, ?, ?, 'ACCEPTED', ?, ?, ?)
        `).bind(
            friendId,
            payload.batchId,
            payload.schemaVersion,
            digest,
            JSON.stringify(result),
            now(),
            now(),
        ),
    );
    await batch(env, statements);
    return result;
}

export async function deleteProfileScope(
    env: ProfileStoreEnvironment,
    friendId: string,
    scope: 'behavior' | 'profile' | 'all',
): Promise<{ deleted: true; scope: 'behavior' | 'profile' | 'all' }> {
    if (!['behavior', 'profile', 'all'].includes(scope)) {
        throw new AppError('INVALID_PROFILE_SCOPE', 'Unsupported profile deletion scope', 400);
    }
    if (isTestStore(env)) {
        const state = getState(env);
        if (scope === 'behavior' || scope === 'all') {
            for (const key of state.behavior.keys()) if (key.startsWith(`${friendId}:`)) state.behavior.delete(key);
        }
        if (scope === 'profile' || scope === 'all') {
            for (const key of state.media.keys()) if (key.startsWith(`${friendId}:`)) state.media.delete(key);
            for (const key of state.sources.keys()) if (key.startsWith(`${friendId}:`)) state.sources.delete(key);
            for (const key of state.batches.keys()) if (key.startsWith(`${friendId}:`)) state.batches.delete(key);
            state.snapshots.delete(friendId);
        }
        if (scope === 'all') {
            state.settings.delete(friendId);
            const cache = env.AI_TEST_CACHE;
            if (cache instanceof Map) {
                for (const key of cache.keys()) if (key.includes(`:${friendId}:`)) cache.delete(key);
            }
        }
        return { deleted: true, scope };
    }

    const statements: D1PreparedStatement[] = [];
    if (scope === 'behavior' || scope === 'all') {
        statements.push(env.DB!.prepare(
            'DELETE FROM ai_profile_behavior_daily WHERE friend_id = ?',
        ).bind(friendId));
    }
    if (scope === 'profile' || scope === 'all') {
        statements.push(env.DB!.prepare('DELETE FROM ai_profile_media_sources WHERE friend_id = ?').bind(friendId));
        statements.push(env.DB!.prepare('DELETE FROM ai_profile_media WHERE friend_id = ?').bind(friendId));
        statements.push(env.DB!.prepare('DELETE FROM ai_profile_feedback WHERE friend_id = ?').bind(friendId));
        statements.push(env.DB!.prepare('DELETE FROM ai_profile_sync_batches WHERE friend_id = ?').bind(friendId));
        statements.push(env.DB!.prepare('DELETE FROM ai_profile_snapshot WHERE friend_id = ?').bind(friendId));
        statements.push(env.DB!.prepare(
            `DELETE FROM ai_cache WHERE friend_id = ?
             AND kind IN ('detail', 'recommendations', 'profile')`,
        ).bind(friendId));
    }
    if (scope === 'all') {
        statements.push(env.DB!.prepare('DELETE FROM ai_cache WHERE friend_id = ?').bind(friendId));
        statements.push(env.DB!.prepare('DELETE FROM ai_profile_settings WHERE friend_id = ?').bind(friendId));
    }
    await batch(env, statements);
    return { deleted: true, scope };
}

function syncInMemory(
    env: ProfileStoreEnvironment,
    friendId: string,
    payload: NormalizedProfileSync,
    digest: string,
): SyncResult {
    const state = getState(env);
    const batchKey = `${friendId}:${payload.batchId}`;
    const existing = state.batches.get(batchKey);
    if (existing) {
        if (existing.digest !== digest) {
            throw new AppError('BATCH_ID_CONFLICT', 'Batch id was already used with another payload', 409);
        }
        return existing.result;
    }
    for (const media of payload.media) {
        const key = `${friendId}:${media.mediaKey}`;
        const previous = state.media.get(key);
        state.media.set(key, {
            __friend_id: friendId,
            media_key: media.mediaKey,
            media_type: media.mediaType,
            tmdb_id: media.mediaIds.tmdbId ?? null,
            imdb_id: media.mediaIds.imdbId ?? null,
            trakt_id: media.mediaIds.traktId ?? null,
            douban_id: media.mediaIds.doubanId ?? null,
            title: media.title,
            year: media.year,
            poster_url: media.posterUrl,
            genres_json: JSON.stringify(media.genres),
            effective_watchlist: previous?.effective_watchlist ?? 0,
            effective_watched: previous?.effective_watched ?? 0,
            effective_rating: previous?.effective_rating ?? null,
            effective_comment_present: previous?.effective_comment_present ?? 0,
        });
        for (const source of media.sources) {
            const sourceKey = `${friendId}:${media.mediaKey}:${source.source}`;
            const old = state.sources.get(sourceKey)?.source;
            if (!old || source.sourceUpdatedAt >= old.sourceUpdatedAt) {
                state.sources.set(sourceKey, { friendId, mediaKey: media.mediaKey, source });
            }
        }
        recomputeMemoryMedia(state, friendId, media.mediaKey);
    }
    for (const event of payload.behavior) {
        const key = `${friendId}:${event.mediaKey}:${event.eventDay}`;
        const old = state.behavior.get(key);
        state.behavior.set(key, {
            __friend_id: friendId,
            media_key: event.mediaKey,
            event_day: event.eventDay,
            detail_dwell_bucket: maxDwellBucket(old?.detail_dwell_bucket, event.detailDwellBucket),
            search_click_count: maxSnapshotCount(old?.search_click_count, event.searchClickCount),
            player_progress_buckets_json: JSON.stringify(
                mergeProgressBuckets(parseJsonObject(old?.player_progress_buckets_json), event.playerProgressBuckets),
            ),
            episode_started_count: maxSnapshotCount(old?.episode_started_count, event.episodeStartedCount),
            episode_completed_count: maxSnapshotCount(old?.episode_completed_count, event.episodeCompletedCount),
            last_event_at: Math.max(old?.last_event_at ?? 0, event.lastEventAt ?? 0) || null,
        });
    }
    const previousVersion = state.snapshots.get(friendId)?.profile_version ?? 0;
    const snapshot = { profile_version: previousVersion + 1, status: 'PENDING' as ProfileStatus };
    state.snapshots.set(friendId, snapshot);
    const result: SyncResult = {
        accepted: true,
        batchId: payload.batchId,
        profileVersion: snapshot.profile_version,
        profileStatus: snapshot.status,
        mediaAccepted: payload.media.length,
        behaviorAccepted: payload.behavior.length,
    };
    state.batches.set(batchKey, { digest, result });
    return result;
}

function recomputeMemoryMedia(state: MemoryState, friendId: string, mediaKey: string): void {
    const media = state.media.get(`${friendId}:${mediaKey}`);
    if (!media) return;
    const sources = [...state.sources.values()]
        .filter(item => item.mediaKey === mediaKey && item.friendId === friendId)
        .map(item => item.source)
        .filter(source => !source.tombstone);
    const latestRating = sources
        .filter(source => source.rating !== null)
        .sort((left, right) => right.sourceUpdatedAt - left.sourceUpdatedAt)[0]?.rating ?? null;
    media.effective_watchlist = sources.some(source => source.watchlist) ? 1 : 0;
    media.effective_watched = sources.some(source => source.watched) ? 1 : 0;
    media.effective_rating = latestRating;
    media.effective_comment_present = sources.some(source => Boolean(source.comment?.trim())) ? 1 : 0;
}

function toPublicSettings(row: SettingsRow, snapshot: SnapshotRow): ProfileSettings {
    return {
        profileConsent: row.profile_consent === 1,
        behaviorConsent: row.behavior_consent === 1,
        personalizationEnabled: row.personalization_enabled === 1,
        syncEnabled: row.sync_enabled === 1,
        consentVersion: row.consent_version,
        profileVersion: snapshot.profile_version,
        profileStatus: snapshot.status,
    };
}

function defaultSettingsRow(): SettingsRow {
    return {
        profile_consent: 0,
        behavior_consent: 0,
        personalization_enabled: 0,
        sync_enabled: 1,
        consent_version: PROFILE_CONSENT_VERSION,
    };
}

async function readSnapshot(env: ProfileStoreEnvironment, friendId: string): Promise<SnapshotRow> {
    if (isTestStore(env)) {
        return getState(env).snapshots.get(friendId) ?? { profile_version: 0, status: 'READY' };
    }
    return (await first<SnapshotRow>(
        env,
        `SELECT profile_version, status FROM ai_profile_snapshot
         WHERE friend_id = ? LIMIT 1`,
        friendId,
    )) ?? { profile_version: 0, status: 'READY' };
}

function toMediaView(row: MediaRow): ProfileMediaView {
    return {
        mediaKey: row.media_key,
        mediaType: row.media_type,
        title: row.title,
        year: row.year,
        posterUrl: row.poster_url,
        genres: parseStringArray(row.genres_json),
        mediaIds: {
            ...(row.tmdb_id !== null ? { tmdbId: row.tmdb_id } : {}),
            ...(row.trakt_id !== null ? { traktId: row.trakt_id } : {}),
            ...(row.imdb_id !== null ? { imdbId: row.imdb_id } : {}),
            ...(row.douban_id !== null ? { doubanId: row.douban_id } : {}),
        },
        watchlist: row.effective_watchlist === 1,
        watched: row.effective_watched === 1,
        rating: row.effective_rating,
        commentPresent: row.effective_comment_present === 1,
    };
}

function toBehaviorView(row: BehaviorRow): ProfileBehaviorView {
    return {
        mediaKey: row.media_key,
        eventDay: row.event_day,
        detailDwellBucket: row.detail_dwell_bucket,
        searchClickCount: row.search_click_count,
        playerProgressBuckets: parseJsonObject(row.player_progress_buckets_json),
        episodeStartedCount: row.episode_started_count,
        episodeCompletedCount: row.episode_completed_count,
        lastEventAt: row.last_event_at,
    };
}

function mediaUpsert(
    env: ProfileStoreEnvironment,
    friendId: string,
    media: NormalizedProfileMedia,
): D1PreparedStatement {
    return env.DB!.prepare(`
        INSERT INTO ai_profile_media (
            friend_id, media_key, media_type, tmdb_id, imdb_id, trakt_id, douban_id,
            title, year, poster_url, genres_json, updated_at
        ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
        ON CONFLICT(friend_id, media_key) DO UPDATE SET
            media_type = excluded.media_type,
            tmdb_id = excluded.tmdb_id,
            imdb_id = excluded.imdb_id,
            trakt_id = excluded.trakt_id,
            douban_id = excluded.douban_id,
            title = excluded.title,
            year = excluded.year,
            poster_url = excluded.poster_url,
            genres_json = excluded.genres_json,
            updated_at = excluded.updated_at
    `).bind(
        friendId,
        media.mediaKey,
        media.mediaType,
        media.mediaIds.tmdbId ?? null,
        media.mediaIds.imdbId ?? null,
        media.mediaIds.traktId ?? null,
        media.mediaIds.doubanId ?? null,
        media.title,
        media.year,
        media.posterUrl,
        JSON.stringify(media.genres),
        now(),
    );
}

function sourceUpsert(
    env: ProfileStoreEnvironment,
    friendId: string,
    mediaKey: string,
    source: NormalizedMediaSource,
): D1PreparedStatement {
    return env.DB!.prepare(`
        INSERT INTO ai_profile_media_sources (
            friend_id, media_key, source, source_media_id, watchlist, watched,
            rating, rating_scale, comment_text, watched_at, source_updated_at,
            client_updated_at, tombstone_json, updated_at
        ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
        ON CONFLICT(friend_id, media_key, source) DO UPDATE SET
            source_media_id = excluded.source_media_id,
            watchlist = excluded.watchlist,
            watched = excluded.watched,
            rating = excluded.rating,
            rating_scale = excluded.rating_scale,
            comment_text = excluded.comment_text,
            watched_at = excluded.watched_at,
            source_updated_at = excluded.source_updated_at,
            client_updated_at = excluded.client_updated_at,
            tombstone_json = excluded.tombstone_json,
            updated_at = excluded.updated_at
        WHERE excluded.source_updated_at >= ai_profile_media_sources.source_updated_at
    `).bind(
        friendId,
        mediaKey,
        source.source,
        source.sourceMediaId,
        boolNumber(source.watchlist),
        boolNumber(source.watched),
        source.rating,
        source.ratingScale,
        source.comment,
        source.watchedAt,
        source.sourceUpdatedAt,
        source.clientUpdatedAt,
        source.tombstone ? JSON.stringify(source.tombstone) : null,
        now(),
    );
}

function mediaEffectiveUpdate(
    env: ProfileStoreEnvironment,
    friendId: string,
    mediaKey: string,
): D1PreparedStatement {
    return env.DB!.prepare(`
        UPDATE ai_profile_media
        SET effective_watchlist = CASE WHEN EXISTS (
                SELECT 1 FROM ai_profile_media_sources
                WHERE friend_id = ? AND media_key = ?
                  AND tombstone_json IS NULL AND watchlist = 1
            ) THEN 1 ELSE 0 END,
            effective_watched = CASE WHEN EXISTS (
                SELECT 1 FROM ai_profile_media_sources
                WHERE friend_id = ? AND media_key = ?
                  AND tombstone_json IS NULL AND watched = 1
            ) THEN 1 ELSE 0 END,
            effective_rating = (
                SELECT rating FROM ai_profile_media_sources
                WHERE friend_id = ? AND media_key = ?
                  AND tombstone_json IS NULL AND rating IS NOT NULL
                ORDER BY source_updated_at DESC LIMIT 1
            ),
            effective_comment_present = CASE WHEN EXISTS (
                SELECT 1 FROM ai_profile_media_sources
                WHERE friend_id = ? AND media_key = ?
                  AND tombstone_json IS NULL AND comment_text IS NOT NULL
                  AND length(trim(comment_text)) > 0
            ) THEN 1 ELSE 0 END,
            updated_at = ?
        WHERE friend_id = ? AND media_key = ?
    `).bind(
        friendId, mediaKey,
        friendId, mediaKey,
        friendId, mediaKey,
        friendId, mediaKey,
        now(), friendId, mediaKey,
    );
}

function behaviorUpsert(
    env: ProfileStoreEnvironment,
    friendId: string,
    event: NormalizedBehavior,
): D1PreparedStatement {
    return env.DB!.prepare(`
        INSERT INTO ai_profile_behavior_daily (
            friend_id, media_key, event_day, detail_dwell_bucket,
            search_click_count, player_progress_buckets_json,
            episode_started_count, episode_completed_count, last_event_at, updated_at
        ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
        ON CONFLICT(friend_id, media_key, event_day) DO UPDATE SET
            detail_dwell_bucket = CASE
                WHEN excluded.detail_dwell_bucket = 'OVER_ONE_HUNDRED_TWENTY_SECONDS'
                    OR ai_profile_behavior_daily.detail_dwell_bucket IS NULL
                    THEN excluded.detail_dwell_bucket
                WHEN excluded.detail_dwell_bucket = 'THIRTY_TO_ONE_HUNDRED_TWENTY_SECONDS'
                    AND ai_profile_behavior_daily.detail_dwell_bucket IN ('UNDER_TEN_SECONDS', 'TEN_TO_THIRTY_SECONDS')
                    THEN excluded.detail_dwell_bucket
                WHEN excluded.detail_dwell_bucket = 'TEN_TO_THIRTY_SECONDS'
                    AND ai_profile_behavior_daily.detail_dwell_bucket = 'UNDER_TEN_SECONDS'
                    THEN excluded.detail_dwell_bucket
                ELSE ai_profile_behavior_daily.detail_dwell_bucket
            END,
            search_click_count = MAX(ai_profile_behavior_daily.search_click_count, excluded.search_click_count),
            player_progress_buckets_json = json_patch(
                json_patch(
                    json_patch(
                        json_patch(
                            '{}',
                            CASE WHEN MAX(
                                COALESCE(json_extract(ai_profile_behavior_daily.player_progress_buckets_json, '$."25"'), 0),
                                COALESCE(json_extract(excluded.player_progress_buckets_json, '$."25"'), 0)
                            ) > 0 THEN json_object('25', MAX(
                                COALESCE(json_extract(ai_profile_behavior_daily.player_progress_buckets_json, '$."25"'), 0),
                                COALESCE(json_extract(excluded.player_progress_buckets_json, '$."25"'), 0)
                            )) ELSE '{}' END
                        ),
                        CASE WHEN MAX(
                            COALESCE(json_extract(ai_profile_behavior_daily.player_progress_buckets_json, '$."50"'), 0),
                            COALESCE(json_extract(excluded.player_progress_buckets_json, '$."50"'), 0)
                        ) > 0 THEN json_object('50', MAX(
                            COALESCE(json_extract(ai_profile_behavior_daily.player_progress_buckets_json, '$."50"'), 0),
                            COALESCE(json_extract(excluded.player_progress_buckets_json, '$."50"'), 0)
                        )) ELSE '{}' END
                    ),
                    CASE WHEN MAX(
                        COALESCE(json_extract(ai_profile_behavior_daily.player_progress_buckets_json, '$."75"'), 0),
                        COALESCE(json_extract(excluded.player_progress_buckets_json, '$."75"'), 0)
                    ) > 0 THEN json_object('75', MAX(
                        COALESCE(json_extract(ai_profile_behavior_daily.player_progress_buckets_json, '$."75"'), 0),
                        COALESCE(json_extract(excluded.player_progress_buckets_json, '$."75"'), 0)
                    )) ELSE '{}' END
                ),
                CASE WHEN MAX(
                    COALESCE(json_extract(ai_profile_behavior_daily.player_progress_buckets_json, '$."completed"'), 0),
                    COALESCE(json_extract(excluded.player_progress_buckets_json, '$."completed"'), 0)
                ) > 0 THEN json_object('completed', MAX(
                    COALESCE(json_extract(ai_profile_behavior_daily.player_progress_buckets_json, '$."completed"'), 0),
                    COALESCE(json_extract(excluded.player_progress_buckets_json, '$."completed"'), 0)
                )) ELSE '{}' END
            ),
            episode_started_count = MAX(ai_profile_behavior_daily.episode_started_count, excluded.episode_started_count),
            episode_completed_count = MAX(ai_profile_behavior_daily.episode_completed_count, excluded.episode_completed_count),
            last_event_at = NULLIF(MAX(
                COALESCE(ai_profile_behavior_daily.last_event_at, 0),
                COALESCE(excluded.last_event_at, 0)
            ), 0),
            updated_at = excluded.updated_at
    `).bind(
        friendId,
        event.mediaKey,
        event.eventDay,
        event.detailDwellBucket,
        event.searchClickCount,
        JSON.stringify(event.playerProgressBuckets),
        event.episodeStartedCount,
        event.episodeCompletedCount,
        event.lastEventAt,
        now(),
    );
}

async function first<T>(env: ProfileStoreEnvironment, sql: string, ...bindings: unknown[]): Promise<T | null> {
    if (!env.DB) throw new AppError('AI_STORAGE_ERROR', 'AI profile storage is unavailable', 503);
    try {
        return await env.DB.prepare(sql).bind(...bindings).first<T>();
    } catch {
        throw new AppError('AI_STORAGE_ERROR', 'AI profile storage is unavailable', 503);
    }
}

async function all<T>(env: ProfileStoreEnvironment, sql: string, ...bindings: unknown[]): Promise<T[]> {
    if (!env.DB) throw new AppError('AI_STORAGE_ERROR', 'AI profile storage is unavailable', 503);
    try {
        const result = await env.DB.prepare(sql).bind(...bindings).all<T>();
        return result.results ?? [];
    } catch {
        throw new AppError('AI_STORAGE_ERROR', 'AI profile storage is unavailable', 503);
    }
}

async function run(env: ProfileStoreEnvironment, sql: string, ...bindings: unknown[]): Promise<void> {
    if (!env.DB) throw new AppError('AI_STORAGE_ERROR', 'AI profile storage is unavailable', 503);
    try {
        await env.DB.prepare(sql).bind(...bindings).run();
    } catch {
        throw new AppError('AI_STORAGE_ERROR', 'AI profile storage is unavailable', 503);
    }
}

async function batch(env: ProfileStoreEnvironment, statements: D1PreparedStatement[]): Promise<void> {
    if (!env.DB) throw new AppError('AI_STORAGE_ERROR', 'AI profile storage is unavailable', 503);
    try {
        await env.DB.batch(statements);
    } catch {
        throw new AppError('AI_STORAGE_ERROR', 'AI profile storage is unavailable', 503);
    }
}

function getState(env: ProfileStoreEnvironment): MemoryState {
    let root = env.AI_TEST_PROFILE;
    if (!(root instanceof Map)) {
        root = testStateRoots.get(env as object);
        if (!root) {
            root = new Map<string, unknown>();
            testStateRoots.set(env as object, root);
        }
    }
    const create = <T>(key: string): Map<string, T> => {
        const current = root!.get(key);
        if (current instanceof Map) return current as Map<string, T>;
        const next = new Map<string, T>();
        root!.set(key, next);
        return next;
    };
    const stateKey = '__state__';
    const existing = root.get(stateKey);
    if (existing && isMemoryState(existing)) return existing;
    const state: MemoryState = {
        settings: create<SettingsRow>('settings'),
        media: create<MediaRow>('media'),
        sources: create<StoredSource>('sources'),
        behavior: create<BehaviorRow>('behavior'),
        snapshots: create<SnapshotRow>('snapshots'),
        batches: create<StoredBatch>('batches'),
    };
    root.set(stateKey, state);
    return state;
}

function isMemoryState(value: unknown): value is MemoryState {
    return Boolean(value && typeof value === 'object' &&
        (value as MemoryState).settings instanceof Map &&
        (value as MemoryState).media instanceof Map &&
        (value as MemoryState).sources instanceof Map &&
        (value as MemoryState).behavior instanceof Map &&
        (value as MemoryState).snapshots instanceof Map &&
        (value as MemoryState).batches instanceof Map);
}

function boolNumber(value: boolean): number {
    return value ? 1 : 0;
}

function parseSyncResult(value: string): SyncResult {
    try {
        const parsed = JSON.parse(value) as SyncResult;
        if (parsed.accepted !== true || typeof parsed.batchId !== 'string') throw new Error('invalid');
        return parsed;
    } catch {
        throw new AppError('AI_STORAGE_ERROR', 'Stored AI profile batch is invalid', 503);
    }
}

function parseStringArray(value: string): string[] {
    try {
        const parsed = JSON.parse(value) as unknown;
        return Array.isArray(parsed) ? parsed.filter(item => typeof item === 'string').slice(0, 8) : [];
    } catch {
        return [];
    }
}

function parseJsonObject(value: string | undefined): Record<string, number> {
    if (!value) return {};
    try {
        const parsed = JSON.parse(value) as unknown;
        if (!parsed || typeof parsed !== 'object' || Array.isArray(parsed)) return {};
        return Object.fromEntries(
            Object.entries(parsed as Record<string, unknown>)
                .filter(([, item]) => typeof item === 'number' && Number.isFinite(item) && item > 0)
                .map(([key, item]) => [key, item as number]),
        );
    } catch {
        return {};
    }
}

function mergeProgressBuckets(
    old: Record<string, number>,
    next: Record<string, number>,
): Record<string, number> {
    const result: Record<string, number> = { ...old };
    for (const [key, value] of Object.entries(next)) {
        result[key] = maxSnapshotCount(result[key], value);
    }
    return result;
}

function maxSnapshotCount(oldValue: number | undefined, nextValue: number): number {
    return Math.min(10000, Math.max(oldValue ?? 0, nextValue));
}

function maxDwellBucket(
    oldValue: string | null | undefined,
    nextValue: string | null,
): string | null {
    const rank = (value: string | null | undefined): number => {
        switch (value) {
            case 'UNDER_TEN_SECONDS': return 1;
            case 'TEN_TO_THIRTY_SECONDS': return 2;
            case 'THIRTY_TO_ONE_HUNDRED_TWENTY_SECONDS': return 3;
            case 'OVER_ONE_HUNDRED_TWENTY_SECONDS': return 4;
            default: return 0;
        }
    };
    return rank(nextValue) >= rank(oldValue) ? nextValue : oldValue ?? null;
}
