import test from 'node:test';
import assert from 'node:assert/strict';
import {
    KeyPool,
    fetchWithKeyRotation,
    parseSecretKeys,
} from '../src/util/key-pool.ts';

function createKv() {
    const values = new Map();
    return {
        values,
        async get(key) {
            return values.get(key) ?? null;
        },
        async put(key, value) {
            values.set(key, value);
        },
    };
}

test('secret parser accepts one key, JSON arrays, commas and newlines', () => {
    assert.deepEqual(parseSecretKeys(' first , second\nthird,first '), ['first', 'second', 'third']);
    assert.deepEqual(parseSecretKeys('["first", "second", "first"]'), ['first', 'second']);
});

test('secret parser filters empty and non-string JSON values', () => {
    assert.deepEqual(parseSecretKeys('["first", "", 7, null, " second "]'), ['first', 'second']);
    assert.deepEqual(parseSecretKeys('   '), []);
});

test('a single Secret keeps the original one-key behavior', async () => {
    const pool = new KeyPool('tmdb', 'single-key', createKv());
    assert.deepEqual((await pool.getCandidates()).map((candidate) => candidate.key), ['single-key']);
});

test('missing Secret fails closed before any upstream request', async () => {
    const pool = new KeyPool('tmdb', ' ', createKv());
    await assert.rejects(
        () => pool.getCandidates(),
        (error) => error.code === 'UPSTREAM_CONFIG_ERROR' && error.statusCode === 500,
    );
});

test('key state persists only fingerprints and rotates after 429', async () => {
    const kv = createKv();
    const pool = new KeyPool('tmdb', 'first,second', kv, () => 1_000);
    const candidates = await pool.getCandidates();

    assert.deepEqual(candidates.map((candidate) => candidate.key), ['first', 'second']);
    await pool.markFailure(candidates[0], 429);

    const stored = [...kv.values.values()][0];
    assert.equal(stored.includes('first'), false);
    assert.equal(stored.includes('second'), false);
    assert.equal(JSON.parse(stored).every((state) => state.fingerprint), true);
    assert.deepEqual((await pool.getCandidates()).map((candidate) => candidate.key), ['second']);
});

test('cooling key returns after five minutes', async () => {
    const kv = createKv();
    let currentTime = 1_000;
    const pool = new KeyPool('tmdb', 'first,second', kv, () => currentTime);
    const [first] = await pool.getCandidates();

    await pool.markFailure(first, 429);
    assert.deepEqual((await pool.getCandidates()).map((candidate) => candidate.key), ['second']);

    currentTime += 5 * 60 * 1000;
    assert.deepEqual((await pool.getCandidates()).map((candidate) => candidate.key), ['first', 'second']);
});

test('401 and 403 invalidate a key until configuration changes', async () => {
    const kv = createKv();
    const pool = new KeyPool('trakt', 'first,second', kv, () => 1_000);
    const [first, second] = await pool.getCandidates();

    await pool.markFailure(first, 401);
    await pool.markFailure(second, 403);
    assert.deepEqual((await pool.getCandidates()).map((candidate) => candidate.key), ['first']);

    const changedPool = new KeyPool('trakt', 'first,second,third', kv, () => 1_000);
    assert.deepEqual((await changedPool.getCandidates()).map((candidate) => candidate.key), ['third']);
});

test('proxy helper tries the next configured key after a rotatable response', async () => {
    const kv = createKv();
    const pool = new KeyPool('omdb', 'first,second', kv, () => 1_000);
    const used = [];
    const response = await fetchWithKeyRotation(
        pool,
        async (key) => {
            used.push(key);
            return new Response(key === 'first' ? 'rate limited' : 'ok', { status: key === 'first' ? 429 : 200 });
        },
        [429],
    );

    assert.equal(response.status, 200);
    assert.equal(await response.text(), 'ok');
    assert.deepEqual(used, ['first', 'second']);
});
