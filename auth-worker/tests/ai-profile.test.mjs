import test from 'node:test';
import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import { DatabaseSync } from 'node:sqlite';
import { handleAiApi } from '../src/ai/handler.ts';
import { errorResponse } from '../src/util/errors.ts';

function createD1Env() {
    const db = new DatabaseSync(':memory:');
    db.exec([
        '../migrations/0001_init.sql',
        '../migrations/0014_ai_cache.sql',
        '../migrations/0019_ai_profile.sql',
    ].map(path => readFileSync(new URL(path, import.meta.url), 'utf8')).join('\n'));
    db.prepare(`
        INSERT INTO friends (id, nickname, created_at, updated_at)
        VALUES (?, ?, ?, ?)
    `).run('friend-1', 'Test', 1, 1);

    const prepare = sql => {
        const statement = db.prepare(sql);
        return {
            bind(...bindings) {
                return {
                    first() {
                        return statement.get(...bindings) ?? null;
                    },
                    all() {
                        return { results: statement.all(...bindings) };
                    },
                    run() {
                        const result = statement.run(...bindings);
                        return {
                            meta: {
                                changes: Number(result.changes),
                                last_row_id: Number(result.lastInsertRowid),
                            },
                        };
                    },
                };
            },
        };
    };

    return {
        DB: {
            prepare,
            batch(statements) {
                db.exec('BEGIN');
                try {
                    for (const statement of statements) statement.run();
                    db.exec('COMMIT');
                } catch (error) {
                    db.exec('ROLLBACK');
                    throw error;
                }
            },
        },
        close() {
            db.close();
        },
    };
}

function createTestEnv() {
    return {
        DB: {
            prepare() {
                throw new Error('D1 should be exercised by the profile implementation');
            },
        },
        AI_TEST_MODE: true,
        AI_TEST_CACHE: new Map(),
        AI_TEST_QUOTA: new Map(),
    };
}

async function call(path, { method = 'GET', body, friendId = 'friend-1', env = createTestEnv() } = {}) {
    const request = new Request(`https://gateway.test${path}`, {
        method,
        headers: body === undefined ? undefined : { 'Content-Type': 'application/json' },
        body: body === undefined ? undefined : JSON.stringify(body),
    });
    try {
        const response = await handleAiApi(
            request,
            env,
            'profile-request',
            path,
            { sub: friendId, device: 'device-1' },
        );
        return { response, json: await response.json() };
    } catch (error) {
        const response = errorResponse(error, 'profile-request');
        return { response, json: await response.json() };
    }
}

function syncBatch(batchId = 'batch-1') {
    return {
        batchId,
        schemaVersion: 1,
        media: [{
            mediaKey: 'movie:tmdb:101',
            mediaType: 'movie',
            title: '示例电影',
            year: 2024,
            mediaIds: { tmdbId: 101 },
            source: 'local',
            sourceMediaId: 'local-101',
            watched: true,
            rating: 8,
            ratingScale: 10,
            comment: '节奏很好',
            sourceUpdatedAt: 100,
        }],
        behavior: [{
            mediaKey: 'movie:tmdb:101',
            eventDay: '2026-08-13',
            detailDwellBucket: 'TEN_TO_THIRTY_SECONDS',
            searchClickCount: 1,
            playerProgressBuckets: { '25': 1, completed: 1 },
            episodeStartedCount: 0,
            episodeCompletedCount: 0,
            lastEventAt: 100,
        }],
    };
}

test('profile sync is isolated by JWT friend id and idempotent by batch id', async () => {
    const env = createTestEnv();
    const settings = await call('/api/ai/profile/settings', {
        method: 'PUT',
        body: { consentVersion: '1', profileConsent: true, behaviorConsent: true },
        friendId: 'friend-1',
        env,
    });
    assert.equal(settings.response.status, 200);

    const first = await call('/api/ai/profile/sync', {
        method: 'POST',
        body: { ...syncBatch(), friendId: 'friend-should-not-be-used' },
        friendId: 'friend-1',
        env,
    });
    const replay = await call('/api/ai/profile/sync', {
        method: 'POST',
        body: syncBatch(),
        friendId: 'friend-1',
        env,
    });
    const otherUser = await call('/api/ai/profile', { friendId: 'friend-2', env });

    assert.equal(first.response.status, 200);
    assert.equal(first.json.data.accepted, true);
    assert.deepEqual(replay.json.data, first.json.data);
    assert.equal(otherUser.response.status, 200);
    assert.equal(otherUser.json.data.media.length, 0);

    const profile = await call('/api/ai/profile', { friendId: 'friend-1', env });
    assert.equal(profile.json.data.media.length, 1);
    assert.equal(profile.json.data.media[0].mediaKey, 'movie:tmdb:101');
    assert.equal(profile.json.data.behavior.length, 1);
});

test('profile behavior snapshots are idempotent across different batch ids', async () => {
    const env = createTestEnv();
    await call('/api/ai/profile/settings', {
        method: 'PUT',
        body: { consentVersion: '1', profileConsent: true, behaviorConsent: true },
        env,
    });

    const first = await call('/api/ai/profile/sync', {
        method: 'POST',
        body: syncBatch('batch-snapshot-1'),
        env,
    });
    const replayAsNewBatch = await call('/api/ai/profile/sync', {
        method: 'POST',
        body: syncBatch('batch-snapshot-2'),
        env,
    });
    const profile = await call('/api/ai/profile', { env });

    assert.equal(first.response.status, 200);
    assert.equal(replayAsNewBatch.response.status, 200);
    assert.equal(profile.json.data.behavior.length, 1);
    assert.equal(profile.json.data.behavior[0].searchClickCount, 1);
    assert.deepEqual(profile.json.data.behavior[0].playerProgressBuckets, { '25': 1, completed: 1 });
});

test('profile sync rejects media until profile consent is granted', async () => {
    const env = createTestEnv();
    await call('/api/ai/profile/settings', {
        method: 'PUT',
        body: { consentVersion: '1', profileConsent: false, behaviorConsent: true },
        env,
    });

    const result = await call('/api/ai/profile/sync', {
        method: 'POST',
        body: {
            ...syncBatch('batch-profile-consent-required'),
            behavior: [],
        },
        env,
    });
    const profile = await call('/api/ai/profile', { env });

    assert.equal(result.response.status, 403);
    assert.equal(result.json.code, 'PROFILE_CONSENT_REQUIRED');
    assert.equal(profile.json.data.media.length, 0);
    assert.equal(profile.json.data.behavior.length, 0);
});

test('profile sync rejects behavior until behavior consent is granted', async () => {
    const env = createTestEnv();
    await call('/api/ai/profile/settings', {
        method: 'PUT',
        body: { consentVersion: '1', profileConsent: true, behaviorConsent: false },
        env,
    });

    const result = await call('/api/ai/profile/sync', {
        method: 'POST',
        body: {
            batchId: 'batch-behavior-consent-required',
            schemaVersion: 1,
            media: [],
            behavior: syncBatch().behavior,
        },
        env,
    });

    assert.equal(result.response.status, 403);
    assert.equal(result.json.code, 'BEHAVIOR_CONSENT_REQUIRED');
});

test('profile settings reject an unknown consent version and deletion scopes are explicit', async () => {
    const env = createTestEnv();
    const invalid = await call('/api/ai/profile/settings', {
        method: 'PUT',
        body: { consentVersion: 'future-version', profileConsent: true },
        env,
    });
    assert.equal(invalid.response.status, 400);
    assert.equal(invalid.json.code, 'INVALID_CONSENT_VERSION');

    const invalidScope = await call('/api/ai/profile/not-a-scope', {
        method: 'DELETE',
        env,
    });
    assert.equal(invalidScope.response.status, 400);
    assert.equal(invalidScope.json.code, 'INVALID_PROFILE_SCOPE');
});

test('profile sync rejects oversized text and raw location or event payloads', async () => {
    const env = createTestEnv();
    const result = await call('/api/ai/profile/sync', {
        method: 'POST',
        body: {
            ...syncBatch('batch-invalid'),
            media: [{
                ...syncBatch('batch-invalid').media[0],
                overview: 'x'.repeat(5000),
                latitude: 31.2,
            }],
        },
        env,
    });
    assert.equal(result.response.status, 400);
    assert.equal(result.json.code, 'INVALID_PROFILE_INPUT');
});

test('D1 profile sync keeps null event time and omits zero progress buckets', async () => {
    const env = createD1Env();
    try {
        const settings = await call('/api/ai/profile/settings', {
            method: 'PUT',
            body: { consentVersion: '1', profileConsent: true, behaviorConsent: true },
            env,
        });
        const first = await call('/api/ai/profile/sync', {
            method: 'POST',
            body: {
                ...syncBatch('batch-d1-time'),
                behavior: [{
                    ...syncBatch().behavior[0],
                    playerProgressBuckets: {},
                    lastEventAt: 100,
                }],
            },
            env,
        });
        const result = await call('/api/ai/profile/sync', {
            method: 'POST',
            body: {
                ...syncBatch('batch-d1-zero-values'),
                behavior: [{
                    ...syncBatch().behavior[0],
                    playerProgressBuckets: { '25': 0 },
                    lastEventAt: null,
                }],
            },
            env,
        });
        const profile = await call('/api/ai/profile', { env });

        assert.equal(settings.response.status, 200);
        assert.equal(first.response.status, 200);
        assert.equal(result.response.status, 200);
        assert.deepEqual(profile.json.data.behavior[0].playerProgressBuckets, {});
        assert.equal(profile.json.data.behavior[0].lastEventAt, 100);
    } finally {
        env.close();
    }
});
