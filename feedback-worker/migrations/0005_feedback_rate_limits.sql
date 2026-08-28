-- 反馈接口原子限流表：替代 KV 读改写（submit/reply/upload-screenshot）。
-- 桶键带固定窗口起始时间，单条条件 UPSERT 完成递增+上限判断，消除并发穿透。
CREATE TABLE IF NOT EXISTS feedback_rate_limits (
    bucket_key TEXT PRIMARY KEY,
    count INTEGER NOT NULL DEFAULT 0,
    updated_at INTEGER NOT NULL
);

CREATE INDEX IF NOT EXISTS idx_feedback_rate_limits_updated_at
    ON feedback_rate_limits(updated_at);
