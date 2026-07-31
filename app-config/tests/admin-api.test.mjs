import test from 'node:test';
import assert from 'node:assert/strict';
import { onRequest } from '../functions/admin-api/[[path]].js';

function contextFor(path, init = {}, env = {}) {
    return {
        request: new Request(`https://app-config-1qe.pages.dev${path}`, init),
        env,
    };
}

function serviceBinding(calls, responseBody = { data: { ok: true } }) {
    return {
        fetch: async request => {
            calls.push(request);
            return new Response(JSON.stringify(responseBody), {
                status: 200,
                headers: { 'Content-Type': 'application/json' },
            });
        },
    };
}

test('管理代理通过 auth Worker Service Binding 转发并清理浏览器元数据', async () => {
    const originalFetch = globalThis.fetch;
    const calls = [];
    let upstreamRequest;
    globalThis.fetch = async () => { throw new Error('public Worker fetch should not be used'); };

    try {
        const response = await onRequest(contextFor('/admin-api/admin/health', {
            headers: { Cookie: 'CF_Authorization=test-token' },
        }, { AUTH_WORKER: serviceBinding(calls) }));
        upstreamRequest = calls[0];
        assert.equal(response.status, 200);
        assert.equal(calls.length, 1);
        assert.equal(upstreamRequest.url, 'https://app-config.internal/admin/health');
        assert.equal(upstreamRequest.headers.get('Authorization'), 'Bearer test-token');
        assert.equal(upstreamRequest.headers.get('Cookie'), null);
        assert.equal(upstreamRequest.headers.get('Host'), null);
        assert.equal(upstreamRequest.headers.get('Content-Length'), null);
    } finally {
        globalThis.fetch = originalFetch;
    }
});

test('管理服务不可用时返回可识别的 502 JSON', async () => {
    const originalFetch = globalThis.fetch;
    try {
        const response = await onRequest(contextFor('/admin-api/admin/health', {}, {
            AUTH_WORKER: { fetch: async () => { throw new Error('offline'); } },
        }));
        assert.equal(response.status, 502);
        assert.deepEqual(await response.json(), {
            code: 'UPSTREAM_UNAVAILABLE',
            message: 'Admin service unavailable',
        });
    } finally {
        globalThis.fetch = originalFetch;
    }
});

test('管理代理按 /fb 前缀通过 feedback Worker Service Binding 转发', async () => {
    const calls = [];
    const response = await onRequest(contextFor('/admin-api/fb/feedback-api/mine?limit=20', {}, {
        FEEDBACK_WORKER: serviceBinding(calls),
    }));

    assert.equal(response.status, 200);
    assert.equal(calls.length, 1);
    assert.equal(calls[0].url, 'https://app-config.internal/feedback-api/mine?limit=20');
});

test('管理代理缺少 Service Binding 时返回 503 JSON', async () => {
    const response = await onRequest(contextFor('/admin-api/admin/health'));
    assert.equal(response.status, 503);
    assert.deepEqual(await response.json(), {
        code: 'UPSTREAM_BINDING_UNAVAILABLE',
        message: 'Admin service unavailable',
    });
});
