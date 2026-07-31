import test from 'node:test';
import assert from 'node:assert/strict';
import { onRequest } from '../functions/admin-api/[[path]].js';

test('same-origin admin screenshot proxy preserves a private long cache', async () => {
    const calls = [];
    const response = await onRequest({
        request: new Request('https://app-config-1qe.pages.dev/admin-api/fb/feedback-api/screenshot/feedback%2Fone.png'),
        env: {
            FEEDBACK_WORKER: {
                fetch: async request => {
                    calls.push(request);
                    return new Response('image', {
                        status: 200,
                        headers: { 'Content-Type': 'image/png' },
                    });
                },
            },
        },
    });

    assert.equal(response.status, 200);
    assert.equal(calls.length, 1);
    assert.equal(calls[0].url, 'https://app-config.internal/feedback-api/screenshot/feedback%2Fone.png');
    assert.equal(response.headers.get('Cache-Control'), 'private, max-age=2592000');
});
