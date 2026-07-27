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
                        async run() { return { success: true }; },
                    };
                },
            };
        },
        async batch(batch) { statements.push(...batch); },
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
