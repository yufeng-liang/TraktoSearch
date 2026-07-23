import test from 'node:test';
import assert from 'node:assert/strict';
import fs from 'node:fs';
import path from 'node:path';
import { fileURLToPath } from 'node:url';

const root = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '..');
const appSource = fs.readFileSync(path.join(root, 'public/admin/app.js'), 'utf8');
const workerSource = fs.readFileSync(path.join(root, '../auth-worker/src/admin/admin.ts'), 'utf8');
const proxySource = fs.readFileSync(path.join(root, 'functions/admin-api/[[path]].js'), 'utf8');

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
