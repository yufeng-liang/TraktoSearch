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

test('returns a clear unavailable response when the selected service binding is missing', async () => {
    const response = await onRequest(contextFor('/gateway-api/api/auth/check'));

    assert.equal(response.status, 503);
    assert.deepEqual(await response.json(), {
        code: 'UPSTREAM_BINDING_UNAVAILABLE',
        message: 'Gateway service unavailable',
    });
});
