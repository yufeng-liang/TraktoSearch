-- AI 供应商健康事件：被动记录真实调用 + 主动探针结果，30 天保留
CREATE TABLE IF NOT EXISTS ai_health_events (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    created_at INTEGER NOT NULL,
    source TEXT NOT NULL,
    route TEXT,
    provider TEXT NOT NULL,
    model TEXT NOT NULL,
    outcome TEXT NOT NULL,
    error_code TEXT,
    http_status INTEGER,
    duration_ms INTEGER,
    request_id TEXT
);

CREATE INDEX IF NOT EXISTS idx_ai_health_created
    ON ai_health_events(created_at);
CREATE INDEX IF NOT EXISTS idx_ai_health_provider
    ON ai_health_events(provider, created_at);
