import test from 'node:test';
import assert from 'node:assert/strict';
import { handleAiApi } from '../src/ai/handler.ts';
import { callAgnesJson } from '../src/ai/agnes.ts';

const AGNES_KEYS = 'sk-agnes-1,sk-agnes-2,sk-agnes-3';

function createTestEnv(overrides = {}) {
    const healthInserts = [];
    const db = {
        prepare(sql) {
            // 健康事件表：接住 INSERT 并按 bind 顺序捕获，供用例断言写入行；
            // 其他 SQL 维持原行为（直接 throw，证明测试模式不应触达它们）。
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
        AI_TEST_TRANSCRIPT: '乌萨奇',
        AI_TEST_CACHE: new Map(),
        AI_TEST_QUOTA: new Map(),
        healthInserts,
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

function validAgnesDailyKnowledgeUnit() {
    return {
        unitId: 'agnes-daily-unit',
        version: 1,
        locale: 'zh-CN',
        relationType: 'general_knowledge',
        evidenceMode: 'external_fact',
        subjectGroup: 'history_and_culture',
        subject: '历史',
        concept: '片场口令',
        title: '片场口令如何组织协作',
        takeaway: '统一口令把多部门准备压缩成同一瞬间，降低拍摄现场的不确定性。',
        relatedMedia: null,
        filmEvidence: '开机前的部门准备和统一信号，是影片制作资料中可确认的协作方式。',
        explanation: '片场时间成本高，统一信号让摄影、灯光、表演和声音在同一时刻进入执行状态。',
        realWorldExample: '复杂项目也需要明确职责边界和统一启动信号。',
        boundary: '这是制作历史的来源说明，不同剧组流程会存在差异。',
        difficulty: 'easy',
        spoilerLevel: 'none',
        source: {
            name: 'Example',
            url: 'https://www.britannica.com/agnes-daily-source',
            evidence: '来源介绍电影制作协作与技术流程。',
        },
        checkQuestion: {
            prompt: '统一口令的主要作用是什么？',
            options: [
                { id: 'a', text: '让所有人立刻改变立场。' },
                { id: 'b', text: '让多部门在同一瞬间进入执行状态。' },
                { id: 'c', text: '证明流程一定正确。' },
            ],
            correctOptionIds: ['b'],
            explanation: '片场口令作为协作信号，能让多部门同时进入执行状态。',
        },
        characterLine: '原来片场还有这个小秘密！',
    };
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
                : systemPrompt.includes('影视知识闯关')
                    ? 'quiz'
                    : systemPrompt.includes('今日影视知识')
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
                    ? requestBody.messages?.[0]?.content.includes('候选学习单元编辑')
                        ? validAgnesQuizUnits()
                        : { questions: validAgnesQuizQuestions() }
                    : validAgnesDailyKnowledgeUnit();
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
        // quiz 走两阶段（候选学习单元 + 二审转换）。units 校验失败后跨供应商轮替：
        // zhipu/mimo 未配 key（AI_TEST_MODE 下不可用）轮空，agnes 第二次兜住又输出不合格
        // units，三轮全失败走确定性兜底——Agnes 共收到两次 quiz-units 请求。
        assert.deepEqual(requests.map(request => request.route), ['taste', 'quiz', 'quiz', 'daily', 'daily']);
        assert.ok(requests.every(request => request.model === 'agnes-2.5-flash'));
        assert.ok(requests.every(request => request.hasBearer));
        assert.ok(requests.every(request => !request.hasResponseFormat));
        // 等所有 fire-and-forget 健康写入落地后再断言
        await new Promise(resolve => setTimeout(resolve, 20));
        // 健康事件链路（与轮替实际路径一致；route 来自 healthCtx.route，统一为业务路由名）：
        // - taste 成功 1 条；
        // - quiz 第一轮 agnes units 成功、二审结构不合格后第二轮 mimo（TEST_MODE 无 key，null=不可用）
        //   写 upstream_error、再回 agnes units 成功——quiz 路由共 3 条；
        // - daily 两阶段（daily-candidate/daily-review）各 1 条。
        // zhipu 未配 key 且在 ladder 中位置靠后，本轮未触达，故无 zhipu 行。
        const healthRows = env.healthInserts.map(row => row.args);
        assert.ok(healthRows.some(r => r[2] === 'taste' && r[3] === 'agnes' && r[5] === 'success'));
        const quizRows = healthRows.filter(r => r[2] === 'quiz');
        assert.equal(quizRows.length, 3, 'quiz 路由：agnes×2 成功 + mimo×1 不可用');
        assert.equal(quizRows.filter(r => r[3] === 'agnes' && r[4] === 'agnes-2.5-flash' && r[5] === 'success').length, 2);
        assert.ok(quizRows.some(r => r[3] === 'mimo' && r[5] === 'upstream_error' && r[6] === 'AI_UPSTREAM_ERROR'));
        const dailyRows = healthRows.filter(r => r[2] === 'daily-candidate' || r[2] === 'daily-review');
        assert.equal(dailyRows.length, 2, 'daily 两阶段各写一条健康事件');
        assert.ok(dailyRows.every(r => r[3] === 'agnes' && r[5] === 'success'));
        // 每行都必须携带 request_id，且 duration_ms 为实测数值或 null
        assert.ok(healthRows.every(r => typeof r[9] === 'string' && r[9].length > 0));
        assert.ok(healthRows.every(r => r[8] === null || typeof r[8] === 'number'));
        assert.equal(healthRows.length, 6);
    } finally {
        globalThis.fetch = originalFetch;
        await new Promise(resolve => setTimeout(resolve, 20));
    }
});

test('Agnes text upstream failure falls back to MiMo text once, then deterministic output', async () => {
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
            // Agnes 失败后回退 MiMo 一次是预期行为（限频是暂态）；这里让 MiMo 也失败，
            // 验证两家都失败时仍走业务确定性兜底且不抛错。
            mimoTextRequests += 1;
            return new Response('MiMo also unavailable', { status: 503 });
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
        // 三条路由（taste/quiz/daily）各自先打 Agnes、失败后各回退 MiMo；
        // quiz 走两段（候选单元 + 二审），taste 无缓存击穿时也可能多次触发，
        // 因此不锁精确次数，只要求「确实发生了 MiMo 回退」且最终走确定性兜底。
        assert.ok(mimoTextRequests >= 3);
        assert.ok(calls.some(url => url.startsWith('https://api.xiaomimimo.com/')));
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
                : systemPrompt.includes('今日影视知识')
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
        assert.match(daily.json.data.unitId, /^seed-/);
        assert.match(daily.json.data.sourceUrl, /^https?:\/\//);
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
        // mimo 主路径 429 后按 agnes > zhipu > mimo 轮替；zhipu 梯队内再按模型级降级链
        // 逐个尝试（5.3-flash → 4.6v → 4.5-air → 4.7 → 4.7-flash），全部失败才轮下一供应商。
        // 本测试 mock 里所有非 mimo 请求都成功，因此第一个 zhipu 模型 5.3-flash 即成功。
        assert.deepEqual(requests, [
            // 主路径 mimo：显式模型失败后先试 mimo 家内降级（v2.5-pro），再轮 agnes
            { url: 'https://api.xiaomimimo.com/v1/chat/completions', model: 'mimo-v2.5' },
            { url: 'https://api.xiaomimimo.com/v1/chat/completions', model: 'mimo-v2.5-pro' },
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

function validAgnesQuizUnits() {
    const specs = [
        { unitId: 'agnes-unit-a', title: 'Agnes Movie 1', subject: '心理学', concept: '归因偏差' },
        { unitId: 'agnes-unit-b', title: 'Agnes Movie 2', subject: '社会学', concept: '社会规范' },
        { unitId: 'agnes-unit-c', title: 'Agnes Movie 3', subject: '历史', concept: '历史语境' },
    ];
    const units = specs.map((spec, index) => ({
        unitId: spec.unitId,
        version: 1,
        locale: 'zh-CN',
        relationType: 'direct_watch',
        evidenceMode: 'viewing_interpretation',
        subjectGroup: spec.subject === '心理学' ? 'people_and_mind' : spec.subject === '社会学' ? 'society_and_institution' : 'history_and_culture',
        subject: spec.subject,
        concept: spec.concept,
        title: `${spec.title} 里如何观察${spec.concept}`,
        takeaway: `观察${spec.title}时，先用可核验材料说话，再谈${spec.concept}的解释。`,
        relatedMedia: { title: spec.title, mediaType: 'movie', tmdbId: 100 + index },
        filmEvidence: `《${spec.title}》上映年份：${2020 + index}，类型：Drama，简介：观看记录可用于讨论${spec.concept}。`,
        explanation: `结合${spec.title}的观看记录，${spec.concept}要求先确认可观察线索，再给出有限解释。`,
        realWorldExample: `讨论现实议题时，同样先核对事实再套用${spec.concept}。`,
        boundary: '这是基于观看记录的入门解读，不是对影片的权威结论。',
        difficulty: 'medium',
        spoilerLevel: 'none',
        source: {
            name: 'Example',
            url: 'https://www.britannica.com/example',
            evidence: `该资料介绍${spec.concept}的基本含义与适用条件。`,
        },
        checkQuestion: {
            prompt: `${spec.concept}强调什么？`,
            options: [
                { id: 'a', text: '先看可观察证据，再给出有限解释。' },
                { id: 'b', text: '直接下最终结论。' },
            ],
            correctOptionIds: ['a'],
            explanation: `${spec.concept}要求先确认证据再解释，所以选 a。`,
        },
        characterLine: null,
    }));
    return { units };
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
