// AI 私有缓存与原子配额存储。生产环境只使用 D1，Map 仅在明确测试模式启用。

import { AppError, now } from '../util/errors.ts';

export interface AiStoreEnvironment {
    DB?: D1Database;
    KV?: KVNamespace;
    AI_TEST_MODE?: boolean | string;
    AI_TEST_CACHE?: Map<string, unknown>;
    AI_TEST_QUOTA?: Map<string, unknown>;
    AI_TEST_RATE_LIMIT?: Map<string, unknown>;
    AI_TEST_NICKNAME?: string;
}

interface CachedRow {
    payload_json: string;
    expires_at: number;
}

interface TestQuotaRow {
    sessionId: string;
    sessionCount: number;
    dailyCount: number;
}

interface D1QuotaRow {
    session_id: string;
    session_count: number;
    daily_count: number;
}

interface TtsRateLimitRow {
    windowStartedAt: number;
    requestCount: number;
    missCount: number;
}

export const AI_SESSION_LIMIT = 14;
export const AI_DAILY_LIMIT = 80;
export const AI_TTS_REQUEST_LIMIT = 20;
export const AI_TTS_MISS_LIMIT = 5;
export const AI_TTS_RATE_WINDOW_SECONDS = 10 * 60;

export function isTestStore(env: AiStoreEnvironment): boolean {
    return env.AI_TEST_MODE === true || env.AI_TEST_MODE === 'true';
}

export async function readAiCache(env: AiStoreEnvironment, key: string): Promise<unknown | null> {
    if (isTestStore(env) && env.AI_TEST_CACHE instanceof Map) {
        const value = env.AI_TEST_CACHE.get(key);
        if (!value) return null;
        if (isRecord(value) && typeof value.expiresAt === 'number' && value.expiresAt <= now()) {
            env.AI_TEST_CACHE.delete(key);
            return null;
        }
        return isRecord(value) && 'payload' in value ? value.payload : value;
    }

    if (!env.DB) throw new AppError('AI_STORAGE_ERROR', 'AI storage is unavailable', 503);
    try {
        const row = await env.DB.prepare(`
            SELECT payload_json, expires_at
            FROM ai_cache
            WHERE cache_key = ? AND expires_at > ?
        `).bind(key, now()).first<CachedRow>();
        if (!row) return null;
        return JSON.parse(row.payload_json) as unknown;
    } catch {
        throw new AppError('AI_STORAGE_ERROR', 'AI storage is unavailable', 503);
    }
}

export async function writeAiCache(
    env: AiStoreEnvironment,
    key: string,
    payload: unknown,
    ttlSeconds: number,
    friendId: string | null,
    kind: string,
): Promise<void> {
    const expiresAt = now() + Math.max(1, Math.min(ttlSeconds, 30 * 24 * 60 * 60));
    if (isTestStore(env) && env.AI_TEST_CACHE instanceof Map) {
        env.AI_TEST_CACHE.set(key, { payload, expiresAt });
        return;
    }

    if (!env.DB) throw new AppError('AI_STORAGE_ERROR', 'AI storage is unavailable', 503);
    try {
        await env.DB.prepare(`
            INSERT INTO ai_cache (cache_key, friend_id, kind, payload_json, expires_at, created_at, updated_at)
            VALUES (?, ?, ?, ?, ?, ?, ?)
            ON CONFLICT(cache_key) DO UPDATE SET
                friend_id = excluded.friend_id,
                kind = excluded.kind,
                payload_json = excluded.payload_json,
                expires_at = excluded.expires_at,
                updated_at = excluded.updated_at
        `).bind(key, friendId, kind, JSON.stringify(payload), expiresAt, now(), now()).run();
    } catch {
        throw new AppError('AI_STORAGE_ERROR', 'AI storage is unavailable', 503);
    }
}

export async function getFriendNickname(env: AiStoreEnvironment, friendId: string): Promise<string> {
    if (isTestStore(env) && typeof env.AI_TEST_NICKNAME === 'string') {
        return env.AI_TEST_NICKNAME.slice(0, 32);
    }
    if (!env.DB) throw new AppError('AI_STORAGE_ERROR', 'AI storage is unavailable', 503);
    try {
        const row = await env.DB.prepare('SELECT nickname FROM friends WHERE id = ?')
            .bind(friendId)
            .first<{ nickname: string }>();
        if (!row || typeof row.nickname !== 'string' || !row.nickname.trim()) {
            throw new AppError('UNAUTHORIZED', 'Friend account was not found', 401);
        }
        return row.nickname.trim().slice(0, 32);
    } catch (error) {
        if (error instanceof AppError) throw error;
        throw new AppError('AI_STORAGE_ERROR', 'AI storage is unavailable', 503);
    }
}

export async function reserveAiQuota(
    env: AiStoreEnvironment,
    friendId: string,
    deviceId: string,
    sessionId: string,
): Promise<{ sessionCount: number; dailyCount: number; sessionLimit: number; dailyLimit: number }> {
    const usageDay = new Date().toISOString().slice(0, 10);
    if (isTestStore(env) && env.AI_TEST_QUOTA instanceof Map) {
        // 日配额按账号而不是设备计算，避免多设备绕过上限。
        const key = `${friendId}:${usageDay}`;
        const current = readTestQuota(env.AI_TEST_QUOTA.get(key));
        const sameSession = current?.sessionId === sessionId;
        const sessionCount = sameSession ? (current?.sessionCount || 0) + 1 : 1;
        const dailyCount = (current?.dailyCount || 0) + 1;
        if (dailyCount > AI_DAILY_LIMIT) {
            throw new AppError('AI_DAILY_QUOTA_EXCEEDED', 'Daily AI quota exceeded', 429);
        }
        if (sameSession && sessionCount > AI_SESSION_LIMIT) {
            throw new AppError('AI_SESSION_QUOTA_EXCEEDED', 'AI session quota exceeded', 429);
        }
        env.AI_TEST_QUOTA.set(key, { sessionId, sessionCount, dailyCount });
        return { sessionCount, dailyCount, sessionLimit: AI_SESSION_LIMIT, dailyLimit: AI_DAILY_LIMIT };
    }

    if (!env.DB) throw new AppError('AI_STORAGE_ERROR', 'AI storage is unavailable', 503);
    try {
        const result = await env.DB.prepare(`
            INSERT INTO ai_usage (
                friend_id, device_id, usage_day, session_id,
                session_count, daily_count, updated_at
            ) VALUES (?, ?, ?, ?, 1, 1, ?)
            ON CONFLICT(friend_id, usage_day) DO UPDATE SET
                device_id = excluded.device_id,
                session_id = excluded.session_id,
                session_count = CASE
                    WHEN ai_usage.session_id = excluded.session_id THEN ai_usage.session_count + 1
                    ELSE 1
                END,
                daily_count = ai_usage.daily_count + 1,
                updated_at = excluded.updated_at
            WHERE ai_usage.daily_count < 80
              AND (ai_usage.session_id <> excluded.session_id OR ai_usage.session_count < 14)
        `).bind(friendId, deviceId, usageDay, sessionId, now()).run();
        if (typeof result.meta?.changes === 'number' && result.meta.changes === 0) {
            const existing = await env.DB.prepare(`
                SELECT session_id, session_count, daily_count
                FROM ai_usage
                WHERE friend_id = ? AND usage_day = ?
            `).bind(friendId, usageDay).first<D1QuotaRow>();
            if (existing?.daily_count === AI_DAILY_LIMIT) {
                throw new AppError('AI_DAILY_QUOTA_EXCEEDED', 'Daily AI quota exceeded', 429);
            }
            throw new AppError('AI_SESSION_QUOTA_EXCEEDED', 'AI session quota exceeded', 429);
        }
        const usage = await env.DB.prepare(`
            SELECT session_count, daily_count
            FROM ai_usage
            WHERE friend_id = ? AND usage_day = ?
        `).bind(friendId, usageDay).first<{ session_count: number; daily_count: number }>();
        return {
            sessionCount: usage?.session_count || 1,
            dailyCount: usage?.daily_count || 1,
            sessionLimit: AI_SESSION_LIMIT,
            dailyLimit: AI_DAILY_LIMIT,
        };
    } catch (error) {
        if (error instanceof AppError) throw error;
        throw new AppError('AI_STORAGE_ERROR', 'AI quota storage is unavailable', 503);
    }
}

export async function reserveAiTtsRequest(env: AiStoreEnvironment, clientIp: string): Promise<void> {
    const state = await readTtsRateLimit(env, clientIp);
    if (state.requestCount >= AI_TTS_REQUEST_LIMIT) {
        throw new AppError('RATE_LIMITED', 'Too many TTS requests', 429);
    }
    state.requestCount += 1;
    await writeTtsRateLimit(env, clientIp, state);
}

export async function reserveAiTtsMiss(env: AiStoreEnvironment, clientIp: string): Promise<void> {
    const state = await readTtsRateLimit(env, clientIp);
    if (state.missCount >= AI_TTS_MISS_LIMIT) {
        throw new AppError('RATE_LIMITED', 'Too many uncached TTS requests', 429);
    }
    state.missCount += 1;
    await writeTtsRateLimit(env, clientIp, state);
}

export async function cacheKeyDigest(input: string): Promise<string> {
    const digest = await crypto.subtle.digest('SHA-256', new TextEncoder().encode(input));
    return Array.from(new Uint8Array(digest), byte => byte.toString(16).padStart(2, '0')).join('');
}

function readTestQuota(value: unknown): TestQuotaRow | null {
    if (!isRecord(value)) return null;
    if (
        typeof value.sessionId !== 'string'
        || typeof value.sessionCount !== 'number'
        || typeof value.dailyCount !== 'number'
    ) return null;
    return {
        sessionId: value.sessionId,
        sessionCount: value.sessionCount,
        dailyCount: value.dailyCount,
    };
}

function isRecord(value: unknown): value is Record<string, unknown> {
    return typeof value === 'object' && value !== null && !Array.isArray(value);
}

async function readTtsRateLimit(env: AiStoreEnvironment, clientIp: string): Promise<TtsRateLimitRow> {
    const key = `ai:tts:rate:v1:${await cacheKeyDigest(clientIp || 'unknown')}`;
    const currentTime = now();
    if (isTestStore(env) && env.AI_TEST_RATE_LIMIT instanceof Map) {
        const current = readTtsRateLimitRow(env.AI_TEST_RATE_LIMIT.get(key));
        if (!current || currentTime - current.windowStartedAt >= AI_TTS_RATE_WINDOW_SECONDS) {
            return { windowStartedAt: currentTime, requestCount: 0, missCount: 0 };
        }
        return current;
    }

    if (!env.KV) return { windowStartedAt: currentTime, requestCount: 0, missCount: 0 };
    try {
        const raw = await env.KV.get(key);
        const current = raw ? readTtsRateLimitRow(JSON.parse(raw)) : null;
        if (!current || currentTime - current.windowStartedAt >= AI_TTS_RATE_WINDOW_SECONDS) {
            return { windowStartedAt: currentTime, requestCount: 0, missCount: 0 };
        }
        return current;
    } catch {
        throw new AppError('AI_STORAGE_ERROR', 'AI rate limit storage is unavailable', 503);
    }
}

async function writeTtsRateLimit(
    env: AiStoreEnvironment,
    clientIp: string,
    state: TtsRateLimitRow,
): Promise<void> {
    const key = `ai:tts:rate:v1:${await cacheKeyDigest(clientIp || 'unknown')}`;
    if (isTestStore(env) && env.AI_TEST_RATE_LIMIT instanceof Map) {
        env.AI_TEST_RATE_LIMIT.set(key, state);
        return;
    }
    if (!env.KV) return;
    try {
        await env.KV.put(key, JSON.stringify(state), { expirationTtl: AI_TTS_RATE_WINDOW_SECONDS });
    } catch {
        throw new AppError('AI_STORAGE_ERROR', 'AI rate limit storage is unavailable', 503);
    }
}

function readTtsRateLimitRow(value: unknown): TtsRateLimitRow | null {
    if (!isRecord(value)) return null;
    if (
        typeof value.windowStartedAt !== 'number'
        || typeof value.requestCount !== 'number'
        || typeof value.missCount !== 'number'
    ) return null;
    return {
        windowStartedAt: value.windowStartedAt,
        requestCount: value.requestCount,
        missCount: value.missCount,
    };
}
