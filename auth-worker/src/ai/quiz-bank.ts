import { now } from '../util/errors.ts';

// 当天 AI 题库的「确定性」机制：抽片、quizId 派生、用量计数与预算判定。
//
// 只放纯函数与用量记录的 KV 读写，不碰题库正文 —— 正文由调用方用既有 quiz 缓存读写，
// 本模块也不碰 `ai:*` 前缀的键（只写 `quizbank:used:{friendId}:{date}`）。
//
// 为什么需要它：产品去掉换片后，当天每个用户固定 2 套 13 题，题库可以提前算好，
// 用户点「开始」时命中当天题库即可秒开。预生成的前提是「可复算」：同一
// (用户, 日期, 套序号) 任何时刻重新算都必须得到同一份选题与同一个 quizId，
// 所以本文件不用 Math.random，全部走文件内自实现的稳定哈希（FNV-1a + 收尾混淆）。

/** 每天为用户预生成的题库套数。 */
export const QUIZ_SETS_PER_DAY = 2;

/** 题库与用量记录都按「日期 + 36 小时」过期，覆盖跨天边界与客户端时钟偏差。 */
export const QUIZ_BANK_TTL_SECONDS = 36 * 60 * 60;

/** 用量记录键前缀。题库正文的键不在这里维护。 */
const USAGE_KEY_PREFIX = 'quizbank:used';

/** 一天毫秒数：把 ISO 日期折算成「天数」后做相差判定。 */
const DAY_MS = 24 * 60 * 60 * 1000;

/**
 * 校验客户端传来的本地日期：必须是 YYYY-MM-DD，且与 todayIso 相差不超过 1 天
 * （容忍时区/跨零点抖动）。
 *
 * 严格拒绝「格式对但日历上不存在」的日期（如 2026-02-30、2026-13-01）：
 * Date 会把它归一化成下个月，放行会让用量记录落到错误的日期键上。
 * todayIso 自身不合法时一律返回 false（拿不到基准就没法判断偏移）。
 */
export function isLocalDate(value: unknown, todayIso: string): boolean {
    const day = parseIsoDay(value);
    const today = parseIsoDay(todayIso);
    if (day === null || today === null) return false;
    return Math.abs(day - today) <= 1;
}

/**
 * 确定性抽片：按 seed 从已看列表里稳定抽出 count 部。
 *
 * 做法：给每部片算一个「seed 派生排序键」（键只取决于 seed 与片名，与列表顺序无关），
 * 按键升序取前 count 部 —— 不是简单取前 N 部，所以同一列表在不同 seed 下抽到的片子不同。
 * 命中集合最后按 watched 的原顺序输出：同 seed 下逐元素稳定，也不会打乱用户已看列表的顺序，
 * count 覆盖全量时等价于返回入参的浅拷贝。
 *
 * 保证：同 (watched, seed, count) 逐字节可复算；不修改入参；count 超过列表长度时返回全部。
 */
export function selectDailyMovies<T extends { title: string }>(
    watched: readonly T[],
    seed: string,
    count: number,
): T[] {
    const movies: T[] = Array.isArray(watched) ? [...watched] : [];
    const take = Number.isSafeInteger(count) && count > 0 ? count : 0;
    if (take === 0 || movies.length === 0) return [];
    if (take >= movies.length) return movies;

    const ranked = movies
        .map((movie, index) => ({ index, key: movieSortKey(seed, movie.title) }))
        .sort((left, right) => (left.key - right.key) || (left.index - right.index));

    const picked = new Set<number>();
    for (const entry of ranked) {
        if (picked.size >= take) break;
        picked.add(entry.index);
    }
    return movies.filter((_, index) => picked.has(index));
}

/**
 * 当天的题库种子：同一 (用户, 日期, 套序号) 必须得到同一结果，用于复算槽位表与抽片。
 *
 * 格式与 quiz-slots.planUnitSlots(seed) 约定的 `{friendId}:{date}:{setIndex}` 一致。
 */
export function bankSeed(friendId: string, date: string, setIndex: number): string {
    return `${friendId}:${date}:${setIndex}`;
}

/**
 * 当天第 N 套题库的 quizId：形如 `bank-{date}-{setIndex}-{hash8}`。
 *
 * 稳定、可复算、不同套不同 id：hash8 取种子（含 friendId）的 32 位稳定哈希，
 * 所以不同用户即使同一天同一套序号，quizId 也不同 —— 它只用于定位缓存，不承载权限。
 */
export function deriveBankQuizId(friendId: string, date: string, setIndex: number): string {
    return `bank-${date}-${setIndex}-${digest8(bankSeed(friendId, date, setIndex))}`;
}

export interface BankUsage {
    /** 已经玩掉的套数 */
    usedSets: number;
    /** 已经成功生成并落库的套数 */
    generatedSets: number;
    /** 生成尝试次数（含失败），用于预算判定 */
    attempts: number;
}

/**
 * 空用量常量（冻结）。
 *
 * 注意：readBankUsage 返回的是它的**副本**而不是它本身 —— 调用方拿到后通常会原地累加，
 * 直接返回共享常量会被改坏，之后再读到「空值」就不空了。
 */
export const EMPTY_BANK_USAGE: BankUsage = Object.freeze({
    usedSets: 0,
    generatedSets: 0,
    attempts: 0,
});

/** 每次新建一份空用量，避免调用方改到共享状态。 */
function emptyUsage(): BankUsage {
    return { usedSets: 0, generatedSets: 0, attempts: 0 };
}

/**
 * KV 里读用量记录；没有或读失败都返回 EMPTY_BANK_USAGE 的副本；KV 未绑定同样返回空值，不抛错。
 *
 * 题库是增强路径：KV 抖动、JSON 损坏、字段被改坏都只影响统计与预算，不能让用户拿不到题。
 * 单个字段不合法（缺失/非数字/非整数/负数）按 0 处理，其余字段照常读取。
 */
export async function readBankUsage(env: BankEnv, friendId: string, date: string): Promise<BankUsage> {
    const kv = env?.KV;
    if (!kv || typeof kv.get !== 'function') return emptyUsage();
    try {
        const raw = await kv.get(usageKey(friendId, date));
        if (typeof raw !== 'string' || raw.length === 0) return emptyUsage();
        return parseBankUsage(JSON.parse(raw) as unknown);
    } catch {
        return emptyUsage();
    }
}

/**
 * 写用量记录（带 TTL）。KV 未绑定时静默返回，不抛错。
 *
 * 落库前按与读取相同的规则做归一，保证 KV 里永远是干净的三字段整数；
 * 写失败同样静默 —— 统计写不进去不能影响本次取题。
 */
export async function writeBankUsage(
    env: BankEnv,
    friendId: string,
    date: string,
    usage: BankUsage,
): Promise<void> {
    const kv = env?.KV;
    if (!kv || typeof kv.put !== 'function') return;
    try {
        const payload: BankUsage = {
            usedSets: countField(usage?.usedSets),
            generatedSets: countField(usage?.generatedSets),
            attempts: countField(usage?.attempts),
        };
        await kv.put(usageKey(friendId, date), JSON.stringify(payload), {
            expirationTtl: QUIZ_BANK_TTL_SECONDS,
        });
    } catch {
        // 静默：用量统计失败不影响取题
    }
}

/**
 * 预算判定：attempts 达到 setsPerDay*2 就不再生成（避免异常客户端刷爆额度）。
 *
 * 边界取「达到即停」：attempts === setsPerDay*2 时为 false，即一天最多尝试 2 倍套数。
 */
export function canGenerateSet(usage: BankUsage, setsPerDay: number = QUIZ_SETS_PER_DAY): boolean {
    return countField(usage?.attempts) < resolveSetsPerDay(setsPerDay) * 2;
}

/**
 * 该给用户第几套：usedSets + 1；超过 setsPerDay 时继续加（走按需生成的冷路径）。
 *
 * setsPerDay 只说明「前几套是当天预生成的」，序号本身不回绕也不截断 —— 预生成槽位用完后
 * 继续递增，调用方按「序号是否超过 setsPerDay」决定走按需生成；额度另由 canGenerateSet 把关。
 */
export function nextSetIndex(usage: BankUsage, setsPerDay: number = QUIZ_SETS_PER_DAY): number {
    void setsPerDay;
    return countField(usage?.usedSets) + 1;
}

/**
 * 该套是否属于「当天预生成的套」：序号在 setsPerDay 以内（1 起数）。
 *
 * 预生成是系统行为，只覆盖当天固定的前 setsPerDay 套；用户玩完这些套还想再来一局时，
 * 多出来的套改走按需生成（等待页有进度可看），否则每进一次出题页就会白烧一套上游调用，
 * 而那一套用户可能永远不玩。判定只吃序号，与是否已达尝试预算（canGenerateSet）是两回事。
 */
export function isPreGeneratedSet(setIndex: number, setsPerDay: number = QUIZ_SETS_PER_DAY): boolean {
    return countField(setIndex) >= 1 && countField(setIndex) <= resolveSetsPerDay(setsPerDay);
}

export interface BankEnv {
    KV?: {
        get(key: string): Promise<string | null>;
        put(key: string, value: string, options?: { expirationTtl?: number }): Promise<void>;
    };
}

/** 用量记录键：`quizbank:used:{friendId}:{date}`。 */
function usageKey(friendId: string, date: string): string {
    return `${USAGE_KEY_PREFIX}:${friendId}:${date}`;
}

/** 把 JSON 解析结果收敛成合法用量；非对象、数组、null 全部退化成空值。 */
function parseBankUsage(value: unknown): BankUsage {
    if (typeof value !== 'object' || value === null || Array.isArray(value)) return emptyUsage();
    const record = value as Record<string, unknown>;
    return {
        usedSets: countField(record.usedSets),
        generatedSets: countField(record.generatedSets),
        attempts: countField(record.attempts),
    };
}

/** 计数字段归一：非数字、非整数、负数、NaN/Infinity 一律按 0 处理。 */
function countField(value: unknown): number {
    return typeof value === 'number' && Number.isSafeInteger(value) && value >= 0 ? value : 0;
}

/** setsPerDay 兜底：非正整数（含 undefined）一律回落到 QUIZ_SETS_PER_DAY。 */
function resolveSetsPerDay(value: number | undefined): number {
    return typeof value === 'number' && Number.isSafeInteger(value) && value > 0
        ? value
        : QUIZ_SETS_PER_DAY;
}

// ==================== D1 原子记账 ====================
//
// KV 的 get→put 读改写不是原子的：两个并发请求都读到同一 used_sets，算出同一
// setIndex → 同一 quizId → 双双跑满一套上游调用，最终写回互相覆盖（attempts/
// generatedSets 丢失），canGenerateSet 预算也被绕过。D1 侧用条件 UPSERT +
// RETURNING 把「分配序号 / 记尝试 / 记成功」变成单语句原子操作，并发请求各拿到
// 不同序号。D1 不可用或语句失败时返回 null，调用方降级回 KV 路径（可用性优先，
// 记账是增强路径，不能让用户拿不到题）。

export interface BankD1Env {
    DB?: D1Database;
    KV?: BankEnv['KV'];
}

export interface BankUsageStore {
    /** 原子分配下一套序号（used_sets+1 并返回新值）。存储不可用返回 null。 */
    allocateSetIndex(friendId: string, date: string): Promise<number | null>;
    /** 原子 attempts+1，返回新值。存储不可用返回 null。 */
    incrementAttempts(friendId: string, date: string): Promise<number | null>;
    /** 原子 generatedSets+1（生成成功后记一笔）。 */
    incrementGeneratedSets(friendId: string, date: string): Promise<void>;
    /** 生成异常时回退 allocate 占掉的号（best-effort，行不存在或已为 0 时无操作）。 */
    rollbackSetIndex(friendId: string, date: string): Promise<void>;
    /** 读当前用量；存储不可用返回 null（调用方降级 KV）。 */
    readUsage(friendId: string, date: string): Promise<BankUsage | null>;
}

const USAGE_TABLE = 'quiz_bank_usage';

export function createD1BankUsageStore(db: D1Database): BankUsageStore {
    return {
        async allocateSetIndex(friendId: string, date: string): Promise<number | null> {
            try {
                // INSERT 分支必须写 used_sets=1：首插行 RETURNING 的就是插入值，
                // 写 0 会让当天第一个请求拿到 null 回退旧值，并发首日仍会撞同一序号
                const row = await db.prepare(
                    `INSERT INTO ${USAGE_TABLE} (friend_id, usage_date, used_sets, generated_sets, attempts, updated_at)
                     VALUES (?1, ?2, 1, 0, 0, ?3)
                     ON CONFLICT(friend_id, usage_date) DO UPDATE SET
                         used_sets = ${USAGE_TABLE}.used_sets + 1,
                         updated_at = excluded.updated_at
                     RETURNING used_sets`,
                ).bind(friendId, date, now()).first<{ used_sets: unknown }>();
                const usedSets = row?.used_sets;
                return typeof usedSets === 'number' && usedSets > 0 ? usedSets : null;
            } catch {
                return null;
            }
        },

        async incrementAttempts(friendId: string, date: string): Promise<number | null> {
            try {
                // 首插写 attempts=1：行不存在时本次就是第一次尝试，不能记 0（预算漏格）
                const row = await db.prepare(
                    `INSERT INTO ${USAGE_TABLE} (friend_id, usage_date, used_sets, generated_sets, attempts, updated_at)
                     VALUES (?1, ?2, 0, 0, 1, ?3)
                     ON CONFLICT(friend_id, usage_date) DO UPDATE SET
                         attempts = ${USAGE_TABLE}.attempts + 1,
                         updated_at = excluded.updated_at
                     RETURNING attempts`,
                ).bind(friendId, date, now()).first<{ attempts: unknown }>();
                const attempts = row?.attempts;
                return typeof attempts === 'number' && attempts >= 0 ? attempts : null;
            } catch {
                return null;
            }
        },

        async incrementGeneratedSets(friendId: string, date: string): Promise<void> {
            try {
                // 首插写 generated_sets=1，理由同 attempts
                await db.prepare(
                    `INSERT INTO ${USAGE_TABLE} (friend_id, usage_date, used_sets, generated_sets, attempts, updated_at)
                     VALUES (?1, ?2, 0, 1, 0, ?3)
                     ON CONFLICT(friend_id, usage_date) DO UPDATE SET
                         generated_sets = ${USAGE_TABLE}.generated_sets + 1,
                         updated_at = excluded.updated_at`,
                ).bind(friendId, date, now()).run();
            } catch {
                // 静默：成功计数丢失只影响统计
            }
        },

        async rollbackSetIndex(friendId: string, date: string): Promise<void> {
            try {
                // 生成异常路径的 best-effort 回退：原语义是生成成功才占号
                await db.prepare(
                    `UPDATE ${USAGE_TABLE}
                     SET used_sets = used_sets - 1, updated_at = ?3
                     WHERE friend_id = ?1 AND usage_date = ?2 AND used_sets > 0`,
                ).bind(friendId, date, now()).run();
            } catch {
                // 静默：回退失败只多占一个序号
            }
        },

        async readUsage(friendId: string, date: string): Promise<BankUsage | null> {
            try {
                const row = await db.prepare(
                    `SELECT used_sets, generated_sets, attempts FROM ${USAGE_TABLE}
                     WHERE friend_id = ?1 AND usage_date = ?2`,
                ).bind(friendId, date).first<{ used_sets: unknown; generated_sets: unknown; attempts: unknown }>();
                if (!row) return emptyUsage();
                return {
                    usedSets: countField(row.used_sets),
                    generatedSets: countField(row.generated_sets),
                    attempts: countField(row.attempts),
                };
            } catch {
                return null;
            }
        },
    };
}

/**
 * 影片的排序键：只取决于 seed 与片名，与片子在列表里的位置无关，
 * 所以「同一批片 + 同一 seed」永远选中同一批（列表顺序变了也不影响抽中集合）。
 */
function movieSortKey(seed: string, title: string): number {
    return mix32(fnv1a32(`${seed}\u0000${title}`));
}

/** 8 位十六进制摘要，用于 quizId 尾缀。 */
function digest8(input: string): string {
    return (mix32(fnv1a32(input)) >>> 0).toString(16).padStart(8, '0');
}

/**
 * FNV-1a 32 位哈希：逐 UTF-16 码元异或后乘 FNV 质数。
 * 用 Math.imul 保证 32 位整数语义（普通乘法在超过 2^53 后会丢精度，结果就不稳定了）。
 */
function fnv1a32(input: string): number {
    let hash = 0x811c9dc5;
    for (let index = 0; index < input.length; index += 1) {
        hash ^= input.charCodeAt(index);
        hash = Math.imul(hash, 0x01000193);
    }
    return hash >>> 0;
}

/**
 * 32 位收尾混淆（murmur3 finalizer 结构）：FNV-1a 对「只差末尾一两个字符」的输入，
 * 低位相关性偏强，而本模块的输入恰好共享很长的前缀（同一用户/日期的 seed），
 * 过一道雪崩让相邻 seed 的排序键充分散开，抽片分布才不会总是贴着同几部片。
 */
function mix32(value: number): number {
    let hash = value >>> 0;
    hash ^= hash >>> 16;
    hash = Math.imul(hash, 0x7feb352d);
    hash ^= hash >>> 15;
    hash = Math.imul(hash, 0x846ca68b);
    hash ^= hash >>> 16;
    return hash >>> 0;
}

/**
 * 解析严格 YYYY-MM-DD 并折算成 UTC 天数；格式不符或日历上不存在都返回 null。
 * 用「回读三个字段」挡掉 2026-02-30 / 2026-13-01 这类会被 Date 悄悄归一化的日期。
 */
function parseIsoDay(value: unknown): number | null {
    if (typeof value !== 'string') return null;
    const match = /^(\d{4})-(\d{2})-(\d{2})$/.exec(value);
    if (!match) return null;

    const year = Number(match[1]);
    const month = Number(match[2]);
    const day = Number(match[3]);
    const time = Date.UTC(year, month - 1, day);
    if (!Number.isFinite(time)) return null;

    const date = new Date(time);
    if (
        date.getUTCFullYear() !== year
        || date.getUTCMonth() !== month - 1
        || date.getUTCDate() !== day
    ) {
        return null;
    }
    return Math.floor(time / DAY_MS);
}
