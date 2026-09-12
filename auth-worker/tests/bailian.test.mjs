import test from 'node:test';
import assert from 'node:assert/strict';
import {
    callBailianJson,
    validateBailianModel,
    isFreeQuotaExhausted,
    bailianBaseUrl,
    BAILIAN_DEFAULT_BASE_URL,
} from '../src/ai/bailian.ts';
import { handleAiApi } from '../src/ai/handler.ts';

/**
 * 百炼（Model Studio 新加坡）提供商契约。
 *
 * 这里锁的三件事都是实测换来的：关思考（不关单次调用慢一个数量级）、json_object
 * （本区不支持 json_schema，传了会被静默忽略）、免费额度耗尽返 403 时要能被上层
 * 认成「换下一个模型」而不是「这家挂了」。
 */

function sseResponse(chunks) {
    const body = chunks
        .map(chunk => 'data: ' + JSON.stringify(chunk) + '\n\n')
        .join('') + 'data: [DONE]\n\n';
    return new Response(body, { status: 200, headers: { 'Content-Type': 'text/event-stream' } });
}

function chunkOf(content) {
    return { choices: [{ index: 0, delta: { content }, finish_reason: null }] };
}

async function withFetch(impl, run) {
    const original = globalThis.fetch;
    globalThis.fetch = impl;
    try {
        return await run();
    } finally {
        globalThis.fetch = original;
    }
}

const env = { BAILIAN_API_KEY: 'test-bailian-key' };

test('请求体固定带 enable_thinking=false 与 json_object', async () => {
    let captured = null;
    await withFetch(async (url, init) => {
        captured = { url: String(url), body: JSON.parse(init.body), headers: init.headers };
        return new Response(JSON.stringify({ choices: [{ message: { content: '{"ok":true}' } }] }), { status: 200 });
    }, async () => {
        const payload = await callBailianJson(env, 'qwen3.6-flash', [{ role: 'user', content: 'hi' }], { maxCompletionTokens: 700 });
        assert.ok(payload);
    });
    assert.equal(captured.url, BAILIAN_DEFAULT_BASE_URL + '/chat/completions');
    assert.equal(captured.body.model, 'qwen3.6-flash');
    assert.equal(captured.body.enable_thinking, false, '不关思考会让单次调用慢一个数量级');
    assert.deepEqual(captured.body.response_format, { type: 'json_object' });
    assert.equal(captured.body.stream, false);
    assert.equal(captured.body.max_tokens, 700);
    assert.equal(captured.headers.Authorization, 'Bearer test-bailian-key');
});

test('responseFormat=false 时不发 response_format，其余参数照旧', async () => {
    let body = null;
    await withFetch(async (url, init) => {
        body = JSON.parse(init.body);
        return new Response(JSON.stringify({ choices: [{ message: { content: 'ok' } }] }), { status: 200 });
    }, async () => {
        await callBailianJson(env, 'qwen3.6-flash', [{ role: 'user', content: 'x' }], { responseFormat: false });
    });
    assert.equal('response_format' in body, false);
    assert.equal(body.enable_thinking, false);
});

test('流式响应按 SSE 拼回与非流式同形的 payload', async () => {
    let sawStream = null;
    const payload = await withFetch(async (url, init) => {
        sawStream = JSON.parse(init.body).stream;
        return sseResponse([chunkOf('{"title"'), chunkOf(':"一一"}')]);
    }, async () => callBailianJson(
        env,
        'qwen3.6-flash',
        [{ role: 'user', content: 'x' }],
        { stream: true },
    ));
    assert.equal(sawStream, true);
    const choices = payload.choices;
    assert.equal(choices[0].message.content, '{"title":"一一"}');
});

test('思考链 delta.reasoning_content 不混进正文', async () => {
    const payload = await withFetch(async () => sseResponse([
        { choices: [{ delta: { reasoning_content: '先想一下…' }, finish_reason: null }] },
        chunkOf('{"ok":1}'),
        { choices: [{ delta: {}, finish_reason: 'stop' }] },
    ]), async () => callBailianJson(env, 'qwen3.6-flash', [{ role: 'user', content: 'x' }], { stream: true }));
    assert.equal(payload.choices[0].message.content, '{"ok":1}');
    assert.equal(payload.choices[0].finish_reason, 'stop');
});

test('免费额度耗尽的 403 归类为可轮替的专用错误码，且带上原文', async () => {
    await withFetch(async () => new Response(JSON.stringify({
        error: { code: 'AllocationQuota.FreeTierOnly', message: 'Free quota exhausted. To continue accessing the model on a paid basis…' },
    }), { status: 403 }), async () => {
        await assert.rejects(
            () => callBailianJson(env, 'qwen3.6-flash', [{ role: 'user', content: 'x' }]),
            error => error.code === 'AI_QUOTA_EXHAUSTED' && /free quota exhausted/i.test(error.message),
        );
    });
});

test('200 里包免费额度耗尽也要判成失败', async () => {
    const payload = { error: { code: 'AllocationQuota.FreeTierOnly', message: 'Free quota exhausted.' } };
    assert.equal(isFreeQuotaExhausted(payload), true);
    // 实测另一种形态：code/type 都是 insufficient_quota，正文里才有 "Free quota exhausted"
    assert.equal(isFreeQuotaExhausted({ error: { code: 'insufficient_quota', type: 'insufficient_quota', message: 'Free quota exhausted.' } }), true);
    assert.equal(isFreeQuotaExhausted({ error: { code: 'InvalidApiKey', message: 'bad key' } }), false);
    await withFetch(async () => new Response(JSON.stringify(payload), { status: 200 }), async () => {
        await assert.rejects(
            () => callBailianJson(env, 'qwen3.6-flash', [{ role: 'user', content: 'x' }]),
            error => error.code === 'AI_QUOTA_EXHAUSTED' && /free quota exhausted/i.test(error.message),
        );
    });
});

test('5xx 短退避重试一次后仍失败才上抛', async () => {
    let attempts = 0;
    await withFetch(async () => {
        attempts += 1;
        return new Response('upstream boom', { status: 502 });
    }, async () => {
        await assert.rejects(() => callBailianJson(env, 'qwen3.6-flash', [{ role: 'user', content: 'x' }]), /Bailian provider request failed/);
    });
    assert.equal(attempts, 2);
});

test('缺 key 时测试模式返回 null（由上层轮下一家），非测试模式报未配置', async () => {
    assert.equal(await callBailianJson({ AI_TEST_MODE: true }, 'qwen3.6-flash', [{ role: 'user', content: 'x' }]), null);
    await assert.rejects(
        () => callBailianJson({}, 'qwen3.6-flash', [{ role: 'user', content: 'x' }]),
        error => error.code === 'AI_NOT_CONFIGURED',
    );
});

test('模型白名单与 base URL 覆盖', () => {
    assert.equal(validateBailianModel('qwen3.6-flash'), 'qwen3.6-flash');
    // 无免费额度的模型（实测 403 FreeTierOnly）不进白名单
    assert.throws(() => validateBailianModel('glm-5.2-fast-preview'), error => error.code === 'INVALID_MODEL');
    assert.throws(() => validateBailianModel('ZHIPU/GLM-5.3'), error => error.code === 'INVALID_MODEL');
    assert.throws(() => validateBailianModel('no-such-model'), error => error.code === 'INVALID_MODEL');
    assert.equal(bailianBaseUrl({}), BAILIAN_DEFAULT_BASE_URL);
    assert.equal(bailianBaseUrl({ BAILIAN_BASE_URL: 'https://example.test/v1/' }), 'https://example.test/v1');
});

test('免费额度耗尽的模型会被记住，后续请求不再白试它', async () => {
    const { __resetModelQuotaMemo } = await import('../src/ai/model-quota.ts');
    __resetModelQuotaMemo();
    const kvStore = new Map();
    const kv = {
        async get(key) { return kvStore.has(key) ? kvStore.get(key) : null; },
        async put(key, value) { kvStore.set(key, value); },
    };
    const dailyEnv = {
        DB: {
            prepare(sql) {
                const statement = {
                    bind() { return statement; },
                    async run() { return { meta: { changes: 1 } }; },
                    async all() { return { results: [] }; },
                    async first() {
                        if (String(sql).includes('FROM friends')) return { nickname: '小明' };
                        return null;
                    },
                };
                return statement;
            },
        },
        KV: kv,
        AI_TEST_MODE: true,
        AI_TEST_CACHE: new Map(),
        AI_TEST_QUOTA: new Map(),
        AI_DEFAULT_PROVIDER: 'bailian',
        BAILIAN_API_KEY: 'test-bailian-key',
        AI_DAILY_ILLUSTRATION_ENABLED: 'false',
    };
    const calls = [];
    await withFetch(async (url, init) => {
        const body = JSON.parse(init.body);
        calls.push(body.model);
        if (body.model === 'qwen3.6-flash') {
            // 实测形态：免费额度耗尽被包在 200 正文里（另一种是 403 FreeTierOnly）
            return new Response(JSON.stringify({
                error: { code: 'insufficient_quota', message: 'Free quota exhausted. To continue accessing the model on a paid basis, please add funds.' },
            }), { status: 200, headers: { 'Content-Type': 'application/json' } });
        }
        const content = JSON.stringify({
            unitId: 'u_quota_probe',
            version: 1,
            locale: 'zh-CN',
            relationType: 'general_knowledge',
            evidenceMode: 'film_fact',
            subjectGroup: 'film_expression',
            subject: '电影学',
            concept: '片场口令',
            title: '片场口令如何组织协作',
            takeaway: '统一口令把多部门准备压缩成同一瞬间，降低拍摄现场的不确定性。',
            relatedMedia: null,
            filmEvidence: '开机前的部门准备和统一信号，是影片制作资料中可确认的协作方式。',
            explanation: '片场口令是统一信号：片场时间成本高，它让摄影、灯光、表演和声音在同一时刻进入执行状态。',
            realWorldExample: '复杂项目也需要明确职责边界和统一启动信号。',
            boundary: '这是制作历史的来源说明，不同剧组流程会存在差异。',
            difficulty: 'easy',
            spoilerLevel: 'none',
            source: { name: 'AI 综合解读', url: '', evidence: '本节由 AI 综合公开通识整理，未引用具体来源。' },
            checkQuestion: {
                prompt: '统一口令的主要作用是什么？',
                options: [{ id: 'a', text: '让所有人立刻改变立场' }, { id: 'b', text: '让多部门在同一瞬间进入执行状态' }],
                correctOptionIds: ['b'],
                explanation: '片场口令作为协作信号，能让多部门同时进入执行状态。',
            },
            characterLine: '',
        });
        return body.stream === true
            ? sseResponse([chunkOf(content)])
            : new Response(JSON.stringify({ choices: [{ message: { content } }] }), { status: 200 });
    }, async () => {
        const dailyOnce = async sessionId => {
            const response = await handleAiApi(
                new Request('https://gateway.test/api/ai/daily', {
                    method: 'POST',
                    headers: { 'Content-Type': 'application/json' },
                    body: JSON.stringify({ action: 'daily', sessionId, forceRefresh: true, locale: 'zh-CN' }),
                }),
                dailyEnv,
                'request-quota-' + sessionId,
                '/api/ai/daily',
                { sub: 'friend-1', device: 'device-1' },
            );
            return response.json();
        };

        // 第一次：主模型没额度，轮换到下一个模型，并把结论记进 KV。
        const first = await dailyOnce('quota-1');
        assert.equal(first.data.isFallback, false, '第二个模型必须接手生成');
        assert.equal(calls[0], 'qwen3.6-flash');
        assert.equal(calls[1], 'qwen3.7-flash');
        assert.deepEqual(JSON.parse(kvStore.get('ai:model-quota:v1:bailian')), ['qwen3.6-flash']);

        // 第二次（同 isolate）：已耗尽的模型不再出现在候选里，第一次调用直接命中可用模型。
        calls.length = 0;
        await dailyOnce('quota-2');
        assert.equal(calls[0], 'qwen3.7-flash', '已耗尽的模型不得再被尝试');
        assert.ok(!calls.includes('qwen3.6-flash'));

        // 第三次（换 isolate：清空内存记忆）：仍然靠 KV 记住，不再白试一次。
        __resetModelQuotaMemo();
        calls.length = 0;
        await dailyOnce('quota-3');
        assert.equal(calls[0], 'qwen3.7-flash', '跨 isolate 也要靠 KV 记住额度耗尽的模型');
        assert.ok(!calls.includes('qwen3.6-flash'));
    });
    __resetModelQuotaMemo();
});

test('AI_DEFAULT_PROVIDER=bailian 时出题走百炼，且请求带关思考与 json_object', async () => {
    const original = globalThis.fetch;
    const calls = [];
    globalThis.fetch = async (url, init) => {
        const body = JSON.parse(init.body);
        calls.push({ url: String(url), body });
        // 出题链路对百炼走流式，桩必须回 SSE（回普通 JSON 会被读成空正文，转成上游失败轮下一家）
        const content = JSON.stringify({ unit: {} });
        return body.stream === true
            ? sseResponse([chunkOf(content)])
            : new Response(JSON.stringify({ choices: [{ message: { content } }] }), { status: 200 });
    };
    const db = {
        prepare(sql) {
            const statement = {
                bind() { return statement; },
                async run() { return { meta: { changes: 1 } }; },
                async all() { return { results: [] }; },
                async first() {
                    if (String(sql).includes('FROM friends')) return { nickname: '小明' };
                    if (String(sql).includes('FROM ai_usage')) return { session_id: 's', session_count: 1, daily_count: 1 };
                    return null;
                },
            };
            return statement;
        },
    };
    try {
        const watched = Array.from({ length: 7 }, (unused, index) => ({
            title: '电影' + index,
            mediaType: 'movie',
            year: 2000 + index,
            genres: ['剧情'],
            mediaIds: { tmdbId: 2000 + index },
            overview: '第 ' + index + ' 部的简介原文，用来做本地证据校验。',
        }));
        const response = await handleAiApi(
            new Request('https://gateway.test/api/ai/quiz/stream', {
                method: 'POST',
                headers: { 'Content-Type': 'application/json' },
                body: JSON.stringify({ action: 'quiz', watched, questionCount: 13, sessionId: 'session-1', date: '2026-09-10' }),
            }),
            { DB: db, KV: { async get() { return null; }, async put() {} }, AI_DEFAULT_PROVIDER: 'bailian', BAILIAN_API_KEY: 'test-bailian-key' },
            'request-bailian-1',
            '/api/ai/quiz/stream',
            { sub: 'friend-1', device: 'device-1' },
        );
        // 生成跑在流式响应背后的后台任务里：必须把流读到关闭，否则 finally 会在请求发出前还原 fetch
        await response.text();
        const bailianCalls = calls.filter(call => call.url.includes('aliyuncs.com'));
        assert.ok(bailianCalls.length > 0, '默认供应商设为 bailian 后必须真的打到百炼域名');
        assert.equal(bailianCalls[0].body.model, 'qwen3.6-flash');
        assert.equal(bailianCalls[0].body.enable_thinking, false);
        assert.deepEqual(bailianCalls[0].body.response_format, { type: 'json_object' });
        assert.equal(bailianCalls[0].body.stream, true, '出题链路对百炼也应开流式');
    } finally {
        globalThis.fetch = original;
    }
});
