import test from 'node:test';
import assert from 'node:assert/strict';
import {
    buildAuthorizationCodePayload,
    buildRefreshTokenPayload,
    classifyTraktOAuthError,
} from '../src/proxy/trakt-token.ts';

test('refresh token payload includes the registered redirect URI', () => {
    assert.deepEqual(
        buildRefreshTokenPayload('refresh-token', 'client-id', 'client-secret'),
        {
            refresh_token: 'refresh-token',
            client_id: 'client-id',
            client_secret: 'client-secret',
            redirect_uri: 'tracktosearch://oauth/callback',
            grant_type: 'refresh_token',
        },
    );
});

test('OAuth errors are classified without exposing token data', () => {
    assert.deepEqual(
        classifyTraktOAuthError(400, { error: 'invalid_grant', error_description: 'invalid code' }),
        { code: 'TRAKT_AUTH_CODE_INVALID', message: 'Trakt authorization code is invalid or expired' },
    );
    assert.deepEqual(
        classifyTraktOAuthError(401, { error: 'invalid_client' }),
        { code: 'TRAKT_CLIENT_INVALID', message: 'Trakt client configuration was rejected' },
    );
});

test('authorization code payload keeps the exact redirect URI', () => {
    assert.deepEqual(
        buildAuthorizationCodePayload('code', 'client-id', 'client-secret'),
        {
            code: 'code',
            client_id: 'client-id',
            client_secret: 'client-secret',
            redirect_uri: 'tracktosearch://oauth/callback',
            grant_type: 'authorization_code',
        },
    );
});
