# 邀请码管理增强设计

## 背景

当前后台创建邀请码后只在弹窗中展示一次完整邀请码，朋友详情页没有邀请码历史、数量或生命周期管理。数据库已有 `used_at`、`revoked_at`、`expires_at` 字段和撤销接口，但没有可供后台列表展示的脱敏标识。

## 目标

- 在朋友详情页展示邀请码数量、类型、状态和时间信息。
- 完整邀请码只在创建成功响应和弹窗中出现一次；后续只展示前 4 位和后 4 位，中间用 `****` 脱敏。
- 支持按状态筛选、服务端分页和撤销未使用邀请码。
- 保留所有邀请码记录和审计日志，不提供物理删除或恢复操作。

## 非目标

- 不保存或恢复完整明文邀请码。
- 不新增“暂时失效后恢复”状态。
- 不改变 App 端邀请码激活协议。

## 生命周期

列表状态由数据库字段和当前时间派生：

| 状态 | 条件 | 操作 |
| --- | --- | --- |
| `AVAILABLE` | 未使用、未撤销、未过期 | 可撤销 |
| `USED` | `used_at IS NOT NULL` | 只读 |
| `EXPIRED` | 未使用、未撤销且 `expires_at < now` | 只读 |
| `REVOKED` | `revoked_at IS NOT NULL` | 只读 |

撤销是不可逆的软删除：只更新 `revoked_at`，不删除记录；已使用或已撤销的邀请码再次撤销返回现有业务错误。

## 数据库

新增 migration，为 `invites` 增加：

```sql
ALTER TABLE invites ADD COLUMN code_mask TEXT;
```

创建邀请码时由 Worker 在计算 hash 前生成 `code_mask`：完整码长度足够时取 `code.slice(0, 4) + '****' + code.slice(-4)`，只保存脱敏值。旧数据的 `code_mask` 允许为空，后台显示 `—`，不能尝试从 hash 反推。

## Worker API

新增 `GET /admin/friends/:friendId/invites`，参数：

- `status`：`ALL`、`AVAILABLE`、`USED`、`EXPIRED`、`REVOKED`，默认 `ALL`。
- `limit`：默认 50，最大 50。
- `offset`：默认 0，非法值归零。

响应 `data`：

```json
{
  "friendId": "...",
  "invites": [
    {
      "id": "...",
      "kind": "ACTIVATION",
      "code_mask": "XZVD****B9AS",
      "status": "AVAILABLE",
      "expires_at": 1780000000,
      "used_at": null,
      "revoked_at": null,
      "created_at": 1779000000
    }
  ],
  "summary": {
    "total": 3,
    "available": 1,
    "used": 1,
    "expired": 1,
    "revoked": 0
  },
  "limit": 50,
  "offset": 0,
  "hasMore": false
}
```

状态过滤条件必须在 SQL 中参数化，不能依赖前端过滤。列表查询和统计查询都限定 `friend_id`，朋友不存在返回 `NOT_FOUND`。

继续使用 `POST /admin/invites/:id/revoke`：校验未使用、未撤销后更新 `revoked_at`，写入 `INVITE_REVOKE` 审计日志。路由和错误码保持兼容。

## 后台 UI

朋友详情页新增“邀请码”区块：

- 顶部显示总数、可用、已使用、已失效四项统计。
- 状态筛选使用下拉菜单，表格支持上一页/下一页。
- 表格显示脱敏码、类型、状态、创建时间、有效期、使用时间和操作。
- 只有 `AVAILABLE` 行显示“撤销”；点击后弹出明确的不可逆影响确认，成功后刷新当前列表和统计。
- 加载、空数据、接口失败和撤销中状态必须可见；筛选或翻页失败不得静默。
- 创建邀请码弹窗保持一次性展示完整码和复制操作，关闭后返回详情页并刷新邀请码区块。

## 边界与安全

- 过期邀请码不可撤销，显示为 `EXPIRED`；撤销优先级高于过期，已撤销始终显示 `REVOKED`。
- 已使用邀请码不能撤销，避免把已经建立的授权历史误标为管理员撤销。
- 旧邀请码没有 `code_mask` 时只显示占位符，不暴露 hash 或任何可用于猜测的内容。
- 列表 API 不返回 `code_hash`，创建 API 之外不返回完整邀请码。
- 朋友被禁用后仍可查看历史邀请码，但不能创建新码；已有码的最终激活行为继续由 App API 的朋友状态校验决定。

## 验证

- Node 静态测试覆盖：脱敏字段写入、状态派生、分页参数、撤销约束、前端统计/筛选/刷新和不展示完整邀请码。
- Worker `typecheck` 与 migration 本地执行通过。
- Pages JS `node --check` 和 admin UI 测试通过。
- Android `assembleDebug` 回归通过，确认激活错误文案资源不受影响。
- 部署后检查 `pages deployment list` 的 `Production/master` 记录，并在已登录后台验证创建、筛选、翻页、撤销和刷新流程。
