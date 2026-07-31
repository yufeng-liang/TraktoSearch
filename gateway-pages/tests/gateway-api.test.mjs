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

test('never caches requests carrying user credentials', async () => {
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
        const init = { headers: { Authorization: 'Bearer user-token' } };
        const first = await onRequest(contextFor('/gateway-api/api/tmdb/movie/1', init, env));
        const second = await onRequest(contextFor('/gateway-api/api/tmdb/movie/1', init, env));

        assert.equal(first.headers.get('X-Gateway-Cache'), 'BYPASS');
        assert.equal(second.headers.get('X-Gateway-Cache'), 'BYPASS');
        assert.equal(calls.length, 2);
        assert.equal(entries.size, 0);
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
