import test from 'node:test';
import assert from 'node:assert/strict';
import {
    __resetModelQuotaMemo,
    isQuotaExhaustedError,
    markModelExhausted,
    readExhaustedModels,
    splitExhaustedModels,
} from '../src/ai/model-quota.ts';
import { AppError } from '../src/util/errors.ts';

/**
 * 模型级免费额度记忆。百炼每个模型独立发 100 万 token 免费额度，用尽不恢复；
 * 记不住它就会每次生成都先白打一次注定失败的调用。
 */

function memoryKv(initial = {}) {
    const store = new Map(Object.entries(initial));
    return {
        store,
        async get(key) { return store.has(key) ? store.get(key) : null; },
        async put(key, value) { store.set(key, value); },
        async delete(key) { store.delete(key); },
    };
}

test('额度耗尽判定覆盖上游的两种形态，且不误伤别的失败', () => {
    assert.equal(isQuotaExhaustedError(new AppError('AI_QUOTA_EXHAUSTED', 'Bailian free quota exhausted', 502, 403)), true);
    assert.equal(isQuotaExhaustedError(new AppError('AI_UPSTREAM_ERROR', 'Bailian provider request failed', 502, 403)), true);
    assert.equal(isQuotaExhaustedError(new AppError('AI_UPSTREAM_ERROR', 'Bailian free quota exhausted', 502, 200)), true);
    assert.equal(isQuotaExhaustedError(new AppError('AI_UPSTREAM_ERROR', 'AllocationQuota.FreeTierOnly', 502, 200)), true);
    assert.equal(isQuotaExhaustedError(new AppError('AI_UPSTREAM_ERROR', 'AI provider returned empty content', 502)), false);
    assert.equal(isQuotaExhaustedError(new AppError('AI_UPSTREAM_ERROR', 'rate limited', 502, 429)), false);
    assert.equal(isQuotaExhaustedError(new Error('socket hang up')), false);
    assert.equal(isQuotaExhaustedError(null), false);
});

test('已耗尽的候选排在最后而不是被删掉', () => {
    const { available, exhausted } = splitExhaustedModels(
        ['qwen3.6-flash', 'qwen3.7-flash', 'qwen3.8-flash'],
        new Set(['qwen3.6-flash']),
    );
    assert.deepEqual(available, ['qwen3.7-flash', 'qwen3.8-flash']);
    assert.deepEqual(exhausted, ['qwen3.6-flash']);

    const none = splitExhaustedModels(['a'], new Set());
    assert.deepEqual(none.available, ['a']);
    assert.deepEqual(none.exhausted, []);
});

test('标记写入 KV，并在 isolate 内立即生效；无 KV 时不报错', async () => {
    __resetModelQuotaMemo();
    const kv = memoryKv();
    const env = { KV: kv };

    assert.deepEqual([...(await readExhaustedModels(env, 'bailian'))], []);
    await markModelExhausted(env, 'bailian', 'qwen3.6-flash');
    const stored = JSON.parse(kv.store.get('ai:model-quota:v1:bailian'));
    assert.deepEqual(stored, ['qwen3.6-flash']);
    assert.deepEqual([...(await readExhaustedModels(env, 'bailian'))], ['qwen3.6-flash']);

    // 换 isolate（清 memo）后仍从 KV 读得到：记忆必须跨请求存活。
    __resetModelQuotaMemo();
    assert.deepEqual([...(await readExhaustedModels(env, 'bailian'))], ['qwen3.6-flash']);

    // 供应商分开记：bailian 的额度耗尽不该影响 zhipu 的梯队。
    assert.deepEqual([...(await readExhaustedModels(env, 'zhipu'))], []);

    // 没有 KV 绑定（老环境/测试）时不抛错：记忆退化成只在本 isolate 内生效。
    __resetModelQuotaMemo();
    const bare = {};
    await markModelExhausted(bare, 'bailian', 'qwen3.6-flash');
    assert.deepEqual([...(await readExhaustedModels(bare, 'bailian'))], ['qwen3.6-flash']);
    __resetModelQuotaMemo();
    assert.deepEqual([...(await readExhaustedModels(bare, 'bailian'))], []);
});

test('KV 内容坏掉时按「没有记录」处理，不能因此掐掉整条梯队', async () => {
    __resetModelQuotaMemo();
    const kv = memoryKv({ 'ai:model-quota:v1:bailian': 'not json' });
    assert.deepEqual([...(await readExhaustedModels({ KV: kv }, 'bailian'))], []);

    __resetModelQuotaMemo();
    const kv2 = { async get() { throw new Error('KV unavailable'); } };
    assert.deepEqual([...(await readExhaustedModels({ KV: kv2 }, 'bailian'))], []);
});
