import test from 'node:test';
import assert from 'node:assert/strict';
import { handleActivate, isActivationBindingConflict } from '../src/auth/activate.ts';

function createActivationDb() {
    let batchCalled = false;
    const invite = {
        id: 'invite-1',
        friend_id: 'friend-1',
        kind: 'ACTIVATION',
        expires_at: Math.floor(Date.now() / 1000) + 3600,
        used_at: null,
        revoked_at: null,
        device_id: null,
        code_mask: 'ABCD****WXYZ',
        friend_status: 'ACTIVE',
        max_devices: 2,
        friend_expires_at: null,
    };
    const continuityDevice = {
        id: 'device-1',
        friend_id: 'friend-1',
        status: 'ACTIVE',
        deleted_at: null,
    };

    const db = {
        prepare(sql) {
            return {
                bind(...params) {
                    return {
                        async all() {
                            if (sql.includes('FROM invites i')) return { results: [invite] };
                            if (sql.includes('WHERE public_key = ?')) return { results: [] };
                            if (sql.includes('WHERE recovery_id_hmac = ?')) return { results: [continuityDevice] };
                            if (sql.includes('INSERT INTO audit_logs')) return { results: [] };
                            throw new Error(`Unexpected all query: ${sql}`);
                        },
                        async first() {
                            if (sql.includes('COUNT(*) as count')) return { count: 0 };
                            throw new Error(`Unexpected first query: ${sql}`);
                        },
                        async run() {
                            if (sql.includes('INSERT INTO audit_logs')) return { success: true };
                            throw new Error(`Unexpected run query: ${sql}`);
                        },
                    };
                },
            };
        },
        async batch() {
            batchCalled = true;
            return [];
        },
        wasBatchCalled() {
            return batchCalled;
        },
    };
    return db;
}

test('active device cannot be activated again with a normal invite', () => {
    assert.equal(
        isActivationBindingConflict({ status: 'ACTIVE', deleted_at: null }, 'ACTIVATION'),
        true,
    );
});

test('revoked device can be reactivated with a normal invite', () => {
    assert.equal(
        isActivationBindingConflict({ status: 'REVOKED', deleted_at: null }, 'ACTIVATION'),
        false,
    );
});

test('soft-deleted device can be restored by reactivation', () => {
    assert.equal(
        isActivationBindingConflict({ status: 'REVOKED', deleted_at: 1_000 }, 'ACTIVATION'),
        false,
    );
});

test('normal activation rejects a new key on an already active installation', async () => {
    const db = createActivationDb();
    const request = new Request('https://example.test/api/auth/activate', {
        method: 'POST',
        body: JSON.stringify({
            inviteCode: 'ABCD1234WXYZ',
            publicKey: 'new-public-key',
            androidId: 'same-installation',
        }),
        headers: { 'Content-Type': 'application/json' },
    });

    await assert.rejects(
        () => handleActivate(request, {
            DB: db,
            JWT_SIGNING_KEY: 'jwt-secret',
            DEVICE_RECOVERY_HMAC_KEY: 'recovery-secret',
        }, 'request-1'),
        error => error?.code === 'DEVICE_ALREADY_BOUND',
    );
    assert.equal(db.wasBatchCalled(), false);
});
