import test from 'node:test';
import assert from 'node:assert/strict';
import { handleAiApi } from '../src/ai/handler.ts';
import { callAgnesJson } from '../src/ai/agnes.ts';

const AGNES_KEYS = 'sk-agnes-1,sk-agnes-2,sk-agnes-3';

function createTestEnv(overrides = {}) {
    return {
        DB: { prepare() { throw new Error('AI test fallback should be used'); } },
        KV: { async get() { return null; }, async put() {} },
        JWT_SIGNING_KEY: 'test-jwt-secret',
        AI_TEST_MODE: true,
        AI_TEST_NICKNAME: '小明',
        AI_TEST_TRANSCRIPT: '乌萨奇',
        AI_TEST_CACHE: new Map(),
        AI_TEST_QUOTA: new Map(),
        ...overrides,
    };
}

async function call(path, { body, env, friendId = 'friend-1', deviceId = 'device-1' } = {}) {
    const request = new Request(`https://gateway.test${path}`, {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify(body),
    });
    const response = await handleAiApi(request, env ?? createTestEnv(), 'request-1', path, {
        sub: friendId,
        device: deviceId,
    });
    const json = await response.json();
    return { response, json };
}

test('Agnes multi-key failover: first key 401 -> retries next key', async () => {
    const authSeen = [];
    globalThis.fetch = async (_url, init) => {
        authSeen.push(init.headers.Authorization);
        if (authSeen.length === 1) {
            return new Response('unauthorized', { status: 401 });
        }
        return new Response(
            JSON.stringify({ choices: [{ message: { content: '{"ok":true}' } }] }),
            { status: 200 },
        );
    };
    try {
        const result = await callAgnesJson(
            { AGNES_API_KEYS: AGNES_KEYS, KV: { async get() { return null; }, async put() {} } },
            'agnes-2.5-flash',
            [{ role: 'user', content: 'hi' }],
        );
        assert.equal(authSeen[0], 'Bearer sk-agnes-1');
        assert.equal(authSeen[1], 'Bearer sk-agnes-2');
        assert.deepEqual(JSON.parse(result.choices[0].message.content), { ok: true });
    } finally {
        globalThis.fetch = undefined;
    }
});

test('Agnes shared rate-limit (all keys 429) -> stable upstream error', async () => {
    globalThis.fetch = async () => new Response('rate limited', { status: 429 });
    try {
        await assert.rejects(
            () => callAgnesJson(
                { AGNES_API_KEYS: AGNES_KEYS, KV: { async get() { return null; }, async put() {} } },
                'agnes-2.5-flash',
                [{ role: 'user', content: 'hi' }],
            ),
            (error) => error.code === 'AI_UPSTREAM_ERROR',
        );
    } finally {
        globalThis.fetch = undefined;
    }
});

test('greeting routes to Agnes when AI_DEFAULT_PROVIDER=agnes', async () => {
    let captured = null;
    globalThis.fetch = async (url, init) => {
        captured = { url, auth: init.headers.Authorization, body: JSON.parse(init.body) };
        return new Response(
            JSON.stringify({
                choices: [{
                    message: { content: JSON.stringify({ greeting: '你好', nicknameMeaning: '朋友', comment: '欢迎' }) },
                }],
            }),
            { status: 200 },
        );
    };
    try {
        const { response, json } = await call('/api/ai/greeting', {
            body: { characterId: 'usagi', sessionId: 'agnes-session' },
            env: createTestEnv({ AI_DEFAULT_PROVIDER: 'agnes', AGNES_API_KEYS: AGNES_KEYS }),
        });
        assert.equal(response.status, 200);
        assert.equal(captured.body.model, 'agnes-2.5-flash');
        assert.match(captured.auth, /^Bearer sk-agnes-/);
        assert.equal(json.data.greeting, '你好');
    } finally {
        globalThis.fetch = undefined;
    }
});

test('explicit agnes model in request overrides default provider', async () => {
    let captured = null;
    globalThis.fetch = async (_url, init) => {
        captured = JSON.parse(init.body).model;
        return new Response(
            JSON.stringify({ choices: [{ message: { content: JSON.stringify({ greeting: 'hi', nicknameMeaning: 'm', comment: 'c' }) } }] }),
            { status: 200 },
        );
    };
    try {
        const { response } = await call('/api/ai/greeting', {
            body: { characterId: 'usagi', sessionId: 'agnes-explicit', model: 'agnes-2.5-flash' },
            env: createTestEnv({ AI_DEFAULT_PROVIDER: 'mimo', MIMO_API_KEY: 'test-mimo', AGNES_API_KEYS: AGNES_KEYS }),
        });
        assert.equal(response.status, 200);
        assert.equal(captured, 'agnes-2.5-flash');
    } finally {
        globalThis.fetch = undefined;
    }
});
