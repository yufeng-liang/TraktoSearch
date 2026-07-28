-- 0010_friend_ip_logs.sql
-- 朋友 IP 历史记录 + friends 表冗余字段

ALTER TABLE friends ADD COLUMN last_ip TEXT;
ALTER TABLE friends ADD COLUMN last_ip_geo TEXT;
ALTER TABLE friends ADD COLUMN ip_updated_at INTEGER;

CREATE TABLE IF NOT EXISTS friend_ip_logs (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    friend_id TEXT NOT NULL REFERENCES friends(id),
    ip TEXT NOT NULL,
    country TEXT,
    region TEXT,
    city TEXT,
    latitude TEXT,
    longitude TEXT,
    isp TEXT,
    created_at INTEGER NOT NULL
);
CREATE INDEX IF NOT EXISTS idx_ip_logs_friend ON friend_ip_logs(friend_id, created_at DESC);
