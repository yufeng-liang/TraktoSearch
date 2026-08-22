-- TTS 请求与缓存未命中计数使用 D1 原子更新，避免 KV 读改写在并发下丢失计数。
CREATE TABLE IF NOT EXISTS ai_tts_rate_limits (
    client_key TEXT PRIMARY KEY,
    window_started_at INTEGER NOT NULL,
    request_count INTEGER NOT NULL DEFAULT 0,
    miss_count INTEGER NOT NULL DEFAULT 0,
    updated_at INTEGER NOT NULL
);

CREATE INDEX IF NOT EXISTS idx_ai_tts_rate_limits_updated_at
    ON ai_tts_rate_limits(updated_at);
