CREATE TABLE IF NOT EXISTS friends (
    id TEXT PRIMARY KEY,
    nickname TEXT NOT NULL,
    note TEXT,
    status TEXT NOT NULL DEFAULT 'ACTIVE' CHECK(status IN ('ACTIVE', 'DISABLED')),
    max_devices INTEGER NOT NULL DEFAULT 2,
    expires_at INTEGER,
    created_at INTEGER NOT NULL,
    updated_at INTEGER NOT NULL
);

CREATE TABLE IF NOT EXISTS invites (
    id TEXT PRIMARY KEY,
    friend_id TEXT NOT NULL REFERENCES friends(id),
    kind TEXT NOT NULL CHECK(kind IN ('ACTIVATION', 'MIGRATION')),
    code_hash TEXT UNIQUE NOT NULL,
    expires_at INTEGER NOT NULL,
    used_at INTEGER,
    revoked_at INTEGER,
    created_at INTEGER NOT NULL
);

CREATE TABLE IF NOT EXISTS devices (
    id TEXT PRIMARY KEY,
    friend_id TEXT NOT NULL REFERENCES friends(id),
    public_key TEXT UNIQUE NOT NULL,
    device_name TEXT,
    status TEXT NOT NULL DEFAULT 'ACTIVE' CHECK(status IN ('ACTIVE', 'REVOKED')),
    app_version TEXT,
    last_seen_at INTEGER,
    activated_at INTEGER NOT NULL,
    revoked_at INTEGER
);

CREATE TABLE IF NOT EXISTS refresh_sessions (
    id TEXT PRIMARY KEY,
    device_id TEXT NOT NULL REFERENCES devices(id),
    token_hash TEXT UNIQUE NOT NULL,
    expires_at INTEGER NOT NULL,
    last_used_at INTEGER,
    revoked_at INTEGER,
    created_at INTEGER NOT NULL
);

CREATE TABLE IF NOT EXISTS trakt_credentials (
    friend_id TEXT PRIMARY KEY REFERENCES friends(id),
    ciphertext TEXT NOT NULL,
    updated_at INTEGER NOT NULL
);

CREATE TABLE IF NOT EXISTS audit_logs (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    event_type TEXT NOT NULL,
    friend_id TEXT,
    device_id TEXT,
    request_id TEXT,
    result TEXT NOT NULL CHECK(result IN ('SUCCESS', 'FAILURE')),
    error_code TEXT,
    created_at INTEGER NOT NULL
);
