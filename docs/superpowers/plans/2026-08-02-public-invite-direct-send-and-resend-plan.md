# 公开邀请码直接发送与重发实现计划

> **面向实现者：** 按任务顺序执行，每个任务先运行针对性测试，再进入下一任务；不要把邀请码明文放进 HTTP 响应、日志或前端状态之外的持久化数据。

**目标：** 将公开申请改为提交后直接发送一次性邀请码，并增加 60 秒冷却的重发能力；重发时原邀请码立即失效，新邀请码有效 72 小时。邮件使用不裁切的响应式布局，在居中的邀请码下方显示官网中的吉伊图片。

**架构：** `auth-worker` 负责名额原子分配、邀请码轮换、Brevo 发信和审计；`website-trakt` 负责申请表单、重发倒计时和静态图片；Gateway 路由保持不变，仅新增 Worker API 路由转发。

**技术栈：** Cloudflare Worker + D1 + KV、Brevo SMTP API、原生 JavaScript 官网、Node `node:test`。

### 任务 1：为 Worker 直接发码与重发建立失败测试

**文件：**
- 修改：`F:\trae-project\auth-worker\tests\invite-request.test.mjs`
- 参考：`F:\trae-project\auth-worker\src\invitations.ts`
- 参考：`F:\trae-project\auth-worker\src\index.ts`

- [ ] 增加直接申请测试：模拟 D1、Brevo 和 `POST /api/invite-requests` 所需的依赖，验证成功路径直接建立 `ISSUED` 申请并发送包含邀请码的邮件，不再要求验证 token。
- [ ] 运行 `node --experimental-strip-types --test tests/invite-request.test.mjs`，确认新测试在旧实现上失败，失败原因必须是仍返回验证邮件或缺少直接发码行为。
- [ ] 增加重发测试：验证 60 秒内返回 `RESEND_COOLDOWN`，冷却结束后 D1 batch 同时撤销旧邀请码、创建新邀请码并更新申请指针。
- [ ] 增加并发测试：第二个请求不能基于同一个旧邀请码再次轮换。
- [ ] 增加邮件失败测试：新邀请码不重新激活旧邀请码，下一次重发仍可继续处理。

### 任务 2：实现 Worker 直接发码和重发路由

**文件：**
- 修改：`F:\trae-project\auth-worker\src\invitations.ts`
- 修改：`F:\trae-project\auth-worker\src\index.ts`
- 测试：`F:\trae-project\auth-worker\tests\invite-request.test.mjs`

- [ ] 在 `handleInviteRequest()` 中保留输入规范化、IP 限流、过期名额释放和同邮箱幂等判断；新申请创建内部申请记录后直接调用邀请码分配逻辑，响应状态改为 `INVITE_SENT`。
- [ ] 保留旧的邮箱验证接口以处理已经发出的历史验证链接，但官网新流程不再生成或依赖验证链接。
- [ ] 增加 `handleInviteResend()`：按邮箱查找公开申请、朋友和当前未使用邀请码；60 秒内返回剩余冷却秒数，冷却结束后生成新码。
- [ ] 使用 D1 batch 条件更新保证一次重发只会成功一次：撤销旧邀请码、插入新邀请码、更新 `invite_requests.invite_id` 和发送时间、写入 `PUBLIC_INVITE_RESEND` 审计记录。
- [ ] 重发成功和首次发送都只通过邮件传递邀请码，不把明文邀请码返回给浏览器或写入日志。
- [ ] 在 `src/index.ts` 增加 `POST /api/invite-requests/resend` 路由，并保持统一错误响应格式。
- [ ] 运行 `npm run typecheck` 和 `node --experimental-strip-types --test tests/invite-request.test.mjs`，确认新增测试通过。

### 任务 3：重构 Brevo 邮件模板与图片

**文件：**
- 修改：`F:\trae-project\auth-worker\src\invitations.ts`
- 测试：`F:\trae-project\auth-worker\tests\invite-request.test.mjs`
- 资源：`F:\website-trakt\assets\chiikawa\ai-chiikawa-love.png`

- [ ] 将邮件外层改为兼容 QQ 邮箱的 table 布局：外层 `width=100%`，内容区 `max-width=600px`，移动端左右内边距 `16px`，避免固定宽度和 padding 叠加造成横向裁切。
- [ ] 邀请码区块设置 `text-align:center`，邀请码文字水平居中。
- [ ] 在邀请码区块下方加入独立居中的图片容器，引用 `https://tracktosearch.pages.dev/assets/chiikawa/ai-chiikawa-love.png`，图片最大宽度约 `180px`，同时提供纯文本正文。
- [ ] 直接发码邮件不再展示邮箱验证链接；官网链接改为官网根地址。
- [ ] 测试 HTML 包含居中规则、图片 HTTPS 地址、邀请码、有效期和反馈链接。

### 任务 4：更新官网申请与重发交互

**文件：**
- 修改：`F:\website-trakt\pages\index.html`
- 修改：`F:\website-trakt\scripts\check-site.mjs`

- [ ] 更新表单文案为“提交后从收件箱获取邀请码”，移除验证链接 30 分钟提示。
- [ ] 增加 `data-invite-resend-api` 和重发按钮；首次提交成功后保留邮箱值，重发按钮初始禁用并显示 60 秒倒计时。
- [ ] 初次提交使用 `INVITE_SENT` 成功状态；重发成功后重置倒计时并提示旧邀请码已失效。
- [ ] 将 `RESEND_COOLDOWN`、`PUBLIC_INVITE_LIMIT_REACHED`、`EMAIL_SEND_FAILED` 等错误映射为用户可读状态。
- [ ] 保留旧验证页兼容代码，但不再作为新申请入口。
- [ ] 更新站点检查，保护直接发码文案、重发 API、重发按钮和邀请码图片引用。

### 任务 5：验证、提交和生产验收

**文件：**
- 代码变更：`F:\trae-project\auth-worker\src\invitations.ts`、`F:\trae-project\auth-worker\src\index.ts`、`F:\trae-project\auth-worker\tests\invite-request.test.mjs`
- 官网变更：`F:\website-trakt\pages\index.html`、`F:\website-trakt\scripts\check-site.mjs`

- [ ] 运行 Worker 全量测试：`node --experimental-strip-types --test (Get-ChildItem -LiteralPath tests -Filter '*.mjs').FullName`。
- [ ] 运行 Worker 类型检查：`npm run typecheck`。
- [ ] 构建并检查官网：`npm run build:production`、`npm run check:site`，确认 `dist/assets/chiikawa/ai-chiikawa-love.png` 存在。
- [ ] 运行 `npx wrangler deploy --dry-run` 检查 Worker 包和绑定。
- [ ] 从 `F:\website-trakt` 部署 Pages，验证官网、Gateway 和图片 URL 均返回 200。
- [ ] 部署 Worker，验证 Worker/Gateway `/health` 返回 200。
- [ ] 对真实测试邮箱执行一次申请和一次重发，确认第一封邮件含新码、重发后旧码失效、新码可用；不在命令输出中打印 API Key 或邀请码明文。
- [ ] 每个独立变更只提交相关文件，保留工作区内已有的 Android、`git-graph`、构建产物和其他无关变更。
