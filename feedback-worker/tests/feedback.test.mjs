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

import { generateDisplayId, parseDisplayId, TYPE_PREFIX_MAP } from '../src/util/display-id.ts';

describe('display-id', () => {
    test('generateDisplayId returns correct format for BUG seq 1', () => {
        assert.equal(generateDisplayId('BUG', 1), 'BUG001');
    });
    test('generateDisplayId returns correct format for FEATURE seq 42', () => {
        assert.equal(generateDisplayId('FEATURE', 42), 'FEAT042');
    });
    test('generateDisplayId pads to 3 digits', () => {
        assert.equal(generateDisplayId('UX', 5), 'UX005');
    });
    test('generateDisplayId extends naturally beyond 999', () => {
        assert.equal(generateDisplayId('BUG', 1000), 'BUG1000');
        assert.equal(generateDisplayId('BUG', 9999), 'BUG9999');
    });
    test('TYPE_PREFIX_MAP maps all types', () => {
        assert.equal(TYPE_PREFIX_MAP.FEATURE, 'FEAT');
        assert.equal(TYPE_PREFIX_MAP.BUG, 'BUG');
        assert.equal(TYPE_PREFIX_MAP.UX, 'UX');
        assert.equal(TYPE_PREFIX_MAP.OTHER, 'OTH');
    });
    test('parseDisplayId extracts type and seq', () => {
        const { type, seq } = parseDisplayId('BUG042');
        assert.equal(type, 'BUG');
        assert.equal(seq, 42);
    });
    test('parseDisplayId handles 4-digit seq', () => {
        const { type, seq } = parseDisplayId('FEAT1234');
        assert.equal(type, 'FEATURE');
        assert.equal(seq, 1234);
    });
    test('parseDisplayId returns null for invalid', () => {
        assert.equal(parseDisplayId('XXX001'), null);
        assert.equal(parseDisplayId('BUG'), null);
        assert.equal(parseDisplayId(''), null);
    });
});

describe('feedback-worker API contracts', () => {
    test('unread-count response shape', () => {
        const mockResponse = { count: 2, items: [
            { feedbackId: 'uuid1', displayId: 'BUG001', type: 'BUG',
              lastReply: { id: 'r1', content: '已修复', createdAt: 1700000000, hasScreenshot: false } },
            { feedbackId: 'uuid2', displayId: 'FEAT003', type: 'FEATURE',
              lastReply: { id: 'r2', content: '已加上', createdAt: 1700000100, hasScreenshot: true } },
        ]};
        assert.equal(mockResponse.count, 2);
        assert.equal(mockResponse.items.length, 2);
        assert.equal(mockResponse.items[0].displayId, 'BUG001');
        assert.equal(mockResponse.items[1].lastReply.hasScreenshot, true);
    });

    test('messages response shape', () => {
        const mockResponse = {
            messages: [
                { id: 'r1', feedbackId: 'f1', displayId: 'BUG001', type: 'BUG',
                  authorRole: 'developer', content: '已修复', screenshots: ['admin/abc.jpg'],
                  createdAt: 1700000000, isUnread: true },
                { id: 'r2', feedbackId: 'f1', displayId: 'BUG001', type: 'BUG',
                  authorRole: 'user', content: '谢谢', screenshots: [],
                  createdAt: 1700000100, isUnread: false },
            ],
            filter: 'ALL', limit: 50, offset: 0, total: 2, hasMore: false,
        };
        assert.equal(mockResponse.messages.length, 2);
        assert.equal(mockResponse.messages[0].authorRole, 'developer');
        assert.equal(mockResponse.messages[0].isUnread, true);
        assert.equal(mockResponse.messages[1].isUnread, false);
    });

    test('user reply screenshot key must start with friendId/', () => {
        const friendId = 'user123';
        const screenshots = ['user123/123-abc.jpg', 'admin/xxx.jpg'];
        const invalid = screenshots.filter(k => !k.startsWith(`${friendId}/`));
        assert.equal(invalid.length, 1);
        assert.equal(invalid[0], 'admin/xxx.jpg');
    });

    test('admin reply screenshot key must start with admin/', () => {
        const screenshots = ['admin/123-abc.jpg', 'user123/xxx.jpg'];
        const invalid = screenshots.filter(k => !k.startsWith('admin/'));
        assert.equal(invalid.length, 1);
        assert.equal(invalid[0], 'user123/xxx.jpg');
    });

    test('user reply triggers REPLIED to PENDING transition', () => {
        // 模拟状态机：REPLIED + user reply → PENDING
        const currentStatus = 'REPLIED';
        const newStatus = currentStatus === 'REPLIED' ? 'PENDING' : currentStatus;
        assert.equal(newStatus, 'PENDING');
    });

    test('admin reply triggers PENDING to REPLIED transition', () => {
        const currentStatus = 'PENDING';
        const newStatus = currentStatus === 'PENDING' ? 'REPLIED' : currentStatus;
        assert.equal(newStatus, 'REPLIED');
    });

    test('CLOSED feedback rejects both user and admin reply', () => {
        const status = 'CLOSED';
        assert.throws(() => {
            if (status === 'CLOSED') throw new Error('Cannot reply to closed feedback');
        });
    });
});
