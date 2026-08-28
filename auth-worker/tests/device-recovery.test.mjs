import test from 'node:test';
import assert from 'node:assert/strict';
import { hmacDeviceContinuityId } from '../src/util/crypto.ts';
import { handleRecover } from '../src/auth/recover.ts';

function createKv() {
    const values = new Map();
    return {
        values,
        async get(key) { return values.get(key) ?? null; },
        async put(key, value) { values.set(key, value); },
        async delete(key) { values.delete(key); },
    };
}

function createDb() {
    const statements = [];
    return {
        statements,
        prepare(sql) {
            return {
                bind(...bindings) {
                    return {
                        sql,
                        bindings,
                        async first() { return null; },
                        async all() { return { results: [] }; },
                        async run() {
                            // 限流桶条件 UPSERT 默认放行（changes=1），否则单测全被 429 拦下；
                            // 其余写语句由 createRecoveryDb 覆盖。
                            if (sql.includes('INSERT INTO rate_limits')) {
                                return { success: true, meta: { changes: 1 } };
                            }
                            return { success: true, meta: { changes: 0 } };
                        },
                    };
                },
            };
        },
        async batch(batch) { statements.push(...batch); },
    };
}

function createRecoveryDb(match) {
    const db = createDb();
    db.prepare = function prepare(sql) {
        return {
            bind(...bindings) {
                return {
                    sql,
                    bindings,
                    async first() {
                        if (sql.includes('SELECT id FROM devices WHERE public_key = ?')) return null;
                        return null;
                    },
                    async all() {
                        if (sql.includes('SELECT d.id as device_id')) return { results: [match] };
                        return { results: [] };
                    },
                    async run() {
                        // 限流桶条件 UPSERT 放行，避免单测被 429 拦下
                        if (sql.includes('INSERT INTO rate_limits')) {
                            return { success: true, meta: { changes: 1 } };
                        }
                        if (sql.includes('UPDATE auth_challenges')) {
                            return { success: true, meta: { changes: 1 } };
                        }
                        return { success: true, meta: { changes: 0 } };
                    },
                };
            },
        };
    };
    return db;
}

async function createSignedRecoveryRequest(androidId = 'android-id') {
    const keyPair = await crypto.subtle.generateKey(
        { name: 'ECDSA', namedCurve: 'P-256' },
        true,
        ['sign', 'verify'],
    );
    const publicKey = Buffer.from(await crypto.subtle.exportKey('spki', keyPair.publicKey)).toString('base64');
    const nonce = 'recovery-nonce';
    const signature = await crypto.subtle.sign(
        { name: 'ECDSA', hash: 'SHA-256' },
        keyPair.privateKey,
        new TextEncoder().encode(nonce),
    );
    return {
        androidId,
        publicKey,
        nonce,
        signature: Buffer.from(signature).toString('base64'),
        deviceName: 'Test device',
        appVersion: '3.6.0',
        packageName: 'com.tracktosearch',
    };
}

test('device continuity HMAC is stable and secret-dependent', async () => {
    const first = await hmacDeviceContinuityId('android-id', 'secret-a');
    const repeated = await hmacDeviceContinuityId('android-id', 'secret-a');
    const differentSecret = await hmacDeviceContinuityId('android-id', 'secret-b');

    assert.equal(first, repeated);
    assert.notEqual(first, differentSecret);
    assert.match(first, /^[0-9a-f]{64}$/);
});

test('recovery rejects a missing challenge before touching the database', async () => {
    const db = createDb();
    const kv = createKv();

    await assert.rejects(
        () => handleRecover(
            new Request('https://gateway.test/api/auth/recover', {
                method: 'POST',
                body: JSON.stringify({
                    androidId: 'raw-id',
                    publicKey: 'invalid',
                    nonce: 'missing',
                    signature: 'invalid',
                    packageName: 'com.tracktosearch',
                }),
            }),
            { DB: db, KV: kv, JWT_SIGNING_KEY: 'jwt-secret', DEVICE_RECOVERY_HMAC_KEY: 'hmac-secret' },
            'request-1',
        ),
        error => error.code === 'INVALID_SIGNATURE',
    );
    assert.equal(db.statements.length, 0);
});

test('recovery returns a new session for exactly one active continuity match', async () => {
    const requestBody = await createSignedRecoveryRequest();
    const db = createRecoveryDb({
        device_id: 'device-1',
        friend_id: 'friend-1',
        device_status: 'ACTIVE',
        friend_status: 'ACTIVE',
        friend_expires_at: null,
    });
    const response = await handleRecover(
        new Request('https://gateway.test/api/auth/recover', {
            method: 'POST',
            body: JSON.stringify(requestBody),
        }),
        { DB: db, KV: createKv(), JWT_SIGNING_KEY: 'jwt-secret', DEVICE_RECOVERY_HMAC_KEY: 'hmac-secret' },
        'request-success',
    );

    assert.equal(response.status, 200);
    const body = await response.json();
    assert.equal(body.code, 'SUCCESS');
    assert.equal(body.data.deviceId, 'device-1');
    assert.equal(db.statements.length, 4);
});

test('recovery rejects a continuity match that is no longer active', async () => {
    const requestBody = await createSignedRecoveryRequest();
    const db = createRecoveryDb({
        device_id: 'revoked-device',
        friend_id: 'friend-1',
        device_status: 'REVOKED',
        friend_status: 'ACTIVE',
        friend_expires_at: null,
    });

    await assert.rejects(
        () => handleRecover(
            new Request('https://gateway.test/api/auth/recover', {
                method: 'POST',
                body: JSON.stringify(requestBody),
            }),
            { DB: db, KV: createKv(), JWT_SIGNING_KEY: 'jwt-secret', DEVICE_RECOVERY_HMAC_KEY: 'hmac-secret' },
            'request-revoked',
        ),
        error => error.code === 'RECOVERY_NOT_FOUND',
    );
    assert.equal(db.statements.length, 0);
});
