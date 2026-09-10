import test from 'node:test';
import assert from 'node:assert/strict';
import { recordHealthEvent } from '../src/ai/health.ts';

function createCaptureEnv() {
    const statements = [];
    const waits = [];
    return {
        env: {
            DB: {
                prepare(sql) {
                    return {
                        bind(...args) {
                            statements.push({ sql, args });
                            return { async run() { return { meta: { changes: 1 } }; } };
                        },
                    };
                },
            },
        },
        background: { waitUntil(p) { waits.push(p); } },
        statements,
        waits,
    };
}

test('recordHealthEvent writes success event via waitUntil without blocking', async () => {
    const capture = createCaptureEnv();
    recordHealthEvent(
        capture.env,
        { route: 'quiz', requestId: 'req-1', background: capture.background },
        'traffic', 'zhipu', 'glm-5.3-flash', 'success', undefined, 1234,
    );
    assert.equal(capture.waits.length, 1, 'should schedule exactly one background task');
    await capture.waits[0];
    assert.equal(capture.statements.length, 1);
    const { sql, args } = capture.statements[0];
    assert.match(sql, /INSERT INTO ai_health_events/);
    assert.match(sql, /created_at[\s\S]*source[\s\S]*route[\s\S]*provider[\s\S]*model[\s\S]*outcome/);
    assert.equal(args[2], 'quiz');
    assert.equal(args[3], 'zhipu');
    assert.equal(args[4], 'glm-5.3-flash');
    assert.equal(args[5], 'success');
    assert.equal(args[6], null);        // error_code
    assert.equal(args[7], null);        // http_status
    assert.equal(args[8], 1234);        // duration_ms
    assert.equal(args[9], 'req-1');     // request_id
});

test('recordHealthEvent maps AppError upstream status into http_status', async () => {
    const capture = createCaptureEnv();
    const { AppError } = await import('../src/util/errors.ts');
    recordHealthEvent(
        capture.env,
        { route: 'probe', requestId: 'req-2', background: capture.background },
        'probe', 'mimo', 'mimo-v2.5-pro', 'upstream_error',
        new AppError('AI_UPSTREAM_ERROR', 'AI provider request failed', 502, 402),
        88,
    );
    await capture.waits[0];
    const { args } = capture.statements[0];
    assert.equal(args[5], 'upstream_error');
    assert.equal(args[6], 'AI_UPSTREAM_ERROR');
    assert.equal(args[7], 402, '原始上游状态码必须透传');
});

test('recordHealthEvent swallows DB write failures', async () => {
    const capture = createCaptureEnv();
    capture.env.DB.prepare = () => ({ bind() { return { async run() { throw new Error('d1 down'); } }; } });
    const consoleWarns = [];
    const originalWarn = console.warn;
    console.warn = (...a) => consoleWarns.push(a);
    try {
        recordHealthEvent(
            capture.env,
            { route: 'taste', requestId: 'req-3', background: capture.background },
            'traffic', 'agnes', 'agnes-2.5-flash', 'success',
        );
        await capture.waits[0];
    } finally {
        console.warn = originalWarn;
    }
    assert.equal(consoleWarns.length, 1, '写失败只允许 console.warn');
});

test('recordHealthEvent without background still fires write', async () => {
    const capture = createCaptureEnv();
    recordHealthEvent(
        capture.env,
        { route: 'daily-candidate', requestId: 'req-4', background: undefined },
        'traffic', 'zhipu', 'glm-4.5-air', 'success',
    );
    await new Promise(resolve => setTimeout(resolve, 10));
    assert.equal(capture.statements.length, 1);
});

test('readKeyPoolSnapshot parses KV states with cooldown remaining', async () => {
    const { readKeyPoolSnapshot } = await import('../src/ai/health.ts');
    const nowMs = Date.now();
    const kv = {
        async get(key) {
            assert.equal(key, 'key-pool:agnes');
            return JSON.stringify([
                { fingerprint: 'a'.repeat(64), status: 'ACTIVE', cooldownUntilMs: 0 },
                { fingerprint: 'b'.repeat(64), status: 'COOLING', cooldownUntilMs: nowMs + 120_000 },
            ]);
        },
    };
    const snapshot = await readKeyPoolSnapshot(kv);
    assert.equal(snapshot.length, 2);
    assert.equal(snapshot[0].status, 'ACTIVE');
    assert.equal(snapshot[0].cooldownRemainingSec, 0);
    assert.ok(snapshot[1].cooldownRemainingSec >= 100 && snapshot[1].cooldownRemainingSec <= 120);
    assert.equal(snapshot[1].fingerprint.length, 64);
});

test('readKeyPoolSnapshot returns empty on missing or malformed KV', async () => {
    const { readKeyPoolSnapshot } = await import('../src/ai/health.ts');
    assert.deepEqual(await readKeyPoolSnapshot({ async get() { return null; } }), []);
    assert.deepEqual(await readKeyPoolSnapshot({ async get() { return 'not-json'; } }), []);
});

// 集成用例：handleAiApi -> handleGreeting -> callLlmJson 成功路径，断言被动健康写入恰好 1 条。
test('callLlmJson success path writes exactly one health event', async () => {
    const { handleAiApi } = await import('../src/ai/handler.ts');
    const capture = createCaptureEnv();
    const statements = [];
    const env = {
        ...capture.env,
        KV: { async get() { return null; }, async put() {} },
        JWT_SIGNING_KEY: 'test-jwt-secret',
        AI_TEST_MODE: true,
        AI_TEST_NICKNAME: '小明',
        AI_TEST_CACHE: new Map(),
        AI_TEST_QUOTA: new Map(),
        // handleAiApi 内的 DB 走 capture；健康写入共享同一捕获数组
        DB: {
            prepare(sql) {
                return {
                    bind(...args) {
                        statements.push({ sql, args });
                        return { async run() { return { meta: { changes: 1 } }; } };
                    },
                };
            },
        },
        AI_DEFAULT_PROVIDER: 'agnes',
        AGNES_API_KEYS: 'sk-agnes-test',
    };
    const originalFetch = globalThis.fetch;
    globalThis.fetch = async () => new Response(
        JSON.stringify({
            choices: [{ message: { content: JSON.stringify({ greeting: '你好', nicknameMeaning: '朋友', comment: '欢迎' }) } }],
        }),
        { status: 200, headers: { 'Content-Type': 'application/json' } },
    );
    try {
        const request = new Request('https://gateway.test/api/ai/greeting', {
            method: 'POST',
            headers: { 'Content-Type': 'application/json' },
            body: JSON.stringify({ action: 'greeting', characterId: 'usagi', sessionId: 'health-integration' }),
        });
        const response = await handleAiApi(request, env, 'req-health-1', '/api/ai/greeting', {
            sub: 'friend-1',
            device: 'device-1',
        });
        assert.equal(response.status, 200);
        // 无 ExecutionContext：fire-and-forget，稍等写入落地
        await new Promise(resolve => setTimeout(resolve, 20));
        const healthRows = statements.filter(row => row.sql.includes('INSERT INTO ai_health_events'));
        assert.equal(healthRows.length, 1, '成功路径只应写一条健康事件');
        const args = healthRows[0].args;
        assert.equal(args[2], 'greeting');
        assert.equal(args[3], 'agnes');
        assert.equal(args[4], 'agnes-2.5-flash');
        assert.equal(args[5], 'success');
        assert.equal(args[6], null);
        assert.equal(args[9], 'req-health-1');
    } finally {
        globalThis.fetch = originalFetch;
    }
});
