import { AppError } from './errors.ts';

const COOLDOWN_MS = 5 * 60 * 1000;

/**
 * isolate 内状态缓存有效期。
 *
 * KV 本身是最终一致存储（写入后最长 60s 才全球可见），因此额外的 10s 内存缓存
 * 不会引入新的一致性问题，但能把「每个代理请求读一次 KV」降到每 10s 一次。
 */
const STATE_CACHE_TTL_MS = 10 * 1000;

interface CachedKeyPoolState {
    /** 缓存对应的 KV 绑定实例；不同绑定（含测试里的不同 stub）不复用缓存。 */
    kv: unknown;
    /** JSON.stringify 后的规范形式，用于判断是否需要真正写 KV。 */
    canonical: string;
    states: StoredKeyState[];
    fetchedAtMs: number;
}

/** isolate 级状态缓存，随 isolate 回收自动释放。 */
const stateCache = new Map<string, CachedKeyPoolState>();

/** 测试用：清空 isolate 级状态缓存。 */
export function resetKeyPoolStateCache(): void {
    stateCache.clear();
}

export type KeyStatus = 'ACTIVE' | 'COOLING' | 'INVALID';

interface StoredKeyState {
    fingerprint: string;
    status: KeyStatus;
    cooldownUntilMs: number;
}

export interface KeyCandidate {
    key: string;
    fingerprint: string;
}

/** 将 Worker Secret 解析为去重后的 key 池，兼容旧的单值配置。 */
export function parseSecretKeys(secret: string | undefined | null): string[] {
    const value = secret?.trim() || '';
    if (!value) return [];

    try {
        const parsed: unknown = JSON.parse(value);
        if (Array.isArray(parsed)) {
            return uniqueStrings(parsed);
        }
    } catch {
        // 非 JSON Secret 按逗号/换行分隔的旧格式继续处理。
    }

    return uniqueStrings(value.split(/[\s,]+/));
}

function uniqueStrings(values: unknown[]): string[] {
    const result: string[] = [];
    const seen = new Set<string>();
    for (const value of values) {
        if (typeof value !== 'string') continue;
        const trimmed = value.trim();
        if (!trimmed || seen.has(trimmed)) continue;
        seen.add(trimmed);
        result.push(trimmed);
    }
    return result;
}

export async function fingerprintKey(key: string): Promise<string> {
    const digest = await crypto.subtle.digest(
        'SHA-256',
        new TextEncoder().encode(key),
    );
    return [...new Uint8Array(digest)]
        .map((byte) => byte.toString(16).padStart(2, '0'))
        .join('');
}

/**
 * Worker 侧的 API key 状态机。
 * KV 只存指纹，不存明文 key；Secret 变化时按指纹保留旧状态并激活新 key。
 *
 * KV 配额优化（免费版每天 1000 次写）：
 * - 单 key 池完全不碰 KV：只有一个候选时，COOLING / INVALID 状态没有可切换对象，
 *   getCandidates 最终必然回退到这唯一的 key，读写 KV 不影响任何行为。
 * - saveStates 状态未变化时跳过写入：markFailure 会对已经是 INVALID / 已在冷却中的
 *   key 反复调用，无条件写会让每个失败的上游请求都消耗 1 次写配额。
 * - 读取走 isolate 级缓存（[STATE_CACHE_TTL_MS]），同一 isolate 的连续请求不重复读 KV。
 */
export class KeyPool {
    private readonly storageKey: string;
    private readonly service: string;
    private readonly secret: string | undefined;
    private readonly kv: KVNamespace;
    private readonly nowMs: () => number;
    /** 最近一次读到/写入的规范化状态，用于跳过无变化的写入。 */
    private lastCanonical: string | null = null;

    constructor(
        service: string,
        secret: string | undefined,
        kv: KVNamespace,
        nowMs: () => number = () => Date.now(),
    ) {
        this.service = service;
        this.secret = secret;
        this.kv = kv;
        this.nowMs = nowMs;
        this.storageKey = `key-pool:${service}`;
    }

    async pickKey(): Promise<string> {
        const [candidate] = await this.getCandidates();
        return candidate.key;
    }

    async getCandidates(): Promise<KeyCandidate[]> {
        const keys = parseSecretKeys(this.secret);
        if (keys.length === 0) {
            throw new AppError(
                'UPSTREAM_CONFIG_ERROR',
                `${this.service} upstream credential is not configured`,
                500,
            );
        }

        // 单 key 池：状态机没有可轮换对象，直接返回，省掉每请求 1 次 KV 读。
        if (keys.length === 1) {
            return [{ key: keys[0], fingerprint: await fingerprintKey(keys[0]) }];
        }

        const configured = await Promise.all(keys.map(async (key) => ({
            key,
            fingerprint: await fingerprintKey(key),
        })));
        const states = await this.syncStates(configured);
        const active = states.filter((state) => state.status === 'ACTIVE');
        if (active.length > 0) return active.map(toCandidate(configured));

        const expired = states.find((state) => (
            state.status === 'COOLING' && state.cooldownUntilMs <= this.nowMs()
        ));
        if (expired) {
            const updated = states.map((state) => state.fingerprint === expired.fingerprint
                ? { ...state, status: 'ACTIVE' as const, cooldownUntilMs: 0 }
                : state);
            await this.saveStates(updated);
            return updated
                .filter((state) => state.status === 'ACTIVE')
                .map(toCandidate(configured));
        }

        // 保持旧客户端语义：全部失效或仍在冷却时仍回退第一个 key。
        return [toCandidate(configured)(states[0])];
    }

    async markFailure(candidate: KeyCandidate, status: number): Promise<void> {
        if (status !== 429 && status !== 401 && status !== 403) return;

        const keys = parseSecretKeys(this.secret);
        // 单 key 池：状态落盘不会改变后续取 key 的结果，跳过以免每次上游失败都写 KV。
        if (keys.length <= 1) return;
        const configured = await Promise.all(keys.map(async (key) => ({
            key,
            fingerprint: await fingerprintKey(key),
        })));
        const states = await this.syncStates(configured);
        const updated = states.map((state) => {
            if (state.fingerprint !== candidate.fingerprint) return state;
            if (status === 429) {
                // 已在冷却窗口内的 key 不再延长冷却：TMDB 这类按 IP 限流的上游会让
                // 同一个 key 连续拿到 429，若每次都刷新 cooldownUntilMs，状态就每次都变、
                // 每次都要写一次 KV。保持原截止时间可让重复 429 变成幂等操作。
                if (state.status === 'COOLING' && state.cooldownUntilMs > this.nowMs()) {
                    return state;
                }
                return {
                    ...state,
                    status: 'COOLING' as const,
                    cooldownUntilMs: this.nowMs() + COOLDOWN_MS,
                };
            }
            return { ...state, status: 'INVALID' as const, cooldownUntilMs: 0 };
        });
        await this.saveStates(updated);
    }

    private async syncStates(configured: KeyCandidate[]): Promise<StoredKeyState[]> {
        const previous = await this.readStates();
        const previousByFingerprint = new Map(
            previous.map((state) => [state.fingerprint, state]),
        );
        const states = configured.map(({ fingerprint }) => {
            const oldState = previousByFingerprint.get(fingerprint);
            if (!oldState) {
                return { fingerprint, status: 'ACTIVE' as const, cooldownUntilMs: 0 };
            }
            if (oldState.status === 'COOLING' && oldState.cooldownUntilMs <= this.nowMs()) {
                return { ...oldState, status: 'ACTIVE' as const, cooldownUntilMs: 0 };
            }
            return oldState;
        });

        await this.saveStates(states);
        return states;
    }

    /**
     * 读取状态：优先用 isolate 缓存，未命中才读 KV。
     * TTL 用真实时钟判断，避免注入的固定测试时钟让缓存永久有效。
     */
    private async readStates(): Promise<StoredKeyState[]> {
        const cached = stateCache.get(this.storageKey);
        if (cached && cached.kv === this.kv && Date.now() - cached.fetchedAtMs < STATE_CACHE_TTL_MS) {
            this.lastCanonical = cached.canonical;
            return cached.states;
        }
        const raw = await this.kv.get(this.storageKey);
        const states = parseStoredStates(raw);
        this.rememberStates(states);
        return states;
    }

    private rememberStates(states: StoredKeyState[]): void {
        const canonical = JSON.stringify(states);
        this.lastCanonical = canonical;
        stateCache.set(this.storageKey, {
            kv: this.kv,
            canonical,
            states,
            fetchedAtMs: Date.now(),
        });
    }

    private async saveStates(states: StoredKeyState[]): Promise<void> {
        const canonical = JSON.stringify(states);
        // 状态与最近一次读到/写入的完全一致时不写，直接省掉一次写配额。
        if (canonical === this.lastCanonical) return;
        await this.kv.put(this.storageKey, canonical);
        this.rememberStates(states);
    }
}

function parseStoredStates(raw: string | null): StoredKeyState[] {
    if (!raw) return [];
    try {
        const parsed: unknown = JSON.parse(raw);
        if (!Array.isArray(parsed)) return [];
        return parsed.filter(isStoredKeyState);
    } catch {
        return [];
    }
}

function isStoredKeyState(value: unknown): value is StoredKeyState {
    if (typeof value !== 'object' || value === null) return false;
    const state = value as Record<string, unknown>;
    return typeof state.fingerprint === 'string'
        && (state.status === 'ACTIVE' || state.status === 'COOLING' || state.status === 'INVALID')
        && typeof state.cooldownUntilMs === 'number';
}

function toCandidate(configured: KeyCandidate[]) {
    return (state: StoredKeyState): KeyCandidate => {
        const candidate = configured.find(({ fingerprint }) => fingerprint === state.fingerprint);
        if (!candidate) throw new Error('Key pool state is out of sync');
        return candidate;
    };
}

/** 对可轮换状态码依次尝试 key，最终返回最后一次上游响应。 */
export async function fetchWithKeyRotation(
    pool: KeyPool,
    request: (key: string) => Promise<Response>,
    rotateOn: readonly number[] = [429, 401, 403],
): Promise<Response> {
    const candidates = await pool.getCandidates();
    let lastResponse: Response | undefined;
    for (let index = 0; index < candidates.length; index += 1) {
        const candidate = candidates[index];
        const response = await request(candidate.key);
        lastResponse = response;
        if (!rotateOn.includes(response.status)) return response;

        await pool.markFailure(candidate, response.status);
        if (index === candidates.length - 1) return response;
        if (response.body) await response.body.cancel();
    }
    return lastResponse!;
}
