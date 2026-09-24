import { test } from 'node:test';
import assert from 'node:assert/strict';
import { parseChangelog } from './make-manifest.mjs';

const SAMPLE = `# 更新日志

<!-- 本文件是唯一真相源 -->

## v2.0.0 更新内容（2026-06-15）

### ✨ 新功能

- **新能力**：做了某件事

### 🐛 修复

- 修了个 bug

---

## v1.9.0 更新内容（2026-06-14）

### 🔧 改进

- 优化了某处
`;

test('parseChangelog 按 H2 切分且保持文件顺序', () => {
  const s = parseChangelog(SAMPLE);
  assert.equal(s.length, 2);
  assert.equal(s[0].tagName, 'v2.0.0');
  assert.equal(s[0].releaseDate, '2026-06-15');
  assert.equal(s[1].tagName, 'v1.9.0');
  assert.equal(s[1].releaseDate, '2026-06-14');
});

test('parseChangelog 由 tagName 推出 versionName（保留 beta 后缀）', () => {
  const s = parseChangelog('## v3.0.0(beta) 更新内容（2026-07-13）\n\n- 内容\n');
  assert.equal(s[0].versionName, '3.0.0(beta)');
});

test('parseChangelog 剥掉段尾的 --- 分隔线', () => {
  const s = parseChangelog(SAMPLE);
  assert.ok(!s[0].body.endsWith('---'), `段尾残留分隔线: ${JSON.stringify(s[0].body.slice(-20))}`);
  assert.ok(s[0].body.includes('修了个 bug'));
});

test('parseChangelog 忽略一级标题与 HTML 注释', () => {
  const s = parseChangelog(SAMPLE);
  assert.ok(!s[0].body.includes('唯一真相源'));
  assert.ok(!s[0].body.includes('# 更新日志'));
});

test('parseChangelog 段头行尾多余空格仍可解析', () => {
  const s = parseChangelog('## v1.0.0 更新内容（2026-01-01）   \n\n- 内容\n');
  assert.equal(s[0].tagName, 'v1.0.0');
});

test('parseChangelog 半角括号段头应报错', () => {
  assert.throws(
    () => parseChangelog('## v1.0.0 更新内容(2026-01-01)\n\n- 内容\n'),
    /段头格式非法/
  );
});

test('parseChangelog 缺日期段头应报错', () => {
  assert.throws(() => parseChangelog('## v1.0.0 更新内容\n\n- 内容\n'), /段头格式非法/);
});

test('parseChangelog 空文件返回空数组', () => {
  assert.deepEqual(parseChangelog(''), []);
});
