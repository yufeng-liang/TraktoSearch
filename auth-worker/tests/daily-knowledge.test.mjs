import test from 'node:test';
import assert from 'node:assert/strict';
import { handleAiApi } from '../src/ai/handler.ts';
import {
    DAILY_KNOWLEDGE_SEEDS,
    SUBJECT_GROUP_IDS,
    fallbackDailyKnowledgeUnit,
    normalizeDailyKnowledgeUnit,
    normalizeQuizSlotUnit,
} from '../src/ai/daily-knowledge.ts';

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

function createLegacyMimoTextEnv(overrides = {}) {
    return createTestEnv({
        AI_DEFAULT_PROVIDER: 'mimo',
        MIMO_API_KEY: 'test-mimo-key',
        ...overrides,
    });
}

async function call(path, { body, env, friendId = 'friend-1' } = {}) {
    const request = new Request(`https://gateway.test${path}`, {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify(body),
    });
    const response = await handleAiApi(request, env, 'request-1', path, {
        sub: friendId,
        device: 'device-1',
    });
    return { response, json: await response.json() };
}

function watchedMovies() {
    return [{
        title: 'Movie 1',
        mediaType: 'movie',
        year: 2020,
        genres: ['Drama'],
        rating: 8,
        userRating: 8,
        watchedAt: '2024-01-01',
        mediaIds: { tmdbId: 100 },
        overview: '影片包含群体讨论场景。',
    }];
}

function validKnowledgeUnit(overrides = {}) {
    return {
        unitId: 'unit-1',
        version: 1,
        locale: 'zh-CN',
        relationType: 'direct_watch',
        evidenceMode: 'viewing_interpretation',
        subjectGroup: 'people_and_mind',
        subject: '心理学',
        concept: '从众压力',
        title: '第一个反对票为什么重要',
        takeaway: '第一个公开反对的人，会降低其他人表达不同意见的心理成本。',
        relatedMedia: { title: 'Movie 1', mediaType: 'movie', tmdbId: 100 },
        filmEvidence: 'Movie 1 是 2020 年的 Drama，输入资料包含“上映年份：2020”“类型：Drama”“简介：影片包含群体讨论场景”，可用来讨论群体压力。',
        explanation: '当多数意见形成后，个体会评估表达异议的成本；第一个公开异议让不同意见变得可见。',
        realWorldExample: '会议中先有人提出不同方案后，后续补充意见更容易出现。',
        boundary: '这是基于影片观看记录的入门解读，不是对角色的临床诊断。',
        difficulty: 'easy',
        spoilerLevel: 'none',
        source: {
            name: 'Example',
            url: 'https://www.britannica.com/source',
            evidence: '来源介绍从众压力与少数意见影响群体讨论的条件。',
        },
        checkQuestion: {
            prompt: '根据这个学习单元，第一个反对票的作用是什么？',
            options: [
                { id: 'a', text: '让所有人立刻改变立场' },
                { id: 'b', text: '降低其他人表达不同意见的心理成本' },
                { id: 'c', text: '证明多数意见一定错误' },
            ],
            correctOptionIds: ['b'],
            explanation: '从众压力说明第一个公开异议会让后续不同意见更容易出现。',
        },
        characterLine: '今天也学到一个小概念！',
        ...overrides,
    };
}

function chatResponse(unit) {
    return new Response(JSON.stringify({
        choices: [{ message: { content: JSON.stringify(unit) } }],
    }), { status: 200, headers: { 'Content-Type': 'application/json' } });
}

function installDailyFetch({ candidate, review = null, localized = null } = {}) {
    const originalFetch = globalThis.fetch;
    const requests = [];
    globalThis.fetch = async (_input, init = {}) => {
        if (init.method === 'HEAD') return new Response(null, { status: 204 });
        const requestBody = JSON.parse(init.body);
        requests.push(requestBody);
        const systemPrompt = String(requestBody.messages?.[0]?.content ?? '');
        const isCandidate = systemPrompt.includes('今日影视知识候选编辑');
        let unit;
        if (localized) {
            const locale = systemPrompt.includes('简体中文')
                ? 'zh-CN'
                : systemPrompt.includes('English (United States)')
                    ? 'en-US'
                    : null;
            const stage = isCandidate ? 'candidate' : 'review';
            unit = locale === null ? null : localized[locale]?.[stage];
        } else {
            unit = isCandidate ? candidate : (review ?? candidate);
        }
        if (!unit) throw new Error('daily test fixture does not match locale/stage');
        return chatResponse(unit);
    };
    return {
        requests,
        restore() { globalThis.fetch = originalFetch; },
    };
}

function today() {
    return new Intl.DateTimeFormat('en-CA', { timeZone: 'Asia/Shanghai' }).format(new Date());
}

test('daily locale is validated and cache entries are isolated by locale', async () => {
    const env = createLegacyMimoTextEnv();
    const zhCandidate = validKnowledgeUnit({ unitId: 'locale-zh', title: '中文学习单元' });
    const enCandidate = validKnowledgeUnit({
        unitId: 'locale-en',
        locale: 'en-US',
        concept: 'conformity pressure',
        title: 'Why the first dissent matters',
        takeaway: 'The first public dissent lowers the social cost of speaking differently.',
        filmEvidence: 'Movie 1 is a 2020 Drama, and the supplied material includes “上映年份：2020”“类型：Drama”“简介：影片包含群体讨论场景”.',
        explanation: 'Once a majority appears, people weigh the social cost of disagreement; one visible dissent changes that calculation.',
        realWorldExample: 'After one colleague proposes another plan, more colleagues feel able to add concerns.',
        boundary: 'This is an introductory interpretation of the viewing record, not a clinical diagnosis.',
        source: {
            name: 'Example',
            url: 'https://www.britannica.com/source',
            evidence: 'The source describes conformity and how minority views can change group discussion.',
        },
        checkQuestion: {
            prompt: 'What does the first dissent do in this learning unit?',
            options: [
                { id: 'a', text: 'It forces everyone to change immediately.' },
                { id: 'b', text: 'It lowers the social cost of different opinions.' },
                { id: 'c', text: 'It proves the majority is wrong.' },
            ],
            correctOptionIds: ['b'],
            explanation: 'Conformity pressure explains why one visible dissent makes further disagreement easier.',
        },
    });
    const zhReview = { ...zhCandidate, unitId: 'locale-zh-review', title: '中文二审学习单元' };
    const enReview = { ...enCandidate, unitId: 'locale-en-review', title: 'Why the first dissent matters after review' };
    const fetchState = installDailyFetch({
        localized: {
            'zh-CN': { candidate: zhCandidate, review: zhReview },
            'en-US': { candidate: enCandidate, review: enReview },
        },
    });

    try {
        const zh = await call('/api/ai/daily', {
            body: { action: 'daily', sessionId: 'locale-zh', watched: watchedMovies(), locale: 'zh-CN', forceRefresh: true },
            env,
        });
        const en = await call('/api/ai/daily', {
            body: { action: 'daily', sessionId: 'locale-en', watched: watchedMovies(), locale: 'en-US', forceRefresh: true },
            env,
        });
        const zhAgain = await call('/api/ai/daily', {
            body: { action: 'daily', sessionId: 'locale-zh', watched: watchedMovies(), locale: 'zh-CN' },
            env,
        });

        assert.equal(zh.response.status, 200);
        assert.equal(en.response.status, 200);
        assert.equal(zhAgain.response.status, 200);
        assert.equal(zh.json.data.unitId, 'locale-zh-review');
        assert.equal(zhAgain.json.data.title, zh.json.data.title);
        assert.equal(en.json.data.unitId, 'locale-en-review');
        assert.equal(en.json.data.locale, 'en-US');
        assert.equal(fetchState.requests.length, 4);
        const keys = [...env.AI_TEST_CACHE.keys()].filter(key => key.startsWith('ai:v3:daily:'));
        assert.ok(keys.some(key => key.includes(':zh-CN:')));
        assert.ok(keys.some(key => key.includes(':en-US:')));

        await assert.rejects(
            () => call('/api/ai/daily', {
                body: { action: 'daily', sessionId: 'locale-invalid', locale: 'fr-FR', forceRefresh: true },
                env,
            }),
            error => error.code === 'INVALID_REQUEST' && error.statusCode === 400,
        );
    } finally {
        fetchState.restore();
    }
});

test('daily cache is also isolated by watched digest inside the same locale', async () => {
    const env = createTestEnv();
    const originalFetch = globalThis.fetch;
    globalThis.fetch = async (_input, init = {}) => {
        if (init.method === 'HEAD') return new Response(null, { status: 204 });
        throw new Error('fallback daily should not call text AI');
    };

    try {
        const first = await call('/api/ai/daily', {
            body: { action: 'daily', sessionId: 'watched-cache-a', watched: watchedMovies(), forceRefresh: true },
            env,
        });
        const second = await call('/api/ai/daily', {
            body: {
                action: 'daily',
                sessionId: 'watched-cache-b',
                watched: [{ ...watchedMovies()[0], genres: ['Thriller'], overview: '影片包含单人密闭空间场景。' }],
                forceRefresh: true,
            },
            env,
        });

        assert.equal(first.response.status, 200);
        assert.equal(second.response.status, 200);
        const keys = [...env.AI_TEST_CACHE.keys()].filter(key => key.startsWith('ai:v3:daily:friend-1:'));
        assert.equal(keys.length, 2);
        assert.notEqual(keys[0], keys[1]);
    } finally {
        globalThis.fetch = originalFetch;
    }
});

test('daily returns the complete learning unit and legacy fields', async () => {
    const unit = validKnowledgeUnit({ unitId: 'complete-unit', title: '完整学习单元' });
    const fetchState = installDailyFetch({ candidate: unit });

    try {
        const env = createLegacyMimoTextEnv();
        const { response, json } = await call('/api/ai/daily', {
            body: { action: 'daily', sessionId: 'daily-complete', watched: watchedMovies(), forceRefresh: true },
            env,
        });

        assert.equal(response.status, 200);
        assert.equal(json.data.unitId, 'complete-unit');
        assert.equal(json.data.unitVersion, 1);
        assert.equal(json.data.locale, 'zh-CN');
        assert.equal(json.data.relationType, 'direct_watch');
        assert.equal(json.data.evidenceMode, 'viewing_interpretation');
        assert.equal(json.data.subjectGroup, 'people_and_mind');
        assert.equal(json.data.subject, '心理学');
        assert.equal
(json.data.relationType, 'direct_watch');
        assert.equal(json.data.evidenceMode, 'viewing_interpretation');
        assert.equal(json.data.subjectGroup, 'people_and_mind');
        assert.equal(json.data.subject, '心理学');
        assert.equal(json.data.concept, '从众压力');
        assert.equal(json.data.takeaway, unit.takeaway);
        assert.deepEqual(json.data.relatedMedia, { title: 'Movie 1', mediaType: 'movie', tmdbId: 100 });
        assert.equal(json.data.filmEvidence, unit.filmEvidence);
        assert.equal(json.data.realWorldExample, unit.realWorldExample);
        assert.equal(json.data.boundary, unit.boundary);
        assert.equal(json.data.spoilerLevel, 'none');
        assert.deepEqual(json.data.source, unit.source);
        assert.deepEqual(json.data.checkQuestion, unit.checkQuestion);
        assert.equal(json.data.title, unit.title);
        assert.equal(json.data.fact, unit.takeaway);
        assert.equal(json.data.explanation, unit.explanation);
        assert.equal(json.data.sourceName, unit.source.name);
        assert.equal(json.data.sourceUrl, unit.source.url);
        assert.equal(json.data.relatedMediaTitle, 'Movie 1');
        assert.equal(json.data.containsSpoiler, false);
        assert.equal(json.data.characterLine, unit.characterLine);
    } finally {
        fetchState.restore();
    }
});

test('daily uses a second review call and returns the rewritten unit', async () => {
    const candidate = validKnowledgeUnit({ unitId: 'candidate-unit', title: '候选标题足够具体' });
    const reviewed = validKnowledgeUnit({ unitId: 'reviewed-unit', title: '审校后的标题' });
    const fetchState = installDailyFetch({ candidate, review: reviewed });

    try {
        const env = createLegacyMimoTextEnv();
        const { response, json } = await call('/api/ai/daily', {
            body: { action: 'daily', sessionId: 'daily-review', watched: watchedMovies(), forceRefresh: true },
            env,
        });

        assert.equal(response.status, 200);
        assert.equal(fetchState.requests.length, 2);
        assert.match(String(fetchState.requests[0].messages[0].content), /今日影视知识候选编辑/);
        assert.match(String(fetchState.requests[1].messages[0].content), /二审审校器/);
        assert.match(String(fetchState.requests[1].messages[1].content), /CANDIDATE_KNOWLEDGE/);
        assert.equal(json.data.unitId, 'reviewed-unit');
        assert.equal(json.data.title, '审校后的标题');
    } finally {
        fetchState.restore();
    }
});
test('daily never returns the first candidate when the review or local gate fails', async () => {
    const cases = [
        ['review-invalid', { review: {} }],
        ['generic', { candidate: validKnowledgeUnit({
            title: '人性的复杂性',
            takeaway: '这部电影展现了人性的复杂性。',
            filmEvidence: 'Movie 1 是一部电影。',
        }) }],
        ['unwatched-direct-media', { candidate: validKnowledgeUnit({
            relatedMedia: { title: 'Not Watched', mediaType: 'movie' },
        }) }],
        ['year-genre-only-evidence', { candidate: validKnowledgeUnit({
            filmEvidence: 'Movie 1 是 2020 年的 Drama。',
        }) }],
        ['invalid-relation', { candidate: validKnowledgeUnit({ relationType: 'random' }) }],
        ['invalid-version-type', { candidate: validKnowledgeUnit({ version: '1' }) }],
        ['invalid-boundary-mode', { candidate: validKnowledgeUnit({
            boundary: '这里只是一句足够长的话，用来描述内容却没有说明任何限制。',
        }) }],
        ['invalid-source-url', { candidate: validKnowledgeUnit({
            source: { name: 'Example', url: 'ftp://example.com/source', evidence: '来源说明从众压力的条件。' },
        }) }],
        ['missing-source-evidence', { candidate: validKnowledgeUnit({
            subject: '物理',
            subjectGroup: 'science_and_nature',
            evidenceMode: 'external_fact',
            source: { name: 'Example', url: 'https://www.britannica.com/source', evidence: '' },
        }) }],
        ['multiple-best-answers', { candidate: validKnowledgeUnit({
            checkQuestion: { ...validKnowledgeUnit().checkQuestion, correctOptionIds: ['a', 'b'] },
        }) }],
        ['inconsistent-question', { candidate: validKnowledgeUnit({
            checkQuestion: { ...validKnowledgeUnit().checkQuestion, explanation: '这个选项看起来更长。' },
        }) }],
    ];

    for (const [name, options] of cases) {
        const candidate = options.candidate ?? validKnowledgeUnit({ unitId: `candidate-${name}`, title: `候选 ${name}` });
        const fetchState = installDailyFetch({ candidate, review: options.review ?? candidate });
        try {
            const env = createLegacyMimoTextEnv();
            const { response, json } = await call('/api/ai/daily', {
                body: { action: 'daily', sessionId: `daily-${name}`, watched: watchedMovies(), forceRefresh: true },
                env,
            });

            assert.equal(response.status, 200, name);
            assert.notEqual(json.data.title, candidate.title, name);
            assert.notEqual(json.data.unitId, candidate.unitId, name);
            assert.equal(json.data.checkQuestion.correctOptionIds.length, 1, name);
        } finally {
            fetchState.restore();
        }
    }
});
test('daily local gates cover localized user-visible fields', async () => {
    const cases = [
        {
            locale: 'zh-CN',
            candidate: validKnowledgeUnit({
                unitId: 'gate-zh-spoiler',
                takeaway: '影片结尾主角死亡后，其他人的选择才改变。',
                spoilerLevel: 'none',
            }),
        },
        {
            locale: 'en-US',
            candidate: validKnowledgeUnit({
                unitId: 'gate-en-risk',
                locale: 'en-US',
                boundary: 'This is an introductory interpretation of the viewing record, not a clinical diagnosis.',
                characterLine: 'Drug dosage advice for a real individual.',
            }),
        },
        {
            locale: 'ja-JP',
            candidate: validKnowledgeUnit({
                unitId: 'gate-ja-generic',
                locale: 'ja-JP',
                title: '人間の複雑さを描く物語',
                boundary: 'これは視聴記録の解釈であり、臨床診断ではない。',
            }),
        },
        {
            locale: 'ko-KR',
            candidate: validKnowledgeUnit({
                unitId: 'gate-ko-spoiler',
                locale: 'ko-KR',
                filmEvidence: '결말에서 진실이 밝혀진다.',
                boundary: '관람 기록의 해석이며 임상 진단이 아니다.',
                spoilerLevel: 'none',
            }),
        },
    ];

    for (const item of cases) {
        const fetchState = installDailyFetch({ candidate: item.candidate, review: item.candidate });
        try {
            const env = createLegacyMimoTextEnv();
            const { response, json } = await call('/api/ai/daily', {
                body: {
                    action: 'daily',
                    sessionId: `daily-${item.locale}`,
                    watched: watchedMovies(),
                    locale: item.locale,
                    forceRefresh: true,
                },
                env,
            });

            assert.equal(response.status, 200, item.locale);
            assert.notEqual(json.data.unitId, item.candidate.unitId, item.locale);
            assert.notEqual(json.data.title, item.candidate.title, item.locale);
        } finally {
            fetchState.restore();
        }
    }
});

test('daily reads the legacy v2 cache without regenerating or breaking old fields', async () => {
    const env = createTestEnv();
    const legacyPayload = {
        id: today(),
        date: today(),
        title: '旧每日冷知识',
        fact: '旧事实字段。',
        explanation: '旧解释字段。',
        sourceName: '旧来源',
        sourceUrl: 'https://example.com/old',
        publishedAt: 123,
        characterLine: '旧台词',
        relatedMediaTitle: null,
        containsSpoiler: false,
    };
    env.AI_TEST_CACHE.set(`ai:v2:daily:friend-1:${today()}`, {
        payload: legacyPayload,
        expiresAt: Math.floor(Date.now() / 1000) + 3600,
    });
    const originalFetch = globalThis.fetch;
    globalThis.fetch = async () => { throw new Error('legacy daily cache must not call AI'); };

    try {
        const { response, json } = await call('/api/ai/daily', {
            body: { action: 'daily', sessionId: 'daily-legacy-cache' },
            env,
        });
        assert.equal(response.status, 200);
        assert.equal(json.data.title, '旧每日冷知识');
        assert.equal(json.data.fact, '旧事实字段。');
        assert.equal(json.data.sourceUrl, 'https://example.com/old');
        assert.equal(json.data.unitId, undefined);
    } finally {
        globalThis.fetch = originalFetch;
    }
});

test('cached daily responses never ship a placeholder source with a real link', async () => {
    const env = createTestEnv();
    // 旧缓存里已经写死「AI 综合解读 + britannica.com 首页」：回给用户前必须清掉链接。
    env.AI_TEST_CACHE.set(`ai:v2:daily:friend-1:${today()}`, {
        payload: {
            id: today(),
            date: today(),
            title: '旧缓存占位来源',
            fact: '旧事实字段。',
            explanation: '旧解释字段。',
            sourceName: 'AI 综合解读',
            sourceUrl: 'https://www.britannica.com/',
            publishedAt: 123,
            characterLine: '旧台词',
            relatedMediaTitle: null,
            containsSpoiler: false,
        },
        expiresAt: Math.floor(Date.now() / 1000) + 3600,
    });
    const originalFetch = globalThis.fetch;
    globalThis.fetch = async () => { throw new Error('cached daily must not call AI'); };

    try {
        const { json } = await call('/api/ai/daily', {
            body: { action: 'daily', sessionId: 'daily-cached-placeholder-source' },
            env,
        });
        assert.equal(json.data.sourceName, 'AI 综合解读');
        assert.equal(json.data.sourceUrl, null);
        assert.equal(json.data.source.url, '');
        assert.equal(json.data.source.evidence, '本节由 AI 综合公开通识整理，未引用具体来源。');
    } finally {
        globalThis.fetch = originalFetch;
    }
});

test('checkQuestion option texts drop trailing sentence punctuation', async () => {
    const candidate = validKnowledgeUnit({
        unitId: 'option-punct-unit',
        title: '选项标点规范化单元',
        checkQuestion: {
            prompt: '根据这个学习单元，第一个反对票的作用是什么？',
            options: [
                { id: 'a', text: '让所有人立刻改变立场。？' },
                { id: 'b', text: '降低其他人表达不同意见的心理成本。' },
                { id: 'c', text: '证明多数意见一定错误！' },
            ],
            correctOptionIds: ['b'],
            explanation: '从众压力说明第一个公开异议会让后续不同意见更容易出现。',
        },
    });
    const fetchState = installDailyFetch({ candidate, review: candidate });
    try {
        const env = createLegacyMimoTextEnv();
        const { response, json } = await call('/api/ai/daily', {
            body: { action: 'daily', sessionId: 'daily-option-punct', watched: watchedMovies(), forceRefresh: true },
            env,
        });
        assert.equal(response.status, 200);
        const texts = json.data.checkQuestion.options.map(option => option.text);
        assert.deepEqual(texts, ['让所有人立刻改变立场', '降低其他人表达不同意见的心理成本', '证明多数意见一定错误']);
    } finally {
        fetchState.restore();
    }
});

test('generic methodology content with a mismatched subject falls back, metacognition subjects stay allowed', async () => {
    // 物理标签 + “避免过度解读”类通用方法内容：学科与内容脱节，必须回落到种子。
    const mismatched = validKnowledgeUnit({
        unitId: 'candidate-physics-generic',
        subject: '物理',
        subjectGroup: 'science_and_nature',
        evidenceMode: 'external_fact',
        title: '如何避免过度解读影片内容',
        takeaway: '如何避免过度解读是重点，判断时要先核对可观察证据。',
        boundary: '这是基于外部来源的事实说明，不构成对影片的权威结论。',
    });
    const mismatchFetch = installDailyFetch({ candidate: mismatched, review: mismatched });
    try {
        const env = createLegacyMimoTextEnv();
        const { response, json } = await call('/api/ai/daily', {
            body: { action: 'daily', sessionId: 'daily-physics-generic', watched: watchedMovies(), forceRefresh: true },
            env,
        });
        assert.equal(response.status, 200);
        assert.notEqual(json.data.unitId, mismatched.unitId, '物理标签不得承载通用“避免过度解读”方法');
    } finally {
        mismatchFetch.restore();
    }

    // 心理学（元认知目录内）讨论“避免过度解读他人行为”属于标签一致，允许放行。
    const meta = validKnowledgeUnit({
        unitId: 'candidate-psychology-meta',
        takeaway: '避免过度解读他人行为时，应先核对可观察证据再下结论。',
    });
    const metaFetch = installDailyFetch({ candidate: meta, review: meta });
    try {
        const env = createLegacyMimoTextEnv();
        const { response, json } = await call('/api/ai/daily', {
            body: { action: 'daily', sessionId: 'daily-psychology-meta', watched: watchedMovies(), forceRefresh: true },
            env,
        });
        assert.equal(response.status, 200);
        assert.equal(json.data.unitId, meta.unitId, '学习/记忆/元认知学科与“避免过度解读”内容一致时不应误杀');
    } finally {
        metaFetch.restore();
    }
});

test('daily prompts forbid subject-generic methodology and unsupported external facts', async () => {
    const candidate = validKnowledgeUnit({ unitId: 'prompt-rule-candidate', title: '提示词硬约束候选' });
    const reviewed = validKnowledgeUnit({ unitId: 'prompt-rule-reviewed', title: '提示词硬约束审校结果' });
    const fetchState = installDailyFetch({ candidate, review: reviewed });
    try {
        const env = createLegacyMimoTextEnv();
        const { response, json } = await call('/api/ai/daily', {
            body: { action: 'daily', sessionId: 'daily-prompt-rules', watched: watchedMovies(), forceRefresh: true },
            env,
        });
        assert.equal(response.status, 200);
        assert.equal(json.data.unitId, 'prompt-rule-reviewed');
        const candidatePrompt = String(fetchState.requests[0].messages[0].content);
        const reviewPrompt = String(fetchState.requests[1].messages[0].content);
        assert.match(candidatePrompt, /学科标签必须与内容真正检验的东西一致/);
        assert.match(candidatePrompt, /避免过度解读/);
        assert.match(candidatePrompt, /source\.evidence 与 URL 的实质支持/);
        // 二审是最后一道闸：内容规则与字段形状规范必须同样出现在复核提示词里
        assert.match(reviewPrompt, /学科标签必须与内容真正检验的东西一致/);
        assert.match(reviewPrompt, /外部事实必须得到 source\.evidence 与 URL 的实质支持/);
        assert.match(reviewPrompt, /字段硬性形状/);
    } finally {
        fetchState.restore();
    }
});

test('reviewed seed knowledge meets the 24-to-36 coverage contract', () => {
    assert.ok(DAILY_KNOWLEDGE_SEEDS.length >= 24, 'seed library must contain at least 24 units');
    assert.ok(DAILY_KNOWLEDGE_SEEDS.length <= 36, 'seed library must contain at most 36 units');

    const groupCounts = new Map();
    const evidenceEnhancedSubjects = new Set([
        '物理', '化学', '生物与生态', '医学与公共卫生', '天文学', '地理与气候',
        '计算机与人工智能', '数学与统计', '工程与材料', '建筑与城市规划',
        '法学', '军事学与战略', '体育科学', '食品科学',
    ]);
    for (const seed of DAILY_KNOWLEDGE_SEEDS) {
        groupCounts.set(seed.subjectGroup, (groupCounts.get(seed.subjectGroup) ?? 0) + 1);

        assert.equal(seed.locale, 'zh-CN');
        assert.equal(seed.relationType, 'general_knowledge');
        assert.equal(seed.relatedMedia, null, 'general knowledge must not claim watched media');
        assert.ok(['film_fact', 'viewing_interpretation', 'external_fact'].includes(seed.evidenceMode));
        if (evidenceEnhancedSubjects.has(seed.subject)) {
            assert.equal(seed.evidenceMode, 'external_fact');
        }

        assert.ok(seed.source.name.length >= 2);
        assert.equal(new URL(seed.source.url).protocol, 'https:');
        assert.ok(seed.source.evidence.length >= 8);

        const options = seed.checkQuestion.options;
        assert.ok(options.length >= 2 && options.length <= 4);
        assert.equal(new Set(options.map(option => option.id)).size, options.length);
        assert.equal(new Set(options.map(option => option.text)).size, options.length);
        assert.equal(seed.checkQuestion.correctOptionIds.length, 1);
        assert.ok(options.some(option => option.id === seed.checkQuestion.correctOptionIds[0]));
    }

    assert.deepEqual([...groupCounts.keys()].sort(), [...SUBJECT_GROUP_IDS].sort());
    for (const [group, count] of groupCounts) {
        assert.ok(count >= 3 && count <= 5, `${group} has ${count} seeds`);
    }

    // 每条种子都直接经过生产校验器，确认受控学科、边界、来源和小题质量门槛。
    for (const seed of DAILY_KNOWLEDGE_SEEDS) {
        const normalized = normalizeDailyKnowledgeUnit(seed, { day: today(), locale: 'zh-CN', movies: [] });
        assert.equal(normalized.unitId, seed.unitId);
    }

    for (const locale of ['zh-CN', 'en-US', 'ja-JP', 'ko-KR']) {
        const fallback = fallbackDailyKnowledgeUnit(today(), locale);
        assert.equal(fallback.locale, locale);
        assert.match(fallback.source.url, /^https?:\/\//);
        assert.equal(fallback.checkQuestion.correctOptionIds.length, 1);
        assert.ok(fallback.filmEvidence);
        assert.ok(fallback.boundary);
    }
});

const SLOT_MOVIE = {
    title: '《一一》',
    mediaType: 'movie',
    year: 2000,
    genres: ['剧情'],
    mediaIds: { tmdbId: 100 },
    evidence: ['上映年份：2000', '类型：剧情', '简介：NJ 在东京与旧情人重逢，洋洋用相机拍别人的后脑勺。'],
};

/** 出题链路的精简单元：只有题位生成与本地校验真正会读的字段。 */
function slimSlotUnit(overrides = {}) {
    return {
        unitId: 'unit-1',
        version: 1,
        locale: 'zh-CN',
        relationType: 'direct_watch',
        evidenceMode: 'film_fact',
        subjectGroup: 'film_expression',
        subject: '电影学',
        concept: '有限视角下的信息差',
        filmEvidence: '洋洋用相机拍别人的后脑勺',
        relatedMedia: { title: '《一一》', mediaType: 'movie' },
        ...overrides,
    };
}

test('精简单元只保留出题链路要用的字段，模型多写的展示字段被忽略', () => {
    const unit = normalizeQuizSlotUnit(slimSlotUnit({
        title: '多余的标题',
        takeaway: '多余的结论',
        checkQuestion: { prompt: '多余的小题' },
    }), { locale: 'zh-CN', movies: [SLOT_MOVIE] });

    assert.deepEqual(Object.keys(unit).sort(), [
        'concept', 'evidenceMode', 'filmEvidence', 'locale', 'relatedMedia',
        'relationType', 'subject', 'subjectGroup', 'unitId', 'version',
    ]);
    assert.equal(unit.concept, '有限视角下的信息差');
    assert.equal(unit.filmEvidence, '洋洋用相机拍别人的后脑勺');
    assert.equal(unit.relatedMedia.title, '《一一》');
    assert.equal(unit.relatedMedia.tmdbId, 100);
});

test('精简单元与完整单元同口径：没回到影片原文、学科越界、来源型学科一律判废', () => {
    const options = { locale: 'zh-CN', movies: [SLOT_MOVIE] };
    // 只提片名、不带任何简介原文：题面会失去可核验依据，必须当场判废而不是留给题位阶段
    assert.throws(() => normalizeQuizSlotUnit(slimSlotUnit({ filmEvidence: '《一一》讲的是家庭与时间。' }), options), /filmEvidence/);
    assert.throws(() => normalizeQuizSlotUnit(slimSlotUnit({ relatedMedia: { title: '《别的片》', mediaType: 'movie' } }), options), /watched input/);
    assert.throws(() => normalizeQuizSlotUnit(slimSlotUnit({ relationType: 'general_knowledge' }), options), /watched media/);
    assert.throws(() => normalizeQuizSlotUnit(slimSlotUnit({ subject: '占星学' }), options), /controlled catalog/);
    assert.throws(() => normalizeQuizSlotUnit(slimSlotUnit({ subjectGroup: 'people_and_mind' }), options), /controlled catalog/);
    assert.throws(() => normalizeQuizSlotUnit(slimSlotUnit({ subject: '物理', subjectGroup: 'science_and_nature' }), options), /external source evidence/);
    assert.throws(() => normalizeQuizSlotUnit(slimSlotUnit({ version: 2 }), options), /unitVersion/);
});

// ---- /api/ai/daily/stream：流式每日知识 ----

async function callDailyStream(body, env, friendId = 'friend-1') {
    const path = '/api/ai/daily/stream';
    const request = new Request(`https://gateway.test${path}`, {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify(body),
    });
    return handleAiApi(request, env, 'request-daily-stream', path, { sub: friendId, device: 'device-1' });
}

async function readStreamEvents(response) {
    const text = await response.text();
    return text.split('\n').map(line => line.trim()).filter(line => line.length > 0).map(line => JSON.parse(line));
}

test('daily stream 按 candidate→review 推进阶段并以 result 收尾', async () => {
    const candidate = validKnowledgeUnit({ unitId: 'candidate-unit', title: '候选标题足够具体' });
    const reviewed = validKnowledgeUnit({ unitId: 'reviewed-unit', title: '审校后的标题' });
    const fetchState = installDailyFetch({ candidate, review: reviewed });

    try {
        const env = createLegacyMimoTextEnv();
        const response = await callDailyStream(
            { action: 'daily', sessionId: 'daily-stream', watched: watchedMovies(), forceRefresh: true },
            env,
        );
        assert.equal(response.status, 200);
        assert.match(response.headers.get('Content-Type') ?? '', /application\/x-ndjson/);
        assert.match(response.headers.get('Cache-Control') ?? '', /no-transform/);

        const events = await readStreamEvents(response);
        assert.deepEqual(
            events.filter(event => event.type === 'stage').map(event => event.stage + ':' + event.status),
            ['candidate:start', 'candidate:done', 'review:start', 'review:done'],
        );
        for (const event of events.filter(event => event.type === 'stage')) {
            assert.ok(event.expectedChars > 0, '阶段事件必须带预期字符数供客户端换算进度');
        }
        const result = events.at(-1);
        assert.equal(result.type, 'result');
        assert.equal(result.daily.unitId, 'reviewed-unit');
        assert.equal(result.daily.title, '审校后的标题');
        assert.ok(result.quota, '生成路径要带上配额，客户端才知道剩余次数');
        // 两段式：候选与复核各打一次上游，流式不改调用次数
        assert.equal(fetchState.requests.length, 2);
    } finally {
        fetchState.restore();
    }
});

test('daily stream 命中当天缓存只下发 result，不打上游也不发阶段事件', async () => {
    const unit = validKnowledgeUnit({ unitId: 'cached-unit' });
    const fetchState = installDailyFetch({ candidate: unit, review: unit });

    try {
        const env = createLegacyMimoTextEnv();
        const first = await readStreamEvents(await callDailyStream(
            { action: 'daily', sessionId: 'daily-warm', watched: watchedMovies(), forceRefresh: true },
            env,
        ));
        assert.equal(first.at(-1).type, 'result');
        assert.equal(fetchState.requests.length, 2);

        // 同一 env 再请求：命中上面写入的当天缓存，秒开
        const second = await readStreamEvents(await callDailyStream(
            { action: 'daily', sessionId: 'daily-warm-2', watched: watchedMovies() },
            env,
        ));
        assert.deepEqual(second.map(event => event.type), ['result']);
        assert.equal(second[0].daily.unitId, 'cached-unit');
        assert.equal(fetchState.requests.length, 2, '缓存命中不许再打上游');
        assert.equal(second[0].quota, undefined, '缓存命中不消耗配额，也不该回带新配额');
    } finally {
        fetchState.restore();
    }
});

test('daily stream 上游全挂时下发确定性兜底 result，而不是 error', async () => {
    const originalFetch = globalThis.fetch;
    globalThis.fetch = async (_input, init = {}) => {
        if (init.method === 'HEAD') return new Response(null, { status: 204 });
        return new Response('upstream down', { status: 500 });
    };
    try {
        const env = createLegacyMimoTextEnv();
        const response = await callDailyStream(
            { action: 'daily', sessionId: 'daily-fallback', watched: watchedMovies(), forceRefresh: true },
            env,
        );
        const events = await readStreamEvents(response);
        assert.equal(events.some(event => event.type === 'error'), false);
        const result = events.at(-1);
        assert.equal(result.type, 'result');
        // 兜底单元也要是完整可渲染的知识（客户端不允许出现空白卡）
        assert.ok(result.daily.unitId);
        assert.ok(result.daily.title);
        assert.ok(result.daily.explanation);
    } finally {
        globalThis.fetch = originalFetch;
    }
});

test('daily stream 前置参数非法时不开流，直接抛给外层按 JSON 错误返回', async () => {
    const env = createLegacyMimoTextEnv();
    // readDailyContext 在开流前先跑，所以参数非法时流根本没建立，外层仍按普通 JSON 错误返回 400
    await assert.rejects(
        () => callDailyStream({ action: 'daily', locale: 'fr-FR', watched: watchedMovies() }, env),
        (error) => error.code === 'INVALID_REQUEST' && error.statusCode === 400,
    );
});

// ── 2026-09 质量审计后的新增门槛 ─────────────────────────────────────────────

test('daily rejects an explanation that invents a character or actor name', () => {
    const movies = [{ title: 'Movie 1', mediaType: 'movie', evidence: ['上映年份：2020', '简介：影片包含群体讨论场景。'] }];
    const invoked = validKnowledgeUnit({
        explanation: '主角范电影（张译饰）在片中反复观察他人的选择，这一段没有出现在输入材料里。',
    });
    assert.throws(
        () => normalizeDailyKnowledgeUnit(invoked, { day: '2026-09-12', locale: 'zh-CN', movies }),
        /没有的人物名/,
    );

    // 演员署名式编造（XXX 饰）同样判废
    const castCrew = validKnowledgeUnit({
        explanation: '片中人物（李某某饰）在讨论中保持沉默，这一点输入材料并未提及。',
    });
    assert.throws(
        () => normalizeDailyKnowledgeUnit(castCrew, { day: '2026-09-12', locale: 'zh-CN', movies }),
        /没有的人物名/,
    );

    // 只写通称「主角」不触发门槛
    const generic = validKnowledgeUnit({
        explanation: '主角在讨论中保持沉默，这一现象可以用从众压力解释。',
    });
    assert.doesNotThrow(() => normalizeDailyKnowledgeUnit(generic, { day: '2026-09-12', locale: 'zh-CN', movies }));

    // 输入材料里真实存在的名字不受影响
    const real = validKnowledgeUnit({
        explanation: '主角张译在讨论中保持沉默，输入材料确实给出了这个名字。',
    });
    assert.doesNotThrow(() => normalizeDailyKnowledgeUnit(real, {
        day: '2026-09-12',
        locale: 'zh-CN',
        movies: [{ title: 'Movie 1', mediaType: 'movie', evidence: ['简介：由张译主演，影片包含群体讨论场景。'] }],
    }));
});

test('illustrationBrief is optional, must be ASCII English, and survives normalization', () => {
    const movies = [{ title: 'Movie 1', mediaType: 'movie', evidence: ['上映年份：2020', '简介：影片包含群体讨论场景。'] }];
    const ok = normalizeDailyKnowledgeUnit(
        validKnowledgeUnit({ illustrationBrief: 'a translucent film ribbon dissolving into soft light' }),
        { day: '2026-09-12', locale: 'zh-CN', movies },
    );
    assert.equal(ok.illustrationBrief, 'a translucent film ribbon dissolving into soft light');

    // 中文 brief 不判废，但会被丢弃（图片模型会把 CJK 画成乱码文字）
    const chinese = normalizeDailyKnowledgeUnit(
        validKnowledgeUnit({ illustrationBrief: '一把伞与它的影子' }),
        { day: '2026-09-12', locale: 'zh-CN', movies },
    );
    assert.equal(chinese.illustrationBrief, null);

    // 缺失时也是 null，不报错
    const missing = normalizeDailyKnowledgeUnit(validKnowledgeUnit(), { day: '2026-09-12', locale: 'zh-CN', movies });
    assert.equal(missing.illustrationBrief, null);

    // 会诱导图片模型画人物/数字/场景的隐喻整条丢弃：真上游出图里出现过罗马数字表盘和插画人物
    for (const risky of [
        'a fragmented mirror reflecting disjointed scenes of a clock face',
        'a calendar page falling apart',
        'two people talking in an empty theatre',
    ]) {
        const dropped = normalizeDailyKnowledgeUnit(
            validKnowledgeUnit({ illustrationBrief: risky }),
            { day: '2026-09-12', locale: 'zh-CN', movies },
        );
        assert.equal(dropped.illustrationBrief, null, risky);
    }

    // 正常的物象隐喻仍保留（"surface" 里含 "face" 但不能被误杀）
    const kept = normalizeDailyKnowledgeUnit(
        validKnowledgeUnit({ illustrationBrief: 'a matte surface of film ribbon folding into soft waves' }),
        { day: '2026-09-12', locale: 'zh-CN', movies },
    );
    assert.equal(kept.illustrationBrief, 'a matte surface of film ribbon folding into soft waves');
});

test('placeholder source keeps no link even when the model copies the example URL', () => {
    const movies = [{ title: 'Movie 1', mediaType: 'movie', evidence: ['上映年份：2020', '简介：影片包含群体讨论场景。'] }];
    // 真上游实测：模型会照抄形状示例，写出「AI 综合解读 + britannica.com 首页」。
    const copied = normalizeDailyKnowledgeUnit(
        validKnowledgeUnit({
            source: {
                name: 'AI 综合解读',
                url: 'https://www.britannica.com/',
                evidence: '本节由 AI 综合公开通识整理，未引用具体来源。',
            },
        }),
        { day: '2026-09-12', locale: 'zh-CN', movies },
    );
    assert.equal(copied.source.name, 'AI 综合解读');
    assert.equal(copied.source.url, '');
    assert.equal(copied.source.evidence, '本节由 AI 综合公开通识整理，未引用具体来源。');
});

test('强证据学科看来源是否真实可核验，不再卡 evidenceMode 的写法', () => {
    const movies = [{ title: '星际穿越', mediaType: 'movie', evidence: ['上映年份：2014', '简介：团队穿越虫洞寻找适合人类生存的行星。'] }];
    const strong = overrides => validKnowledgeUnit({
        unitId: 'strong-subject-unit',
        relationType: 'general_knowledge',
        subjectGroup: 'science_and_nature',
        subject: '物理',
        concept: '时间膨胀',
        title: '强引力下时间为什么会变慢',
        takeaway: '引力越强，观察者测得的时间流逝越慢，这是广义相对论的推论。',
        relatedMedia: null,
        checkQuestion: {
            prompt: '强引力环境下的时间流逝会怎样变化？',
            options: [
                { id: 'a', text: '与地球上完全一致' },
                { id: 'b', text: '强引力下的时钟走得更慢' },
                { id: 'c', text: '时间会倒流' },
            ],
            correctOptionIds: ['b'],
            explanation: '时间膨胀说明强引力场附近的时钟走得更慢，星球上一小时可以对应地球上的数年。',
        },
        filmEvidence: '影片用强引力星球上的时间差呈现这一效应。',
        explanation: '时间膨胀说明强引力场附近的时钟走得更慢，因此星球上一小时对应地球上数年。',
        realWorldExample: 'GPS 卫星必须按相对论修正时钟，否则定位每天会偏出数公里。',
        boundary: '这是来源支持的物理说明，不是对剧情的解读。',
        source: { name: 'Encyclopaedia Britannica', url: 'https://www.britannica.com/science/time-dilation', evidence: '该条目说明引力时间膨胀的机制与观测证据。' },
        ...overrides,
    });

    // 模型常写的组合：用 film_fact 框定「影片里的物理」，同时给出真实条目 —— 应当通过
    assert.doesNotThrow(() => normalizeDailyKnowledgeUnit(
        strong({ evidenceMode: 'film_fact' }),
        { day: '2026-09-12', locale: 'zh-CN', movies },
    ));

    // 真正要拦的：强证据学科却没有真实来源
    assert.throws(
        () => normalizeDailyKnowledgeUnit(
            strong({ source: { name: 'AI 综合解读', url: '', evidence: '本节由 AI 综合公开通识整理，未引用具体来源。' } }),
            { day: '2026-09-12', locale: 'zh-CN', movies },
        ),
        /evidence-enhanced subject/,
    );
    assert.throws(
        () => normalizeDailyKnowledgeUnit(
            strong({ source: { name: '某电影资料馆', url: 'https://www.cafdc.cn/entry', evidence: '该资料说明时间膨胀。' } }),
            { day: '2026-09-12', locale: 'zh-CN', movies },
        ),
        /evidence-enhanced subject/,
    );
});

test('候选与二审提示词都带「输出前逐条自检」清单', async () => {
    const { dailyCandidateMessages, dailyReviewMessages, normalizeDailyKnowledgeUnit } = await import('../src/ai/daily-knowledge.ts');
    const candidate = dailyCandidateMessages('2026-09-12', 'zh-CN', [], null, []);
    const system = String(candidate[0].content);
    // 判废最集中的三条：难度取值、concept 回填、强证据学科配来源
    assert.match(system, /输出前逐条自检/);
    assert.match(system, /difficulty 只能是小写英文 easy、medium、hard/);
    assert.match(system, /必须原样出现 concept 全文/);
    assert.match(system, /source 必须是可信域内的真实条目/);
    assert.match(system, /"name":"AI 综合解读","url":""/);
    assert.match(system, /不得出现输入材料里没有的人名/);

    // 二审同样带上（review 轮也会被判废，理由相同）
    const unit = normalizeDailyKnowledgeUnit(
        {
            unitId: 'self-check-unit',
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
            boundary: '这是制作历史的说明，不同剧组流程会存在差异。',
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
        },
        { day: '2026-09-12', locale: 'zh-CN', movies: [] },
    );
    const review = dailyReviewMessages(unit, 'zh-CN', [], null, []);
    assert.match(String(review[0].content), /输出前逐条自检/);
});

test('本地判废理由被翻译成中文修复指令再回灌给模型', async () => {
    const { dailyRepairHintFor, dailyCandidateMessages } = await import('../src/ai/daily-knowledge.ts');

    // 门槛抛的是英文错误串（日志/健康事件按它聚合），回灌给中文提示词时必须变成可执行指令
    const spoiler = dailyRepairHintFor('AI daily knowledge unit is invalid: spoilerLevel understates plot disclosure');
    assert.match(spoiler, /spoilerLevel/);
    assert.match(spoiler, /spoilerLevel 写成了 none/);
    assert.ok(!spoiler.includes('understates plot disclosure'), '不该把英文原话当指令回灌');

    const names = dailyRepairHintFor('AI daily knowledge unit is invalid: explanation或realWorldExample出现了输入材料里没有的人物名（禁编造演职员/角色名）');
    assert.match(names, /改用「主角」/);

    // 未收录的理由按原文回灌，不能吞掉（否则模型完全不知道发生了什么）
    assert.equal(dailyRepairHintFor('AI daily knowledge unit is invalid: some brand new gate'), 'AI daily knowledge unit is invalid: some brand new gate');

    // 端到端：修复轮里确实带着中文指令
    const messages = dailyCandidateMessages('2026-09-12', 'zh-CN', [], spoiler, []);
    const user = String(messages[1].content);
    assert.match(user, /修复要求｜优先级最高/);
    assert.match(user, /spoilerLevel 写成了 none/);
});

test('daily prompts carry the trusted source catalog and the recent-usage list', async () => {
    const { dailyCandidateMessages } = await import('../src/ai/daily-knowledge.ts');
    const messages = dailyCandidateMessages('2026-09-12', 'zh-CN', [], null, [
        { day: '2026-09-11', concept: '胶片作为记忆载体', subject: '电影学', mediaTitle: '一秒钟' },
    ]);
    const system = String(messages[0].content);
    const user = String(messages[1].content);
    assert.match(system, /www\.britannica\.com/);
    assert.match(system, /禁止编造机构域名/);
    assert.match(system, /illustrationBrief/);
    // 无来源占位必须是「空 url」，否则模型会照抄示例网址造出半真引用。
    assert.match(system, /"name":"AI 综合解读","url":""/);
    assert.doesNotMatch(system, /"url":"https:\/\/www\.britannica\.com\/"/);
    assert.match(user, /最近已出，必须避开/);
    assert.match(user, /一秒钟/);
});
