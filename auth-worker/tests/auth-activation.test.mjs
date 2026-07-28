import test from 'node:test';
import assert from 'node:assert/strict';
import { isActivationBindingConflict } from '../src/auth/activate.ts';

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
