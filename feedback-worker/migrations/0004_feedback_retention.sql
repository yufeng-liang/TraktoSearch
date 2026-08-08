-- 记录反馈关闭时间，用于按政策清理已关闭反馈。

ALTER TABLE feedbacks ADD COLUMN closed_at INTEGER;

CREATE INDEX IF NOT EXISTS idx_feedbacks_closed_at
    ON feedbacks(status, closed_at);
