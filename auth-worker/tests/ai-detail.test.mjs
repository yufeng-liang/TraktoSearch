import test from 'node:test';
import assert from 'node:assert/strict';
import { handleAiApi } from '../src/ai/handler.ts';
import { errorResponse } from '../src/util/errors.ts';

function createTestEnv() {
    return {
        DB: {
            prepare() {
                throw new Error('D1 should be exercised by the detail implementation');
            },
        },
        AI_TEST_MODE: true,
        AI_TEST_CACHE: new Map(),
        AI_TEST_QUOTA: new Map(),
    };
}

async function call(path, { method = 'POST', body, friendId = 'friend-1', env = createTestEnv() } = {}) {
    const request = new Request(`https://gateway.test${path}`, {
        method,
        headers: { 'Content-Type': 'application/json' },
        body: body === undefined ? '{}' : JSON.stringify(body),
    });
    try {
        const response = await handleAiApi(
            request,
            env,
            'detail-request',
            path,
            { sub: friendId, device: 'device-1' },
        );
        return { response, json: await response.json() };
    } catch (error) {
        const response = errorResponse(error, 'detail-request');
        return { response, json: await response.json() };
    }
}

function detailRequest() {
    return {
        media: {
            mediaKey: 'movie:tmdb:1',
            mediaType: 'movie',
            title: '冬日示例',
            year: 2024,
            genres: ['Drama'],
            overview: '一段关于日常选择的无剧透简介。',
            publicRating: 8.1,
            mediaIds: { tmdbId: 1 },
        },
        scene: 'UNMARKED',
        environment: {
            localDate: '2026-08-13',
            weekday: 4,
            timeOfDay: 'EVENING',
            season: 'WINTER',
            weatherTag: 'COLD',
        },
    };
}

test('detail response uses the bounded no-spoiler schema', async () => {
    const result = await call('/api/ai/detail/analyze', { body: detailRequest() });
    assert.equal(result.response.status, 200);
    const detail = result.json.data;
    assert.ok(['HIGH', 'MEDIUM', 'LOW'].includes(detail.interestLevel));
    assert.ok(['HIGH', 'MEDIUM', 'LOW'].includes(detail.confidence));
    assert.equal(typeof detail.spoilerFreeSummary, 'string');
    assert.equal(typeof detail.publicRatingInterpretation, 'string');
    assert.equal(typeof detail.watchAdvice, 'string');
    assert.ok(Array.isArray(detail.reasons));
    assert.equal('predictedRating' in detail, false);
    assert.equal('spoiler' in detail, false);
    assert.equal('spoilerFreeIntro' in detail, false);
});

test('watchlist detail returns coarse timing without accepting location fields', async () => {
    const body = detailRequest();
    body.scene = 'WATCHLIST_CONTEXT';
    body.environment.city = '不应上传的城市';
    const result = await call('/api/ai/detail/analyze', { body });
    assert.equal(result.response.status, 400);
    assert.equal(result.json.code, 'INVALID_DETAIL_INPUT');
});

test('recommendation ranking only returns deduplicated candidates with reliable ids', async () => {
    const result = await call('/api/ai/recommendations/rank', {
        body: {
            currentMedia: { mediaKey: 'movie:tmdb:1', mediaIds: { tmdbId: 1 } },
            candidates: [
                { mediaKey: 'movie:tmdb:2', mediaType: 'movie', title: '候选二', mediaIds: { tmdbId: 2 }, genres: ['Drama'] },
                { mediaKey: 'movie:tmdb:2-copy', mediaType: 'movie', title: '重复候选', mediaIds: { tmdbId: 2 }, genres: ['Drama'] },
                { mediaKey: 'movie:tmdb:3', mediaType: 'movie', title: '候选三', mediaIds: { tmdbId: 3 }, genres: ['Comedy'] },
                { mediaKey: 'movie:no-id', mediaType: 'movie', title: '无 ID 候选', genres: ['Drama'] },
            ],
        },
    });
    assert.equal(result.response.status, 200);
    assert.deepEqual(result.json.data.recommendations.map(item => item.mediaKey), ['movie:tmdb:2', 'movie:tmdb:3']);
    assert.equal(result.json.data.recommendations.some(item => item.title === '无 ID 候选'), false);
    assert.equal(result.json.data.recommendations.some(item => item.mediaKey === 'movie:tmdb:1'), false);
    assert.equal('predictedRating' in result.json.data, false);
    assert.equal('spoiler' in result.json.data, false);
});
