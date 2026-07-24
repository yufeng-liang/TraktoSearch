# 邀请码管理增强实现计划

> **面向 AI 代理的执行说明：** 按任务顺序执行；每个任务完成后运行该任务的验证命令，再进入下一任务。

**目标：** 为朋友详情页增加邀请码历史、统计、状态筛选、分页和不可逆撤销，同时只保存邀请码脱敏标识，不保存可恢复的完整码。

**技术栈：** Cloudflare D1/Worker TypeScript、Pages 原生 JavaScript、Node test、Wrangler。

### 任务 1：D1 脱敏字段与 Worker 列表接口

**文件：**
- 创建：`auth-worker/migrations/0003_invite_code_mask.sql`
- 修改：`auth-worker/src/admin/admin.ts`
- 修改：`auth-worker/src/index.ts`
- 修改：`auth-worker/src/util/errors.ts`（仅在需要新增错误码时）
- 测试：`app-config/tests/admin-ui.test.mjs`

- [ ] 先新增静态断言：migration 增加 `code_mask`；创建邀请码保存 mask；列表查询包含 friend 过滤、状态条件、limit/offset；响应不返回 `code_hash`。
- [ ] 运行 `node --test app-config/tests/admin-ui.test.mjs`，确认新增断言失败。
- [ ] 新增 D1 migration；创建邀请码时生成 `前4位****后4位`，短码使用安全占位；实现 `listInvites` 的状态派生、summary 和 `hasMore`。
- [ ] 将 `GET /admin/friends/:id/invites` 接入路由，验证朋友不存在、非法 status、limit/offset 边界。
- [ ] 运行 `npm run typecheck`（`auth-worker`）和 Node 测试，确认通过。
- [ ] 提交：`feat: 增加邀请码列表接口与脱敏存储`。

### 任务 2：前端 API 与邀请码区块

**文件：**
- 修改：`app-config/public/admin/app.js`
- 修改：`app-config/public/admin/styles.css`（仅补充表格、统计和状态布局样式）

- [ ] 先扩展静态断言：存在 `getInvites`、状态筛选、summary 展示、分页和不渲染完整码的路径。
- [ ] 运行 Node 测试，确认新增断言失败。
- [ ] 增加 API client 的列表和撤销方法；在朋友详情页新增邀请码卡片，显示四项统计、状态下拉、表格和分页按钮。
- [ ] 用 `escapeHtml` 渲染所有服务端字段；时间统一通过现有秒/毫秒归一化函数格式化。
- [ ] 覆盖加载中、空列表、错误重试、筛选切换、翻页越界和朋友禁用后的只读状态。
- [ ] 运行 `node --check public/admin/app.js`、`node --test tests/admin-ui.test.mjs`。
- [ ] 提交：`feat: 增强后台邀请码列表与统计`。

### 任务 3：撤销确认与生命周期边界

**文件：**
- 修改：`auth-worker/src/admin/admin.ts`
- 修改：`app-config/public/admin/app.js`
- 测试：`app-config/tests/admin-ui.test.mjs`

- [ ] 先增加撤销边界断言：仅 AVAILABLE 可撤销，过期/已使用/已撤销返回对应错误，成功写审计日志。
- [ ] 运行测试确认断言失败。
- [ ] Worker 拒绝过期邀请码撤销；前端为 AVAILABLE 行显示撤销按钮，确认弹窗明确不可逆，撤销中禁用按钮，成功后刷新当前页和统计。
- [ ] 处理并发撤销、当前页最后一条被撤销、撤销失败和 401/Access 失效状态。
- [ ] 运行 Worker typecheck、Node 语法检查和全部 admin UI 测试。
- [ ] 提交：`fix: 收紧邀请码撤销生命周期`。

### 任务 4：集成验证与发布

**文件：** 无新增源文件；检查前述改动。

- [ ] 本地执行 migration（使用项目既有 D1 migration 命令）并确认重复执行不会破坏现有表。
- [ ] 运行 `node --check app-config/public/admin/app.js`、`node --test app-config/tests/admin-ui.test.mjs`、`npm run typecheck`。
- [ ] 运行 `./gradlew.bat assembleDebug --no-daemon`，确认 Android 授权资源未回归。
- [ ] 部署 Worker 并记录版本 ID；部署 Pages 时使用 `--branch master`，用 `pages deployment list` 确认 `Production/master`。
- [ ] 在已登录后台验证：创建后一次性完整码、脱敏列表、四项统计、五种状态筛选、分页、撤销确认、刷新和审计日志。
- [ ] 检查 `git diff --check`、工作树状态，并提交最终集成变更。
