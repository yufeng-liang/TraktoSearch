-- 出题题库用量记账：从 KV 读-改-写迁到 D1 原子 UPSERT。
--
-- KV get+put 非原子：两个并发 /api/ai/quiz/stream 都读到同一 used_sets，算出同一
-- setIndex → 同一 quizId → 双双跑满一套上游调用（17+ 格 LLM 调用白烧），最终写回
-- 互相覆盖（attempts/generatedSets 丢失），canGenerateSet 预算也被绕过。
-- D1 侧用条件 UPSERT + RETURNING 做原子分配，并发请求各拿到不同序号。
-- KV 路径保留为 D1 不可用时的降级（见 quiz-bank.ts）。

CREATE TABLE IF NOT EXISTS quiz_bank_usage (
    friend_id TEXT NOT NULL,
    usage_date TEXT NOT NULL,
    used_sets INTEGER NOT NULL DEFAULT 0,
    generated_sets INTEGER NOT NULL DEFAULT 0,
    attempts INTEGER NOT NULL DEFAULT 0,
    updated_at INTEGER NOT NULL,
    PRIMARY KEY (friend_id, usage_date)
);

CREATE INDEX IF NOT EXISTS idx_quiz_bank_usage_date
    ON quiz_bank_usage(usage_date);
