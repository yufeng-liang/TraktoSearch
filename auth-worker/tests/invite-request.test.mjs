import test from 'node:test';
import assert from 'node:assert/strict';
import {
    buildInvitationEmail,
    handleInviteRequest,
    normalizeInviteRequest,
    issueInvitation,
    releaseExpiredPublicInvitations,
    resendInvitation,
    PUBLIC_INVITE_RESERVATION_TTL_SECONDS,
    sendEmail,
    enforcePublicRateLimit,
} from '../src/invitations.ts';

test('keeps the public invitation reservation window at 72 hours', () => {
    assert.equal(PUBLIC_INVITE_RESERVATION_TTL_SECONDS, 72 * 60 * 60);
});

test('normalizes a public invitation request without changing the user name', () => {
    assert.deepEqual(
        normalizeInviteRequest({ nickname: '  小明  ', email: '  USER@Example.COM ' }),
        { nickname: '小明', email: 'user@example.com' },
    );
});

test('rejects invalid public invitation input', () => {
    assert.throws(
        () => normalizeInviteRequest({ nickname: '', email: 'not-an-email' }),
        error => error?.code === 'INVALID_REQUEST',
    );
});

test('direct public request issues an invitation without email verification', async () => {
    let emailBody;
    const originalFetch = globalThis.fetch;
    globalThis.fetch = async (url, init) => {
        emailBody = { url, init };
        return new Response(JSON.stringify({ messageId: '<brevo-message-id>' }), { status: 201 });
    };

    const db = {
        prepare() {
            return {
                bind() {
                    return {
                        async all() { return { results: [] }; },
                        async first() { return null; },
                        async run() { return { meta: { changes: 1 } }; },
                    };
                },
            };
        },
        async batch(statements) {
            return statements.map(() => ({ meta: { changes: 1 } }));
        },
    };

    try {
        const response = await handleInviteRequest(
            new Request('https://example.com/api/invite-requests', {
                method: 'POST',
                headers: { 'content-type': 'application/json' },
                body: JSON.stringify({ nickname: 'test-user', email: 'user@example.com' }),
            }),
            dbEnv(db),
            'request-id',
        );

        assert.equal(response.status, 200);
        const payload = await response.json();
        assert.equal(payload.data.status, 'INVITE_SENT');
        assert.equal(payload.data.inviteCode, undefined);
        const body = JSON.parse(emailBody.init.body);
        assert.match(body.subject, /取票码已送达/);
        assert.match(body.htmlContent, /TICKET CODE/);
        assert.doesNotMatch(body.htmlContent, /invite\/verify/);
    } finally {
        globalThis.fetch = originalFetch;
    }
});

test('public resend enforces a 60 second cooldown', async () => {
    const db = {
        prepare(sql) {
            return {
                bind() {
                    return {
                        sql,
                        async first() {
                            return sql.includes('FROM friends')
                                ? {
                                    friend_id: 'friend-1',
                                    nickname: 'test-user',
                                    email: 'user@example.com',
                                    request_id: 'request-1',
                                    invite_id: 'invite-1',
                                    email_sent_at: 1_700_000_000,
                                    invite_expires_at: 1_700_100_000,
                                }
                                : null;
                        },
                    };
                },
            };
        },
    };

    await assert.rejects(
        () => resendInvitation(dbEnv(db), { email: 'user@example.com' }, 'request-id', 1_700_000_030),
        error => error?.code === 'RESEND_COOLDOWN' && /30/.test(error.message),
    );
});

test('private invite test key bypasses the public IP rate limit without writing KV', async () => {
    let putCalled = false;
    await enforcePublicRateLimit({
        INVITE_TEST_BYPASS_KEY: 'test-key',
        KV: {
            async get() { return '3'; },
            async put() { putCalled = true; },
        },
    }, new Request('https://example.com/api/invite-requests', {
        headers: { 'X-Invite-Test-Key': 'test-key' },
    }));

    assert.equal(putCalled, false);
});

test('private invite test key bypasses the resend cooldown', async () => {
    const db = {
        prepare(sql) {
            return {
                bind() {
                    return {
                        async first() {
                            return sql.includes('FROM friends')
                                ? {
                                    friend_id: 'friend-1',
                                    nickname: 'test-user',
                                    email: 'user@example.com',
                                    request_id: 'request-1',
                                    invite_id: 'invite-1',
                                    email_sent_at: 1_700_000_000,
                                    invite_expires_at: 1_700_100_000,
                                }
                                : null;
                        },
                    };
                },
                async run() { return { meta: { changes: 1 } }; },
            };
        },
        async batch() {
            return [1, 1, 1, 1].map(() => ({ meta: { changes: 1 } }));
        },
    };
    const originalFetch = globalThis.fetch;
    globalThis.fetch = async () => new Response(JSON.stringify({ messageId: 'test-message' }), { status: 201 });
    try {
        await resendInvitation({
            ...dbEnv(db),
            INVITE_TEST_BYPASS_KEY: 'test-key',
            BREVO_API_KEY: 'brevo-key',
            EMAIL_FROM: 'TraktoSearch <sender@example.com>',
        }, { email: 'user@example.com' }, 'request-id', 1_700_000_030, { bypassCooldown: true });
    } finally {
        globalThis.fetch = originalFetch;
    }
});

test('public resend atomically revokes the old code and creates a new code', async () => {
    const batches = [];
    let emailBody;
    const originalFetch = globalThis.fetch;
    globalThis.fetch = async (url, init) => {
        emailBody = { url, init };
        return new Response(JSON.stringify({ messageId: '<brevo-message-id>' }), { status: 201 });
    };
    const db = {
        prepare(sql) {
            return {
                bind() {
                    return {
                        sql,
                        async first() {
                            return sql.includes('FROM friends')
                                ? {
                                    friend_id: 'friend-1',
                                    nickname: 'test-user',
                                    email: 'user@example.com',
                                    request_id: 'request-1',
                                    invite_id: 'invite-1',
                                    email_sent_at: 1_700_000_000,
                                    invite_expires_at: 1_700_100_000,
                                }
                                : null;
                        },
                        async run() { return { meta: { changes: 1 } }; },
                    };
                },
            };
        },
        async batch(statements) {
            batches.push(statements);
            return statements.map(() => ({ meta: { changes: 1 } }));
        },
    };

    try {
        const result = await resendInvitation(
            dbEnv(db),
            { email: 'user@example.com' },
            'request-id',
            1_700_000_100,
        );

        assert.match(result.inviteCode, /^[0-9]{6}$/);
        assert.equal(batches.length, 1);
        assert.match(batches[0][0].sql, /UPDATE invites\s+SET revoked_at/);
        assert.match(batches[0][1].sql, /INSERT INTO invites/);
        assert.match(batches[0][2].sql, /UPDATE invite_requests/);
        assert.match(batches[0][3].sql, /PUBLIC_INVITE_RESEND/);
        const body = JSON.parse(emailBody.init.body);
        assert.equal(body.to[0].email, 'user@example.com');
        assert.match(body.htmlContent, /TICKET CODE/);
    } finally {
        globalThis.fetch = originalFetch;
    }
});

test('public resend rejects a concurrent rotation based on a stale code', async () => {
    const db = {
        prepare(sql) {
            return {
                bind() {
                    return {
                        async first() {
                            return sql.includes('FROM friends')
                                ? {
                                    friend_id: 'friend-1',
                                    nickname: 'test-user',
                                    email: 'user@example.com',
                                    request_id: 'request-1',
                                    invite_id: 'invite-1',
                                    email_sent_at: 1_700_000_000,
                                    invite_expires_at: 1_700_100_000,
                                }
                                : null;
                        },
                    };
                },
            };
        },
        async batch(statements) {
            return statements.map(() => ({ meta: { changes: 0 } }));
        },
    };

    await assert.rejects(
        () => resendInvitation(dbEnv(db), { email: 'user@example.com' }, 'request-id', 1_700_000_100),
        error => error?.code === 'RESEND_CONFLICT',
    );
});

function dbEnv(db) {
    return {
        DB: db,
        BREVO_API_KEY: 'brevo-test-key',
        EMAIL_FROM: 'TraktoSearch <noreply@example.com>',
        PUBLIC_SITE_ORIGIN: 'https://tracktosearch.pages.dev',
    };
}

test('sends transactional email through Brevo with the verified sender', async () => {
    const originalFetch = globalThis.fetch;
    let call;
    globalThis.fetch = async (url, init) => {
        call = { url, init };
        return new Response(JSON.stringify({ messageId: '<brevo-message-id>' }), {
            status: 201,
            headers: { 'content-type': 'application/json' },
        });
    };

    try {
        await sendEmail({
            BREVO_API_KEY: 'brevo-test-key',
            EMAIL_FROM: 'TraktoSearch <Yu-Fengliang@outlook.com>',
            EMAIL_REPLY_TO: '1577865546@qq.com',
        }, {
            to: 'user@example.com',
            subject: '测试邮件',
            html: '<p>HTML</p>',
            text: 'TEXT',
        });
    } finally {
        globalThis.fetch = originalFetch;
    }

    assert.equal(call.url, 'https://api.brevo.com/v3/smtp/email');
    assert.equal(call.init.headers['api-key'], 'brevo-test-key');
    const body = JSON.parse(call.init.body);
    assert.deepEqual(body.sender, { name: 'TraktoSearch', email: 'Yu-Fengliang@outlook.com' });
    assert.deepEqual(body.to, [{ email: 'user@example.com' }]);
    assert.deepEqual(body.replyTo, { email: '1577865546@qq.com' });
    assert.equal(body.htmlContent, '<p>HTML</p>');
    assert.equal(body.textContent, 'TEXT');
});

test('issueInvitation uses an atomic quota guard before creating the friend and invite', async () => {
    const statements = [];
    const preparedSql = [];
    const db = {
        prepare(sql) {
            preparedSql.push(sql);
            return {
                bind(...bindings) {
                    return {
                        sql,
                        bindings,
                        async all() { return { results: [] }; },
                        // reserveInviteCode 会先查码位是否被占用
                        async first() { return null; },
                        async run() { return { meta: { changes: 1 } }; },
                    };
                },
            };
        },
        async batch(batchStatements) {
            statements.push(...batchStatements);
            return batchStatements.map(() => ({ meta: { changes: 1 } }));
        },
    };

    const originalFetch = globalThis.fetch;
    globalThis.fetch = async () => new Response(JSON.stringify({ messageId: '<brevo-message-id>' }), { status: 201 });
    let result;
    try {
        result = await issueInvitation({
            DB: db,
            BREVO_API_KEY: 'brevo-test-key',
            EMAIL_FROM: 'TraktoSearch <noreply@example.com>',
            PUBLIC_SITE_ORIGIN: 'https://tracktosearch.pages.dev',
        }, {
            id: 'request-1',
            nickname: '小明',
            email: 'user@example.com',
            verificationTokenHash: 'token-hash',
        },
        'request-id',
        1_700_000_000);
    } finally {
        globalThis.fetch = originalFetch;
    }

    assert.match(result.inviteCode, /^[0-9]{6}$/);
    assert.equal(statements.length, 4);
    assert.match(statements[0].sql, /INSERT INTO friends/);
    assert.match(statements[0].sql, /COUNT\(\*\) FROM invite_requests/);
    assert.match(statements[0].sql, /< \?/);
    assert.match(statements[1].sql, /INSERT INTO invites/);
    assert.match(statements[1].sql, /WHERE EXISTS/);
    assert.match(statements[2].sql, /UPDATE invite_requests/);
    assert.match(statements[2].sql, /SET status = 'ISSUED'/);
    assert.match(statements[3].sql, /PUBLIC_INVITE_ISSUE/);
    assert.match(statements[3].sql, /WHERE EXISTS/);
});

test('releases expired unused public invitations without deleting the request audit trail', async () => {
    const batches = [];
    const db = {
        prepare(sql) {
            return {
                bind(...bindings) {
                    return {
                        sql,
                        bindings,
                        async all() {
                            return {
                                results: [{ id: 'request-1', friend_id: 'friend-1', invite_id: 'invite-1' }],
                            };
                        },
                    };
                },
            };
        },
        async batch(statements) {
            batches.push(statements);
            return statements.map(() => ({ meta: { changes: 1 } }));
        },
    };

    const released = await releaseExpiredPublicInvitations({ DB: db }, 1_700_000_000);

    assert.equal(released, 1);
    assert.equal(batches.length, 1);
    assert.match(batches[0][0].sql, /SET status = 'EXPIRED'/);
    assert.match(batches[0][0].sql, /friend_id = NULL/);
    assert.match(batches[0][0].sql, /invite_id = NULL/);
    assert.match(batches[0][1].sql, /DELETE FROM invites/);
    assert.match(batches[0][2].sql, /DELETE FROM friends/);
});

test('invitation email centers the code and includes the Chiikawa image', () => {
    const email = buildInvitationEmail({
        nickname: '小明',
        inviteCode: 'ABCD2345EFGH',
        siteUrl: 'https://tracktosearch.pages.dev',
        expiresAt: 1_700_000_000,
    });

    assert.match(email.html, /ABCD2345EFGH/);
    assert.match(email.html, /text-align:center/);
    const labelIndex = email.html.indexOf('TICKET CODE');
    const cardIndex = email.html.indexOf('background:#E9E2D4');
    assert.ok(labelIndex >= 0 && labelIndex < cardIndex);
    assert.match(email.html, /欢迎加入 TraktoSearch！/);
    assert.doesNotMatch(email.html, /欢迎来到 TraktoSearch/);
    assert.match(email.html, /直接帮助我改进后续版本/);
    assert.match(email.html, /App 内的反馈与建议提交/);
    assert.match(email.text, /App 内的反馈与建议提交/);
    assert.doesNotMatch(email.html, /复制取票码|onclick=|navigator\.clipboard/);
    assert.doesNotMatch(email.html, /letter-spacing:\.2em;text-transform:uppercase;">TraktoSearch/);
    assert.match(email.html, /background:#E9E2D4;border-left:4px solid #D95532;text-align:center/);
    assert.match(email.html, /margin:0 0 12px;text-align:center/);
    assert.match(email.html, /https:\/\/tracktosearch\.pages\.dev\/assets\/chiikawa\/ai-three-watching-email\.png/);
    assert.doesNotMatch(email.html, /app-icon-email/);
    assert.match(email.html, /TraktoSearch 取票码已准备好，请打开邮件查看。/);
    assert.match(email.html, /感谢你想试试 TraktoSearch，请在 App 取票机页面输入下列取票码。/);
    assert.match(email.text, /感谢你想试试 TraktoSearch，请在 App 取票机页面输入下列取票码。/);
    assert.doesNotMatch(email.html, /invite\/verify/);
    assert.match(email.text, /ABCD2345EFGH/);
    assert.match(email.text, /官网/);
    // 邮件固定为浅色配色：缺少声明时邮件客户端的深色模式会把整个米白底反相压暗
    assert.match(email.html, /<meta name="color-scheme" content="light only">/);
    assert.match(email.html, /<meta name="supported-color-schemes" content="light only">/);
    assert.match(email.html, /:root\{color-scheme:light only;supported-color-schemes:light only\}/);
    // Gmail 移动端会剥离 <head>，html/body 内联声明必须同时存在才能兜住反相
    assert.match(email.html, /<html lang="zh-CN" style="color-scheme:light only;supported-color-schemes:light only">/);
    assert.match(email.html, /<body style="color-scheme:light only;supported-color-schemes:light only;/);
});

test('invitation email offers an APK download that always tracks the latest release', () => {
    // 故意用非生产域名：下载地址必须由 siteUrl 派生，写死域名在这里就会红
    const email = buildInvitationEmail({
        nickname: '小明',
        inviteCode: '123456',
        siteUrl: 'https://preview.example/',
        expiresAt: 1_700_000_000,
    });

    assert.match(email.html, /href="https:\/\/preview\.example\/dl\/latest\.apk"/);
    assert.match(email.text, /https:\/\/preview\.example\/dl\/latest\.apk/);
    assert.match(email.html, /下载 Android 安装包/);
    // 邮件会长期留在收件箱里，版本号写死就会把旧用户引向旧包
    assert.doesNotMatch(email.html, /TraktoSearch-v\d/);
    assert.doesNotMatch(email.text, /TraktoSearch-v\d/);
    // 下载入口已独立成按钮，官网那句里再留「下载入口」就是重复信息
    assert.doesNotMatch(email.html, /下载入口/);
    // 票码是唯一主角，下载按钮只能排在票码之后
    const codeCardIndex = email.html.indexOf('background:#E9E2D4');
    const buttonIndex = email.html.indexOf('/dl/latest.apk');
    assert.ok(codeCardIndex >= 0 && codeCardIndex < buttonIndex);
    // 直链 APK 是钓鱼邮件的典型形状：正文里要点明来源域，让收件人能核对
    assert.match(email.html, /安装包始终由 preview\.example 提供/);
    assert.match(email.html, /未知来源/);
});
