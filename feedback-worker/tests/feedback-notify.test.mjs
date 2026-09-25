// tests/feedback-notify.test.mjs
import test from 'node:test';
import assert from 'node:assert/strict';
import { handleSubmit } from '../src/api/submit.ts';
import { signAccessToken, verifyAccessToken } from '../src/util/jwt.ts';
import { FEEDBACK_NOTIFY_SCOPE, notifyNewFeedback, buildNotifyPayload } from '../src/notify/feedback-notify.ts';
// 跨 worker 契约：token 由 feedback-worker 签发、由 auth-worker 验签。
// 两边各有一份 jwt.ts（独立 npm 项目），实现漂移时只有这里能发现。
import { verifyAccessToken as verifyOnAuthWorker } from '../../auth-worker/src/util/jwt.ts';
import { handleFeedbackNotify } from '../../auth-worker/src/feedback-notify.ts';

const JWT_SECRET = 'shared-signing-key';

function submitBody(overrides = {}) {
    return {
        type: 'BUG',
        content: '搜索结果偶尔为空',
        contact: 'ming@example.com',
        screenshots: [],
        friendNickname: '小明',
        traktUsername: 'ming',
        appVersion: '1.4.2',
        osVersion: 'Android 15',
        deviceModel: 'Pixel 8',
        ...overrides,
    };
}

/** 覆盖 submit 用到的三条语句：限流 UPSERT(.run)、seq 自增(.first)、feedbacks 插入(.run) */
function mockDb() {
    const calls = [];
    const db = {
        prepare(sql) {
            return {
                bind(...bindings) {
                    calls.push({ sql, bindings });
                    return {
                        async run() {
                            if (sql.includes('feedback_rate_limits')) return { meta: { changes: 1 } };
                            return { meta: { changes: 1 } };
                        },
                        async first() {
                            if (sql.includes('feedback_seq')) return { seq: 7 };
                            return null;
                        },
                    };
                },
            };
        },
    };
    return { db, calls };
}

function captureFetcher({ status = 200, body = '{"code":"SUCCESS"}' } = {}) {
    const calls = [];
    return {
        calls,
        fetcher: {
            fetch: async (url, init) => {
                calls.push({ url, init });
                return new Response(body, { status, headers: { 'Content-Type': 'application/json' } });
            },
        },
    };
}

/** 记录 waitUntil 的 promise，让测试能 await 异步通知 */
function mockCtx() {
    const tasks = [];
    return { tasks, ctx: { waitUntil: promise => tasks.push(promise) } };
}

function submitRequest(body) {
    return new Request('https://feedback-worker.internal/feedback-api/submit', {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify(body),
    });
}

const payload = { sub: 'friend-1', device: 'device-1' };

test('提交成功后异步通知 auth-worker，payload 字段与落库一致', async () => {
    const { db } = mockDb();
    const { fetcher, calls } = captureFetcher();
    const { ctx, tasks } = mockCtx();

    const response = await handleSubmit(
        submitRequest(submitBody()),
        { DB: db, KV: {}, AUTH_WORKER: fetcher, JWT_SIGNING_KEY: JWT_SECRET },
        'req-1',
        payload,
        ctx,
    );
    assert.equal(response.status, 200);
    const result = await response.json();
    assert.equal(result.data.displayId, 'BUG007');
    assert.equal(tasks.length, 1, '应把通知放进 waitUntil');
    await Promise.all(tasks);

    assert.equal(calls.length, 1);
    assert.equal(calls[0].url, 'https://auth-worker.internal/internal/feedback-notify');
    const sent = JSON.parse(calls[0].init.body);
    assert.equal(sent.displayId, 'BUG007');
    assert.equal(sent.type, 'BUG');
    assert.equal(sent.content, '搜索结果偶尔为空');
    assert.equal(sent.friendNickname, '小明');
    assert.equal(sent.contact, 'ming@example.com');
    assert.equal(sent.traktUsername, 'ming');
    assert.equal(sent.doubanUsername, null);
    assert.equal(sent.appVersion, '1.4.2');
    assert.equal(sent.osVersion, 'Android 15');
    assert.equal(sent.deviceModel, 'Pixel 8');
    assert.equal(sent.screenshotCount, 0);
    assert.equal(typeof sent.createdAt, 'number');
});

test('通知 token 带内部 scope，且能被 auth-worker 验签通过', async () => {
    const { db } = mockDb();
    const { fetcher, calls } = captureFetcher();
    const { ctx, tasks } = mockCtx();

    await handleSubmit(
        submitRequest(submitBody()),
        { DB: db, KV: {}, AUTH_WORKER: fetcher, JWT_SIGNING_KEY: JWT_SECRET },
        'req-1',
        payload,
        ctx,
    );
    await Promise.all(tasks);

    const authHeader = calls[0].init.headers.Authorization || calls[0].init.headers.get?.('Authorization');
    assert.match(authHeader, /^Bearer /);
    const token = authHeader.slice(7);

    const verified = await verifyOnAuthWorker(JWT_SECRET, token);
    assert.ok(verified, 'auth-worker 必须能验签 feedback-worker 签发的 token');
    assert.deepEqual(verified.scope, [FEEDBACK_NOTIFY_SCOPE]);
    // 负控制：不能带 App 的 api scope，否则任何登录用户都能触发通知邮件
    assert.ok(!verified.scope.includes('api'));
});

test('AUTH_WORKER binding 缺失时提交仍成功，不发信', async () => {
    const { db } = mockDb();
    const { ctx, tasks } = mockCtx();

    const response = await handleSubmit(
        submitRequest(submitBody()),
        { DB: db, KV: {}, JWT_SIGNING_KEY: JWT_SECRET },
        'req-1',
        payload,
        ctx,
    );
    assert.equal(response.status, 200);
    const result = await response.json();
    assert.equal(result.data.displayId, 'BUG007');
    await Promise.all(tasks);
});

test('发信上游 500 不影响提交响应', async () => {
    const { db } = mockDb();
    const { fetcher } = captureFetcher({ status: 500, body: 'brevo down' });
    const { ctx, tasks } = mockCtx();

    const response = await handleSubmit(
        submitRequest(submitBody()),
        { DB: db, KV: {}, AUTH_WORKER: fetcher, JWT_SIGNING_KEY: JWT_SECRET },
        'req-1',
        payload,
        ctx,
    );
    assert.equal(response.status, 200);
    await Promise.all(tasks);
});

test('上游 fetch 抛异常时被吞掉，不冒泡到调用方', async () => {
    const { db } = mockDb();
    const { ctx, tasks } = mockCtx();
    const env = {
        DB: db,
        KV: {},
        JWT_SIGNING_KEY: JWT_SECRET,
        AUTH_WORKER: { fetch: async () => { throw new Error('network down'); } },
    };

    const response = await handleSubmit(submitRequest(submitBody()), env, 'req-1', payload, ctx);
    assert.equal(response.status, 200);
    await assert.doesNotReject(() => Promise.all(tasks));
});

test('无 ctx 时不发信（老调用方兼容）', async () => {
    const { db } = mockDb();
    const { fetcher, calls } = captureFetcher();

    const response = await handleSubmit(
        submitRequest(submitBody()),
        { DB: db, KV: {}, AUTH_WORKER: fetcher, JWT_SIGNING_KEY: JWT_SECRET },
        'req-1',
        payload,
    );
    assert.equal(response.status, 200);
    assert.equal(calls.length, 0);
});

test('notifyNewFeedback 用 60 秒短时 token', async () => {
    const { fetcher, calls } = captureFetcher();
    await notifyNewFeedback(
        { AUTH_WORKER: fetcher, JWT_SIGNING_KEY: JWT_SECRET },
        buildNotifyPayload({
            id: 'fb-1', displayId: 'UX001', type: 'UX', content: 'x', friendNickname: 'a',
            contact: null, traktUsername: null, doubanUsername: null,
            appVersion: '1.0', osVersion: 'Android 15', deviceModel: 'Pixel 8',
            screenshotCount: 1, createdAt: 1_700_000_000,
        }),
    );

    const token = calls[0].init.headers.Authorization.slice(7);
    const verified = await verifyAccessToken(JWT_SECRET, token);
    assert.ok(verified);
    assert.ok(verified.exp - verified.iat <= 60, `TTL 应 <= 60s，实际 ${verified.exp - verified.iat}`);
});

test('端到端：submit 的通知真被 auth-worker 端点接受并发信', async () => {
    // 用真的 auth-worker handler 当 binding 上游：两个 worker 各有一份 jwt.ts，
    // 只验证「能验签」还不够——真正要证明的是这条链路端到端通。
    const sent = [];
    const authEnv = {
        JWT_SIGNING_KEY: JWT_SECRET,
        ADMIN_EMAIL: 'dev@example.com',
        ADMIN_UI_ORIGIN: 'https://admin.example.com',
        EMAIL_FROM: 'TraktoSearch <no-reply@example.com>',
        EMAIL: { send: async message => { sent.push(message); } },
    };
    const fetcher = {
        fetch: async (url, init) => handleFeedbackNotify(
            new Request(url, init),
            authEnv,
            'req-e2e',
        ),
    };

    const { db } = mockDb();
    const { ctx, tasks } = mockCtx();
    const response = await handleSubmit(
        submitRequest(submitBody({ contact: null, traktUsername: null })),
        { DB: db, KV: {}, AUTH_WORKER: fetcher, JWT_SIGNING_KEY: JWT_SECRET },
        'req-1',
        payload,
        ctx,
    );
    assert.equal(response.status, 200);
    await Promise.all(tasks);

    assert.equal(sent.length, 1, 'auth-worker 应真的发出邮件');
    assert.equal(sent[0].to, 'dev@example.com');
    assert.match(sent[0].subject, /BUG007/);
    assert.match(sent[0].subject, /小明/);
    assert.match(sent[0].html, /搜索结果偶尔为空/);
    assert.match(sent[0].html, /https:\/\/admin\.example\.com\/admin\/#\/feedback\//);
    // 未填的可选字段不该在正文里留空行
    assert.doesNotMatch(sent[0].text, /联系方式：/);
});
