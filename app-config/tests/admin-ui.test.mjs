import test from 'node:test';
import assert from 'node:assert/strict';
import fs from 'node:fs';
import path from 'node:path';
import { fileURLToPath } from 'node:url';

const root = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '..');
test('admin branding uses the app icon for both logo and favicon', () => {
    assert.match(htmlSource, /<img class="brand-mark" src="app-icon\.png"/);
    assert.match(htmlSource, /<link rel="icon" type="image\/png" href="app-icon\.png">/);
});
const appSource = fs.readFileSync(path.join(root, 'public/admin/app.js'), 'utf8');
const htmlSource = fs.readFileSync(path.join(root, 'public/admin/index.html'), 'utf8');
const stylesSource = fs.readFileSync(path.join(root, 'public/admin/styles.css'), 'utf8');
const workerSource = fs.readFileSync(path.join(root, '../auth-worker/src/admin/admin.ts'), 'utf8');
const proxySource = fs.readFileSync(path.join(root, 'functions/admin-api/[[path]].js'), 'utf8');
const indexSource = fs.readFileSync(path.join(root, '../auth-worker/src/index.ts'), 'utf8');
const migrationSource = fs.readFileSync(path.join(root, '../auth-worker/migrations/0003_invite_code_mask.sql'), 'utf8');
const softDeleteMigrationSource = fs.readFileSync(path.join(root, '../auth-worker/migrations/0008_device_soft_delete.sql'), 'utf8');
const authScreenSource = fs.readFileSync(path.join(root, '../app/src/main/java/com/tracktosearch/ui/screen/login/ActivationLoginScreen.kt'), 'utf8');
const stringSources = ['values', 'values-zh', 'values-ja', 'values-ko'].map((dir) =>
    fs.readFileSync(path.join(root, `../app/src/main/res/${dir}/strings.xml`), 'utf8')
);

test('用户创建不提交用户到期时间，邀请码单独携带有效期', () => {
    assert.match(appSource, /用户长期有效；邀请码单独设置有效期/);
    assert.match(appSource, /const code = invite\.inviteCode \|\| invite\.code/);
    const createFriendModal = appSource.match(/function showCreateFriendModal\([\s\S]*?function showEditFriendModal\(/);
    assert.ok(createFriendModal, 'create friend modal should exist');
    assert.doesNotMatch(createFriendModal[0], /name="expiresAt"/);
    assert.match(workerSource, /INSERT INTO friends \(id, nickname, email, note, status, max_devices, expires_at, created_at, updated_at\)[\s\S]*VALUES \(\?, \?, \?, \?, 'ACTIVE', \?, NULL, \?, \?\)/);
});

test('后台表单和仪表盘关键错误不会静默失败', () => {
    assert.match(appSource, /form\.noValidate = true/);
    assert.match(appSource, /showFormError\(form, '请输入昵称。'/);
    assert.match(appSource, /bindSubmitButton\(form, submitBtn\)/);
    assert.match(appSource, /s\.totalFriends/);
    // 仪表盘已扩到五路独立降级，钉住解构形状与两个原始调用
    assert.match(appSource, /const \[statsRes, auditRes, aiRes, crashRes, feedbackRes\] = await Promise\.allSettled\(\[/);
    assert.match(appSource, /API\.getStats\(\),/);
    assert.match(appSource, /API\.getAuditLogs\(\{ limit: 50, offset: 0 \}\),/);
    assert.match(appSource, /auditRetry/);
});

test('dashboard failure view opens an all-time paginated modal', () => {
    assert.match(appSource, /js-view-all-failures/);
    assert.doesNotMatch(appSource, /href="#\/audit" class="btn btn-ghost btn-sm">查看全部/);
    assert.match(appSource, /function showAllFailuresModal/);
    assert.match(appSource, /API\.getAuditLogs\(\{ result: 'FAILURE', limit, offset \}\)/);
    assert.match(appSource, /failure-modal-pagination/);
});

test('创建朋友后使用与路由解析器一致的 hash 格式', () => {
    assert.match(appSource, /const nextHash = '#\/' \+ route/);
});

test('管理代理清理认证和长度头并返回上游降级响应', () => {
    assert.match(proxySource, /headers\.delete\('Host'\)/);
    assert.match(proxySource, /headers\.delete\('Content-Length'\)/);
    assert.match(proxySource, /UPSTREAM_UNAVAILABLE/);
});

test('migration invite is blocked by the admin page when no active device exists', () => {
    assert.match(appSource, /showCreateInviteModal\(f\.id, f\.nickname, f\.devices\)/);
    assert.match(appSource, /function showCreateInviteModal\(friendId, friendName, activeDeviceCount = 0\)/);
    assert.match(appSource, /MIGRATION_DEVICE_NOT_FOUND/);
    assert.match(appSource, /迁移邀请码需要已有绑定设备/);
});

test('admin timestamps from Worker seconds are rendered as real dates', () => {
    assert.match(appSource, /function toDateMs\(value\)/);
    assert.match(appSource, /timestamp < 1e12 \? timestamp \* 1000 : timestamp/);
    assert.match(appSource, /formatDate\(expiresAt\)/);
});

test('Worker rejects migration invite creation without an active device', () => {
    assert.match(workerSource, /kind === 'MIGRATION'/);
    assert.match(workerSource, /MIGRATION_DEVICE_NOT_FOUND/);
    assert.match(workerSource, /WHERE friend_id = \? AND status = 'ACTIVE'/);
});

test('auth screen maps migration and duplicate-device errors in every locale', () => {
    assert.match(authScreenSource, /MIGRATION_DEVICE_NOT_FOUND/);
    assert.match(authScreenSource, /MIGRATION_DEVICE_MISMATCH/);
    assert.match(authScreenSource, /DEVICE_ALREADY_BOUND/);
    for (const source of stringSources) {
        assert.match(source, /auth_error_migration_device_not_found/);
        assert.match(source, /auth_error_migration_device_mismatch/);
        assert.match(source, /auth_error_device_already_bound/);
    }
});

test('invite list stores only a masked code and exposes paginated status data', () => {
    assert.match(migrationSource, /ADD COLUMN code_mask TEXT/);
    assert.match(workerSource, /code_mask/);
    assert.match(workerSource, /function maskInviteCode|const codeMask/);
    assert.match(workerSource, /export async function listInvites/);
    assert.match(workerSource, /hasMore/);
    assert.match(workerSource, /summary/);
    assert.match(workerSource, /code_hash/);
    assert.match(indexSource, /inviteCreateMatch/);
    assert.match(indexSource, /request\.method === 'GET'/);
});

test('invite management UI renders masked records, summary, filtering, pagination and revoke', () => {
    assert.match(appSource, /getInvites\(/);
    assert.match(appSource, /AVAILABLE|USED|EXPIRED|REVOKED/);
    assert.match(appSource, /hasMore/);
    assert.match(appSource, /revokeInvite\(/);
    assert.match(appSource, /code_mask|codeMask/);
    assert.match(appSource, /INVITE_REVOKE/);
});

test('friend detail exposes device identity and recovery summaries', () => {
    assert.match(workerSource, /active_devices/);
    assert.match(workerSource, /revoked_devices/);
    assert.match(workerSource, /recovery_count/);
    assert.match(workerSource, /has_recovery_identity/);
    assert.match(appSource, /device-summary-grid/);
    assert.match(appSource, /设备列表[\s\S]*device-summary-grid[\s\S]*设备总数/);
    assert.match(appSource, /possibleDuplicate/);
    assert.match(appSource, /REINSTALL_RECOVER/);
    assert.match(workerSource, /export async function listDevices/);
    assert.match(workerSource, /summary: friend/);
    assert.match(workerSource, /recovery_id_hmac IS NOT NULL AS has_recovery_identity/);
});

test('friend records expose and edit the user email address', () => {
    assert.match(workerSource, /f\.email/);
    assert.match(workerSource, /email\?: string/);
    assert.match(workerSource, /normalizeOptionalEmail/);
    assert.match(appSource, /email: f\.email/);
    assert.match(appSource, /name="email"/);
    assert.match(appSource, /API\.updateFriend\(friend\.id, \{ nickname, email: email \|\| null/);
});

test('revoked device records can be soft-deleted from the admin UI', () => {
    assert.match(softDeleteMigrationSource, /ALTER TABLE devices ADD COLUMN deleted_at INTEGER/);
    assert.match(workerSource, /export async function deleteDevice/);
    assert.match(workerSource, /deleted_at IS NULL/);
    assert.match(workerSource, /DEVICE_DELETE/);
    assert.match(indexSource, /request\.method === 'DELETE'/);
    assert.match(appSource, /async deleteDevice\(deviceId\)/);
    assert.match(appSource, /showDeleteDeviceModal/);
    assert.match(appSource, /js-delete-device/);
    assert.match(appSource, /btn-danger btn-sm js-delete-device/);
});

test('invite lifecycle keeps expired and used records read-only', () => {
    assert.match(workerSource, /INVITE_EXPIRED/);
    assert.match(workerSource, /invite\.expires_at < currentTime/);
    assert.match(workerSource, /if \(invite\.used_at !== null\)/);
    assert.match(workerSource, /used_at IS NULL AND revoked_at IS NULL AND expires_at >= \?/);
    assert.match(workerSource, /updateResult\.meta\.changes !== 1/);
    assert.match(workerSource, /SELECT id, kind, code_mask, expires_at, used_at, revoked_at, created_at/);
    assert.doesNotMatch(workerSource, /SELECT id, kind, code_hash, code_mask/);
});

test('invite card keeps vertical spacing and a stable filter width', () => {
    const stylesSource = fs.readFileSync(path.join(root, 'public/admin/styles.css'), 'utf8');
    assert.match(stylesSource, /\.detail-grid > div:not\(\.card\)/);
    assert.match(stylesSource, /gap:\s*20px/);
    assert.match(stylesSource, /\.card-header \.invite-status-filter/);
    assert.match(stylesSource, /flex:\s*0 0 220px/);
    assert.match(stylesSource, /width:\s*160px;\s*flex-basis:\s*160px/);
});

test('friend detail uses full-width lists and places disable action in the heading', () => {
    const stylesSource = fs.readFileSync(path.join(root, 'public/admin/styles.css'), 'utf8');
    assert.match(appSource, /title\.className = 'detail-heading'/);
    assert.match(appSource, /friend-detail-grid/);
    assert.match(appSource, /content\.appendChild\(inviteSection\)/);
    assert.doesNotMatch(appSource, /content\.firstElementChild\?\.appendChild\(inviteSection\)/);
    assert.match(stylesSource, /\.friend-detail-grid \{\s*grid-template-columns: minmax\(0, 1fr\);/);
    assert.match(stylesSource, /\.detail-heading \{/);
});

test('formatTime uses normalized milliseconds for dates beyond 30 days', () => {
    assert.match(appSource, /return new Date\(dateMs\)\.toLocaleDateString\('zh-CN'\)/);
});

test('feedback screenshots use the same-origin admin proxy', () => {
    assert.match(appSource, /const FEEDBACK_SCREENSHOT_BASE_URL = `\$\{API_BASE\}\/fb\/feedback-api\/screenshot`/);
    assert.match(appSource, /FEEDBACK_SCREENSHOT_BASE_URL\}\/\$\{encodeURIComponent\(k\)\}/);
    assert.doesNotMatch(appSource, /feedback-worker\.douban-movie-api-peak\.workers\.dev/);
});

test('feedback admin APIs use the app-config Service Binding proxy', () => {
    assert.doesNotMatch(appSource, /const isFeedbackRequest = path\.startsWith\('\/fb\/'\)/);
    assert.doesNotMatch(appSource, /const requestBase = isFeedbackRequest/);
    assert.match(appSource, /fetch\(`\$\{API_BASE\}\$\{path\}`/);
});

test('反馈详情标题只展示反馈 ID 和用户，内容区隐藏类型行', () => {
    const stylesSource = fs.readFileSync(path.join(root, 'public/admin/styles.css'), 'utf8');
    assert.match(appSource, /feedback-detail-subtitle/);
    assert.match(appSource, /<span class="fb-id-badge"[\s\S]*?<\/span><span>· \$\{escapeHtml\(f\.friend_nickname \|\| ''\)\}<\/span>/);
    assert.doesNotMatch(appSource, /<p class="section-subtitle feedback-detail-subtitle">[\s\S]*?typeLabels\[f\.type\]/);
    assert.doesNotMatch(appSource, /<span style="color:var\(--text-dim\)">类型<\/span>/);
    assert.match(stylesSource, /\.feedback-detail-subtitle \{\s*display: flex;\s*align-items: center;/);
    assert.match(stylesSource, /\.feedback-detail-subtitle \.fb-id-badge \{[\s\S]*?font-size: 16px;/);
});

test('反馈对话将开发者显示为自己并右对齐，用户左对齐且尖角位于底部', () => {
    const stylesSource = fs.readFileSync(path.join(root, 'public/admin/styles.css'), 'utf8');
    assert.match(appSource, /document\.getElementById\('fb-back'\)\.addEventListener\('click', \(\) => navigate\('feedback'\)\)/);
    assert.doesNotMatch(appSource, /document\.getElementById\('fb-back'\)[\s\S]*?location\.hash = '#\/feedback'/);
    assert.match(appSource, /const roleLabel = role === 'developer' \? '自己' : '用户';/);
    assert.match(appSource, /role === 'developer' \? '我' : 'U'/);
    assert.match(stylesSource, /\.fb-bubble-row\.developer \{\s*flex-direction: row-reverse;/);
    assert.match(stylesSource, /\.fb-bubble-row\.user \{\s*flex-direction: row;/);
    assert.match(stylesSource, /\.fb-bubble-row\.developer \.fb-bubble-meta \{\s*text-align: right;/);
    assert.match(stylesSource, /\.fb-bubble-row\.user \.fb-bubble-meta \{\s*text-align: left;/);
    assert.match(stylesSource, /\.fb-bubble\.developer \{[\s\S]*?border-bottom-right-radius: 4px;/);
    assert.match(stylesSource, /\.fb-bubble\.user \{[\s\S]*?border-bottom-left-radius: 4px;/);
    assert.doesNotMatch(stylesSource, /\.fb-bubble\.developer \{[\s\S]*?border-top-(left|right)-radius: 4px;/);
    assert.doesNotMatch(stylesSource, /\.fb-bubble\.user \{[\s\S]*?border-top-(left|right)-radius: 4px;/);
});

test('反馈列表与筛选区保留标准列表上边距', () => {
    const stylesSource = fs.readFileSync(path.join(root, 'public/admin/styles.css'), 'utf8');
    assert.match(appSource, /tableWrap\.className = 'table-wrap feedback-table-wrap'/);
    assert.match(stylesSource, /\.feedback-table-wrap \{\s*margin-top: 20px;/);
});

test('用户状态使用有效文案', () => {
    assert.match(appSource, /ACTIVE: '有效'/);
    assert.match(appSource, /<option value="ACTIVE">有效<\/option>/);
});

test('navigation renders once through hashchange for a changed route', () => {
    const navigateBlock = appSource.match(/function navigate\([\s\S]*?\r?\n}\r?\n/);
    assert.ok(navigateBlock, 'navigate function should exist');
    assert.doesNotMatch(navigateBlock[0], /window\.location\.hash = nextHash;[\s\S]*?render\(\);/);
});

test('admin API requests have a timeout', () => {
    assert.match(appSource, /new AbortController\(\)/);
    assert.match(appSource, /timedOut = true;[\s\S]*?controller\.abort\(\)/);
    assert.match(appSource, /REQUEST_TIMEOUT_MS/);
});

test('toast entrance animation uses milliseconds', () => {
    const stylesSource = fs.readFileSync(path.join(root, 'public/admin/styles.css'), 'utf8');
    assert.match(stylesSource, /animation: toast-in 250ms/);
});

test('friend list delegates search and pagination to the server', () => {
    assert.match(appSource, /API\.getFriends\(\{ q: query, status: filter, limit, offset \}\)/);
    assert.match(appSource, /friendsPage\.total/);
    assert.match(workerSource, /LIMIT \? OFFSET \?/);
    assert.match(workerSource, /nickname LIKE|LOWER\(f\.nickname\)/);
});

test('后台统一使用用户文案，用户列表用设备数量展示上限', () => {
    assert.doesNotMatch(htmlSource, /朋友/);
    assert.doesNotMatch(appSource, /朋友/);
    assert.match(appSource, /<th>设备<\/th><th>有效期至<\/th>/);
    assert.doesNotMatch(appSource, /<th>上限<\/th>/);
    assert.match(appSource, /<td>\$\{f\.devices\}\/\$\{f\.maxDevices\}<\/td>/);
    assert.doesNotMatch(appSource, /\n\s*<td>\$\{f\.maxDevices\}<\/td>/);
});

test('admin status and reduced motion states are accessible', () => {
    assert.match(htmlSource, /id="toastContainer"[^>]*role="status"[^>]*aria-live="polite"/);
    assert.match(appSource, /setAttribute\('aria-current', 'page'\)/);
    const stylesSource = fs.readFileSync(path.join(root, 'public/admin/styles.css'), 'utf8');
    assert.match(stylesSource, /\.main\s*\{\s*scroll-behavior: auto;/);
});

test('admin forms expose labels, autocomplete intent and inline error semantics', () => {
    assert.match(appSource, /label class="form-label" for="create-friend-nickname"/);
    assert.match(appSource, /id="create-friend-nickname"[^>]*autocomplete="nickname"/);
    assert.match(appSource, /label class="form-label" for="edit-friend-expires-at"/);
    assert.match(appSource, /error\.id = `\$\{form\.id\}-error`/);
    assert.match(appSource, /form\.setAttribute\('aria-describedby', error\.id\)/);
});

test('mobile sidebar has an accessible scrim and escape close behavior', () => {
    assert.match(htmlSource, /id="sidebarScrim"[^>]*aria-label="关闭侧栏"/);
    assert.match(htmlSource, /id="menuToggle"[^>]*aria-controls="sidebar"[^>]*aria-expanded="false"/);
    assert.match(appSource, /e\.key === 'Escape'[\s\S]*?closeSidebar\(\)/);
    assert.match(appSource, /sidebarScrim.*addEventListener\('click'/);
    const stylesSource = fs.readFileSync(path.join(root, 'public/admin/styles.css'), 'utf8');
    assert.match(stylesSource, /\.sidebar-scrim\[hidden\]\s*\{\s*display:\s*none/);
});

test('invite copy falls back when Clipboard API is unavailable', () => {
    assert.match(appSource, /async function copyText\(text\)/);
    assert.match(appSource, /document\.execCommand\('copy'\)/);
    assert.match(appSource, /await copyText\(code\)/);
});

test('dashboard retry preserves render context for partial failures', () => {
    assert.match(appSource, /dashboardErrorCard\('失败记录加载失败', auditRes\.reason, container, renderToken\)/);
});

test('friend detail uses the targeted aggregate endpoint', () => {
    assert.match(appSource, /this\.get\(`\/admin\/friends\/\$\{id\}\/detail`\)/);
    assert.match(appSource, /maxDevices: source\.max_devices/);
    assert.match(appSource, /expiresAt: source\.expires_at/);
    assert.match(workerSource, /export async function getFriendDetail/);
    assert.match(indexSource, /const friendDetailMatch = path\.match/);
    assert.match(indexSource, /getFriendDetail\(env, requestId, friendDetailMatch\[1\]\)/);
});

test('page transitions cancel in-flight admin requests', () => {
    assert.match(appSource, /const activeRequestControllers = new Set\(\)/);
    assert.match(appSource, /activeRequestControllers\.add\(controller\)/);
    assert.match(appSource, /function cancelPendingRequests\(\)/);
    assert.match(appSource, /cancelPendingRequests\(\);\s*const renderToken/);
});

test('toolbar controls expose accessible names and expanded state', () => {
    assert.match(htmlSource, /id="paletteBtn"[^>]*aria-controls="palettePanel"[^>]*aria-expanded="false"/);
    assert.match(appSource, /searchInput.*aria-label/);
    assert.match(appSource, /statusFilter.*aria-label/);
    assert.match(appSource, /btn\.setAttribute\('aria-expanded', String\(!panel\.hidden\)\)/);
});

test('disabled friends can be re-enabled without restoring revoked devices', () => {
    assert.match(appSource, /API\.enableFriend\(friendId\)/);
    assert.match(appSource, /重新启用用户/);
    assert.match(appSource, /不会恢复已撤销设备和会话/);
    assert.match(appSource, /FRIEND_ENABLE/);
    assert.match(workerSource, /export async function enableFriend/);
    assert.match(workerSource, /UPDATE friends SET status = 'ACTIVE'/);
    assert.match(workerSource, /VALUES \('FRIEND_ENABLE'/);
    assert.match(indexSource, /const enableMatch = path\.match/);
});

test('admin visual system favors stable density over glass and motion cost', () => {
    const stylesSource = fs.readFileSync(path.join(root, 'public/admin/styles.css'), 'utf8');
    assert.doesNotMatch(htmlSource, /Space\+Grotesk/);
    assert.match(stylesSource, /--radius-lg:\s*8px/);
    assert.match(stylesSource, /\.ambient-blob\s*\{[\s\S]*?animation: none;/);
    assert.match(stylesSource, /backdrop-filter: blur\(16px\) saturate\(120%\)/);
    assert.match(stylesSource, /\.glass::before,\s*\.stat-card::after,\s*\.card::after/);
});

test('feedback detail is reachable by hash deep link from the notification email', () => {
    // 邮件里的深链是 admin/#/feedback/{id}，必须真能进详情页；
    // 此前 render() 的 feedback 分支忽略 params.id，链接只会落到列表页。
    assert.match(
        appSource,
        /case 'feedback':[\s\S]*?if \(state\.params\.id\) showFeedbackDetail\(state\.params\.id, main, renderToken\);[\s\S]*?else renderFeedback\(main, renderToken\);/,
    );

    // 真执行 parseHash，证明 id 确实落进 state.params（而不是只匹配源码文本）
    const fnSource = appSource.match(/function parseHash\(\) \{[\s\S]*?\n\}/);
    assert.ok(fnSource, 'parseHash should exist');
    const run = hash => {
        const state = {};
        const window = { location: { hash } };
        new Function('window', 'state', `${fnSource[0]}\nparseHash();`)(window, state);
        return state;
    };

    const deepLink = run('#/feedback/550e8400-e29b-41d4-a716-446655440000');
    assert.equal(deepLink.route, 'feedback');
    assert.equal(deepLink.params.id, '550e8400-e29b-41d4-a716-446655440000');

    // 负控制：列表页没有 id，不能被当成详情
    const listLink = run('#/feedback');
    assert.equal(listLink.route, 'feedback');
    assert.deepEqual(listLink.params, {});
});

test('手机端底部标签栏接管主导航，顶栏不再重复一套链接', () => {
    assert.match(htmlSource, /<nav class="tabbar" id="tabbar" aria-label="主导航">/);
    const tabs = htmlSource.match(/class="tab-link" data-route="[a-z-]+"/g) || [];
    assert.equal(tabs.length, 4, '底部应是 4 个页面标签 + 1 个「更多」');
    assert.match(htmlSource, /class="tab-link tab-more" id="tabMore"[\s\S]*?aria-expanded="false"/);
    assert.doesNotMatch(htmlSource, /class="topbar-nav"/, '顶栏那套导航与侧栏重复且少了反馈管理');
    assert.doesNotMatch(stylesSource, /\.topbar-nav|\.nav-link\b/, '删掉的导航不该留下死样式');
    assert.match(appSource, /querySelectorAll\('\.sidebar-link, \.tab-link\[data-route\]'\)/);
    // 抽屉在手机上只装底部没放下的两个页面
    assert.equal((htmlSource.match(/<li data-tab-dup>/g) || []).length, 4);
    assert.match(stylesSource, /\.sidebar-list li\[data-tab-dup\] \{ display: none; \}/);
});

test('卡片化的字段名取自同一张表的 <th>，模板不维护第二份标签', () => {
    assert.match(appSource, /function labelizeTables\(root\) \{/);
    assert.match(appSource, /cell\.dataset\.label = labels\[index\]/);
    // 骨架 / 空态 / 错误横幅是整行 colspan，不能被贴上字段名
    assert.match(appSource, /if \(cell\.colSpan > 1\) return/);
    assert.ok((appSource.match(/labelizeTables\(/g) || []).length >= 11, '每张表的渲染点都要调用一次');
    // 邮箱列改回模板直写：事后插 <th> 会让列数与硬写 colspan 各自漂移
    assert.match(appSource, /<th>昵称<\/th><th>邮箱<\/th><th>状态<\/th>/);
    assert.doesNotMatch(appSource, /insertAdjacentHTML\('afterend', '<th>邮箱<\/th>'\)/);
    assert.match(appSource, /<table data-card-wide="2" data-card-hide="7">/);
});

test('parseColumnIndexes 只收整数列下标，缺省不会命中所有列', () => {
    const src = appSource.match(/function parseColumnIndexes\(value\) \{[\s\S]*?\n\}/);
    assert.ok(src, 'parseColumnIndexes should exist');
    const parse = new Function(`return (${src[0]})`)();
    assert.deepEqual(parse('2,7'), [2, 7]);
    assert.deepEqual(parse(undefined), [], '缺省不能产出会误伤整列的值');
    assert.deepEqual(parse('3,'), [3]);
});

test('手机端排版硬约束：动态视口高度、44px 触控靶、截图留在应用内', () => {
    assert.ok(
        stylesSource.indexOf('height: 100dvh') > stylesSource.indexOf('height: 100vh'),
        'dvh 必须写在 vh 之后才会生效'
    );
    const mobileBlocks = (stylesSource.match(/@media \(max-width: 640px\)[\s\S]*?\n\}/g) || []).join('\n');
    assert.match(mobileBlocks, /\.tabbar \{/);
    assert.match(mobileBlocks, /\.btn-sm \{ padding: 7px 12px; min-height: 44px/);
    assert.match(mobileBlocks, /\.palette-btn \{ padding: 6px 8px; min-height: 44px/);
    assert.match(mobileBlocks, /\.ai-probe-model \{[^}]*min-height: 44px/);
    assert.match(mobileBlocks, /\.detail-heading-actions \{[\s\S]*?position: fixed/);
    assert.match(mobileBlocks, /\.fb-reply-dock \{[\s\S]*?position: sticky/);
    // 吸底回复区必须不透明，否则钉住时会把底下的对话文字透出来
    assert.match(mobileBlocks, /\.fb-reply-dock \{[\s\S]*?background: var\(--surface-solid\)/);
    // 统计加载失败时只有一张错误卡，不能落在半格网格里把中文挤成一列一字
    assert.match(appSource, /<div class="card dashboard-error-card"><div class="error-banner">/);
    assert.match(stylesSource, /\.dashboard-error-card \{ grid-column: 1 \/ -1; \}/);
    assert.doesNotMatch(appSource, /window\.open\(img\.dataset\.key/, '手机上开新标签会丢掉当前页面状态');
    assert.match(appSource, /function showImageLightbox\(src, alt\)/);
});

test('详情卡的「标签—值」行由类名承载，标签不参与收缩', () => {
    assert.match(stylesSource, /\.kv-row > span:first-child \{ flex: 0 0 auto; \}/);
    assert.ok(
        !appSource.includes('style="display:flex;justify-content:space-between"'),
        '内联排版会让中文标签在窄屏逐字竖排'
    );
});

test('平板档（641–1024）按触屏对待，时间列不再断成两行', () => {
    const tablet = stylesSource.match(/@media \(min-width: 641px\) and \(max-width: 1024px\)[\s\S]*?\n\}/);
    assert.ok(tablet, '平板档触控靶段落应存在');
    assert.match(tablet[0], /\.btn-sm \{ min-height: 44px; \}/);
    assert.match(tablet[0], /\.form-input, \.form-select \{ min-height: 44px; font-size: 16px; \}/);
    // 下界必须写 641px，否则会把上面 ≤640 段落已定的手机尺寸反向盖掉
    assert.match(stylesSource, /\.cell-nowrap \{ white-space: nowrap; \}/);
    assert.ok((appSource.match(/class="cell-nowrap"/g) || []).length >= 2, '审计与反馈的时间列都要贴上');
    const mobileBlocks = (stylesSource.match(/@media \(max-width: 640px\)[\s\S]*?\n\}/g) || []).join('\n');
    // 卡片头当标题用，nowrap 的时间戳在手机上要能换行
    assert.match(mobileBlocks, /\.table-scroll td:first-child \{[\s\S]*?white-space: normal/);
});

test('反馈列表卡片层级稳定：内容整行两行截断，次要字段单行省略', () => {
    assert.match(appSource, /<span class="cell-clamp">\$\{escapeHtml\(f\.content \|\| '—'\)\}<\/span>/);
    assert.match(appSource, /<span class="cell-ellipsis">\$\{escapeHtml\(f\.friend_nickname/);
    assert.match(appSource, /<table class="fb-list-table" data-card-wide="4">/);
    const mobileBlocks = (stylesSource.match(/@media \(max-width: 640px\)[\s\S]*?\n\}/g) || []).join('\n');
    // 时间从末列提到用户同一行，卡片才不会每行只有一项
    assert.match(mobileBlocks, /\.fb-list-table td:nth-child\(7\) \{ order: 4; \}/);
    assert.match(mobileBlocks, /\.table-scroll td\[data-card-wide\] > \.cell-clamp \{ flex: 1 1 100%; \}/);
    assert.match(stylesSource, /\.cell-ellipsis \{[\s\S]*?text-overflow: ellipsis/);

    // 列表接口的 screenshots 是 JSON 字符串：坏数据要降级成 0，不能抛错打断整页
    const src = appSource.match(/function screenshotCount\(raw\) \{[\s\S]*?\n\}/);
    assert.ok(src, 'screenshotCount should exist');
    const count = new Function(`return (${src[0]})`)();
    assert.equal(count('["a","b"]'), 2);
    assert.equal(count(null), 0);
    assert.equal(count('{"not":"array"}'), 0);
    assert.equal(count('坏数据'), 0);
});
