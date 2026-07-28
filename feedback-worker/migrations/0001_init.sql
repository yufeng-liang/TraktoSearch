-- 0001_init.sql
-- 反馈与回复表

CREATE TABLE IF NOT EXISTS feedbacks (
    id TEXT PRIMARY KEY,
    friend_id TEXT NOT NULL,
    friend_nickname TEXT NOT NULL,
    device_id TEXT,
    trakt_username TEXT,
    douban_username TEXT,
    type TEXT NOT NULL CHECK(type IN ('FEATURE','BUG','UX','OTHER')),
    content TEXT NOT NULL,
    contact TEXT,
    screenshots TEXT,
    app_version TEXT NOT NULL,
    os_version TEXT NOT NULL,
    device_model TEXT NOT NULL,
    status TEXT NOT NULL DEFAULT 'PENDING' CHECK(status IN ('PENDING','REPLIED','CLOSED')),
    created_at INTEGER NOT NULL
);

CREATE TABLE IF NOT EXISTS feedback_replies (
    id TEXT PRIMARY KEY,
    feedback_id TEXT NOT NULL REFERENCES feedbacks(id),
    content TEXT NOT NULL,
    created_at INTEGER NOT NULL
);

CREATE INDEX IF NOT EXISTS idx_feedbacks_friend ON feedbacks(friend_id, created_at DESC);
CREATE INDEX IF NOT EXISTS idx_feedbacks_status ON feedbacks(status, created_at DESC);
CREATE INDEX IF NOT EXISTS idx_feedbacks_created ON feedbacks(created_at DESC);
CREATE INDEX IF NOT EXISTS idx_replies_feedback ON feedback_replies(feedback_id, created_at ASC);
