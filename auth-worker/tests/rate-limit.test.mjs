import test from 'node:test';
import assert from 'node:assert/strict';
import { consumeRateLimit } from '../src/util/rate-limit.ts';
import { enforcePublicRateLimit } from '../src/invitations.ts';

/** 记录最后一次 prepare 的 SQL 与 bindings，便于断言语句形状 */
function mockDb(options = {}) {
    const calls = [];
    const db = {
        prepare(sql) {
            return {
                bind(...bindings) {
                    calls.push({ sql, bindings });
                    return {
                        async run() {
                            if (options.runError) throw options.runError;
                            return { meta: { changes: options.changes ?? 1 } };
                        },
                    };
                },
            };
        },
    };
    return { db, calls };
}

test('consumeRateLimit 新桶返回 true（放行）', async () => {
    const { db } = mockDb({ changes: 1 });
    assert.equal(await consumeRateLimit(db, 'bucket-a', 5, 300), true);
});

test('consumeRateLimit 窗口内超限返回 false（拒绝）', async () => {
    const { db } = mockDb({ changes: 0 });
    assert.equal(await consumeRateLimit(db, 'bucket-a', 5, 300), false);
});

test('consumeRateLimit DB 异常时 fail-open 放行', async () => {
    const { db } = mockDb({ runError: new Error('d1 unavailable') });
    assert.equal(await consumeRateLimit(db, 'bucket-a', 5, 300), true);
});

test('consumeRateLimit DB 为 null 时放行', async () => {
    assert.equal(await consumeRateLimit(null, 'bucket-a', 5, 300), true);
});

test('consumeRateLimit 使用条件 UPSERT：窗口重置/计数递增/上限判断一条语句完成', async () => {
    const { db, calls } = mockDb({ changes: 1 });
    await consumeRateLimit(db, 'bucket-a', 3, 600);

    assert.equal(calls.length, 1);
    const { sql, bindings } = calls[0];
    assert.match(sql, /INSERT INTO rate_limits/);
    assert.match(sql, /ON CONFLICT\(bucket_key\) DO UPDATE/);
    // 窗口滑动重置
    assert.match(sql, /window_started_at = CASE/);
    // 计数递增
    assert.match(sql, /count = CASE/);
    // 上限判断在 WHERE 里，UPDATE 影响 0 行即拒绝
    assert.match(sql, /WHERE rate_limits\.window_started_at \+ \? <= excluded\.window_started_at\s+OR rate_limits\.count < \?/);
    assert.equal(bindings[0], 'bucket-a');
    assert.equal(bindings[1], bindings[2]); // 初始窗口与当前时间一致
});

test('enforcePublicRateLimit 超限抛 RATE_LIMITED', async () => {
    const { db } = mockDb({ changes: 0 });
    await assert.rejects(
        () => enforcePublicRateLimit(
            { DB: db, PUBLIC_SITE_ORIGIN: 'https://tracktosearch.pages.dev' },
            new Request('https://example.com/api/invite-requests', { method: 'POST' }),
        ),
        error => error?.code === 'RATE_LIMITED' && error?.statusCode === 429,
    );
});

test('enforcePublicRateLimit 桶键按 IP 哈希且带 public-invite 前缀', async () => {
    const { db, calls } = mockDb({ changes: 1 });
    await enforcePublicRateLimit(
        { DB: db, PUBLIC_SITE_ORIGIN: 'https://tracktosearch.pages.dev' },
        new Request('https://example.com/api/invite-requests', {
            method: 'POST',
            headers: { 'CF-Connecting-IP': '203.0.113.9' },
        }),
    );
    assert.equal(calls.length, 1);
    assert.match(calls[0].bindings[0], /^public-invite:ip:[0-9a-f]{64}$/);
    assert.equal(calls[0].bindings[3], 3600); // 窗口 1 小时（bindings: 0=key 1,2=当前时间 3,4,5=窗口 6=上限）
});
