import test from 'node:test';
import assert from 'node:assert/strict';
import {
    handleLegalRequest,
    normalizeLegalRequest,
    LEGAL_REQUEST_TYPES,
} from '../src/legal-requests.ts';

test('normalizes a legal request and keeps the optional account reference bounded', () => {
    assert.deepEqual(
        normalizeLegalRequest({
            type: 'PRIVACY_DELETION',
            email: '  USER@Example.COM ',
            accountReference: '  小明  ',
            description: '请删除我提交的账户和同步数据。',
            acknowledged: true,
        }),
        {
            type: 'PRIVACY_DELETION',
            email: 'user@example.com',
            accountReference: '小明',
            description: '请删除我提交的账户和同步数据。',
        },
    );
    assert.ok(LEGAL_REQUEST_TYPES.includes('COPYRIGHT_NOTICE'));
});

test('rejects a legal request without acknowledgement or with invalid input', () => {
    assert.throws(
        () => normalizeLegalRequest({
            type: 'PRIVACY_ACCESS',
            email: 'user@example.com',
            description: '请查阅我的数据。',
            acknowledged: false,
        }),
        error => error?.code === 'LEGAL_REQUEST_ACK_REQUIRED',
    );
    assert.throws(
        () => normalizeLegalRequest({
            type: 'UNKNOWN',
            email: 'not-an-email',
            description: 'x',
            acknowledged: true,
        }),
        error => error?.code === 'INVALID_REQUEST',
    );
});

test('stores a legal request and returns only an opaque request id', async () => {
    const statements = [];
    const db = {
        prepare(sql) {
            return {
                bind(...bindings) {
                    statements.push({ sql, bindings });
                    return {
                        async run() { return { meta: { changes: 1 } }; },
                    };
                },
            };
        },
    };
    const scheduled = [];
    const response = await handleLegalRequest(
        new Request('https://example.com/api/legal-requests', {
            method: 'POST',
            headers: {
                'content-type': 'application/json',
                'CF-Connecting-IP': '203.0.113.5',
            },
            body: JSON.stringify({
                type: 'COPYRIGHT_NOTICE',
                email: 'rights@example.com',
                accountReference: '',
                description: '请核查页面中使用的素材并联系我处理权利问题。',
                acknowledged: true,
            }),
        }),
        {
            DB: db,
            KV: undefined,
            ADMIN_EMAIL: undefined,
            EMAIL_FROM: undefined,
            PUBLIC_SITE_ORIGIN: 'https://tracktosearch.pages.dev',
        },
        'request-id',
        { waitUntil(promise) { scheduled.push(promise); } },
    );

    assert.equal(response.status, 200);
    const payload = await response.json();
    assert.equal(payload.data.email, undefined);
    assert.match(payload.data.id, /^[0-9a-f-]{36}$/);
    // IP 与 email 限流各 1 条 D1 条件 UPSERT + 业务 2 条 = 4
    assert.equal(statements.length, 4);
    assert.match(statements[2].sql, /INSERT INTO legal_requests/);
    assert.match(statements[3].sql, /INSERT INTO audit_logs/);
    assert.equal(scheduled.length, 0);
});
