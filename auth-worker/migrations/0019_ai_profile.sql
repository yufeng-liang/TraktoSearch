-- AI 观影画像域：所有记录都按 friend_id 隔离，原始来源与派生状态分开保存。
CREATE TABLE IF NOT EXISTS ai_profile_settings (
    friend_id TEXT PRIMARY KEY REFERENCES friends(id) ON DELETE CASCADE,
    profile_consent INTEGER NOT NULL DEFAULT 0 CHECK(profile_consent IN (0, 1)),
    behavior_consent INTEGER NOT NULL DEFAULT 0 CHECK(behavior_consent IN (0, 1)),
    personalization_enabled INTEGER NOT NULL DEFAULT 0 CHECK(personalization_enabled IN (0, 1)),
    sync_enabled INTEGER NOT NULL DEFAULT 1 CHECK(sync_enabled IN (0, 1)),
    consent_version TEXT NOT NULL DEFAULT '1',
    created_at INTEGER NOT NULL,
    updated_at INTEGER NOT NULL
);

CREATE TABLE IF NOT EXISTS ai_profile_media (
    friend_id TEXT NOT NULL REFERENCES friends(id) ON DELETE CASCADE,
    media_key TEXT NOT NULL,
    media_type TEXT NOT NULL CHECK(media_type IN ('movie', 'show')),
    tmdb_id INTEGER,
    imdb_id TEXT,
    trakt_id TEXT,
    douban_id TEXT,
    title TEXT NOT NULL,
    year INTEGER,
    poster_url TEXT,
    genres_json TEXT NOT NULL DEFAULT '[]',
    effective_watchlist INTEGER NOT NULL DEFAULT 0 CHECK(effective_watchlist IN (0, 1)),
    effective_watched INTEGER NOT NULL DEFAULT 0 CHECK(effective_watched IN (0, 1)),
    effective_rating REAL,
    effective_comment_present INTEGER NOT NULL DEFAULT 0 CHECK(effective_comment_present IN (0, 1)),
    last_explicit_at INTEGER,
    last_derived_at INTEGER,
    updated_at INTEGER NOT NULL,
    PRIMARY KEY(friend_id, media_key)
);

CREATE TABLE IF NOT EXISTS ai_profile_media_sources (
    friend_id TEXT NOT NULL REFERENCES friends(id) ON DELETE CASCADE,
    media_key TEXT NOT NULL,
    source TEXT NOT NULL,
    source_media_id TEXT,
    watchlist INTEGER NOT NULL DEFAULT 0 CHECK(watchlist IN (0, 1)),
    watched INTEGER NOT NULL DEFAULT 0 CHECK(watched IN (0, 1)),
    rating REAL,
    rating_scale INTEGER,
    comment_text TEXT,
    watched_at TEXT,
    source_updated_at INTEGER NOT NULL,
    client_updated_at INTEGER,
    tombstone_json TEXT,
    updated_at INTEGER NOT NULL,
    PRIMARY KEY(friend_id, media_key, source),
    FOREIGN KEY(friend_id, media_key)
        REFERENCES ai_profile_media(friend_id, media_key) ON DELETE CASCADE
);

CREATE TABLE IF NOT EXISTS ai_profile_behavior_daily (
    friend_id TEXT NOT NULL REFERENCES friends(id) ON DELETE CASCADE,
    media_key TEXT NOT NULL,
    event_day TEXT NOT NULL,
    detail_dwell_bucket TEXT,
    search_click_count INTEGER NOT NULL DEFAULT 0,
    player_progress_buckets_json TEXT NOT NULL DEFAULT '{}',
    episode_started_count INTEGER NOT NULL DEFAULT 0,
    episode_completed_count INTEGER NOT NULL DEFAULT 0,
    last_event_at INTEGER,
    updated_at INTEGER NOT NULL,
    PRIMARY KEY(friend_id, media_key, event_day)
);

CREATE TABLE IF NOT EXISTS ai_profile_snapshot (
    friend_id TEXT PRIMARY KEY REFERENCES friends(id) ON DELETE CASCADE,
    profile_version INTEGER NOT NULL DEFAULT 0,
    status TEXT NOT NULL DEFAULT 'PENDING' CHECK(status IN ('PENDING', 'READY', 'FAILED')),
    profile_json TEXT,
    source_watermark TEXT,
    generated_at INTEGER,
    updated_at INTEGER NOT NULL
);

CREATE TABLE IF NOT EXISTS ai_profile_feedback (
    friend_id TEXT NOT NULL REFERENCES friends(id) ON DELETE CASCADE,
    media_key TEXT NOT NULL,
    feedback_type TEXT NOT NULL CHECK(feedback_type IN ('NOT_REPRESENTATIVE')),
    created_at INTEGER NOT NULL,
    updated_at INTEGER NOT NULL,
    PRIMARY KEY(friend_id, media_key, feedback_type)
);

CREATE TABLE IF NOT EXISTS ai_profile_sync_batches (
    friend_id TEXT NOT NULL REFERENCES friends(id) ON DELETE CASCADE,
    batch_id TEXT NOT NULL,
    schema_version INTEGER NOT NULL,
    payload_digest TEXT NOT NULL,
    status TEXT NOT NULL CHECK(status IN ('ACCEPTED', 'REJECTED')),
    result_json TEXT NOT NULL,
    created_at INTEGER NOT NULL,
    processed_at INTEGER NOT NULL,
    PRIMARY KEY(friend_id, batch_id)
);

CREATE INDEX IF NOT EXISTS idx_ai_profile_media_friend_updated
    ON ai_profile_media(friend_id, updated_at DESC);
CREATE INDEX IF NOT EXISTS idx_ai_profile_media_friend_type
    ON ai_profile_media(friend_id, media_type);
CREATE INDEX IF NOT EXISTS idx_ai_profile_media_tmdb
    ON ai_profile_media(friend_id, tmdb_id);
CREATE INDEX IF NOT EXISTS idx_ai_profile_media_trakt
    ON ai_profile_media(friend_id, trakt_id);
CREATE INDEX IF NOT EXISTS idx_ai_profile_media_imdb
    ON ai_profile_media(friend_id, imdb_id);
CREATE INDEX IF NOT EXISTS idx_ai_profile_media_douban
    ON ai_profile_media(friend_id, douban_id);
CREATE INDEX IF NOT EXISTS idx_ai_profile_sources_updated
    ON ai_profile_media_sources(friend_id, source_updated_at DESC);
CREATE INDEX IF NOT EXISTS idx_ai_profile_behavior_day
    ON ai_profile_behavior_daily(friend_id, event_day DESC);
CREATE INDEX IF NOT EXISTS idx_ai_profile_sync_batches_created
    ON ai_profile_sync_batches(friend_id, created_at DESC);
