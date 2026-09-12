// 模型级免费额度记忆：百炼的免费额度按模型独立发放（每个模型 100 万 token），
// 用尽后上游稳定返回 403 `AllocationQuota.FreeTierOnly` / `insufficient_quota`，
// 而这笔额度不会自己回来。所以「已耗尽」的模型必须从轮换里摘掉：
// 否则每次生成都会先打一次注定失败的调用，白等一个 RTT，把延迟和健康事件都搅浑。
//
// 状态存 KV（isolate 会回收，内存记忆活不过一次请求），TTL 取 30 天——
// 少于百炼免费额度的 90 天有效期，够久到不浪费调用，又能在额度恢复后自动回到梯队。
// isolate 内再加一层 60 秒 memo：出题链路一次生成会调很多次上游，不该每次都读 KV。

export interface ModelQuotaEnvironment {
    KV?: KVNamespace;
}

const MARKER_KEY_PREFIX = 'ai:model-quota:v1:';
const MARKER_TTL_SECONDS = 30 * 24 * 60 * 60;
const MEMO_TTL_MS = 60 * 1000;

/** isolate 内缓存：provider -> 已耗尽模型集合（含过期时间）。 */
const memo = new Map<string, { models: Set<string>; expiresAtMs: number }>();
/** 同一 isolate 内并发读取去重：出题一次生成会并发发起多个槽位，别让它们各读一次 KV。 */
const inFlight = new Map<string, Promise<Set<string>>>();

function markerKey(provider: string): string {
    return MARKER_KEY_PREFIX + provider;
}

/**
 * 免费额度耗尽判定。上游两种形态都见过了：
 * - 403 `AllocationQuota.FreeTierOnly`（百炼官方口径）；
 * - 200 包在正文里的 `{"error":{"code":"insufficient_quota","message":"Free quota exhausted..."}}`。
 * bailian 适配层已把它们统一折成 `AI_QUOTA_EXHAUSTED`，这里再按文本兜一层，
 * 免得网关换措辞就退化回「每次都白打一次」。
 */
export function isQuotaExhaustedError(error: unknown, memoizedModel?: string): boolean {
    if (!error || typeof error !== 'object') return false;
    const candidate = error as { code?: unknown; message?: unknown; upstreamStatus?: unknown };
    if (candidate.code === 'AI_QUOTA_EXHAUSTED') return true;
    if (candidate.upstreamStatus === 403) return true;
    const message = typeof candidate.message === 'string' ? candidate.message : '';
    const text = message + (memoizedModel ?? '');
    return /AllocationQuota\.FreeTierOnly|insufficient_quota|free quota|quota exhausted/i.test(text);
}

/** 读取该供应商下已耗尽的模型集合。无 KV 绑定时返回空集（退化成不去重）。 */
export async function readExhaustedModels(env: ModelQuotaEnvironment, provider: string): Promise<Set<string>> {
    const cached = memo.get(provider);
    if (cached && cached.expiresAtMs > Date.now()) return new Set(cached.models);

    const pending = inFlight.get(provider);
    if (pending) return new Set(await pending);

    const task = (async (): Promise<Set<string>> => {
        const empty = new Set<string>();
        if (!env.KV) return empty;
        try {
            const raw = await env.KV.get(markerKey(provider));
            if (typeof raw !== 'string' || raw === '') return empty;
            const parsed: unknown = JSON.parse(raw);
            if (!Array.isArray(parsed)) return empty;
            return new Set(parsed.filter((item): item is string => typeof item === 'string' && item !== ''));
        } catch {
            // 读不到就按「没记录」处理：宁可多试一次，也不能因为 KV 抖动把整条梯队掐掉。
            return empty;
        }
    })();

    inFlight.set(provider, task);
    try {
        const models = await task;
        memo.set(provider, { models, expiresAtMs: Date.now() + MEMO_TTL_MS });
        return new Set(models);
    } finally {
        inFlight.delete(provider);
    }
}

/** 记下额度耗尽的模型：写 KV + 立即更新 isolate 内 memo，让本次请求的后续调用也不用再试它。 */
export async function markModelExhausted(
    env: ModelQuotaEnvironment,
    provider: string,
    model: string,
): Promise<void> {
    if (!model) return;
    const current = await readExhaustedModels(env, provider);
    if (current.has(model)) return;
    current.add(model);
    memo.set(provider, { models: current, expiresAtMs: Date.now() + MEMO_TTL_MS });
    if (!env.KV) return;
    try {
        await env.KV.put(markerKey(provider), JSON.stringify([...current]), {
            expirationTtl: MARKER_TTL_SECONDS,
        });
    } catch {
        // 写失败只影响下一次请求白试一次，不该让本次生成失败。
    }
}

/**
 * 把候选模型拆成「还有额度的」和「已耗尽的」。
 * 已耗尽的不删除而是排在后面：所有候选都没有额度时仍然会试它们，
 * 不至于因为一条过期的记录把整家供应商掐死。
 */
export function splitExhaustedModels(
    candidates: readonly string[],
    exhausted: ReadonlySet<string>,
): { available: string[]; exhausted: string[] } {
    const available: string[] = [];
    const spent: string[] = [];
    for (const candidate of candidates) {
        (exhausted.has(candidate) ? spent : available).push(candidate);
    }
    return { available, exhausted: spent };
}

/** 测试用：清掉 isolate 内缓存，模拟「换了一个 isolate」。 */
export function __resetModelQuotaMemo(): void {
    memo.clear();
    inFlight.clear();
}
