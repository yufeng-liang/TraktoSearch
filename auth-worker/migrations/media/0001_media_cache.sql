-- 公开影视元数据共享缓存：摘要走 D1，大块 section 走 R2。
CREATE TABLE IF NOT EXISTS media_summary (
    media_type TEXT NOT NULL,
    tmdb_id INTEGER NOT NULL,
    locale TEXT NOT NULL,
    schema_version INTEGER NOT NULL,
    payload_json TEXT NOT NULL,
    title_refreshed_at INTEGER NOT NULL,
    volatile_refreshed_at INTEGER NOT NULL,
    updated_at INTEGER NOT NULL,
    PRIMARY KEY (media_type, tmdb_id, locale, schema_version)
);

CREATE INDEX IF NOT EXISTS idx_media_summary_updated_at
    ON media_summary(updated_at);

CREATE TABLE IF NOT EXISTS media_detail_manifest (
    media_type TEXT NOT NULL,
    tmdb_id INTEGER NOT NULL,
    locale TEXT NOT NULL,
    schema_version INTEGER NOT NULL,
    section TEXT NOT NULL,
    object_key TEXT NOT NULL,
    refreshed_at INTEGER NOT NULL,
    PRIMARY KEY (media_type, tmdb_id, locale, schema_version, section)
);

CREATE INDEX IF NOT EXISTS idx_media_detail_manifest_refreshed_at
    ON media_detail_manifest(refreshed_at);

CREATE TABLE IF NOT EXISTS media_refresh_lease (
    lease_key TEXT PRIMARY KEY,
    expires_at INTEGER NOT NULL
);

CREATE INDEX IF NOT EXISTS idx_media_refresh_lease_expires_at
    ON media_refresh_lease(expires_at);
