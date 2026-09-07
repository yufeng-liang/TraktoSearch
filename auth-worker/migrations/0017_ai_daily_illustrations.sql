-- 今日影视知识概念插图状态：以学习单元、语言和风格版本为幂等键。
-- D1 条件 UPSERT 原子领取 generating 租约，避免并发重复生图。
CREATE TABLE IF NOT EXISTS ai_daily_illustrations (
    knowledge_unit_id TEXT NOT NULL,
    locale TEXT NOT NULL,
    image_style_version TEXT NOT NULL,
    status TEXT NOT NULL CHECK (status IN ('generating', 'ready', 'unavailable')),
    attempt_count INTEGER NOT NULL DEFAULT 0 CHECK (attempt_count >= 0),
    object_key TEXT,
    mime_type TEXT,
    width INTEGER,
    height INTEGER,
    size_bytes INTEGER,
    last_error_code TEXT,
    created_at INTEGER NOT NULL,
    updated_at INTEGER NOT NULL,
    PRIMARY KEY(knowledge_unit_id, locale, image_style_version)
);

CREATE INDEX IF NOT EXISTS idx_ai_daily_illustrations_updated_at
    ON ai_daily_illustrations(updated_at);
