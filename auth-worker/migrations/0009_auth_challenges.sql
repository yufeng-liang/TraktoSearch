CREATE TABLE IF NOT EXISTS auth_challenges (
    nonce_hash TEXT PRIMARY KEY,
    challenge_type TEXT NOT NULL CHECK(challenge_type IN ('REFRESH', 'RECOVERY')),
    subject TEXT NOT NULL,
    expires_at INTEGER NOT NULL,
    consumed_at INTEGER
);

CREATE INDEX IF NOT EXISTS idx_auth_challenges_expires_at
    ON auth_challenges(expires_at);
