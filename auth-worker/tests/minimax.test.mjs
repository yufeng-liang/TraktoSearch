// MiniMax（api.aiportx.com 网关）接入用例：请求形状、显式模型路由、未配置时的轮替语义。
import test from 'node:test';
import assert from 'node:assert/strict';
import { handleAiApi } from '../src/ai/handler.ts';
import { callMinimaxJson } from '../src/ai/minimax.ts';

function createTestEnv(overrides = {}) {
    const healthInserts = [];
    const db = {
        prepare(sql) {
            if (typeof sql === 'string' && sql.includes('INSERT INTO ai_health_events')) {
                return {
                    bind(...args) {
                        healthInserts.push({ sql, args });
                        return { async run() { return { meta: { changes: 1 } }; } };
                    },
                };
            }
            throw new Error('AI test fallback should be used');
        },
    };
    return {
        DB: db,
        KV: { async get() { return null; }, async put() {} },
        JWT_SIGNING_KEY: 'test-jwt-secret',
        AI_TEST_MODE: true,
        AI_TEST_NICKNAME: '小明',
        AI_TEST_CACHE: new Map(),
        AI_TEST_QUOTA: new Map(),
        healthInserts,
        ...overrides,
    };
}

async function call(path, { body, env } = {}) {
    const request = new Request(`https://gateway.test${path}`, {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify(body),
    });
    const response = await handleAiApi(request, env ?? createTestEnv(), 'request-1', path, {
        sub: 'friend-1',
        device: 'device-1',
    });
    return { response, json: await response.json() };
}

function greetingPayload() {
    return JSON.stringify({
        choices: [{ message: { content: JSON.stringify({ greeting: '你好', nicknameMeaning: '朋友', comment: '欢迎' }) } }],
    });
}

test('callMinimaxJson 发 max_tokens 与 stream:false，默认不带 response_format', async () => {
    let captured = null;
    globalThis.fetch = async (url, init) => {
        captured = { url, auth: init.headers.Authorization, body: JSON.parse(init.body), timeout: init.signal };
        return new Response(greetingPayload(), { status: 200, headers: { 'Content-Type': 'application/json' } });
    };
    try {
        const result = await callMinimaxJson(
            { MINIMAX_API_KEY: 'sk-minimax-test' },
            'MiniMax-M3',
            [{ role: 'user', content: 'hi' }],
            { maxCompletionTokens: 2048 },
        );
        assert.equal(captured.url, 'https://api.aiportx.com/v1/chat/completions');
        assert.equal(captured.auth, 'Bearer sk-minimax-test');
        assert.equal(captured.body.model, 'MiniMax-M3');
        assert.equal(captured.body.max_tokens, 2048);
        assert.equal(captured.body.stream, false);
        assert.equal(Object.hasOwn(captured.body, 'response_format'), false, '网关会静默忽略 response_format，默认不发');
        assert.ok(result.choices[0].message.content);
    } finally {
        globalThis.fetch = undefined;
    }
});

test('白名单只放行 M3：M2 系与其他家的模型名一律拒绝', async () => {
    // 用户 2026-09-12 决定「只取 M3」——M2.x 带 reasoning 且计入 max_tokens，别再悄悄放开
    for (const model of ['agnes-3.0-flash', 'MiniMax-M2.5', 'MiniMax-M2.7-highspeed', 'MiniMax-M2']) {
        await assert.rejects(
            () => callMinimaxJson({ MINIMAX_API_KEY: 'k' }, model, [{ role: 'user', content: 'hi' }]),
            (error) => error.code === 'INVALID_MODEL',
            `${model} 应该被拒`,
        );
    }
});

test('请求里写 MiniMax-M2.5 时不再路由到 minimax（白名单已收窄）', async () => {
    await assert.rejects(
        () => call('/api/ai/greeting', {
            body: { characterId: 'usagi', sessionId: 'minimax-m2-rejected', model: 'MiniMax-M2.5' },
            env: createTestEnv({ MINIMAX_API_KEY: 'sk-minimax-test' }),
        }),
        (error) => error.code === 'INVALID_MODEL',
    );
});

test('供应商优先级 minimax 在 bailian 与 zhipu 之间：百炼没 key 时先轮 minimax 再轮 zhipu', async () => {
    const urls = [];
    globalThis.fetch = async (url) => {
        urls.push(String(url));
        return new Response(greetingPayload(), { status: 200 });
    };
    try {
        const { response, json } = await call('/api/ai/greeting', {
            body: { characterId: 'usagi', sessionId: 'minimax-priority', forceRefresh: true },
            env: createTestEnv({
                AI_DEFAULT_PROVIDER: 'bailian',
                MINIMAX_API_KEY: 'sk-minimax-test',
                ZHIPU_API_KEY: 'sk-zhipu-test',
            }),
        });
        assert.equal(response.status, 200);
        assert.equal(json.data.greeting, '你好');
        // 只发出一次上游请求，且落在 minimax（zhipu 有 key 但排在 minimax 之后，不该被触达）
        assert.deepEqual(urls, ['https://api.aiportx.com/v1/chat/completions']);
    } finally {
        globalThis.fetch = undefined;
    }
});

test('callMinimaxJson 遇 5xx 退避重试一次，第二次成功即返回', async () => {
    const statuses = [];
    globalThis.fetch = async () => {
        statuses.push(1);
        if (statuses.length === 1) return new Response('gateway down', { status: 502 });
        return new Response(greetingPayload(), { status: 200 });
    };
    try {
        const result = await callMinimaxJson(
            { MINIMAX_API_KEY: 'sk-minimax-test' },
            'MiniMax-M3',
            [{ role: 'user', content: 'hi' }],
        );
        assert.equal(statuses.length, 2);
        assert.ok(result.choices[0].message.content);
    } finally {
        globalThis.fetch = undefined;
    }
});

test('4xx 是确定性错误：不重试，直接带上游状态码上抛', async () => {
    let calls = 0;
    globalThis.fetch = async () => { calls += 1; return new Response('bad request', { status: 400 }); };
    try {
        await assert.rejects(
            () => callMinimaxJson({ MINIMAX_API_KEY: 'k' }, 'MiniMax-M3', [{ role: 'user', content: 'hi' }]),
            (error) => error.code === 'AI_UPSTREAM_ERROR' && error.upstreamStatus === 400,
        );
        assert.equal(calls, 1);
    } finally {
        globalThis.fetch = undefined;
    }
});

test('request 里显式写 MiniMax-M3 时路由到 minimax 供应商', async () => {
    let captured = null;
    globalThis.fetch = async (url, init) => {
        captured = { url: String(url), model: JSON.parse(init.body).model };
        return new Response(greetingPayload(), { status: 200 });
    };
    try {
        const { response, json } = await call('/api/ai/greeting', {
            body: { characterId: 'usagi', sessionId: 'minimax-explicit', model: 'MiniMax-M3', forceRefresh: true },
            env: createTestEnv({ AI_DEFAULT_PROVIDER: 'bailian', MINIMAX_API_KEY: 'sk-minimax-test' }),
        });
        assert.equal(response.status, 200);
        assert.equal(json.data.greeting, '你好');
        assert.equal(captured.url, 'https://api.aiportx.com/v1/chat/completions');
        assert.equal(captured.model, 'MiniMax-M3');
    } finally {
        globalThis.fetch = undefined;
    }
});

test('minimax 未配 key 时不报错，按梯队轮到下一家', async () => {
    const urls = [];
    globalThis.fetch = async (url) => {
        urls.push(String(url));
        return new Response(greetingPayload(), { status: 200 });
    };
    try {
        const { response, json } = await call('/api/ai/greeting', {
            body: { characterId: 'usagi', sessionId: 'minimax-unconfigured', forceRefresh: true },
            env: createTestEnv({
                AI_DEFAULT_PROVIDER: 'minimax',
                AGNES_API_KEYS: 'sk-agnes-1',
            }),
        });
        assert.equal(response.status, 200);
        assert.equal(json.data.greeting, '你好');
        // 无 key 的 minimax 直接返回 null（不发请求），落到了配了 key 的 agnes
        assert.ok(urls.every(url => url.startsWith('https://apihub.agnes-ai.com/')), urls.join(','));
    } finally {
        globalThis.fetch = undefined;
    }
});
