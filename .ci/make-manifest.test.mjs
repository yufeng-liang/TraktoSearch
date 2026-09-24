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

test('parseChangelog 保留正文中部的 --- 分隔线', () => {
  const s = parseChangelog(
    '## v2.0.0 更新内容（2026-06-15）\n\n- 前一段\n\n---\n\n- 后一段\n\n---\n\n## v1.9.0 更新内容（2026-06-14）\n\n- 旧版\n'
  );
  assert.equal(s.length, 2);
  // 只剥段尾那一条；正文中部的是作者有意写的分组线，剥掉会改变渲染结果
  assert.ok(s[0].body.includes('---'), `正文中部 --- 被剥掉了: ${JSON.stringify(s[0].body)}`);
  assert.ok(s[0].body.includes('前一段'));
  assert.ok(s[0].body.includes('后一段'));
  assert.ok(!s[0].body.endsWith('---'), `段尾分隔线残留: ${JSON.stringify(s[0].body.slice(-20))}`);
});

test('parseChangelog 首个合法段之后的非法段头也必须报错', () => {
  assert.throws(
    () =>
      parseChangelog(
        '## v2.0.0 更新内容（2026-06-15）\n\n- 新版\n\n## v1.9.0 更新内容(2026-06-14)\n\n- 旧版\n'
      ),
    /段头格式非法/
  );
});

test('parseChangelog 空文件返回空数组', () => {
  assert.deepEqual(parseChangelog(''), []);
});
