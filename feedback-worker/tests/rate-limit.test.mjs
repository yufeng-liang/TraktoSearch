// tests/rate-limit.test.mjs
import { test } from 'node:test';
import assert from 'node:assert/strict';
import { checkRateLimit } from '../src/util/rate-limit.ts';

function mockDb(changes, { runError } = {}) {
    const calls = [];
    const db = {
        prepare(sql) {
            return {
                bind(...bindings) {
                    calls.push({ sql, bindings });
                    return {
                        async run() {
                            if (runError) throw runError;
                            return { meta: { changes } };
                        },
                    };
                },
            };
        },
    };
    return { db, calls };
}

test('checkRateLimit 新窗口放行（changes=1）', async () => {
    const { db } = mockDb(1);
    assert.equal(await checkRateLimit({ DB: db }, 'feedback:submit:u1:minute', 1, 60), true);
});

test('checkRateLimit 窗口内超限拒绝（changes=0）', async () => {
    const { db } = mockDb(0);
    assert.equal(await checkRateLimit({ DB: db }, 'feedback:submit:u1:minute', 1, 60), false);
});

test('checkRateLimit DB 异常 fail-open 放行', async () => {
    const { db } = mockDb(0, { runError: new Error('d1 down') });
    assert.equal(await checkRateLimit({ DB: db }, 'feedback:submit:u1:minute', 1, 60), true);
});

test('checkRateLimit DB 为 null 放行', async () => {
    assert.equal(await checkRateLimit({ DB: null }, 'feedback:submit:u1:minute', 1, 60), true);
});

test('checkRateLimit 桶键带固定窗口起始且用条件 UPSERT', async () => {
    const { db, calls } = mockDb(1);
    await checkRateLimit({ DB: db }, 'feedback:submit:u1:day', 10, 86400);

    assert.equal(calls.length, 1);
    const { sql, bindings } = calls[0];
    assert.match(sql, /INSERT INTO feedback_rate_limits/);
    assert.match(sql, /ON CONFLICT\(bucket_key\) DO UPDATE/);
    assert.match(sql, /count = feedback_rate_limits\.count \+ 1/);
    assert.match(sql, /WHERE feedback_rate_limits\.count < \?/);
    // 桶键 = key:窗口起始（对齐到 86400 秒边界）
    assert.match(bindings[0], /^feedback:submit:u1:day:\d+$/);
    const windowStart = Number(bindings[0].split(':').pop());
    assert.equal(windowStart % 86400, 0);
    assert.equal(bindings[2], 10); // 上限
});
