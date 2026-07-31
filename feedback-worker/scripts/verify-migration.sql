-- verify-migration.sql
-- 迁移后验证查询：检查 schema 字段、display_id 回填、author_role 回填、last_read_at 回填、feedback_seq 同步

-- ============ 1. 验证 feedbacks 表新字段 ============
-- 预期：display_id, last_read_at 出现在结果中
PRAGMA table_info(feedbacks);

-- ============ 2. 验证 feedback_conversations 表结构 ============
-- 预期：id, feedback_id, author_role, content, screenshots, parent_reply_id, created_at
PRAGMA table_info(feedback_conversations);

-- ============ 3. 验证 feedback_seq 表 ============
-- 预期：4 行（FEATURE/BUG/UX/OTHER），seq >= 0
SELECT type, seq FROM feedback_seq ORDER BY type;

-- ============ 4. 验证 display_id 回填完整 ============
-- 预期：missing_count 为 0
SELECT COUNT(*) AS missing_count
FROM feedbacks
WHERE display_id IS NULL OR display_id = '';

-- ============ 5. 验证 display_id 唯一性 ============
-- 预期：duplicate_count 为 0
SELECT COUNT(*) AS duplicate_count
FROM (
    SELECT display_id, COUNT(*) AS cnt
    FROM feedbacks
    WHERE display_id IS NOT NULL AND display_id != ''
    GROUP BY display_id
    HAVING cnt > 1
);

-- ============ 6. 验证 display_id 格式 ============
-- 预期：malformed_count 为 0
SELECT COUNT(*) AS malformed_count
FROM feedbacks
WHERE display_id NOT GLOB 'FEAT[0-9][0-9][0-9]'
  AND display_id NOT GLOB 'BUG[0-9][0-9][0-9]'
  AND display_id NOT GLOB 'UX[0-9][0-9][0-9]'
  AND display_id NOT GLOB 'OTH[0-9][0-9][0-9]';

-- ============ 7. 验证 display_id 与 type 一致性 ============
-- 预期：mismatch_count 为 0
SELECT COUNT(*) AS mismatch_count
FROM feedbacks
WHERE (type = 'FEATURE' AND display_id NOT GLOB 'FEAT*')
   OR (type = 'BUG' AND display_id NOT GLOB 'BUG*')
   OR (type = 'UX' AND display_id NOT GLOB 'UX*')
   OR (type = 'OTHER' AND display_id NOT GLOB 'OTH*');

-- ============ 8. 验证 feedback_seq 与 display_id 一致 ============
-- 预期：每个 type 的 seq 等于该 type 下 display_id 序号的最大值
SELECT
    fs.type,
    fs.seq AS seq_in_table,
    COALESCE(
        (SELECT MAX(CAST(SUBSTR(f.display_id, 5) AS INTEGER))
         FROM feedbacks f WHERE f.type = fs.type AND f.display_id IS NOT NULL AND f.display_id != ''),
        0
    ) AS max_display_id_seq
FROM feedback_seq fs
ORDER BY fs.type;

-- ============ 9. 验证 feedback_replies 数据已迁移到 feedback_conversations ============
-- 预期：replies_count == conversations_from_replies_count
SELECT
    (SELECT COUNT(*) FROM feedback_replies) AS replies_count,
    (SELECT COUNT(*) FROM feedback_conversations) AS conversations_count,
    (SELECT COUNT(*) FROM feedback_conversations WHERE id IN (SELECT id FROM feedback_replies)) AS conversations_from_replies_count;

-- ============ 10. 验证 author_role 回填 ============
-- 预期：null_count 为 0；developer_count == conversations_count（历史回复全部开发者）
SELECT
    COUNT(*) AS total_conversations,
    SUM(CASE WHEN author_role IS NULL OR author_role = '' THEN 1 ELSE 0 END) AS null_count,
    SUM(CASE WHEN author_role = 'developer' THEN 1 ELSE 0 END) AS developer_count
FROM feedback_conversations;

-- ============ 11. 验证 last_read_at 回填 ============
-- 预期：zero_count 为 0
SELECT COUNT(*) AS zero_count
FROM feedbacks
WHERE last_read_at = 0;

-- ============ 12. 查看前 10 条 feedbacks 回填结果 ============
SELECT id, type, display_id, created_at, last_read_at
FROM feedbacks
ORDER BY created_at ASC
LIMIT 10;

-- ============ 13. 查看前 10 条 feedback_conversations ============
SELECT id, feedback_id, author_role, content, created_at
FROM feedback_conversations
ORDER BY created_at ASC
LIMIT 10;
