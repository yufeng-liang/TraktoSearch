import assert from 'node:assert/strict';
import { test } from 'node:test';
import { webcrypto } from 'node:crypto';
import { handleRefresh } from '../src/auth/refresh.ts';
import { sha256 } from '../src/util/crypto.ts';

if (!globalThis.crypto) globalThis.crypto = webcrypto;

const CURRENT_TIME = Math.floor(Date.now() / 1000);

class FakeD1 {
    constructor({ session, tokenHash, attemptId, deviceId, friendId }) {
        this.session = { ...session };
        this.sessions = [{ ...session }];
        this.tokenHash = tokenHash;
        this.attemptId = attemptId;
        this.deviceId = deviceId;
        this.friendId = friendId;
        this.idempotency = null;
        this.auditLogs = [];
        this.challengeConsumed = false;
    }

    prepare(sql) {
        const db = this;
        return {
            sql,
            bindings: [],
            bind(...bindings) {
                this.bindings = bindings;
                return this;
            },
            async all() {
                if (sql.includes('FROM refresh_idempotency')) {
                    return { results: db.idempotency ? [db.idempotency] : [] };
                }
                if (sql.includes('SELECT rs.id, rs.device_id')) {
                    const tokenHash = this.bindings[1];
                    const session = db.sessions.find(
                        item => item.device_id === this.bindings[0] && item.token_hash === tokenHash,
                    );
                    return { results: session ? [{
                        ...session,
                        device_status: 'ACTIVE',
                        public_key: db.publicKey,
                        friend_id: db.friendId,
                        friend_status: 'ACTIVE',
                        friend_expires_at: null,
                    }] : [] };
                }
                if (sql.includes('SELECT COUNT(*) AS count')) {
                    return {
                        results: [{
                            count: db.sessions.filter(item => item.device_id === db.deviceId && item.revoked_at === null).length,
                        }],
                    };
                }
                if (sql.includes('SELECT revoked_at FROM refresh_sessions')) {
                    const item = db.sessions.find(candidate => candidate.id === this.bindings[0]);
                    return { results: item ? [{ revoked_at: item.revoked_at }] : [] };
                }
                return { results: [] };
            },
            async run() {
                if (sql.includes('UPDATE auth_challenges')) {
                    if (db.challengeConsumed) return { meta: { changes: 0 } };
                    db.challengeConsumed = true;
                    return { meta: { changes: 1 } };
                }
                if (sql.includes('INSERT INTO audit_logs')) {
                    db.auditLogs.push({
                        event_type: this.bindings[0],
                        friend_id: this.bindings[1],
                        device_id: this.bindings[2],
                        request_id: this.bindings[3],
                        result: this.bindings[4],
                        error_code: this.bindings[5],
                        detail: this.bindings[6],
                    });
                }
                return { meta: { changes: 1 } };
            },
        };
    }

    async batch(statements) {
        for (const statement of statements) {
            const sql = statement.sql ?? '';
            const bindings = statement.bindings;
            if (sql.includes('INSERT INTO refresh_idempotency')) {
                if (!this.idempotency) {
                    this.idempotency = {
                        attempt_id: bindings[0],
                        device_id: bindings[1],
                        friend_id: bindings[2],
                        token_hash: bindings[3],
                        request_id: bindings[4],
                        response_ciphertext: bindings[5],
                        old_session_id: bindings[6],
                        new_session_id: bindings[7],
                        expires_at: bindings[8],
                    };
                }
                continue;
            }
            if (sql.includes('UPDATE refresh_sessions')) {
                const session = this.sessions.find(item => item.id === bindings[2]);
                if (session && session.revoked_at === null) {
                    session.revoked_at = bindings[0];
                    session.last_used_at = bindings[1];
                }
                continue;
            }
            if (sql.includes('INSERT INTO refresh_sessions')) {
                this.sessions.push({
                    id: bindings[0],
                    device_id: bindings[1],
                    token_hash: bindings[2],
                    expires_at: bindings[3],
                    created_at: bindings[4],
                    revoked_at: null,
                });
                continue;
            }
        }
        return statements.map(() => ({ success: true, meta: { changes: 1 } }));
    }
}

function request(body) {
    return new Request('https://worker.test/api/auth/refresh', {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify(body),
    });
}

test('refresh retry with the same attemptId returns the committed response without replay revocation', async () => {
    const deviceId = 'device-id';
    const friendId = 'friend-id';
    const attemptId = 'attempt-123456';
    const refreshToken = 'old-refresh-token';
    const tokenHash = await sha256(refreshToken);
    const db = new FakeD1({
        session: {
            id: 'old-session',
            device_id: deviceId,
            token_hash: tokenHash,
            expires_at: CURRENT_TIME + 86_400,
            revoked_at: null,
        },
        tokenHash,
        attemptId,
        deviceId,
        friendId,
    });
    const keyPair = await crypto.subtle.generateKey(
        { name: 'ECDSA', namedCurve: 'P-256' },
        true,
        ['sign', 'verify'],
    );
    const publicKey = await crypto.subtle.exportKey('spki', keyPair.publicKey);
    db.publicKey = Buffer.from(publicKey).toString('base64');
    const env = { DB: db, JWT_SIGNING_KEY: 'test-jwt-signing-key' };
    const sign = async (nonce) => Buffer.from(await crypto.subtle.sign(
        { name: 'ECDSA', hash: 'SHA-256' },
        keyPair.privateKey,
        new TextEncoder().encode(nonce),
    )).toString('base64');
    const body = {
        deviceId,
        refreshToken,
        nonce: 'nonce-1',
        signature: await sign('nonce-1'),
        attemptId,
    };

    const firstResponse = await handleRefresh(request(body), env, 'request-1');
    const firstBody = await firstResponse.json();
    assert.equal(firstResponse.status, 200);
    assert.equal(firstBody.code, 'SUCCESS');

    const secondResponse = await handleRefresh(
        request({ ...body, nonce: 'nonce-2', signature: await sign('nonce-2') }),
        env,
        'request-2',
    );
    const secondBody = await secondResponse.json();
    assert.equal(secondResponse.status, 200);
    assert.deepEqual(secondBody.data, firstBody.data);
    assert.equal(db.sessions.filter(item => item.revoked_at === null).length, 1);
    assert.equal(db.auditLogs.filter(item => item.event_type === 'REFRESH_REPLAY').length, 0);
    assert.ok(db.auditLogs.some(item => item.event_type === 'REFRESH_IDEMPOTENT'));
    assert.ok(!db.idempotency.response_ciphertext.includes(firstBody.data.accessToken));
});
