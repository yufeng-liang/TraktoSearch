-- 通用 D1 原子限流表：替代 recover/public-invite/legal/feedback 的 KV 读改写。
-- 单条条件 UPSERT 完成窗口重置+计数递增+上限判断，消除并发穿透与 TTL 滑动。
-- 参考 0015_ai_tts_rate_limits.sql 的设计。
CREATE TABLE IF NOT EXISTS rate_limits (
    bucket_key TEXT PRIMARY KEY,
    window_started_at INTEGER NOT NULL,
    count INTEGER NOT NULL DEFAULT 0,
    updated_at INTEGER NOT NULL
);

CREATE INDEX IF NOT EXISTS idx_rate_limits_updated_at
    ON rate_limits(updated_at);
