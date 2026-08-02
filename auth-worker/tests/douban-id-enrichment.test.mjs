import assert from 'node:assert/strict';
import test from 'node:test';

import {
    applyCachedDoubanMappings,
    buildDoubanMappingKey,
    chooseStrictTmdbMovie,
    enrichDoubanItems,
} from '../src/proxy/douban-id-enrichment.ts';

test('uses the Douban subject ID and matcher version as the mapping key', () => {
    assert.equal(
        buildDoubanMappingKey('1295644', 'movie'),
        'douban:id:1295644:movie:v1',
    );
});

test('chooses the only exact title and year TMDB candidate', () => {
    const candidate = chooseStrictTmdbMovie(
        { title: 'Titanic', year: '1997' },
        [
            { id: 597, title: 'Titanic', original_title: 'Titanic', release_date: '1997-11-19' },
            { id: 11349, title: 'Titanic', original_title: 'Titanic', release_date: '1953-04-05' },
        ],
    );

    assert.equal(candidate?.id, 597);
});

test('rejects a same-title candidate when the year conflicts', () => {
    const candidate = chooseStrictTmdbMovie(
        { title: 'Titanic', year: '1997' },
        [{ id: 11349, title: 'Titanic', original_title: 'Titanic', release_date: '1953-04-05' }],
    );

    assert.equal(candidate, null);
});

test('rejects ambiguous same-title candidates without a year', () => {
    const candidate = chooseStrictTmdbMovie(
        { title: 'The Departed' },
        [
            { id: 1422, title: 'The Departed', original_title: 'The Departed', release_date: '2006-10-06' },
            { id: 9999, title: 'The Departed', original_title: 'The Departed', release_date: '1990-01-01' },
        ],
    );

    assert.equal(candidate, null);
});

test('applies a matched KV mapping and only returns unmapped items as pending', async () => {
    const mappingKey = buildDoubanMappingKey('1', 'movie');
    const kv = {
        async get(key) {
            if (key === mappingKey) {
                return JSON.stringify({
                    status: 'matched',
                    tmdbId: 597,
                    traktId: 10,
                    imdbId: 'tt0120338',
                    mediaType: 'movie',
                    matcherVersion: 1,
                });
            }
            return null;
        },
    };

    const result = await applyCachedDoubanMappings([
        { id: '1', title: 'Titanic' },
        { id: '2', title: 'A New Movie' },
    ], kv);

    assert.deepEqual(result.items[0], {
        id: '1',
        title: 'Titanic',
        tmdbId: 597,
        traktId: 10,
        imdbId: 'tt0120338',
        mediaType: 'movie',
    });
    assert.deepEqual(result.pending.map((item) => item.id), ['2']);
});

test('resolves a pending item through TMDB and Trakt and persists the mapping', async () => {
    const values = new Map();
    const kv = {
        async get(key) { return values.get(key) ?? null; },
        async put(key, value) { values.set(key, value); },
    };
    const originalFetch = globalThis.fetch;
    const requests = [];
    globalThis.fetch = async (input) => {
        const url = String(input);
        requests.push(url);
        if (url.includes('/3/search/movie')) {
            return Response.json({
                results: [{ id: 597, title: 'Titanic', original_title: 'Titanic', release_date: '1997-11-19' }],
            });
        }
        if (url.includes('/search/tmdb/597')) {
            return Response.json([{
                type: 'movie',
                movie: { ids: { trakt: 10, tmdb: 597, imdb: 'tt0120338' } },
            }]);
        }
        throw new Error(`Unexpected upstream URL: ${url}`);
    };

    try {
        const result = await enrichDoubanItems(
            [{ id: '1', title: 'Titanic', year: '1997' }],
            { KV: kv, TMDB_API_KEY: 'tmdb-key', TRAKT_CLIENT_ID: 'trakt-key' },
        );

        assert.equal(result[0].tmdbId, 597);
        assert.equal(result[0].traktId, 10);
        assert.equal(result[0].imdbId, 'tt0120338');
        assert.equal(result[0].mediaType, 'movie');
        assert.ok(requests.some((url) => url.includes('/3/search/movie')));
        assert.ok(requests.some((url) => url.includes('/search/tmdb/597')));

        const stored = JSON.parse(values.get(buildDoubanMappingKey('1', 'movie')));
        assert.equal(stored.status, 'matched');
        assert.equal(stored.tmdbId, 597);
        assert.equal(stored.traktId, 10);
    } finally {
        globalThis.fetch = originalFetch;
    }
});
