import { now } from './errors.ts';

/**
 * D1 原子限流（条件 UPSERT）工具。
 *
 * 替代 KV get→put 读改写：读改写不是原子的，并发请求可同时读到旧值全部放行；
 * 且每次 put 都会重置 TTL，实际窗口被不断拉长。本工具用单条条件 UPSERT 原子
 * 完成「窗口重置 + 计数递增 + 上限判断」，窗口以 Unix 秒为单位（与 errors.now
 * 一致，参考 ai/store.ts:reserveAiTtsCounter 模板）。
 *
 * 语义：
 * - 新桶或窗口过期 → count 置 1，返回 true（放行）
 * - 窗口内 count < limit → count+1，返回 true（放行）
 * - 窗口内 count >= limit → UPDATE 命中 0 行（WHERE 不满足），返回 false（拒绝）
 *
 * 失败策略：DB 不可用或语句异常时 **fail-open**（返回 true 放行）。
 * 限流是防护层而非核心功能，存储故障时放行比把故障放大成 503 更可取
 * （修复原 KV put 失败异常上抛变 500 的问题）。
 */
export async function consumeRateLimit(
    db: D1Database,
    bucketKey: string,
    limit: number,
    windowSeconds: number,
): Promise<boolean> {
    if (!db) return true;
    const currentTime = now();
    try {
        const result = await db.prepare(`
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
            windowSeconds,
            windowSeconds,
            windowSeconds,
            limit,
        ).run();
        return Number(result.meta?.changes || 0) === 1;
    } catch {
        // fail-open：限流存储故障时不拦截请求
        return true;
    }
}
