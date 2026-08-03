import assert from 'node:assert/strict';
import { test } from 'node:test';
import { handleTranslateProxy } from '../src/proxy/translate.ts';

type StoredValues = Map<string, string>;

function createEnv(values: StoredValues) {
    return {
        KV: {
            get: async (key: string) => values.get(key) ?? null,
            put: async (key: string, value: string) => {
                values.set(key, value);
            },
        },
        BAIDU_API_KEY: 'test-api-key',
        BAIDU_SECRET_KEY: 'test-secret-key',
        BAIDU_APP_ID: 'test-app-id',
    } as any;
}

function translationRequest(text: string): Request {
    return new Request('https://worker.test/api/translate/ai', {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ q: text, from: 'en', to: 'zh', reference: 'movie review' }),
    });
}

test('translation proxy returns a normalized translation and reuses the KV result', async () => {
    const values = new Map<string, string>();
    const env = createEnv(values);
    const waitUntilPromises: Promise<unknown>[] = [];
    const ctx = {
        waitUntil(promise: Promise<unknown>) {
            waitUntilPromises.push(promise);
        },
    } as any;
    const originalFetch = globalThis.fetch;
    let upstreamCalls = 0;

    globalThis.fetch = async () => {
        upstreamCalls += 1;
        return new Response(JSON.stringify({
            from: 'en',
            to: 'zh',
            trans_result: [{ src: 'hello', dst: '你好' }],
        }), {
            status: 200,
            headers: { 'Content-Type': 'application/json' },
        });
    };

    try {
        const firstResponse = await handleTranslateProxy(
            translationRequest('hello'),
            env,
            '/api/translate/ai',
            ctx,
        );
        assert.equal((await firstResponse.json() as { translation?: string }).translation, '你好');
        await Promise.all(waitUntilPromises);

        const secondResponse = await handleTranslateProxy(
            translationRequest('hello'),
            env,
            '/api/translate/ai',
            ctx,
        );
        assert.equal((await secondResponse.json() as { translation?: string }).translation, '你好');
        assert.equal(upstreamCalls, 1);
    } finally {
        globalThis.fetch = originalFetch;
    }
});
