// 票码测试共享夹具：用真实 SQLite 跑生产迁移，而不是假库。
//
// 用真实库是因为要防的正是假库看不见的东西：
// invite_requests.friend_id / invite_id 有外键指向 friends / invites，
// 删父行前必须先清空指针，顺序错了整批回滚会失败（D1 的 batch 是事务性的）。
// 同理，假库会让「审计 UPDATE 绑错字段」这类只匹配 0 行的语句看起来成功。

import { readFileSync } from 'node:fs';
import { DatabaseSync } from 'node:sqlite';
import { __setNowForTests } from '../../src/util/errors.ts';

export const T = 1_700_000_000;
export const COOLDOWN = 60;
export const INVITE_TTL = 72 * 60 * 60;
export const HTTP_REQUEST_ID = 'http-request-1';
export const EMAIL = 'user@example.com';

// 端点级例程内部调 errors.now() 取时钟，用 __setNowForTests 钉住后才能一律按 T 播种。
// 不钉住的话，releaseExpiredPublicInvitations 会先把按 T 播种的码当过期回收掉。
export async function withNow(value, run) {
    __setNowForTests(() => value);
    try {
        return await run();
    } finally {
        __setNowForTests(null);
    }
}

const MIGRATIONS = [
    '0001_init.sql',
    '0002_indexes.sql',
    '0003_invite_code_mask.sql',
    '0004_audit_log_detail.sql',
    '0007_device_recovery.sql',
    '0008_device_soft_delete.sql',
    '0011_public_invite_requests.sql',
    '0016_rate_limits.sql',
];

export function createInviteDb() {
    const db = new DatabaseSync(':memory:');
    db.exec(MIGRATIONS
        .map(name => readFileSync(new URL(`../../migrations/${name}`, import.meta.url), 'utf8'))
        .join('\n'));

    // 刻意用同步返回：batch 夹具把一组语句 map 成数组后直接 COMMIT，调用方 await
    // 的是数组本身而不是元素，元素若是 Promise 就让 meta.changes 读成 undefined。
    // D1 允许不 bind 直接 first/all/run（无参语句），所以 prepare 的结果本身也要可执行。
    const executable = statement => ({
        first(...bindings) { return statement.get(...bindings) ?? null; },
        all(...bindings) { return { results: statement.all(...bindings) }; },
        run(...bindings) {
            const result = statement.run(...bindings);
            return {
                meta: {
                    changes: Number(result.changes),
                    last_row_id: Number(result.lastInsertRowid),
                },
            };
        },
    });

    const prepare = sql => ({
        ...executable(db.prepare(sql)),
        bind(...bindings) {
            const statement = executable(db.prepare(sql));
            return {
                sql,
                first: () => statement.first(...bindings),
                all: () => statement.all(...bindings),
                run: () => statement.run(...bindings),
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

// 发信结果可控：fail=true 时 Brevo 回 5xx。回传「拦截到的邮件」+「被测调用的返回值」，
// 断言到底发没发、发给谁、正文里是哪一张码，全靠 sent。
export async function withEmail(fail, run) {
    const sent = [];
    const original = globalThis.fetch;
    globalThis.fetch = async (url, init) => {
        try {
            const payload = JSON.parse(init?.body ?? '{}');
            sent.push({
                to: payload.to?.[0]?.email,
                subject: payload.subject,
                html: payload.htmlContent,
                text: payload.textContent,
            });
        } catch {
            sent.push({ raw: init?.body });
        }
        return fail
            ? new Response('brevo unavailable', { status: 503 })
            : new Response(JSON.stringify({ messageId: '<brevo-message-id>' }), { status: 201 });
    };
    try {
        const result = await run();
        return { sent, result };
    } finally {
        globalThis.fetch = original;
    }
}

// 每个用例换 IP：enforcePublicRateLimit 是按 IP 3 次/小时，同 IP 连跑会被静默限流
export function inviteRequest(path, body, ip) {
    return new Request(`https://auth.worker.dev${path}`, {
        method: 'POST',
        headers: { 'Content-Type': 'application/json', 'CF-Connecting-IP': ip },
        body: JSON.stringify(body),
    });
}

export const row = (raw, sql, ...args) => raw.prepare(sql).get(...args);

export const rows = (raw, sql, ...args) => raw.prepare(sql).all(...args);

function insertFriend(raw, {
    id,
    email = EMAIL,
    nickname = '小明',
    signupRequestId = null,
    status = 'ACTIVE',
    at = T,
}) {
    raw.prepare(`
        INSERT INTO friends (id, nickname, email, signup_request_id, status, max_devices, created_at, updated_at)
        VALUES (?, ?, ?, ?, ?, 2, ?, ?)
    `).run(id, nickname, email, signupRequestId, status, at, at);
}

function insertInvite(raw, {
    id,
    friendId,
    codeHash,
    mask,
    expiresAt,
    usedAt = null,
    revokedAt = null,
    at = T,
    kind = 'ACTIVATION',
}) {
    raw.prepare(`
        INSERT INTO invites (id, friend_id, kind, code_hash, code_mask, expires_at, used_at, revoked_at, created_at)
        VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
    `).run(id, friendId, kind, codeHash, mask, expiresAt, usedAt, revokedAt, at);
}

function insertRequest(raw, {
    id,
    email = EMAIL,
    nickname = '小明',
    tokenHash,
    status,
    friendId = null,
    inviteId = null,
    verificationExpiresAt = T + 1800,
    emailSentAt = null,
    at = T,
}) {
    raw.prepare(`
        INSERT INTO invite_requests (
            id, nickname, email, email_normalized, verification_token_hash, status,
            friend_id, invite_id, verification_expires_at, email_sent_at, created_at, updated_at
        ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
    `).run(id, nickname, email, email, tokenHash, status, friendId, inviteId,
        verificationExpiresAt, emailSentAt, at, at);
}

// 「已签发、码还活着」：resend 原本唯一支持的一种状态。
// sentAt 单独可调，用来测冷却内 / 冷却外两种时刻。
export function seedIssuedRequest(raw, { at = T, ttl = INVITE_TTL, sentAt = at } = {}) {
    insertFriend(raw, { id: 'f1', signupRequestId: 'ir-1', at });
    insertInvite(raw, {
        id: 'invite-old', friendId: 'f1', codeHash: 'hash-old', mask: 'OLD***',
        expiresAt: at + ttl, at,
    });
    insertRequest(raw, {
        id: 'ir-1', tokenHash: 'token-hash', status: 'ISSUED', friendId: 'f1',
        inviteId: 'invite-old', verificationExpiresAt: at + 1800, emailSentAt: sentAt, at,
    });
}

// 「重发过一次再放到过期」留下的 zombie：request 已 EXPIRED 且指针清空，
// 但 friends 行因为名下还留着被撤销的旧码行而永久残留
export function seedZombieFriend(raw, { at = T, email = EMAIL } = {}) {
    insertFriend(raw, { id: 'f1', email, signupRequestId: 'ir-1', at });
    insertInvite(raw, {
        id: 'invite-revoked', friendId: 'f1', codeHash: 'hash-revoked', mask: 'OLD***',
        expiresAt: at + INVITE_TTL, revokedAt: at + 120, at,
    });
    insertRequest(raw, { id: 'ir-1', email, tokenHash: 'token-hash', status: 'EXPIRED', at });
}

// 管理员在后台手工建的用户：没有任何申请行
export function seedAdminFriend(raw, { at = T, email = EMAIL } = {}) {
    insertFriend(raw, { id: 'f1', email, signupRequestId: null, at });
}

// 真正注册过的用户：码已兑换
export function seedRedeemedFriend(raw, { at = T, email = EMAIL } = {}) {
    insertFriend(raw, { id: 'f1', email, signupRequestId: 'ir-1', at });
    insertInvite(raw, {
        id: 'invite-used', friendId: 'f1', codeHash: 'hash-used', mask: 'OLD***',
        expiresAt: at + INVITE_TTL, usedAt: at + 60, at,
    });
    insertRequest(raw, {
        id: 'ir-1', email, tokenHash: 'token-hash', status: 'ISSUED', friendId: 'f1',
        inviteId: 'invite-used', verificationExpiresAt: at + 1800, emailSentAt: at, at,
    });
}

// 被停用的邮箱：不能靠公共表单另开一行绕禁令
export function seedDisabledFriend(raw, { at = T, email = EMAIL } = {}) {
    insertFriend(raw, { id: 'f1', email, signupRequestId: 'ir-1', status: 'DISABLED', at });
}
