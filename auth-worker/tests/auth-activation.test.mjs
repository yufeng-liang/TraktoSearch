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
        async batch(batchStatements) {
            batchCalled = true;
            // 每条语句都报告写入一行，模拟核销成功的正常路径
            return (batchStatements || []).map(() => ({ meta: { changes: 1 } }));
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

// 批次内各语句的 changes 可调，用于模拟并发落败方
function createRaceDb(options = {}) {
    const {
        batchChanges = [1, 1, 1, 1, 1],
        inviteState = {
            used_at: null,
            revoked_at: null,
            expires_at: Math.floor(Date.now() / 1000) + 3600,
            friend_status: 'ACTIVE',
            friend_expires_at: null,
            max_devices: 2,
        },
        activeDevices = 0,
    } = options;

    const batchedSql = [];
    const db = {
        prepare(sql) {
            return {
                bind(...params) {
                    return {
                        sql,
                        bindings: params,
                        async all() {
                            // handleActivate 主查询：SELECT i.id, ..., i.code_hash
                            if (sql.includes('i.code_hash')) {
                                return {
                                    results: [{
                                        id: 'invite-1',
                                        friend_id: 'friend-1',
                                        kind: 'ACTIVATION',
                                        expires_at: inviteState.expires_at,
                                        used_at: null,
                                        revoked_at: null,
                                        device_id: null,
                                        code_mask: 'ABCD****WXYZ',
                                        friend_status: inviteState.friend_status,
                                        max_devices: inviteState.max_devices,
                                        friend_expires_at: inviteState.friend_expires_at,
                                    }],
                                };
                            }
                            return { results: [] };
                        },
                        async first() {
                            if (sql.includes('COUNT(*) as count')) return { count: activeDevices };
                            // throwActivationRaceFailure 重新读取邀请码状态
                            if (sql.includes('i.used_at, i.revoked_at')) return inviteState;
                            return null;
                        },
                        async run() {
                            return { success: true, meta: { changes: 1 } };
                        },
                    };
                },
            };
        },
        async batch(statements) {
            batchedSql.push(...statements.map(statement => statement.sql));
            return statements.map((_, index) => ({ meta: { changes: batchChanges[index] ?? 1 } }));
        },
        get batchedSql() {
            return batchedSql;
        },
    };
    return db;
}

function activationRequest() {
    return new Request('https://example.test/api/auth/activate', {
        method: 'POST',
        body: JSON.stringify({ inviteCode: 'ABCD1234WXYZ', publicKey: 'public-key' }),
        headers: { 'Content-Type': 'application/json' },
    });
}

test('activation consumes the invite first and guards every write on that consumption', async () => {
    const db = createRaceDb();
    const response = await handleActivate(activationRequest(), {
        DB: db,
        JWT_SIGNING_KEY: 'jwt-secret',
        DEVICE_RECOVERY_HMAC_KEY: 'recovery-secret',
    }, 'request-1');

    assert.equal(response.status, 200);
    const [consumeSql, deviceSql, , sessionSql, auditSql] = db.batchedSql;
    // 第一条语句就是邀请码核销，且把已用/撤销/过期/设备上限全部折进 WHERE
    assert.match(consumeSql, /UPDATE invites\s+SET used_at = \?/);
    assert.match(consumeSql, /AND used_at IS NULL/);
    assert.match(consumeSql, /AND revoked_at IS NULL/);
    assert.match(consumeSql, /AND expires_at >= \?/);
    assert.match(consumeSql, /< \?/);
    // 后续写入一律带“本批次已核销”守卫，落败方会整批空转
    for (const sql of [deviceSql, sessionSql, auditSql]) {
        assert.match(sql, /EXISTS \(SELECT 1 FROM invites WHERE id = \? AND used_at = \?\)/);
    }
});

test('concurrent activation loser reports INVITE_ALREADY_USED without writing a device', async () => {
    const db = createRaceDb({
        // 并发的另一方先抢到核销，本请求第一条语句 changes = 0
        batchChanges: [0, 1, 1, 1, 1],
        inviteState: {
            used_at: 1_700_000_000,
            revoked_at: null,
            expires_at: Math.floor(Date.now() / 1000) + 3600,
            friend_status: 'ACTIVE',
            friend_expires_at: null,
            max_devices: 2,
        },
    });

    await assert.rejects(
        () => handleActivate(activationRequest(), {
            DB: db,
            JWT_SIGNING_KEY: 'jwt-secret',
            DEVICE_RECOVERY_HMAC_KEY: 'recovery-secret',
        }, 'request-1'),
        error => error?.code === 'INVITE_ALREADY_USED',
    );
});

test('concurrent activation loser reports DEVICE_LIMIT_REACHED when the friend is full', async () => {
    const db = createRaceDb({
        batchChanges: [0, 1, 1, 1, 1],
        inviteState: {
            used_at: null,
            revoked_at: null,
            expires_at: Math.floor(Date.now() / 1000) + 3600,
            friend_status: 'ACTIVE',
            friend_expires_at: null,
            max_devices: 1,
        },
        activeDevices: 1,
    });

    await assert.rejects(
        () => handleActivate(activationRequest(), {
            DB: db,
            JWT_SIGNING_KEY: 'jwt-secret',
            DEVICE_RECOVERY_HMAC_KEY: 'recovery-secret',
        }, 'request-1'),
        error => error?.code === 'DEVICE_LIMIT_REACHED',
    );
});

test('activation refuses to issue tokens when a device or session write is incomplete', async () => {
    const db = createRaceDb({ batchChanges: [1, 1, 0, 0, 1] });

    await assert.rejects(
        () => handleActivate(activationRequest(), {
            DB: db,
            JWT_SIGNING_KEY: 'jwt-secret',
            DEVICE_RECOVERY_HMAC_KEY: 'recovery-secret',
        }, 'request-1'),
        error => error?.code === 'ACTIVATION_INCOMPLETE',
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
