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

import { execFileSync } from 'node:child_process';
import fs from 'node:fs';
import os from 'node:os';
import path from 'node:path';

const tmp = fs.mkdtempSync(path.join(os.tmpdir(), 'mm-test-'));

const BASE_ENV = {
  VERSION_NAME: '2.0.0',
  VERSION_CODE: '42',
  FILE_NAME: 'TraktoSearch-v2.0.0.apk',
  SHA256: 'a'.repeat(64),
  SIZE: '12345',
  MIN_SDK: '26'
};

test('history 从 CHANGELOG.md 生成，顺序即文件顺序', () => {
  const cl = path.join(tmp, 'CHANGELOG-a.md');
  fs.writeFileSync(cl, SAMPLE);
  const out = path.join(tmp, 'history-a.json');
  execFileSync('node', ['.ci/make-manifest.mjs', 'history', '--changelog', cl, '--out', out], { stdio: 'pipe' });
  const j = JSON.parse(fs.readFileSync(out, 'utf8'));
  assert.equal(j.schema, 1);
  assert.equal(j.releases.length, 2);
  assert.equal(j.releases[0].tagName, 'v2.0.0');
  assert.equal(j.releases[0].versionName, '2.0.0');
  assert.equal(j.releases[0].releaseDate, '2026-06-15');
  assert.ok(j.releases[0].changelog['zh-CN'].includes('新能力'));
  assert.equal(j.releases[1].tagName, 'v1.9.0');
});

test('history 生成顺序与文件顺序一致（不做日期重排）', () => {
  const cl = path.join(tmp, 'CHANGELOG-b.md');
  // 故意让日期与文件顺序矛盾：文件顺序必须胜出
  fs.writeFileSync(cl, [
    '## v9.0.0 更新内容（2020-01-01）', '', '- 新', '',
    '## v8.0.0 更新内容（2030-01-01）', '', '- 旧', ''
  ].join('\n'));
  const out = path.join(tmp, 'history-b.json');
  execFileSync('node', ['.ci/make-manifest.mjs', 'history', '--changelog', cl, '--out', out], { stdio: 'pipe' });
  const j = JSON.parse(fs.readFileSync(out, 'utf8'));
  assert.equal(j.releases[0].tagName, 'v9.0.0');
  assert.equal(j.releases[1].tagName, 'v8.0.0');
});

test('latest 从 CHANGELOG.md 顶部段取日期与正文', () => {
  const cl = path.join(tmp, 'CHANGELOG-c.md');
  fs.writeFileSync(cl, SAMPLE);
  const out = path.join(tmp, 'latest-c.json');
  execFileSync('node', ['.ci/make-manifest.mjs', 'latest', '--changelog', cl, '--out', out], {
    stdio: 'pipe',
    env: { ...process.env, ...BASE_ENV }
  });
  const j = JSON.parse(fs.readFileSync(out, 'utf8'));
  assert.equal(j.latest.releaseDate, '2026-06-15');
  assert.ok(j.latest.changelog['zh-CN'].includes('新能力'));
  assert.equal(j.latest.versionCode, 42);
});

test('latest 在版本号与顶部段不符时报错', () => {
  const cl = path.join(tmp, 'CHANGELOG-d.md');
  fs.writeFileSync(cl, SAMPLE);
  const out = path.join(tmp, 'latest-d.json');
  assert.throws(() => {
    execFileSync('node', ['.ci/make-manifest.mjs', 'latest', '--changelog', cl, '--out', out], {
      stdio: 'pipe',
      env: { ...process.env, ...BASE_ENV, VERSION_NAME: '99.0.0' }
    });
  });
});

test('history 遇到坏段头直接失败', () => {
  const cl = path.join(tmp, 'CHANGELOG-e.md');
  fs.writeFileSync(cl, '## v1.0.0 更新内容\n\n- 缺日期\n');
  const out = path.join(tmp, 'history-e.json');
  assert.throws(() => {
    execFileSync('node', ['.ci/make-manifest.mjs', 'history', '--changelog', cl, '--out', out], { stdio: 'pipe' });
  });
});
