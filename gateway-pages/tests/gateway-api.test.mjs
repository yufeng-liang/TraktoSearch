import test from 'node:test';
import assert from 'node:assert/strict';
import { onRequest } from '../functions/gateway-api/[[path]].js';

function contextFor(path, init = {}, env = {}) {
    return {
        request: new Request(`https://tracktosearch-gateway.pages.dev${path}`, init),
        env,
    };
}

function serviceBinding(calls, responseBody = { code: 'SUCCESS' }) {
    return {
        fetch: async (request) => {
            calls.push(request);
            return new Response(JSON.stringify(responseBody), {
                status: 200,
                headers: { 'Content-Type': 'application/json' },
            });
        },
    };
}

test('forwards app API requests through the auth Worker service binding', async () => {
    const calls = [];
    const originalFetch = globalThis.fetch;
    globalThis.fetch = async () => {
        throw new Error('public Worker fetch should not be used');
    };

    try {
        const response = await onRequest(contextFor('/gateway-api/api/auth/activate?x=1', {
            method: 'POST',
            headers: { Authorization: 'Bearer test-token', 'Content-Type': 'application/json' },
            body: '{"inviteCode":"TEST"}',
        }, {
            AUTH_WORKER: serviceBinding(calls),
        }));

        assert.equal(response.status, 200);
        assert.equal(calls.length, 1);
        assert.equal(calls[0].url, 'https://gateway.internal/api/auth/activate?x=1');
        assert.equal(calls[0].method, 'POST');
        assert.equal(calls[0].headers.get('Authorization'), 'Bearer test-token');
        assert.equal(calls[0].headers.get('Origin'), 'https://tracktosearch-gateway.pages.dev');
        assert.equal(await calls[0].text(), '{"inviteCode":"TEST"}');
    } finally {
        globalThis.fetch = originalFetch;
    }
});

test('does not expose non-app Worker routes', async () => {
    const response = await onRequest(contextFor('/gateway-api/admin/friends'));
    assert.equal(response.status, 404);
});

test('forwards feedback API requests through the feedback Worker service binding', async () => {
    const calls = [];
    const originalFetch = globalThis.fetch;
    globalThis.fetch = async () => {
        throw new Error('public Worker fetch should not be used');
    };

    try {
        const response = await onRequest(contextFor('/gateway-api/feedback-api/mine?limit=20', {
            method: 'GET',
            headers: { Authorization: 'Bearer test-token' },
        }, {
            FEEDBACK_WORKER: serviceBinding(calls),
        }));

        assert.equal(response.status, 200);
        assert.equal(calls.length, 1);
        assert.equal(calls[0].url, 'https://gateway.internal/feedback-api/mine?limit=20');
    } finally {
        globalThis.fetch = originalFetch;
    }
});

test('caches anonymous public API responses at the gateway edge', async () => {
    const calls = [];
    const entries = new Map();
    const originalCaches = globalThis.caches;
    globalThis.caches = {
        default: {
            async match(request) {
                return entries.get(request.url);
            },
            async put(request, response) {
                entries.set(request.url, response);
            },
        },
    };

    try {
        const env = { AUTH_WORKER: serviceBinding(calls, { data: { title: 'cached' } }) };
        const first = await onRequest(contextFor('/gateway-api/api/tmdb/movie/1', {}, env));
        const second = await onRequest(contextFor('/gateway-api/api/tmdb/movie/1', {}, env));

        assert.equal(first.status, 200);
        assert.equal(first.headers.get('X-Gateway-Cache'), 'MISS');
        assert.equal(second.status, 200);
        assert.equal(second.headers.get('X-Gateway-Cache'), 'HIT');
        assert.equal(calls.length, 1);
        assert.deepEqual(await second.json(), { data: { title: 'cached' } });
    } finally {
        globalThis.caches = originalCaches;
    }
});

test('caches public read-only endpoints even when the app sends Authorization', async () => {
    const calls = [];
    const entries = new Map();
    const originalCaches = globalThis.caches;
    globalThis.caches = {
        default: {
            async match(request) {
                return entries.get(request.url);
            },
            async put(request, response) {
                entries.set(request.url, response);
            },
        },
    };

    try {
        const env = { AUTH_WORKER: serviceBinding(calls, { data: { title: 'cached' } }) };
        const init = { headers: { Authorization: 'Bearer user-token' } };
        const first = await onRequest(contextFor('/gateway-api/api/tmdb/movie/1', init, env));
        const second = await onRequest(contextFor('/gateway-api/api/tmdb/movie/1', init, env));

        assert.equal(first.headers.get('X-Gateway-Cache'), 'MISS');
        assert.equal(second.headers.get('X-Gateway-Cache'), 'HIT');
        // 客户端响应保持不缓存（App 有自建缓存层）
        assert.equal(first.headers.get('Cache-Control'), 'public, max-age=0');
        // 边缘缓存条目必须带 TTL，否则 put() 会被 Cache API 拒绝（413）
        const cachedEntry = entries.get('https://tracktosearch-gateway.pages.dev/gateway-api/api/tmdb/movie/1');
        assert.equal(cachedEntry.headers.get('Cache-Control'), 'public, max-age=600');
        assert.equal(calls.length, 1);
        assert.equal(entries.size, 1);
    } finally {
        globalThis.caches = originalCaches;
    }
});

test('never caches endpoints that carry per-user data', async () => {
    const calls = [];
    const entries = new Map();
    const originalCaches = globalThis.caches;
    globalThis.caches = {
        default: {
            async match(request) {
                return entries.get(request.url);
            },
            async put(request, response) {
                entries.set(request.url, response);
            },
        },
    };

    try {
        const env = { AUTH_WORKER: serviceBinding(calls, { data: { private: true } }) };
        const privatePaths = [
            '/gateway-api/api/trakt/sync/watchlist/movies',
            '/gateway-api/api/trakt/users/me',
            '/gateway-api/api/trakt/recommendations/movies',
            '/gateway-api/api/trakt/shows/1/progress/watched',
            '/gateway-api/api/trakt/oauth/authorize',
            // 查询串里的 session_id / guest_session_id 代表个人账号态数据
            '/gateway-api/api/tmdb/account/favorite/movies?session_id=abc',
            '/gateway-api/api/tmdb/guest_session/xyz/rated/movies?guest_session_id=def',
        ];

        for (const path of privatePaths) {
            const init = { headers: { Authorization: 'Bearer user-token' } };
            const first = await onRequest(contextFor(path, init, env));
            const second = await onRequest(contextFor(path, init, env));
            assert.equal(first.headers.get('X-Gateway-Cache'), 'BYPASS', `${path} 不应命中缓存`);
            assert.equal(second.headers.get('X-Gateway-Cache'), 'BYPASS', `${path} 不应命中缓存`);
            assert.equal(first.headers.get('Cache-Control'), 'no-store', `${path} 不应可缓存`);
        }

        assert.equal(calls.length, privatePaths.length * 2);
        assert.equal(entries.size, 0);
    } finally {
        globalThis.caches = originalCaches;
    }
});

test('does not share cache between different query parameters', async () => {
    const calls = [];
    const entries = new Map();
    const originalCaches = globalThis.caches;
    globalThis.caches = {
        default: {
            async match(request) {
                return entries.get(request.url);
            },
            async put(request, response) {
                entries.set(request.url, response);
            },
        },
    };

    try {
        const env = { AUTH_WORKER: serviceBinding(calls, { data: { ok: true } }) };
        await onRequest(contextFor('/gateway-api/api/tmdb/search/movie?query=a&language=zh-CN', {}, env));
        await onRequest(contextFor('/gateway-api/api/tmdb/search/movie?query=b&language=zh-CN', {}, env));

        assert.equal(calls.length, 2);
        assert.equal(entries.size, 2);
    } finally {
        globalThis.caches = originalCaches;
    }
});

test('returns a clear unavailable response when the selected service binding is missing', async () => {
    const response = await onRequest(contextFor('/gateway-api/api/auth/check'));

    assert.equal(response.status, 503);
    assert.deepEqual(await response.json(), {
        code: 'UPSTREAM_BINDING_UNAVAILABLE',
        message: 'Gateway service unavailable',
    });
});

test('proxies TMDB images through the gateway with long edge cache', async () => {
    const upstreamCalls = [];
    const entries = new Map();
    const originalFetch = globalThis.fetch;
    const originalCaches = globalThis.caches;
    globalThis.fetch = async (url) => {
        upstreamCalls.push(url);
        return new Response(Buffer.from([0xff, 0xd8, 0xff, 0xe0]), {
            status: 200,
            headers: { 'Content-Type': 'image/jpeg', 'Content-Length': '4' },
        });
    };
    globalThis.caches = {
        default: {
            async match(request) { return entries.get(request.url); },
            async put(request, response) { entries.set(request.url, response); },
        },
    };

    try {
        const path = '/gateway-api/api/tmdb-image/t/p/w342/abc123.jpg';
        const first = await onRequest(contextFor(path));
        const second = await onRequest(contextFor(path));

        assert.equal(first.status, 200);
        assert.equal(first.headers.get('X-Gateway-Cache'), 'MISS');
        assert.match(first.headers.get('Cache-Control'), /s-maxage=2592000/);
        assert.equal(first.headers.get('Content-Type'), 'image/jpeg');
        assert.equal(second.headers.get('X-Gateway-Cache'), 'HIT');
        assert.equal(upstreamCalls.length, 1);
        assert.equal(upstreamCalls[0], 'https://image.tmdb.org/t/p/w342/abc123.jpg');
    } finally {
        globalThis.fetch = originalFetch;
        globalThis.caches = originalCaches;
    }
});

test('rejects non-whitelisted TMDB image sizes', async () => {
    const response = await onRequest(contextFor('/gateway-api/api/tmdb-image/t/p/w999/abc.jpg'));
    assert.equal(response.status, 400);
});

test('rejects invalid TMDB image paths (no proxy abuse)', async () => {
    const originalFetch = globalThis.fetch;
    globalThis.fetch = async () => { throw new Error('must not fetch'); };
    try {
        const r1 = await onRequest(contextFor('/gateway-api/api/tmdb-image/t/p'));
        const r2 = await onRequest(contextFor('/gateway-api/api/tmdb-image/t/p/w342/../secret.jpg'));
        const r3 = await onRequest(contextFor('/gateway-api/api/tmdb-image/t/p/w342/x.html'));
        assert.equal(r1.status, 400);
        assert.equal(r2.status, 400);
        assert.equal(r3.status, 400);
    } finally {
        globalThis.fetch = originalFetch;
    }
});

test('rejects non-GET image proxy requests', async () => {
    const response = await onRequest(contextFor('/gateway-api/api/tmdb-image/t/p/w342/a.jpg', { method: 'POST' }));
    assert.equal(response.status, 405);
});
