import test from 'node:test';
import assert from 'node:assert/strict';
import {
    handlePublicUpdateReleaseProxy,
    isPublicUpdateReleasePath,
} from '../src/proxy/update.ts';

test('public update proxy only accepts the fixed release path', () => {
    assert.equal(
        isPublicUpdateReleasePath('/api/gitee/repos/yufeng-liang/TrackToSearch-release/releases'),
        true,
    );
    assert.equal(
        isPublicUpdateReleasePath('/api/gitee/repos/yufeng-liang/TrackToSearch/contents/private.json'),
        false,
    );
});

test('public update proxy fetches the fixed release repository without a JWT', async () => {
    const calls = [];
    const originalFetch = globalThis.fetch;
    globalThis.fetch = async (request, init) => {
        calls.push({ request, init });
        return new Response('[{"tag_name":"v3.6.0"}]', {
            status: 200,
            headers: { 'Content-Type': 'application/json' },
        });
    };

    try {
        const response = await handlePublicUpdateReleaseProxy(
            new Request(
                'https://gateway.internal/api/gitee/repos/yufeng-liang/TrackToSearch-release/releases?per_page=1&direction=desc',
            ),
            { GITEE_ACCESS_TOKEN: 'server-secret' },
            '/api/gitee/repos/yufeng-liang/TrackToSearch-release/releases',
        );

        assert.equal(response.status, 200);
        assert.equal(calls.length, 1);
        assert.equal(
            calls[0].request,
            'https://gitee.com/api/v5/repos/yufeng-liang/TrackToSearch-release/releases?per_page=1&direction=desc',
        );
        assert.equal(calls[0].init.headers.get('Authorization'), 'token server-secret');
        assert.deepEqual(await response.json(), [{ tag_name: 'v3.6.0' }]);
    } finally {
        globalThis.fetch = originalFetch;
    }
});
