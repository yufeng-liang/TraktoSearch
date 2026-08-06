import test from 'node:test';
import assert from 'node:assert/strict';
import { handleGiteeProxy } from '../src/proxy/gitee.ts';

const GITEE_PUBLIC_PATH =
    '/api/gitee/repos/yufeng-liang/meta-data-public/contents/details_pool/0001.json';

function encodeJson(value) {
    return Buffer.from(JSON.stringify(value), 'utf8').toString('base64');
}

function decodeJson(value) {
    return JSON.parse(Buffer.from(value, 'base64').toString('utf8'));
}

function jsonResponse(value, status = 200) {
    return new Response(JSON.stringify(value), {
        status,
        headers: { 'Content-Type': 'application/json' },
    });
}

function request(path, method, body, headers = {}) {
    return new Request(`https://gateway.internal${path}`, {
        method,
        headers: {
            'Content-Type': 'application/json',
            ...headers,
        },
        body: body === undefined ? undefined : JSON.stringify(body),
    });
}

test('meta-data-public 内容上传保持明文且不读取 CLOUD_SYNC_AES_KEY', async () => {
    const originalFetch = globalThis.fetch;
    const calls = [];
    let cloudSyncKeyRead = false;
    const publicData = { doubanId: '1295644', title: '公共详情' };
    const content = encodeJson(publicData);

    globalThis.fetch = async (upstreamRequest, init) => {
        calls.push({ upstreamRequest, init });
        const upstreamBody = JSON.parse(new TextDecoder().decode(init.body));
        return jsonResponse({ content: upstreamBody.content, sha: 'public-sha' });
    };

    try {
        const response = await handleGiteeProxy(
            request(GITEE_PUBLIC_PATH, 'PUT', {
                message: '写入公共详情',
                content,
            }, { Authorization: 'Bearer app-jwt' }),
            {
                GITEE_ACCESS_TOKEN: 'server-gitee-token',
                get CLOUD_SYNC_AES_KEY() {
                    cloudSyncKeyRead = true;
                    return 'must-not-be-read';
                },
            },
            GITEE_PUBLIC_PATH,
        );

        assert.equal(response.status, 200);
        assert.equal(calls.length, 1);
        assert.equal(calls[0].init.headers.get('Authorization'), 'token server-gitee-token');
        assert.notEqual(calls[0].init.headers.get('Authorization'), 'Bearer app-jwt');
        assert.equal(cloudSyncKeyRead, false);
        assert.equal(
            JSON.parse(new TextDecoder().decode(calls[0].init.body)).content,
            content,
        );
        assert.deepEqual(decodeJson(JSON.parse(await response.text()).content), publicData);
    } finally {
        globalThis.fetch = originalFetch;
    }
});

test('details_pool、failures、personal 私有路径仍使用服务端加密', async () => {
    const originalFetch = globalThis.fetch;
    const privatePrefixes = ['details_pool/', 'failures/', 'personal/'];
    const cloudSyncKey = 'worker-test-cloud-sync-key';

    try {
        for (const prefix of privatePrefixes) {
            let encryptedContent;
            globalThis.fetch = async (_upstreamRequest, init) => {
                if (init.method === 'PUT') {
                    encryptedContent = JSON.parse(new TextDecoder().decode(init.body)).content;
                    return jsonResponse({ content: encryptedContent, sha: 'private-sha' });
                }
                return jsonResponse({ content: encryptedContent, sha: 'private-sha' });
            };

            const privatePath = `/api/gitee/repos/yufeng-liang/meta-data/contents/${prefix}0001.json`;
            const content = encodeJson({ prefix, private: true });
            const putResponse = await handleGiteeProxy(
                request(privatePath, 'PUT', { content }),
                {
                    GITEE_ACCESS_TOKEN: 'server-gitee-token',
                    CLOUD_SYNC_AES_KEY: cloudSyncKey,
                },
                privatePath,
            );
            const putBody = await putResponse.json();

            assert.equal(putResponse.status, 200);
            assert.notEqual(encryptedContent, content, `应加密 ${prefix}`);
            assert.deepEqual(decodeJson(putBody.content), { prefix, private: true });

            const getResponse = await handleGiteeProxy(
                request(privatePath, 'GET'),
                {
                    GITEE_ACCESS_TOKEN: 'server-gitee-token',
                    CLOUD_SYNC_AES_KEY: cloudSyncKey,
                },
                privatePath,
            );

            assert.equal(getResponse.status, 200);
            assert.deepEqual(decodeJson((await getResponse.json()).content), { prefix, private: true });
        }
    } finally {
        globalThis.fetch = originalFetch;
    }
});

test('公共池冲突响应可透明支持重新 GET、合并后 PUT', async () => {
    const originalFetch = globalThis.fetch;
    const calls = [];
    let putCount = 0;
    const existingData = { doubanId: '1295644', title: '已有详情' };
    const incomingData = { doubanId: '1295644', rating: 9.2 };

    globalThis.fetch = async (upstreamRequest, init) => {
        const body = init.body ? JSON.parse(new TextDecoder().decode(init.body)) : undefined;
        calls.push({ upstreamRequest, init, body });

        if (init.method === 'PUT' && putCount++ === 0) {
            return jsonResponse({ message: '文件已存在' }, 409);
        }
        if (init.method === 'GET') {
            return jsonResponse({ content: encodeJson(existingData), sha: 'existing-sha' });
        }
        return jsonResponse({ content: body.content, sha: 'merged-sha' });
    };

    try {
        const conflictResponse = await handleGiteeProxy(
            request(GITEE_PUBLIC_PATH, 'PUT', { content: encodeJson(incomingData) }),
            { GITEE_ACCESS_TOKEN: 'server-gitee-token', CLOUD_SYNC_AES_KEY: 'must-not-be-used' },
            GITEE_PUBLIC_PATH,
        );

        assert.equal(conflictResponse.status, 409);
        assert.deepEqual(await conflictResponse.json(), { message: '文件已存在' });

        const currentResponse = await handleGiteeProxy(
            request(`${GITEE_PUBLIC_PATH}?ref=master`, 'GET'),
            { GITEE_ACCESS_TOKEN: 'server-gitee-token', CLOUD_SYNC_AES_KEY: 'must-not-be-used' },
            GITEE_PUBLIC_PATH,
        );
        const currentBody = await currentResponse.json();

        assert.equal(currentResponse.status, 200);
        assert.deepEqual(decodeJson(currentBody.content), existingData);

        const mergedData = { ...existingData, ...incomingData };
        const mergedResponse = await handleGiteeProxy(
            request(GITEE_PUBLIC_PATH, 'PUT', {
                content: encodeJson(mergedData),
                sha: currentBody.sha,
            }),
            { GITEE_ACCESS_TOKEN: 'server-gitee-token', CLOUD_SYNC_AES_KEY: 'must-not-be-used' },
            GITEE_PUBLIC_PATH,
        );
        const mergedUpstreamBody = calls.at(-1).body;

        assert.equal(mergedResponse.status, 200);
        assert.deepEqual(decodeJson(mergedUpstreamBody.content), mergedData);
        assert.equal(mergedUpstreamBody.sha, 'existing-sha');
        assert.equal(calls.length, 3);
        assert.equal(calls[1].upstreamRequest, 'https://gitee.com/api/v5/repos/yufeng-liang/meta-data-public/contents/details_pool/0001.json?ref=master');
        for (const call of calls) {
            assert.equal(call.init.headers.get('Authorization'), 'token server-gitee-token');
        }
    } finally {
        globalThis.fetch = originalFetch;
    }
});
