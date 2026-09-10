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

/** 在 createDbStub 基础上接住健康事件 INSERT，供出题链路的 invalid_output 断言。 */
function createHealthCapturingDb() {
    const healthInserts = [];
    const base = createDbStub();
    return {
        healthInserts,
        prepare(sql) {
            if (sql.includes('INSERT INTO ai_health_events')) {
                return {
                    bind(...args) {
                        healthInserts.push(args);
                        return { async run() { return { meta: { changes: 1 } }; } };
                    },
                };
            }
            return base.prepare(sql);
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

test('quiz stream 槽位判废时补记 invalid_output 健康事件', async () => {
    const originalFetch = globalThis.fetch;
    globalThis.fetch = async (input) => {
        if (String(input).includes('agnes-ai.com')) {
            // 所有槽位都返回 200 但结构不合格的单元：上游 success 与本地 invalid_output 都应落库
            return new Response(JSON.stringify({
                choices: [{ message: { content: JSON.stringify({ unit: { unitId: 'bad' } }) } }],
            }), { status: 200, headers: { 'Content-Type': 'application/json' } });
        }
        return new Response('unavailable', { status: 500 });
    };
    try {
        const db = createHealthCapturingDb();
        const env = createEnv({
            DB: db,
            AI_TEST_MODE: false,
            AGNES_API_KEYS: 'test-agnes-key',
            AI_DEFAULT_PROVIDER: 'agnes',
        });
        const events = await readEvents(await callStream(QUIZ_BODY, env));
        assert.equal(events.at(-1).type, 'result');
        // 等 fire-and-forget 健康写入落地（流式链路不挂 waitUntil）
        await new Promise(resolve => setTimeout(resolve, 30));
        const rows = db.healthInserts.map(args => ({ route: args[2], provider: args[3], outcome: args[5], code: args[6] }));
        assert.ok(rows.length > 0, '健康事件必须落库');
        assert.ok(rows.some(r => r.route === 'quiz-units' && r.provider === 'agnes' && r.outcome === 'success'), '上游 200 应记 success');
        assert.ok(rows.some(r => r.route === 'quiz-units' && r.provider === 'agnes' && r.outcome === 'invalid_output' && r.code === 'INVALID_AI_OUTPUT'), '槽位本地判废应补记 invalid_output');
        assert.ok(rows.every(r => r.route === 'quiz-units'), 'units 未通过时不应出现 quiz-review 行');
    } finally {
        globalThis.fetch = originalFetch;
    }
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

// ---- 槽位降级必须仍然挂在已审校单元上 ----
//
// 历史事故：兜底题沿用整包兜底池（unitId=null、自带学科概念），只要有一格判废，
// 整包校验必然在「AI question must come from a reviewed knowledge unit」处失败，
// 单格降级会连带整套作废。这个用例把「全部题位都判废」推到极限，锁住降级只损失单格。

const SLOT_SYNOPSES = [
    '张艺谋执导，张译主演，讲述 1970 年代西北大漠中一名劳改犯为看女儿影像在胶片上跋涉的故事',
    '金刚狼在二战长崎核爆中救下矢志郎，多年后受邀前往东京，卷入了一场关于生死的阴谋',
    '迈尔斯被放射性蜘蛛咬伤后获得能力，与来自平行宇宙的多个蜘蛛侠并肩作战阻止粒子对撞机',
    '吕克·贝松执导，让·雷诺饰演职业杀手莱昂，与玛蒂尔达因全家被害结成师徒关系，绿植与牛奶贯穿全片',
    '星爵、火箭浣熊、格鲁特、卡魔拉和德拉克斯组队护送宇宙灵球，最终在山达尔星对抗罗南',
    '语言与爱情交织的都市喜剧，讲述两位译者在一档节目里反复误解彼此用词的故事',
    '阿宝与生父李山重逢，回到熊猫村修炼气功，对抗来自灵界的牛魔王天煞',
];

const SLOT_MOVIES = WATCHED_MOVIES.map((movie, index) => ({ ...movie, overview: SLOT_SYNOPSES[index] }));

/** 从 fetch 的 init 里还原提示词全文：桩只需要看提示词就能判断这是单元槽位还是题位。 */
function promptTextOf(init) {
    if (!init || typeof init.body !== 'string') return '';
    try {
        const parsed = JSON.parse(init.body);
        return (parsed.messages ?? []).map(message => String(message.content ?? '')).join('\n');
    } catch {
        return String(init.body);
    }
}

function jsonCompletion(content) {
    return new Response(JSON.stringify({ choices: [{ message: { content } }] }), {
        status: 200,
        headers: { 'Content-Type': 'application/json' },
    });
}

/** 按槽位提示词里钉死的 unitId/subjectGroup/白名单现造一个能过本地门槛的单元。 */
function slotUnitPayload(prompt) {
    const unitId = /unitId=unit-(\d+)/u.exec(prompt)?.[1] ?? '1';
    const subjectGroup = /subjectGroup=([a-z_]+)/u.exec(prompt)?.[1] ?? 'film_expression';
    const subjects = (/subject 从白名单【([^】]+)】/u.exec(prompt)?.[1] ?? '电影学').split('、');
    const angle = /角度类型=([^（(，,]+)/u.exec(prompt)?.[1]?.trim() ?? '象征';
    const evidenceBlock = /<WATCHED_EVIDENCE>(.*?)<\/WATCHED_EVIDENCE>/su.exec(prompt)?.[1] ?? '[]';
    const movies = JSON.parse(evidenceBlock);
    const movie = movies[(Number(unitId) - 1) % movies.length];
    const synopsis = (movie.evidence ?? []).find(line => line.startsWith('简介：'))?.slice('简介：'.length) ?? movie.title;
    const concept = '测试概念' + unitId;
    return {
        unit: {
            unitId: 'unit-' + unitId,
            unitVersion: 1,
            locale: 'zh-CN',
            relationType: 'direct_watch',
            evidenceMode: 'film_fact',
            subjectGroup,
            subject: subjects[0],
            concept,
            title: '《' + movie.title + '》的' + angle + '角度与影像读法',
            takeaway: '把' + concept + '放回影片材料里看，结论要能由这段简介原文复述出来。',
            relatedMedia: { title: movie.title, mediaType: movie.mediaType },
            filmEvidence: synopsis,
            explanation: '这段材料给出的是可核验的事实，' + concept + '只用来解释它为什么这样成立。',
            realWorldExample: '日常讨论作品时，先复述材料，再说自己补的那一层解释。',
            boundary: '这里的说明来自已看记录里的事实材料，不是对影片的额外延伸解读。',
            difficulty: 'easy',
            spoilerLevel: 'none',
            source: { name: '已看记录', url: 'https://example.com/watched-record', evidence: '已看记录里的简介与类型原文。' },
            checkQuestion: {
                prompt: '这段材料里直接写到的内容是什么？',
                options: [
                    { id: 'a', text: synopsis.slice(0, 24) },
                    { id: 'b', text: '材料之外的幕后花絮' },
                    { id: 'c', text: '与' + concept + '无关的票房数字' },
                ],
                correctOptionIds: ['a'],
                explanation: '材料直接写着这段简介，' + concept + '只是解释它的角度。',
            },
        },
    };
}

test('quiz stream 全部题位判废时降级题仍挂在已审校单元上（整包不被判废）', async () => {
    const originalFetch = globalThis.fetch;
    let questionCalls = 0;
    globalThis.fetch = async (url, init) => {
        if (!String(url).includes('agnes-ai.com')) return new Response('unavailable', { status: 500 });
        const prompt = promptTextOf(init);
        if (prompt.includes('单槽位学习单元编辑')) return jsonCompletion(JSON.stringify(slotUnitPayload(prompt)));
        // 题位一律返回结构不合格的 200：走满修复轮后全部降级
        questionCalls += 1;
        return jsonCompletion(JSON.stringify({ question: { type: 'unsupported' } }));
    };
    try {
        const env = createEnv({
            DB: createDbStub(),
            AI_TEST_MODE: false,
            AGNES_API_KEYS: 'test-agnes-key',
            AI_DEFAULT_PROVIDER: 'agnes',
        });
        const events = await readEvents(await callStream({ ...QUIZ_BODY, watched: SLOT_MOVIES }, env));
        assert.equal(events.at(-1).type, 'result');
        const quiz = events.at(-1).quiz;
        assert.equal(quiz.questions.length, 13, '降级后仍要下发 13 题');
        assert.ok(questionCalls >= 13, '每个题位都要真的打过一次上游首轮');
        for (const question of quiz.questions) {
            assert.ok(question.unitId, '降级题必须挂在已审校单元上，否则整包会被判废');
            assert.ok(String(question.concept).startsWith('测试概念'), '降级题必须沿用命中单元的概念');
            assert.ok(String(question.knowledgePoint).startsWith('测试概念'), '降级题的考点要能追溯到单元概念');
            assert.ok(String(question.evidenceUsed).length >= 8, '降级题仍要带可核验证据');
        }
    } finally {
        globalThis.fetch = originalFetch;
    }
});

/** 一道「形状全合格」的题位输出：用来把失败点精确压到某一个质量门槛上。 */
function slotQuestionPayload(prompt, overrides = {}) {
    const index = Number(/id 固定为 "q(\d+)"/u.exec(prompt)?.[1] ?? '1');
    const type = /type 固定为 "([a-z]+)"/u.exec(prompt)?.[1] ?? 'single';
    const difficulty = /difficulty 固定为 "(\w+)"/u.exec(prompt)?.[1] ?? 'easy';
    const unitId = /unitId 必须原样等于所给单元的 unitId "([^"]+)"/u.exec(prompt)?.[1] ?? 'unit-1';
    const concept = /concept 必须原样等于 "([^"]+)"/u.exec(prompt)?.[1] ?? '测试概念';
    const subject = /subject 必须沿用该单元的学科 "([^"]+)"/u.exec(prompt)?.[1] ?? '电影学';
    const title = /sourceTitle 必须逐字等于 "([^"]+)"/u.exec(prompt)?.[1] ?? WATCHED_MOVIES[0].title;
    const movieIndex = Math.max(0, WATCHED_MOVIES.findIndex(movie => movie.title === title));
    // 题干与证据都要回到该片简介原文：取一段去掉标点的连续前缀即可稳定命中
    const anchor = SLOT_SYNOPSES[movieIndex].replace(/[，。！？、\s]/gu, '').slice(0, 12);
    const knowledgePoint = '考点' + index + '号';
    const base = {
        id: 'q' + String(index).padStart(2, '0'),
        unitId,
        subject,
        concept,
        sourceTitle: title,
        difficulty,
        knowledgePoint,
        learningTakeaway: '这一题的学习结论是先把材料读准，再在材料能支撑的范围里下判断。',
        prompt: '关于“' + knowledgePoint + '”，影片里写着“' + anchor + '”，因此' + knowledgePoint + '说明了什么？',
        evidenceUsed: '影片里写着“' + anchor + '”，这一段是本题的直接依据。',
        answerRationale: '正确选项回到考点“' + knowledgePoint + '”：材料只支持这一层判断。',
        distractorRationale: '干扰项都没有落到“' + knowledgePoint + '”上，属于材料之外的推论。',
        explanation: '材料写着“' + anchor + '”，把它对应到考点“' + knowledgePoint + '”，因此结论只能限定在这段材料支撑得住的范围内。',
    };
    if (type === 'short') {
        return {
            question: {
                ...base,
                type,
                distractorRationale: '',
                answerKeywords: ['材料', '考点', '依据', '判断', '范围'],
                correctAnswer: '先读材料原句，再按考点限定的范围作答，不引入材料之外的情节推断。',
                ...overrides,
            },
        };
    }
    const options = [
        { id: 'opt-a', text: '只按材料写明的信息判断' },
        { id: 'opt-b', text: '按个人印象补上材料没有的细节' },
    ];
    return {
        question: {
            ...base,
            type,
            options,
            correctAnswer: type === 'multiple' ? ['opt-a', 'opt-b'] : 'opt-a',
            ...overrides,
        },
    };
}

test('题位只差一个字段不合格时判废止步于该格，不拖垮整套 13 题', async () => {
    const originalFetch = globalThis.fetch;
    let nearMissSeen = 0;
    globalThis.fetch = async (url, init) => {
        if (!String(url).includes('agnes-ai.com')) return new Response('unavailable', { status: 500 });
        const prompt = promptTextOf(init);
        if (prompt.includes('单槽位学习单元编辑')) return jsonCompletion(JSON.stringify(slotUnitPayload(prompt)));
        const index = Number(/id 固定为 "q(\d+)"/u.exec(prompt)?.[1] ?? '0');
        // 第 5 格把 learningTakeaway 写短：形状全对、只差长度门槛，实测真实上游出现过
        if (index === 5) {
            nearMissSeen += 1;
            return jsonCompletion(JSON.stringify(slotQuestionPayload(prompt, { learningTakeaway: '太短' })));
        }
        return jsonCompletion(JSON.stringify(slotQuestionPayload(prompt)));
    };
    try {
        const env = createEnv({
            DB: createDbStub(),
            AI_TEST_MODE: false,
            AGNES_API_KEYS: 'test-agnes-key',
            AI_DEFAULT_PROVIDER: 'agnes',
        });
        const events = await readEvents(await callStream({ ...QUIZ_BODY, watched: SLOT_MOVIES }, env));
        assert.equal(events.at(-1).type, 'result');
        const quiz = events.at(-1).quiz;
        assert.equal(quiz.questions.length, 13, '一格不合格不该把整套换成兜底题库');
        assert.ok(nearMissSeen >= 1, '第 5 格必须真的被判废过（否则这条用例没测到东西）');
        // 其余 12 题是模型写的（考点带关卡前缀），被拒的那一格降级成结构化兜底题
        const modelQuestions = quiz.questions.filter(question => String(question.knowledgePoint).startsWith('考点'));
        assert.equal(modelQuestions.length, 12, '一格不合格只应影响那一格');
        for (const question of quiz.questions) {
            assert.ok(question.unitId, '每格仍要挂在已审校单元上');
            assert.ok(String(question.concept).startsWith('测试概念'), '概念必须沿用命中单元');
        }
    } finally {
        globalThis.fetch = originalFetch;
    }
});
