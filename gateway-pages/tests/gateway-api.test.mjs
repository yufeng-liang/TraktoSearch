import test from 'node:test';
import assert from 'node:assert/strict';
import { onRequest } from '../functions/gateway-api/[[path]].js';

function contextFor(path, init = {}) {
    return {
        request: new Request(`https://tracktosearch-gateway.pages.dev${path}`, init),
    };
}

test('forwards app API requests through the fixed Worker origin', async () => {
    const calls = [];
    const originalFetch = globalThis.fetch;
    globalThis.fetch = async (request) => {
        calls.push(request);
        return new Response(JSON.stringify({ code: 'SUCCESS' }), {
            status: 200,
            headers: { 'Content-Type': 'application/json' },
        });
    };

    try {
        const response = await onRequest(contextFor('/gateway-api/api/auth/activate?x=1', {
            method: 'POST',
            headers: { Authorization: 'Bearer test-token', 'Content-Type': 'application/json' },
            body: '{"inviteCode":"TEST"}',
        }));

        assert.equal(response.status, 200);
        assert.equal(calls.length, 1);
        assert.equal(calls[0].url, 'https://auth-worker.douban-movie-api-peak.workers.dev/api/auth/activate?x=1');
        assert.equal(calls[0].method, 'POST');
        assert.equal(calls[0].headers.get('Authorization'), 'Bearer test-token');
        assert.equal(await calls[0].text(), '{"inviteCode":"TEST"}');
    } finally {
        globalThis.fetch = originalFetch;
    }
});

test('does not expose non-app Worker routes', async () => {
    const response = await onRequest(contextFor('/gateway-api/admin/friends'));
    assert.equal(response.status, 404);
});
