import test from 'node:test';
import assert from 'node:assert/strict';
import { handleAiApi } from '../src/ai/handler.ts';
import { DAILY_ILLUSTRATION_STYLE_VERSION, handleAiIllustration, publicDailyIllustration } from '../src/ai/daily-illustration.ts';
import { fallbackDailyKnowledgeUnit } from '../src/ai/daily-knowledge.ts';

function today() {
    return new Intl.DateTimeFormat('en-CA', { timeZone: 'Asia/Shanghai' }).format(new Date());
}

function createTestEnv(overrides = {}) {
    // 健康事件表：接住 INSERT 并捕获（callLlmJson 轮替每次尝试都会 recordHealthEvent），
    // 其他 SQL 维持原行为（直接 throw，证明测试模式不应触达它们）。
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
        healthInserts,
        KV: { async get() { return null; }, async put() {} },
        JWT_SIGNING_KEY: 'test-jwt-secret',
        AI_TEST_MODE: true,
        AI_TEST_CACHE: new Map(),
        AI_TEST_QUOTA: new Map(),
        AI_TEST_ILLUSTRATION_STATE: new Map(),
        AI_TEST_ILLUSTRATION_RATE_LIMIT: new Map(),
        AI_DAILY_ILLUSTRATION_ENABLED: true,
        AI_DAILY_ILLUSTRATION_DAILY_LIMIT: 10,
        AGNES_API_KEYS: 'test-agnes-key',
        ...overrides,
    };
}

function createBackground() {
    const tasks = [];
    return {
        tasks,
        waitUntil(promise) { tasks.push(promise); },
    };
}

function createImageBucket({ failPut = false } = {}) {
    const objects = new Map();
    return {
        objects,
        failPut,
        async head(key) {
            const object = objects.get(key);
            return object ? { key, size: object.bytes.byteLength, httpMetadata: object.httpMetadata } : null;
        },
        async put(key, value, options = {}) {
            if (failPut) throw new Error('R2 write failed');
            const bytes = new Uint8Array(await new Response(value).arrayBuffer());
            objects.set(key, {
                bytes,
                httpMetadata: options.httpMetadata || { contentType: 'application/octet-stream' },
                customMetadata: options.customMetadata || {},
            });
            return { key, size: bytes.byteLength };
        },
        async get(key) {
            const object = objects.get(key);
            if (!object) return null;
            return {
                body: new Blob([object.bytes]).stream(),
                arrayBuffer: async () => object.bytes.slice().buffer,
                writeHttpMetadata(headers) {
                    if (object.httpMetadata?.contentType) headers.set('Content-Type', object.httpMetadata.contentType);
                },
                httpMetadata: object.httpMetadata,
                httpEtag: '"test-illustration-etag"',
            };
        },
    };
}

function validPngBytes(width = 1312, height = 736) {
    const bytes = new Uint8Array(1024);
    for (let index = 0; index < bytes.length; index += 1) bytes[index] = (index * 37 + 11) & 0xff;
    bytes.set([0x89, 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a], 0);
    const writeUInt32BE = (offset, value) => {
        bytes[offset] = (value >>> 24) & 0xff;
        bytes[offset + 1] = (value >>> 16) & 0xff;
        bytes[offset + 2] = (value >>> 8) & 0xff;
        bytes[offset + 3] = value & 0xff;
    };
    writeUInt32BE(16, width);
    writeUInt32BE(20, height);
    return bytes;
}

function toBase64(bytes) {
    return btoa(String.fromCharCode(...bytes));
}

function installDailyFetch({ imageMode = 'valid', unit = null, isFailure = null } = {}) {
    const originalFetch = globalThis.fetch;
    const imageRequests = [];
    let imageCalls = 0;
    let knowledgeUnit = unit ?? fallbackDailyKnowledgeUnit(today(), 'zh-CN');
    globalThis.fetch = async (input, init = {}) => {
        const url = new URL(typeof input === 'string' ? input : input.url);
        if (init.method === 'HEAD') return new Response(null, { status: 204 });
        if (url.pathname.endsWith('/chat/completions')) {
            return new Response(JSON.stringify({
                choices: [{ message: { content: JSON.stringify(knowledgeUnit) } }],
            }), { status: 200, headers: { 'Content-Type': 'application/json' } });
        }
        if (url.pathname.endsWith('/images/generations')) {
            imageCalls += 1;
            const body = JSON.parse(init.body);
            imageRequests.push(body);
            const mode = imageMode;
            if (isFailure?.()) return new Response('upstream failed', { status: 502 });
            if (mode === 'error') return new Response('upstream failed', { status: 502 });
            if (mode === 'html') {
                const html = new TextEncoder().encode('<html><body>not an image</body></html>');
                return new Response(JSON.stringify({ data: [{ b64_json: toBase64(html) }] }), {
                    status: 200,
                    headers: { 'Content-Type': 'application/json' },
                });
            }
            if (mode === 'url-only') {
                return new Response(JSON.stringify({ data: [{ url: 'https://storage.googleapis.com/agnes-aigc/temp.png' }] }), {
                    status: 200,
                    headers: { 'Content-Type': 'application/json' },
                });
            }
            return new Response(JSON.stringify({ data: [{ b64_json: toBase64(validPngBytes()) }] }), {
                status: 200,
                headers: { 'Content-Type': 'application/json' },
            });
        }
        throw new Error(`unexpected fetch in illustration test: ${url.pathname}`);
    };
    return {
        imageRequests,
        get imageCalls() { return imageCalls; },
        setUnit(next) { knowledgeUnit = next; },
        restore() { globalThis.fetch = originalFetch; },
    };
}

async function callDaily(env, background, { forceRefresh = true } = {}) {
    const request = new Request('https://gateway.test/api/ai/daily', {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ action: 'daily', sessionId: 'illustration-session', forceRefresh }),
    });
    const response = await handleAiApi(request, env, 'request-1', '/api/ai/daily', {
        sub: 'friend-1',
        device: 'device-1',
    }, background);
    return { response, json: await response.json() };
}

/**
 * 可控时钟：状态机里的租约/冷却都是分钟级窗口，直接睡真实时间既慢又不稳。
 * 依赖 src/util/errors.ts 的 __setNowForTests（只影响 now()，Date.now() 仍是真实值）。
 * base 默认取当前真实秒；调用方通常先快照 base，再把状态行的 updatedAt 钉在同一基准上。
 */
async function withStubbedNow(offsetSeconds, fn, base = Math.floor(Date.now() / 1000)) {
    const { __setNowForTests } = await import('../src/util/errors.ts');
    __setNowForTests(() => base + offsetSeconds);
    try {
        return await fn(base);
    } finally {
        __setNowForTests(null);
    }
}

function illustrationStates(env) {
    return [...env.AI_TEST_ILLUSTRATION_STATE.values()];
}

test('daily text returns immediately, then illustration becomes ready with a controlled R2 URL', async () => {
    const fetchState = installDailyFetch();
    try {
        const bucket = createImageBucket();
        const background = createBackground();
        const env = createTestEnv({ AI_IMAGE_CACHE: bucket });
        const first = await callDaily(env, background);
        assert.equal(first.response.status, 200);
        assert.equal(first.json.data.illustration.status, 'generating');
        assert.ok(first.json.data.title);
        assert.ok(first.json.data.fact);
        assert.equal(first.json.data.illustration.url, null);

        await Promise.all(background.tasks);
        assert.equal(fetchState.imageCalls, 1);
        const state = illustrationStates(env)[0];
        assert.equal(state.status, 'ready');
        assert.equal(state.mimeType, 'image/png');
        assert.equal(state.width, 1312);
        assert.equal(state.height, 736);
        assert.equal(bucket.objects.size, 1);
        const objectKey = [...bucket.objects.keys()][0];
        assert.match(objectKey, /^daily-illustration-v2\/[a-f0-9]{64}\.png$/);

        const second = await callDaily(env, background, { forceRefresh: false });
        assert.equal(second.response.status, 200);
        assert.equal(second.json.data.illustration.status, 'ready');
        assert.ok(second.json.data.illustration.url.startsWith('https://gateway.test/api/ai/illustration/'));
        assert.equal(second.json.data.illustration.mimeType, 'image/png');
        assert.equal(fetchState.imageCalls, 1, 'ready state must be idempotent');

        const token = second.json.data.illustration.url.split('/api/ai/illustration/')[1];
        const imageResponse = await handleAiIllustration(env, token);
        assert.equal(imageResponse.status, 200);
        assert.equal(imageResponse.headers.get('Content-Type'), 'image/png');
        assert.equal((await imageResponse.arrayBuffer()).byteLength, 1024);

        const prompt = fetchState.imageRequests[0].prompt;
        // 提示词按 v3 契约：人物/文字禁令在，且禁令里不再点名具体物件（点名会被画出来）。
        assert.match(prompt, /no real actors/i);
        assert.match(prompt, /Strictly no people/i);
        assert.match(prompt, /Strictly no text or text-like marks anywhere/i);
        assert.match(prompt, /Do not imitate historical photographs/);
        assert.match(prompt, /Any frame, pane or reflective surface in the composition must stay blank or purely abstract/);
        assert.match(prompt, /Do not draw dials, gauges or calendars/);
        assert.doesNotMatch(prompt, /film-strip edge codes/);
        assert.doesNotMatch(prompt, /film strips, reels, frames, panels/);
    } finally {
        fetchState.restore();
    }
});

test('concurrent background tasks single-flight the same unit', async () => {
    const fetchState = installDailyFetch();
    try {
        const env = createTestEnv({ AI_IMAGE_CACHE: createImageBucket() });
        const unit = fallbackDailyKnowledgeUnit(today(), 'zh-CN');
        const { generateDailyIllustrationInBackground } = await import('../src/ai/daily-illustration.ts');
        await Promise.all([
            generateDailyIllustrationInBackground(env, unit, 'friend-1'),
            generateDailyIllustrationInBackground(env, unit, 'friend-1'),
        ]);
        assert.equal(fetchState.imageCalls, 1);
        assert.equal(illustrationStates(env)[0].status, 'ready');
    } finally {
        fetchState.restore();
    }
});

test('provider failure retries once, marks unavailable, and keeps text successful', async () => {
    const fetchState = installDailyFetch({ imageMode: 'error' });
    try {
        const bucket = createImageBucket();
        const background = createBackground();
        const env = createTestEnv({ AI_IMAGE_CACHE: bucket });
        const first = await callDaily(env, background);
        assert.equal(first.response.status, 200);
        await Promise.all(background.tasks);

        assert.equal(fetchState.imageCalls, 2);
        assert.equal(bucket.objects.size, 0);
        const state = illustrationStates(env)[0];
        assert.equal(state.status, 'unavailable');
        assert.equal(state.lastErrorCode, 'PROVIDER_FAILED');

        const second = await callDaily(env, background, { forceRefresh: false });
        assert.equal(second.response.status, 200);
        assert.equal(second.json.data.illustration.status, 'unavailable');
        assert.ok(second.json.data.title);
        assert.equal(fetchState.imageCalls, 2, 'unavailable state must not retry automatically');
    } finally {
        fetchState.restore();
    }
});

test('non-image and URL-only Agnes outputs are rejected without R2 persistence', async () => {
    for (const imageMode of ['html', 'url-only']) {
        const fetchState = installDailyFetch({ imageMode });
        try {
            const bucket = createImageBucket();
            const env = createTestEnv({ AI_IMAGE_CACHE: bucket });
            const unit = fallbackDailyKnowledgeUnit(today(), 'zh-CN');
            const { generateDailyIllustrationInBackground } = await import('../src/ai/daily-illustration.ts');
            await generateDailyIllustrationInBackground(env, unit, 'friend-1');
            assert.equal(fetchState.imageCalls, 2);
            assert.equal(bucket.objects.size, 0);
            const state = illustrationStates(env)[0];
            assert.equal(state.status, 'unavailable');
            assert.equal(state.lastErrorCode, 'INVALID_IMAGE');
        } finally {
            fetchState.restore();
        }
    }
});

test('R2 write failure retries once and degrades without affecting text', async () => {
    const fetchState = installDailyFetch();
    try {
        const bucket = createImageBucket({ failPut: true });
        const env = createTestEnv({ AI_IMAGE_CACHE: bucket });
        const unit = fallbackDailyKnowledgeUnit(today(), 'zh-CN');
        const { generateDailyIllustrationInBackground } = await import('../src/ai/daily-illustration.ts');
        await generateDailyIllustrationInBackground(env, unit, 'friend-1');
        assert.equal(fetchState.imageCalls, 2);
        assert.equal(bucket.objects.size, 0);
        const state = illustrationStates(env)[0];
        assert.equal(state.status, 'unavailable');
        assert.equal(state.lastErrorCode, 'STORAGE_FAILED');
    } finally {
        fetchState.restore();
    }
});

test('independent illustration rate limit stops the second unit after one daily image', async () => {
    const fetchState = installDailyFetch();
    try {
        const env = createTestEnv({
            AI_IMAGE_CACHE: createImageBucket(),
            AI_DAILY_ILLUSTRATION_DAILY_LIMIT: 1,
        });
        const { generateDailyIllustrationInBackground } = await import('../src/ai/daily-illustration.ts');
        const firstUnit = fallbackDailyKnowledgeUnit(today(), 'zh-CN');
        const secondUnit = { ...firstUnit, unitId: `${firstUnit.unitId}-second` };
        await generateDailyIllustrationInBackground(env, firstUnit, 'friend-1');
        await generateDailyIllustrationInBackground(env, secondUnit, 'friend-1');

        assert.equal(fetchState.imageCalls, 1);
        const states = illustrationStates(env);
        assert.equal(states.find(state => state.status === 'ready')?.status, 'ready');
        assert.equal(states.find(state => state.status === 'unavailable')?.lastErrorCode, 'RATE_LIMITED');
    } finally {
        fetchState.restore();
    }
});

test('disabled provider never calls Agnes images and returns unavailable', async () => {
    const fetchState = installDailyFetch();
    try {
        const background = createBackground();
        const env = createTestEnv({
            AI_DAILY_ILLUSTRATION_ENABLED: false,
            AI_IMAGE_CACHE: createImageBucket(),
        });
        const result = await callDaily(env, background);
        assert.equal(result.response.status, 200);
        assert.equal(result.json.data.illustration.status, 'unavailable');
        assert.equal(result.json.data.illustration.url, null);
        assert.equal(fetchState.imageCalls, 0);
        // 禁用插图时 background 里只允许出现健康写入任务（callLlmJson 轮替的 recordHealthEvent），
        // 不允许出现插图生成任务：每个健康写入都会同步捕获一条 INSERT，故
        // await 全部任务后 tasks.length 必须等于 healthInserts.length，否则存在非健康任务。
        await Promise.allSettled(background.tasks);
        assert.equal(background.tasks.length, env.healthInserts.length);
        assert.ok(background.tasks.length > 0 || env.healthInserts.length === 0);
        assert.equal(env.AI_TEST_ILLUSTRATION_STATE.size, 0);
    } finally {
        fetchState.restore();
    }
});

test('legacy daily cache keeps the old response contract without illustration fields', async () => {
    const originalFetch = globalThis.fetch;
    globalThis.fetch = async () => { throw new Error('legacy cache must not call AI'); };
    try {
        const env = createTestEnv({ AI_DAILY_ILLUSTRATION_ENABLED: true });
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
        const result = await callDaily(env, createBackground(), { forceRefresh: false });
        assert.equal(result.response.status, 200);
        assert.equal(result.json.data.title, '旧每日冷知识');
        assert.equal(result.json.data.sourceUrl, 'https://example.com/old');
        assert.equal(result.json.data.illustration, undefined);
    } finally {
        globalThis.fetch = originalFetch;
    }
});

test('technical validation accepts a valid PNG and rejects non-image or invalid dimensions', async () => {
    const { validateConceptIllustration } = await import('../src/ai/daily-illustration.ts');
    const valid = validateConceptIllustration(validPngBytes());
    assert.equal(valid.mimeType, 'image/png');
    assert.equal(valid.width, 1312);
    assert.equal(valid.height, 736);

    const html = new Uint8Array(1024);
    html.set(new TextEncoder().encode('<html>not an image</html>'), 0);
    assert.throws(() => validateConceptIllustration(html), /Illustration format is invalid/);

    const tooSmall = validPngBytes(100, 100);
    assert.throws(() => validateConceptIllustration(tooSmall), /dimensions or content are invalid/);
});

test('expired generating lease with attempts left retries and reaches ready', async () => {
    const fetchState = installDailyFetch();
    try {
        const env = createTestEnv({ AI_IMAGE_CACHE: createImageBucket() });
        const unit = fallbackDailyKnowledgeUnit(today(), 'zh-CN');
        const stateKey = JSON.stringify([unit.unitId, unit.locale, DAILY_ILLUSTRATION_STYLE_VERSION]);
        env.AI_TEST_ILLUSTRATION_STATE.set(stateKey, {
            status: 'generating',
            attemptCount: 1,
            objectKey: null,
            mimeType: null,
            width: null,
            height: null,
            sizeBytes: null,
            lastErrorCode: 'PROVIDER_FAILED',
            updatedAt: Math.floor(Date.now() / 1000) - 700,
        });
        const { generateDailyIllustrationInBackground } = await import('../src/ai/daily-illustration.ts');
        await generateDailyIllustrationInBackground(env, unit, 'friend-1');
        assert.equal(fetchState.imageCalls, 1);
        const state = illustrationStates(env)[0];
        assert.equal(state.status, 'ready');
        assert.equal(state.attemptCount, 2);
    } finally {
        fetchState.restore();
    }
});

test('expired generating state with exhausted attempts closes to unavailable without another provider call', async () => {
    const fetchState = installDailyFetch();
    try {
        const env = createTestEnv({ AI_IMAGE_CACHE: createImageBucket() });
        const unit = fallbackDailyKnowledgeUnit(today(), 'zh-CN');
        const stateKey = JSON.stringify([unit.unitId, unit.locale, DAILY_ILLUSTRATION_STYLE_VERSION]);
        env.AI_TEST_ILLUSTRATION_STATE.set(stateKey, {
            status: 'generating',
            attemptCount: 2,
            objectKey: null,
            mimeType: null,
            width: null,
            height: null,
            sizeBytes: null,
            lastErrorCode: 'PROVIDER_FAILED',
            updatedAt: Math.floor(Date.now() / 1000) - 700,
        });
        const { generateDailyIllustrationInBackground } = await import('../src/ai/daily-illustration.ts');
        await generateDailyIllustrationInBackground(env, unit, 'friend-1');
        assert.equal(fetchState.imageCalls, 0, '次数耗尽后不得再调用上游');
        const state = illustrationStates(env)[0];
        assert.equal(state.status, 'unavailable');
        assert.equal(state.lastErrorCode, 'ATTEMPTS_EXHAUSTED');
    } finally {
        fetchState.restore();
    }
});

test('invalid illustration tokens are rejected', async () => {
    const env = createTestEnv({ AI_IMAGE_CACHE: createImageBucket() });
    await assert.rejects(
        () => handleAiIllustration(env, 'not-a-token'),
        /Invalid or expired illustration token/,
    );
});

test('illustration prompt uses the visual brief and never the narrative text', async () => {
    const fetchState = installDailyFetch();
    try {
        const env = createTestEnv({ AI_IMAGE_CACHE: createImageBucket() });
        const { buildConceptIllustrationPrompt, deriveVisualBrief } = await import('../src/ai/daily-illustration.ts');

        const withBrief = {
            unitId: 'u_prompt_brief',
            locale: 'zh-CN',
            concept: '胶片如何保存记忆',
            takeaway: '胶片把抽象记忆变成可触摸的实体，这一句绝不该出现在图片提示词里。',
            explanation: '这段叙事解释同样不该出现，它会把模型推向复刻具体场景与人物。',
            brief: 'a translucent film ribbon dissolving into soft light',
        };
        const prompt = buildConceptIllustrationPrompt(withBrief);
        assert.match(prompt, /a translucent film ribbon dissolving into soft light/);
        assert.ok(!prompt.includes(withBrief.takeaway), '结论文本不得进入图片提示词');
        assert.ok(!prompt.includes(withBrief.explanation), '解释文本不得进入图片提示词');
        assert.match(prompt, /no people/i);
        assert.match(prompt, /no text/i);
        // 实测：禁令里点名「胶片边缘编号」反而会让模型画出胶片并带上边缘标记，所以改成通用写法。
        assert.doesNotMatch(prompt, /edge codes/i);

        // 中文 brief 一律丢弃（CJK 提示词会被画成乱码文字），退回通用抽象构图。
        const chineseBrief = deriveVisualBrief({ ...withBrief, concept: '能指与所指', brief: '一把伞与它的影子' });
        assert.ok(!/[\u4e00-\u9fff]/u.test(chineseBrief), 'CJK brief 必须被丢弃');
        assert.match(chineseBrief, /abstract/i);

        // 抽象概念同样退回通用构图，而不是把概念原文塞进提示词。
        const abstractBrief = deriveVisualBrief({ ...withBrief, concept: '记忆在电影中的视觉化呈现与叙事功能', brief: null });
        assert.match(abstractBrief, /layered geometric shapes/i);

        // 没有可用 brief 时退到通用抽象构图（刻意不带中文概念：混进英文提示词会被画成乱码文字）。
        const genericBrief = deriveVisualBrief({ ...withBrief, concept: '胶片', brief: null });
        assert.ok(!/[一-鿿]/u.test(genericBrief));
    } finally {
        fetchState.restore();
    }
});

test('reused unitId with a new concept does not serve the stale illustration', async () => {
    const first = installDailyFetch();
    try {
        const env = createTestEnv({ AI_IMAGE_CACHE: createImageBucket() });
        const base = fallbackDailyKnowledgeUnit(today(), 'zh-CN');
        const firstUnit = { ...base, unitId: 'u_reused_id', concept: '胶片与记忆', illustrationBrief: 'a film ribbon in soft light' };
        first.setUnit(firstUnit);
        // 首轮在真实时间下跑完，避免它落库时读到被推进过的时钟。
        const background = createBackground();
        const initial = await callDaily(env, background);
        assert.equal(initial.json.data.illustration.status, 'generating');
        await Promise.all(background.tasks);
        assert.equal(first.imageCalls, 1);
        const firstObjectKey = [...env.AI_IMAGE_CACHE.objects.keys()][0];

        // 同一个 unitId 换概念（模型自造 id 的常见复用）：必须当作未生成，重新画一张。
        // 断言分两段：①装配层把旧 ready 判为过期（响应回 generating）；②后台任务画的是新图
        // （对象键不同）。直接跑后台会因为 ready 行带租约保护而被跳过，所以先走响应装配。
        const secondUnit = { ...base, unitId: 'u_reused_id', concept: '媒体如何设置议题', illustrationBrief: 'concentric rings spreading across a still pond' };
        first.setUnit(secondUnit);
        const buildSecondResponse = async () => {
            // 用空 KV 缓存直接调装配层：真实路径里 daily 文本缓存会命中旧响应，看不到重新装配。
            const emptyCacheEnv = { ...env, AI_TEST_CACHE: new Map() };
            const background = createBackground();
            const payload = { unitId: 'u_reused_id', locale: 'zh-CN', concept: secondUnit.concept, illustrationBrief: secondUnit.illustrationBrief };
            const illustration = await publicDailyIllustration(emptyCacheEnv, payload, 'friend-1', 'https://gateway.test', background);
            return { illustration, background };
        };
        const { illustration, background: secondBackground } = await withStubbedNow(700, buildSecondResponse);
        assert.equal(illustration.status, 'generating', '内容变了不得直接复用旧图');
        await Promise.all(secondBackground.tasks);
        assert.equal(first.imageCalls, 2, '内容变化应触发一次新的图片生成');
        const objectKeys = [...env.AI_IMAGE_CACHE.objects.keys()];
        assert.equal(objectKeys.length, 2);
        assert.notEqual(objectKeys[1], firstObjectKey, '新内容必须落到不同的对象键');
    } finally {
        first.restore();
    }
});

test('unavailable illustration retries once after the cooldown window', async () => {
    // 第一次生成失败（上游 502）→ 状态落 unavailable；冷却到点后必须允许再来一轮，
    // 否则生产里那 3/4 次 PROVIDER_FAILED 会让用户当天永远看不到图。
    let failing = true;
    const fetchState = installDailyFetch({ isFailure: () => failing });
    try {
        const { generateDailyIllustrationInBackground } = await import('../src/ai/daily-illustration.ts');
        const env = createTestEnv({ AI_IMAGE_CACHE: createImageBucket() });
        const unit = fallbackDailyKnowledgeUnit(today(), 'zh-CN');
        const stateKey = JSON.stringify([unit.unitId, unit.locale, DAILY_ILLUSTRATION_STYLE_VERSION]);
        const base = Math.floor(Date.now() / 1000);
        const unitInput = { unitId: unit.unitId, locale: unit.locale, concept: unit.concept, takeaway: unit.takeaway, explanation: unit.explanation };

        // 第一轮：两次尝试都失败 → unavailable
        await generateDailyIllustrationInBackground(env, unit, 'friend-1');
        assert.equal(fetchState.imageCalls, 2, '失败后应重试一次再放弃');
        assert.equal(env.AI_TEST_ILLUSTRATION_STATE.get(stateKey).status, 'unavailable');

        // 冷却期内：装配层不得重新调度
        await withStubbedNow(60, async () => {
            const background = createBackground();
            const illustration = await publicDailyIllustration(env, unitInput, 'friend-1', 'https://gateway.test', background);
            assert.equal(illustration.status, 'unavailable', '冷却期内保持不可用');
            assert.equal(background.tasks.length, 0, '冷却期内不得重新调度');
        }, base);

        // 冷却到点 + 上游恢复：允许再试一轮并成功
        failing = false;
        const warm = await withStubbedNow(601, async () => {
            const background = createBackground();
            const illustration = await publicDailyIllustration(env, unitInput, 'friend-1', 'https://gateway.test', background);
            return { illustration, background };
        }, base);
        assert.equal(warm.illustration.status, 'generating', '冷却结束后应重新调度');
        await Promise.all(warm.background.tasks);
        assert.equal(fetchState.imageCalls, 3, '冷却结束后应真的再调一次上游（累计 2 次失败 + 1 次成功）');
        assert.equal(env.AI_TEST_ILLUSTRATION_STATE.get(stateKey).status, 'ready');
    } finally {
        fetchState.restore();
    }
});
