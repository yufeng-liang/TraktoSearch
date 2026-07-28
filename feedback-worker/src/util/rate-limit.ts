// KV 限流器

interface RateLimitEnv {
    KV: KVNamespace;
}

/**
 * 检查限流。
 * @param env 含 KV 的 env
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
    const now = Math.floor(Date.now() / 1000);
    const windowStart = now - (now % windowSeconds);
    const bucketKey = `${key}:${windowStart}`;

    const raw = await env.KV.get(bucketKey);
    const count = raw ? Number.parseInt(raw, 10) : 0;
    if (count >= max) return false;

    await env.KV.put(bucketKey, String(count + 1), {
        expirationTtl: windowSeconds,
    });
    return true;
}
