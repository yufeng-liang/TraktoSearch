// 通用并发工具：并发闸门 + 限频退避重试。
//
// 背景：出题从「一次生成 13 题」改成「13 个题位并发生成、失败只补那一格」之后，
// 需要 (a) 一个闸门把同时打出去的上游请求压住，避免自己把自己打成限频；
// (b) 一个退避重试工具应对上游瞬时限流。
//
// 两者都与出题业务无关，因此单独放这一个文件，且**不 import 任何项目内模块**：
// 限频判定只做结构化判断（读 error.code / error.message），不依赖 AppError 类，
// 这样调用方（出题、翻译、摘要……）都能复用它而不会产生循环依赖。

/**
 * 单个元素的结果：成功带值，失败带原始错误。
 *
 * 失败之所以不外抛，是因为批量场景里「一格失败」不该让整批作废——
 * 调用方按下标就能只补失败的那一格。
 */
export type GateOutcome<R> = { ok: true; value: R } | { ok: false; error: unknown };

/** 退避基准等待：400ms 起步，2^n 增长。 */
const DEFAULT_BASE_MS = 400;

/** 退避上限：单次等待封顶 8s，超过这个时长的等待对一次出题请求已无意义。 */
const DEFAULT_MAX_MS = 8000;

/** 指数项封顶的次幂：2^30 已远超任何 maxMs，再大只会溢出成 Infinity。 */
const MAX_EXPONENT = 30;

/**
 * 以不超过 limit 的并发度跑完 items，逐个收集结果。
 *
 * 实现是 **worker 池循环**（固定起 `min(limit, items.length)` 个 runner，共用自增下标抢活），
 * 而不是「每 limit 个切一批 await 一批」：后者会被最慢的那个拖住整批，
 * 这里任何一个 runner 完成都会立刻接管下一个下标，同时运行中的 worker 数量恒 ≤ limit。
 *
 * 永远不因单个 worker 抛错而整体失败：失败以 `{ ok: false, error }` 返回，
 * 且返回数组下标与输入下标严格一一对应（与完成先后无关）。
 *
 * @param limit 并发上限；小于 1（含 NaN）时按 1 处理。
 */
export async function mapWithGate<T, R>(
    items: readonly T[],
    limit: number,
    worker: (item: T, index: number) => Promise<R>,
): Promise<Array<GateOutcome<R>>> {
    const outcomes = new Array<GateOutcome<R>>(items.length);
    if (items.length === 0) return outcomes;

    const concurrency = normalizeConcurrency(limit, items.length);
    let nextIndex = 0;

    const runner = async (): Promise<void> => {
        for (;;) {
            // 自增与读取之间没有 await，单线程下不会出现两个 runner 抢到同一下标。
            const index = nextIndex;
            nextIndex += 1;
            if (index >= items.length) return;
            try {
                outcomes[index] = { ok: true, value: await worker(items[index], index) };
            } catch (error) {
                // 同步抛出与 Promise reject 都在这里落网，且原样保留错误值（可能是字符串甚至 undefined）。
                outcomes[index] = { ok: false, error };
            }
        }
    };

    await Promise.all(Array.from({ length: concurrency }, runner));
    return outcomes;
}

/**
 * 上游限频判定：供应商把 HTTP 200 里的业务错误码 1305（「该模型当前访问量过大」）
 * 折算成 AppError 后，这里按结构化特征识别，用于决定「要不要退避重试」。
 *
 * 命中规则：
 * - `status` / `statusCode` / `upstreamStatus` 为 429（上游原始状态码）；
 * - `code === 'RATE_LIMITED'`（网关自己的限流错误码）；
 * - `code === 'AI_UPSTREAM_ERROR'` 且 message 含 `rate limited`（大小写不敏感）/ `1305` / `访问量过大`；
 * - 上游原始业务错误对象直接上抛时 `code` 为 `'1305'`（或数字 1305），或 message 里带 1305。
 *
 * 其它一律 false（undefined / null / 字符串 / 普通 Error 都不会被当成限频）。
 */
export function isRetryableRateLimit(error: unknown): boolean {
    if (!error || typeof error !== 'object') return false;

    const candidate = error as {
        code?: unknown;
        message?: unknown;
        status?: unknown;
        statusCode?: unknown;
        upstreamStatus?: unknown;
    };

    if (isTooManyRequests(candidate.status)
        || isTooManyRequests(candidate.statusCode)
        || isTooManyRequests(candidate.upstreamStatus)) {
        return true;
    }

    const code = typeof candidate.code === 'string'
        ? candidate.code
        : typeof candidate.code === 'number' ? String(candidate.code) : '';
    if (code === 'RATE_LIMITED') return true;
    if (code === '1305') return true;

    const message = typeof candidate.message === 'string' ? candidate.message.toLowerCase() : '';
    if (code === 'AI_UPSTREAM_ERROR'
        && (message.includes('rate limited') || message.includes('1305') || message.includes('访问量过大'))) {
        return true;
    }

    return message.includes('1305');
}

/**
 * 指数退避：base * 2^attempt，等分抖动后封顶 maxMs。
 *
 * 抖动取 `[capped / 2, capped]`：并发重试如果同刻齐步重试，等于把限频又原样打一遍，
 * 抖动下限保底在封顶值一半，既打散了同刻重试，也不会让 attempt 变大反而等得更短。
 *
 * @param attempt 从 0 开始的第几次重试（0 表示第一次失败后的等待）
 * @param options.random 随机源，可注入以便测试确定性（会被夹到 [0, 1]）
 */
export function backoffDelayMs(
    attempt: number,
    options: { baseMs?: number; maxMs?: number; random?: () => number } = {},
): number {
    const baseMs = normalizeDuration(options.baseMs, DEFAULT_BASE_MS);
    const maxMs = normalizeDuration(options.maxMs, DEFAULT_MAX_MS);
    const random = options.random ?? Math.random;

    const safeAttempt = Number.isFinite(attempt) ? Math.max(0, Math.floor(attempt)) : 0;
    // 指数项封顶：attempt 很大时 2^attempt 会溢出成 Infinity，而 baseMs 为 0 时
    // 0 * Infinity 会得到 NaN —— NaN 会一路传给 setTimeout，比封顶值更危险。
    const capped = Math.min(baseMs * 2 ** Math.min(safeAttempt, MAX_EXPONENT), maxMs);
    if (!(capped > 0)) return 0;

    const unit = clamp01(random());
    return Math.round(capped * (0.5 + 0.5 * unit));
}

/**
 * 按需重试：只在 shouldRetry 判为可重试时退避重试，重试前 `await sleep(backoffDelayMs(...))`。
 *
 * 约定：
 * - `attempts` 是**总**尝试次数（含首次），attempts <= 1 时只调用一次。
 * - shouldRetry 返回 false 时立刻抛出，不消耗剩余尝试次数，也不睡眠。
 * - 最后一次失败**原样抛出**（错误对象同一性不变），由调用方决定如何折算。
 * - `sleep` 可注入（默认 setTimeout），测试无需真实等待。
 */
export async function retryWithBackoff<T>(
    fn: () => Promise<T>,
    options: {
        attempts: number;
        shouldRetry?: (error: unknown) => boolean;
        sleep?: (ms: number) => Promise<void>;
        baseMs?: number;
        maxMs?: number;
    },
): Promise<T> {
    const shouldRetry = options.shouldRetry ?? isRetryableRateLimit;
    const sleep = options.sleep ?? defaultSleep;
    const attempts = Number.isFinite(options.attempts) ? Math.max(1, Math.floor(options.attempts)) : 1;

    let lastError: unknown;
    for (let attempt = 0; attempt < attempts; attempt += 1) {
        try {
            return await fn();
        } catch (error) {
            lastError = error;
            // 已经是最后一次尝试：不再判定、不再睡眠，直接落到末尾原样抛出。
            if (attempt >= attempts - 1) break;
            if (!shouldRetry(error)) break;
            await sleep(backoffDelayMs(attempt, { baseMs: options.baseMs, maxMs: options.maxMs }));
        }
    }
    throw lastError;
}

/** 并发度收敛：非有限值（NaN/Infinity）与小于 1 的一律按 1，且不超过元素个数。 */
function normalizeConcurrency(limit: number, itemCount: number): number {
    const floored = Number.isFinite(limit) ? Math.floor(limit) : 1;
    return Math.max(1, Math.min(floored, itemCount));
}

/** 时长参数收敛：非有限值或负数回退到默认值。 */
function normalizeDuration(value: number | undefined, fallback: number): number {
    if (typeof value !== 'number' || !Number.isFinite(value) || value < 0) return fallback;
    return value;
}

/** 夹到 [0, 1]；注入的随机源越界或返回 NaN 时按 0 处理，保证结果非负。 */
function clamp01(value: number): number {
    if (!Number.isFinite(value)) return 0;
    return Math.min(1, Math.max(0, value));
}

/** 上游原始 HTTP 429：AppError 走 upstreamStatus，普通错误对象可能用 status/statusCode。 */
function isTooManyRequests(value: unknown): boolean {
    return value === 429 || value === '429';
}

/** 默认睡眠：可被 options.sleep 覆盖，测试里注入空实现即可不真实等待。 */
function defaultSleep(ms: number): Promise<void> {
    return new Promise(resolve => setTimeout(resolve, ms));
}
