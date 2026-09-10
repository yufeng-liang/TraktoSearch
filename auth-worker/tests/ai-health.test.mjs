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

test('probe endpoint returns per-provider result and writes probe events', async () => {
    // 直接调 handleAiHealthProbe（不经 verifyAccessJWT——那是 index.ts 层职责，已有独立层）
    const { handleAiHealthProbe } = await import('../src/admin/ai-health.ts');
    const originalFetch = globalThis.fetch;
    const calls = [];
    globalThis.fetch = async (input, init) => {
        const body = JSON.parse(init.body);
        calls.push({ model: body.model, auth: init.headers });
        // zhipu 200 OK、agnes 200 OK、mimo 402（fetchWithKeyRotation 内部 fetch 同 mock）
        if (body.model?.startsWith('glm') || body.model?.startsWith('agnes')) {
            return new Response(JSON.stringify({ choices: [{ message: { content: 'OK' } }] }), { status: 200 });
        }
        return new Response(JSON.stringify({ error: { message: 'insufficient balance' } }), { status: 402 });
    };
    const statements = [];
    const env = {
        DB: {
            prepare(sql) {
                return {
                    bind(...args) { statements.push({ sql, args }); return { async run() { return { meta: { changes: 1 } }; } }; },
                };
            },
        },
        KV: { async get() { return null; }, async put() {} },
        ZHIPU_API_KEY: 'test-zhipu',
        MIMO_API_KEY: 'test-mimo',
        AGNES_API_KEYS: 'test-agnes-key',
        AI_TEST_MODE: false,
    };
    try {
        const request = new Request('https://gw.test/admin/ai/health/probe', {
            method: 'POST',
            headers: { 'Content-Type': 'application/json' },
            body: JSON.stringify({ all: true }),
        });
        const response = await handleAiHealthProbe(request, env, 'req-probe-1');
        assert.equal(response.status, 200);
        const data = (await response.json()).data;
        const byProvider = Object.fromEntries(data.results.map(r => [r.provider, r]));
        assert.equal(byProvider.zhipu.outcome, 'success');
        assert.equal(byProvider.mimo.outcome, 'upstream_error');
        assert.equal(byProvider.mimo.httpStatus, 402);
        assert.equal(byProvider.agnes.outcome, 'success');
        // 三家各写一条 probe 健康事件
        const probeRows = statements.filter(s => s.sql.includes('ai_health_events'));
        assert.equal(probeRows.length, 3);
        const mimoRow = probeRows.map(r => r.args).find(a => a[3] === 'mimo');
        assert.equal(mimoRow[5], 'upstream_error');
        assert.equal(mimoRow[6], 'AI_UPSTREAM_ERROR');
        assert.equal(mimoRow[7], 402);
    } finally {
        globalThis.fetch = originalFetch;
    }
});

test('probe endpoint validates provider and model params', async () => {
    const { handleAiHealthProbe } = await import('../src/admin/ai-health.ts');
    const env = { DB: { prepare() { throw new Error('no'); } }, KV: { async get() { return null; } } };
    const badProvider = new Request('https://gw.test/admin/ai/health/probe', {
        method: 'POST', headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ provider: 'openai' }),
    });
    const response = await handleAiHealthProbe(badProvider, env, 'req-p2');
    assert.equal(response.status, 400);
    const badModel = new Request('https://gw.test/admin/ai/health/probe', {
        method: 'POST', headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ provider: 'zhipu', model: 'gpt-4o' }),
    });
    const response2 = await handleAiHealthProbe(badModel, env, 'req-p3');
    assert.equal(response2.status, 400);
});

test('health list aggregates by provider and model with window filter', async () => {
    const { handleAiHealthList } = await import('../src/admin/ai-health.ts');
    const queries = [];
    const env = {
        DB: {
            prepare(sql) {
                queries.push(sql);
                return {
                    bind(...args) {
                        return {
                            async all() {
                                if (sql.includes('GROUP BY')) {
                                    return { results: [
                                        { provider: 'zhipu', model: 'glm-5.3-flash', total: 10, success: 9, avg_duration_ms: 3200, p95_duration_ms: 8100 },
                                        { provider: 'mimo', model: 'mimo-v2.5-pro', total: 2, success: 0, avg_duration_ms: 900, p95_duration_ms: 950 },
                                    ] };
                                }
                                if (sql.includes('ORDER BY') && sql.includes('LIMIT')) {
                                    return { results: [
                                        { created_at: 1725900000, source: 'traffic', route: 'quiz', provider: 'mimo', model: 'mimo-v2.5-pro', outcome: 'upstream_error', error_code: 'AI_UPSTREAM_ERROR', http_status: 402, duration_ms: 900, request_id: 'r1' },
                                    ] };
                                }
                                return { results: [] };
                            },
                        };
                    },
                };
            },
        },
        KV: { async get(key) { return key === 'key-pool:agnes' ? JSON.stringify([{ fingerprint: 'a'.repeat(64), status: 'ACTIVE', cooldownUntilMs: 0 }]) : null; } },
    };
    const request = new Request('https://gw.test/admin/ai/health?window=24h', { method: 'GET' });
    const response = await handleAiHealthList(request, env, 'req-l1');
    assert.equal(response.status, 200);
    const data = (await response.json()).data;
    assert.equal(data.window, '24h');
    assert.equal(data.aggregates.length, 2);
    const zhipu = data.aggregates.find(a => a.provider === 'zhipu');
    assert.equal(zhipu.successRate, 0.9);
    assert.equal(data.keyPool.length, 1);
    assert.ok(queries.some(q => q.includes('created_at >= ?')));
    assert.ok(queries.some(q => q.includes('LIMIT 50')));
});

test('health list rejects invalid window', async () => {
    const { handleAiHealthList } = await import('../src/admin/ai-health.ts');
    const env = { DB: { prepare() { throw new Error('no'); } }, KV: { async get() { return null; } } };
    const request = new Request('https://gw.test/admin/ai/health?window=90d', { method: 'GET' });
    const response = await handleAiHealthList(request, env, 'req-l2');
    assert.equal(response.status, 400);
});
