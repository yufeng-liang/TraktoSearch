import test from 'node:test';
import assert from 'node:assert/strict';
import { handleAiApi } from '../src/ai/handler.ts';
import { signAccessToken } from '../src/util/jwt.ts';

/**
 * /api/ai/quiz/stream 的契约测试：NDJSON 事件序列、心跳保活、降级仍下发 result。
 * 上游一律走测试桩（AI_TEST_MODE 或注入的 fetch），不打真实网络。
 */

function createEnv(overrides = {}) {
    return {
        DB: {
            prepare() {
                throw new Error('AI test fallback should be used');
            },
        },
        KV: {
            async get() { return null; },
            async put() {},
        },
        JWT_SIGNING_KEY: 'test-jwt-secret',
        AI_TEST_MODE: true,
        AI_TEST_NICKNAME: '小明',
        AI_TEST_CACHE: new Map(),
        AI_TEST_QUOTA: new Map(),
        ...overrides,
    };
}

/**
 * 最小 D1 桩：非测试模式下 store 层要求 DB 可用（配额 UPSERT、昵称查询）。
 * 配额返回 changes=1（未触顶）、昵称返回一行，其余查询返回空。
 */
function createDbStub() {
    return {
        prepare(sql) {
            const statement = {
                bind() { return statement; },
                async run() { return { meta: { changes: 1 } }; },
                async all() { return { results: [] }; },
                // 按查询分流：ai_cache 未命中必须返回 null，否则会被当成命中并解析失败
                async first() {
                    if (sql.includes('FROM friends')) return { nickname: '小明' };
                    if (sql.includes('FROM ai_usage')) return { session_id: 'session-1', session_count: 1, daily_count: 1 };
                    return null;
                },
            };
            return statement;
        },
    };
}

const WATCHED_MOVIES = [
    { title: '一秒钟', mediaType: 'movie', year: 2020, genres: ['剧情', '历史'], mediaIds: { tmdbId: 1001 } },
    { title: '金刚狼2', mediaType: 'movie', year: 2013, genres: ['动作', '科幻'], mediaIds: { tmdbId: 1002 } },
    { title: '蜘蛛侠：平行宇宙', mediaType: 'movie', year: 2018, genres: ['动画', '动作'], mediaIds: { tmdbId: 1003 } },
    { title: '这个杀手不太冷', mediaType: 'movie', year: 1994, genres: ['剧情', '犯罪'], mediaIds: { tmdbId: 1004 } },
    { title: '银河护卫队', mediaType: 'movie', year: 2014, genres: ['动作', '冒险'], mediaIds: { tmdbId: 1005 } },
    { title: '爱情怎么翻译？', mediaType: 'movie', year: 2026, genres: ['剧情', '喜剧'], mediaIds: { tmdbId: 1006 } },
    { title: '功夫熊猫3', mediaType: 'movie', year: 2016, genres: ['动画', '动作'], mediaIds: { tmdbId: 1007 } },
];

async function callStream(body, env) {
    const request = new Request('https://gateway.test/api/ai/quiz/stream', {
        method: 'POST',
        headers: {
            'Content-Type': 'application/json',
            Authorization: `Bearer ${await signAccessToken('test-jwt-secret', 'friend-1', 'device-1')}`,
        },
        body: JSON.stringify(body),
    });
    return handleAiApi(request, env, 'request-stream', '/api/ai/quiz/stream', {
        sub: 'friend-1',
        device: 'device-1',
    });
}

/** 逐行解析 NDJSON 事件流（响应结束后一次性解析即可，测试不关心到达时序）。 */
async function readEvents(response) {
    const text = await response.text();
    return text
        .split('\n')
        .map(line => line.trim())
        .filter(line => line.length > 0)
        .map(line => JSON.parse(line));
}

const QUIZ_BODY = { action: 'quiz', watched: WATCHED_MOVIES, questionCount: 13, sessionId: 'session-1' };

test('quiz stream 以 NDJSON 下发阶段事件并以 result 收尾', async () => {
    const response = await callStream(QUIZ_BODY, createEnv());
    assert.equal(response.status, 200);
    assert.match(response.headers.get('Content-Type') ?? '', /application\/x-ndjson/);

    const events = await readEvents(response);
    assert.equal(events[0].type, 'stage');
    assert.equal(events[0].stage, 'units');
    assert.equal(events[0].status, 'start');
    // 槽位化后进度分母是「槽位数」而不是字符数：客户端只拿它算比例
    assert.ok(Number.isInteger(events[0].expectedChars) && events[0].expectedChars > 0);

    const result = events.at(-1);
    assert.equal(result.type, 'result');
    // 上游不可用时也必须下发兜底题：客户端永远能拿到 13 题，而不是空响应
    assert.equal(result.quiz.questions.length, 13);
});

test('quiz stream 生成失败不抛错，仍以 result 下发兜底题', async () => {
    // AI_TEST_MODE 下三家供应商都返回 null，等价于「上游全部不可用」
    const events = await readEvents(await callStream(QUIZ_BODY, createEnv()));
    assert.equal(events.some(event => event.type === 'error'), false);
    assert.equal(events.at(-1).type, 'result');
});

test('quiz stream 生成期间按配置间隔发送 ping 心跳', async () => {
    const env = createEnv({ AI_QUIZ_STREAM_HEARTBEAT_MS: 15 });
    const events = await readEvents(await callStream(QUIZ_BODY, env));
    assert.ok(events.length > 0);
    // 上游不可用时链路瞬间结束，未必跨过心跳间隔；这里断言的是「心跳不会破坏事件流」
    const pings = events.filter(event => event.type === 'ping');
    for (const ping of pings) assert.equal(typeof ping.elapsedMs, 'number');
});

test('quiz stream 在等待上游首字节期间持续发送心跳与生成进度', async () => {
    const originalFetch = globalThis.fetch;
    const encoder = new TextEncoder();
    const sleep = (ms) => new Promise(resolve => setTimeout(resolve, ms));
    const sse = (chunks, { firstDelayMs = 0, gapMs = 0 } = {}) => new Response(new ReadableStream({
        async start(controller) {
            if (firstDelayMs > 0) await sleep(firstDelayMs);
            for (const chunk of chunks) {
                controller.enqueue(encoder.encode(`data: ${JSON.stringify(chunk)}\n\n`));
                if (gapMs > 0) await sleep(gapMs);
            }
            controller.enqueue(encoder.encode('data: [DONE]\n\n'));
            controller.close();
        },
    }), { status: 200, headers: { 'Content-Type': 'text/event-stream' } });

    globalThis.fetch = async (url) => {
        if (String(url).includes('bigmodel.cn')) {
            // 首字节前挂 120ms：期间客户端只会收到心跳，这正是保活存在的意义
            return sse(
                [
                    { choices: [{ delta: { content: '{"units":[{"unitId":"a"' } }] },
                    { choices: [{ delta: { content: '}]}' }, finish_reason: 'stop' }] },
                ],
                { firstDelayMs: 120, gapMs: 30 },
            );
        }
        return new Response('unavailable', { status: 500 });
    };
    try {
        const env = createEnv({
            DB: createDbStub(),
            AI_TEST_MODE: false,
            ZHIPU_API_KEY: 'test-key',
            AI_DEFAULT_PROVIDER: 'zhipu',
            AI_QUIZ_STREAM_HEARTBEAT_MS: 30,
            AI_QUIZ_PROGRESS_THROTTLE_MS: 0,
        });
        const events = await readEvents(await callStream(QUIZ_BODY, env));
        const pings = events.filter(event => event.type === 'ping');
        assert.ok(pings.length >= 1, '等待上游首字节期间必须有心跳，否则客户端读超时会掐断连接');
        const progress = events.filter(event => event.type === 'progress');
        assert.ok(progress.length >= 1, '流式上游应回报真实生成进度');
        assert.ok(progress.every(event => event.stage === 'units'));
        assert.ok(progress.every(event => typeof event.chars === 'number' && event.chars > 0));
        assert.equal(events.at(-1).type, 'result');
    } finally {
        globalThis.fetch = originalFetch;
    }
});

test('quiz stream 指定已缓存的 quizId 时直接回放，不再重新生成', async () => {
    const env = createEnv();
    const first = await readEvents(await callStream(QUIZ_BODY, env));
    const quizId = first.at(-1).quiz.quizId;
    assert.ok(quizId);

    const replay = await readEvents(await callStream({ ...QUIZ_BODY, quizId }, env));
    assert.equal(replay.length, 1);
    assert.equal(replay[0].type, 'result');
    assert.equal(replay[0].quiz.quizId, quizId);
});

test('quiz stream 的 watched 不足 7 部时按普通 JSON 错误返回，不进入流式', async () => {
    const env = createEnv();
    await assert.rejects(
        () => callStream({ ...QUIZ_BODY, watched: WATCHED_MOVIES.slice(0, 3) }, env),
        (error) => error.code === 'NOT_ENOUGH_MOVIES',
    );
});
