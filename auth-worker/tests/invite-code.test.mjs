import test from 'node:test';
import assert from 'node:assert/strict';
import { INVITE_CODE_LENGTH, generateInviteCode, maskInviteCode } from '../src/util/crypto.ts';
import { reserveInviteCode } from '../src/util/invite-code.ts';

test('invite codes are six pure digits', () => {
    assert.equal(INVITE_CODE_LENGTH, 6);
    for (let i = 0; i < 500; i++) {
        assert.match(generateInviteCode(), /^[0-9]{6}$/);
    }
});

test('invite code digits stay unbiased across the full 0-9 range', () => {
    // 拒绝采样的回归护栏：byte % 10 会让 0-5 比 6-9 多约 1/256 的概率。
    // 12 万个数字下，每个数字的期望频次是 12000，偏置实现会把 0-5 抬到 ~12300
    // 而 6-9 压到 ~11500；这里的 ±6% 窗口既能抓住那种偏置，又不会随机抖动误报。
    const counts = new Array(10).fill(0);
    const samples = 20_000;
    for (let i = 0; i < samples; i++) {
        for (const digit of generateInviteCode()) counts[Number(digit)]++;
    }
    const expected = (samples * INVITE_CODE_LENGTH) / 10;
    for (let digit = 0; digit <= 9; digit++) {
        const drift = Math.abs(counts[digit] - expected) / expected;
        assert.ok(drift < 0.06, `digit ${digit} drifted ${(drift * 100).toFixed(2)}% from uniform`);
    }
});

test('six digit codes are masked whole so the plaintext never reaches the database', () => {
    // 按 12 位写法取首尾 4 位会在 6 位码上拼出完整明文（"123456" -> "1234****3456"）。
    for (let i = 0; i < 200; i++) {
        const code = generateInviteCode();
        const mask = maskInviteCode(code);
        assert.equal(mask, '******');
        assert.doesNotMatch(mask, /[0-9]/);
    }
});

test('legacy twelve character codes keep the head and tail mask for manual lookup', () => {
    assert.equal(maskInviteCode('ABCD2345EFGH'), 'ABCD****EFGH');
});

function createInviteCodeDb(takenHashes) {
    const checkedHashes = [];
    return {
        checkedHashes,
        prepare(sql) {
            assert.match(sql, /SELECT 1 AS taken FROM invites WHERE code_hash = \?/);
            return {
                bind(codeHash) {
                    checkedHashes.push(codeHash);
                    return {
                        async first() {
                            return takenHashes.has(codeHash) ? { taken: 1 } : null;
                        },
                    };
                },
            };
        },
    };
}

test('reserveInviteCode returns the first free code and its hash', async () => {
    const db = createInviteCodeDb(new Set());
    const { code, codeHash } = await reserveInviteCode(db);

    assert.match(code, /^[0-9]{6}$/);
    assert.equal(db.checkedHashes.length, 1);
    assert.equal(codeHash, db.checkedHashes[0]);
});

test('reserveInviteCode redraws when the code hash is already taken', async () => {
    // 前两次抽签一律判为已占用，第三次放行：验证碰撞会重抽而不是直接抛错。
    let remainingRejections = 2;
    const db = createInviteCodeDb({ has: () => remainingRejections-- > 0 });
    const { code } = await reserveInviteCode(db);

    assert.match(code, /^[0-9]{6}$/);
    assert.equal(db.checkedHashes.length, 3);
});

test('reserveInviteCode gives up with INVITE_CODE_EXHAUSTED instead of forcing a UNIQUE violation', async () => {
    const db = createInviteCodeDb({ has: () => true });

    await assert.rejects(
        () => reserveInviteCode(db),
        error => error?.code === 'INVITE_CODE_EXHAUSTED' && error?.statusCode === 503,
    );
    assert.equal(db.checkedHashes.length, 8);
});
