-- 公开法律/隐私请求：仅保留处理请求所需的最少字段。

CREATE TABLE IF NOT EXISTS legal_requests (
    id TEXT PRIMARY KEY,
    request_type TEXT NOT NULL CHECK(request_type IN (
        'PRIVACY_ACCESS',
        'PRIVACY_CORRECTION',
        'PRIVACY_DELETION',
        'COPYRIGHT_NOTICE',
        'OTHER'
    )),
    email TEXT NOT NULL,
    email_normalized TEXT NOT NULL,
    account_reference TEXT,
    description TEXT NOT NULL,
    status TEXT NOT NULL DEFAULT 'OPEN' CHECK(status IN ('OPEN', 'CLOSED')),
    created_at INTEGER NOT NULL,
    updated_at INTEGER NOT NULL,
    closed_at INTEGER,
    closed_note TEXT
);

CREATE INDEX IF NOT EXISTS idx_legal_requests_status_created
    ON legal_requests(status, created_at DESC);

CREATE INDEX IF NOT EXISTS idx_legal_requests_email
    ON legal_requests(email_normalized, created_at DESC);
