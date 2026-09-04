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

test('missing or invalid default provider stays on Agnes instead of MiMo text', async () => {
    const originalFetch = globalThis.fetch;
    const requests = [];
    globalThis.fetch = async (input, init = {}) => {
        const url = String(input);
        if (url.startsWith('https://api.xiaomimimo.com/')) {
            throw new Error('default text route must not call MiMo');
        }
        requests.push({ url, model: JSON.parse(init.body).model });
        return new Response(
            JSON.stringify({
                choices: [{ message: { content: JSON.stringify({ greeting: '你好', nicknameMeaning: '朋友', comment: '欢迎' }) } }],
            }),
            { status: 200 },
        );
    };

    try {
        for (const [index, provider] of [undefined, '', 'typo'].entries()) {
            const overrides = { AGNES_API_KEYS: AGNES_KEYS };
            if (provider !== undefined) overrides.AI_DEFAULT_PROVIDER = provider;
            const { response } = await call('/api/ai/greeting', {
                body: { characterId: 'usagi', sessionId: `agnes-safe-default-${index}`, forceRefresh: true },
                env: createTestEnv(overrides),
            });
            assert.equal(response.status, 200);
        }
        assert.equal(requests.length, 3);
        assert.ok(requests.every(request => request.url === 'https://apihub.agnes-ai.com/v1/chat/completions'));
        assert.ok(requests.every(request => request.model === 'agnes-2.5-flash'));
    } finally {
        globalThis.fetch = originalFetch;
    }
});

test('taste, quiz, and daily route to Agnes as the default text provider', async () => {
    const originalFetch = globalThis.fetch;
    const requests = [];
    globalThis.fetch = async (input, init = {}) => {
        const url = String(input);
        if (url === 'https://apihub.agnes-ai.com/v1/chat/completions') {
            const requestBody = JSON.parse(init.body);
            const systemPrompt = String(requestBody.messages?.[0]?.content ?? '');
            const route = systemPrompt.includes('影视品味分析助手')
                ? 'taste'
                : systemPrompt.includes('影视知识闯关出题人')
                    ? 'quiz'
                    : systemPrompt.includes('每日影视冷知识编辑')
                        ? 'daily'
                        : null;
            assert.ok(route, 'unexpected Agnes prompt');
            requests.push({
                route,
                model: requestBody.model,
                hasBearer: typeof init.headers?.Authorization === 'string'
                    && init.headers.Authorization.startsWith('Bearer '),
                hasResponseFormat: Object.hasOwn(requestBody, 'response_format'),
            });

            const payload = route === 'taste'
                ? {
                    roast: '片单很有自己的方向。',
                    taste: ['偏爱复杂人物'],
                    recommendations: [{
                        title: '十二怒汉',
                        year: 1957,
                        mediaType: 'movie',
                        reason: '同样重视人物在限制中的选择。',
                    }],
                }
                : route === 'quiz'
                    ? { questions: validAgnesQuizQuestions() }
                    : {
                        title: '电影片场的一个小细节',
                        fact: '电影拍摄现场的简短口令帮助不同工种在同一时刻进入状态。',
                        explanation: '这类口令把复杂协作压缩成所有人都能迅速理解的信号。',
                        sourceName: '示例来源',
                        sourceUrl: 'https://example.com/agnes-daily-source',
                        characterLine: '原来片场还有这个小秘密！',
                    };
            return new Response(JSON.stringify({
                choices: [{ message: { content: JSON.stringify(payload) } }],
            }), { status: 200, headers: { 'Content-Type': 'application/json' } });
        }
        if (url.startsWith('https://api.xiaomimimo.com/')) {
            throw new Error('default text routes must not call MiMo');
        }
        // daily 会额外 HEAD 核验来源链接；测试只需让它可达。
        return new Response(null, { status: 204 });
    };

    try {
        const env = createTestEnv({
            AI_DEFAULT_PROVIDER: 'agnes',
            AGNES_API_KEYS: AGNES_KEYS,
        });
        const watched = agnesWatchedMovies();
        const taste = await call('/api/ai/taste', {
            body: { action: 'taste', sessionId: 'agnes-default-taste', watched, forceRefresh: true },
            env,
        });
        const quiz = await call('/api/ai/quiz', {
            body: { action: 'quiz', sessionId: 'agnes-default-quiz', watched, forceRefresh: true },
            env,
        });
        const daily = await call('/api/ai/daily', {
            body: { action: 'daily', sessionId: 'agnes-default-daily', forceRefresh: true },
            env,
        });

        assert.equal(taste.response.status, 200);
        assert.equal(quiz.response.status, 200);
        assert.equal(daily.response.status, 200);
        assert.deepEqual(requests.map(request => request.route), ['taste', 'quiz', 'daily']);
        assert.ok(requests.every(request => request.model === 'agnes-2.5-flash'));
        assert.ok(requests.every(request => request.hasBearer));
        assert.ok(requests.every(request => !request.hasResponseFormat));
    } finally {
        globalThis.fetch = originalFetch;
    }
});

test('Agnes text upstream failure uses deterministic fallbacks without calling MiMo text', async () => {
    const originalFetch = globalThis.fetch;
    const calls = [];
    let mimoTextRequests = 0;
    globalThis.fetch = async (input, init = {}) => {
        const url = String(input);
        calls.push(url);
        if (url === 'https://apihub.agnes-ai.com/v1/chat/completions') {
            return new Response('Agnes temporarily unavailable', { status: 503 });
        }
        if (url.startsWith('https://api.xiaomimimo.com/')) {
            mimoTextRequests += 1;
            throw new Error('Agnes failure must not spend MiMo text balance');
        }
        // daily 还会额外用 HEAD 检查来源链接。
        return new Response(null, { status: 204 });
    };

    try {
        const paths = [
            ['/api/ai/taste', {
                action: 'taste',
                sessionId: 'agnes-failure-taste',
                watched: agnesWatchedMovies(),
                forceRefresh: true,
            }],
            ['/api/ai/quiz', {
                action: 'quiz',
                sessionId: 'agnes-failure-quiz',
                watched: agnesWatchedMovies(),
                forceRefresh: true,
            }],
            ['/api/ai/daily', {
                action: 'daily',
                sessionId: 'agnes-failure-daily',
                forceRefresh: true,
            }],
        ];
        for (const [path, body] of paths) {
            const { response } = await call(path, {
                body,
                env: createTestEnv({
                    AI_DEFAULT_PROVIDER: 'agnes',
                    AGNES_API_KEYS: AGNES_KEYS,
                    // 即使配置了 MiMo key，也必须证明 Agnes 主路径不会发出 MiMo 文本请求。
                    MIMO_API_KEY: 'sentinel-mimo-text-key',
                }),
            });
            assert.equal(response.status, 200, `${path} should use its deterministic fallback`);
        }
        assert.ok(calls.some(url => url === 'https://apihub.agnes-ai.com/v1/chat/completions'));
        assert.equal(mimoTextRequests, 0);
        assert.equal(calls.some(url => url.startsWith('https://api.xiaomimimo.com/')), false);
    } finally {
        globalThis.fetch = originalFetch;
    }
});

test('Agnes HTTP 200 with invalid JSON or structure uses deterministic route fallbacks', async () => {
    const originalFetch = globalThis.fetch;
    const requests = [];
    let mimoTextRequests = 0;
    globalThis.fetch = async (input, init = {}) => {
        const url = String(input);
        if (url.startsWith('https://api.xiaomimimo.com/')) {
            mimoTextRequests += 1;
            throw new Error('Agnes invalid output must not call MiMo text');
        }
        if (url === 'https://apihub.agnes-ai.com/v1/chat/completions') {
            const requestBody = JSON.parse(init.body);
            const systemPrompt = String(requestBody.messages?.[0]?.content ?? '');
            const route = systemPrompt.includes('影视品味分析助手')
                ? 'taste'
                : systemPrompt.includes('每日影视冷知识编辑')
                    ? 'daily'
                    : 'greeting';
            requests.push(route);
            const content = route === 'greeting'
                ? '{not-json'
                : route === 'taste'
                    ? JSON.stringify({
                        roast: '片单很有方向。',
                        taste: ['偏爱复杂人物'],
                        recommendations: [],
                    })
                    : JSON.stringify({ title: '只有标题' });
            return new Response(JSON.stringify({
                choices: [{ message: { content } }],
            }), { status: 200, headers: { 'Content-Type': 'application/json' } });
        }
        // daily fallback 会核验其固定来源链接；这里只让 HEAD 成功，不提供任何额外上游响应。
        return new Response(null, { status: 204 });
    };

    try {
        const env = createTestEnv({
            AI_DEFAULT_PROVIDER: 'agnes',
            AGNES_API_KEYS: AGNES_KEYS,
            MIMO_API_KEY: 'sentinel-mimo-text-key',
        });
        const greeting = await call('/api/ai/greeting', {
            body: { characterId: 'usagi', sessionId: 'agnes-invalid-greeting', forceRefresh: true },
            env,
        });
        const taste = await call('/api/ai/taste', {
            body: { sessionId: 'agnes-invalid-taste', watched: agnesWatchedMovies(), forceRefresh: true },
            env,
        });
        const daily = await call('/api/ai/daily', {
            body: { sessionId: 'agnes-invalid-daily', forceRefresh: true },
            env,
        });

        assert.equal(greeting.response.status, 200);
        assert.equal(greeting.json.data.comment, '真正能让人记住你的，还是你挑片时留下的细节。');
        assert.equal(taste.response.status, 200);
        assert.deepEqual(taste.json.data.recommendations, []);
        assert.equal(daily.response.status, 200);
        assert.equal(daily.json.data.title, '电影的第一声“Action”');
        assert.deepEqual(requests, ['greeting', 'taste', 'daily']);
        assert.equal(mimoTextRequests, 0);
    } finally {
        globalThis.fetch = originalFetch;
    }
});

test('Agnes text caches use a new version and ignore legacy MiMo or fallback greeting data', async () => {
    const originalFetch = globalThis.fetch;
    let agnesRequests = 0;
    globalThis.fetch = async (input, init = {}) => {
        const url = String(input);
        if (url.startsWith('https://api.xiaomimimo.com/')) {
            throw new Error('legacy cache test must not call MiMo text');
        }
        assert.equal(url, 'https://apihub.agnes-ai.com/v1/chat/completions');
        agnesRequests += 1;
        return new Response(JSON.stringify({
            choices: [{ message: { content: JSON.stringify({
                greeting: 'Agnes 新问候',
                nicknameMeaning: '新的模型结果',
                comment: '不应命中旧缓存。',
            }) } }],
        }), { status: 200, headers: { 'Content-Type': 'application/json' } });
    };

    try {
        const env = createTestEnv({
            AI_DEFAULT_PROVIDER: 'agnes',
            AGNES_API_KEYS: AGNES_KEYS,
            MIMO_API_KEY: 'sentinel-mimo-text-key',
        });
        const legacyKey = 'ai:v1:greeting:friend-1:usagi:text';
        env.AI_TEST_CACHE.set(legacyKey, {
            payload: {
                characterId: 'usagi',
                characterName: '乌萨奇',
                nickname: '小明',
                greeting: '旧 MiMo/旧 fallback 结果',
                spokenText: '旧 MiMo/旧 fallback 结果',
                nicknameMeaning: '旧结果',
                comment: '旧缓存不应返回',
                text: '旧缓存不应返回',
                audio: null,
            },
            expiresAt: Math.floor(Date.now() / 1000) + 3600,
        });

        const result = await call('/api/ai/greeting', {
            body: { characterId: 'usagi', sessionId: 'agnes-cache-version' },
            env,
        });

        assert.equal(result.response.status, 200);
        assert.equal(result.json.data.greeting, 'Agnes 新问候');
        assert.equal(agnesRequests, 1);
        assert.equal(env.AI_TEST_CACHE.has(legacyKey), true);
        assert.ok([...env.AI_TEST_CACHE.keys()].some(key => key.startsWith('ai:v2:greeting:')));
    } finally {
        globalThis.fetch = originalFetch;
    }
});

test('explicit legacy MiMo text request keeps the compatibility fallback to Agnes', async () => {
    const originalFetch = globalThis.fetch;
    const requests = [];
    globalThis.fetch = async (input, init = {}) => {
        const url = String(input);
        const model = JSON.parse(init.body).model;
        requests.push({ url, model });
        if (url.startsWith('https://api.xiaomimimo.com/')) {
            return new Response('MiMo text balance unavailable', { status: 429 });
        }
        return new Response(
            JSON.stringify({
                choices: [{ message: { content: JSON.stringify({ greeting: '兼容成功', nicknameMeaning: '旧请求', comment: '已切到 Agnes' }) } }],
            }),
            { status: 200 },
        );
    };

    try {
        const { response, json } = await call('/api/ai/greeting', {
            body: {
                characterId: 'usagi',
                sessionId: 'legacy-mimo-request',
                model: 'mimo-v2.5',
                forceRefresh: true,
            },
            env: createTestEnv({
                AI_DEFAULT_PROVIDER: 'agnes',
                MIMO_API_KEY: 'test-mimo',
                AGNES_API_KEYS: AGNES_KEYS,
            }),
        });
        assert.equal(response.status, 200);
        assert.equal(json.data.greeting, '兼容成功');
        assert.deepEqual(requests, [
            { url: 'https://api.xiaomimimo.com/v1/chat/completions', model: 'mimo-v2.5' },
            { url: 'https://apihub.agnes-ai.com/v1/chat/completions', model: 'agnes-2.5-flash' },
        ]);
    } finally {
        globalThis.fetch = originalFetch;
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

function agnesWatchedMovies() {
    return Array.from({ length: 7 }, (_, index) => ({
        title: `Agnes Movie ${index + 1}`,
        year: 2020 + index,
        genres: ['Drama'],
        rating: 7,
        userRating: 8,
        watchedAt: `202${index % 4}-01-01`,
        mediaIds: { tmdbId: 100 + index },
    }));
}

function validAgnesQuizQuestions() {
    return Array.from({ length: 13 }, (_, index) => {
        if (index < 10) {
            return {
                id: `agnes-q${index + 1}`,
                type: 'single',
                prompt: '人物如何在处境中作出选择？',
                options: [
                    { id: 'a', text: '只看情节表面。' },
                    { id: 'b', text: '把人物处境和选择放在一起理解。' },
                ],
                correctAnswer: 'b',
                explanation: '人物处境和选择共同构成主题。',
                filmIndex: index % 7,
            };
        }
        if (index < 12) {
            return {
                id: `agnes-q${index + 1}`,
                type: 'multiple',
                prompt: '哪些角度有助于理解作品？',
                options: [
                    { id: 'a', text: '人物处境。' },
                    { id: 'b', text: '现实经验。' },
                    { id: 'c', text: '演员名单。' },
                ],
                correctAnswer: ['a', 'b'],
                explanation: '多角度回看才能形成完整理解。',
                filmIndex: index % 7,
            };
        }
        return {
            id: `agnes-q${index + 1}`,
            type: 'short',
            prompt: '这部作品最值得带回现实的问题是什么？',
            options: [],
            correctAnswer: '人物如何作出选择。',
            explanation: '把银幕经验连接到现实思考。',
            answerKeywords: ['选择', '处境', '现实'],
            filmIndex: index % 7,
        };
    });
}
