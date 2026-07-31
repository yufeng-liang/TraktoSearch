-- 0003_conversations.sql
-- 适用于已执行过 0002_feedback_v2.sql 的环境（远程 D1 当前状态）
--
-- 远程 D1 当前状态（已跑 0002_feedback_v2.sql）：
-- - feedbacks 表已有 display_id / last_read_at / is_unread 字段，且 display_id 已回填
-- - feedback_replies 表已有 author_role 字段
-- - 缺少 feedback_conversations 表（代码已切换到该表）
-- - 缺少 feedback_seq 表（submit API 需要原子序号）
--
-- 本迁移补齐：
-- 1. 创建 feedback_conversations 表（与 0002_conversations.sql 一致）
-- 2. 创建 feedback_seq 表并初始化
-- 3. 把 feedback_replies 数据迁移到 feedback_conversations（保留 author_role）
-- 4. 同步 feedback_seq 为当前 display_id 最大序号

-- ============ 1. 新建 feedback_conversations 表 ============

CREATE TABLE IF NOT EXISTS feedback_conversations (
    id TEXT PRIMARY KEY,
    feedback_id TEXT NOT NULL REFERENCES feedbacks(id),
    author_role TEXT NOT NULL DEFAULT 'developer' CHECK(author_role IN ('developer','user')),
    content TEXT NOT NULL,
    screenshots TEXT,
    parent_reply_id TEXT,
    created_at INTEGER NOT NULL
);
CREATE INDEX IF NOT EXISTS idx_conversations_feedback
    ON feedback_conversations(feedback_id, created_at ASC);
CREATE INDEX IF NOT EXISTS idx_conversations_created
    ON feedback_conversations(created_at DESC);
CREATE INDEX IF NOT EXISTS idx_conversations_role
    ON feedback_conversations(feedback_id, author_role, created_at ASC);

-- ============ 2. 创建 feedback_seq 序列表 ============

CREATE TABLE IF NOT EXISTS feedback_seq (
    type TEXT PRIMARY KEY,
    seq INTEGER NOT NULL DEFAULT 0
);
INSERT OR IGNORE INTO feedback_seq (type, seq) VALUES
    ('FEATURE', 0),
    ('BUG', 0),
    ('UX', 0),
    ('OTHER', 0);

-- ============ 3. 迁移 feedback_replies → feedback_conversations ============
-- 保留原 author_role 字段（历史数据全部 'developer'）

INSERT INTO feedback_conversations (id, feedback_id, author_role, content, screenshots, parent_reply_id, created_at)
SELECT id, feedback_id, author_role, content, NULL, NULL, created_at
FROM feedback_replies
WHERE id NOT IN (SELECT id FROM feedback_conversations);

-- ============ 4. 同步 feedback_seq 为当前 display_id 最大序号 ============
-- 远程 display_id 已回填，把 seq 设为当前最大值，避免新数据冲突

UPDATE feedback_seq
SET seq = (
    SELECT COALESCE(MAX(CAST(SUBSTR(display_id, 5) AS INTEGER)), 0)
    FROM feedbacks
    WHERE type = feedback_seq.type
      AND display_id IS NOT NULL
      AND display_id != ''
);

-- ============ 5. 验证查询（仅查询，不修改） ============
-- SELECT type, seq FROM feedback_seq ORDER BY type;
-- SELECT id, feedback_id, author_role, created_at FROM feedback_conversations ORDER BY created_at ASC;
