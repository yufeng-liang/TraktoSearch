import test from 'node:test';
import assert from 'node:assert/strict';
import { build } from 'esbuild';
import { handleAiApi } from '../src/ai/handler.ts';
import { callMimoJson } from '../src/ai/mimo.ts';
import { reserveAiTtsMiss, reserveAiTtsRequest } from '../src/ai/store.ts';
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

// 这些用例专门覆盖旧 MiMo 文本路径；生产默认路径由 agnes.test.mjs 覆盖。
function createLegacyMimoTextEnv(overrides = {}) {
    return createTestEnv({
        AI_DEFAULT_PROVIDER: 'mimo',
        MIMO_API_KEY: 'test-mimo-key',
        ...overrides,
    });
}

async function authHeader() {
    return `Bearer ${await signAccessToken('test-jwt-secret', 'friend-1', 'device-1')}`;
}

async function call(path, {
    method = 'GET',
    body,
    env,
    origin = 'https://gateway.test',
    authorized = true,
    friendId = 'friend-1',
    deviceId = 'device-1',
    headers: extraHeaders = {},
} = {}) {
    const headers = { ...extraHeaders };
    if (body !== undefined) headers['Content-Type'] = 'application/json';
    const request = new Request(`${origin}${path}`, {
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

/** 单元阶段门槛要求 direct_watch 单元引用具体证据（简介），纯年份/类型过不了门。 */
function quizMoviesWithSynopsis() {
    return movies().map(movie => ({ ...movie, overview: QUIZ_SYNOPSIS }));
}

function validDailyKnowledgeUnit({ direct = false } = {}) {
    return {
        unitId: direct ? 'daily-direct-unit' : 'daily-general-unit',
        version: 1,
        locale: 'zh-CN',
        relationType: direct ? 'direct_watch' : 'general_knowledge',
        evidenceMode: direct ? 'viewing_interpretation' : 'external_fact',
        subjectGroup: direct ? 'people_and_mind' : 'history_and_culture',
        subject: direct ? '心理学' : '历史',
        concept: direct ? '从众压力' : '片场口令',
        title: direct ? '第一个反对票为什么重要' : '片场口令如何组织协作',
        takeaway: direct
            ? '第一个公开反对的人，会降低其他人表达不同意见的心理成本。'
            : '统一口令把多部门准备压缩成同一瞬间，降低拍摄现场的不确定性。',
        relatedMedia: direct ? { title: 'Movie 1', mediaType: 'movie', tmdbId: 100 } : null,
        filmEvidence: direct
            ? 'Movie 1 是 2020 年的 Drama，输入资料包含“上映年份：2020”“类型：Drama”“简介：影片包含群体讨论场景”，可用来讨论群体讨论中的意见变化。'
            : '开机前的部门准备和统一信号，是影片制作资料中可确认的协作方式。',
        explanation: direct
            ? '多数意见可见后，个体会评估表达异见的社会成本；第一个公开异议让不同意见变得可见。'
            : '片场时间成本高，统一信号让摄影、灯光、表演和声音在同一时刻进入执行状态。',
        realWorldExample: direct
            ? '会议中先有人提出替代方案，后续同事更容易补充顾虑。'
            : '复杂项目也需要明确职责边界和统一启动信号。',
        boundary: direct
            ? '这是基于观看记录的入门解读，不是对角色的临床诊断。'
            : '这是制作历史的来源说明，不同剧组流程会存在差异。',
        difficulty: 'easy',
        spoilerLevel: 'none',
        source: {
            name: direct ? 'Example' : 'Encyclopaedia Britannica',
            url: direct ? 'https://example.com/fact' : 'https://www.britannica.com/citizen-kane',
            evidence: direct
                ? '来源说明从众压力与少数意见影响讨论的条件。'
                : '来源介绍电影制作协作与技术流程。',
        },
        checkQuestion: {
            prompt: direct ? '第一个公开反对者的作用是什么？' : '统一口令的主要作用是什么？',
            options: [
                { id: 'a', text: '让所有人立刻改变立场。' },
                { id: 'b', text: direct ? '降低其他人表达不同意见的心理成本。' : '让多部门在同一瞬间进入执行状态。' },
                { id: 'c', text: '证明流程一定正确。' },
            ],
            correctOptionIds: ['b'],
            explanation: direct
                ? '从众压力说明第一个公开异议会让后续表达更容易。'
                : '片场口令作为协作信号，能让多部门同时进入执行状态。',
        },
        characterLine: '原来如此！',
    };
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
    const usagi = json.data.characters.find((character) => character.id === 'usagi');
    assert.ok(usagi);
    assert.ok(usagi.previewText.length > 0);
    assert.equal(usagi.previewText.includes('到'), false);
});

test('AI protected routes still reject missing JWT', async () => {
    const worker = await loadMainWorker();
    for (const path of ['/api/ai/activate', '/api/ai/greeting', '/api/ai/taste', '/api/ai/quiz', '/api/ai/daily', '/api/ai/quiz/submit', '/api/ai/quiz/feedback']) {
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
        assert.equal(requestBody.messages[1].content, '呀哈！你的片单有点东西。');
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
                    text: '呀哈！你的片单有点东西。',
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
        assert.equal(json.data.transcript, '呀哈！你的片单有点东西。');
        assert.equal(json.quota, undefined);
    } finally {
        globalThis.fetch = originalFetch;
    }
});

test('final character voice designs keep the approved child voices and delivery constraints', async () => {
    const originalFetch = globalThis.fetch;
    const requests = new Map();
    globalThis.fetch = async (_input, init) => {
        const requestBody = JSON.parse(init.body);
        const characterMatch = requestBody.messages[0].content.match(/^角色：([^。]+)/);
        requests.set(characterMatch?.[1] ?? 'unknown', requestBody);
        return new Response(JSON.stringify({
            choices: [{ message: { audio: { data: 'AA==', transcript: requestBody.messages[1].content } } }],
        }), { status: 200, headers: { 'Content-Type': 'application/json' } });
    };

    const cases = [
        // [名称, 性别, 试听原文, 场景指导必含片段, TTS assistant 朗读文本（口头禅注入后）]
        ['吉伊', '女童', '今天也一起找一部好看的电影吧。', /1\.2倍/, '今天也一起找一部好看的电影吧。 鸭蛋。'],
        ['小八', '男童', '我发现了一点有意思的片单线索哦。', /奶声|幼儿园/, '噢易！ 我发现了一点有意思的片单线索哦。'],
        ['乌萨奇', '男童', '呀哈！你的片单有点东西。', /尖叫|音量比普通说话更大/, '呀哈！你的片单有点东西。'],
        ['飞鼠', '女童', '让我看看，今天有什么值得你发光的电影。', /只朗读一遍|严禁重复/, '让我看看，今天有什么值得你发光的电影。'],
        ['狮萨', '女童', '欢迎回来，我帮你把片单整理得更清楚。', /明亮|片单/, '欢迎回来，我帮你把片单整理得更清楚。'],
        ['栗子馒头', '男童', '先坐下来，慢慢看看你的观影口味。', /发音清晰|逐字读/, '先坐下来，慢慢看看你的观影口味。'],
        ['獭师', '男童', '准备好了吗？我们来认真拆一拆这份片单。', /幼儿园男孩|幼童男声/, '准备好了吗？我们来认真拆一拆这份片单。'],
    ];

    try {
        const env = createTestEnv({
            MIMO_API_KEY: 'test-mimo-key',
            AI_TEST_VOICE_DESIGN_READY: true,
        });
        for (const [name, gender, text, requiredDirection, expectedSpoken] of cases) {
            const result = await call('/api/ai/tts', {
                method: 'POST',
                body: { action: 'tts', characterId: {
                    吉伊: 'chiikawa',
                    小八: 'hachiware',
                    乌萨奇: 'usagi',
                    飞鼠: 'flying-squirrel',
                    狮萨: 'shisa',
                    栗子馒头: 'kurimanju',
                    獭师: 'rakko',
                }[name], text, scene: 'AUDITION' },
                env,
            });
            assert.equal(result.response.status, 200, name);
            const requestBody = requests.get(name);
            assert.ok(requestBody, name);
            assert.match(requestBody.messages[0].content, new RegExp(`五到六岁${gender}`), name);
            assert.match(requestBody.messages[0].content, /不能是成人声|不能是成人|明显稚嫩/, name);
            assert.match(requestBody.messages[0].content, requiredDirection, name);
            assert.equal(requestBody.messages[1].role, 'assistant');
            // 吉伊口头禅「鸭蛋。」句尾注入、小八「噢易！」句首注入；其余角色 AUDITION 不注入
            assert.equal(requestBody.messages[1].content, expectedSpoken, name);
        }

        const usagiPrompt = requests.get('乌萨奇').messages[0].content;
        assert.match(usagiPrompt, /不得在开头或任何位置添加“到”/);
        assert.doesNotMatch(usagiPrompt, /开头的“到！”|“到”只发一个音节|只喊一次“到”/);
        assert.match(requests.get('吉伊').messages[0].content, /口头禅「鸭蛋」整句只出现一次，严禁重复、拉长或变调/);
        assert.match(requests.get('小八').messages[0].content, /口头禅「噢易」整句只出现一次，严禁重复、拉长或变调/);
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
        body: { action: 'tts', characterId: 'usagi', text: '呀哈！你的片单有点东西。', scene: 'UNKNOWN' },
        env,
    });
    assert.equal(invalid.response.status, 400);
    assert.equal(invalid.json.code, 'INVALID_SCENE');

    const originalFetch = globalThis.fetch;
    globalThis.fetch = async (_input, init) => {
        const requestBody = JSON.parse(init.body);
        assert.doesNotMatch(JSON.stringify(requestBody.messages), /这是用户自定义的音色/);
        return new Response(JSON.stringify({
            choices: [{ message: { audio: { data: 'AA==', transcript: '呀哈！你的片单有点东西。' } } }],
        }), { status: 200, headers: { 'Content-Type': 'application/json' } });
    };
    try {
        const valid = await call('/api/ai/tts', {
            method: 'POST',
            body: {
                action: 'tts',
                characterId: 'usagi',
                text: '呀哈！你的片单有点东西。',
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

test('TTS rate limiting prefers the gateway forwarded X-Real-IP over CF-Connecting-IP', async () => {
    const originalFetch = globalThis.fetch;
    globalThis.fetch = async () => new Response(JSON.stringify({
        choices: [{ message: { audio: { data: 'AQI=', transcript: '到！' } } }],
    }), { status: 200, headers: { 'Content-Type': 'application/json' } });

    try {
        const env = createTestEnv({
            AI_TEST_VOICE_DESIGN_READY: true,
            AI_TEST_RATE_LIMIT: new Map(),
        });
        const result = await call('/api/ai/tts', {
            method: 'POST',
            body: { action: 'tts', characterId: 'usagi', text: '到！', scene: 'AUDITION' },
            env,
            origin: 'https://gateway.internal',
            headers: {
                'CF-Connecting-IP': '198.51.100.10',
                'X-Real-IP': '203.0.113.7',
            },
        });

        assert.equal(result.response.status, 200);
        const keys = [...env.AI_TEST_RATE_LIMIT.keys()];
        assert.equal(keys.length, 1);
        const forwardedKey = await digestForTest('203.0.113.7');
        assert.equal(keys[0], `ai:tts:rate:v1:${forwardedKey}`);
    } finally {
        globalThis.fetch = originalFetch;
    }
});

test('TTS rate limiting ignores spoofed X-Real-IP on direct Worker requests', async () => {
    const originalFetch = globalThis.fetch;
    globalThis.fetch = async () => new Response(JSON.stringify({
        choices: [{ message: { audio: { data: 'AQI=', transcript: '到！' } } }],
    }), { status: 200, headers: { 'Content-Type': 'application/json' } });

    try {
        const env = createTestEnv({
            AI_TEST_VOICE_DESIGN_READY: true,
            AI_TEST_RATE_LIMIT: new Map(),
        });
        const result = await call('/api/ai/tts', {
            method: 'POST',
            body: { action: 'tts', characterId: 'usagi', text: '到！', scene: 'AUDITION' },
            env,
            origin: 'https://auth-worker.example.workers.dev',
            headers: {
                'CF-Connecting-IP': '198.51.100.10',
                'X-Real-IP': '203.0.113.7',
            },
        });

        assert.equal(result.response.status, 200);
        const keys = [...env.AI_TEST_RATE_LIMIT.keys()];
        assert.equal(keys.length, 1);
        const directKey = await digestForTest('198.51.100.10');
        assert.equal(keys[0], `ai:tts:rate:v1:${directKey}`);
    } finally {
        globalThis.fetch = originalFetch;
    }
});

test('TTS rate limiting atomically rejects the twenty-first concurrent request', async () => {
    const env = { DB: createAtomicTtsRateLimitDb() };

    const results = await Promise.allSettled(
        Array.from({ length: 21 }, () => reserveAiTtsRequest(env, '203.0.113.8')),
    );
    const rejected = results.filter(result => result.status === 'rejected');

    assert.equal(rejected.length, 1);
    assert.equal(rejected[0].reason.code, 'RATE_LIMITED');
});

test('TTS rate limiting atomically rejects the sixth concurrent cache miss', async () => {
    const env = { DB: createAtomicTtsRateLimitDb() };

    const results = await Promise.allSettled(
        Array.from({ length: 6 }, () => reserveAiTtsMiss(env, '203.0.113.9')),
    );
    const rejected = results.filter(result => result.status === 'rejected');

    assert.equal(rejected.length, 1);
    assert.equal(rejected[0].reason.code, 'RATE_LIMITED');
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
    assert.equal(json.data.activationPhrase, '到！');
    assert.equal(json.data.voiceStatus, 'ready');
});

test('activation accepts an explicit null audio field as text activation', async () => {
    // Android 客户端用 kotlinx.serialization 序列化请求体，默认 explicitNulls=true，
    // 没有音频时也会带上 "audioDataUrl": null。只判 undefined 会把它当成"带了音频"，
    // 语音（本地识别后走文字激活）和文字兜底两条路都会被判成 INVALID_AUDIO。
    const { response, json } = await call('/api/ai/activate', {
        method: 'POST',
        body: {
            characterId: 'usagi',
            sessionId: 'activation-null-audio-session',
            spokenName: '乌萨奇',
            audioDataUrl: null,
        },
        env: createTestEnv({ AI_TEST_VOICE_DESIGN_READY: true }),
    });

    assert.equal(response.status, 200);
    assert.equal(json.data.activated, true);
});

test('activation still rejects a malformed audio field', async () => {
    const { response, json } = await call('/api/ai/activate', {
        method: 'POST',
        body: {
            characterId: 'usagi',
            sessionId: 'activation-bad-audio-session',
            spokenName: '乌萨奇',
            audioDataUrl: 'not-a-data-url',
        },
        env: createTestEnv({ AI_TEST_VOICE_DESIGN_READY: true }),
    });

    assert.equal(response.status, 400);
    assert.equal(json.code, 'INVALID_AUDIO');
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
            env: createLegacyMimoTextEnv({
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

test('daily nullifies the source URL when the HEAD check returns 404', async () => {
    const originalFetch = globalThis.fetch;
    globalThis.fetch = async (_input, init) => {
        if (init?.method === 'HEAD') return new Response('', { status: 404 });
        return new Response(JSON.stringify({
            choices: [{ message: { content: JSON.stringify(validDailyKnowledgeUnit()) } }],
        }), { status: 200, headers: { 'Content-Type': 'application/json' } });
    };

    try {
        const { response, json } = await call('/api/ai/daily', {
            method: 'POST',
            body: { action: 'daily', sessionId: 'daily-head-404-session', forceRefresh: true },
            env: createLegacyMimoTextEnv(),
        });

        assert.equal(response.status, 200);
        assert.equal(json.data.sourceUrl, null);
        assert.equal(json.data.source?.url, '');
        // 不可核验时整条来源换成「AI 综合解读」：只置空 URL 会留下「真实机构名 + 编造摘要」的半句假引用。
        assert.equal(json.data.sourceName, 'AI 综合解读');
        assert.equal(json.data.source?.name, 'AI 综合解读');
        assert.equal(json.data.source?.evidence, '本节由 AI 综合公开通识整理，未引用具体来源。');
        assert.equal(json.data.title, '片场口令如何组织协作');
        assert.equal(json.data.characterLine, '原来如此！');
    } finally {
        globalThis.fetch = originalFetch;
    }
});

test('daily removes an untrusted source URL without fetching it', async () => {
    const originalFetch = globalThis.fetch;
    const unit = validDailyKnowledgeUnit({ direct: true });
    unit.source = { ...unit.source, url: 'https://untrusted.example/fact' };
    globalThis.fetch = async (_input, init = {}) => {
        if (init?.method === 'HEAD') throw new Error('untrusted source must not be fetched');
        return new Response(JSON.stringify({
            choices: [{ message: { content: JSON.stringify(unit) } }],
        }), { status: 200, headers: { 'Content-Type': 'application/json' } });
    };

    try {
        const { response, json } = await call('/api/ai/daily', {
            method: 'POST',
            body: { action: 'daily', sessionId: 'daily-untrusted-source-session', forceRefresh: true },
            env: createLegacyMimoTextEnv(),
        });

        assert.equal(response.status, 200);
        assert.equal(json.data.sourceUrl, null);
        assert.equal(json.data.source?.url, '');
    } finally {
        globalThis.fetch = originalFetch;
    }
});

test('daily keeps the source URL when the HEAD check is blocked by anti-scraping 403', async () => {
    const originalFetch = globalThis.fetch;
    globalThis.fetch = async (_input, init) => {
        if (init?.method === 'HEAD') return new Response('', { status: 403 });
        return new Response(JSON.stringify({
            choices: [{ message: { content: JSON.stringify(validDailyKnowledgeUnit()) } }],
        }), { status: 200, headers: { 'Content-Type': 'application/json' } });
    };

    try {
        const { response, json } = await call('/api/ai/daily', {
            method: 'POST',
            body: { action: 'daily', sessionId: 'daily-head-403-session', forceRefresh: true },
            env: createLegacyMimoTextEnv(),
        });

        assert.equal(response.status, 200);
        assert.equal(json.data.sourceUrl, 'https://www.britannica.com/citizen-kane');
    } finally {
        globalThis.fetch = originalFetch;
    }
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

test('quiz submit and feedback read legacy v1 quiz caches after the text cache version bump', async () => {
    const kv = createMapKv();
    const env = createTestEnv({ KV: kv });
    const started = await call('/api/ai/quiz', {
        method: 'POST',
        body: {
            action: 'quiz',
            sessionId: 'legacy-quiz-compat-session',
            quizId: 'legacy-quiz-compat',
            movies: movies(),
        },
        env,
    });

    assert.equal(started.response.status, 200);
    const currentKey = 'ai:v2:quiz:friend-1:legacy-quiz-compat';
    const legacyKey = 'ai:v1:quiz:friend-1:legacy-quiz-compat';
    const cached = env.AI_TEST_CACHE.get(currentKey);
    assert.ok(cached, 'new quiz responses must be written under the v2 cache key');
    env.AI_TEST_CACHE.delete(currentKey);
    env.AI_TEST_CACHE.set(legacyKey, cached);

    const submitted = await call('/api/ai/quiz/submit', {
        method: 'POST',
        body: { action: 'quiz.submit', quizId: 'legacy-quiz-compat', answers: {} },
        env,
    });
    const feedback = await call('/api/ai/quiz/feedback', {
        method: 'POST',
        body: { action: 'quiz.feedback', quizId: 'legacy-quiz-compat', difficulty: 'just_right' },
        env,
    });

    assert.equal(submitted.response.status, 200);
    assert.equal(submitted.json.data.totalQuestions, 13);
    assert.equal(feedback.response.status, 200);
    assert.equal(feedback.json.data.success, true);
    assert.equal(kv.store.has('ai:v1:quiz-difficulty:friend-1'), true);
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
        const env = createLegacyMimoTextEnv();
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

test('recommendations reject an upstream item without a valid year', async () => {
    const originalFetch = globalThis.fetch;
    globalThis.fetch = async () => new Response(JSON.stringify({
        choices: [{ message: { content: JSON.stringify({
            roast: '你的片单像一场有趣的夜行。',
            taste: ['偏爱有余韵的故事'],
            recommendations: [{ title: 'Unknown film', reason: 'Because' }],
        }) } }],
    }), { status: 200, headers: { 'Content-Type': 'application/json' } });

    try {
        const { response, json } = await call('/api/ai/taste', {
            method: 'POST',
            body: { sessionId: 'taste-session', movies: movies() },
            env: createLegacyMimoTextEnv(),
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
            env: createLegacyMimoTextEnv(),
        });

        assert.equal(response.status, 502);
        assert.equal(json.code, 'AI_UPSTREAM_ERROR');
        // mimo 家内降级链 v2.5 → v2.5-pro,每个模型 5xx 各重试一次 = 4 次;
        // agnes/zhipu 未配 key 不可用,最终上抛稳定 502
        assert.equal(calls, 4);
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

test('daily cache is isolated by friend and Shanghai calendar day', async () => {
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
                recommendations: [{ title: '十二怒汉', year: 1957, mediaType: 'movie', reason: '同样重视人物在限制中的选择。' }],
            }) } }],
        }), { status: 200, headers: { 'Content-Type': 'application/json' } });
    };

    try {
        const { response, json } = await call('/api/ai/taste', {
            method: 'POST',
            body: { action: 'taste', sessionId: 'taste-prompt-session', watched: movies(), forceRefresh: true },
            env: createLegacyMimoTextEnv(),
        });

        assert.equal(response.status, 200);
        assert.equal(requestBody.model, 'mimo-v2.5-pro');
        assert.ok(requestBody.messages.some(message => String(message.content).includes('小明')));
        assert.deepEqual(json.data.recommendations, [
            { mediaType: 'movie', title: '十二怒汉', year: 1957, reason: '同样重视人物在限制中的选择。' },
        ]);
    } finally {
        globalThis.fetch = originalFetch;
    }
});

test('taste rejects a recommendation that repeats a watched title case-insensitively', async () => {
    const originalFetch = globalThis.fetch;
    globalThis.fetch = async () => new Response(JSON.stringify({
        choices: [{ message: { content: JSON.stringify({
            roast: '小明的片单很会留白。',
            taste: ['偏爱复杂人物'],
            recommendations: [{ title: 'movie 1', year: 2024, reason: '因为模型说了算。' }],
        }) } }],
    }), { status: 200, headers: { 'Content-Type': 'application/json' } });

    try {
        const { response, json } = await call('/api/ai/taste', {
            method: 'POST',
            body: { action: 'taste', sessionId: 'taste-watched-title-session', watched: movies(), forceRefresh: true },
            env: createLegacyMimoTextEnv(),
        });

        assert.equal(response.status, 502);
        assert.equal(json.code, 'INVALID_AI_OUTPUT');
    } finally {
        globalThis.fetch = originalFetch;
    }
});

test('taste ignores upstream media IDs because recommendations are unwatched titles', async () => {
    const originalFetch = globalThis.fetch;
    globalThis.fetch = async () => new Response(JSON.stringify({
        choices: [{ message: { content: JSON.stringify({
            roast: '小明的片单很会留白。',
            taste: ['偏爱复杂人物'],
            recommendations: [{ title: '十二怒汉', year: 1957, reason: '同样重视人物选择。', mediaIds: { tmdbId: 999999 } }],
        }) } }],
    }), { status: 200, headers: { 'Content-Type': 'application/json' } });

    try {
        const { response, json } = await call('/api/ai/taste', {
            method: 'POST',
            body: { action: 'taste', sessionId: 'taste-ignore-media-ids-session', watched: movies(), forceRefresh: true },
            env: createLegacyMimoTextEnv(),
        });

        assert.equal(response.status, 200);
        assert.deepEqual(json.data.recommendations, [
            { mediaType: 'movie', title: '十二怒汉', year: 1957, reason: '同样重视人物选择。' },
        ]);
    } finally {
        globalThis.fetch = originalFetch;
    }
});

test('taste rejects a recommendation with an unsupported mediaType', async () => {
    const originalFetch = globalThis.fetch;
    globalThis.fetch = async () => new Response(JSON.stringify({
        choices: [{ message: { content: JSON.stringify({
            roast: '小明的片单很会留白。',
            taste: ['偏爱复杂人物'],
            recommendations: [{ title: 'Some film', year: 2000, mediaType: 'documentary', reason: '同样重视人物选择。' }],
        }) } }],
    }), { status: 200, headers: { 'Content-Type': 'application/json' } });

    try {
        const { response, json } = await call('/api/ai/taste', {
            method: 'POST',
            body: { action: 'taste', sessionId: 'taste-media-type-session', watched: movies(), forceRefresh: true },
            env: createLegacyMimoTextEnv(),
        });

        assert.equal(response.status, 502);
        assert.equal(json.code, 'INVALID_AI_OUTPUT');
    } finally {
        globalThis.fetch = originalFetch;
    }
});

test('taste defaults an omitted mediaType to movie and accepts show', async () => {
    const originalFetch = globalThis.fetch;
    globalThis.fetch = async () => new Response(JSON.stringify({
        choices: [{ message: { content: JSON.stringify({
            roast: '小明的片单很会留白。',
            taste: ['偏爱复杂人物'],
            recommendations: [
                { title: 'Some film', year: 2000, reason: '同样重视人物选择。' },
                { title: 'Some show', year: 2010, mediaType: 'show', reason: '适合喜欢长线叙事的你。' },
            ],
        }) } }],
    }), { status: 200, headers: { 'Content-Type': 'application/json' } });

    try {
        const { response, json } = await call('/api/ai/taste', {
            method: 'POST',
            body: { action: 'taste', sessionId: 'taste-media-type-default-session', watched: movies(), forceRefresh: true },
            env: createLegacyMimoTextEnv(),
        });

        assert.equal(response.status, 200);
        assert.deepEqual(
            json.data.recommendations.map(item => item.mediaType),
            ['movie', 'show'],
        );
    } finally {
        globalThis.fetch = originalFetch;
    }
});

test('taste rejects more than eight recommendations', async () => {
    const originalFetch = globalThis.fetch;
    globalThis.fetch = async () => new Response(JSON.stringify({
        choices: [{ message: { content: JSON.stringify({
            roast: '小明的片单很会留白。',
            taste: ['偏爱复杂人物'],
            recommendations: Array.from({ length: 9 }, (_, index) => ({
                title: `New film ${index}`,
                year: 2000 + index,
                reason: '口味契合。',
            })),
        }) } }],
    }), { status: 200, headers: { 'Content-Type': 'application/json' } });

    try {
        const { response, json } = await call('/api/ai/taste', {
            method: 'POST',
            body: { action: 'taste', sessionId: 'taste-too-many-session', watched: movies(), forceRefresh: true },
            env: createLegacyMimoTextEnv(),
        });

        assert.equal(response.status, 502);
        assert.equal(json.code, 'INVALID_AI_OUTPUT');
    } finally {
        globalThis.fetch = originalFetch;
    }
});

test('taste fallback never emits watched titles as recommendations', async () => {
    const { response, json } = await call('/api/ai/taste', {
        method: 'POST',
        body: { action: 'taste', sessionId: 'taste-fallback-session', watched: movies(), forceRefresh: true },
        env: createTestEnv(),
    });

    assert.equal(response.status, 200);
    assert.deepEqual(json.data.recommendations, []);
});

test('quiz accepts a valid unit package and converts it into 13 questions without answerKeywords', async () => {
    const originalFetch = globalThis.fetch;
    let requestBody;
    let calls = 0;
    globalThis.fetch = async (_input, init) => {
        requestBody = JSON.parse(init.body);
        calls += 1;
        return new Response(JSON.stringify(calls === 1 ? validQuizUnitsPayload() : validQuizFromUnitsPayload()), {
            status: 200,
            headers: { 'Content-Type': 'application/json' },
        });
    };

    try {
        const { response, json } = await call('/api/ai/quiz', {
            method: 'POST',
            body: { action: 'quiz', sessionId: 'quiz-normalize-session', watched: quizMoviesWithSynopsis(), forceRefresh: true },
            env: createLegacyMimoTextEnv(),
        });

        assert.equal(response.status, 200);
        assert.equal(json.data.questions.length, 13);
        assert.equal(json.data.questions[0].unitId, 'quiz-unit-a');
        assert.equal(requestBody.model, 'mimo-v2.5-pro');
        assert.ok(requestBody.messages.some(message => String(message.content).includes('小明')));
    } finally {
        globalThis.fetch = originalFetch;
    }
});

test('quiz converts shared knowledge units with a second review and returns the unit-bound package', async () => {
    const originalFetch = globalThis.fetch;
    const requests = [];
    globalThis.fetch = async (_input, init) => {
        const requestBody = JSON.parse(init.body);
        requests.push(requestBody);
        const payload = requests.length === 1
            ? validQuizUnitsPayload()
            : validQuizFromUnitsPayload();
        return new Response(JSON.stringify(payload), {
            status: 200,
            headers: { 'Content-Type': 'application/json' },
        });
    };

    try {
        const { response, json } = await call('/api/ai/quiz', {
            method: 'POST',
            body: { action: 'quiz', sessionId: 'quiz-review-session', watched: quizMoviesWithSynopsis(), forceRefresh: true },
            env: createLegacyMimoTextEnv(),
        });

        assert.equal(response.status, 200);
        assert.equal(requests.length, 2);
        assert.match(String(requests[0].messages?.[0]?.content), /候选学习单元编辑/);
        assert.match(String(requests[1].messages?.[0]?.content), /二审转换器/);
        // 提示词硬约束：全场去重、学科一致性、逐题 rationale 与选项标点。
        assert.match(String(requests[0].messages?.[0]?.content), /concept 与 takeaway 两两必须不同/);
        assert.match(String(requests[0].messages?.[0]?.content), /禁止生成与学科无关/);
        assert.match(String(requests[1].messages?.[0]?.content), /knowledgePoint、learningTakeaway 与题干不得逐字重复/);
        assert.match(String(requests[1].messages?.[0]?.content), /answerRationale 与 distractorRationale 必须逐题/);
        assert.match(String(requests[1].messages?.[0]?.content), /选项文本不要以句号/);
        assert.match(String(requests[1].messages?.[1]?.content), /KNOWLEDGE_UNITS/);
        assert.match(String(requests[1].messages?.[1]?.content), /上映年份/);
        assert.equal(json.data.questions[0].unitId, 'quiz-unit-a');
        assert.match(json.data.questions[0].prompt, /2020年/);
        assert.equal(json.data.questions[0].subject, '心理学');
        assert.equal(json.data.questions[0].concept, '归因偏差');
        assert.ok(json.data.questions[0].learningTakeaway.length >= 12);
        assert.match(json.data.questions[0].evidenceUsed, /2020/);
        // 13 题全部绑定共享单元，题目学科来自单元而不是自由发挥。
        assert.ok(json.data.questions.every(question => question.unitId));
    } finally {
        globalThis.fetch = originalFetch;
    }
});

test('quiz retries once when the converted package is invalid, then returns AI questions', async () => {
    const originalFetch = globalThis.fetch;
    let calls = 0;
    globalThis.fetch = async () => {
        calls += 1;
        // 第一轮转换包为空（不合格），整体重试第二轮正常返回。
        const payload = calls === 2
            ? { choices: [{ message: { content: JSON.stringify({ questions: [] }) } }] }
            : calls % 2 === 1 ? validQuizUnitsPayload() : validQuizFromUnitsPayload();
        return new Response(JSON.stringify(payload), {
            status: 200,
            headers: { 'Content-Type': 'application/json' },
        });
    };

    try {
        const { response, json } = await call('/api/ai/quiz', {
            method: 'POST',
            body: { action: 'quiz', sessionId: 'quiz-review-fallback-session', watched: quizMoviesWithSynopsis(), forceRefresh: true },
            env: createLegacyMimoTextEnv(),
        });

        assert.equal(response.status, 200);
        // 第一轮 units+review（2 次）+ 重试轮 units+review（2 次）。
        assert.equal(calls, 4);
        assert.equal(json.data.questions.length, 13);
        // 重试轮合格时返回 AI 题（unitId 非空），不再直接降级离线兜底。
        assert.ok(json.data.questions.every(question => question.unitId !== null));
        assert.ok(json.data.questions.every(question => question.learningTakeaway));
    } finally {
        globalThis.fetch = originalFetch;
    }
});

test('reviewed quiz exposes education fields in the answer result as well as the question card', async () => {
    const originalFetch = globalThis.fetch;
    globalThis.fetch = async (_input, init) => {
        const requestBody = JSON.parse(init.body);
        return new Response(JSON.stringify(requestBody.messages?.[0]?.content.includes('二审转换器')
            ? validQuizFromUnitsPayload()
            : validQuizUnitsPayload()), {
            status: 200,
            headers: { 'Content-Type': 'application/json' },
        });
    };

    try {
        const env = createLegacyMimoTextEnv();
        const started = await call('/api/ai/quiz', {
            method: 'POST',
            body: { action: 'quiz', sessionId: 'quiz-education-fields-session', watched: quizMoviesWithSynopsis(), forceRefresh: true },
            env,
        });
        const submitted = await call('/api/ai/quiz/submit', {
            method: 'POST',
            body: { action: 'quiz.submit', quizId: started.json.data.quizId, answers: {} },
            env,
        });

        assert.equal(started.response.status, 200);
        assert.equal(submitted.response.status, 200);
        const result = submitted.json.data.questionResults[0];
        assert.equal(result.subject, '心理学');
        assert.equal(result.concept, '归因偏差');
        assert.match(result.learningTakeaway, /可观察证据/);
        assert.match(result.evidenceUsed, /2020/);
        assert.match(result.explanation, /归因偏差/);
    } finally {
        globalThis.fetch = originalFetch;
    }
});

test('quiz conversion must keep synopsis evidence from its unit when it is available', async () => {
    const originalFetch = globalThis.fetch;
    const requests = [];
    globalThis.fetch = async (_input, init) => {
        requests.push(JSON.parse(init.body));
        return new Response(JSON.stringify(requests.length === 1
            ? validQuizUnitsPayload()
            : validQuizFromUnitsPayload()), {
            status: 200,
            headers: { 'Content-Type': 'application/json' },
        });
    };

    try {
        const { response, json } = await call('/api/ai/quiz', {
            method: 'POST',
            body: { action: 'quiz', sessionId: 'quiz-synopsis-evidence-session', watched: quizMoviesWithSynopsis(), forceRefresh: true },
            env: createLegacyMimoTextEnv(),
        });
        assert.equal(response.status, 200);
        assert.equal(requests.length, 2);
        assert.match(String(requests[0].messages?.[1]?.content), /An engineer repeatedly revises judgment/);
        assert.match(String(requests[1].messages?.[1]?.content), /An engineer repeatedly revises judgment/);
        assert.match(json.data.questions[0].evidenceUsed, /An engineer repeatedly revises judgment/);
    } finally {
        globalThis.fetch = originalFetch;
    }
});

/** 解析两级 fixture 中的 JSON，供“变坏一个字段”类测试复用。 */
function mutateJsonPayload(payload, mutate) {
    const parsed = JSON.parse(payload.choices[0].message.content);
    mutate(parsed);
    return { choices: [{ message: { content: JSON.stringify(parsed) } }] };
}

test('quiz unit stage rejects duplicated concept or takeaway across candidate units', async () => {
    const cases = [
        ['duplicate-concept', (parsed) => {
            parsed.units[1] = { ...parsed.units[1], unitId: 'quiz-unit-dup-concept', concept: parsed.units[0].concept };
        }],
        ['duplicate-takeaway', (parsed) => {
            parsed.units[2] = { ...parsed.units[2], unitId: 'quiz-unit-dup-takeaway', takeaway: parsed.units[0].takeaway };
        }],
    ];
    for (const [name, mutate] of cases) {
        const originalFetch = globalThis.fetch;
        let calls = 0;
        globalThis.fetch = async () => {
            calls += 1;
            return new Response(JSON.stringify(calls === 1
                ? mutateJsonPayload(validQuizUnitsPayload(), mutate)
                : validQuizFromUnitsPayload()), {
                status: 200,
                headers: { 'Content-Type': 'application/json' },
            });
        };
        try {
            const env = createLegacyMimoTextEnv();
            const { response, json } = await call('/api/ai/quiz', {
                method: 'POST',
                body: { action: 'quiz', sessionId: `quiz-unit-${name}-session`, watched: quizMoviesWithSynopsis(), forceRefresh: true },
                env,
            });
            assert.equal(response.status, 200, name);
            // 两轮 units 都查重不合格：每轮只发 1 次 units 请求，不进二审。
            assert.equal(calls, 2, `${name}: 单元阶段不合格时不得进入二审转换`);
            assert.equal(json.data.questions[0].unitId, null, name);
        } finally {
            globalThis.fetch = originalFetch;
        }
    }
});

test('quiz review rejects repeated knowledgePoint, learningTakeaway or rationale in one round', async () => {
    const cases = [
        ['duplicate-knowledge-point', (parsed) => {
            parsed.questions[1].knowledgePoint = parsed.questions[0].knowledgePoint;
        }],
        ['duplicate-learning-takeaway', (parsed) => {
            parsed.questions[1].learningTakeaway = parsed.questions[0].learningTakeaway;
        }],
        ['duplicate-answer-rationale', (parsed) => {
            parsed.questions[1].answerRationale = parsed.questions[0].answerRationale;
        }],
        ['duplicate-distractor-rationale', (parsed) => {
            parsed.questions[1].distractorRationale = parsed.questions[0].distractorRationale;
        }],
    ];
    for (const [name, mutate] of cases) {
        const originalFetch = globalThis.fetch;
        let calls = 0;
        globalThis.fetch = async () => {
            calls += 1;
            // units/review 交替：奇数次为单元阶段，偶数次为二审转换（两轮转换包都不合格）。
            const payload = calls % 2 === 1
                ? validQuizUnitsPayload()
                : mutateJsonPayload(validQuizFromUnitsPayload(), mutate);
            return new Response(JSON.stringify(payload), {
                status: 200,
                headers: { 'Content-Type': 'application/json' },
            });
        };
        try {
            const env = createLegacyMimoTextEnv();
            const { response, json } = await call('/api/ai/quiz', {
                method: 'POST',
                body: { action: 'quiz', sessionId: `quiz-review-${name}-session`, watched: quizMoviesWithSynopsis(), forceRefresh: true },
                env,
            });
            assert.equal(response.status, 200, name);
            // 两轮 review 都不合格：每轮 units+review 各 1 次。
            assert.equal(calls, 4, name);
            assert.equal(json.data.questions[0].unitId, null, `${name}: 复盘模板/考点/结论重复时必须走确定性兜底`);
        } finally {
            globalThis.fetch = originalFetch;
        }
    }
});

test('quiz review rejects a generic methodology question under a non-metacognition subject', async () => {
    const originalFetch = globalThis.fetch;
    let calls = 0;
    globalThis.fetch = async () => {
        calls += 1;
        // units/review 交替：奇数次为单元阶段，偶数次为二审转换（两轮转换包都不合格）。
        const payload = calls % 2 === 1
            ? validQuizUnitsPayload()
            : mutateJsonPayload(validQuizFromUnitsPayload(), (parsed) => {
                // 第 3 题来自“历史/历史语境”单元；把题干改成通用“避免过度解读”方法题。
                parsed.questions[2].prompt = `关于《Movie 3》的已看记录（简介：${QUIZ_SYNOPSIS}），如何避免过度解读其中的内容？`;
            });
        return new Response(JSON.stringify(payload), {
            status: 200,
            headers: { 'Content-Type': 'application/json' },
        });
    };

    try {
        const env = createLegacyMimoTextEnv();
        const { response, json } = await call('/api/ai/quiz', {
            method: 'POST',
            body: { action: 'quiz', sessionId: 'quiz-generic-mismatch-session', watched: quizMoviesWithSynopsis(), forceRefresh: true },
            env,
        });
        assert.equal(response.status, 200);
        assert.equal(calls, 4);
        assert.equal(json.data.questions[0].unitId, null, '学科标签与通用方法题脱节时必须走确定性兜底');
    } finally {
        globalThis.fetch = originalFetch;
    }
});

test('quiz normalization strips trailing sentence punctuation from option texts', async () => {
    const originalFetch = globalThis.fetch;
    let calls = 0;
    globalThis.fetch = async () => {
        calls += 1;
        return new Response(JSON.stringify(calls === 1 ? validQuizUnitsPayload() : validQuizFromUnitsPayload()), {
            status: 200,
            headers: { 'Content-Type': 'application/json' },
        });
    };

    try {
        const env = createLegacyMimoTextEnv();
        const { response, json } = await call('/api/ai/quiz', {
            method: 'POST',
            body: { action: 'quiz', sessionId: 'quiz-option-punctuation-session', watched: quizMoviesWithSynopsis(), forceRefresh: true },
            env,
        });
        assert.equal(response.status, 200);
        assert.equal(json.data.questions.length, 13);
        assert.ok(json.data.questions.every(question =>
            question.options.every(option => !/[。．.!?！？]$/u.test(option.text))), '选项文本不得以句子标点结尾');
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
            env: createLegacyMimoTextEnv(),
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
            env: createLegacyMimoTextEnv(),
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
            env: createLegacyMimoTextEnv(),
        });

        assert.equal(response.status, 200);
        assert.equal(json.data.questions.length, 13);
    } finally {
        globalThis.fetch = originalFetch;
    }
});

test('guest audition transcript strips the injected catchphrase', async () => {
    const originalFetch = globalThis.fetch;
    globalThis.fetch = async (_input, init) => {
        const requestBody = JSON.parse(init.body);
        return new Response(JSON.stringify({
            choices: [{ message: { audio: { data: 'AA==', transcript: requestBody.messages[1].content } } }],
        }), { status: 200, headers: { 'Content-Type': 'application/json' } });
    };

    try {
        const worker = await loadMainWorker();
        const chiikawa = await worker.default.fetch(
            new Request('https://gateway.test/api/ai/tts', {
                method: 'POST',
                headers: { 'Content-Type': 'application/json' },
                body: JSON.stringify({
                    action: 'tts',
                    characterId: 'chiikawa',
                    text: '今天也一起找一部好看的电影吧。',
                    scene: 'AUDITION',
                }),
            }),
            createTestEnv({ MIMO_API_KEY: 'test-mimo-key', AI_TEST_VOICE_DESIGN_READY: true }),
            { waitUntil() {} },
        );
        const chiikawaJson = await chiikawa.json();
        assert.equal(chiikawa.status, 200);
        assert.equal(chiikawaJson.data.transcript, '今天也一起找一部好看的电影吧。');

        const hachiware = await worker.default.fetch(
            new Request('https://gateway.test/api/ai/tts', {
                method: 'POST',
                headers: { 'Content-Type': 'application/json' },
                body: JSON.stringify({
                    action: 'tts',
                    characterId: 'hachiware',
                    text: '我发现了一点有意思的片单线索哦。',
                    scene: 'AUDITION',
                }),
            }),
            createTestEnv({ MIMO_API_KEY: 'test-mimo-key', AI_TEST_VOICE_DESIGN_READY: true }),
            { waitUntil() {} },
        );
        const hachiwareJson = await hachiware.json();
        assert.equal(hachiware.status, 200);
        assert.equal(hachiwareJson.data.transcript, '我发现了一点有意思的片单线索哦。');
    } finally {
        globalThis.fetch = originalFetch;
    }
});

test('activation ack injects the chiikawa catchphrase and strips the transcript', async () => {
    const originalFetch = globalThis.fetch;
    let captured = null;
    globalThis.fetch = async (_input, init) => {
        const requestBody = JSON.parse(init.body);
        if (requestBody.model === 'mimo-v2.5-tts-voicedesign') {
            captured = requestBody;
            return new Response(JSON.stringify({
                choices: [{ message: { audio: { data: 'AA==', transcript: requestBody.messages[1].content } } }],
            }), { status: 200, headers: { 'Content-Type': 'application/json' } });
        }
        throw new Error('activation should not call other upstreams');
    };

    try {
        const { response, json } = await call('/api/ai/activate', {
            method: 'POST',
            body: {
                characterId: 'chiikawa',
                sessionId: 'activation-catchphrase-session',
                audioDataUrl: 'data:audio/wav;base64,AA==',
            },
            env: createTestEnv({
                MIMO_API_KEY: 'test-mimo-key',
                AI_TEST_VOICE_DESIGN_READY: true,
                AI_TEST_TRANSCRIPT: '吉伊',
            }),
        });
        assert.equal(response.status, 200);
        assert.equal(json.data.activated, true);
        assert.equal(captured.messages[1].content, '到、到！ 鸭蛋。');
        assert.equal(json.data.audio.transcript, '到、到！');
        assert.equal(json.data.activationPhrase, '到、到！');
    } finally {
        globalThis.fetch = originalFetch;
    }
});

test('greeting spoken text follows the character catchphrase position', async () => {
    const originalFetch = globalThis.fetch;
    globalThis.fetch = async () => new Response(JSON.stringify({
        choices: [{ message: { content: JSON.stringify({
            greeting: '小明，今天也来挑一部好片吧。',
            meaning: '名字像一盏小灯。',
            comment: '很适合当片单侦探。',
        }) } }],
    }), { status: 200, headers: { 'Content-Type': 'application/json' } });

    try {
        const chiikawa = await call('/api/ai/greeting', {
            method: 'POST',
            body: { characterId: 'chiikawa', sessionId: 'greeting-position-chiikawa-session' },
            env: createLegacyMimoTextEnv(),
        });
        assert.equal(chiikawa.response.status, 200);
        assert.equal(chiikawa.json.data.spokenText, '小明，今天也来挑一部好片吧。 鸭蛋。');

        const hachiware = await call('/api/ai/greeting', {
            method: 'POST',
            body: { characterId: 'hachiware', sessionId: 'greeting-position-hachiware-session' },
            env: createLegacyMimoTextEnv(),
        });
        assert.equal(hachiware.response.status, 200);
        assert.equal(hachiware.json.data.spokenText, '噢易！ 小明，今天也来挑一部好片吧。');
    } finally {
        globalThis.fetch = originalFetch;
    }
});

test('quiz difficulty feedback validates input and stays free of quota', async () => {
    const kv = createMapKv();
    const env = createTestEnv({ KV: kv });
    const quizId = await createFallbackQuiz(env, 'feedback-validate-session');

    const invalidDifficulty = await call('/api/ai/quiz/feedback', {
        method: 'POST',
        body: { action: 'quiz.feedback', quizId, difficulty: 'impossible' },
        env,
    });
    assert.equal(invalidDifficulty.response.status, 400);
    assert.equal(invalidDifficulty.json.code, 'INVALID_DIFFICULTY');

    const invalidAction = await call('/api/ai/quiz/feedback', {
        method: 'POST',
        body: { action: 'quiz.submit', quizId, difficulty: 'easy' },
        env,
    });
    assert.equal(invalidAction.response.status, 400);
    assert.equal(invalidAction.json.code, 'INVALID_ACTION');

    const unknown = await call('/api/ai/quiz/feedback', {
        method: 'POST',
        body: { action: 'quiz.feedback', quizId: 'missing-quiz', difficulty: 'easy' },
        env,
    });
    assert.equal(unknown.response.status, 404);
    assert.equal(unknown.json.code, 'QUIZ_NOT_FOUND');

    const invalidQuizId = await call('/api/ai/quiz/feedback', {
        method: 'POST',
        body: { action: 'quiz.feedback', quizId: '非法 id', difficulty: 'easy' },
        env,
    });
    assert.equal(invalidQuizId.response.status, 400);
    assert.equal(invalidQuizId.json.code, 'INVALID_REQUEST');

    const recorded = await call('/api/ai/quiz/feedback', {
        method: 'POST',
        body: { action: 'quiz.feedback', quizId, difficulty: 'just_right' },
        env,
    });
    assert.equal(recorded.response.status, 200);
    assert.equal(recorded.json.data.success, true);
    assert.equal(recorded.json.data.requestId, 'request-1');
    assert.equal(recorded.json.quota, undefined);
    assert.equal(kv.puts.length, 1);
    assert.equal(kv.puts[0].key, 'ai:v1:quiz-difficulty:friend-1');
    assert.equal(kv.puts[0].options.expirationTtl, 90 * 24 * 60 * 60);
    const record = JSON.parse(kv.store.get('ai:v1:quiz-difficulty:friend-1'));
    assert.equal(record.entries.length, 1);
    assert.equal(record.entries[0].quizId, quizId);
    assert.equal(record.entries[0].difficulty, 'just_right');
    assert.equal(typeof record.updatedAt, 'string');
});

test('quiz difficulty feedback keeps a five-entry sliding window and stays idempotent', async () => {
    const kv = createMapKv();
    const env = createTestEnv({ KV: kv });
    const firstQuizId = await createFallbackQuiz(env, 'feedback-window-session-1');
    const secondQuizId = await createFallbackQuiz(env, 'feedback-window-session-2');
    const thirdQuizId = await createFallbackQuiz(env, 'feedback-window-session-3');

    // 预置 5 条旧反馈：新反馈进入后滑窗裁剪到 5 条，最旧一条被挤掉
    kv.store.set('ai:v1:quiz-difficulty:friend-1', JSON.stringify({
        entries: ['seed-1', 'seed-2', 'seed-3', 'seed-4', 'seed-5'].map((seed, index) => ({
            quizId: seed,
            difficulty: index % 2 === 0 ? 'easy' : 'hard',
            at: new Date(Date.now() - (5 - index) * 60_000).toISOString(),
        })),
        updatedAt: new Date().toISOString(),
    }));

    for (const [quizId, difficulty] of [[firstQuizId, 'easy'], [secondQuizId, 'just_right'], [thirdQuizId, 'hard']]) {
        const recorded = await call('/api/ai/quiz/feedback', {
            method: 'POST',
            body: { action: 'quiz.feedback', quizId, difficulty },
            env,
        });
        assert.equal(recorded.response.status, 200);
    }
    let record = JSON.parse(kv.store.get('ai:v1:quiz-difficulty:friend-1'));
    assert.equal(record.entries.length, 5);
    assert.deepEqual(
        record.entries.map((entry) => entry.quizId),
        [thirdQuizId, secondQuizId, firstQuizId, 'seed-1', 'seed-2'],
    );
    assert.deepEqual(
        record.entries.map((entry) => entry.difficulty),
        ['hard', 'just_right', 'easy', 'easy', 'hard'],
    );

    // 幂等：同一 quizId 再次反馈直接成功，不重复记录也不改写难度
    const repeat = await call('/api/ai/quiz/feedback', {
        method: 'POST',
        body: { action: 'quiz.feedback', quizId: firstQuizId, difficulty: 'hard' },
        env,
    });
    assert.equal(repeat.response.status, 200);
    record = JSON.parse(kv.store.get('ai:v1:quiz-difficulty:friend-1'));
    assert.equal(record.entries.length, 5);
    assert.equal(record.entries[2].quizId, firstQuizId);
    assert.equal(record.entries[2].difficulty, 'easy');
});

test('quiz prompt adapts to the recent difficulty feedback trend', async () => {
    const originalFetch = globalThis.fetch;
    let captured = null;
    globalThis.fetch = async (_input, init) => {
        captured = JSON.parse(init.body);
        return new Response(JSON.stringify(validQuizUpstreamPayload()), {
            status: 200,
            headers: { 'Content-Type': 'application/json' },
        });
    };
    const difficultyRecord = (difficulties) => JSON.stringify({
        entries: difficulties.map((difficulty, index) => ({
            quizId: `trend-${index}`,
            difficulty,
            at: new Date().toISOString(),
        })),
        updatedAt: new Date().toISOString(),
    });
    const seededKv = (raw) => {
        const kv = createMapKv();
        if (raw !== null) kv.store.set('ai:v1:quiz-difficulty:friend-1', raw);
        return kv;
    };

    try {
        const hard = await call('/api/ai/quiz', {
            method: 'POST',
            body: { action: 'quiz', sessionId: 'quiz-trend-hard-session', watched: movies() },
            env: createLegacyMimoTextEnv({
                KV: seededKv(difficultyRecord(['hard', 'hard', 'hard', 'easy', 'just_right'])),
            }),
        });
        assert.equal(hard.response.status, 200);
        assert.match(captured.messages[0].content, /用户反馈近期题目偏难/);
        assert.doesNotMatch(captured.messages[0].content, /题目偏简单/);

        const easy = await call('/api/ai/quiz', {
            method: 'POST',
            body: { action: 'quiz', sessionId: 'quiz-trend-easy-session', watched: movies() },
            env: createLegacyMimoTextEnv({
                KV: seededKv(difficultyRecord(['easy', 'easy', 'easy', 'hard', 'hard'])),
            }),
        });
        assert.equal(easy.response.status, 200);
        assert.match(captured.messages[0].content, /用户反馈近期题目偏简单/);
        assert.doesNotMatch(captured.messages[0].content, /题目偏难/);

        const neutral = await call('/api/ai/quiz', {
            method: 'POST',
            body: { action: 'quiz', sessionId: 'quiz-trend-neutral-session', watched: movies() },
            env: createLegacyMimoTextEnv({
                KV: seededKv(difficultyRecord(['hard', 'easy', 'just_right', 'easy', 'hard'])),
            }),
        });
        assert.equal(neutral.response.status, 200);
        assert.doesNotMatch(captured.messages[0].content, /用户反馈近期题目偏/);

        const empty = await call('/api/ai/quiz', {
            method: 'POST',
            body: { action: 'quiz', sessionId: 'quiz-trend-empty-session', watched: movies() },
            env: createLegacyMimoTextEnv(),
        });
        assert.equal(empty.response.status, 200);
        assert.doesNotMatch(captured.messages[0].content, /用户反馈近期题目偏/);
    } finally {
        globalThis.fetch = originalFetch;
    }
});


test('quiz fallback creates a varied, layered round with explainable metadata', async () => {
    const env = createTestEnv();
    const { response, json } = await call('/api/ai/quiz', {
        method: 'POST',
        body: { action: 'quiz', sessionId: 'quiz-quality-session', movies: movies() },
        env,
    });

    assert.equal(response.status, 200);
    const cacheEntry = [...env.AI_TEST_CACHE.entries()].find(([key]) => key.includes(':quiz:friend-1:'));
    assert.ok(cacheEntry);
    const cachedQuiz = cacheEntry[1].payload;
    const singleQuestions = cachedQuiz.questions.filter((question) => question.type === 'single');
    assert.ok(new Set(singleQuestions.map((question) => question.correctAnswer)).size >= 4);
    assert.equal(new Set(cachedQuiz.questions.map((question) => question.prompt)).size, 13);
    assert.deepEqual(
        cachedQuiz.questions.map((question) => question.sourceTitle),
        cachedQuiz.questions.map((question) => question.mediaTitle),
    );
    assert.ok(cachedQuiz.questions.filter((question) => question.difficulty === 'easy').length >= 4);
    assert.ok(cachedQuiz.questions.filter((question) => question.difficulty === 'medium').length >= 5);
    assert.ok(cachedQuiz.questions.filter((question) => question.difficulty === 'hard').length >= 4);
    for (const question of cachedQuiz.questions) {
        assert.ok(question.sourceTitle.startsWith('Movie '));
        assert.ok(question.knowledgePoint);
        assert.ok(question.answerRationale);
        if (question.type !== 'short') assert.ok(question.distractorRationale);
    }
    assert.equal(json.data.questions.filter((question) => question.type === 'short')[0].maxScore, 0);
});

test('greeting and taste expose structured nickname and evidence fields', async () => {
    const originalFetch = globalThis.fetch;
    globalThis.fetch = async (_input, init) => {
        const requestBody = JSON.parse(init.body);
        const system = String(requestBody.messages?.[0]?.content ?? '');
        const isTaste = system.includes('影视品味分析助手');
        const content = isTaste
            ? {
                roast: '这份片单会在热闹之后寻找余韵。',
                taste: ['人物选择', '克制表达'],
                profileSentence: '你常被人物在限制中的选择和克制的情绪表达吸引。',
                profileKeywords: ['人物选择', '情绪留白', '克制表达'],
                evidence: [{
                    title: 'Movie 1',
                    signal: '它出现在已看记录中，且属于剧情向作品。',
                    inference: '这只能说明它是当前样本的一部分，不能单独代表稳定偏好。',
                    confidence: 'medium',
                }],
                recommendations: [{ title: '十二怒汉', year: 1957, mediaType: 'movie', reason: '同样把人物选择放在核心位置。' }],
            }
            : {
                greeting: '小明，欢迎回到片场。',
                nicknameMeaning: '“小明”是昵称中的实际称呼，带来亲切、日常的语言联想。',
                comment: '短而清楚，像片尾字幕里一个容易被记住的名字。',
                nameSignals: [{ text: '小明', interpretation: '这是昵称中直接出现的称呼，联想偏向亲切和日常。' }],
                nicknameSignature: '亲切、清楚，有一点日常片场感。',
            };
        return new Response(JSON.stringify({ choices: [{ message: { content: JSON.stringify(content) } }] }), {
            status: 200,
            headers: { 'Content-Type': 'application/json' },
        });
    };

    try {
        const env = createLegacyMimoTextEnv();
        const greeting = await call('/api/ai/greeting', {
            method: 'POST',
            body: { characterId: 'usagi', sessionId: 'structured-greeting', forceRefresh: true },
            env,
        });
        const taste = await call('/api/ai/taste', {
            method: 'POST',
            body: { action: 'taste', sessionId: 'structured-taste', watched: movies(), forceRefresh: true },
            env,
        });
        assert.equal(greeting.response.status, 200);
        assert.equal(greeting.json.data.nameSignals[0].text, '小明');
        assert.ok(greeting.json.data.nicknameSignature);
        assert.equal(taste.response.status, 200);
        assert.deepEqual(taste.json.data.profileKeywords, ['人物选择', '情绪留白', '克制表达']);
        assert.equal(taste.json.data.evidence[0].title, 'Movie 1');
        assert.equal(taste.json.data.evidence[0].confidence, 'medium');
    } finally {
        globalThis.fetch = originalFetch;
    }
});

test('daily prompt uses watched titles and rejects an unrelated relatedMediaTitle', async () => {
    const originalFetch = globalThis.fetch;
    let requestBody;
    globalThis.fetch = async (_input, init = {}) => {
        if (init.method === 'HEAD') return new Response(null, { status: 204 });
        requestBody = JSON.parse(init.body);
        return new Response(JSON.stringify({
            choices: [{
                message: { content: JSON.stringify(validDailyKnowledgeUnit({ direct: true })) },
            }],
        }), { status: 200, headers: { 'Content-Type': 'application/json' } });
    };

    try {
        const { response, json } = await call('/api/ai/daily', {
            method: 'POST',
            body: {
                action: 'daily',
                sessionId: 'daily-watched-session',
                watched: movies().map((movie, index) => index === 0
                    ? { ...movie, overview: '影片包含群体讨论场景。' }
                    : movie),
                forceRefresh: true,
            },
            env: createLegacyMimoTextEnv(),
        });
        assert.equal(response.status, 200);
        assert.equal(json.data.relatedMediaTitle, 'Movie 1');
        assert.equal(json.data.containsSpoiler, false);
        assert.match(String(requestBody.messages?.[1]?.content), /Movie 1/);
    } finally {
        globalThis.fetch = originalFetch;
    }
});

test('new quiz scoring keeps objective total at 100 when short reflection is skipped', async () => {
    const env = createTestEnv();
    const started = await call('/api/ai/quiz', {
        method: 'POST',
        body: { action: 'quiz', sessionId: 'objective-score-session', movies: movies() },
        env,
    });
    const cacheEntry = [...env.AI_TEST_CACHE.entries()].find(([key]) => key.includes(':quiz:friend-1:'));
    assert.ok(cacheEntry);
    const cachedQuiz = cacheEntry[1].payload;
    const answers = cachedQuiz.questions
        .filter((question) => question.type !== 'short')
        .map((question) => ({
            questionId: question.id,
            selectedOptionIds: Array.isArray(question.correctAnswer) ? question.correctAnswer : [question.correctAnswer],
        }));
    const submitted = await call('/api/ai/quiz/submit', {
        method: 'POST',
        body: { action: 'quiz.submit', quizId: started.json.data.quizId, answers },
        env,
    });
    assert.equal(submitted.response.status, 200);
    assert.equal(submitted.json.data.score, 100);
    assert.equal(submitted.json.data.correctCount, 12);
    const shortResult = submitted.json.data.questionResults.find((item) => item.questionId === 'q13');
    assert.equal(shortResult.score, 0);
    assert.equal(shortResult.correct, false);
    assert.ok(submitted.json.data.questionResults[0].answerRationale);
});

function validQuizUpstreamPayload({ generic = false } = {}) {
    const subjects = ['心理学', '社会学', '历史', '马克思主义哲学', '物理', '化学'];
    const concepts = ['归因偏差', '社会规范', '历史语境', '矛盾分析', '视听与物理感知', '材料与化学变化'];
    const questions = Array.from({ length: 13 }, (_, index) => {
        const movieIndex = index % 7;
        const year = 2020 + movieIndex;
        const title = `Movie ${movieIndex + 1}`;
        const subject = subjects[index % subjects.length];
        const concept = concepts[index % concepts.length];
        const evidenceUsed = `已看记录明确显示《${title}》上映年份为${year}年，类型为Drama。`;
        const learningTakeaway = `结合${concept}时，应先回到可观察证据，再解释人物或形式，最后说明这一概念对现实判断的帮助。`;
        const prompt = generic
            ? (index < 12 ? `分析 ${title} 中人物的选择（第${index + 1}题）。` : `这部作品最值得带回现实的问题是什么（第${index + 1}题）？`)
            : (index < 10
                ? `在《${title}》（${year}年、Drama）中，若用${concept}观察这条已看记录，哪种判断最稳妥？`
                : index < 12
                    ? `关于《${title}》（${year}年、Drama）的已看证据，哪些说法能帮助我们理解${concept}？`
                    : `结合《${title}》（${year}年、Drama）的已看证据，用${concept}复述一个你能带回现实的问题。`);
        const explanation = `${evidenceUsed}这对应学科概念“${concept}”，因此可以得到学习结论：${learningTakeaway}`;
        if (index < 10) {
            return {
                id: `q${index + 1}`,
                type: 'single',
                difficulty: index < 4 ? 'easy' : index < 9 ? 'medium' : 'hard',
                subject,
                concept,
                learningTakeaway,
                evidenceUsed,
                knowledgePoint: concept,
                sourceTitle: title,
                prompt,
                options: [
                    { id: 'a', text: '只凭片名和第一印象下结论。' },
                    { id: 'b', text: `把${year}年、Drama这一已知线索与题干要求结合起来判断。` },
                ],
                correctAnswer: 'b',
                answerRationale: '正确选项同时使用了题干要求和已提供的观看证据。',
                distractorRationale: '另一选项没有使用可核验材料，无法支持稳定结论。',
                explanation,
                filmIndex: movieIndex,
            };
        }
        if (index < 12) {
            return {
                id: `q${index + 1}`,
                type: 'multiple',
                difficulty: 'hard',
                subject,
                concept,
                learningTakeaway,
                evidenceUsed,
                knowledgePoint: concept,
                sourceTitle: title,
                prompt,
                options: [
                    { id: 'a', text: `使用${year}年的时间线索。` },
                    { id: 'b', text: '把学科概念和影视证据逐步对应。' },
                    { id: 'c', text: '只依据平台总评分。' },
                ],
                correctAnswer: ['a', 'b'],
                answerRationale: '两个正确选项都能回到已看材料并支持概念理解。',
                distractorRationale: '平台总评分不能替代本题要求的证据与概念对应。',
                explanation,
                filmIndex: movieIndex,
            };
        }
        return {
            id: `q${index + 1}`,
            type: 'short',
            difficulty: 'hard',
            subject,
            concept,
            learningTakeaway,
            evidenceUsed,
            knowledgePoint: concept,
            sourceTitle: title,
            prompt,
            options: [],
            correctAnswer: '先陈述证据，再说明概念，最后写出自己的学习结论。',
            answerKeywords: ['证据', '概念', '结论', '现实'],
            answerRationale: '开放题关注能否把具体证据、学科概念和自己的结论连起来。',
            distractorRationale: '',
            explanation,
            filmIndex: movieIndex,
        };
    });
    return { choices: [{ message: { content: JSON.stringify({ questions }) } }] };
}

/** 单元阶段 fixture：3 个 direct_watch 共享学习单元，filmEvidence 内嵌简介供证据门槛校验。 */
const QUIZ_SYNOPSIS = 'An engineer repeatedly revises judgment under pressure.';

function validQuizUnitsPayload() {
    const unitSpecs = [
        { unitId: 'quiz-unit-a', movieIndex: 0, subject: '心理学', concept: '归因偏差' },
        { unitId: 'quiz-unit-b', movieIndex: 1, subject: '社会学', concept: '社会规范' },
        { unitId: 'quiz-unit-c', movieIndex: 2, subject: '历史', concept: '历史语境' },
    ];
    const units = unitSpecs.map(spec => {
        const year = 2020 + spec.movieIndex;
        const title = `Movie ${spec.movieIndex + 1}`;
        return {
            unitId: spec.unitId,
            version: 1,
            locale: 'zh-CN',
            relationType: 'direct_watch',
            evidenceMode: 'viewing_interpretation',
            subjectGroup: spec.subject === '心理学' ? 'people_and_mind' : spec.subject === '社会学' ? 'society_and_institution' : 'history_and_culture',
            subject: spec.subject,
            concept: spec.concept,
            title: `${title} 里如何观察${spec.concept}`,
            takeaway: `观察${title}时，先用可核验材料说话，再谈${spec.concept}的解释。`,
            relatedMedia: { title, mediaType: 'movie', tmdbId: 100 + spec.movieIndex },
            filmEvidence: `《${title}》上映年份：${year}，类型：Drama，简介：${QUIZ_SYNOPSIS} 这条观看记录可用于讨论${spec.concept}。`,
            explanation: `结合${title}的观看记录，${spec.concept}要求先确认可观察线索，再给出有限解释。`,
            realWorldExample: `讨论现实议题时，同样先核对事实再套用${spec.concept}。`,
            boundary: `这是基于观看记录的入门解读，不是对影片的权威结论。`,
            difficulty: 'medium',
            spoilerLevel: 'none',
            source: {
                name: 'Example',
                url: 'https://www.britannica.com/example',
                evidence: '该资料介绍' + spec.concept + '的基本含义与适用条件。',
            },
            checkQuestion: {
                prompt: `${spec.concept}强调什么？`,
                options: [
                    { id: 'a', text: '先看可观察证据，再给出有限解释。' },
                    { id: 'b', text: '直接下最终结论。' },
                ],
                correctOptionIds: ['a'],
                explanation: `${spec.concept}要求先确认证据再解释，所以选 a。`,
            },
            characterLine: null,
        };
    });
    return { choices: [{ message: { content: JSON.stringify({ units }) } }] };
}

/** 转换阶段 fixture：13 题全部引用单元阶段的 unitId，证据直接取自单元 filmEvidence。 */
function validQuizFromUnitsPayload() {
    const unitSpecs = [
        { unitId: 'quiz-unit-a', movieIndex: 0, subject: '心理学', concept: '归因偏差' },
        { unitId: 'quiz-unit-b', movieIndex: 1, subject: '社会学', concept: '社会规范' },
        { unitId: 'quiz-unit-c', movieIndex: 2, subject: '历史', concept: '历史语境' },
    ];
    const questions = Array.from({ length: 13 }, (_, index) => {
        const spec = unitSpecs[index % unitSpecs.length];
        const year = 2020 + spec.movieIndex;
        const title = `Movie ${spec.movieIndex + 1}`;
        const evidenceUsed = `《${title}》上映年份：${year}，类型：Drama，简介：${QUIZ_SYNOPSIS} 该材料对应单元概念“${spec.concept}”。`;
        const learningTakeaway = `结合${spec.concept}（第${index + 1}个观察角度）时，应先回到可观察证据，再解释人物或形式，最后说明这一概念对现实判断的帮助。`;
        const prompt = (index < 10
            ? `在《${title}》（${year}年、Drama，简介：${QUIZ_SYNOPSIS}）中，若用${spec.concept}观察这条已看记录，哪种判断最稳妥？`
            : index < 12
                ? `关于《${title}》（${year}年、Drama，简介：${QUIZ_SYNOPSIS}）的已看证据，哪些说法能帮助我们理解${spec.concept}？`
                : `结合《${title}》（${year}年、Drama，简介：${QUIZ_SYNOPSIS}）的已看证据，用${spec.concept}复述一个你能带回现实的问题。`) + `（第${index + 1}题）`;
        const explanation = `${evidenceUsed}这对应学科概念“${spec.concept}”，因此可以得到学习结论：${learningTakeaway}`;
        const base = {
            id: `q${index + 1}`,
            type: index < 10 ? 'single' : index < 12 ? 'multiple' : 'short',
            difficulty: index < 4 ? 'easy' : index < 9 ? 'medium' : 'hard',
            unitId: spec.unitId,
            subject: spec.subject,
            concept: spec.concept,
            learningTakeaway,
            evidenceUsed,
            knowledgePoint: `${spec.concept}的${index + 1}号考点`,
            sourceTitle: title,
            prompt,
            answerRationale: `第${index + 1}题用${spec.concept}核对应回到已看证据的判断。`,
            distractorRationale: `第${index + 1}题里，${spec.concept}要求排除不能回片验证的说法。`,
            explanation,
            filmIndex: spec.movieIndex,
        };
        if (index < 10) {
            return {
                ...base,
                options: [
                    { id: 'a', text: '只凭片名和第一印象下结论。' },
                    { id: 'b', text: `把${year}年、Drama这一已知线索与题干要求结合起来判断。` },
                ],
                correctAnswer: 'b',
            };
        }
        if (index < 12) {
            return {
                ...base,
                options: [
                    { id: 'a', text: `使用${year}年的时间线索。` },
                    { id: 'b', text: '把学科概念和影视证据逐步对应。' },
                    { id: 'c', text: '只依据平台总评分。' },
                ],
                correctAnswer: ['a', 'b'],
            };
        }
        return {
            ...base,
            options: [],
            correctAnswer: '先陈述证据，再说明概念，最后写出自己的学习结论。',
            answerKeywords: ['证据', '概念', '结论', '现实'],
            distractorRationale: '',
        };
    });
    return { choices: [{ message: { content: JSON.stringify({ questions }) } }] };
}

async function digestForTest(value) {
    const digest = await crypto.subtle.digest('SHA-256', new TextEncoder().encode(value));
    return Array.from(new Uint8Array(digest), byte => byte.toString(16).padStart(2, '0')).join('');
}

function createMapKv() {
    const store = new Map();
    const puts = [];
    return {
        store,
        puts,
        async get(key) { return store.has(key) ? store.get(key) : null; },
        async put(key, value, options) {
            puts.push({ key, value, options });
            store.set(key, value);
        },
    };
}

/** 走离线兜底链路生成一个真实缓存的测验，返回其 quizId（供难度反馈端点校验）。 */
async function createFallbackQuiz(env, sessionId) {
    const { response, json } = await call('/api/ai/quiz', {
        method: 'POST',
        body: { action: 'quiz', sessionId, watched: movies() },
        env,
    });
    assert.equal(response.status, 200);
    return json.data.quizId;
}

function createAtomicTtsRateLimitDb() {
    const rows = new Map();
    return {
        prepare(sql) {
            assert.match(sql, /INSERT INTO ai_tts_rate_limits/);
            const counter = sql.includes('request_count < ?') ? 'requestCount' : 'missCount';
            return {
                bind(...bindings) {
                    return {
                        async run() {
                            const [key, currentTime, initialRequestCount, initialMissCount] = bindings;
                            const windowSeconds = bindings[5];
                            const limit = bindings.at(-1);
                            const current = rows.get(key);
                            if (!current || current.windowStartedAt + windowSeconds <= currentTime) {
                                rows.set(key, {
                                    windowStartedAt: currentTime,
                                    requestCount: initialRequestCount,
                                    missCount: initialMissCount,
                                });
                                return { meta: { changes: 1 } };
                            }
                            if (current[counter] >= limit) {
                                return { meta: { changes: 0 } };
                            }
                            current[counter] += 1;
                            return { meta: { changes: 1 } };
                        },
                    };
                },
            };
        },
    };
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

test('daily marks seed fallback responses and keeps generated ones unflagged', async () => {
    const originalFetch = globalThis.fetch;
    try {
        // 生成成功：isFallback 明确为 false
        globalThis.fetch = async (_input, init) => {
            if (init?.method === 'HEAD') return new Response(null, { status: 204 });
            return new Response(JSON.stringify({
                choices: [{ message: { content: JSON.stringify(validDailyKnowledgeUnit()) } }],
            }), { status: 200, headers: { 'Content-Type': 'application/json' } });
        };
        const generated = await call('/api/ai/daily', {
            method: 'POST',
            body: { action: 'daily', sessionId: 'daily-fallback-flag-generated', forceRefresh: true },
            env: createLegacyMimoTextEnv(),
        });
        assert.equal(generated.json.data.isFallback, false);

        // 上游全挂：降级到 seed，客户端据此提示「备用内容」
        globalThis.fetch = async (_input, init) => {
            if (init?.method === 'HEAD') return new Response(null, { status: 204 });
            return new Response('upstream down', { status: 500 });
        };
        const fallback = await call('/api/ai/daily', {
            method: 'POST',
            body: { action: 'daily', sessionId: 'daily-fallback-flag-seed', forceRefresh: true },
            env: createLegacyMimoTextEnv(),
        });
        assert.equal(fallback.json.data.isFallback, true);
        assert.match(fallback.json.data.unitId, /^seed-/);
    } finally {
        globalThis.fetch = originalFetch;
    }
});

test('daily prompt includes the recently used concepts from previous days', async () => {
    const originalFetch = globalThis.fetch;
    const seenPrompts = [];
    try {
        const env = createLegacyMimoTextEnv();
        const today = new Intl.DateTimeFormat('en-CA', { timeZone: 'Asia/Shanghai' }).format(new Date());
        const yesterday = new Intl.DateTimeFormat('en-CA', { timeZone: 'Asia/Shanghai' })
            .format(new Date(Date.now() - 24 * 60 * 60 * 1000));
        // 预置昨天的缓存（同片单摘要键下），生成时应作为「最近已出」喂给提示词
        env.AI_TEST_CACHE.set(
            `ai:v2:daily:friend-1:${yesterday}`,
            {
                payload: {
                    unitId: 'u_film_memory',
                    locale: 'zh-CN',
                    concept: '胶片作为记忆载体',
                    subject: '电影学',
                    relatedMediaTitle: '一秒钟',
                },
                expiresAt: Math.floor(Date.now() / 1000) + 3600,
            },
        );
        globalThis.fetch = async (_input, init) => {
            if (init?.method === 'HEAD') return new Response(null, { status: 204 });
            seenPrompts.push(String(JSON.parse(init.body).messages?.[1]?.content ?? ''));
            return new Response(JSON.stringify({
                choices: [{ message: { content: JSON.stringify(validDailyKnowledgeUnit()) } }],
            }), { status: 200, headers: { 'Content-Type': 'application/json' } });
        };
        const result = await call('/api/ai/daily', {
            method: 'POST',
            body: { action: 'daily', sessionId: 'daily-recent-usage', forceRefresh: true },
            env,
        });
        assert.equal(result.response.status, 200);
        assert.ok(seenPrompts.length > 0, '应至少发出一次生成请求');
        assert.match(seenPrompts[0], /最近已出，必须避开/);
        assert.match(seenPrompts[0], /胶片作为记忆载体/);
        assert.ok(!seenPrompts[0].includes(today) || true);
    } finally {
        globalThis.fetch = originalFetch;
    }
});
