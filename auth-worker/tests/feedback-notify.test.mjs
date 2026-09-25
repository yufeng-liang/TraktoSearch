// tests/feedback-notify.test.mjs
import test from 'node:test';
import assert from 'node:assert/strict';
import {
    FEEDBACK_NOTIFY_SCOPE,
    normalizeFeedbackNotify,
    buildFeedbackNotifyEmail,
    handleFeedbackNotify,
} from '../src/feedback-notify.ts';
import { signAccessToken } from '../src/util/jwt.ts';

const JWT_SECRET = 'test-signing-key';

function validBody(overrides = {}) {
    return {
        id: 'fb-1',
        displayId: 'BUG001',
        type: 'BUG',
        content: '搜索结果偶尔为空',
        friendNickname: '小明',
        contact: 'ming@example.com',
        traktUsername: 'ming',
        doubanUsername: null,
        appVersion: '1.4.2',
        osVersion: 'Android 15',
        deviceModel: 'Pixel 8',
        screenshotCount: 2,
        createdAt: 1_700_000_000,
        ...overrides,
    };
}

function envWith(overrides = {}) {
    const sent = [];
    return {
        sent,
        env: {
            JWT_SIGNING_KEY: JWT_SECRET,
            ADMIN_EMAIL: 'dev@example.com',
            ADMIN_UI_ORIGIN: 'https://admin.example.com',
            EMAIL_FROM: 'TraktoSearch <no-reply@example.com>',
            BREVO_API_KEY: 'brevo-key',
            ...overrides,
        },
    };
}

function jsonRequest(body, token) {
    return new Request('https://auth-worker.internal/internal/feedback-notify', {
        method: 'POST',
        headers: {
            'Content-Type': 'application/json',
            ...(token ? { Authorization: `Bearer ${token}` } : {}),
        },
        body: typeof body === 'string' ? body : JSON.stringify(body),
    });
}

test('normalizeFeedbackNotify 收紧字段并保留正文换行', () => {
    const input = normalizeFeedbackNotify(validBody({ content: '第一行\n第二行' }));
    assert.equal(input.displayId, 'BUG001');
    assert.equal(input.type, 'BUG');
    assert.equal(input.content, '第一行\n第二行');
    assert.equal(input.doubanUsername, null);
    assert.equal(input.screenshotCount, 2);
});

test('normalizeFeedbackNotify 拒绝非法类型与缺失必填字段', () => {
    assert.throws(
        () => normalizeFeedbackNotify(validBody({ type: 'XXX' })),
        error => error?.code === 'INVALID_REQUEST',
    );
    assert.throws(
        () => normalizeFeedbackNotify(validBody({ appVersion: '' })),
        error => error?.code === 'INVALID_REQUEST',
    );
    assert.throws(
        () => normalizeFeedbackNotify(validBody({ content: '   ' })),
        error => error?.code === 'INVALID_REQUEST',
    );
});

test('normalizeFeedbackNotify 把截图张数钳在 0..5', () => {
    assert.equal(normalizeFeedbackNotify(validBody({ screenshotCount: 99 })).screenshotCount, 5);
    assert.equal(normalizeFeedbackNotify(validBody({ screenshotCount: -3 })).screenshotCount, 0);
    assert.equal(normalizeFeedbackNotify(validBody({ screenshotCount: 'x' })).screenshotCount, 0);
});

test('邮件正文转义 HTML 并把换行转成 <br>', () => {
    const input = normalizeFeedbackNotify(validBody({
        content: '<script>alert(1)</script>\n第二行',
        friendNickname: '<b>小明</b>',
    }));
    const email = buildFeedbackNotifyEmail(input, 'https://admin.example.com');

    assert.match(email.html, /&lt;script&gt;alert\(1\)&lt;\/script&gt;<br>第二行/);
    assert.doesNotMatch(email.html, /<script>/);
    assert.match(email.html, /&lt;b&gt;小明&lt;\/b&gt;/);
    assert.match(email.subject, /BUG001/);
});

test('邮件深链指向后台详情页 hash 路由', () => {
    // 真实 id 是 crypto.randomUUID()，encodeURIComponent 不改变它
    const id = '550e8400-e29b-41d4-a716-446655440000';
    const input = normalizeFeedbackNotify(validBody({ id }));
    const email = buildFeedbackNotifyEmail(input, 'https://admin.example.com/');
    const expected = `https://admin.example.com/admin/#/feedback/${id}`;
    assert.match(email.html, new RegExp(`href="${expected}"`));
    assert.match(email.text, new RegExp(`后台详情页：${expected}`));
});

test('缺 token 返回 401', async () => {
    const { env } = envWith();
    await assert.rejects(
        () => handleFeedbackNotify(jsonRequest(validBody()), env, 'req-1'),
        error => error?.statusCode === 401,
    );
});

test('App 的 api scope token 不能触发通知（403）', async () => {
    const { env } = envWith();
    const token = await signAccessToken(JWT_SECRET, 'friend-1', 'device-1', ['api'], 60);
    await assert.rejects(
        () => handleFeedbackNotify(jsonRequest(validBody(), token), env, 'req-1'),
        error => error?.statusCode === 403,
    );
});

test('过期 token 返回 401', async () => {
    const { env } = envWith();
    const token = await signAccessToken(JWT_SECRET, 'feedback-worker', 'service-binding', [FEEDBACK_NOTIFY_SCOPE], -10);
    await assert.rejects(
        () => handleFeedbackNotify(jsonRequest(validBody(), token), env, 'req-1'),
        error => error?.statusCode === 401,
    );
});

test('正确 scope 的 token 发信成功', async () => {
    const { env } = envWith();
    const sent = [];
    env.EMAIL = { send: async message => { sent.push(message); } };
    delete env.BREVO_API_KEY;

    const token = await signAccessToken(JWT_SECRET, 'feedback-worker', 'service-binding', [FEEDBACK_NOTIFY_SCOPE], 60);
    const response = await handleFeedbackNotify(jsonRequest(validBody(), token), env, 'req-1');

    assert.equal(response.status, 200);
    assert.equal(sent.length, 1);
    assert.equal(sent[0].to, 'dev@example.com');
    assert.match(sent[0].subject, /BUG001/);
    assert.match(sent[0].html, /搜索结果偶尔为空/);
});

test('畸形 JSON 归 400 而不是 500', async () => {
    const { env } = envWith();
    const token = await signAccessToken(JWT_SECRET, 'feedback-worker', 'service-binding', [FEEDBACK_NOTIFY_SCOPE], 60);
    await assert.rejects(
        () => handleFeedbackNotify(jsonRequest('{not json', token), env, 'req-1'),
        error => error?.statusCode === 400,
    );
});

test('未配置 ADMIN_EMAIL 时返回 503，不发信', async () => {
    const { env } = envWith({ ADMIN_EMAIL: undefined });
    const sent = [];
    env.EMAIL = { send: async message => { sent.push(message); } };
    delete env.BREVO_API_KEY;

    const token = await signAccessToken(JWT_SECRET, 'feedback-worker', 'service-binding', [FEEDBACK_NOTIFY_SCOPE], 60);
    await assert.rejects(
        () => handleFeedbackNotify(jsonRequest(validBody(), token), env, 'req-1'),
        error => error?.statusCode === 503,
    );
    assert.equal(sent.length, 0);
});
