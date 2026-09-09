// 今日影视知识的 AI 概念插图：安全提示词、后台幂等生成、R2 持久化与受控访问。

import { AppError, now } from '../util/errors.ts';
import { sha256 } from '../util/crypto.ts';
import { signAccessToken, verifyAccessToken } from '../util/jwt.ts';
import { callAgnesImage } from './agnes.ts';
import type { DailyLocale } from './daily-knowledge.ts';

export const DAILY_ILLUSTRATION_STYLE_VERSION = 'editorial-low-saturation-v1';
const DAILY_ILLUSTRATION_OBJECT_PREFIX = 'daily-illustration-v1';
const DAILY_ILLUSTRATION_MAX_ATTEMPTS = 2;
const DAILY_ILLUSTRATION_LEASE_SECONDS = 10 * 60;
const DAILY_ILLUSTRATION_DEFAULT_DAILY_LIMIT = 10;
const DAILY_ILLUSTRATION_RATE_WINDOW_SECONDS = 24 * 60 * 60;
const DAILY_ILLUSTRATION_TTL_SECONDS = 365 * 24 * 60 * 60;
const DAILY_ILLUSTRATION_URL_TTL_SECONDS = 10 * 60;
const DAILY_ILLUSTRATION_MIN_BYTES = 512;
const DAILY_ILLUSTRATION_MAX_BYTES = 8 * 1024 * 1024;
const DAILY_ILLUSTRATION_MIN_DIMENSION = 256;
const DAILY_ILLUSTRATION_MAX_DIMENSION = 4096;
const DAILY_ILLUSTRATION_MAX_ASPECT_RATIO = 3;

export type DailyIllustrationStatus = 'generating' | 'unavailable' | 'ready';
export type ConceptIllustrationMimeType = 'image/png' | 'image/jpeg' | 'image/webp';

export interface DailyIllustrationUnitInput {
    unitId: string;
    locale: DailyLocale;
    concept: string;
    takeaway: string;
    explanation: string;
}

export interface ConceptIllustrationProvider {
    generate(unit: DailyIllustrationUnitInput): Promise<Uint8Array>;
}

export interface DailyIllustrationEnvironment {
    DB?: D1Database;
    KV?: KVNamespace;
    AGNES_API_KEYS?: string;
    AI_TEST_MODE?: boolean | string;
    AI_TEST_ILLUSTRATION_STATE?: Map<string, unknown>;
    AI_TEST_ILLUSTRATION_RATE_LIMIT?: Map<string, unknown>;
    AI_IMAGE_CACHE?: R2Bucket;
    AI_AUDIO_CACHE?: R2Bucket;
    AI_DAILY_ILLUSTRATION_ENABLED?: boolean | string;
    AI_DAILY_ILLUSTRATION_DAILY_LIMIT?: number | string;
    JWT_SIGNING_KEY?: string;
}

export interface BackgroundScheduler {
    waitUntil(promise: Promise<unknown>): void;
}

export interface DailyIllustrationPublic {
    status: DailyIllustrationStatus;
    role: 'concept_illustration';
    url: string | null;
    styleVersion: string;
    mimeType?: ConceptIllustrationMimeType | null;
    width?: number | null;
    height?: number | null;
    urlExpiresAt?: number | null;
}

interface IllustrationState {
    status: DailyIllustrationStatus;
    attemptCount: number;
    objectKey: string | null;
    mimeType: ConceptIllustrationMimeType | null;
    width: number | null;
    height: number | null;
    sizeBytes: number | null;
    lastErrorCode: string | null;
    updatedAt: number;
}

interface ValidatedIllustration {
    mimeType: ConceptIllustrationMimeType;
    width: number;
    height: number;
    bytes: Uint8Array;
}

/** Agnes 适配器只负责取原始字节；安全提示词与技术校验由本模块统一约束。 */
export class AgnesIllustrationProvider implements ConceptIllustrationProvider {
    private readonly env: DailyIllustrationEnvironment;

    constructor(env: DailyIllustrationEnvironment) {
        this.env = env;
    }

    async generate(unit: DailyIllustrationUnitInput): Promise<Uint8Array> {
        return callAgnesImage(
            this.env,
            'agnes-image-2.5-flash',
            buildConceptIllustrationPrompt(unit),
        );
    }
}

export function buildConceptIllustrationPrompt(unit: DailyIllustrationUnitInput): string {
    // 不传影片名、角色名、演员名或 filmEvidence，避免模型复刻具体影片场景。
    return [
        'Create one low-saturation editorial concept illustration.',
        `Concept: ${safePromptText(unit.concept, 120)}`,
        `Core takeaway: ${safePromptText(unit.takeaway, 320)}`,
        `Explanation: ${safePromptText(unit.explanation, 700)}`,
        'Visual style: abstract, metaphorical, flat editorial illustration, muted colors, matte paper texture, soft light, generous negative space, single centered idea, 16:9 composition.',
        'Strict safety constraints: no real actors, no identifiable real people, no celebrity likeness, no film characters, no costumes, no props, no composition copied from any movie scene, no posters or screenshots.',
        'Do not imitate historical photographs, archival film, documentary footage, or news photos. Do not use photorealism.',
        'Do not include any text, letters, numbers, labels, captions, subtitles, logos, symbols, signatures, or watermarks.',
        'Do not depict dangerous chemicals, weapons, violence, self-harm, malware interfaces, intrusion steps, or operational technical instructions. Keep the image conceptual and non-operational.',
        'The image is only a learning aid, not factual evidence. Subject labels are added later by the app, not by the image model.',
    ].join('\n');
}

/**
 * 为 daily 响应装配插图状态。任何插图存储或签名异常都降级为 unavailable，
 * 绝不能让文字响应失败或改变旧字段。
 */
export async function publicDailyIllustration(
    env: DailyIllustrationEnvironment,
    unit: DailyIllustrationUnitInput,
    friendId: string,
    origin: string,
    background: BackgroundScheduler | undefined,
): Promise<DailyIllustrationPublic> {
    if (!isIllustrationEnabled(env)) return unavailableIllustration();
    try {
        const state = await readIllustrationState(env, unit);
        if (state?.status === 'unavailable') return unavailableIllustration();
        if (state?.status === 'ready') return await readyIllustration(env, state, origin);
        scheduleDailyIllustration(env, unit, friendId, background);
        return {
            status: 'generating',
            role: 'concept_illustration',
            url: null,
            styleVersion: DAILY_ILLUSTRATION_STYLE_VERSION,
        };
    } catch {
        return unavailableIllustration();
    }
}

export function scheduleDailyIllustration(
    env: DailyIllustrationEnvironment,
    unit: DailyIllustrationUnitInput,
    friendId: string,
    background: BackgroundScheduler | undefined,
): void {
    // 生产入口始终传入 ExecutionContext；没有调度器时保持不生成，避免直接测试路径阻塞文字响应。
    if (!background) return;
    background.waitUntil(generateDailyIllustrationInBackground(env, unit, friendId));
}

/** 后台任务永不抛错；失败只写入稳定错误码，不记录密钥或上游原文。 */
export async function generateDailyIllustrationInBackground(
    env: DailyIllustrationEnvironment,
    unit: DailyIllustrationUnitInput,
    friendId: string,
): Promise<void> {
    try {
        const claim = await claimIllustration(env, unit);
        if (!claim.claimed) return;

        if (!(await reserveDailyIllustrationRateLimit(env, friendId))) {
            await markIllustrationUnavailable(env, unit, claim.attemptCount, 'RATE_LIMITED');
            return;
        }

        const provider = new AgnesIllustrationProvider(env);
        for (let attempt = claim.attemptCount; attempt <= DAILY_ILLUSTRATION_MAX_ATTEMPTS; attempt += 1) {
            try {
                const raw = await provider.generate(unit);
                const image = validateConceptIllustration(raw);
                const objectKey = await illustrationObjectKey(unit, image.mimeType);
                await uploadIllustrationToR2(env, unit, objectKey, image);
                if (await markIllustrationReady(env, unit, attempt, objectKey, image)) return;
                // 状态租约已被其他任务接管时立即停止，避免旧任务覆盖新状态。
                return;
            } catch (error) {
                const errorCode = classifyIllustrationError(error);
                if (attempt < DAILY_ILLUSTRATION_MAX_ATTEMPTS) {
                    await touchIllustrationGenerating(env, unit, attempt, errorCode);
                    continue;
                }
                await markIllustrationUnavailable(env, unit, attempt, errorCode);
                return;
            }
        }
    } catch {
        // 后台插图失败不允许影响 waitUntil 或 daily 文字响应。
    }
}

export async function handleAiIllustration(
    env: DailyIllustrationEnvironment,
    token: string,
): Promise<Response> {
    const secret = typeof env.JWT_SIGNING_KEY === 'string' ? env.JWT_SIGNING_KEY.trim() : '';
    const payload = secret ? await verifyAccessToken(secret, token) : null;
    if (
        !payload
        || payload.sub !== 'illustration'
        || !Array.isArray(payload.scope)
        || !payload.scope.includes('illustration')
        || typeof payload.device !== 'string'
        || !isIllustrationObjectKey(payload.device)
    ) {
        throw new AppError('INVALID_ILLUSTRATION_TOKEN', 'Invalid or expired illustration token', 403);
    }

    const bucket = illustrationBucket(env);
    if (!bucket) throw new AppError('ILLUSTRATION_NOT_FOUND', 'Illustration is not available', 404);
    let object: R2ObjectBody | null = null;
    try {
        object = await bucket.get(payload.device);
    } catch {
        throw new AppError('AI_STORAGE_ERROR', 'Illustration storage is unavailable', 503);
    }
    if (!object || !object.body) throw new AppError('ILLUSTRATION_NOT_FOUND', 'Illustration is not available', 404);

    const contentType = object.httpMetadata?.contentType;
    if (contentType !== 'image/png' && contentType !== 'image/jpeg' && contentType !== 'image/webp') {
        throw new AppError('ILLUSTRATION_NOT_FOUND', 'Illustration is not available', 404);
    }
    const headers = new Headers();
    object.writeHttpMetadata(headers);
    headers.set('Content-Type', contentType);
    headers.set('Cache-Control', `private, max-age=${DAILY_ILLUSTRATION_URL_TTL_SECONDS}`);
    headers.set('ETag', object.httpEtag);
    return new Response(object.body, { status: 200, headers });
}

function unavailableIllustration(): DailyIllustrationPublic {
    return {
        status: 'unavailable',
        role: 'concept_illustration',
        url: null,
        styleVersion: DAILY_ILLUSTRATION_STYLE_VERSION,
        mimeType: null,
        width: null,
        height: null,
        urlExpiresAt: null,
    };
}

async function readyIllustration(
    env: DailyIllustrationEnvironment,
    state: IllustrationState,
    origin: string,
): Promise<DailyIllustrationPublic> {
    const secret = typeof env.JWT_SIGNING_KEY === 'string' ? env.JWT_SIGNING_KEY.trim() : '';
    if (!state.objectKey || !state.mimeType || !secret) return unavailableIllustration();
    const token = await signAccessToken(
        secret,
        'illustration',
        state.objectKey,
        ['illustration'],
        DAILY_ILLUSTRATION_URL_TTL_SECONDS,
    );
    return {
        status: 'ready',
        role: 'concept_illustration',
        url: `${origin}/api/ai/illustration/${token}`,
        styleVersion: DAILY_ILLUSTRATION_STYLE_VERSION,
        mimeType: state.mimeType,
        width: state.width,
        height: state.height,
        urlExpiresAt: (now() + DAILY_ILLUSTRATION_URL_TTL_SECONDS) * 1000,
    };
}

function isIllustrationEnabled(env: DailyIllustrationEnvironment): boolean {
    const value = env.AI_DAILY_ILLUSTRATION_ENABLED;
    return value === true || value === 'true' || value === '1' || value === 'on';
}

function readDailyIllustrationLimit(env: DailyIllustrationEnvironment): number {
    const value = Number(env.AI_DAILY_ILLUSTRATION_DAILY_LIMIT ?? DAILY_ILLUSTRATION_DEFAULT_DAILY_LIMIT);
    if (!Number.isFinite(value)) return DAILY_ILLUSTRATION_DEFAULT_DAILY_LIMIT;
    const limit = Math.floor(value);
    if (limit < 0) return 0;
    return Math.min(limit, 100);
}

function illustrationBucket(env: DailyIllustrationEnvironment): R2Bucket | undefined {
    // 先支持专用绑定；未配置时复用现有私有 AI 媒体桶，避免为了 P2 额外创建资源。
    return env.AI_IMAGE_CACHE ?? env.AI_AUDIO_CACHE;
}

function isTestIllustrationStore(env: DailyIllustrationEnvironment): boolean {
    return env.AI_TEST_MODE === true || env.AI_TEST_MODE === 'true';
}

function testStateMap(env: DailyIllustrationEnvironment): Map<string, unknown> {
    if (!(env.AI_TEST_ILLUSTRATION_STATE instanceof Map)) {
        env.AI_TEST_ILLUSTRATION_STATE = new Map<string, unknown>();
    }
    return env.AI_TEST_ILLUSTRATION_STATE;
}

function testRateMap(env: DailyIllustrationEnvironment): Map<string, unknown> {
    if (!(env.AI_TEST_ILLUSTRATION_RATE_LIMIT instanceof Map)) {
        env.AI_TEST_ILLUSTRATION_RATE_LIMIT = new Map<string, unknown>();
    }
    return env.AI_TEST_ILLUSTRATION_RATE_LIMIT;
}

function illustrationStateKey(unit: DailyIllustrationUnitInput): string {
    return JSON.stringify([
        unit.unitId,
        unit.locale,
        DAILY_ILLUSTRATION_STYLE_VERSION,
    ]);
}

async function readIllustrationState(
    env: DailyIllustrationEnvironment,
    unit: DailyIllustrationUnitInput,
): Promise<IllustrationState | null> {
    if (isTestIllustrationStore(env)) {
        return readStateRecord(testStateMap(env).get(illustrationStateKey(unit)));
    }
    if (!env.DB) return null;
    try {
        const row = await env.DB.prepare(`
            SELECT status, attempt_count, object_key, mime_type, width, height, size_bytes, last_error_code, updated_at
            FROM ai_daily_illustrations
            WHERE knowledge_unit_id = ? AND locale = ? AND image_style_version = ?
        `).bind(unit.unitId, unit.locale, DAILY_ILLUSTRATION_STYLE_VERSION)
            .first<IllustrationRow>();
        return row ? toIllustrationState(row) : null;
    } catch {
        return null;
    }
}

interface IllustrationRow {
    status: DailyIllustrationStatus;
    attempt_count: number;
    object_key: string | null;
    mime_type: ConceptIllustrationMimeType | null;
    width: number | null;
    height: number | null;
    size_bytes: number | null;
    last_error_code: string | null;
    updated_at: number;
}

function readStateRecord(value: unknown): IllustrationState | null {
    if (!isRecord(value)) return null;
    if (
        (value.status !== 'generating' && value.status !== 'ready' && value.status !== 'unavailable')
        || typeof value.attemptCount !== 'number'
        || typeof value.updatedAt !== 'number'
    ) return null;
    return {
        status: value.status,
        attemptCount: value.attemptCount,
        objectKey: typeof value.objectKey === 'string' ? value.objectKey : null,
        mimeType: value.mimeType === 'image/png' || value.mimeType === 'image/jpeg' || value.mimeType === 'image/webp'
            ? value.mimeType
            : null,
        width: typeof value.width === 'number' ? value.width : null,
        height: typeof value.height === 'number' ? value.height : null,
        sizeBytes: typeof value.sizeBytes === 'number' ? value.sizeBytes : null,
        lastErrorCode: typeof value.lastErrorCode === 'string' ? value.lastErrorCode : null,
        updatedAt: value.updatedAt,
    };
}

function toIllustrationState(row: IllustrationRow): IllustrationState {
    return {
        status: row.status,
        attemptCount: row.attempt_count,
        objectKey: row.object_key,
        mimeType: row.mime_type,
        width: row.width,
        height: row.height,
        sizeBytes: row.size_bytes,
        lastErrorCode: row.last_error_code,
        updatedAt: row.updated_at,
    };
}

async function claimIllustration(
    env: DailyIllustrationEnvironment,
    unit: DailyIllustrationUnitInput,
): Promise<{ claimed: boolean; attemptCount: number }> {
    const currentTime = now();
    const attemptCount = 1;
    if (isTestIllustrationStore(env)) {
        const map = testStateMap(env);
        const key = illustrationStateKey(unit);
        const current = readStateRecord(map.get(key));
        if (current) {
            if (current.status !== 'generating') return { claimed: false, attemptCount: current.attemptCount };
            if (currentTime - current.updatedAt < DAILY_ILLUSTRATION_LEASE_SECONDS) {
                return { claimed: false, attemptCount: current.attemptCount };
            }
            // 租约已过期：还有重试额度就继续下一次尝试；额度耗尽则必须把卡死的
            // generating 状态终结为 unavailable，否则客户端会一直轮询到“永远生成中”。
            if (current.attemptCount >= DAILY_ILLUSTRATION_MAX_ATTEMPTS) {
                await markIllustrationUnavailable(env, unit, current.attemptCount, 'ATTEMPTS_EXHAUSTED');
                return { claimed: false, attemptCount: current.attemptCount };
            }
            const nextAttempt = current.attemptCount + 1;
            map.set(key, generatingState(nextAttempt, currentTime));
            return { claimed: true, attemptCount: nextAttempt };
        }
        map.set(key, generatingState(attemptCount, currentTime));
        return { claimed: true, attemptCount };
    }

    if (!env.DB) return { claimed: false, attemptCount };
    try {
        const result = await env.DB.prepare(`
            INSERT INTO ai_daily_illustrations (
                knowledge_unit_id, locale, image_style_version, status, attempt_count,
                object_key, mime_type, width, height, size_bytes, last_error_code,
                created_at, updated_at
            ) VALUES (?, ?, ?, 'generating', ?, NULL, NULL, NULL, NULL, NULL, NULL, ?, ?)
            ON CONFLICT(knowledge_unit_id, locale, image_style_version) DO UPDATE SET
                status = 'generating',
                attempt_count = ai_daily_illustrations.attempt_count + 1,
                object_key = NULL,
                mime_type = NULL,
                width = NULL,
                height = NULL,
                size_bytes = NULL,
                last_error_code = NULL,
                updated_at = excluded.updated_at
            WHERE ai_daily_illustrations.status = 'generating'
              AND ai_daily_illustrations.attempt_count < ?
              AND ai_daily_illustrations.updated_at + ? <= ?
        `).bind(
            unit.unitId,
            unit.locale,
            DAILY_ILLUSTRATION_STYLE_VERSION,
            attemptCount,
            currentTime,
            currentTime,
            DAILY_ILLUSTRATION_MAX_ATTEMPTS,
            DAILY_ILLUSTRATION_LEASE_SECONDS,
            currentTime,
        ).run();
        if (Number(result.meta?.changes || 0) !== 1) {
            await closeExpiredExhaustedClaim(env, unit, currentTime);
            return { claimed: false, attemptCount };
        }
        // 冲突分支会把旧 attempt_count + 1；重新读取，避免租约恢复时误按第 1 次尝试重复两次。
        const row = await env.DB.prepare(`
            SELECT attempt_count
            FROM ai_daily_illustrations
            WHERE knowledge_unit_id = ? AND locale = ? AND image_style_version = ?
        `).bind(unit.unitId, unit.locale, DAILY_ILLUSTRATION_STYLE_VERSION)
            .first<{ attempt_count: number }>();
        return { claimed: true, attemptCount: row?.attempt_count ?? attemptCount };
    } catch {
        return { claimed: false, attemptCount };
    }
}

/**
 * 租约过期且重试次数已耗尽时，把残留的 generating 行终结为 unavailable。
 * WHERE 只命中“仍处于 generating、次数已满、租约确已过期”的行，不会干扰正在运行的任务
 * 或已 ready/unavailable 的状态。
 */
async function closeExpiredExhaustedClaim(
    env: DailyIllustrationEnvironment,
    unit: DailyIllustrationUnitInput,
    currentTime: number,
): Promise<void> {
    if (!env.DB) return;
    try {
        await env.DB.prepare(`
            UPDATE ai_daily_illustrations
            SET status = 'unavailable', object_key = NULL, mime_type = NULL, width = NULL,
                height = NULL, size_bytes = NULL, last_error_code = 'ATTEMPTS_EXHAUSTED',
                updated_at = ?
            WHERE knowledge_unit_id = ? AND locale = ? AND image_style_version = ?
              AND status = 'generating'
              AND attempt_count >= ?
              AND updated_at + ? <= ?
        `).bind(
            currentTime,
            unit.unitId,
            unit.locale,
            DAILY_ILLUSTRATION_STYLE_VERSION,
            DAILY_ILLUSTRATION_MAX_ATTEMPTS,
            DAILY_ILLUSTRATION_LEASE_SECONDS,
            currentTime,
        ).run();
    } catch {
        // 终结失败也不抛错：下次调度仍会再次尝试清理。
    }
}

function generatingState(attemptCount: number, updatedAt: number): IllustrationState {
    return {
        status: 'generating',
        attemptCount,
        objectKey: null,
        mimeType: null,
        width: null,
        height: null,
        sizeBytes: null,
        lastErrorCode: null,
        updatedAt,
    };
}

async function touchIllustrationGenerating(
    env: DailyIllustrationEnvironment,
    unit: DailyIllustrationUnitInput,
    attemptCount: number,
    errorCode: string,
): Promise<boolean> {
    const currentTime = now();
    if (isTestIllustrationStore(env)) {
        return updateTestState(env, unit, state => ({
            ...state,
            status: 'generating',
            attemptCount: attemptCount + 1,
            lastErrorCode: errorCode,
            updatedAt: currentTime,
        }), attemptCount, 'generating');
    }
    if (!env.DB) return false;
    try {
        const result = await env.DB.prepare(`
            UPDATE ai_daily_illustrations
            SET status = 'generating', attempt_count = ai_daily_illustrations.attempt_count + 1,
                last_error_code = ?, updated_at = ?
            WHERE knowledge_unit_id = ? AND locale = ? AND image_style_version = ?
              AND status = 'generating' AND attempt_count = ?
        `).bind(
            errorCode,
            currentTime,
            unit.unitId,
            unit.locale,
            DAILY_ILLUSTRATION_STYLE_VERSION,
            attemptCount,
        ).run();
        return Number(result.meta?.changes || 0) === 1;
    } catch {
        return false;
    }
}

async function markIllustrationReady(
    env: DailyIllustrationEnvironment,
    unit: DailyIllustrationUnitInput,
    attemptCount: number,
    objectKey: string,
    image: ValidatedIllustration,
): Promise<boolean> {
    const currentTime = now();
    if (isTestIllustrationStore(env)) {
        return updateTestState(env, unit, state => ({
            ...state,
            status: 'ready',
            attemptCount,
            objectKey,
            mimeType: image.mimeType,
            width: image.width,
            height: image.height,
            sizeBytes: image.bytes.byteLength,
            lastErrorCode: null,
            updatedAt: currentTime,
        }), attemptCount, 'generating');
    }
    if (!env.DB) return false;
    try {
        const result = await env.DB.prepare(`
            UPDATE ai_daily_illustrations
            SET status = 'ready', object_key = ?, mime_type = ?, width = ?, height = ?,
                size_bytes = ?, last_error_code = NULL, updated_at = ?
            WHERE knowledge_unit_id = ? AND locale = ? AND image_style_version = ?
              AND status = 'generating' AND attempt_count = ?
        `).bind(
            objectKey,
            image.mimeType,
            image.width,
            image.height,
            image.bytes.byteLength,
            currentTime,
            unit.unitId,
            unit.locale,
            DAILY_ILLUSTRATION_STYLE_VERSION,
            attemptCount,
        ).run();
        return Number(result.meta?.changes || 0) === 1;
    } catch {
        return false;
    }
}

async function markIllustrationUnavailable(
    env: DailyIllustrationEnvironment,
    unit: DailyIllustrationUnitInput,
    attemptCount: number,
    errorCode: string,
): Promise<boolean> {
    const currentTime = now();
    if (isTestIllustrationStore(env)) {
        return updateTestState(env, unit, state => ({
            ...state,
            status: 'unavailable',
            attemptCount,
            objectKey: null,
            mimeType: null,
            width: null,
            height: null,
            sizeBytes: null,
            lastErrorCode: errorCode,
            updatedAt: currentTime,
        }), attemptCount, 'generating');
    }
    if (!env.DB) return false;
    try {
        const result = await env.DB.prepare(`
            UPDATE ai_daily_illustrations
            SET status = 'unavailable', object_key = NULL, mime_type = NULL, width = NULL,
                height = NULL, size_bytes = NULL, last_error_code = ?, updated_at = ?
            WHERE knowledge_unit_id = ? AND locale = ? AND image_style_version = ?
              AND status = 'generating' AND attempt_count = ?
        `).bind(
            errorCode,
            currentTime,
            unit.unitId,
            unit.locale,
            DAILY_ILLUSTRATION_STYLE_VERSION,
            attemptCount,
        ).run();
        return Number(result.meta?.changes || 0) === 1;
    } catch {
        return false;
    }
}

function updateTestState(
    env: DailyIllustrationEnvironment,
    unit: DailyIllustrationUnitInput,
    mutate: (state: IllustrationState) => IllustrationState,
    expectedAttemptCount: number,
    expectedStatus: DailyIllustrationStatus,
): boolean {
    const map = testStateMap(env);
    const key = illustrationStateKey(unit);
    const current = readStateRecord(map.get(key));
    if (!current || current.status !== expectedStatus || current.attemptCount !== expectedAttemptCount) return false;
    map.set(key, mutate(current));
    return true;
}

async function reserveDailyIllustrationRateLimit(
    env: DailyIllustrationEnvironment,
    friendId: string,
): Promise<boolean> {
    const limit = readDailyIllustrationLimit(env);
    if (limit === 0) return false;
    const day = new Intl.DateTimeFormat('en-CA', { timeZone: 'Asia/Shanghai' }).format(new Date());
    const digest = await sha256(`daily-illustration:${friendId}:${day}`);
    const bucketKey = `ai:daily-illustration:v1:${digest}`;
    const currentTime = now();

    if (isTestIllustrationStore(env)) {
        const map = testRateMap(env);
        const current = readRateRecord(map.get(bucketKey));
        if (current && currentTime - current.windowStartedAt < DAILY_ILLUSTRATION_RATE_WINDOW_SECONDS) {
            if (current.count >= limit) return false;
            map.set(bucketKey, { windowStartedAt: current.windowStartedAt, count: current.count + 1, updatedAt: currentTime });
            return true;
        }
        map.set(bucketKey, { windowStartedAt: currentTime, count: 1, updatedAt: currentTime });
        return true;
    }

    if (!env.DB) return false;
    try {
        const result = await env.DB.prepare(`
            INSERT INTO rate_limits (bucket_key, window_started_at, count, updated_at)
            VALUES (?, ?, 1, ?)
            ON CONFLICT(bucket_key) DO UPDATE SET
                window_started_at = CASE
                    WHEN rate_limits.window_started_at + ? <= excluded.window_started_at
                        THEN excluded.window_started_at
                    ELSE rate_limits.window_started_at
                END,
                count = CASE
                    WHEN rate_limits.window_started_at + ? <= excluded.window_started_at
                        THEN 1
                    ELSE rate_limits.count + 1
                END,
                updated_at = excluded.updated_at
            WHERE rate_limits.window_started_at + ? <= excluded.window_started_at
               OR rate_limits.count < ?
        `).bind(
            bucketKey,
            currentTime,
            currentTime,
            DAILY_ILLUSTRATION_RATE_WINDOW_SECONDS,
            DAILY_ILLUSTRATION_RATE_WINDOW_SECONDS,
            DAILY_ILLUSTRATION_RATE_WINDOW_SECONDS,
            limit,
        ).run();
        return Number(result.meta?.changes || 0) === 1;
    } catch {
        return false;
    }
}

interface RateRecord {
    windowStartedAt: number;
    count: number;
    updatedAt: number;
}

function readRateRecord(value: unknown): RateRecord | null {
    if (!isRecord(value)) return null;
    if (
        typeof value.windowStartedAt !== 'number'
        || typeof value.count !== 'number'
        || typeof value.updatedAt !== 'number'
    ) return null;
    return {
        windowStartedAt: value.windowStartedAt,
        count: value.count,
        updatedAt: value.updatedAt,
    };
}

async function illustrationObjectKey(
    unit: DailyIllustrationUnitInput,
    mimeType: ConceptIllustrationMimeType,
): Promise<string> {
    const digest = await sha256(illustrationStateKey(unit));
    const extension = mimeType === 'image/jpeg' ? 'jpg' : mimeType === 'image/webp' ? 'webp' : 'png';
    return `${DAILY_ILLUSTRATION_OBJECT_PREFIX}/${digest}.${extension}`;
}

function isIllustrationObjectKey(key: string): boolean {
    return /^daily-illustration-v1\/[a-f0-9]{64}\.(?:png|jpg|webp)$/.test(key);
}

async function uploadIllustrationToR2(
    env: DailyIllustrationEnvironment,
    unit: DailyIllustrationUnitInput,
    objectKey: string,
    image: ValidatedIllustration,
): Promise<void> {
    const bucket = illustrationBucket(env);
    if (!bucket) {
        throw new AppError('AI_ILLUSTRATION_STORAGE_NOT_CONFIGURED', 'Illustration storage is not configured', 503);
    }
    try {
        await bucket.put(objectKey, image.bytes, {
            httpMetadata: {
                contentType: image.mimeType,
                cacheControl: 'private, max-age=31536000, immutable',
            },
            customMetadata: {
                knowledgeUnitId: unit.unitId,
                locale: unit.locale,
                imageStyleVersion: DAILY_ILLUSTRATION_STYLE_VERSION,
                expiresAt: String(now() + DAILY_ILLUSTRATION_TTL_SECONDS),
            },
        });
    } catch {
        throw new AppError('AI_STORAGE_ERROR', 'Illustration storage write failed', 503);
    }
    let stored: R2Object | null = null;
    try {
        stored = await bucket.head(objectKey);
    } catch {
        throw new AppError('AI_STORAGE_ERROR', 'Illustration storage verification failed', 503);
    }
    if (!stored || stored.size !== image.bytes.byteLength) {
        throw new AppError('AI_STORAGE_ERROR', 'Illustration storage verification failed', 503);
    }
}

/**
 * 技术校验：签名、MIME、尺寸、大小和明显非图片响应。
 * 这里通过格式头解码宽高；不信任上游 Content-Type，也不接受供应商 URL。
 */
export function validateConceptIllustration(bytes: Uint8Array): ValidatedIllustration {
    if (bytes.byteLength < DAILY_ILLUSTRATION_MIN_BYTES || bytes.byteLength > DAILY_ILLUSTRATION_MAX_BYTES) {
        throw new AppError('INVALID_AI_OUTPUT', 'Illustration size is invalid', 502);
    }
    const image = readImageHeader(bytes);
    if (!image) throw new AppError('INVALID_AI_OUTPUT', 'Illustration format is invalid', 502);
    if (
        image.width < DAILY_ILLUSTRATION_MIN_DIMENSION
        || image.height < DAILY_ILLUSTRATION_MIN_DIMENSION
        || image.width > DAILY_ILLUSTRATION_MAX_DIMENSION
        || image.height > DAILY_ILLUSTRATION_MAX_DIMENSION
        || image.width / image.height > DAILY_ILLUSTRATION_MAX_ASPECT_RATIO
        || image.height / image.width > DAILY_ILLUSTRATION_MAX_ASPECT_RATIO
        || !hasByteVariety(bytes)
    ) {
        throw new AppError('INVALID_AI_OUTPUT', 'Illustration dimensions or content are invalid', 502);
    }
    return { ...image, bytes };
}

function readImageHeader(bytes: Uint8Array): { mimeType: ConceptIllustrationMimeType; width: number; height: number } | null {
    const png = readPngHeader(bytes);
    if (png) return png;
    const jpeg = readJpegHeader(bytes);
    if (jpeg) return jpeg;
    return readWebpHeader(bytes);
}

function readPngHeader(bytes: Uint8Array): { mimeType: 'image/png'; width: number; height: number } | null {
    const pngSignature = [0x89, 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a];
    if (bytes.byteLength < 24 || !pngSignature.every((byte, index) => bytes[index] === byte)) return null;
    return {
        mimeType: 'image/png',
        width: readUInt32BE(bytes, 16),
        height: readUInt32BE(bytes, 20),
    };
}

function readJpegHeader(bytes: Uint8Array): { mimeType: 'image/jpeg'; width: number; height: number } | null {
    if (bytes.byteLength < 4 || bytes[0] !== 0xff || bytes[1] !== 0xd8) return null;
    let offset = 2;
    while (offset + 9 < bytes.byteLength) {
        if (bytes[offset] !== 0xff) {
            offset += 1;
            continue;
        }
        const marker = bytes[offset + 1];
        if (marker === 0xff) {
            offset += 1;
            continue;
        }
        if (marker === 0x01 || (marker >= 0xd0 && marker <= 0xd7)) {
            offset += 2;
            continue;
        }
        if (marker === 0xda) return null;
        if (offset + 4 > bytes.byteLength) return null;
        const segmentLength = readUInt16BE(bytes, offset + 2);
        if (segmentLength < 2) return null;
        const isStartOfFrame = (marker >= 0xc0 && marker <= 0xc3)
            || (marker >= 0xc5 && marker <= 0xc7)
            || (marker >= 0xc9 && marker <= 0xcb)
            || (marker >= 0xcd && marker <= 0xcf);
        if (isStartOfFrame && offset + 9 <= bytes.byteLength) {
            return {
                mimeType: 'image/jpeg',
                height: readUInt16BE(bytes, offset + 5),
                width: readUInt16BE(bytes, offset + 7),
            };
        }
        offset += 2 + segmentLength;
    }
    return null;
}

function readWebpHeader(bytes: Uint8Array): { mimeType: 'image/webp'; width: number; height: number } | null {
    if (bytes.byteLength < 30) return null;
    if (
        String.fromCharCode(...bytes.subarray(0, 4)) !== 'RIFF'
        || String.fromCharCode(...bytes.subarray(8, 12)) !== 'WEBP'
    ) return null;
    const chunk = String.fromCharCode(...bytes.subarray(12, 16));
    if (chunk === 'VP8X') {
        return {
            mimeType: 'image/webp',
            width: 1 + (bytes[24] | (bytes[25] << 8) | (bytes[26] << 16)),
            height: 1 + (bytes[27] | (bytes[28] << 8) | (bytes[29] << 16)),
        };
    }
    if (chunk === 'VP8L') {
        const bits = bytes[20] | (bytes[21] << 8) | (bytes[22] << 16) | (bytes[23] << 24);
        return {
            mimeType: 'image/webp',
            width: (bits & 0x3fff) + 1,
            height: ((bits >> 14) & 0x3fff) + 1,
        };
    }
    if (chunk === 'VP8 ') {
        return {
            mimeType: 'image/webp',
            width: bytes[26] | (bytes[27] << 8),
            height: bytes[28] | (bytes[29] << 8),
        };
    }
    return null;
}

function readUInt32BE(bytes: Uint8Array, offset: number): number {
    return ((bytes[offset] << 24) | (bytes[offset + 1] << 16) | (bytes[offset + 2] << 8) | bytes[offset + 3]) >>> 0;
}

function readUInt16BE(bytes: Uint8Array, offset: number): number {
    return (bytes[offset] << 8) | bytes[offset + 1];
}

function hasByteVariety(bytes: Uint8Array): boolean {
    // 抽样检查字节多样性，拦截全零/重复填充这类明显空白产物；格式头仍负责拦截 HTML/JSON。
    const values = new Set<number>();
    const step = Math.max(1, Math.ceil(bytes.byteLength / 2048));
    for (let index = 0; index < bytes.byteLength; index += step) {
        values.add(bytes[index]);
        if (values.size >= 16) return true;
    }
    return false;
}

function classifyIllustrationError(error: unknown): string {
    if (error instanceof AppError) {
        if (error.code === 'AI_NOT_CONFIGURED') return 'PROVIDER_NOT_CONFIGURED';
        if (error.code === 'AI_UPSTREAM_ERROR') return 'PROVIDER_FAILED';
        if (error.code === 'INVALID_AI_OUTPUT') return 'INVALID_IMAGE';
        if (error.code === 'AI_STORAGE_ERROR' || error.code === 'AI_ILLUSTRATION_STORAGE_NOT_CONFIGURED') return 'STORAGE_FAILED';
        return error.code;
    }
    return 'ILLUSTRATION_FAILED';
}

function safePromptText(value: string, maxLength: number): string {
    const normalized = value
        .replace(/[\u0000-\u001f\u007f]+/g, ' ')
        .replace(/\s+/g, ' ')
        .trim();
    return normalized.slice(0, maxLength);
}

function isRecord(value: unknown): value is Record<string, unknown> {
    return typeof value === 'object' && value !== null && !Array.isArray(value);
}
