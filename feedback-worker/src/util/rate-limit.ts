// D1 原子限流器（条件 UPSERT），替代 KV 读改写

interface RateLimitEnv {
    DB: D1Database;
}

/**
 * 检查限流。
 *
 * 固定窗口语义与原 KV 实现一致：桶键带窗口起始时间，新窗口自然落到新键。
 * 计数递增与上限判断由单条条件 UPSERT 原子完成，消除 KV 读-判-写非原子的
 * 并发穿透（「每分钟 1 条/每天 10 条」可被并发请求同时读旧值突破）与
 * KV 60 秒最终一致性窗口。
 *
 * @param env 含 DB 的 env
 * @param key 限流键，如 `feedback:submit:${friendId}:minute`
 * @param max 最大次数
 * @param windowSeconds 窗口秒数
 * @returns true=允许，false=拒绝
 */
export async function checkRateLimit(
    env: RateLimitEnv,
    key: string,
    max: number,
    windowSeconds: number
): Promise<boolean> {
    if (!env.DB) return true;
    const currentTime = Math.floor(Date.now() / 1000);
    const windowStart = currentTime - (currentTime % windowSeconds);
    const bucketKey = `${key}:${windowStart}`;

    try {
        const result = await env.DB.prepare(`
            INSERT INTO feedback_rate_limits (bucket_key, count, updated_at)
            VALUES (?, 1, ?)
            ON CONFLICT(bucket_key) DO UPDATE SET
                count = feedback_rate_limits.count + 1,
                updated_at = excluded.updated_at
            WHERE feedback_rate_limits.count < ?
        `).bind(bucketKey, currentTime, max).run();

        // changes=1 说明本次计数生效（新桶插入或窗口内未超限递增）；
        // changes=0 说明窗口内已满（WHERE 不满足），拒绝。
        return Number(result.meta?.changes || 0) === 1;
    } catch {
        // fail-open：限流存储故障时不拦截请求，避免把存储故障放大成 500
        // （修复原 KV put 失败异常上抛的问题）。
        return true;
    }
}
