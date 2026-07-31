-- 0002_conversations.sql
-- 反馈系统增强：显示 ID、对话流、未读追踪
--
-- 改动内容：
-- 1. feedbacks 表新增 display_id（FEAT001/BUG001/UX001/OTH001）和 last_read_at（毫秒时间戳）
-- 2. 新建 feedback_seq 序列表（按 type 独立递增 display_id）
-- 3. 新建 feedback_conversations 表（语义升级 feedback_replies，新增 author_role/screenshots/parent_reply_id）
-- 4. 把 feedback_replies 历史数据迁移到 feedback_conversations（author_role='developer'）
-- 5. 回填 display_id（按 type + created_at ASC 排序生成序号）
-- 6. 回填 last_read_at（有回复→最新回复时间；无回复→创建时间）
-- 7. 更新 feedback_seq 为当前最大序号，避免新数据 display_id 冲突

-- ============ 1. ALTER TABLE 新增字段 ============

ALTER TABLE feedbacks ADD COLUMN display_id TEXT;
ALTER TABLE feedbacks ADD COLUMN last_read_at INTEGER NOT NULL DEFAULT 0;

-- ============ 2. 序列表：按类型独立递增 ============

CREATE TABLE IF NOT EXISTS feedback_seq (
    type TEXT PRIMARY KEY,
    seq INTEGER NOT NULL DEFAULT 0
);
INSERT INTO feedback_seq (type, seq) VALUES
    ('FEATURE', 0),
    ('BUG', 0),
    ('UX', 0),
    ('OTHER', 0);

-- ============ 3. 新建 feedback_conversations 表 ============
-- 语义升级 feedback_replies，新增 author_role/screenshots/parent_reply_id
-- 旧表 feedback_replies 在数据迁移后保留（不 DROP），便于回滚查阅

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

-- ============ 4. 迁移 feedback_replies → feedback_conversations ============
-- 历史回复全部视为开发者回复（author_role='developer'），无截图无父回复

INSERT INTO feedback_conversations (id, feedback_id, author_role, content, screenshots, parent_reply_id, created_at)
SELECT id, feedback_id, 'developer', content, NULL, NULL, created_at
FROM feedback_replies
WHERE id NOT IN (SELECT id FROM feedback_conversations);

-- ============ 5. 回填 display_id ============
-- 按 type 分组，按 created_at ASC, id ASC 排序生成序号

WITH ranked AS (
    SELECT
        id,
        CASE type
            WHEN 'FEATURE' THEN 'FEAT'
            WHEN 'BUG' THEN 'BUG'
            WHEN 'UX' THEN 'UX'
            WHEN 'OTHER' THEN 'OTH'
        END || printf('%03d', ROW_NUMBER() OVER (PARTITION BY type ORDER BY created_at ASC, id ASC)) AS new_display_id
    FROM feedbacks
)
UPDATE feedbacks
SET display_id = (
    SELECT new_display_id FROM ranked WHERE ranked.id = feedbacks.id
)
WHERE display_id IS NULL OR display_id = '';

-- 为 display_id 加唯一索引（回填完成后创建）
CREATE UNIQUE INDEX IF NOT EXISTS idx_feedbacks_display_id
    ON feedbacks(display_id) WHERE display_id IS NOT NULL;

-- ============ 6. 回填 last_read_at ============
-- 有回复的反馈：设为最新回复时间（假设用户已读所有历史回复）
-- 无回复的反馈：设为反馈创建时间（用户自己提交，必然已读）

UPDATE feedbacks
SET last_read_at = COALESCE(
    (SELECT MAX(created_at) FROM feedback_conversations WHERE feedback_id = feedbacks.id),
    created_at
)
WHERE last_read_at = 0;

-- ============ 7. 更新 feedback_seq 为当前最大序号 ============
-- 避免新提交的反馈 display_id 与历史数据冲突

UPDATE feedback_seq
SET seq = (
    SELECT COALESCE(MAX(CAST(SUBSTR(display_id, 5) AS INTEGER)), 0)
    FROM feedbacks
    WHERE type = feedback_seq.type
      AND display_id IS NOT NULL
      AND display_id != ''
);

-- ============ 8. 验证查询（仅查询，不修改） ============
-- 执行后可查看结果确认迁移成功
-- SELECT type, seq FROM feedback_seq;
-- SELECT id, type, display_id, last_read_at FROM feedbacks ORDER BY created_at ASC;
-- SELECT id, feedback_id, author_role, created_at FROM feedback_conversations ORDER BY created_at ASC;
