import test from 'node:test';
import assert from 'node:assert/strict';
import { onRequest } from '../functions/admin-api/[[path]].js';

function contextFor(path, init = {}) {
    return { request: new Request(`https://app-config-1qe.pages.dev${path}`, init) };
}

test('管理代理把 Access Cookie 转成 Bearer 并移除浏览器元数据', async () => {
    const originalFetch = globalThis.fetch;
    let upstreamRequest;
    globalThis.fetch = async request => {
        upstreamRequest = request;
        return new Response(JSON.stringify({ data: { ok: true } }), {
            status: 200,
            headers: { 'Content-Type': 'application/json' },
        });
    };

    try {
        const response = await onRequest(contextFor('/admin-api/admin/health', {
            headers: { Cookie: 'CF_Authorization=test-token' },
        }));
        assert.equal(response.status, 200);
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
    globalThis.fetch = async () => { throw new Error('offline'); };

    try {
        const response = await onRequest(contextFor('/admin-api/admin/health'));
        assert.equal(response.status, 502);
        assert.deepEqual(await response.json(), {
            code: 'UPSTREAM_UNAVAILABLE',
            message: 'Admin service unavailable',
        });
    } finally {
        globalThis.fetch = originalFetch;
    }
});
