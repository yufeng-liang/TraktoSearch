// 发信失败后的数据一致性：用真实 SQLite 跑生产迁移，验证两条发信路径都会回滚。
//
// 用真实库而不是假库，是因为要防的正是假库看不见的东西：
// invite_requests.friend_id / invite_id 有外键指向 friends / invites，
// 删父行前必须先清空指针，顺序错了整批回滚会失败（D1 的 batch 是事务性的）。
// 同理，假库会让「审计 UPDATE 绑错字段」这类只匹配 0 行的语句看起来成功。

import test from 'node:test';
import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import { DatabaseSync } from 'node:sqlite';
import { issueInvitation, resendInvitation } from '../src/invitations.ts';

const T = 1_700_000_000;
const COOLDOWN = 60;
const INVITE_TTL = 72 * 60 * 60;
const HTTP_REQUEST_ID = 'http-request-1';

const MIGRATIONS = [
    '0001_init.sql',
    '0002_indexes.sql',
    '0003_invite_code_mask.sql',
    '0004_audit_log_detail.sql',
    '0007_device_recovery.sql',
    '0011_public_invite_requests.sql',
    '0016_rate_limits.sql',
];

function createDb() {
    const db = new DatabaseSync(':memory:');
    db.exec(MIGRATIONS
        .map(name => readFileSync(new URL(`../migrations/${name}`, import.meta.url), 'utf8'))
        .join('\n'));

    const prepare = sql => ({
        bind(...bindings) {
            const statement = db.prepare(sql);
            return {
                first() { return statement.get(...bindings) ?? null; },
                all() { return { results: statement.all(...bindings) }; },
                run() {
                    const result = statement.run(...bindings);
                    return {
                        meta: {
                            changes: Number(result.changes),
                            last_row_id: Number(result.lastInsertRowid),
                        },
                    };
                },
            };
        },
    });

    return {
        raw: db,
        env: {
            DB: {
                prepare,
                batch(statements) {
                    db.exec('BEGIN');
                    try {
                        const results = statements.map(statement => statement.run());
                        db.exec('COMMIT');
                        return results;
                    } catch (error) {
                        db.exec('ROLLBACK');
                        throw error;
                    }
                },
            },
            BREVO_API_KEY: 'brevo-test-key',
            EMAIL_FROM: 'TraktoSearch <noreply@example.com>',
            PUBLIC_SITE_ORIGIN: 'https://tracktosearch.pages.dev',
        },
    };
}

// 发信结果可控：fail=true 时 Brevo 回 5xx
async function withEmail(fail, run) {
    const original = globalThis.fetch;
    globalThis.fetch = async () => (fail
        ? new Response('brevo unavailable', { status: 503 })
        : new Response(JSON.stringify({ messageId: '<brevo-message-id>' }), { status: 201 }));
    try {
        return await run();
    } finally {
        globalThis.fetch = original;
    }
}

function seedIssuedRequest(raw) {
    raw.exec(`
        INSERT INTO friends (id, nickname, email, signup_request_id, status, max_devices, created_at, updated_at)
        VALUES ('f1', '小明', 'user@example.com', 'ir-1', 'ACTIVE', 2, ${T}, ${T});
        INSERT INTO invites (id, friend_id, kind, code_hash, code_mask, expires_at, created_at)
        VALUES ('invite-old', 'f1', 'ACTIVATION', 'hash-old', 'OLD***', ${T + INVITE_TTL}, ${T});
        INSERT INTO invite_requests (
            id, nickname, email, email_normalized, verification_token_hash, status,
            friend_id, invite_id, verification_expires_at, email_sent_at, created_at, updated_at
        ) VALUES (
            'ir-1', '小明', 'user@example.com', 'user@example.com', 'token-hash', 'ISSUED',
            'f1', 'invite-old', ${T + 1800}, ${T}, ${T}, ${T}
        );
    `);
}

const row = (raw, sql, ...args) => raw.prepare(sql).get(...args);

test('首次签发发信失败：回滚干净，且抛 EMAIL_SEND_FAILED 而不是外键错误', async () => {
    const { raw, env } = createDb();
    raw.exec(`
        INSERT INTO invite_requests (
            id, nickname, email, email_normalized, verification_token_hash, status,
            verification_expires_at, created_at, updated_at
        ) VALUES ('ir-1', '小明', 'user@example.com', 'user@example.com', 'token-hash',
            'VERIFICATION_SENT', ${T + 1800}, ${T}, ${T});
    `);

    await withEmail(true, async () => {
        await assert.rejects(
            () => issueInvitation(env, {
                id: 'ir-1',
                nickname: '小明',
                email: 'user@example.com',
                verificationTokenHash: 'token-hash',
            }, HTTP_REQUEST_ID, T),
            error => error?.code === 'EMAIL_SEND_FAILED',
        );
    });

    // 名额没有被泄漏：friend 与 invite 都必须删干净
    assert.equal(row(raw, 'SELECT COUNT(*) AS n FROM friends').n, 0, 'friends 应回滚为空');
    assert.equal(row(raw, 'SELECT COUNT(*) AS n FROM invites').n, 0, 'invites 应回滚为空');

    const request = row(raw, 'SELECT status, friend_id, invite_id FROM invite_requests WHERE id = ?', 'ir-1');
    assert.equal(request.status, 'EMAIL_FAILED', '请求状态应回滚为 EMAIL_FAILED');
    assert.equal(request.friend_id, null, '外键指针应清空，否则删 friends 会失败');
    assert.equal(request.invite_id, null, '外键指针应清空，否则删 invites 会失败');

    // 审计行必须从 SUCCESS 翻成 FAILURE，且用 HTTP 请求 ID 匹配得上
    const audit = row(raw, `
        SELECT result, detail FROM audit_logs
        WHERE event_type = 'PUBLIC_INVITE_ISSUE' AND request_id = ?
    `, HTTP_REQUEST_ID);
    assert.ok(audit, '审计行应存在（用 HTTP 请求 ID 能查到）');
    assert.equal(audit.result, 'FAILURE', '审计行应标记为 FAILURE');
    assert.match(audit.detail, /email_send_failed_rolled_back/);
    raw.close();
});

test('重发发信失败：旧码恢复可用、冷却不重置，且新码不残留', async () => {
    const { raw, env } = createDb();
    seedIssuedRequest(raw);

    await withEmail(true, async () => {
        await assert.rejects(
            () => resendInvitation(env, { email: 'user@example.com' }, HTTP_REQUEST_ID, T + COOLDOWN),
            error => error?.code === 'EMAIL_SEND_FAILED',
        );
    });

    // 旧码必须恢复：用户手上/收件箱里只有这一个码
    const oldInvite = row(raw, 'SELECT revoked_at FROM invites WHERE id = ?', 'invite-old');
    assert.equal(oldInvite.revoked_at, null, '旧码不应被留在已撤销状态');

    // 新码不能残留：用户从未收到它，留着只是占码位
    assert.equal(row(raw, 'SELECT COUNT(*) AS n FROM invites').n, 1, 'invites 应只剩旧码');

    const request = row(raw, 'SELECT invite_id, email_sent_at FROM invite_requests WHERE id = ?', 'ir-1');
    assert.equal(request.invite_id, 'invite-old', '指针应指回旧码');
    assert.equal(request.email_sent_at, T, 'email_sent_at 应还原，否则用户要白等 60 秒');

    // 审计行必须翻成 FAILURE，不能留下「已重发成功」的假记录
    const audit = row(raw, `
        SELECT result FROM audit_logs
        WHERE event_type = 'PUBLIC_INVITE_RESEND' AND request_id = ?
    `, HTTP_REQUEST_ID);
    assert.ok(audit, '审计行应存在（用 HTTP 请求 ID 能查到）');
    assert.equal(audit.result, 'FAILURE', '审计行应标记为 FAILURE');

    // 行为级验证：发信恢复后用户应能立即重试，而不是撞上 RESEND_COOLDOWN
    await withEmail(false, async () => {
        const result = await resendInvitation(
            env, { email: 'user@example.com' }, 'http-request-2', T + COOLDOWN,
        );
        assert.match(result.inviteCode, /^[0-9]{6}$/);
    });
    raw.close();
});

// 反向对照：证明上面的字段断言不是恒真——成功路径下这些字段确实会变
test('重发成功：旧码被撤销、指针换新、冷却被重置', async () => {
    const { raw, env } = createDb();
    seedIssuedRequest(raw);

    let newInviteId;
    await withEmail(false, async () => {
        const result = await resendInvitation(
            env, { email: 'user@example.com' }, HTTP_REQUEST_ID, T + COOLDOWN,
        );
        newInviteId = result.inviteId;
    });

    const oldInvite = row(raw, 'SELECT revoked_at FROM invites WHERE id = ?', 'invite-old');
    assert.equal(oldInvite.revoked_at, T + COOLDOWN, '成功路径下旧码必须被撤销');
    assert.equal(row(raw, 'SELECT COUNT(*) AS n FROM invites').n, 2, '成功路径下新旧码共存');

    const request = row(raw, 'SELECT invite_id, email_sent_at FROM invite_requests WHERE id = ?', 'ir-1');
    assert.equal(request.invite_id, newInviteId, '成功路径下指针必须指向新码');
    assert.equal(request.email_sent_at, T + COOLDOWN, '成功路径下冷却必须被重置');

    const audit = row(raw, `
        SELECT result FROM audit_logs
        WHERE event_type = 'PUBLIC_INVITE_RESEND' AND request_id = ?
    `, HTTP_REQUEST_ID);
    assert.equal(audit.result, 'SUCCESS', '成功路径下审计记为 SUCCESS');
    raw.close();
});
