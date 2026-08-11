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
} = {}) {
    const headers = {};
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

test('guest can audition a ready character through the main router without consuming quota', async () => {
    const originalFetch = globalThis.fetch;
    globalThis.fetch = async (_input, init) => {
        const requestBody = JSON.parse(init.body);
        assert.equal(requestBody.model, 'mimo-v2.5-tts-voiceclone');
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
                    sessionId: 'guest-audition-session',
                }),
            }),
            createTestEnv({
                MIMO_API_KEY: 'test-mimo-key',
                AI_TEST_VOICE_SAMPLE_USAGI: 'data:audio/wav;base64,AA==',
            }),
            { waitUntil() {} },
        );
        const json = await response.json();
        assert.equal(response.status, 200);
        assert.equal(json.data.audioDataUrl, 'data:audio/wav;base64,AA==');
        assert.equal(json.quota, undefined);
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
        createTestEnv({ AI_TEST_VOICE_SAMPLE_USAGI: 'data:audio/wav;base64,AA==' }),
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
        createTestEnv({ AI_TEST_VOICE_SAMPLE_USAGI: 'data:audio/wav;base64,AA==' }),
        { waitUntil() {} },
    );
    const json = await response.json();
    assert.equal(response.status, 200);
    assert.equal(json.quota.sessionUsed, 1);
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

test('character catalog contains all seven spirits and marks unbound voices as preparing', async () => {
    const { response, json } = await call('/api/ai/characters', { env: createTestEnv() });

    assert.equal(response.status, 200);
    assert.equal(json.data.characters.length, 7);
    assert.deepEqual(
        json.data.characters.map(character => character.id),
        ['chiikawa', 'hachiware', 'usagi', 'flying-squirrel', 'shisa', 'kurimanju', 'rakko'],
    );
    assert.ok(json.data.characters.every(character => character.voiceStatus === 'preparing'));
});

test('character readiness checks the matching R2 object instead of only the binding', async () => {
    const bucket = {
        async head(key) {
            return key === 'usagi.wav' ? { size: 3 } : null;
        },
        async get() {
            return null;
        },
    };
    const { response, json } = await call('/api/ai/characters', {
        env: createTestEnv({ AI_VOICE_SAMPLES: bucket }),
    });

    assert.equal(response.status, 200);
    const statuses = Object.fromEntries(json.data.characters.map(character => [character.id, character.voiceStatus]));
    assert.equal(statuses.usagi, 'ready');
    assert.equal(statuses.chiikawa, 'preparing');
    assert.equal(statuses.rakko, 'preparing');
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
        env: createTestEnv({ AI_TEST_VOICE_SAMPLE_USAGI: 'data:audio/wav;base64,AA==' }),
    });

    assert.equal(response.status, 200);
    assert.equal(json.data.activated, true);
    assert.equal(json.data.activationPhrase, '到！');
    assert.equal(json.data.voiceStatus, 'ready');
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

test('session quota stops the eighth uncached interaction', async () => {
    const env = createTestEnv();
    for (let attempt = 0; attempt < 7; attempt += 1) {
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

test('tts consumes quota and stops on the eighth call', async () => {
    // 测试样本是唯一允许绕过 R2 对象检查的测试例外。
    const env = createTestEnv({ AI_TEST_VOICE_SAMPLE_USAGI: 'data:audio/wav;base64,AA==' });
    for (let attempt = 0; attempt < 7; attempt += 1) {
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

test('cached interactions still consume the shared seven-call session quota', async () => {
    const env = createTestEnv();
    for (let attempt = 0; attempt < 7; attempt += 1) {
        const result = await call('/api/ai/greeting', {
            method: 'POST',
            body: {
                characterId: 'usagi',
                sessionId: 'shared-sprite-session',
            },
            env,
        });
        assert.equal(result.response.status, 200);
    }

    const exhausted = await call('/api/ai/greeting', {
        method: 'POST',
        body: {
            characterId: 'usagi',
            sessionId: 'shared-sprite-session',
        },
        env,
    });
    assert.equal(exhausted.response.status, 429);
    assert.equal(exhausted.json.code, 'AI_SESSION_QUOTA_EXCEEDED');
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
