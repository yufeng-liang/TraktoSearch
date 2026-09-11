import test from 'node:test';
import assert from 'node:assert/strict';
import {
    EMPTY_BANK_USAGE,
    QUIZ_BANK_TTL_SECONDS,
    QUIZ_SETS_PER_DAY,
    bankSeed,
    canGenerateSet,
    deriveBankQuizId,
    isLocalDate,
    isPreGeneratedSet,
    nextSetIndex,
    readBankUsage,
    selectDailyMovies,
    writeBankUsage,
} from '../src/ai/quiz-bank.ts';

/**
 * 当天题库机制里「与生成过程无关」那部分的契约测试：
 * 确定性抽片、quizId 派生、用量计数与预算判定。全部纯函数 + 内存 KV 桩，不打网络。
 */

const TODAY = '2026-09-09';
const FRIEND = 'friend-1';

/** 20 部已看影片：数量大于两套题库的抽取量，才能看出不同 seed 抽到的不是同一批。 */
const WATCHED = Array.from({ length: 20 }, (_, index) => ({
    title: `已看影片-${String(index + 1).padStart(2, '0')}`,
    year: 2000 + index,
}));

/**
 * 内存 KV 桩：Map 存值 + 记录每次 put 的 TTL 参数 + 可注入读写故障。
 * 与线上 KV 一样，get 未命中返回 null（而不是 undefined）。
 */
function createKv({ failGet = false, failPut = false } = {}) {
    const store = new Map();
    const puts = [];
    return {
        store,
        puts,
        KV: {
            async get(key) {
                if (failGet) throw new Error('kv get failed');
                return store.has(key) ? store.get(key) : null;
            },
            async put(key, value, options) {
                if (failPut) throw new Error('kv put failed');
                puts.push({ key, value, options });
                store.set(key, value);
            },
        },
    };
}

test('isLocalDate 接受今天与前后 1 天', () => {
    assert.equal(isLocalDate(TODAY, TODAY), true);
    assert.equal(isLocalDate('2026-09-08', TODAY), true);
    assert.equal(isLocalDate('2026-09-10', TODAY), true);
    // 跨月、跨年边界同样按天数算，不是按字符串比大小
    assert.equal(isLocalDate('2026-09-01', '2026-09-02'), true);
    assert.equal(isLocalDate('2025-12-31', '2026-01-01'), true);
    // 闰日
    assert.equal(isLocalDate('2028-02-29', '2028-03-01'), true);
});

test('isLocalDate 拒绝非法格式与相差 2 天', () => {
    assert.equal(isLocalDate('2026-09-07', TODAY), false);
    assert.equal(isLocalDate('2026-09-11', TODAY), false);
    for (const value of [
        '2026-9-9',             // 未补零
        '20260909',             // 无分隔符
        '2026/09/09',           // 分隔符不对
        '2026-09-09T00:00:00Z', // 带时间
        '2026-13-01',           // 月份越界
        '2026-02-30',           // 日历上不存在
        '2026-00-10',           // 月份 0
        '2026-09-00',           // 日 0
        '',
        'today',
    ]) {
        assert.equal(isLocalDate(value, TODAY), false, `应拒绝 ${JSON.stringify(value)}`);
    }
    for (const value of [null, undefined, 20260909, {}, [], true]) {
        assert.equal(isLocalDate(value, TODAY), false, `应拒绝非字符串 ${JSON.stringify(value)}`);
    }
    // 基准日期本身不合法时无法判断偏移，一律 false
    assert.equal(isLocalDate(TODAY, 'not-a-date'), false);
});

test('selectDailyMovies 同 seed 两次调用深相等', () => {
    const seed = bankSeed(FRIEND, TODAY, 0);
    const first = selectDailyMovies(WATCHED, seed, 7);
    const second = selectDailyMovies(WATCHED, seed, 7);
    assert.equal(first.length, 7);
    assert.deepEqual(first, second);
    // 同一天同一套的抽片只依赖 (seed, 片单内容)，与片单顺序无关
    const shuffled = [...WATCHED].reverse();
    assert.deepEqual(
        [...selectDailyMovies(shuffled, seed, 7)].map(movie => movie.title).sort(),
        [...first].map(movie => movie.title).sort(),
    );
});

test('selectDailyMovies 不同 seed 抽出不同的一批（不是取前 N 部）', () => {
    const setA = selectDailyMovies(WATCHED, bankSeed(FRIEND, TODAY, 0), 7);
    const setB = selectDailyMovies(WATCHED, bankSeed(FRIEND, TODAY, 1), 7);
    const titlesA = setA.map(movie => movie.title);
    const titlesB = setB.map(movie => movie.title);
    assert.equal(titlesA.length, 7);
    assert.equal(titlesB.length, 7);
    assert.notDeepEqual([...titlesA].sort(), [...titlesB].sort());
    // 抽中的前 7 部不是「列表开头那 7 部」
    assert.notDeepEqual(titlesA, WATCHED.slice(0, 7).map(movie => movie.title));
});

test('selectDailyMovies 抽中结果按 watched 原顺序输出，且不修改入参', () => {
    const snapshot = JSON.stringify(WATCHED);
    const picked = selectDailyMovies(WATCHED, bankSeed(FRIEND, TODAY, 0), 7);
    assert.notEqual(picked, WATCHED);
    assert.equal(JSON.stringify(WATCHED), snapshot, '入参不能被就地排序或改写');
    // 输出保持入参相对顺序：命中项在原列表里的下标必须递增
    const positions = picked.map(movie => WATCHED.indexOf(movie));
    assert.deepEqual(positions, [...positions].sort((left, right) => left - right));
    assert.ok(positions.every(position => position >= 0));
});

test('selectDailyMovies count 超过列表长度时返回全部', () => {
    assert.deepEqual(selectDailyMovies(WATCHED.slice(0, 3), 'seed-x', 7), WATCHED.slice(0, 3));
    assert.deepEqual(selectDailyMovies(WATCHED, 'seed-x', WATCHED.length), WATCHED);
    assert.equal(selectDailyMovies([], 'seed-x', 7).length, 0);
    assert.deepEqual(selectDailyMovies(WATCHED, 'seed-x', 0), []);
    assert.deepEqual(selectDailyMovies(WATCHED, 'seed-x', -1), []);
});

test('bankSeed 与 deriveBankQuizId 稳定、可复算且带日期与套序号', () => {
    assert.equal(bankSeed(FRIEND, TODAY, 0), `${FRIEND}:${TODAY}:0`);

    const quizId = deriveBankQuizId(FRIEND, TODAY, 0);
    assert.match(quizId, /^bank-2026-09-09-0-[0-9a-f]{8}$/);
    // 复算：同参数逐字节相同
    assert.equal(deriveBankQuizId(FRIEND, TODAY, 0), quizId);
    assert.equal(
        deriveBankQuizId(FRIEND, TODAY, 0),
        deriveBankQuizId(`${FRIEND}`, `${TODAY}`, 0),
    );
    assert.ok(quizId.includes(TODAY));
    assert.ok(quizId.includes('-0-'));
});

test('deriveBankQuizId 不同套序号/不同用户不同 id', () => {
    const first = deriveBankQuizId(FRIEND, TODAY, 0);
    const second = deriveBankQuizId(FRIEND, TODAY, 1);
    const third = deriveBankQuizId(FRIEND, TODAY, 2);
    assert.notEqual(first, second);
    assert.notEqual(second, third);
    assert.notEqual(first, third);
    // 换用户、换日期也要换 id（friendId 只进哈希，不进可读前缀）
    assert.notEqual(deriveBankQuizId('friend-2', TODAY, 0), first);
    assert.notEqual(deriveBankQuizId(FRIEND, '2026-09-10', 0), first);
});

test('readBankUsage 在 KV 未绑定/键缺失/读失败时降级为空用量', async () => {
    assert.deepEqual(await readBankUsage({}, FRIEND, TODAY), EMPTY_BANK_USAGE);
    assert.deepEqual(await readBankUsage({ KV: undefined }, FRIEND, TODAY), EMPTY_BANK_USAGE);
    assert.deepEqual(await readBankUsage(undefined, FRIEND, TODAY), EMPTY_BANK_USAGE);
    // KV 对象存在但没有 get（绑定缺失的另一半）
    assert.deepEqual(await readBankUsage({ KV: {} }, FRIEND, TODAY), EMPTY_BANK_USAGE);
    // 键不存在
    assert.deepEqual(await readBankUsage({ KV: createKv().KV }, FRIEND, TODAY), EMPTY_BANK_USAGE);
    // 读抛错
    assert.deepEqual(
        await readBankUsage({ KV: createKv({ failGet: true }).KV }, FRIEND, TODAY),
        EMPTY_BANK_USAGE,
    );
    // 返回的是副本：调用方原地累加不能污染共享常量
    const usage = await readBankUsage({}, FRIEND, TODAY);
    usage.attempts += 1;
    assert.equal(EMPTY_BANK_USAGE.attempts, 0);
    assert.deepEqual(EMPTY_BANK_USAGE, { usedSets: 0, generatedSets: 0, attempts: 0 });
});

test('readBankUsage 在内容损坏或字段不合法时按 0 处理', async () => {
    const key = `quizbank:used:${FRIEND}:${TODAY}`;
    for (const raw of ['{ not json', 'null', '"str"', '[1,2]', '42', '']) {
        const kv = createKv();
        kv.store.set(key, raw);
        assert.deepEqual(
            await readBankUsage({ KV: kv.KV }, FRIEND, TODAY),
            EMPTY_BANK_USAGE,
            `损坏内容应降级：${raw}`,
        );
    }

    const broken = createKv();
    broken.store.set(key, JSON.stringify({ usedSets: -1, generatedSets: 2.5, attempts: '3' }));
    assert.deepEqual(await readBankUsage({ KV: broken.KV }, FRIEND, TODAY), {
        usedSets: 0,
        generatedSets: 0,
        attempts: 0,
    });

    const partial = createKv();
    partial.store.set(key, JSON.stringify({ usedSets: 2 }));
    assert.deepEqual(await readBankUsage({ KV: partial.KV }, FRIEND, TODAY), {
        usedSets: 2,
        generatedSets: 0,
        attempts: 0,
    });
});

test('readBankUsage 读取合法记录', async () => {
    const kv = createKv();
    const usage = { usedSets: 1, generatedSets: 2, attempts: 3 };
    kv.store.set(`quizbank:used:${FRIEND}:${TODAY}`, JSON.stringify(usage));
    assert.deepEqual(await readBankUsage({ KV: kv.KV }, FRIEND, TODAY), usage);
    // 不同 friendId/date 互不串键
    assert.deepEqual(await readBankUsage({ KV: kv.KV }, 'friend-2', TODAY), EMPTY_BANK_USAGE);
    assert.deepEqual(await readBankUsage({ KV: kv.KV }, FRIEND, '2026-09-10'), EMPTY_BANK_USAGE);
});

test('writeBankUsage 写用量记录并带 36 小时 TTL', async () => {
    const kv = createKv();
    const usage = { usedSets: 1, generatedSets: 2, attempts: 3 };
    await writeBankUsage({ KV: kv.KV }, FRIEND, TODAY, usage);

    assert.equal(kv.puts.length, 1);
    assert.equal(kv.puts[0].key, `quizbank:used:${FRIEND}:${TODAY}`);
    assert.equal(kv.puts[0].options.expirationTtl, QUIZ_BANK_TTL_SECONDS);
    assert.equal(QUIZ_BANK_TTL_SECONDS, 36 * 60 * 60);
    assert.deepEqual(JSON.parse(kv.puts[0].value), usage);
    // 回读一致（写入方与读取方共用同一份键格式）
    assert.deepEqual(await readBankUsage({ KV: kv.KV }, FRIEND, TODAY), usage);
});

test('writeBankUsage 在 KV 未绑定或写失败时静默返回', async () => {
    await writeBankUsage({}, FRIEND, TODAY, EMPTY_BANK_USAGE);
    await writeBankUsage({ KV: {} }, FRIEND, TODAY, EMPTY_BANK_USAGE);
    await writeBankUsage(undefined, FRIEND, TODAY, EMPTY_BANK_USAGE);

    const kv = createKv({ failPut: true });
    await writeBankUsage({ KV: kv.KV }, FRIEND, TODAY, EMPTY_BANK_USAGE);
    assert.equal(kv.store.size, 0);
});

test('canGenerateSet 在 attempts 达到 setsPerDay*2 时为 false', () => {
    assert.equal(QUIZ_SETS_PER_DAY, 2);
    const usage = count => ({ usedSets: 0, generatedSets: 0, attempts: count });
    assert.equal(canGenerateSet(usage(0)), true);
    assert.equal(canGenerateSet(usage(3)), true);
    // 边界：恰好等于 setsPerDay*2 就不再生成
    assert.equal(canGenerateSet(usage(QUIZ_SETS_PER_DAY * 2)), false);
    assert.equal(canGenerateSet(usage(5)), false);
    // 自定义 setsPerDay 时同一条规则
    assert.equal(canGenerateSet(usage(5), 3), true);
    assert.equal(canGenerateSet(usage(6), 3), false);
    // 字段被改坏时不至于把预算撑开
    assert.equal(canGenerateSet({ usedSets: 0, generatedSets: 0, attempts: Number.NaN }), true);
});

test('nextSetIndex 按已玩套数前进，用满预生成槽位后继续加', () => {
    const usage = usedSets => ({ usedSets, generatedSets: QUIZ_SETS_PER_DAY, attempts: 0 });
    assert.equal(nextSetIndex(EMPTY_BANK_USAGE), 1);
    assert.equal(nextSetIndex(usage(0)), 1);
    assert.equal(nextSetIndex(usage(1)), 2);
    // 两套预生成用完后不截断、不回绕，继续指向下一套（冷路径按需生成）
    assert.equal(nextSetIndex(usage(QUIZ_SETS_PER_DAY)), 3);
    assert.equal(nextSetIndex(usage(4)), 5);
    assert.equal(nextSetIndex(usage(4), 3), 5);
    assert.equal(nextSetIndex({ usedSets: Number.NaN, generatedSets: 0, attempts: 0 }), 1);
});

test('isPreGeneratedSet 只认当天前 setsPerDay 套，超出即按需生成', () => {
    assert.equal(isPreGeneratedSet(1), true);
    assert.equal(isPreGeneratedSet(QUIZ_SETS_PER_DAY), true);
    // 边界：第 setsPerDay+1 套起不是预生成套（用户玩完当天的量还想再来一局）
    assert.equal(isPreGeneratedSet(QUIZ_SETS_PER_DAY + 1), false);
    assert.equal(isPreGeneratedSet(9), false);
    // 自定义 setsPerDay 时同一条规则
    assert.equal(isPreGeneratedSet(3, 3), true);
    assert.equal(isPreGeneratedSet(4, 3), false);
    // 非法序号一律不算预生成套，脏值不能把「系统预生成」的口子撑开
    assert.equal(isPreGeneratedSet(0), false);
    assert.equal(isPreGeneratedSet(-1), false);
    assert.equal(isPreGeneratedSet(Number.NaN), false);
});
