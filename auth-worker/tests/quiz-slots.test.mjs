import test from 'node:test';
import assert from 'node:assert/strict';
import {
    QUIZ_ANGLE_TYPES,
    QUIZ_QUESTION_SLOT_COUNT,
    QUIZ_UNIT_SLOT_COUNT,
    planQuestionSlots,
    planUnitSlots,
} from '../src/ai/quiz-slots.ts';

/**
 * 出题槽位表的纯函数契约测试：槽位数量、学科白名单、seed 确定性、
 * 13 个题位的题型/难度分布与同概念角度不重复。
 * 全链路无 IO、无随机数，所以这里断言的是「逐字节可复现」本身。
 */

/** 14 个强证据学科：它们要求 external_fact + 非空 source.evidence，是整包作废的高发点，槽位表必须一个都不给。 */
const STRONG_EVIDENCE_SUBJECTS = [
    '物理',
    '化学',
    '生物与生态',
    '医学与公共卫生',
    '天文学',
    '地理与气候',
    '计算机与人工智能',
    '数学与统计',
    '工程与材料',
    '建筑与城市规划',
    '法学',
    '军事学与战略',
    '体育科学',
    '食品科学',
];

/** 5 个「不需要外部来源」的组及其白名单（与实现表逐字对应的期望值）。 */
const GROUP_WHITELIST = {
    film_expression: ['电影学', '叙事学', '摄影与视觉设计', '剪辑与声音', '表演与戏剧'],
    people_and_mind: ['心理学', '认知科学', '发展心理学', '教育学'],
    society_and_institution: ['社会学', '人类学', '传播学', '政治学', '经济学', '犯罪学'],
    history_and_culture: ['历史', '文化研究', '语言学与符号学', '宗教神话与民俗', '音乐与艺术史'],
    philosophy_and_ethics: ['哲学与伦理学', '马克思主义哲学'],
};

const UNIT_SEEDS = [
    'friend-1:2026-09-09:1',
    'friend-1:2026-09-09:2',
    'friend-2:2026-09-09:1',
    'friend-3:2026-01-01:3',
    'friend-7:2025-12-31:7',
];

/** 4 单元（正常路径）。 */
const UNITS_4 = [
    { index: 1, concept: '网状叙事如何支撑多重宇宙设定' },
    { index: 2, concept: '记忆重构中的叙事不可靠性' },
    { index: 3, concept: '动作场面的剪辑节奏与情绪累积' },
    { index: 4, concept: '配乐主题如何标记角色的立场变化' },
];

/** 3 单元（单元被修复后仍有不合格单元被丢弃），index 故意不从 1 连续，用来抓「假设一定有 4 个」的实现。 */
const UNITS_3 = [
    { index: 1, concept: '网状叙事如何支撑多重宇宙设定' },
    { index: 3, concept: '动作场面的剪辑节奏与情绪累积' },
    { index: 4, concept: '配乐主题如何标记角色的立场变化' },
];

/** 位次写死的题型与难度表：1-4 single/easy，5-8 single/medium，9 multiple/medium，10-11 single/hard，12 multiple/hard，13 short/hard。 */
const EXPECTED_SHAPES = [
    ['single', 'easy'],
    ['single', 'easy'],
    ['single', 'easy'],
    ['single', 'easy'],
    ['single', 'medium'],
    ['single', 'medium'],
    ['single', 'medium'],
    ['single', 'medium'],
    ['multiple', 'medium'],
    ['single', 'hard'],
    ['single', 'hard'],
    ['multiple', 'hard'],
    ['short', 'hard'],
];

test('planUnitSlots 返回固定 4 个槽位，组与角度互不重复', () => {
    for (const seed of UNIT_SEEDS) {
        const slots = planUnitSlots(seed);
        assert.equal(slots.length, QUIZ_UNIT_SLOT_COUNT);
        assert.deepEqual(slots.map((slot) => slot.index), [1, 2, 3, 4]);

        const groups = slots.map((slot) => slot.subjectGroup);
        assert.equal(new Set(groups).size, 4, `${seed} 的学科组出现重复：${groups.join(',')}`);
        for (const group of groups) {
            assert.ok(
                Object.hasOwn(GROUP_WHITELIST, group),
                `${seed} 选到了受控目录之外的组：${group}`,
            );
        }

        const angles = slots.map((slot) => slot.angleType);
        assert.equal(new Set(angles).size, 4, `${seed} 的单元角度出现重复：${angles.join(',')}`);
        for (const angle of angles) {
            assert.ok(QUIZ_ANGLE_TYPES.includes(angle), `未知角度类型：${angle}`);
        }
    }
});

test('planUnitSlots 白名单照抄组定义且不含任何强证据学科', () => {
    const seenGroups = new Set();
    for (const seed of UNIT_SEEDS) {
        for (const slot of planUnitSlots(seed)) {
            seenGroups.add(slot.subjectGroup);
            assert.deepEqual(
                slot.allowedSubjects,
                GROUP_WHITELIST[slot.subjectGroup],
                `${slot.subjectGroup} 的白名单与受控目录不一致`,
            );
            for (const subject of STRONG_EVIDENCE_SUBJECTS) {
                assert.equal(
                    slot.allowedSubjects.includes(subject),
                    false,
                    `${slot.subjectGroup} 混入强证据学科「${subject}」`,
                );
            }
        }
    }
    // 防止断言空转：选到的组必须覆盖全部 5 个可选组
    assert.deepEqual([...seenGroups].sort(), Object.keys(GROUP_WHITELIST).sort());
});

test('planUnitSlots 同 seed 逐字节一致，不同 seed 产出不同组合', () => {
    for (const seed of UNIT_SEEDS) {
        assert.deepEqual(planUnitSlots(seed), planUnitSlots(seed));
        assert.equal(JSON.stringify(planUnitSlots(seed)), JSON.stringify(planUnitSlots(seed)));
    }

    const fingerprints = UNIT_SEEDS.map((seed) => JSON.stringify(planUnitSlots(seed)));
    assert.ok(new Set(fingerprints).size > 1, '不同 seed 必须能产生不同组合');

    // 同一天的第 1/2/3 套必须换组换角度，否则复算出来的槽位表天天同一个样
    const daily = [1, 2, 3].map((setIndex) =>
        JSON.stringify(planUnitSlots(`friend-1:2026-09-09:${setIndex}`)),
    );
    assert.equal(new Set(daily).size, 3, '同一天不同套序号的槽位表不应完全相同');
});

test('planQuestionSlots 返回 13 个题位，题型与难度计数为 10/2/1 与 4/5/4', () => {
    const slots = planQuestionSlots(UNITS_4);
    assert.equal(slots.length, QUIZ_QUESTION_SLOT_COUNT);
    assert.deepEqual(
        slots.map((slot) => slot.index),
        EXPECTED_SHAPES.map((_, position) => position + 1),
    );
    assert.deepEqual(
        slots.map((slot) => [slot.type, slot.difficulty]),
        EXPECTED_SHAPES,
    );

    const typeCounts = { single: 0, multiple: 0, short: 0 };
    const difficultyCounts = { easy: 0, medium: 0, hard: 0 };
    for (const slot of slots) {
        typeCounts[slot.type] += 1;
        difficultyCounts[slot.difficulty] += 1;
    }
    assert.deepEqual(typeCounts, { single: 10, multiple: 2, short: 1 });
    assert.deepEqual(difficultyCounts, { easy: 4, medium: 5, hard: 4 });
});

test('planQuestionSlots 3 个与 4 个单元都能把 13 个题位分配完', () => {
    for (const units of [UNITS_4, UNITS_3]) {
        const slots = planQuestionSlots(units);
        assert.equal(slots.length, QUIZ_QUESTION_SLOT_COUNT);
        assert.equal(new Set(slots.map((slot) => slot.index)).size, QUIZ_QUESTION_SLOT_COUNT);

        // 轮转分配：第 i 个题位（0 基）归属 units[i % n]，概念与序号都取自该单元
        slots.forEach((slot, position) => {
            const unit = units[position % units.length];
            assert.equal(slot.unitIndex, unit.index);
            assert.equal(slot.concept, unit.concept);
        });

        // 每个单元都分到题位，且数量差不超过 1
        const counts = units.map(
            (unit) => slots.filter((slot) => slot.unitIndex === unit.index).length,
        );
        assert.equal(counts.reduce((sum, count) => sum + count, 0), QUIZ_QUESTION_SLOT_COUNT);
        assert.ok(Math.max(...counts) - Math.min(...counts) <= 1, `分配不均：${counts.join(',')}`);
    }

    // 4 单元时单元 1 拿 1/5/9/13 共 4 题；3 单元时单元 1 拿 1/4/7/10/13 共 5 题
    assert.deepEqual(
        planQuestionSlots(UNITS_4)
            .filter((slot) => slot.unitIndex === 1)
            .map((slot) => slot.index),
        [1, 5, 9, 13],
    );
    assert.deepEqual(
        planQuestionSlots(UNITS_3)
            .filter((slot) => slot.unitIndex === 1)
            .map((slot) => slot.index),
        [1, 4, 7, 10, 13],
    );
});

test('planQuestionSlots 同概念内角度不重复，概念起点互不相同', () => {
    for (const units of [UNITS_4, UNITS_3]) {
        const slots = planQuestionSlots(units);
        const byConcept = new Map();
        for (const slot of slots) {
            const group = byConcept.get(slot.concept) ?? [];
            group.push(slot);
            byConcept.set(slot.concept, group);
        }

        for (const [concept, group] of byConcept) {
            const angles = group.map((slot) => slot.angleType);
            assert.equal(
                new Set(angles).size,
                group.length,
                `概念「${concept}」内角度重复：${angles.join(',')}`,
            );
            for (const angle of angles) {
                assert.ok(QUIZ_ANGLE_TYPES.includes(angle), `未知角度类型：${angle}`);
            }
        }

        // 不同概念的角度起点必须不同，避免所有概念都从「象征」开始
        const starts = [...byConcept.values()].map((group) => group[0].angleType);
        assert.equal(new Set(starts).size, starts.length, `概念起点重复：${starts.join(',')}`);
    }
});

test('planQuestionSlots 的 siblingAngles 与 conceptOrdinal 反映同概念其他题位', () => {
    for (const units of [UNITS_4, UNITS_3]) {
        const slots = planQuestionSlots(units);
        const byConcept = new Map();
        for (const slot of slots) {
            const group = byConcept.get(slot.concept) ?? [];
            group.push(slot);
            byConcept.set(slot.concept, group);
        }

        for (const group of byConcept.values()) {
            group.forEach((slot, position) => {
                assert.equal(slot.conceptOrdinal, position + 1);
                assert.deepEqual(
                    slot.siblingAngles,
                    group.filter((other) => other !== slot).map((other) => other.angleType),
                );
                assert.equal(slot.siblingAngles.includes(slot.angleType), false);
                assert.equal(slot.siblingAngles.length, group.length - 1);
            });
        }
    }

    // 单题概念：3/4 个单元时每个概念都分到 3-5 题，够不到这个分支；
    // 用 13 个单元让每格独占一个概念，验证「单题概念 siblingAngles 为空」这条规则
    const singletons = planQuestionSlots(
        EXPECTED_SHAPES.map((_, position) => ({ index: position + 1, concept: `概念-${position + 1}` })),
    );
    assert.equal(singletons.length, QUIZ_QUESTION_SLOT_COUNT);
    for (const slot of singletons) {
        assert.equal(slot.conceptOrdinal, 1);
        assert.deepEqual(slot.siblingAngles, []);
    }
});

test('planQuestionSlots 空单元数组抛中文错误', () => {
    assert.throws(
        () => planQuestionSlots([]),
        (error) => error instanceof Error && /至少 3 个/.test(error.message),
    );
});
