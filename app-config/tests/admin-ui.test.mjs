import test from 'node:test';
import assert from 'node:assert/strict';
import fs from 'node:fs';
import path from 'node:path';
import { fileURLToPath } from 'node:url';

const root = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '..');
const appSource = fs.readFileSync(path.join(root, 'public/admin/app.js'), 'utf8');
const workerSource = fs.readFileSync(path.join(root, '../auth-worker/src/admin/admin.ts'), 'utf8');
const proxySource = fs.readFileSync(path.join(root, 'functions/admin-api/[[path]].js'), 'utf8');
const indexSource = fs.readFileSync(path.join(root, '../auth-worker/src/index.ts'), 'utf8');
const migrationSource = fs.readFileSync(path.join(root, '../auth-worker/migrations/0003_invite_code_mask.sql'), 'utf8');
const authScreenSource = fs.readFileSync(path.join(root, '../app/src/main/java/com/tracktosearch/ui/screen/auth/AuthScreen.kt'), 'utf8');
const stringSources = ['values', 'values-zh', 'values-ja', 'values-ko'].map((dir) =>
    fs.readFileSync(path.join(root, `../app/src/main/res/${dir}/strings.xml`), 'utf8')
);

test('朋友创建不提交朋友到期时间，邀请码单独携带有效期', () => {
    assert.match(appSource, /朋友长期有效；邀请码单独设置有效期/);
    assert.match(appSource, /const code = invite\.inviteCode \|\| invite\.code/);
    assert.doesNotMatch(appSource, /name="expiresAt"/);
    assert.match(workerSource, /VALUES \(\?, \?, \?, 'ACTIVE', \?, NULL, \?, \?\)/);
});

test('后台表单和仪表盘关键错误不会静默失败', () => {
    assert.match(appSource, /form\.noValidate = true/);
    assert.match(appSource, /showFormError\(form, '请输入昵称。'/);
    assert.match(appSource, /bindSubmitButton\(form, submitBtn\)/);
    assert.match(appSource, /s\.totalFriends/);
    assert.match(appSource, /Promise\.all\(\[API\.getStats\(\), API\.getAuditLogs\(\)\]\)/);
    assert.match(appSource, /auditRetry/);
});

test('创建朋友后使用与路由解析器一致的 hash 格式', () => {
    assert.match(appSource, /window\.location\.hash = '#\/' \+ route/);
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

test('invite lifecycle keeps expired and used records read-only', () => {
    assert.match(workerSource, /INVITE_EXPIRED/);
    assert.match(workerSource, /invite\.expires_at < currentTime/);
    assert.match(workerSource, /if \(invite\.used_at !== null\)/);
    assert.match(workerSource, /SELECT id, kind, code_mask, expires_at, used_at, revoked_at, created_at/);
    assert.doesNotMatch(workerSource, /SELECT id, kind, code_hash, code_mask/);
});
