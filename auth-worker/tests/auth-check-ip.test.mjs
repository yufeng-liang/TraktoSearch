import test from 'node:test';
import assert from 'node:assert/strict';
import { handleCheck } from '../src/auth/check.ts';

// 捕获 check 写入 friend_ip_logs 的绑定参数
function createCheckDb() {
    const statements = [];
    const db = {
        prepare(sql) {
            return {
                bind(...bindings) {
                    const statement = {
                        sql,
                        bindings,
                        async all() {
                            if (sql.includes('FROM friends f')) {
                                return {
                                    results: [{
                                        friend_id: 'friend-1',
                                        nickname: '宇锋',
                                        friend_status: 'ACTIVE',
                                        friend_expires_at: null,
                                        recovery_id_hmac: 'existing-hmac',
                                        device_id: 'device-1',
                                        device_status: 'ACTIVE',
                                    }],
                                };
                            }
                            return { results: [] };
                        },
                    };
                    return statement;
                },
            };
        },
        async batch(batchStatements) {
            statements.push(...batchStatements);
            return batchStatements.map(() => ({ meta: { changes: 1 } }));
        },
    };

    return {
        db,
        recordedIp() {
            const insert = statements.find(statement => statement.sql.includes('INSERT INTO friend_ip_logs'));
            return insert ? insert.bindings[1] : null;
        },
        recordedCountry() {
            const insert = statements.find(statement => statement.sql.includes('INSERT INTO friend_ip_logs'));
            return insert ? insert.bindings[2] : null;
        },
    };
}

function checkRequest(url, headers = {}, cf = undefined) {
    const request = new Request(url, {
        method: 'POST',
        body: JSON.stringify({}),
        headers: { 'Content-Type': 'application/json', ...headers },
    });
    if (cf !== undefined) request.cf = cf;
    return request;
}

const env = {
    DB: null,
    DEVICE_RECOVERY_HMAC_KEY: 'recovery-secret',
};

test('public check ignores a client supplied X-Real-IP and records the Cloudflare address', async () => {
    const { db, recordedIp } = createCheckDb();
    await handleCheck(
        checkRequest('https://auth.example.workers.dev/api/auth/check', {
            'X-Real-IP': '1.2.3.4',
            'CF-Connecting-IP': '9.9.9.9',
        }),
        { ...env, DB: db },
        'request-1',
        { sub: 'friend-1', device: 'device-1' },
    );
    assert.equal(recordedIp(), '9.9.9.9');
});

test('gateway service binding check trusts the forwarded X-Real-IP', async () => {
    const { db, recordedIp } = createCheckDb();
    await handleCheck(
        checkRequest('https://gateway.internal/api/auth/check', {
            'X-Real-IP': '1.2.3.4',
            'CF-Connecting-IP': '9.9.9.9',
        }),
        { ...env, DB: db },
        'request-1',
        { sub: 'friend-1', device: 'device-1' },
    );
    assert.equal(recordedIp(), '1.2.3.4');
});

test('public check ignores a client supplied X-Client-Geo payload', async () => {
    const { db, recordedCountry } = createCheckDb();
    await handleCheck(
        checkRequest(
            'https://auth.example.workers.dev/api/auth/check',
            {
                'CF-Connecting-IP': '9.9.9.9',
                'X-Client-Geo': JSON.stringify({ country: 'XX', city: 'Fakeville' }),
            },
            { country: 'CN', city: 'Kunming' },
        ),
        { ...env, DB: db },
        'request-1',
        { sub: 'friend-1', device: 'device-1' },
    );
    assert.equal(recordedCountry(), 'CN');
});

test('gateway service binding check trusts the forwarded X-Client-Geo payload', async () => {
    const { db, recordedCountry } = createCheckDb();
    await handleCheck(
        checkRequest(
            'https://gateway.internal/api/auth/check',
            {
                'X-Real-IP': '1.2.3.4',
                'X-Client-Geo': JSON.stringify({ country: 'JP', city: 'Tokyo' }),
            },
            { country: 'CN', city: 'Kunming' },
        ),
        { ...env, DB: db },
        'request-1',
        { sub: 'friend-1', device: 'device-1' },
    );
    assert.equal(recordedCountry(), 'JP');
});

test('check skips the IP log when no client address is available', async () => {
    const { db, recordedIp } = createCheckDb();
    await handleCheck(
        checkRequest('https://auth.example.workers.dev/api/auth/check'),
        { ...env, DB: db },
        'request-1',
        { sub: 'friend-1', device: 'device-1' },
    );
    assert.equal(recordedIp(), null);
});
