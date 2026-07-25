# 后台管理网页优化实施计划

> **面向 AI 代理的执行说明：** 这是当前任务的实施计划。每个行为变更先补充最小回归测试，再修改实现，最后运行相关测试和 `git diff --check`。

**目标：** 在不改变现有后台技术栈的前提下，修复确定性缺陷，改善请求生命周期、错误降级、数据规模适配、无障碍和移动端体验。

**暂缓决策：** 朋友禁用后是否允许重新启用；后台视觉方向是保留玻璃拟态还是改为系统化/工业化管理风格。

## 第一批：确定性缺陷

**文件：**

- 修改：`app-config/public/admin/app.js`
- 修改：`app-config/public/admin/styles.css`
- 修改：`app-config/tests/admin-ui.test.mjs`

- [x] 为 `formatTime()` 增加 Unix 秒且超过 30 天的回归断言。
- [x] 使用已转换的毫秒值格式化日期。
- [x] 修复 Toast 动画单位。
- [x] 消除 `navigate()` 与 `hashchange` 的双重渲染。
- [x] 为异步加载增加页面实例标识，避免旧页面回写。
- [x] 同步当前编辑表单和仪表盘请求行为的测试契约。

## 第二批：请求与错误状态

**文件：**

- 修改：`app-config/public/admin/app.js`
- 修改：`app-config/tests/admin-ui.test.mjs`

- [x] API 请求支持超时和 AbortSignal。
- [x] 页面离开时取消当前页面请求。
- [x] 仪表盘统计、失败日志和健康信息允许局部失败。
- [x] 统一加载、空数据、错误和重试状态。
- [x] 防止重复提交和旧响应覆盖当前数据。

## 第三批：数据规模与审计可靠性

**文件：**

- 修改：`app-config/public/admin/app.js`
- 修改：`auth-worker/src/admin/admin.ts`
- 修改：`auth-worker/src/index.ts`
- 新增或修改：`auth-worker/migrations/*`
- 新增：对应 Worker/API 测试

- [x] 朋友列表支持服务端搜索、状态过滤和分页。
- [x] 增加朋友详情聚合接口或等价的单次查询。
- [ ] 评估审计日志游标分页和组合索引。
- [x] 保持现有接口兼容，先补充默认参数和边界测试。

## 第四批：产品决策后的状态能力

- [ ] 若确认可恢复，新增启用朋友 API。
- [ ] 新增启用审计事件和前端确认/刷新流程。
- [ ] 明确禁用、启用、设备撤销和邀请码状态的可逆性文案。

## 第五批：UI/UX、无障碍和性能

- [x] 表单标签、错误提示、Toast 和导航补齐无障碍语义。
- [x] 完善移动端侧栏、顶部工具栏、焦点和 Escape 行为。
- [x] 增加 Clipboard fallback。
- [x] 为 reduced motion 关闭平滑滚动和动态背景。
- [ ] 在视觉方向确认后，降低滤镜和噪声成本并统一管理页层级。
- [ ] 增加 CSP 和资源缓存策略。

## 验证命令

```bash
node --test app-config/tests/admin-ui.test.mjs app-config/tests/admin-api.test.mjs
cd auth-worker && npm run typecheck
git diff --check
```
