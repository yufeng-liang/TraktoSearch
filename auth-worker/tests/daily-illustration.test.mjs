import test from 'node:test';
import assert from 'node:assert/strict';
import { handleAiApi } from '../src/ai/handler.ts';
import { handleAiIllustration } from '../src/ai/daily-illustration.ts';
import { fallbackDailyKnowledgeUnit } from '../src/ai/daily-knowledge.ts';

function today() {
    return new Intl.DateTimeFormat('en-CA', { timeZone: 'Asia/Shanghai' }).format(new Date());
}

function createTestEnv(overrides = {}) {
    return {
        DB: { prepare() { throw new Error('AI test fallback should be used'); } },
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

function installDailyFetch({ imageMode = 'valid', unit = null } = {}) {
    const originalFetch = globalThis.fetch;
    const imageRequests = [];
    let imageCalls = 0;
    const knowledgeUnit = unit ?? fallbackDailyKnowledgeUnit(today(), 'zh-CN');
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
            if (imageMode === 'error') return new Response('upstream failed', { status: 502 });
            if (imageMode === 'html') {
                const html = new TextEncoder().encode('<html><body>not an image</body></html>');
                return new Response(JSON.stringify({ data: [{ b64_json: toBase64(html) }] }), {
                    status: 200,
                    headers: { 'Content-Type': 'application/json' },
                });
            }
            if (imageMode === 'url-only') {
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
        assert.match(objectKey, /^daily-illustration-v1\/[a-f0-9]{64}\.png$/);

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
        assert.match(prompt, /no real actors/);
        assert.match(prompt, /Do not include any text, letters, numbers/);
        assert.match(prompt, /signatures, or watermarks/);
        assert.match(prompt, /Do not imitate historical photographs/);
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
        assert.equal(background.tasks.length, 0);
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

test('invalid illustration tokens are rejected', async () => {
    const env = createTestEnv({ AI_IMAGE_CACHE: createImageBucket() });
    await assert.rejects(
        () => handleAiIllustration(env, 'not-a-token'),
        /Invalid or expired illustration token/,
    );
});
