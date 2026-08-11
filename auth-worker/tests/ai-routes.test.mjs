import test from 'node:test';
import assert from 'node:assert/strict';
import { build } from 'esbuild';
import { handleAiApi } from '../src/ai/handler.ts';
import { callMimoJson } from '../src/ai/mimo.ts';
import { signAccessToken } from '../src/util/jwt.ts';

function createTestEnv(overrides = {}) {
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
        AI_TEST_TRANSCRIPT: '乌萨奇',
        AI_TEST_CACHE: new Map(),
        AI_TEST_QUOTA: new Map(),
        ...overrides,
    };
}

async function authHeader() {
    return `Bearer ${await signAccessToken('test-jwt-secret', 'friend-1', 'device-1')}`;
}

async function call(path, {
    method = 'GET',
    body,
    env,
    authorized = true,
    friendId = 'friend-1',
    deviceId = 'device-1',
    headers: extraHeaders = {},
} = {}) {
    const headers = { ...extraHeaders };
    if (body !== undefined) headers['Content-Type'] = 'application/json';
    const request = new Request(`https://gateway.test${path}`, {
        method,
        headers,
        body: body === undefined ? undefined : JSON.stringify(body),
    });
    let response = null;
    if (authorized) {
        try {
            response = await handleAiApi(request, env ?? createTestEnv(), 'request-1', path, {
                sub: friendId,
                device: deviceId,
            });
        } catch (error) {
            response = errorResponse(error);
        }
    }
    if (!response) throw new Error('Unauthorized calls must be tested through the main router');
    const json = await response.json();
    return { response, json };
}

function createAudioBucket({ failPut = false } = {}) {
    const objects = new Map();
    return {
        objects,
        async head(key) {
            const object = objects.get(key);
            return object ? { key, size: object.bytes.byteLength, customMetadata: object.customMetadata } : null;
        },
        async put(key, value, options = {}) {
            if (failPut) throw new Error('R2 write failed');
            const bytes = new Uint8Array(await new Response(value).arrayBuffer());
            objects.set(key, {
                bytes,
                contentType: options.httpMetadata?.contentType || 'application/octet-stream',
                customMetadata: {
                    expiresAt: String(Math.floor(Date.now() / 1000) + 86_400),
                    ...(options.customMetadata || {}),
                },
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
                    headers.set('Content-Type', object.contentType);
                },
                httpEtag: '"test-etag"',
            };
        },
    };
}

function movies() {
    return Array.from({ length: 7 }, (_, index) => ({
        title: `Movie ${index + 1}`,
        year: 2020 + index,
        genres: ['Drama'],
        rating: 7 + index / 10,
        userRating: 8,
        watchedAt: `202${index % 4}-01-01`,
        mediaIds: { tmdbId: 100 + index },
    }));
}

test('AI character catalog is public through the main router', async () => {
    const worker = await loadMainWorker();
    const response = await worker.default.fetch(
        new Request('https://gateway.test/api/ai/characters'),
        createTestEnv(),
        { waitUntil() {} },
    );
    const json = await response.json();
    assert.equal(response.status, 200);
    assert.equal(json.code, 'SUCCESS');
    assert.equal(json.data.characters.length, 7);
    assert.equal(json.data.sessionLimit, 14);
    assert.equal(json.data.dailyLimit, 80);
});

test('AI protected routes still reject missing JWT', async () => {
    const worker = await loadMainWorker();
    for (const path of ['/api/ai/activate', '/api/ai/greeting', '/api/ai/taste', '/api/ai/quiz', '/api/ai/daily', '/api/ai/quiz/submit']) {
        const method = path === '/api/ai/daily' ? 'GET' : 'POST';
        const response = await worker.default.fetch(
            new Request(`https://gateway.test${path}`, {
                method,
                headers: method === 'POST' ? { 'Content-Type': 'application/json' } : undefined,
                body: method === 'POST' ? '{}' : undefined,
            }),
            createTestEnv(),
            { waitUntil() {} },
        );
        const json = await response.json();
        assert.equal(response.status, 401, path);
        assert.equal(json.code, 'UNAUTHORIZED', path);
    }
});

test('guest audition uses voicedesign MP3 request without consuming quota', async () => {
    const originalFetch = globalThis.fetch;
    globalThis.fetch = async (_input, init) => {
        const requestBody = JSON.parse(init.body);
        assert.equal(requestBody.model, 'mimo-v2.5-tts-voicedesign');
        assert.equal(requestBody.audio.format, 'mp3');
        assert.equal(requestBody.audio.optimize_text_preview, false);
        assert.match(requestBody.messages[0].content, /角色|场景|指导/);
        assert.equal(requestBody.messages[1].role, 'assistant');
        assert.equal(requestBody.messages[1].content, '到！你的片单有点东西。');
        return new Response(JSON.stringify({
            choices: [{ message: { audio: { data: 'AA==', transcript: '到！' } } }],
        }), { status: 200, headers: { 'Content-Type': 'application/json' } });
    };

    try {
        const worker = await loadMainWorker();
        const response = await worker.default.fetch(
            new Request('https://gateway.test/api/ai/tts', {
                method: 'POST',
                headers: { 'Content-Type': 'application/json' },
                body: JSON.stringify({
                    action: 'tts',
                    characterId: 'usagi',
                    text: '到！你的片单有点东西。',
                    scene: 'AUDITION',
                    sessionId: 'guest-audition-session',
                }),
            }),
            createTestEnv({
                MIMO_API_KEY: 'test-mimo-key',
                AI_TEST_VOICE_DESIGN_READY: true,
            }),
            { waitUntil() {} },
        );
        const json = await response.json();
        assert.equal(response.status, 200);
        assert.equal(json.data.audioDataUrl, 'data:audio/mpeg;base64,AA==');
        assert.equal(json.data.transcript, '到！你的片单有点东西。');
        assert.equal(json.quota, undefined);
    } finally {
        globalThis.fetch = originalFetch;
    }
});

test('guest TTS rejects an unknown scene and ignores legacy style override', async () => {
    const env = createTestEnv({
        MIMO_API_KEY: 'test-mimo-key',
        AI_TEST_VOICE_DESIGN_READY: true,
    });
    const invalid = await call('/api/ai/tts', {
        method: 'POST',
        body: { action: 'tts', characterId: 'usagi', text: '到！你的片单有点东西。', scene: 'UNKNOWN' },
        env,
    });
    assert.equal(invalid.response.status, 400);
    assert.equal(invalid.json.code, 'INVALID_SCENE');

    const originalFetch = globalThis.fetch;
    globalThis.fetch = async (_input, init) => {
        const requestBody = JSON.parse(init.body);
        assert.doesNotMatch(JSON.stringify(requestBody.messages), /这是用户自定义的音色/);
        return new Response(JSON.stringify({
            choices: [{ message: { audio: { data: 'AA==', transcript: '到！你的片单有点东西。' } } }],
        }), { status: 200, headers: { 'Content-Type': 'application/json' } });
    };
    try {
        const valid = await call('/api/ai/tts', {
            method: 'POST',
            body: {
                action: 'tts',
                characterId: 'usagi',
                text: '到！你的片单有点东西。',
                scene: 'AUDITION',
                style: '这是用户自定义的音色',
            },
            env,
        });
        assert.equal(valid.response.status, 200);
    } finally {
        globalThis.fetch = originalFetch;
    }
});

test('guest TTS only accepts the character audition text', async () => {
    const worker = await loadMainWorker();
    const response = await worker.default.fetch(
        new Request('https://gateway.test/api/ai/tts', {
            method: 'POST',
            headers: { 'Content-Type': 'application/json' },
            body: JSON.stringify({ action: 'tts', characterId: 'usagi', text: '任意文本' }),
        }),
        createTestEnv({ AI_TEST_VOICE_DESIGN_READY: true }),
        { waitUntil() {} },
    );
    const json = await response.json();
    assert.equal(response.status, 403);
    assert.equal(json.code, 'FORBIDDEN');
});

test('authenticated TTS goes through the quota path instead of the guest path', async () => {
    const worker = await loadMainWorker();
    const response = await worker.default.fetch(
        new Request('https://gateway.test/api/ai/tts', {
            method: 'POST',
            headers: {
                Authorization: await authHeader(),
                'Content-Type': 'application/json',
            },
            body: JSON.stringify({ action: 'tts', characterId: 'usagi', text: '到！', sessionId: 'auth-tts-session' }),
        }),
        createTestEnv({ AI_TEST_VOICE_DESIGN_READY: true }),
        { waitUntil() {} },
    );
    const json = await response.json();
    assert.equal(response.status, 200);
    assert.equal(json.quota.sessionUsed, 1);
});

test('TTS stores MP3 in R2, single-flights MiMo, and does not charge cache hits', async () => {
    const originalFetch = globalThis.fetch;
    let upstreamCalls = 0;
    globalThis.fetch = async () => {
        upstreamCalls += 1;
        return new Response(JSON.stringify({
            choices: [{ message: { audio: { data: 'AQI=', transcript: '到！' } } }],
        }), { status: 200, headers: { 'Content-Type': 'application/json' } });
    };

    try {
        const env = createTestEnv({
            MIMO_API_KEY: 'test-mimo-key',
            AI_TEST_VOICE_DESIGN_READY: true,
            AI_AUDIO_CACHE: createAudioBucket(),
            JWT_SIGNING_KEY: 'test-jwt-secret',
        });
        const request = {
            method: 'POST',
            body: {
                action: 'tts',
                characterId: 'usagi',
                text: '到！',
                scene: 'ACTIVATION_ACK',
                sessionId: 'cached-tts-session',
            },
            env,
        };
        const first = await call('/api/ai/tts', request);
        const second = await call('/api/ai/tts', request);

        assert.equal(first.response.status, 200);
        assert.equal(second.response.status, 200);
        assert.match(first.json.data.audioUrl, /^https:\/\/gateway\.test\/api\/ai\/audio\/[^/]+$/);
        assert.equal(first.json.data.audioDataUrl, null);
        assert.equal(second.json.data.audioDataUrl, null);
        assert.equal(upstreamCalls, 1);
        assert.equal(first.json.quota.sessionUsed, 1);
        assert.equal(second.json.quota, undefined);
    } finally {
        globalThis.fetch = originalFetch;
    }
});

test('TTS uses the configured public API base for service-bound requests', async () => {
    const originalFetch = globalThis.fetch;
    globalThis.fetch = async () => new Response(JSON.stringify({
        choices: [{ message: { audio: { data: 'AQI=', transcript: 'voice' } } }],
    }), { status: 200, headers: { 'Content-Type': 'application/json' } });

    try {
        const { response, json } = await call('/api/ai/tts', {
            method: 'POST',
            body: { action: 'tts', characterId: 'usagi', text: 'voice', scene: 'AUDITION' },
            env: createTestEnv({
                MIMO_API_KEY: 'test-mimo-key',
                AI_TEST_VOICE_DESIGN_READY: true,
                AI_AUDIO_CACHE: createAudioBucket(),
                AUDIO_PUBLIC_BASE_URL: 'https://tracktosearch-gateway.pages.dev/gateway-api',
            }),
        });
        assert.equal(response.status, 200);
        assert.match(json.data.audioUrl, /^https:\/\/tracktosearch-gateway\.pages\.dev\/gateway-api\/api\/ai\/audio\/[^/]+$/);
    } finally {
        globalThis.fetch = originalFetch;
    }
});

test('signed TTS audio validates scope, expiry, signature, and object existence', async () => {
    const originalFetch = globalThis.fetch;
    globalThis.fetch = async () => new Response(JSON.stringify({
        choices: [{ message: { audio: { data: 'AQI=', transcript: '到！' } } }],
    }), { status: 200, headers: { 'Content-Type': 'application/json' } });

    try {
        const bucket = createAudioBucket();
        const env = createTestEnv({
            MIMO_API_KEY: 'test-mimo-key',
            AI_TEST_VOICE_DESIGN_READY: true,
            AI_AUDIO_CACHE: bucket,
            JWT_SIGNING_KEY: 'test-jwt-secret',
        });
        const generated = await call('/api/ai/tts', {
            method: 'POST',
            body: { action: 'tts', characterId: 'usagi', text: '到！', scene: 'AUDITION' },
            env,
        });
        const worker = await loadMainWorker();
        const audioUrl = generated.json.data.audioUrl;
        const valid = await worker.default.fetch(new Request(audioUrl), env, { waitUntil() {} });
        assert.equal(valid.status, 200);
        assert.equal(valid.headers.get('content-type'), 'audio/mpeg');
        assert.deepEqual(Array.from(new Uint8Array(await valid.arrayBuffer())), [1, 2]);

        const token = audioUrl.slice(audioUrl.lastIndexOf('/') + 1);
        const tokenParts = token.split('.');
        const base64UrlAlphabet = 'ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789-_';
        const lastSignatureIndex = base64UrlAlphabet.indexOf(tokenParts[2].at(-1));
        assert.equal(lastSignatureIndex % 4, 0);
        // 32 字节 HMAC 的末尾有两个未使用 bit；这是同一签名字节的非规范编码。
        tokenParts[2] = tokenParts[2].slice(0, -1) + base64UrlAlphabet[lastSignatureIndex + 1];
        const tampered = audioUrl.slice(0, audioUrl.lastIndexOf('/') + 1) + tokenParts.join('.');
        const tamperedResponse = await worker.default.fetch(new Request(tampered), env, { waitUntil() {} });
        assert.equal(tamperedResponse.status, 403);

        const expiredToken = await signAccessToken(
            'test-jwt-secret',
            'audio',
            [...bucket.objects.keys()][0],
            ['audio'],
            -1,
        );
        const expired = await worker.default.fetch(
            new Request(`https://gateway.test/api/ai/audio/${expiredToken}`),
            env,
            { waitUntil() {} },
        );
        assert.equal(expired.status, 403);

        const unknownToken = await signAccessToken(
            'test-jwt-secret',
            'audio',
            'tts-vd-v1/ffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffff.mp3',
            ['audio'],
            600,
        );
        const unknown = await worker.default.fetch(
            new Request(`https://gateway.test/api/ai/audio/${unknownToken}`),
            env,
            { waitUntil() {} },
        );
        assert.equal(unknown.status, 404);
    } finally {
        globalThis.fetch = originalFetch;
    }
});

test('TTS falls back to a data URL when R2 cannot write', async () => {
    const originalFetch = globalThis.fetch;
    globalThis.fetch = async () => new Response(JSON.stringify({
        choices: [{ message: { audio: { data: 'AQI=', transcript: '到！' } } }],
    }), { status: 200, headers: { 'Content-Type': 'application/json' } });

    try {
        const { response, json } = await call('/api/ai/tts', {
            method: 'POST',
            body: { action: 'tts', characterId: 'usagi', text: '到！', scene: 'AUDITION' },
            env: createTestEnv({
                MIMO_API_KEY: 'test-mimo-key',
                AI_TEST_VOICE_DESIGN_READY: true,
                AI_AUDIO_CACHE: createAudioBucket({ failPut: true }),
            }),
        });
        assert.equal(response.status, 200);
        assert.equal(json.data.audioDataUrl, 'data:audio/mpeg;base64,AQI=');
        assert.equal(json.data.audioUrl, null);
    } finally {
        globalThis.fetch = originalFetch;
    }
});

test('TTS cache keys separate scenes for the same spoken text', async () => {
    const originalFetch = globalThis.fetch;
    globalThis.fetch = async () => new Response(JSON.stringify({
        choices: [{ message: { audio: { data: 'AQI=', transcript: '到！' } } }],
    }), { status: 200, headers: { 'Content-Type': 'application/json' } });

    try {
        const bucket = createAudioBucket();
        const env = createTestEnv({
            MIMO_API_KEY: 'test-mimo-key',
            AI_TEST_VOICE_DESIGN_READY: true,
            AI_AUDIO_CACHE: bucket,
            JWT_SIGNING_KEY: 'test-jwt-secret',
        });
        await call('/api/ai/tts', {
            method: 'POST',
            body: { action: 'tts', characterId: 'usagi', text: '到！', scene: 'AUDITION' },
            env,
        });
        await call('/api/ai/tts', {
            method: 'POST',
            body: { action: 'tts', characterId: 'usagi', text: '到！', scene: 'GREETING' },
            env,
        });
        const keys = [...bucket.objects.keys()];
        assert.equal(keys.length, 2);
        assert.notEqual(keys[0], keys[1]);
        assert.ok(keys.every(key => /^tts-vd-v1\/[a-f0-9]{64}\.mp3$/.test(key)));
    } finally {
        globalThis.fetch = originalFetch;
    }
});

test('TTS regenerates an R2 object whose expiry metadata is missing', async () => {
    const originalFetch = globalThis.fetch;
    let upstreamCalls = 0;
    globalThis.fetch = async () => {
        upstreamCalls += 1;
        return new Response(JSON.stringify({
            choices: [{ message: { audio: { data: 'AQI=', transcript: '供应商文本' } } }],
        }), { status: 200, headers: { 'Content-Type': 'application/json' } });
    };

    try {
        const bucket = createAudioBucket();
        const env = createTestEnv({
            MIMO_API_KEY: 'test-mimo-key',
            AI_TEST_VOICE_DESIGN_READY: true,
            AI_AUDIO_CACHE: bucket,
            JWT_SIGNING_KEY: 'test-jwt-secret',
        });
        const request = {
            method: 'POST',
            body: { action: 'tts', characterId: 'usagi', text: '到！', scene: 'AUDITION', sessionId: 'metadata-session' },
            env,
        };
        assert.equal((await call('/api/ai/tts', request)).response.status, 200);
        const cachedObject = [...bucket.objects.values()][0];
        delete cachedObject.customMetadata.expiresAt;

        assert.equal((await call('/api/ai/tts', request)).response.status, 200);
        assert.equal(upstreamCalls, 2);
    } finally {
        globalThis.fetch = originalFetch;
    }
});

test('TTS limits each IP to 20 requests and 5 cache misses per ten minutes', async () => {
    const originalFetch = globalThis.fetch;
    globalThis.fetch = async () => new Response(JSON.stringify({
        choices: [{ message: { audio: { data: 'AQI=', transcript: '到！' } } }],
    }), { status: 200, headers: { 'Content-Type': 'application/json' } });

    try {
        const missLimitedEnv = createTestEnv({
            AI_TEST_VOICE_DESIGN_READY: true,
            AI_TEST_RATE_LIMIT: new Map(),
        });
        const missRequest = {
            method: 'POST',
            body: { action: 'tts', characterId: 'usagi', text: '到！', scene: 'AUDITION' },
            env: missLimitedEnv,
            headers: { 'CF-Connecting-IP': '198.51.100.10' },
        };
        for (let attempt = 0; attempt < 5; attempt += 1) {
            assert.equal((await call('/api/ai/tts', missRequest)).response.status, 200);
        }
        const sixthMiss = await call('/api/ai/tts', missRequest);
        assert.equal(sixthMiss.response.status, 429);
        assert.equal(sixthMiss.json.code, 'RATE_LIMITED');

        const totalLimitedEnv = createTestEnv({
            MIMO_API_KEY: 'test-mimo-key',
            AI_TEST_VOICE_DESIGN_READY: true,
            AI_AUDIO_CACHE: createAudioBucket(),
            JWT_SIGNING_KEY: 'test-jwt-secret',
            AI_TEST_RATE_LIMIT: new Map(),
        });
        const totalRequest = {
            method: 'POST',
            body: { action: 'tts', characterId: 'usagi', text: '到！', scene: 'AUDITION' },
            env: totalLimitedEnv,
            headers: { 'CF-Connecting-IP': '198.51.100.11' },
        };
        for (let attempt = 0; attempt < 20; attempt += 1) {
            assert.equal((await call('/api/ai/tts', totalRequest)).response.status, 200);
        }
        const twentyFirst = await call('/api/ai/tts', totalRequest);
        assert.equal(twentyFirst.response.status, 429);
        assert.equal(twentyFirst.json.code, 'RATE_LIMITED');
    } finally {
        globalThis.fetch = originalFetch;
    }
});

test('AI route rejects malformed JSON with a stable error', async () => {
    const env = createTestEnv();
    const request = new Request('https://gateway.test/api/ai/greeting', {
        method: 'POST',
        headers: {
            Authorization: await authHeader(),
            'Content-Type': 'application/json',
        },
        body: '{',
    });

    let response;
    try {
        response = await handleAiApi(request, env, 'request-1', '/api/ai/greeting', {
            sub: 'friend-1',
            device: 'device-1',
        });
    } catch (error) {
        response = errorResponse(error);
    }
    const json = await response.json();
    assert.equal(response.status, 400);
    assert.equal(json.code, 'INVALID_JSON');
});

test('character catalog contains all seven spirits and hides full voice design prompts', async () => {
    const { response, json } = await call('/api/ai/characters', { env: createTestEnv() });

    assert.equal(response.status, 200);
    assert.equal(json.data.characters.length, 7);
    assert.deepEqual(
        json.data.characters.map(character => character.id),
        ['chiikawa', 'hachiware', 'usagi', 'flying-squirrel', 'shisa', 'kurimanju', 'rakko'],
    );
    assert.ok(json.data.characters.every(character => character.voiceStatus === 'preparing'));
    assert.ok(json.data.characters.every(character => character.voiceDesignPrompt === undefined));
});

test('character readiness follows MiMo voicedesign configuration without voice samples', async () => {
    const { response, json } = await call('/api/ai/characters', {
        env: createTestEnv({ MIMO_API_KEY: 'test-mimo-key' }),
    });

    assert.equal(response.status, 200);
    const statuses = Object.fromEntries(json.data.characters.map(character => [character.id, character.voiceStatus]));
    assert.ok(Object.values(statuses).every(status => status === 'ready'));
});

test('production mode ignores test-only voice samples when checking readiness', async () => {
    const { response, json } = await call('/api/ai/characters', {
        env: createTestEnv({
            AI_TEST_MODE: false,
            AI_TEST_VOICE_SAMPLE_USAGI: 'data:audio/wav;base64,AA==',
        }),
    });

    assert.equal(response.status, 200);
    const usagi = json.data.characters.find(character => character.id === 'usagi');
    assert.equal(usagi.voiceStatus, 'preparing');
});

test('AI model is restricted to the documented allowlist', async () => {
    const { response, json } = await call('/api/ai/greeting', {
        method: 'POST',
        body: { characterId: 'usagi', model: 'not-a-mimo-model' },
        env: createTestEnv(),
    });

    assert.equal(response.status, 400);
    assert.equal(json.code, 'INVALID_MODEL');
});

test('AI action is validated when supplied by the client', async () => {
    const { response, json } = await call('/api/ai/greeting', {
        method: 'POST',
        body: { action: 'delete-account', characterId: 'usagi' },
        env: createTestEnv(),
    });

    assert.equal(response.status, 400);
    assert.equal(json.code, 'INVALID_ACTION');
});

test('quiz rejects a non-13 question request', async () => {
    const { response, json } = await call('/api/ai/quiz', {
        method: 'POST',
        body: { sessionId: 'quiz-session', questionCount: 12, movies: movies() },
        env: createTestEnv(),
    });

    assert.equal(response.status, 400);
    assert.equal(json.code, 'INVALID_QUESTION_COUNT');
});

test('quiz fallback returns seven films and 10 single, 2 multiple, 1 short questions', async () => {
    const { response, json } = await call('/api/ai/quiz', {
        method: 'POST',
        body: { sessionId: 'quiz-session', movies: movies() },
        env: createTestEnv(),
    });

    assert.equal(response.status, 200);
    assert.equal(json.data.movies.length, 7);
    assert.equal(json.data.questions.length, 13);
    assert.deepEqual(
        json.data.questions.reduce((counts, question) => {
            counts[question.type] += 1;
            return counts;
        }, { single: 0, multiple: 0, short: 0 }),
        { single: 10, multiple: 2, short: 1 },
    );
});

test('taste accepts the watched field emitted by Android', async () => {
    const { response, json } = await call('/api/ai/taste', {
        method: 'POST',
        body: { action: 'taste', sessionId: 'watched-taste-session', watched: movies() },
        env: createTestEnv(),
    });

    assert.equal(response.status, 200);
    assert.ok(Array.isArray(json.data.taste));
});

test('quiz accepts the watched field emitted by Android', async () => {
    const { response, json } = await call('/api/ai/quiz', {
        method: 'POST',
        body: { action: 'quiz', sessionId: 'watched-quiz-session', watched: movies() },
        env: createTestEnv(),
    });

    assert.equal(response.status, 200);
    assert.equal(json.data.movies.length, 7);
});

test('activation consumes one recording and returns the role confirmation', async () => {
    const { response, json } = await call('/api/ai/activate', {
        method: 'POST',
        body: {
            characterId: 'usagi',
            sessionId: 'activation-session',
            audioDataUrl: 'data:audio/wav;base64,AA==',
        },
        env: createTestEnv({
            AI_TEST_VOICE_DESIGN_READY: true,
            AI_TEST_TRANSCRIPT: '乌萨奇',
        }),
    });

    assert.equal(response.status, 200);
    assert.equal(json.data.activated, true);
    assert.equal(json.data.activationPhrase, '到——！');
    assert.equal(json.data.voiceStatus, 'ready');
});

test('activation keeps the text success when confirmation audio fails', async () => {
    const originalFetch = globalThis.fetch;
    globalThis.fetch = async () => {
        throw new Error('MiMo TTS unavailable');
    };

    try {
        const { response, json } = await call('/api/ai/activate', {
            method: 'POST',
            body: {
                characterId: 'usagi',
                sessionId: 'activation-audio-failure-session',
                audioDataUrl: 'data:audio/wav;base64,AA==',
            },
            env: createTestEnv({
                MIMO_API_KEY: 'test-mimo-key',
                AI_TEST_VOICE_DESIGN_READY: true,
                AI_TEST_TRANSCRIPT: '乌萨奇',
            }),
        });

        assert.equal(response.status, 200);
        assert.equal(json.data.activated, true);
        assert.equal(json.data.audio, null);
    } finally {
        globalThis.fetch = originalFetch;
    }
});

test('greeting keeps the text result when welcome audio fails', async () => {
    const originalFetch = globalThis.fetch;
    globalThis.fetch = async (_input, init) => {
        const requestBody = JSON.parse(init.body);
        if (requestBody.model === 'mimo-v2.5-tts-voicedesign') {
            throw new Error('MiMo TTS unavailable');
        }
        return new Response(JSON.stringify({
            choices: [{ message: { content: JSON.stringify({
                greeting: '小明，欢迎回来。',
                meaning: '名字很有精神。',
                comment: '今天也来找一部好电影吧。',
            }) } }],
        }), { status: 200, headers: { 'Content-Type': 'application/json' } });
    };

    try {
        const { response, json } = await call('/api/ai/greeting', {
            method: 'POST',
            body: { characterId: 'usagi', includeAudio: true, sessionId: 'greeting-audio-failure-session' },
            env: createTestEnv({
                MIMO_API_KEY: 'test-mimo-key',
                AI_TEST_VOICE_DESIGN_READY: true,
            }),
        });

        assert.equal(response.status, 200);
        assert.equal(json.data.audio, null);
        assert.match(json.data.spokenText, /呀哈/);
    } finally {
        globalThis.fetch = originalFetch;
    }
});

test('activation rejects a character whose matching voice sample is missing', async () => {
    const { response, json } = await call('/api/ai/activate', {
        method: 'POST',
        body: {
            characterId: 'usagi',
            sessionId: 'activation-not-ready-session',
            spokenName: '乌萨奇',
        },
        env: createTestEnv({ AI_VOICE_SAMPLES: { async head() { return null; } } }),
    });

    assert.equal(response.status, 400);
    assert.equal(json.code, 'VOICE_NOT_READY');
});

test('daily fallback always includes a source URL', async () => {
    const { response, json } = await call('/api/ai/daily', {
        method: 'POST',
        body: { action: 'daily', sessionId: 'daily-session' },
        env: createTestEnv(),
    });

    assert.equal(response.status, 200);
    assert.match(json.data.sourceUrl, /^https?:\/\//);
});

test('quiz submission reveals score and explanations after the quiz', async () => {
    const env = createTestEnv();
    const started = await call('/api/ai/quiz', {
        method: 'POST',
        body: { sessionId: 'submit-session', quizId: 'quiz-submit-1', movies: movies() },
        env,
    });
    const submitted = await call('/api/ai/quiz/submit', {
        method: 'POST',
        body: { action: 'quiz.submit', quizId: started.json.data.quizId, answers: {} },
        env,
    });

    assert.equal(submitted.response.status, 200);
    assert.equal(submitted.json.data.score, 0);
    assert.equal(submitted.json.data.questionResults.length, 13);
    assert.ok(submitted.json.data.questionResults[0].explanation);
    assert.equal(typeof submitted.json.data.questionResults[0].correctAnswer, 'string');
    assert.equal(started.json.data.questions[0].correctAnswer, undefined);
});

test('quiz submission accepts the answer array emitted by Android', async () => {
    const env = createTestEnv();
    const started = await call('/api/ai/quiz', {
        method: 'POST',
        body: { action: 'quiz', sessionId: 'array-submit-session', movies: movies() },
        env,
    });
    const submitted = await call('/api/ai/quiz/submit', {
        method: 'POST',
        body: {
            action: 'quiz.submit',
            quizId: started.json.data.quizId,
            answers: [{ questionId: started.json.data.questions[0].id, selectedOptionIds: [], textAnswer: null }],
        },
        env,
    });

    assert.equal(submitted.response.status, 200);
    assert.equal(submitted.json.data.totalQuestions, 13);
});

test('quiz submission inherits the session that created the cached quiz', async () => {
    const env = createTestEnv();
    const started = await call('/api/ai/quiz', {
        method: 'POST',
        body: { action: 'quiz', sessionId: 'quiz-submit-session', quizId: 'quiz-session-inheritance', movies: movies() },
        env,
    });
    const submitted = await call('/api/ai/quiz/submit', {
        method: 'POST',
        body: { action: 'quiz.submit', quizId: started.json.data.quizId, answers: {} },
        env,
    });

    assert.equal(submitted.response.status, 200);
    assert.equal(submitted.json.quota.sessionUsed, 2);
});

test('production path fails closed when the MiMo secret is absent', async () => {
    await assert.rejects(
        () => callMimoJson({ AI_TEST_MODE: false }, 'mimo-v2.5', []),
        error => error?.code === 'AI_NOT_CONFIGURED' && error?.statusCode === 503,
    );
});

test('greeting cache prevents a second Mimo request', async () => {
    const originalFetch = globalThis.fetch;
    let calls = 0;
    globalThis.fetch = async () => {
        calls += 1;
        return new Response(JSON.stringify({
            choices: [{ message: { content: JSON.stringify({
                greeting: '小明，今天也来挑一部好片吧。',
                meaning: '名字像一盏小灯。',
                comment: '很适合当片单侦探。',
            }) } }],
        }), { status: 200, headers: { 'Content-Type': 'application/json' } });
    };

    try {
        const env = createTestEnv({ MIMO_API_KEY: 'test-mimo-key' });
        const first = await call('/api/ai/greeting', {
            method: 'POST',
            body: { characterId: 'usagi', sessionId: 'greeting-session' },
            env,
        });
        const second = await call('/api/ai/greeting', {
            method: 'POST',
            body: { characterId: 'usagi', sessionId: 'greeting-session' },
            env,
        });

        assert.equal(first.response.status, 200);
        assert.equal(second.response.status, 200);
        assert.equal(calls, 1);
        assert.deepEqual(second.json.data, first.json.data);
    } finally {
        globalThis.fetch = originalFetch;
    }
});

test('recommendations reject an upstream item without a valid media ID', async () => {
    const originalFetch = globalThis.fetch;
    globalThis.fetch = async () => new Response(JSON.stringify({
        choices: [{ message: { content: JSON.stringify({
            roast: '你的片单像一场有趣的夜行。',
            taste: ['偏爱有余韵的故事'],
            recommendations: [{ title: 'Unknown', reason: 'Because', mediaIds: {} }],
        }) } }],
    }), { status: 200, headers: { 'Content-Type': 'application/json' } });

    try {
        const { response, json } = await call('/api/ai/taste', {
            method: 'POST',
            body: { sessionId: 'taste-session', movies: movies() },
            env: createTestEnv({ MIMO_API_KEY: 'test-mimo-key' }),
        });

        assert.equal(response.status, 502);
        assert.equal(json.code, 'INVALID_AI_OUTPUT');
    } finally {
        globalThis.fetch = originalFetch;
    }
});

test('upstream failure is retried once and returned as a stable error', async () => {
    const originalFetch = globalThis.fetch;
    let calls = 0;
    globalThis.fetch = async () => {
        calls += 1;
        return new Response('provider failure details', { status: 503 });
    };

    try {
        const { response, json } = await call('/api/ai/greeting', {
            method: 'POST',
            body: { characterId: 'usagi', sessionId: 'failure-session' },
            env: createTestEnv({ MIMO_API_KEY: 'test-mimo-key' }),
        });

        assert.equal(response.status, 502);
        assert.equal(json.code, 'AI_UPSTREAM_ERROR');
        assert.equal(calls, 2);
        assert.doesNotMatch(json.message, /provider failure details/);
    } finally {
        globalThis.fetch = originalFetch;
    }
});

test('session quota stops the fifteenth uncached interaction', async () => {
    const env = createTestEnv();
    for (let attempt = 0; attempt < 14; attempt += 1) {
        const result = await call('/api/ai/greeting', {
            method: 'POST',
            body: {
                characterId: 'usagi',
                sessionId: 'quota-session',
                forceRefresh: true,
            },
            env,
        });
        assert.equal(result.response.status, 200);
    }

    const exhausted = await call('/api/ai/greeting', {
        method: 'POST',
        body: { characterId: 'usagi', sessionId: 'quota-session', forceRefresh: true },
        env,
    });
    assert.equal(exhausted.response.status, 429);
    assert.equal(exhausted.json.code, 'AI_SESSION_QUOTA_EXCEEDED');
});

test('tts consumes the doubled session quota and stops on the fifteenth call', async () => {
    const env = createTestEnv({ AI_TEST_VOICE_DESIGN_READY: true });
    for (let attempt = 0; attempt < 14; attempt += 1) {
        const result = await call('/api/ai/tts', {
            method: 'POST',
            body: { characterId: 'usagi', text: '到！', sessionId: 'tts-quota-session' },
            env,
        });
        assert.equal(result.response.status, 200);
    }

    const exhausted = await call('/api/ai/tts', {
        method: 'POST',
        body: { characterId: 'usagi', text: '到！', sessionId: 'tts-quota-session' },
        env,
    });
    assert.equal(exhausted.response.status, 429);
    assert.equal(exhausted.json.code, 'AI_SESSION_QUOTA_EXCEEDED');
});

test('tts rejects voice-not-ready characters without spending quota', async () => {
    const env = createTestEnv();
    const result = await call('/api/ai/tts', {
        method: 'POST',
        body: { characterId: 'usagi', text: '到！', sessionId: 'tts-notready-session' },
        env,
    });
    assert.equal(result.response.status, 400);
    assert.equal(result.json.code, 'VOICE_NOT_READY');
});

test('quiz honors a client-provided quizId on the second call', async () => {
    const env = createTestEnv();
    const first = await call('/api/ai/quiz', {
        method: 'POST',
        body: { sessionId: 'reuse-session', quizId: 'fixed-quiz-id', movies: movies() },
        env,
    });
    assert.equal(first.response.status, 200);
    assert.equal(first.json.data.quizId, 'fixed-quiz-id');

    const second = await call('/api/ai/quiz', {
        method: 'POST',
        body: { sessionId: 'reuse-session-2', quizId: 'fixed-quiz-id', movies: movies() },
        env,
    });
    assert.equal(second.response.status, 200);
    assert.equal(second.json.data.quizId, 'fixed-quiz-id');
    assert.deepEqual(second.json.data.questions, first.json.data.questions);
});

test('cached interactions do not consume the shared session quota', async () => {
    const env = createTestEnv();
    const first = await call('/api/ai/greeting', {
        method: 'POST',
        body: {
            characterId: 'usagi',
            sessionId: 'shared-sprite-session',
        },
        env,
    });
    assert.equal(first.response.status, 200);
    assert.equal(first.json.quota.sessionUsed, 1);

    for (let attempt = 0; attempt < 20; attempt += 1) {
        const result = await call('/api/ai/greeting', {
            method: 'POST',
            body: {
                characterId: 'usagi',
                sessionId: 'shared-sprite-session',
            },
            env,
        });
        assert.equal(result.response.status, 200);
        assert.equal(result.json.quota, undefined);
    }
    const usage = env.AI_TEST_QUOTA.get(`friend-1:${new Date().toISOString().slice(0, 10)}`);
    assert.equal(usage.sessionCount, 1);
    assert.equal(usage.dailyCount, 1);
});

test('taste, quiz, and daily cache hits do not consume AI quota', async () => {
    const env = createTestEnv();
    const tasteRequest = {
        method: 'POST',
        body: { action: 'taste', watched: movies(), sessionId: 'cache-hit-session' },
        env,
    };
    const tasteFirst = await call('/api/ai/taste', tasteRequest);
    const tasteSecond = await call('/api/ai/taste', tasteRequest);
    assert.equal(tasteFirst.response.status, 200);
    assert.equal(tasteSecond.response.status, 200);
    assert.equal(tasteSecond.json.quota, undefined);

    const quizRequest = {
        method: 'POST',
        body: { action: 'quiz', quizId: 'cache-hit-quiz', watched: movies(), sessionId: 'cache-hit-session' },
        env,
    };
    const quizFirst = await call('/api/ai/quiz', quizRequest);
    const quizSecond = await call('/api/ai/quiz', quizRequest);
    assert.equal(quizFirst.response.status, 200);
    assert.equal(quizSecond.response.status, 200);
    assert.equal(quizSecond.json.quota, undefined);

    const dailyRequest = {
        method: 'POST',
        body: { action: 'daily', sessionId: 'cache-hit-session' },
        env,
    };
    const dailyFirst = await call('/api/ai/daily', dailyRequest);
    const dailySecond = await call('/api/ai/daily', dailyRequest);
    assert.equal(dailyFirst.response.status, 200);
    assert.equal(dailySecond.response.status, 200);
    assert.equal(dailySecond.json.quota, undefined);

    const usage = env.AI_TEST_QUOTA.get(`friend-1:${new Date().toISOString().slice(0, 10)}`);
    assert.equal(usage.sessionCount, 3);
    assert.equal(usage.dailyCount, 3);
});

test('daily cache is isolated by friend and UTC date', async () => {
    const env = createTestEnv();
    const first = await call('/api/ai/daily', {
        method: 'POST',
        body: { action: 'daily', sessionId: 'daily-friend-1' },
        env,
        friendId: 'friend-1',
    });
    const second = await call('/api/ai/daily', {
        method: 'POST',
        body: { action: 'daily', sessionId: 'daily-friend-2' },
        env,
        friendId: 'friend-2',
    });

    assert.equal(first.response.status, 200);
    assert.equal(second.response.status, 200);
    const dailyKeys = [...env.AI_TEST_CACHE.keys()].filter(key => key.includes(':daily:'));
    assert.equal(dailyKeys.length, 2);
    assert.ok(dailyKeys.some(key => key.includes('friend-1')));
    assert.ok(dailyKeys.some(key => key.includes('friend-2')));
});

test('taste defaults to the pro model and includes the nickname in the prompt', async () => {
    const originalFetch = globalThis.fetch;
    let requestBody;
    globalThis.fetch = async (_input, init) => {
        requestBody = JSON.parse(init.body);
        return new Response(JSON.stringify({
            choices: [{ message: { content: JSON.stringify({
                roast: '小明的片单很会留白。',
                taste: ['偏爱复杂人物'],
                recommendations: [{ title: 'Movie 1', year: 2020, reason: '同样重视人物选择。', mediaIds: { tmdbId: 100 } }],
            }) } }],
        }), { status: 200, headers: { 'Content-Type': 'application/json' } });
    };

    try {
        const { response, json } = await call('/api/ai/taste', {
            method: 'POST',
            body: { action: 'taste', sessionId: 'taste-prompt-session', watched: movies(), forceRefresh: true },
            env: createTestEnv({ MIMO_API_KEY: 'test-mimo-key' }),
        });

        assert.equal(response.status, 200);
        assert.equal(requestBody.model, 'mimo-v2.5-pro');
        assert.ok(requestBody.messages.some(message => String(message.content).includes('小明')));
        assert.equal(json.data.recommendations[0].mediaIds.tmdbId, 100);
    } finally {
        globalThis.fetch = originalFetch;
    }
});

test('taste rejects a recommendation containing an ID outside the watched whitelist', async () => {
    const originalFetch = globalThis.fetch;
    globalThis.fetch = async () => new Response(JSON.stringify({
        choices: [{ message: { content: JSON.stringify({
            roast: '小明的片单很会留白。',
            taste: ['偏爱复杂人物'],
            recommendations: [{ title: 'Unknown', year: 2024, reason: '因为模型说了算。', mediaIds: { tmdbId: 999999 } }],
        }) } }],
    }), { status: 200, headers: { 'Content-Type': 'application/json' } });

    try {
        const { response, json } = await call('/api/ai/taste', {
            method: 'POST',
            body: { action: 'taste', sessionId: 'taste-whitelist-session', watched: movies(), forceRefresh: true },
            env: createTestEnv({ MIMO_API_KEY: 'test-mimo-key' }),
        });

        assert.equal(response.status, 502);
        assert.equal(json.code, 'INVALID_AI_OUTPUT');
    } finally {
        globalThis.fetch = originalFetch;
    }
});

test('taste rejects a watched media ID that is not in the verified whitelist', async () => {
    const originalFetch = globalThis.fetch;
    globalThis.fetch = async () => new Response(JSON.stringify({
        choices: [{ message: { content: JSON.stringify({
            roast: '小明的片单很会留白。',
            taste: ['偏爱复杂人物'],
            recommendations: [{ title: 'Movie 1', year: 2020, reason: '同样重视人物选择。', mediaIds: { tmdbId: 100 } }],
        }) } }],
    }), { status: 200, headers: { 'Content-Type': 'application/json' } });

    try {
        const watched = movies();
        watched[0] = {
            ...watched[0],
            mediaIds: { traktId: '42', tmdbId: 100 },
            verifiedMediaIds: { traktId: '42' },
        };
        const { response, json } = await call('/api/ai/taste', {
            method: 'POST',
            body: { action: 'taste', sessionId: 'taste-verified-whitelist-session', watched, forceRefresh: true },
            env: createTestEnv({ MIMO_API_KEY: 'test-mimo-key' }),
        });

        assert.equal(response.status, 502);
        assert.equal(json.code, 'INVALID_AI_OUTPUT');
    } finally {
        globalThis.fetch = originalFetch;
    }
});

test('quiz accepts a valid Mimo question package without answerKeywords', async () => {
    const originalFetch = globalThis.fetch;
    let requestBody;
    globalThis.fetch = async (_input, init) => {
        requestBody = JSON.parse(init.body);
        return new Response(JSON.stringify(validQuizUpstreamPayload()), {
            status: 200,
            headers: { 'Content-Type': 'application/json' },
        });
    };

    try {
        const { response, json } = await call('/api/ai/quiz', {
            method: 'POST',
            body: { action: 'quiz', sessionId: 'quiz-normalize-session', watched: movies(), forceRefresh: true },
            env: createTestEnv({ MIMO_API_KEY: 'test-mimo-key' }),
        });

        assert.equal(response.status, 200);
        assert.equal(json.data.questions.length, 13);
        assert.equal(requestBody.model, 'mimo-v2.5-pro');
        assert.ok(requestBody.messages.some(message => String(message.content).includes('小明')));
    } finally {
        globalThis.fetch = originalFetch;
    }
});

test('quiz falls back only after an invalid Mimo structure', async () => {
    const originalFetch = globalThis.fetch;
    globalThis.fetch = async () => new Response(JSON.stringify({
        choices: [{ message: { content: JSON.stringify({ questions: [] }) } }],
    }), { status: 200, headers: { 'Content-Type': 'application/json' } });

    try {
        const { response, json } = await call('/api/ai/quiz', {
            method: 'POST',
            body: { action: 'quiz', sessionId: 'quiz-invalid-structure-session', watched: movies(), forceRefresh: true },
            env: createTestEnv({ MIMO_API_KEY: 'test-mimo-key' }),
        });

        assert.equal(response.status, 200);
        assert.equal(json.data.questions.length, 13);
        assert.equal(json.data.questions[0].options[1].id, 'b');
    } finally {
        globalThis.fetch = originalFetch;
    }
});

test('quiz treats an invalid model-generated question id as a structure failure', async () => {
    const originalFetch = globalThis.fetch;
    globalThis.fetch = async () => {
        const payload = validQuizUpstreamPayload();
        const generated = JSON.parse(payload.choices[0].message.content);
        generated.questions[0].id = 'invalid question id';
        return new Response(JSON.stringify({
            choices: [{ message: { content: JSON.stringify(generated) } }],
        }), { status: 200, headers: { 'Content-Type': 'application/json' } });
    };

    try {
        const { response, json } = await call('/api/ai/quiz', {
            method: 'POST',
            body: { action: 'quiz', sessionId: 'quiz-invalid-id-session', watched: movies(), forceRefresh: true },
            env: createTestEnv({ MIMO_API_KEY: 'test-mimo-key' }),
        });

        assert.equal(response.status, 200);
        assert.equal(json.data.questions.length, 13);
        assert.equal(json.data.questions[0].options[1].id, 'b');
    } finally {
        globalThis.fetch = originalFetch;
    }
});

test('quiz falls back when the successful Mimo response envelope is not an object', async () => {
    const originalFetch = globalThis.fetch;
    globalThis.fetch = async () => new Response(JSON.stringify([]), {
        status: 200,
        headers: { 'Content-Type': 'application/json' },
    });

    try {
        const { response, json } = await call('/api/ai/quiz', {
            method: 'POST',
            body: { action: 'quiz', sessionId: 'quiz-invalid-envelope-session', watched: movies(), forceRefresh: true },
            env: createTestEnv({ MIMO_API_KEY: 'test-mimo-key' }),
        });

        assert.equal(response.status, 200);
        assert.equal(json.data.questions.length, 13);
    } finally {
        globalThis.fetch = originalFetch;
    }
});

function validQuizUpstreamPayload() {
    const questions = Array.from({ length: 13 }, (_, index) => {
        if (index < 10) {
            return {
                id: `q${index + 1}`,
                type: 'single',
                prompt: `分析 Movie ${index % 7 + 1} 中人物的选择。`,
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
                id: `q${index + 1}`,
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
            id: `q${index + 1}`,
            type: 'short',
            prompt: '这部作品最值得带回现实的问题是什么？',
            options: [],
            correctAnswer: '人物如何作出选择。',
            explanation: '把银幕经验连接到现实思考。',
            filmIndex: index % 7,
        };
    });
    return { choices: [{ message: { content: JSON.stringify({ questions }) } }] };
}

function errorResponse(error) {
    assert.equal(typeof error?.code, 'string');
    return new Response(JSON.stringify({
        code: error.code,
        message: error.message,
    }), { status: error.statusCode || 500, headers: { 'Content-Type': 'application/json' } });
}

let mainWorkerPromise;

async function loadMainWorker() {
    if (!mainWorkerPromise) {
        mainWorkerPromise = build({
            entryPoints: ['src/index.ts'],
            absWorkingDir: new URL('..', import.meta.url).pathname.replace(/^\/(?=[A-Za-z]:)/, ''),
            bundle: true,
            external: ['node:crypto'],
            format: 'esm',
            logLevel: 'silent',
            platform: 'node',
            write: false,
        }).then(result => import(
            `data:text/javascript;base64,${Buffer.from(result.outputFiles[0].text).toString('base64')}`
        ));
    }
    return mainWorkerPromise;
}
