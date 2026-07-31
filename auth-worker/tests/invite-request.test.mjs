import test from 'node:test';
import assert from 'node:assert/strict';
import {
    buildInvitationEmail,
    normalizeInviteRequest,
    issueInvitation,
    releaseExpiredPublicInvitations,
    PUBLIC_INVITE_RESERVATION_TTL_SECONDS,
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

    const result = await issueInvitation({
        DB: db,
        EMAIL: { send: async () => ({ messageId: 'email-1' }) },
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

    assert.equal(result.inviteCode.length, 12);
    assert.equal(statements.length, 4);
    assert.match(preparedSql[0], /FROM invite_requests\s+WHERE status = 'ISSUED'/);
    assert.match(statements[0].sql, /UPDATE invite_requests/);
    assert.match(statements[0].sql, /COUNT\(\*\) FROM invite_requests/);
    assert.match(statements[0].sql, /< \?/);
    assert.match(statements[1].sql, /INSERT INTO friends/);
    assert.match(statements[1].sql, /WHERE EXISTS/);
    assert.match(statements[2].sql, /INSERT INTO invites/);
    assert.match(statements[2].sql, /WHERE EXISTS/);
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

test('invitation email contains both a verification link and the one-time code', () => {
    const email = buildInvitationEmail({
        nickname: '小明',
        inviteCode: 'ABCD2345EFGH',
        verificationUrl: 'https://tracktosearch.pages.dev/invite/verify/?token=token',
        expiresAt: 1_700_000_000,
    });

    assert.match(email.html, /ABCD2345EFGH/);
    assert.match(email.html, /href="https:\/\/tracktosearch\.pages\.dev\/invite\/verify\/\?token=token"/);
    assert.match(email.text, /ABCD2345EFGH/);
    assert.match(email.text, /官网/);
});
