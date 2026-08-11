-- AI 缓存按账号隔离；daily 记录可由所有授权用户复用，friend_id 为空。
CREATE TABLE IF NOT EXISTS ai_cache (
    cache_key TEXT PRIMARY KEY,
    friend_id TEXT REFERENCES friends(id),
    kind TEXT NOT NULL,
    payload_json TEXT NOT NULL,
    expires_at INTEGER NOT NULL,
    created_at INTEGER NOT NULL,
    updated_at INTEGER NOT NULL
);

CREATE INDEX IF NOT EXISTS idx_ai_cache_friend_kind
    ON ai_cache(friend_id, kind, expires_at);

-- 同一账号、UTC 日期只有一行；UPSERT 的 WHERE 条件原子限制会话与日配额。
CREATE TABLE IF NOT EXISTS ai_usage (
    friend_id TEXT NOT NULL REFERENCES friends(id),
    device_id TEXT NOT NULL REFERENCES devices(id),
    usage_day TEXT NOT NULL,
    session_id TEXT NOT NULL,
    session_count INTEGER NOT NULL DEFAULT 0,
    daily_count INTEGER NOT NULL DEFAULT 0,
    updated_at INTEGER NOT NULL,
    PRIMARY KEY(friend_id, usage_day)
);

CREATE INDEX IF NOT EXISTS idx_ai_usage_day
    ON ai_usage(friend_id, usage_day);
