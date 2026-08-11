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

async function call(path, { method = 'GET', body, env, authorized = true } = {}) {
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
                sub: 'friend-1',
                device: 'device-1',
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

test('AI routes reject missing JWT through the main router', async () => {
    const worker = await loadMainWorker();
    const response = await worker.default.fetch(
        new Request('https://gateway.test/api/ai/characters'),
        createTestEnv(),
        { waitUntil() {} },
    );
    const json = await response.json();
    assert.equal(response.status, 401);
    assert.equal(json.code, 'UNAUTHORIZED');
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
        env: createTestEnv(),
    });

    assert.equal(response.status, 200);
    assert.equal(json.data.activated, true);
    assert.equal(json.data.activationPhrase, '到！');
    assert.equal(json.data.voiceStatus, 'preparing');
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
    // 音色就绪（提供 AI_VOICE_SAMPLES）才能通过 VOICE_NOT_READY 守卫走到配额路径
    const env = createTestEnv({ AI_VOICE_SAMPLES: {} });
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
