import { AppError } from './errors.ts';

const COOLDOWN_MS = 5 * 60 * 1000;

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
 */
export class KeyPool {
    private readonly storageKey: string;
    private readonly service: string;
    private readonly secret: string | undefined;
    private readonly kv: KVNamespace;
    private readonly nowMs: () => number;

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
        if (keys.length === 0) return;
        const configured = await Promise.all(keys.map(async (key) => ({
            key,
            fingerprint: await fingerprintKey(key),
        })));
        const states = await this.syncStates(configured);
        const updated = states.map((state) => {
            if (state.fingerprint !== candidate.fingerprint) return state;
            if (status === 429) {
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
        const raw = await this.kv.get(this.storageKey);
        const previous = parseStoredStates(raw);
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

        if (JSON.stringify(previous) !== JSON.stringify(states)) {
            await this.saveStates(states);
        }
        return states;
    }

    private async saveStates(states: StoredKeyState[]): Promise<void> {
        await this.kv.put(this.storageKey, JSON.stringify(states));
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
