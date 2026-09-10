import test from 'node:test';
import assert from 'node:assert/strict';
import { parseAssistantJson, repairAssistantJson } from '../src/ai/mimo.ts';

/**
 * 模型输出 JSON 的容错解析。
 *
 * 背景：出题槽位实测过两类缺陷都让 JSON.parse 直接判废，而其中一类（收尾引号多打
 * 反斜杠）的正文其实是完整的——整套 13 题会因为一格解析失败而进修复轮，白烧上游时间。
 * 这里锁住「能救则救、救不了必须原样放弃」，避免为了容错把非法 JSON 修成看似合法的对象。
 */

/** parseAssistantJson 吃的是供应商原始 payload，测试里包一层与线上同形。 */
function payloadWith(content) {
    return { choices: [{ message: { content } }] };
}

test('模型把字符串收尾引号写成反斜杠引号时仍能解析', () => {
    const text = '{\n "prompt": "题干",\n "evidenceUsed": "与玛蒂尔达因全家被害结成师徒关系\\"\n}';
    const parsed = parseAssistantJson(payloadWith(text));
    assert.equal(parsed.evidenceUsed, '与玛蒂尔达因全家被害结成师徒关系');
});

test('外层包装对象同样能修反斜杠引号', () => {
    const text = '{"question": {"prompt": "题干", "evidenceUsed": "支撑了本题关于角色差异的对比。\\"\n  }\n}';
    const parsed = parseAssistantJson(payloadWith(text));
    assert.equal(parsed.question.evidenceUsed, '支撑了本题关于角色差异的对比。');
});

test('尾部被上游截断时补全字符串与括号', () => {
    const parsed = parseAssistantJson(payloadWith('{"question": {"prompt": "题干", "evidenceUsed": "截断在'));
    assert.equal(parsed.question.prompt, '题干');
    assert.equal(parsed.question.evidenceUsed, '截断在');
});

test('尾部半截键值不冒充合法字段', () => {
    const parsed = parseAssistantJson(payloadWith('{"question": {"prompt": "题干", "evidenceUsed":'));
    assert.deepEqual(parsed, { question: { prompt: '题干' } });
});

test('值缺起始引号（实测高发）时补上引号', () => {
    const text = '{"question": {"prompt": "题干", "learningTakeaway":异质性群体在共同目标下更愿意合作"}';
    const parsed = parseAssistantJson(payloadWith(text));
    assert.equal(parsed.question.learningTakeaway, '异质性群体在共同目标下更愿意合作');
});

test('值缺起始引号与尾部截断叠加时也能救回', () => {
    const text = '{"question": {"learningTakeaway":异质性群体在共同目标下更愿意合作", "evidenceUsed": "截断在';
    const parsed = parseAssistantJson(payloadWith(text));
    assert.equal(parsed.question.learningTakeaway, '异质性群体在共同目标下更愿意合作');
    assert.equal(parsed.question.evidenceUsed, '截断在');
});

test('补引号不会动合法 JSON 的数字/字面量/字符串值', () => {
    for (const text of ['{"a": 1}', '{"a": true}', '{"a": null}', '{"a": -1.5}', '{"a": "x", "b": "y"}']) {
        assert.equal(repairAssistantJson(text), null, `合法 JSON 不该被改写：${text}`);
    }
});

test('正文内合法的转义引号不被改写', () => {
    const text = '{"prompt": "他说：\\"你好\\"，然后走了"}';
    assert.equal(repairAssistantJson(text), null);
    assert.equal(parseAssistantJson(payloadWith(text)).prompt, '他说："你好"，然后走了');
});

test('完全不成 JSON 的输出必须原样判废，不修成半个对象', () => {
    assert.equal(repairAssistantJson('这不是 JSON'), null);
    assert.throws(
        () => parseAssistantJson(payloadWith('这不是 JSON')),
        (error) => error.code === 'INVALID_AI_OUTPUT',
    );
});

test('修复结果自身必须可解析，否则放弃', () => {
    for (const text of ['{"a": "x', '{"a": [1, 2', '{"a": {"b": "c']) {
        const repaired = repairAssistantJson(text);
        if (repaired !== null) {
            assert.doesNotThrow(() => JSON.parse(repaired), `修复结果必须可解析：${text}`);
        }
    }
});
