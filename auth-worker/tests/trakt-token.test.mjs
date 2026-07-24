import test from 'node:test';
import assert from 'node:assert/strict';
import {
    buildAuthorizationCodePayload,
    buildRefreshTokenPayload,
    buildTraktApiHeaders,
    buildTraktProxyUrl,
    classifyTraktOAuthError,
    readTraktProxyBody,
} from '../src/proxy/trakt-token.ts';
import { buildTmdbAuth } from '../src/proxy/tmdb-token.ts';
import { buildDoubanHeaders } from '../src/proxy/douban-token.ts';

test('TMDB v4 token is sent as a Bearer credential', () => {
    assert.deepEqual(buildTmdbAuth('eyJheader.payload.signature'), {
        headers: { Authorization: 'Bearer eyJheader.payload.signature' },
    });
});

test('legacy TMDB API key remains a query credential', () => {
    assert.deepEqual(buildTmdbAuth('legacy-api-key'), {
        headers: {},
        apiKey: 'legacy-api-key',
    });
});

test('Douban hot-list proxy uses the original header credential', () => {
    assert.deepEqual(buildDoubanHeaders('douban-key')['X-API-Key'], 'douban-key');
});

test('Trakt API proxy headers include a descriptive user agent', () => {
    assert.equal(
        buildTraktApiHeaders('access-token', 'client-id')['User-Agent'],
        'TrackToSearch/3.0',
    );
});

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

test('Trakt proxy preserves Retrofit query parameters', () => {
    assert.equal(
        buildTraktProxyUrl(
            'https://gateway.test/api/trakt/sync/history?type=movies&extended=full&page=2&limit=200',
            'sync/history',
        ),
        'https://api.trakt.tv/sync/history?type=movies&extended=full&page=2&limit=200',
    );
});

test('Trakt proxy buffers a request body for an automatic retry', async () => {
    const request = new Request('https://gateway.test/api/trakt/sync/watchlist', {
        method: 'POST',
        body: '{"movies":[{"ids":{"tmdb":123}}]}',
    });
    const body = await readTraktProxyBody(request);

    assert.equal(await new Response(body).text(), '{"movies":[{"ids":{"tmdb":123}}]}');
    assert.equal(await new Response(body).text(), '{"movies":[{"ids":{"tmdb":123}}]}');
});
