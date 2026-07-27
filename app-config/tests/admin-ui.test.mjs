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
const workerSource = fs.readFileSync(path.join(root, '../auth-worker/src/admin/admin.ts'), 'utf8');
const proxySource = fs.readFileSync(path.join(root, 'functions/admin-api/[[path]].js'), 'utf8');
const indexSource = fs.readFileSync(path.join(root, '../auth-worker/src/index.ts'), 'utf8');
const migrationSource = fs.readFileSync(path.join(root, '../auth-worker/migrations/0003_invite_code_mask.sql'), 'utf8');
const softDeleteMigrationSource = fs.readFileSync(path.join(root, '../auth-worker/migrations/0008_device_soft_delete.sql'), 'utf8');
const authScreenSource = fs.readFileSync(path.join(root, '../app/src/main/java/com/tracktosearch/ui/screen/login/ActivationLoginScreen.kt'), 'utf8');
const stringSources = ['values', 'values-zh', 'values-ja', 'values-ko'].map((dir) =>
    fs.readFileSync(path.join(root, `../app/src/main/res/${dir}/strings.xml`), 'utf8')
);

test('朋友创建不提交朋友到期时间，邀请码单独携带有效期', () => {
    assert.match(appSource, /朋友长期有效；邀请码单独设置有效期/);
    assert.match(appSource, /const code = invite\.inviteCode \|\| invite\.code/);
    const createFriendModal = appSource.match(/function showCreateFriendModal\([\s\S]*?function showEditFriendModal\(/);
    assert.ok(createFriendModal, 'create friend modal should exist');
    assert.doesNotMatch(createFriendModal[0], /name="expiresAt"/);
    assert.match(workerSource, /VALUES \(\?, \?, \?, 'ACTIVE', \?, NULL, \?, \?\)/);
});

test('后台表单和仪表盘关键错误不会静默失败', () => {
    assert.match(appSource, /form\.noValidate = true/);
    assert.match(appSource, /showFormError\(form, '请输入昵称。'/);
    assert.match(appSource, /bindSubmitButton\(form, submitBtn\)/);
    assert.match(appSource, /s\.totalFriends/);
    assert.match(appSource, /Promise\.allSettled\(\[API\.getStats\(\), API\.getAuditLogs\(\{ limit: 50, offset: 0 \}\)\]\)/);
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
    assert.match(appSource, /dashboardErrorCard\('失败记录加载失败', auditResult\.reason, container, renderToken\)/);
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
    assert.match(appSource, /重新启用朋友/);
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
