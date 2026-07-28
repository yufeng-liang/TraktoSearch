import test from 'node:test';
import assert from 'node:assert/strict';
import {
    createAuthChallenge,
    consumeAuthChallenge,
} from '../src/auth/challenge.ts';

function createDb(changes) {
    const statements = [];
    return {
        statements,
        prepare(sql) {
            return {
                bind(...bindings) {
                    return {
                        sql,
                        bindings,
                        async run() {
                            statements.push({ sql, bindings });
                            return {
                                success: true,
                                meta: { changes },
                            };
                        },
                    };
                },
            };
        },
    };
}

test('challenge is persisted in D1 instead of eventually consistent KV', async () => {
    const db = createDb(1);

    const { nonce, expiresAt } = await createAuthChallenge(db, 'REFRESH', 'device-id', 1_000);

    assert.match(nonce, /^[A-Za-z0-9_-]+$/);
    assert.equal(expiresAt, 1_000 + 600);
    assert.equal(db.statements.length, 1);
    assert.match(db.statements[0].sql, /INSERT INTO auth_challenges/);
    assert.equal(db.statements[0].bindings[1], 'REFRESH');
    assert.equal(db.statements[0].bindings[2], 'device-id');
    assert.equal(db.statements[0].bindings[3], 1_000 + 600);
});

test('challenge can be consumed exactly once', async () => {
    const firstDb = createDb(1);
    const secondDb = createDb(0);

    assert.equal(
        await consumeAuthChallenge(firstDb, 'nonce', 'REFRESH', 'device-id', 1_001),
        true,
    );
    assert.equal(
        await consumeAuthChallenge(secondDb, 'nonce', 'REFRESH', 'device-id', 1_001),
        false,
    );
    assert.match(firstDb.statements[0].sql, /consumed_at IS NULL/);
    assert.match(firstDb.statements[0].sql, /expires_at >=/);
});
