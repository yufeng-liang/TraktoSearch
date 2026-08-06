CREATE TABLE IF NOT EXISTS refresh_idempotency (
    attempt_id TEXT PRIMARY KEY,
    device_id TEXT NOT NULL REFERENCES devices(id),
    friend_id TEXT NOT NULL REFERENCES friends(id),
    token_hash TEXT NOT NULL,
    request_id TEXT NOT NULL,
    response_ciphertext TEXT NOT NULL,
    old_session_id TEXT NOT NULL,
    new_session_id TEXT NOT NULL,
    expires_at INTEGER NOT NULL,
    created_at INTEGER NOT NULL
);

CREATE INDEX IF NOT EXISTS idx_refresh_idempotency_expires_at
    ON refresh_idempotency(expires_at);
