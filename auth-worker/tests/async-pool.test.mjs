import test from 'node:test';
import assert from 'node:assert/strict';
import {
    mapWithGate,
    isRetryableRateLimit,
    backoffDelayMs,
    retryWithBackoff,
} from '../src/ai/async-pool.ts';

/**
 * 通用并发工具（src/ai/async-pool.ts）的契约测试。
 *
 * 全部用可控 deferred 制造真实重叠，用注入的 sleep 计数，不依赖真实等待；
 * 整个文件应在毫秒级跑完，不用长计时器。
 */

/** 可控 deferred：测试自己决定 worker 何时能继续。 */
function deferred() {
    let resolve;
    const promise = new Promise(settle => { resolve = settle; });
    return { promise, resolve };
}

const tick = () => new Promise(resolve => setTimeout(resolve, 0));

/**
 * 用 deferred 把每个 worker 卡住，测试逐批放行，全程记录同时在跑的 worker 数峰值。
 *
 * 这样测的是「真实重叠时的上限」而不是「跑完之后回顾一下」：如果实现是先把所有
 * worker 都启动再等，峰值会立刻超过 limit 并被这里抓到。
 */
async function runWithPending(items, limit, workerImpl = async item => item) {
    const pending = [];
    let inFlight = 0;
    let peak = 0;
    let settled = false;

    const run = mapWithGate(items, limit, async (item, index) => {
        inFlight += 1;
        peak = Math.max(peak, inFlight);
        const gate = deferred();
        pending.push(gate);
        try {
            await gate.promise;
            return await workerImpl(item, index);
        } finally {
            inFlight -= 1;
        }
    }).finally(() => { settled = true; });

    let guard = 0;
    while (!settled) {
        await tick();
        if (settled) break;
        for (const gate of pending.splice(0, pending.length)) gate.resolve();
        guard += 1;
        if (guard > 200) throw new Error('并发池未在预期轮次内跑完');
    }

    return { outcomes: await run, peak };
}

test('mapWithGate 同时运行中的 worker 数不超过 limit', async () => {
    const items = Array.from({ length: 9 }, (_, index) => index);
    const { outcomes, peak } = await runWithPending(items, 3);

    assert.equal(outcomes.length, 9);
    assert.ok(peak <= 3, `峰值并发 ${peak} 超过了 limit=3`);
    // 也必须真的并发起来（否则「压到 6 个」就退化成串行了）
    assert.equal(peak, 3);
    outcomes.forEach((outcome, index) => assert.deepEqual(outcome, { ok: true, value: index }));
});

test('mapWithGate limit 大于元素数时按元素数并发', async () => {
    const { outcomes, peak } = await runWithPending([1, 2], 8);
    assert.equal(peak, 2);
    assert.deepEqual(outcomes, [{ ok: true, value: 1 }, { ok: true, value: 2 }]);
});

test('mapWithGate 的返回顺序与输入下标一一对应（与完成先后无关）', async () => {
    const items = ['a', 'b', 'c', 'd', 'e', 'f'];
    const seen = [];
    const outcomes = await mapWithGate(items, 3, async (item, index) => {
        seen.push([item, index]);
        // 故意让靠前的元素最慢：结果顺序不能跟着完成顺序走
        await new Promise(resolve => setTimeout(resolve, (items.length - index) * 3));
        return `${item}@${index}`;
    });

    assert.deepEqual(seen, [['a', 0], ['b', 1], ['c', 2], ['d', 3], ['e', 4], ['f', 5]]);
    assert.deepEqual(outcomes, items.map((item, index) => ({ ok: true, value: `${item}@${index}` })));
});

test('mapWithGate 把 worker 抛出的任何值收成失败结果，不整体抛出', async () => {
    const items = ['a', 'b', 'c', 'd', 'e'];
    const boom = new Error('slot generation failed');

    const outcomes = await mapWithGate(items, 2, async (item, index) => {
        if (index === 0) throw boom;
        if (index === 2) throw 'string failure';
        if (index === 4) throw undefined;
        return `${item}-${index}`;
    });

    assert.deepEqual(outcomes[0], { ok: false, error: boom });
    assert.equal(outcomes[0].error, boom, '错误对象必须原样保留');
    assert.deepEqual(outcomes[1], { ok: true, value: 'b-1' });
    assert.deepEqual(outcomes[2], { ok: false, error: 'string failure' });
    assert.deepEqual(outcomes[3], { ok: true, value: 'd-3' });
    assert.deepEqual(outcomes[4], { ok: false, error: undefined });
});

test('mapWithGate 空输入返回空数组且不调用 worker', async () => {
    let calls = 0;
    const outcomes = await mapWithGate([], 4, async () => {
        calls += 1;
        return 1;
    });
    assert.deepEqual(outcomes, []);
    assert.equal(calls, 0);
});

test('mapWithGate limit 小于 1 或非有限值时按 1 处理', async () => {
    for (const limit of [0, -5, Number.NaN]) {
        const { outcomes, peak } = await runWithPending([1, 2, 3, 4], limit);
        assert.equal(peak, 1, `limit=${limit} 时必须退化成串行`);
        assert.deepEqual(outcomes, [
            { ok: true, value: 1 },
            { ok: true, value: 2 },
            { ok: true, value: 3 },
            { ok: true, value: 4 },
        ]);
    }
});

test('isRetryableRateLimit 命中限频特征的正例', () => {
    assert.equal(isRetryableRateLimit({ code: 'AI_UPSTREAM_ERROR', message: 'Zhipu provider rate limited' }), true);
    assert.equal(isRetryableRateLimit({ code: 'AI_UPSTREAM_ERROR', message: 'Zhipu Provider RATE LIMITED' }), true);
    assert.equal(isRetryableRateLimit({ code: 'AI_UPSTREAM_ERROR', message: 'Zhipu error 1305' }), true);
    assert.equal(isRetryableRateLimit({ code: 'AI_UPSTREAM_ERROR', message: '该模型当前访问量过大' }), true);
    assert.equal(isRetryableRateLimit({ code: 'RATE_LIMITED', message: 'Too many requests' }), true);
    assert.equal(isRetryableRateLimit({ status: 429 }), true);
    assert.equal(isRetryableRateLimit({ statusCode: 429, message: 'Too many requests' }), true);
    // AppError 把上游状态码放在 upstreamStatus 上（statusCode 已折算成 502）
    assert.equal(isRetryableRateLimit({ code: 'AI_UPSTREAM_ERROR', message: 'Zhipu provider request failed', statusCode: 502, upstreamStatus: 429 }), true);
    // 上游原始业务错误对象直接上抛
    assert.equal(isRetryableRateLimit({ code: '1305', message: '该模型当前访问量过大' }), true);
    assert.equal(isRetryableRateLimit({ code: 1305 }), true);
});

test('isRetryableRateLimit 对非限频输入一律 false', () => {
    assert.equal(isRetryableRateLimit(undefined), false);
    assert.equal(isRetryableRateLimit(null), false);
    assert.equal(isRetryableRateLimit('rate limited'), false);
    assert.equal(isRetryableRateLimit(1305), false);
    assert.equal(isRetryableRateLimit(new Error('rate limited')), false);
    assert.equal(isRetryableRateLimit({ code: 'AI_UPSTREAM_ERROR', message: 'Zhipu provider request failed' }), false);
    assert.equal(isRetryableRateLimit({ code: 'AI_UPSTREAM_ERROR', message: 'Agnes provider request failed', statusCode: 502 }), false);
    assert.equal(isRetryableRateLimit({ code: 'INTERNAL_ERROR', message: 'boom' }), false);
    assert.equal(isRetryableRateLimit({ status: 500 }), false);
    assert.equal(isRetryableRateLimit({}), false);
});

test('backoffDelayMs 指数增长、封顶且不出现负值', () => {
    const options = { baseMs: 400, maxMs: 8000 };

    // 抖动边界：random 取 1 得到封顶值，取 0 得到一半
    assert.equal(backoffDelayMs(0, { ...options, random: () => 1 }), 400);
    assert.equal(backoffDelayMs(1, { ...options, random: () => 1 }), 800);
    assert.equal(backoffDelayMs(0, { ...options, random: () => 0 }), 200);
    assert.equal(backoffDelayMs(-3, { ...options, random: () => 1 }), 400, '负数 attempt 按 0 处理');

    // 固定随机源时随 attempt 单调不减，且始终落在 [0, maxMs]
    let previous = -1;
    for (let attempt = 0; attempt < 12; attempt += 1) {
        const delay = backoffDelayMs(attempt, { ...options, random: () => 0.5 });
        assert.ok(delay >= previous, `attempt=${attempt} 的退避小于上一档`);
        assert.ok(delay >= 0 && delay <= options.maxMs, `attempt=${attempt} 的退避越界：${delay}`);
        previous = delay;
    }

    // 封顶：任意大 attempt 都不超过 maxMs，也不出现 NaN/Infinity
    assert.equal(backoffDelayMs(40, { ...options, random: () => 1 }), 8000);
    const huge = backoffDelayMs(1_000_000, { ...options, random: () => 0.3 });
    assert.equal(Number.isFinite(huge), true);
    assert.ok(huge > 0 && huge <= 8000, `attempt 溢出时退避越界：${huge}`);

    // 抖动源异常（越界 / NaN）不得产生负值或 NaN
    for (const random of [() => -5, () => 2, () => Number.NaN]) {
        const delay = backoffDelayMs(3, { ...options, random });
        assert.ok(Number.isFinite(delay) && delay >= 0 && delay <= 8000, `越界抖动源产生了非法退避：${delay}`);
    }

    // 默认值：base 400ms，封顶不超过 8000ms
    const defaultDelay = backoffDelayMs(0);
    assert.ok(defaultDelay >= 200 && defaultDelay <= 400, `默认退避越界：${defaultDelay}`);
    assert.ok(backoffDelayMs(20) <= 8000);
});

test('retryWithBackoff 重试到成功并返回结果', async () => {
    const sleeps = [];
    let calls = 0;

    const result = await retryWithBackoff(async () => {
        calls += 1;
        if (calls < 3) throw { code: 'AI_UPSTREAM_ERROR', message: 'Zhipu provider rate limited' };
        return { answer: 42 };
    }, {
        attempts: 3,
        sleep: async ms => { sleeps.push(ms); },
        baseMs: 400,
        maxMs: 8000,
    });

    assert.deepEqual(result, { answer: 42 });
    assert.equal(calls, 3);
    assert.equal(sleeps.length, 2);
    // 每次重试前必须真实退避（attempt 0 落在 [200,400]，attempt 1 落在 [400,800]）
    assert.ok(sleeps[0] >= 200 && sleeps[0] <= 400, `首次退避越界：${sleeps[0]}`);
    assert.ok(sleeps[1] >= 400 && sleeps[1] <= 800, `二次退避越界：${sleeps[1]}`);
});

test('retryWithBackoff 用尽尝试后原样抛出最后一次错误', async () => {
    const errors = [new Error('first'), new Error('second'), new Error('third')];
    const sleeps = [];
    let calls = 0;

    await assert.rejects(
        () => retryWithBackoff(async () => {
            const error = errors[calls];
            calls += 1;
            throw error;
        }, {
            attempts: 3,
            shouldRetry: () => true,
            sleep: async ms => { sleeps.push(ms); },
            baseMs: 10,
            maxMs: 40,
        }),
        error => error === errors[2],
    );

    assert.equal(calls, 3, 'attempts 是总尝试次数（含首次）');
    assert.equal(sleeps.length, 2, '只有前两次失败需要退避，最后一次失败直接原样抛出');
});

test('retryWithBackoff 遇到不可重试错误立刻抛出，不消耗尝试也不睡眠', async () => {
    const boom = new Error('deterministic failure');
    const sleeps = [];
    let calls = 0;

    await assert.rejects(
        () => retryWithBackoff(async () => {
            calls += 1;
            throw boom;
        }, {
            attempts: 5,
            sleep: async ms => { sleeps.push(ms); },
        }),
        error => error === boom,
    );

    // 默认 shouldRetry 用 isRetryableRateLimit：普通 Error 不可重试
    assert.equal(calls, 1);
    assert.equal(sleeps.length, 0);

    // 显式 shouldRetry 返回 false 时同样立刻抛出，即使错误「看起来」可重试
    let retried = 0;
    await assert.rejects(
        () => retryWithBackoff(async () => {
            retried += 1;
            throw { code: 'AI_UPSTREAM_ERROR', message: 'rate limited' };
        }, {
            attempts: 4,
            shouldRetry: () => false,
            sleep: async () => { throw new Error('不应进入睡眠'); },
        }),
        error => error?.code === 'AI_UPSTREAM_ERROR',
    );
    assert.equal(retried, 1);
});

test('retryWithBackoff attempts 为 1 时只尝试一次', async () => {
    let calls = 0;
    await assert.rejects(
        () => retryWithBackoff(async () => {
            calls += 1;
            throw new Error('once');
        }, { attempts: 1, shouldRetry: () => true }),
        /once/,
    );
    assert.equal(calls, 1);
});
