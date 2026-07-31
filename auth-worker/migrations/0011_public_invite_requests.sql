ALTER TABLE friends ADD COLUMN email TEXT;
ALTER TABLE friends ADD COLUMN signup_request_id TEXT;

CREATE UNIQUE INDEX IF NOT EXISTS idx_friends_signup_request_id
    ON friends(signup_request_id)
    WHERE signup_request_id IS NOT NULL;

CREATE INDEX IF NOT EXISTS idx_friends_email
    ON friends(email);

CREATE TABLE IF NOT EXISTS invite_requests (
    id TEXT PRIMARY KEY,
    nickname TEXT NOT NULL,
    email TEXT NOT NULL,
    email_normalized TEXT NOT NULL,
    verification_token_hash TEXT UNIQUE NOT NULL,
    status TEXT NOT NULL CHECK(status IN ('VERIFICATION_SENT', 'ISSUED', 'EMAIL_FAILED', 'EXPIRED', 'REPLACED')),
    friend_id TEXT REFERENCES friends(id),
    invite_id TEXT REFERENCES invites(id),
    verification_expires_at INTEGER NOT NULL,
    verified_at INTEGER,
    email_sent_at INTEGER,
    created_at INTEGER NOT NULL,
    updated_at INTEGER NOT NULL
);

CREATE INDEX IF NOT EXISTS idx_invite_requests_email
    ON invite_requests(email_normalized, created_at DESC);

CREATE INDEX IF NOT EXISTS idx_invite_requests_status
    ON invite_requests(status, created_at DESC);
