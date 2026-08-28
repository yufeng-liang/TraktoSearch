import test from 'node:test';
import assert from 'node:assert/strict';
import crypto from 'node:crypto';

import { onRequestPost, onRequestGet as listGet } from '../functions/api/crash-logs.js';
import { onRequestGet as detailGet, onRequestPatch as detailPatch } from '../functions/api/crash-logs/[id].js';
import { onRequest as middlewareOnRequest } from '../functions/api/_middleware.js';

// ===== KV mock =====
function kvMock(initial = {}) {
    const map = new Map(Object.entries(initial));
    return {
        _map: map,
        async put(key, value) { map.set(key, value); },
        async get(key, { type } = {}) {
            const value = map.get(key);
            if (value === undefined) return null;
            return type === 'json' ? JSON.parse(value) : value;
        },
        async list({ prefix = '', limit = 1000, cursor } = {}) {
            const keys = [...map.keys()].filter(k => k.startsWith(prefix)).sort();
            const start = cursor ? Number(cursor) : 0;
            const page = keys.slice(start, start + limit);
            return {
                keys: page.map(name => ({ name })),
                cursor: start + page.length < keys.length ? String(start + page.length) : undefined,
            };
        },
    };
}

function entry(id, overrides = {}) {
    return JSON.stringify({
        id,
        timestamp: '2026-08-27T10:00:00.000Z',
        appVersion: '2.36.0',
        androidVersion: '14',
        device: 'Pixel 8',
        currentPage: 'Home',
        recentActions: 'open app',
        stackTrace: 'java.lang.RuntimeException: boom\n    at com.app.Main.onCreate',
        status: 'open',
        ...overrides,
    });
}

function contextFor(path, init = {}, env = {}) {
    const segments = path.split('/').filter(Boolean);
    return {
        request: new Request(`https://app-config-1qe.pages.dev${path}`, init),
        env,
        params: { id: segments[segments.length - 1] },
    };
}

// ===== RSA key pair for Access JWT tests =====
const { privateKey, publicKey } = crypto.generateKeyPairSync('rsa', { modulusLength: 2048 });
const publicJwk = publicKey.export({ format: 'jwk' });
const KID = 'test-kid';

function b64url(input) {
    return Buffer.from(input).toString('base64url');
}

function signJwt(payload = {}, kid = KID, key = privateKey) {
    const header = b64url(JSON.stringify({ alg: 'RS256', kid }));
    const body = b64url(JSON.stringify({
        exp: Math.floor(Date.now() / 1000) + 3600,
        iss: 'https://test-team.cloudflareaccess.com',
        ...payload,
    }));
    const signer = crypto.createSign('sha256');
    signer.update(`${header}.${body}`);
    const signature = b64url(signer.sign(key));
    return `${header}.${body}.${signature}`;
}

/** 覆盖 globalThis.fetch 返回指定 JWKS；返回恢复函数 */
function mockJwksFetch(keys) {
    const original = globalThis.fetch;
    globalThis.fetch = async () => new Response(JSON.stringify({ keys }), {
        status: 200,
        headers: { 'Content-Type': 'application/json' },
    });
    return () => { globalThis.fetch = original; };
}

function middlewareContext(path, init, env = {}) {
    return {
        request: new Request(`https://app-config-1qe.pages.dev${path}`, init),
        env,
        next: async () => new Response('ok', { status: 200 }),
    };
}

// ===== POST =====
test('POST 创建崩溃日志写入 KV 并返回 201', async () => {
    const kv = kvMock();
    const response = await onRequestPost(contextFor('/api/crash-logs', {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ stackTrace: 'boom', device: 'Pixel 8' }),
    }, { CRASH_LOGS: kv }));

    assert.equal(response.status, 201);
    const data = await response.json();
    assert.equal(data.ok, true);
    assert.match(data.id, /^crash_/);
    const stored = JSON.parse(kv._map.get(data.id));
    assert.equal(stored.status, 'open');
    assert.equal(stored.device, 'Pixel 8');
});

test('POST 缺少 stackTrace 返回 400', async () => {
    const kv = kvMock();
    const response = await onRequestPost(contextFor('/api/crash-logs', {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ device: 'Pixel 8' }),
    }, { CRASH_LOGS: kv }));
    assert.equal(response.status, 400);
});

test('POST stackTrace 超长返回 413', async () => {
    const kv = kvMock();
    const response = await onRequestPost(contextFor('/api/crash-logs', {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ stackTrace: 'x'.repeat(500 * 1024 + 1) }),
    }, { CRASH_LOGS: kv }));
    assert.equal(response.status, 413);
});

test('POST recentActions 超长返回 413', async () => {
    const kv = kvMock();
    const response = await onRequestPost(contextFor('/api/crash-logs', {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ stackTrace: 'boom', recentActions: 'y'.repeat(100 * 1024 + 1) }),
    }, { CRASH_LOGS: kv }));
    assert.equal(response.status, 413);
});

// ===== GET list =====
test('GET 列表分页返回 entries/total/hasMore/stats', async () => {
    const kv = kvMock({
        crash_1: entry('crash_1', { timestamp: '2026-08-27T10:00:00.000Z' }),
        crash_2: entry('crash_2', { timestamp: '2026-08-26T10:00:00.000Z', status: 'fixed' }),
        crash_3: entry('crash_3', { timestamp: '2026-07-01T10:00:00.000Z', device: '' }),
    });
    const response = await listGet(contextFor('/api/crash-logs?limit=2&offset=0', {}, { CRASH_LOGS: kv }));

    assert.equal(response.status, 200);
    const data = await response.json();
    assert.equal(data.entries.length, 2);
    assert.equal(data.total, 3);
    assert.equal(data.hasMore, true);
    // 时间倒序：最新在前
    assert.equal(data.entries[0].id, 'crash_1');
    assert.equal(data.entries[1].id, 'crash_2');
    // 统计基于全量
    assert.equal(data.stats.total, 3);
    assert.equal(data.stats.open, 2);
    assert.equal(data.stats.devices, 1);
    assert.equal(data.stats.last7d, 2);
    // 列表项含堆栈预览前三行
    assert.match(data.entries[0].stackTracePreview, /java\.lang\.RuntimeException: boom/);
});

test('GET 列表状态与关键词筛选', async () => {
    const kv = kvMock({
        crash_1: entry('crash_1', { stackTrace: 'NullPointerException at Login', status: 'open' }),
        crash_2: entry('crash_2', { stackTrace: 'IllegalStateException at Home', status: 'fixed' }),
    });

    const openResp = await listGet(contextFor('/api/crash-logs?status=open', {}, { CRASH_LOGS: kv }));
    const openData = await openResp.json();
    assert.equal(openData.total, 1);
    assert.equal(openData.entries[0].id, 'crash_1');

    const searchResp = await listGet(contextFor('/api/crash-logs?search=login', {}, { CRASH_LOGS: kv }));
    const searchData = await searchResp.json();
    assert.equal(searchData.total, 1);
    assert.equal(searchData.entries[0].id, 'crash_1');
    // 搜索不影响统计（全量口径）
    assert.equal(searchData.stats.total, 2);
});

test('GET 列表未来时间戳不计入 last7d', async () => {
    const kv = kvMock({
        crash_future: entry('crash_future', { timestamp: new Date(Date.now() + 86400000).toISOString() }),
        crash_now: entry('crash_now', { timestamp: new Date().toISOString() }),
    });
    const response = await listGet(contextFor('/api/crash-logs', {}, { CRASH_LOGS: kv }));
    const data = await response.json();
    assert.equal(data.stats.total, 2);
    assert.equal(data.stats.last7d, 1);
});

test('GET 列表超过 1000 条时用 cursor 翻完全量', async () => {
    const kv = kvMock({});
    for (let i = 0; i < 1050; i++) {
        kv._map.set(`crash_${String(i).padStart(4, '0')}`, entry(`crash_${i}`));
    }
    const response = await listGet(contextFor('/api/crash-logs?limit=50', {}, { CRASH_LOGS: kv }));
    const data = await response.json();
    assert.equal(data.stats.total, 1050);
    assert.equal(data.total, 1050);
});

// ===== detail =====
test('GET 详情支持 crash_ 前缀与裸 id 兼容', async () => {
    const kv = kvMock({
        crash_abc: entry('crash_abc'),
        legacy_old: entry('legacy_old'), // 旧数据裸 id 键
    });

    const prefixed = await detailGet(contextFor('/api/crash-logs/crash_abc', {}, { CRASH_LOGS: kv }));
    assert.equal(prefixed.status, 200);
    assert.equal((await prefixed.json()).id, 'crash_abc');

    const legacy = await detailGet(contextFor('/api/crash-logs/legacy_old', {}, { CRASH_LOGS: kv }));
    assert.equal(legacy.status, 200);
    assert.equal((await legacy.json()).id, 'legacy_old');

    const missing = await detailGet(contextFor('/api/crash-logs/nope', {}, { CRASH_LOGS: kv }));
    assert.equal(missing.status, 404);
});

test('PATCH 更新状态写回实际命中的键（裸 id 旧数据不写错位置）', async () => {
    const kv = kvMock({
        legacy_old: entry('legacy_old', { status: 'open' }),
    });

    const response = await detailPatch(contextFor('/api/crash-logs/legacy_old', {
        method: 'PATCH',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ status: 'fixed' }),
    }, { CRASH_LOGS: kv }));

    assert.equal(response.status, 200);
    assert.equal((await response.json()).status, 'fixed');
    // 必须写回 legacy_old 键，而非 crash_legacy_old
    assert.equal(JSON.parse(kv._map.get('legacy_old')).status, 'fixed');
    assert.equal(kv._map.has('crash_legacy_old'), false);
});

test('PATCH 非法 status 返回 400，不存在记录返回 404', async () => {
    const kv = kvMock({ crash_abc: entry('crash_abc') });

    const bad = await detailPatch(contextFor('/api/crash-logs/crash_abc', {
        method: 'PATCH',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ status: 'bogus' }),
    }, { CRASH_LOGS: kv }));
    assert.equal(bad.status, 400);

    const missing = await detailPatch(contextFor('/api/crash-logs/nope', {
        method: 'PATCH',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ status: 'fixed' }),
    }, { CRASH_LOGS: kv }));
    assert.equal(missing.status, 404);
});

// ===== middleware =====
test('中间件：无凭据 GET 返回 401', async () => {
    const restore = mockJwksFetch([{ kid: KID, kty: 'RSA', n: publicJwk.n, e: publicJwk.e }]);
    try {
        const response = await middlewareOnRequest(middlewareContext('/api/crash-logs', {}, {}));
        assert.equal(response.status, 401);
    } finally {
        restore();
    }
});

test('中间件：伪造 JWT（错误签名）返回 401', async () => {
    const restore = mockJwksFetch([{ kid: KID, kty: 'RSA', n: publicJwk.n, e: publicJwk.e }]);
    try {
        const fakeKey = crypto.generateKeyPairSync('rsa', { modulusLength: 2048 }).privateKey;
        const fake = signJwt({}, KID, fakeKey);
        const response = await middlewareOnRequest(middlewareContext('/api/crash-logs', {
            headers: { 'Cf-Access-Jwt-Assertion': fake },
        }, {}));
        assert.equal(response.status, 401);
    } finally {
        restore();
    }
});

test('中间件：有效 Access JWT（头注入）放行 GET', async () => {
    const restore = mockJwksFetch([{ kid: KID, kty: 'RSA', n: publicJwk.n, e: publicJwk.e }]);
    try {
        const token = signJwt();
        const response = await middlewareOnRequest(middlewareContext('/api/crash-logs', {
            headers: { 'Cf-Access-Jwt-Assertion': token },
        }, {}));
        assert.equal(response.status, 200);
    } finally {
        restore();
    }
});

test('中间件：有效 Access JWT（cookie 兜底）放行 PATCH', async () => {
    const restore = mockJwksFetch([{ kid: KID, kty: 'RSA', n: publicJwk.n, e: publicJwk.e }]);
    try {
        const token = signJwt();
        const response = await middlewareOnRequest(middlewareContext('/api/crash-logs/crash_1', {
            method: 'PATCH',
            headers: { Cookie: `CF_Authorization=${encodeURIComponent(token)}` },
        }, {}));
        assert.equal(response.status, 200);
    } finally {
        restore();
    }
});

test('中间件：过期 JWT 返回 401', async () => {
    const restore = mockJwksFetch([{ kid: KID, kty: 'RSA', n: publicJwk.n, e: publicJwk.e }]);
    try {
        const expired = signJwt({ exp: Math.floor(Date.now() / 1000) - 60 });
        const response = await middlewareOnRequest(middlewareContext('/api/crash-logs', {
            headers: { 'Cf-Access-Jwt-Assertion': expired },
        }, {}));
        assert.equal(response.status, 401);
    } finally {
        restore();
    }
});

test('中间件：POST 上报仍校验 CRASH_LOG_TOKEN', async () => {
    const response = await middlewareOnRequest(middlewareContext('/api/crash-logs', {
        method: 'POST',
        headers: { Authorization: 'Bearer correct-token' },
    }, { CRASH_LOG_TOKEN: 'correct-token' }));
    assert.equal(response.status, 200);

    const rejected = await middlewareOnRequest(middlewareContext('/api/crash-logs', {
        method: 'POST',
        headers: { Authorization: 'Bearer wrong-token' },
    }, { CRASH_LOG_TOKEN: 'correct-token' }));
    assert.equal(rejected.status, 401);
});

test('中间件：非 crash-logs 前缀路径不被 Access 分支误伤', async () => {
    const restore = mockJwksFetch([{ kid: KID, kty: 'RSA', n: publicJwk.n, e: publicJwk.e }]);
    try {
        // /api/crash-logs-other 不是崩溃日志路径，无 token 应 401
        const response = await middlewareOnRequest(middlewareContext('/api/crash-logs-other', {}, {}));
        assert.equal(response.status, 401);
    } finally {
        restore();
    }
});
