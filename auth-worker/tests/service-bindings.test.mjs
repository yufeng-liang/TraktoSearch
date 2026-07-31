import test from 'node:test';
import assert from 'node:assert/strict';
import { handleDoubanProxy } from '../src/proxy/douban.ts';
import { handleCrashLogProxy } from '../src/proxy/crash-logs.ts';

function kvMock() {
    const values = new Map();
    return {
        values,
        get: async key => values.get(key) ?? null,
        put: async (key, value) => { values.set(key, value); },
    };
}

test('豆瓣代理通过 Service Binding 调用 douban Worker', async () => {
    const kv = kvMock();
    const calls = [];
    const originalFetch = globalThis.fetch;
    globalThis.fetch = async () => { throw new Error('public Worker fetch should not be used'); };

    try {
        const response = await handleDoubanProxy(
            new Request('https://gateway.internal/api/douban/subject/123?lang=zh-CN', { method: 'GET' }),
            {
                KV: kv,
                DOUBAN_API_KEY: 'douban-test-key',
                DOUBAN_WORKER: {
                    fetch: async request => {
                        calls.push(request);
                        return new Response('{"ok":true}', {
                            status: 200,
                            headers: { 'Content-Type': 'application/json' },
                        });
                    },
                },
            },
            '/api/douban/subject/123',
        );

        assert.equal(response.status, 200);
        assert.equal(calls.length, 1);
        assert.equal(calls[0].url, 'https://douban-movie-api.internal/subject/123?lang=zh-CN');
        assert.equal(calls[0].headers.get('X-API-Key'), 'douban-test-key');
    } finally {
        globalThis.fetch = originalFetch;
    }
});

test('崩溃日志由 auth-worker 直接写入共享 KV', async () => {
    const kv = kvMock();
    const response = await handleCrashLogProxy(
        new Request('https://gateway.internal/api/crash-logs', {
            method: 'POST',
            body: JSON.stringify({ stackTrace: 'Error: test', appVersion: '1.0.0' }),
            headers: { 'Content-Type': 'application/json' },
        }),
        { CRASH_LOGS: kv },
    );

    assert.equal(response.status, 201);
    const result = await response.json();
    assert.equal(result.ok, true);
    assert.equal(kv.values.size, 1);
    const entry = JSON.parse([...kv.values.values()][0]);
    assert.equal(entry.stackTrace, 'Error: test');
    assert.equal(entry.appVersion, '1.0.0');
});
