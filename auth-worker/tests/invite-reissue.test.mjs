// 票码三态：同一个邮箱再点「发送票码」，到底该不该真发信。
//
// 背景是 friends 行在**签发那一刻**就建好了，所以旧实现里「邮箱已有 friends 行」=
// 「刚发成功过」= 静默 no-op：用户没收到信、再点一次，页面照样提示已发送。
// 现在按三态分流：查无此人→签发；有行但码未兑换→真发新码；已兑换/已停用→如实拒绝。

import test from 'node:test';
import assert from 'node:assert/strict';
import * as invitations from '../src/invitations.ts';
import { sha256 } from '../src/util/crypto.ts';
import {
    createInviteDb,
    inviteRequest,
    row,
    rows,
    seedAdminFriend,
    seedDisabledFriend,
    seedIssuedRequest,
    seedRedeemedFriend,
    seedZombieFriend,
    withEmail,
    withNow,
    COOLDOWN,
    EMAIL,
    INVITE_TTL,
    T,
} from './helpers/invite-db.mjs';

const SUBMIT_PATH = '/api/invite-requests';
const RESEND_PATH = '/api/invite-requests/resend';
const HTTP_REQ = 'http-req-1';

let ipSeq = 0;
// enforcePublicRateLimit 是按 IP 3 次/小时，用例必须各占一个 IP
const nextIp = () => `10.1.${(ipSeq >> 8) & 0xff}.${(ipSeq++ & 0xff) + 1}`;

// 把 handler 抛出的 AppError 折算成 index.ts 路由实际会给出的 {status, code}，
// 这样断言的就是前端看到的那份契约；非业务异常一律冒出来，别伪装成 400。
async function toResponse(run) {
    try {
        const response = await run();
        const body = await response.json();
        return { status: response.status, code: body.code, data: body.data };
    } catch (error) {
        if (typeof error?.code === 'string' && typeof error?.statusCode === 'number') {
            return { status: error.statusCode, code: error.code, data: undefined };
        }
        throw error;
    }
}

const submit = (env, body = { nickname: '小明', email: EMAIL }) => toResponse(
    () => invitations.handleInviteRequest(
        inviteRequest(SUBMIT_PATH, body, nextIp()), env, HTTP_REQ,
    ),
);

const resend = (env, email = EMAIL) => toResponse(
    () => invitations.handleInviteResend(
        inviteRequest(RESEND_PATH, { email }, nextIp()), env, HTTP_REQ,
    ),
);

// 回滚类断言要的是「抛出去的那个错误」，不能被 toResponse 折成返回值
const submitRaw = (env, body = { nickname: '小明', email: EMAIL }) => invitations.handleInviteRequest(
    inviteRequest(SUBMIT_PATH, body, nextIp()), env, HTTP_REQ,
);

const resendRaw = (env, email = EMAIL) => invitations.handleInviteResend(
    inviteRequest(RESEND_PATH, { email }, nextIp()), env, HTTP_REQ,
);

const countOf = (raw, sql, ...args) => row(raw, sql, ...args).n;
const issuedCount = raw => countOf(raw, 'SELECT COUNT(*) AS n FROM invite_requests WHERE status = ?', 'ISSUED');
const inviteCount = raw => countOf(raw, 'SELECT COUNT(*) AS n FROM invites');
const friendCount = raw => countOf(raw, 'SELECT COUNT(*) AS n FROM friends');
const liveInvites = raw => rows(raw, 'SELECT id FROM invites WHERE revoked_at IS NULL AND used_at IS NULL');
const requestRow = (raw, id = 'ir-1') => row(
    raw, 'SELECT status, friend_id, invite_id, email_sent_at FROM invite_requests WHERE id = ?', id,
);

// 把 200 席名额占满：只塞 ISSUED 申请行，指针留 NULL 就不牵动 friends / invites
function fillQuota(raw, total = invitations.PUBLIC_INVITE_LIMIT) {
    const statement = raw.prepare(`
        INSERT INTO invite_requests (
            id, nickname, email, email_normalized, verification_token_hash, status,
            verification_expires_at, created_at, updated_at
        ) VALUES (?, ?, ?, ?, ?, 'ISSUED', ?, ?, ?)
    `);
    for (let index = 0; index < total; index++) {
        statement.run(`quota-${index}`, '占位', `quota-${index}@example.com`, `quota-${index}@example.com`,
            `quota-token-${index}`, T + 1800, T, T);
    }
}

test('新房客：仍然直接签发并真发信', async () => {
    const { raw, env } = createInviteDb();
    const { sent, result } = await withNow(T, () => withEmail(false, () => submit(env)));

    assert.equal(sent.length, 1, '必须真发一封信');
    assert.equal(sent[0].to, EMAIL);
    assert.match(sent[0].html, /TICKET CODE/);
    assert.equal(result.status, 200);
    assert.equal(result.data.status, 'INVITE_SENT');

    const request = row(raw, 'SELECT status, friend_id, invite_id FROM invite_requests WHERE email_normalized = ?', EMAIL);
    assert.equal(request.status, 'ISSUED');
    assert.ok(request.friend_id && request.invite_id, '签发后指针必须挂上');
    assert.equal(issuedCount(raw), 1, '占一个席位');
    raw.close();
});

test('未兑换且码还在有效期内：再点「发送票码」必须真发第二封，旧码作废', async () => {
    const { raw, env } = createInviteDb();
    seedIssuedRequest(raw, { sentAt: T - 600 });

    const { sent, result } = await withNow(T, () => withEmail(false, () => submit(env)));

    assert.equal(result.status, 200);
    assert.equal(result.data.status, 'INVITE_REISSUED', '前端要能区分「新发」与「补发」');
    assert.equal(sent.length, 1, '未兑换用户重点提交，必须真发信而不是静默 no-op');
    assert.equal(inviteCount(raw), 2, '旧码留档 + 新码一张');
    assert.notEqual(row(raw, 'SELECT revoked_at FROM invites WHERE id = ?', 'invite-old').revoked_at, null,
        '旧码必须作废');
    // 新码按 id 认，不能按 created_at 排序猜：时钟被钉住时新旧两行同一秒，
    // UUID 完全可能排在 'invite-old' 之前。
    const newInvite = row(raw, 'SELECT id, revoked_at FROM invites WHERE id <> ?', 'invite-old');
    assert.equal(newInvite.revoked_at, null, '新码必须可用');

    // 邮件正文里必须是被插进去的那张新码，而不是用户手上刚作废的旧码
    const code = sent[0].text.match(/([0-9]{6})/)[1];
    assert.equal(row(raw, 'SELECT code_hash FROM invites WHERE id = ?', newInvite.id).code_hash,
        await sha256(code), '发出的码必须等于库里那张新码');

    assert.equal(friendCount(raw), 1, '不能给同一个人另开一行 friends');
    assert.equal(issuedCount(raw), 1, '复用既有席位，不能重复占额');
    assert.ok(rows(raw, 'SELECT detail FROM audit_logs')
        .some(entry => /via:submit/.test(entry.detail ?? '')),
    '审计要能区分这次是从「发送票码」入口触发的');
    raw.close();
});

test('未兑换但申请行已 EXPIRED（zombie）：「发送票码」要复活席位并真发信', async () => {
    const { raw, env } = createInviteDb();
    seedZombieFriend(raw);
    assert.equal(issuedCount(raw), 0, '播种本身不该占额');

    const { sent, result } = await withNow(T, () => withEmail(false, () => submit(env)));

    assert.equal(result.status, 200);
    assert.equal(sent.length, 1, 'zombie 是最常见的死角状态，必须能发出去');
    const request = requestRow(raw);
    assert.equal(request.status, 'ISSUED', '过期申请行要复活为 ISSUED');
    assert.equal(request.friend_id, 'f1');
    assert.ok(request.invite_id, '指针必须挂到新码');
    assert.equal(request.email_sent_at, T, '冷却起点要刷新');
    assert.equal(issuedCount(raw), 1, '复活等于重新占一个席位');
    raw.close();
});

test('管理员手工建的用户（无申请行）：提交入口补申请行并回填锚点', async () => {
    const { raw, env } = createInviteDb();
    seedAdminFriend(raw);

    const { sent, result } = await withNow(T, () => withEmail(false, () => submit(env)));

    assert.equal(result.status, 200);
    assert.equal(sent.length, 1);
    const request = row(raw, 'SELECT id, status FROM invite_requests WHERE friend_id = ?', 'f1');
    assert.ok(request, '必须补一条申请行，否则后续过期回收找不到锚');
    assert.equal(request.status, 'ISSUED');
    assert.equal(row(raw, 'SELECT signup_request_id FROM friends WHERE id = ?', 'f1').signup_request_id,
        request.id, 'friends 要回填 signup_request_id，下次重发才认得它');
    raw.close();
});

test('管理员手工建的用户：重发入口同样能发码', async () => {
    const { raw, env } = createInviteDb();
    seedAdminFriend(raw);

    const { sent, result } = await withNow(T + COOLDOWN, () => withEmail(false, () => resend(env)));

    assert.equal(result.status, 200, '重发入口对同一状态也要放行');
    assert.equal(result.data.status, 'INVITE_RESENT');
    assert.equal(sent.length, 1);
    raw.close();
});

test('已兑换的正式用户：如实返回 ALREADY_REGISTERED，一封信都不发', async () => {
    const { raw, env } = createInviteDb();
    seedRedeemedFriend(raw);

    const { sent, result } = await withNow(T, () => withEmail(false, () => submit(env)));

    assert.equal(sent.length, 0, '已兑换用户不能白拿第 201 个席位');
    assert.equal(result.status, 409);
    assert.equal(result.code, 'ALREADY_REGISTERED');
    assert.equal(inviteCount(raw), 1, '不能偷偷多造一张码');
    assert.equal(issuedCount(raw), 1, '席位计数不能变');
    assert.equal(friendCount(raw), 1, '不得为同一邮箱另开一行');
    raw.close();
});

test('被停用的邮箱：不能靠公共表单另开一行绕禁令', async () => {
    const { raw, env } = createInviteDb();
    seedDisabledFriend(raw);

    const { sent, result } = await withNow(T, () => withEmail(false, () => submit(env)));

    assert.equal(sent.length, 0);
    assert.equal(result.code, 'ALREADY_REGISTERED');
    assert.equal(friendCount(raw), 1, '不得为同一邮箱新建 friends 行');
    assert.equal(inviteCount(raw), 0);
    raw.close();
});

test('60 秒内重复提交：如实回 RESEND_COOLDOWN，且不预先造出第二张码', async () => {
    const { raw, env } = createInviteDb();
    seedIssuedRequest(raw, { sentAt: T - 5 });

    const { sent, result } = await withNow(T, () => withEmail(false, () => submit(env)));

    assert.equal(sent.length, 0);
    assert.equal(result.status, 429);
    assert.equal(result.code, 'RESEND_COOLDOWN');
    assert.equal(inviteCount(raw), 1, '拒绝必须发生在插新码之前');
    assert.equal(row(raw, 'SELECT revoked_at FROM invites WHERE id = ?', 'invite-old').revoked_at, null,
        '冷却期内不能把用户手上能用的旧码作废掉');
    raw.close();
});

test('名额已满：复活 zombie 席位要撞 PUBLIC_INVITE_LIMIT_REACHED，且不留下占位脏数据', async () => {
    const { raw, env } = createInviteDb();
    seedZombieFriend(raw);
    fillQuota(raw);

    const { sent, result } = await withNow(T, () => withEmail(false, () => submit(env)));

    assert.equal(sent.length, 0);
    assert.equal(result.code, 'PUBLIC_INVITE_LIMIT_REACHED');
    const request = requestRow(raw);
    assert.equal(request.status, 'EXPIRED', '没抢到席位就不能把申请行改成 ISSUED');
    assert.equal(request.invite_id, null);
    raw.close();
});

test('重发入口换锚后：zombie 用户不再撞 INVITE_NOT_FOUND', async () => {
    const { raw, env } = createInviteDb();
    seedZombieFriend(raw);

    const { sent, result } = await withNow(T, () => withEmail(false, () => resend(env)));

    assert.equal(result.status, 200);
    assert.equal(result.data.status, 'INVITE_RESENT');
    assert.equal(sent.length, 1);
    assert.equal(issuedCount(raw), 1);
    raw.close();
});

test('重发：查无此邮箱仍是 INVITE_NOT_FOUND，不能因为放宽锚点而放行', async () => {
    const { raw, env } = createInviteDb();

    const { sent, result } = await withNow(T, () => withEmail(false, () => resend(env, 'nobody@example.com')));

    assert.equal(sent.length, 0);
    assert.equal(result.status, 404);
    assert.equal(result.code, 'INVITE_NOT_FOUND');
    assert.equal(friendCount(raw), 0, '重发入口绝不能顺手建用户');
    raw.close();
});

test('已兑换用户点重发：与提交入口给出同一个 ALREADY_REGISTERED', async () => {
    const { raw, env } = createInviteDb();
    seedRedeemedFriend(raw);

    const { result } = await withNow(T, () => withEmail(false, () => resend(env)));

    assert.equal(result.code, 'ALREADY_REGISTERED');
    assert.equal(inviteCount(raw), 1);
    raw.close();
});

test('reissue 发信失败要整体还原：席位退回 EXPIRED、不冒出新码、审计不留假成功', async () => {
    const { raw, env } = createInviteDb();
    seedZombieFriend(raw);

    await withNow(T, () => assert.rejects(
        () => withEmail(true, () => submitRaw(env)),
        error => error?.code === 'EMAIL_SEND_FAILED',
    ));

    const request = requestRow(raw);
    assert.equal(request.status, 'EXPIRED', '回滚后申请状态必须原样，否则失败的一次白占席位');
    assert.equal(request.friend_id, null);
    assert.equal(request.invite_id, null);
    assert.equal(request.email_sent_at, null);
    assert.equal(issuedCount(raw), 0);
    assert.equal(liveInvites(raw).length, 0, 'zombie 本来就没有可用码，回滚后也不该冒出一张');
    const audit = rows(raw, 'SELECT result FROM audit_logs');
    assert.equal(audit.length, 1, '审计只应有那次失败尝试');
    assert.equal(audit[0].result, 'FAILURE');
    raw.close();
});

test('reissue 发信失败后能立刻重试，而不是撞上自己刷新的冷却', async () => {
    const { raw, env } = createInviteDb();
    seedIssuedRequest(raw, { sentAt: T - 600 });

    await withNow(T, async () => {
        await assert.rejects(
            () => withEmail(true, () => submitRaw(env)),
            error => error?.code === 'EMAIL_SEND_FAILED',
        );
        const { sent } = await withEmail(false, () => submit(env));
        assert.equal(sent.length, 1, '上一次失败不该让用户白等 60 秒');
    });

    assert.equal(liveInvites(raw).length, 1, '最终只留一张可用码');
    assert.equal(requestRow(raw).status, 'ISSUED');
    assert.equal(requestRow(raw).email_sent_at, T);
    raw.close();
});

test('重发失败后旧码仍可用（沿用既有回滚语义，换锚不能弄丢它）', async () => {
    const { raw, env } = createInviteDb();
    seedIssuedRequest(raw, { sentAt: T - 600 });

    await withNow(T, () => assert.rejects(
        () => withEmail(true, () => resendRaw(env)),
        error => error?.code === 'EMAIL_SEND_FAILED',
    ));

    assert.equal(row(raw, 'SELECT revoked_at FROM invites WHERE id = ?', 'invite-old').revoked_at, null,
        '旧码必须恢复可用');
    assert.equal(liveInvites(raw).length, 1);
    assert.equal(requestRow(raw).invite_id, 'invite-old', '指针要指回旧码');
    assert.equal(requestRow(raw).email_sent_at, T - 600, '冷却不该被失败的一次刷新');
    raw.close();
});

// resolve 与写入之间被并发轮换过：只靠「先读后写」会给同一人叠两张可用码，
// 所以 insert / repoint 两条语句都带上「invite_id 仍等于读到的那张」的守卫。
test('候选被并发轮换过：按陈旧快照 reissue 必须撞 RESEND_CONFLICT 且不留第二张码', async () => {
    const { raw, env } = createInviteDb();
    seedIssuedRequest(raw, { sentAt: T - 600 });
    const target = await invitations.resolvePublicInviteTarget(env, EMAIL);
    assert.equal(target.kind, 'UNREDEEMED', '先确认取到的是可重发候选');

    // 另一次重发抢先换了指针（先插码再改指针，invite_id 有外键）
    raw.prepare(`
        INSERT INTO invites (id, friend_id, kind, code_hash, code_mask, expires_at, created_at)
        VALUES ('invite-other', 'f1', 'ACTIVATION', 'hash-other', 'OTH***', ?, ?)
    `).run(T + INVITE_TTL, T);
    raw.prepare("UPDATE invite_requests SET invite_id = 'invite-other' WHERE id = 'ir-1'").run();

    await assert.rejects(
        () => invitations.reissueInvitation(env, target.candidate, HTTP_REQ, T, { via: 'resend' }),
        error => error?.code === 'RESEND_CONFLICT',
    );

    assert.equal(countOf(raw, 'SELECT COUNT(*) AS n FROM invites'), 2, '陈旧快照不能插出新码');
    assert.equal(row(raw, 'SELECT revoked_at FROM invites WHERE id = ?', 'invite-old').revoked_at, null,
        '别人正在用的那张码不该被陈旧请求作废');
    raw.close();
});

test('提交入口不能改写已存在用户的称呼', async () => {
    const { raw, env } = createInviteDb();
    seedIssuedRequest(raw, { sentAt: T - 600 });

    const { sent } = await withNow(T, () => withEmail(false, () => submit(env, { nickname: '冒充者', email: EMAIL })));

    assert.equal(row(raw, 'SELECT nickname FROM friends WHERE id = ?', 'f1').nickname, '小明',
        '公共表单不该能改写已存在用户的称呼');
    assert.match(sent[0].html, /你好，小明/);
    assert.doesNotMatch(sent[0].html, /冒充者/);
    raw.close();
});
