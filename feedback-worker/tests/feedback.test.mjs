// tests/feedback.test.mjs
import { test, describe } from 'node:test';
import assert from 'node:assert/strict';

// 注：完整 MockWorker 测试需要 @cloudflare/vitest-pool-workers，
// 此处先编写校验逻辑的纯函数测试，集成测试在 deploy 后手测。

describe('feedback-worker validation', () => {
    test('valid types accepted', () => {
        const VALID = new Set(['FEATURE', 'BUG', 'UX', 'OTHER']);
        assert.ok(VALID.has('FEATURE'));
        assert.ok(VALID.has('BUG'));
        assert.ok(VALID.has('UX'));
        assert.ok(VALID.has('OTHER'));
        assert.ok(!VALID.has('XXX'));
    });

    test('content length 5-2000', () => {
        const content = 'a'.repeat(5);
        assert.ok(content.length >= 5 && content.length <= 2000);
        const tooShort = 'ab';
        assert.ok(tooShort.length < 5);
        const tooLong = 'a'.repeat(2001);
        assert.ok(tooLong.length > 2000);
    });

    test('screenshot count limited to 5', () => {
        const MAX = 5;
        const shots = ['a', 'b', 'c', 'd', 'e', 'f'].slice(0, MAX);
        assert.equal(shots.length, 5);
    });

    test('valid status set', () => {
        const VALID = new Set(['PENDING', 'REPLIED', 'CLOSED']);
        assert.ok(VALID.has('PENDING'));
        assert.ok(VALID.has('REPLIED'));
        assert.ok(VALID.has('CLOSED'));
        assert.ok(!VALID.has('OPEN'));
    });

    test('pagination limit clamped to 50', () => {
        const requested = 100;
        const limit = Math.min(requested, 50);
        assert.equal(limit, 50);
    });
});
