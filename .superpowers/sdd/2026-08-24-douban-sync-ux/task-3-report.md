# Task 3 报告：Compose UI、CTA 与四语言文案

## 完成

- 同步完成摘要拆分成功、复用/跳过、失败待重试、冲突修复、待续传和云端上传状态。
- 结果页保留活动详情折叠入口，并提供查看失败、重试失败、查看冲突 CTA（由调用方可选接入）。
- 续传弹窗关闭只保留 pending；新增“放弃未处理数据”二次确认，并在 Main 导航接入真实丢弃操作。
- 失败、待续传、冲突、取消和云端上传失败结果横幅不再 5 秒自动隐藏；纯成功结果仍按原策略短暂展示。
- 后台服务启动失败时保留弹窗并给出明确提示。
- 独立豆瓣模式与首次导入文案明确本地/云端范围及不会修改 Trakt；模式选择新增范围说明。
- 一致性弹窗补充登录、重试、查看冲突、后台启动失败反馈 CTA。
- 同步阶段新增结构化字段 `conflictsFound`、`conflictFixedCount`、`cloudUploadAttempted`、`cloudUploadSucceeded`，由同步上传阶段填充。
- 英/中/日/韩文案同步；中文摘要避免把 successCount 称为“新增”。

## 验证

- `:app:compileDebugKotlin --no-daemon --no-configuration-cache`：通过。
- 指定 Compose 单测：49 个中原有 3 个旧摘要断言失败；新增交互测试编译通过。失败原因是旧测试仍断言旧的 `Success · Skipped · Cached` 单行摘要，运行环境的资源文本含分隔符编码差异；功能 UI 已保留兼容行。
- `git diff --cached --check`：通过。

## 提交

- `6cd6698f fix(豆瓣同步体验): 完善结果与断点交互`

## Concern

- `onViewFailures`、`onRetry`、`onViewConflicts` 设计为可选回调，当前设置页/主列表仅接入后台与登录反馈；失败项/冲突明细页面的既有导航入口尚未在本 Task 内绑定，避免猜测目标路由。

## 修复轮

- 失败 CTA 现在直接在同步结果弹窗展开最多 5 条失败项及剩余数量；重试 CTA 调用 `WatchlistViewModel.retryLatestDoubanFailures()`，仅提交可恢复失败项。
- 一致性 CTA 改为“查看一致性结果”，打开现有一致性结果弹窗，不伪装成冲突明细页。
- 更新 3 个旧摘要断言，覆盖成功、复用/跳过、失败待重试文案。
- 编译：`:app:compileDebugKotlin --no-daemon --no-configuration-cache` 通过。
- 定向 Compose 测试受 Gradle 测试进程资源/筛选环境影响：一次执行出现 JVM native memory 失败；重新限制单 worker 后 Gradle 报参数选择问题，未形成可靠的 0 failures 结果。失败摘要断言本身已修正，需主线程环境恢复后复跑。
- 修复后单独运行 `DoubanSyncDialogTest`：BUILD SUCCESSFUL。
