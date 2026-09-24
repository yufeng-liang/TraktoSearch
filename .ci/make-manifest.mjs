#!/usr/bin/env node
/**
 * 发版清单生成器（CI 用，见 .github/workflows/release-apk.yml）。
 *
 * 三个子命令：
 *   latest  --out <path>            生成 manifest/latest.json 的内容（同时用作 manifest/v<name>.json）
 *   history --releases <path> --out <path>
 *                                   由 `gh api repos/<owner>/<repo>/releases --paginate` 的
 *                                   JSON 数组生成 manifest/history.json
 *   section --version <v> --out <path> [--changelog <path>]
 *                                   从 CHANGELOG.md 抽出指定版本段（含段头）写进 --out，
 *                                   供发版流程把更新日志塞进 GitHub Release body
 *
 * 为什么单独成文件而不是写在 YAML 里：清单字段是 App 与官网共同消费的契约，
 * 出错会让线上出现「有更新但下不动」或「校验值对不上」，必须能在本机跑、能断言。
 */
import fs from 'node:fs';
import { pathToFileURL } from 'node:url';

/**
 * 段头：`## v3.6.0 更新内容（2026-07-28）`
 *
 * 日期必须用全角括号——App 侧 injectDateIntoChangelog 的剥离正则是
 * `（[^）]*\d{4}-\d{2}-\d{2}[^）]*）$`（全角），半角括号不匹配会导致弹窗里
 * 渲染出两对日期括号。行尾 `\s*` 不能省：实测多一个空格就不匹配，
 * 而这种手误在编辑器里几乎看不出来。
 */
const SECTION_HEADER = /^##\s+(v\S+)\s+更新内容（(\d{4}-\d{2}-\d{2})）\s*$/;

/**
 * 解析 CHANGELOG.md 为版本段数组，顺序沿用文件顺序（约定为新→旧）。
 * 遇到以 `## ` 开头但不匹配段头格式的行直接抛错：格式漂移要在发版前暴露，
 * 而不是静默产出一份缺版本的清单。
 *
 * 这里用 throw 而不是模块里的 fail()：fail() 走 process.exit(1)，被测试
 * 或 CI 断言直接调用时会把整个进程杀掉，assert.throws 永远等不到异常。
 * 调用方（main）负责把异常转成 fail 的输出格式。
 */
export function parseChangelog(text) {
  const sections = [];
  let current = null;
  let buf = [];

  const finish = () => {
    if (!current) return;
    // 段尾的 `---` 是段间分隔线，不属于正文；留着会让 App 里多渲染一条分隔线
    const body = buf.join('\n').replace(/\n*-{3,}\s*$/, '').trim();
    sections.push({
      tagName: current.tagName,
      versionName: current.tagName.replace(/^v/, ''),
      releaseDate: current.releaseDate,
      body
    });
  };

  for (const line of text.split(/\r?\n/)) {
    const m = line.match(SECTION_HEADER);
    if (m) {
      finish();
      current = { tagName: m[1], releaseDate: m[2] };
      buf = [];
      continue;
    }
    // 任何 `## ` 开头但不匹配段头格式的行都是写错的段头——包括夹在合法段之间的。
    // 不能只在「首个合法段之前」检查：那样中段的格式漂移会被静默并进上一段正文，
    // 发版时表现为版本凭空消失 + 正文串段。`### ` 不会命中（第三个字符是 # 不是空白）。
    if (/^##\s/.test(line)) {
      throw new Error(
        `段头格式非法：${JSON.stringify(line)}。要求形如 \`## v1.2.3 更新内容（2026-01-01）\`（日期用全角括号）`
      );
    }
    if (current) buf.push(line);
  }
  finish();
  return sections;
}

// CLI 参数在 runCli() 里赋值：flag() 要留在模块作用域供 main() 与测试共用，
// 所以这里用可重新赋值的模块级变量而不是 const。
let args = [];

const flag = (name) => {
  const i = args.indexOf(`--${name}`);
  return i === -1 ? null : args[i + 1];
};

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

// 被 `node .ci/make-manifest.mjs ...` 直接调用才跑 CLI；被测试 import 时不执行
const invokedDirectly =
  process.argv[1] && import.meta.url === pathToFileURL(process.argv[1]).href;

if (invokedDirectly) main();

function main() {
  try {
    runCli();
  } catch (e) {
    // parseChangelog 用 throw 报错（见其注释），在这里统一转成 fail 的输出格式
    fail(e.message);
  }
}

function runCli() {
  args = process.argv.slice(2);
  const mode = args[0];
  if (!mode || !['latest', 'history', 'section'].includes(mode)) {
    fail(`用法: make-manifest.mjs <latest|history|section> --out <path> [--releases <path>] [--version <v>]`);
  }
  const out = flag('out');
  if (!out) fail('缺少 --out');

  if (mode === 'section') {
    const version = flag('version');
    if (!version) fail('section 需要 --version <v>，例如 --version v3.6.0');
    const changelogFile = flag('changelog') || 'CHANGELOG.md';
    const sections = parseChangelog(fs.readFileSync(changelogFile, 'utf8'));
    const hit = sections.find((s) => s.tagName === version);
    if (!hit) {
      fail(`${changelogFile} 里找不到 ${version} 的段。已解析到 ${sections.length} 个版本，顶部是 ${sections[0]?.tagName ?? '（空）'}`);
    }
    const text = `## ${hit.tagName} 更新内容（${hit.releaseDate}）\n\n${hit.body}\n`;
    fs.writeFileSync(out, text);
    console.log(`已导出 ${hit.tagName}（${hit.releaseDate}），${hit.body.length} 字节`);
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
  } else if (mode === 'history') {
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
}

function write(path, obj) {
  const dir = path.split(/[\\/]/).slice(0, -1).join('/');
  if (dir) fs.mkdirSync(dir, { recursive: true });
  fs.writeFileSync(path, JSON.stringify(obj, null, 2) + '\n');
}
