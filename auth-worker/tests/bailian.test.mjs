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

test('免费额度耗尽的 403 归类为可轮替的上游错误，且带上原文', async () => {
    await withFetch(async () => new Response(JSON.stringify({
        error: { code: 'AllocationQuota.FreeTierOnly', message: 'Free quota exhausted. To continue accessing the model on a paid basis…' },
    }), { status: 403 }), async () => {
        await assert.rejects(
            () => callBailianJson(env, 'qwen3.6-flash', [{ role: 'user', content: 'x' }]),
            error => error.code === 'AI_UPSTREAM_ERROR' && /free quota exhausted/i.test(error.message),
        );
    });
});

test('200 里包免费额度耗尽也要判成失败', async () => {
    const payload = { error: { code: 'AllocationQuota.FreeTierOnly', message: 'Free quota exhausted.' } };
    assert.equal(isFreeQuotaExhausted(payload), true);
    assert.equal(isFreeQuotaExhausted({ error: { code: 'InvalidApiKey', message: 'bad key' } }), false);
    await withFetch(async () => new Response(JSON.stringify(payload), { status: 200 }), async () => {
        await assert.rejects(
            () => callBailianJson(env, 'qwen3.6-flash', [{ role: 'user', content: 'x' }]),
            error => error.code === 'AI_UPSTREAM_ERROR' && /free quota exhausted/i.test(error.message),
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
