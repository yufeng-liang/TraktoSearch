#!/usr/bin/env node
/**
 * 发版清单生成器（CI 用，见 .github/workflows/release-apk.yml）。
 *
 * 两个子命令：
 *   latest  --out <path>            生成 manifest/latest.json 的内容（同时用作 manifest/v<name>.json）
 *   history --releases <path> --out <path>
 *                                   由 `gh api repos/<owner>/<repo>/releases --paginate` 的
 *                                   JSON 数组生成 manifest/history.json
 *
 * 为什么单独成文件而不是写在 YAML 里：清单字段是 App 与官网共同消费的契约，
 * 出错会让线上出现「有更新但下不动」或「校验值对不上」，必须能在本机跑、能断言。
 */
import fs from 'node:fs';

const args = process.argv.slice(2);
const mode = args[0];
if (!mode || !['latest', 'history'].includes(mode)) {
  fail(`用法: make-manifest.mjs <latest|history> --out <path> [--releases <path>]`);
}
const flag = (name) => {
  const i = args.indexOf(`--${name}`);
  return i === -1 ? null : args[i + 1];
};
const out = flag('out');
if (!out) fail('缺少 --out');

function fail(msg) {
  console.error(`::error::${msg}`);
  process.exit(1);
}
const env = (name) => {
  const v = (process.env[name] || '').trim();
  if (!v) fail(`缺少环境变量 ${name}`);
  return v;
};

/**
 * GitHub 的 created_at 是 UTC，而用户看到的日期一直是东八区那天
 * （迁移前 App 用设备时区的 SimpleDateFormat 解析，同一时刻会显示成次日）。
 * 这里显式按 Asia/Shanghai 取日期，避免发版流程接管后所有历史条目的日期整体挪一天。
 */
function dateInShanghai(iso) {
  if (!iso) return '';
  const parts = new Intl.DateTimeFormat('en-CA', {
    timeZone: 'Asia/Shanghai',
    year: 'numeric',
    month: '2-digit',
    day: '2-digit'
  }).format(new Date(iso));
  return parts; // en-CA 的格式就是 yyyy-MM-dd
}

if (mode === 'latest') {
  const versionName = env('VERSION_NAME');
  const versionCode = Number(env('VERSION_CODE'));
  const fileName = env('FILE_NAME');
  const sha256 = env('SHA256').toLowerCase();
  const size = Number(env('SIZE'));
  const releaseDate = env('RELEASE_DATE');
  const minSdk = Number(env('MIN_SDK'));
  const changelogFile = env('CHANGELOG_ZH_FILE');

  const changelog = fs.readFileSync(changelogFile, 'utf8');
  // 存不带日期括号的原始正文：App 侧 injectDateIntoChangelog 会按 releaseDate 补标题日期，
  // 多塞一层会让弹窗出现「v3.6.1 更新内容（2026-09-22）（2026-09-22）」。
  if (!changelog.trim()) fail('changelog 正文为空，宁可不发也别发一个空日志的更新提示');
  if (!/^\d+(\.\d+)+$/.test(versionName)) fail(`versionName 格式异常：${versionName}`);
  if (!Number.isInteger(versionCode) || versionCode <= 0) fail(`versionCode 异常：${process.env.VERSION_CODE}`);
  if (!/^[a-f0-9]{64}$/.test(sha256)) fail(`sha256 不是 64 位小写 hex：${sha256}`);
  if (!Number.isInteger(size) || size <= 0) fail(`size 异常：${process.env.SIZE}`);
  if (!fileName.startsWith('TraktoSearch-v')) fail(`APK 文件名不符合 TraktoSearch-v*.apk：${fileName}`);
  if (!/^\d{4}-\d{2}-\d{2}$/.test(releaseDate)) fail(`releaseDate 不是 yyyy-MM-dd：${releaseDate}`);

  const manifest = {
    schema: 1,
    latest: {
      versionName,
      versionCode,
      fileName,
      url: `/dl/${fileName}`,
      sha256,
      size,
      releaseDate,
      minSdk,
      changelog: { 'zh-CN': changelog }
    }
  };
  write(out, manifest);
  console.log(`已生成清单：${versionName} (code ${versionCode})，${size} 字节`);
} else {
  const releasesFile = flag('releases');
  if (!releasesFile) fail('history 需要 --releases <path>');
  const raw = JSON.parse(fs.readFileSync(releasesFile, 'utf8'));
  if (!Array.isArray(raw)) fail('--releases 不是 JSON 数组');

  const entries = raw
    // 只滤 draft：GitHub 公开 releases 接口本就不返回 draft，而 prerelease 要留着——
    // v3.0.0(beta) 这类条目迁移前就出现在设置页全量日志里，滤掉等于悄悄少一条历史。
    .filter((r) => !r.draft)
    .map((r) => {
      const tagName = r.tag_name || '';
      return {
        versionName: tagName.replace(/^v/, ''),
        tagName,
        releaseDate: dateInShanghai(r.created_at),
        // 正文为空的 release 不进列表：App 侧会过滤掉，留着只会多一条空标题
        changelog: { 'zh-CN': (r.body || '').trim() }
      };
    })
    .filter((e) => e.versionName && e.changelog['zh-CN']);

  // 新→旧。GitHub 列表接口本身按 created_at 倒序，但分页合并后不保证，这里显式排一次。
  entries.sort((a, b) => (a.releaseDate < b.releaseDate ? 1 : a.releaseDate > b.releaseDate ? -1 : 0));

  if (!entries.length) fail('history 为空，宁可不写也别把设置页刷成空白');
  write(out, { schema: 1, releases: entries });
  console.log(`已生成全量日志：${entries.length} 个版本，最新 ${entries[0].tagName}`);
}

function write(path, obj) {
  const dir = path.split(/[\\/]/).slice(0, -1).join('/');
  if (dir) fs.mkdirSync(dir, { recursive: true });
  fs.writeFileSync(path, JSON.stringify(obj, null, 2) + '\n');
}
