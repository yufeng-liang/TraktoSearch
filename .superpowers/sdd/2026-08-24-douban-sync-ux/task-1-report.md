# Task 1 实现报告：数据安全、恢复状态和结果契约

## 范围

在隔离工作树 `F:\work-tree-Codex\douban-sync-ux-0824\trae-project` 完成 Task 1。按用户确认的规则实现：续传关闭保留 pending、完整同步启动成功后才清理 pending、rollback 与 pending 恢复入口串行、最近同步摘要持久化、可恢复一致性检查批次和结构化冲突明细。

Task brief 未列出但为完成 Room 迁移所必需的 `DatabaseModule.kt` 已获主线程批准纳入；新增 `MIGRATION_14_15`，不删除或重写旧表数据。

## TDD 证据

1. RED：先新增 `DoubanSyncManagerCancelTest.放弃未处理数据才清空pending`，现状运行 `:app:testDebugUnitTest --tests com.tracktosearch.data.repository.DoubanSyncManagerCancelTest` 在编译期失败：`Unresolved reference 'discardPendingItems'`。
2. GREEN：新增明确 `discardPendingItems()`，并把 UI 关闭动作与明确丢弃动作分开；随后编译和针对性测试通过。
3. 迁移/恢复行为新增 `DoubanWatchlistDataLayerTest.migration14To15_createsRecoveryTablesIdempotentlyAndPreservesPendingRows`。
4. 一致性续传/失效行为新增 checker 测试：同账号续传待处理任务、账号变化使旧任务 INVALID 并提示重新检查。

## 实现

- `AppNavigation.kt`
  - 续传弹窗关闭仅隐藏当前弹窗，保留 pending。
  - rollback 弹窗关闭保留 rollback；rollback 有数据时阻断 pending 弹窗。
  - 完整同步不再由 UI 预先清空 pending；同步真正进入 full rewrite 后才清理。
- `DoubanSyncManager.kt`
  - 增加 `discardPendingItems()`，仅供明确确认的丢弃操作使用。
  - full rewrite 在登录/启动链路进入后清理 pending，启动失败不会清理。
  - 保存最近一次同步摘要（模式、成功/跳过/失败、待处理数、完成时间）。
  - 一致性检查失败不再抛异常阻断个人数据上传；保留 retry 任务并把失败计数放入进度。
- `DoubanSyncMetaStorage.kt`
  - DataStore 原子覆盖一条 `DoubanSyncSummary`，避免摘要无限增长。
- `DoubanEntities.kt` / `AppDatabase.kt` / `DatabaseModule.kt`
  - Room v15 新增 `douban_consistency_check_runs`、`douban_consistency_check_tasks`、`douban_consistency_conflicts`。
  - 迁移使用 `CREATE TABLE/INDEX IF NOT EXISTS`，保留旧 pending、同步和 rollback 数据，重复执行幂等。
- `DoubanTraktStatusConsistencyChecker.kt`
  - 持久化批次账号键、数据版本、阶段、进度、待处理任务和冲突记录。
  - 重启后同账号/同数据版本优先消费 PENDING/RETRY；账号或数据版本变化安全标记 INVALID 并提示重新检查。
  - 冲突记录区分 `STATUS_CONFLICT`、`TRAKT_DUAL_STATUS`，结果状态写入 `FIXED` / `RETRY`，错误保留消息。

## 验证

- `.\gradlew.bat :app:compileDebugKotlin :app:compileDebugUnitTestKotlin --no-daemon`：通过。
- `.\gradlew.bat :app:testDebugUnitTest --tests com.tracktosearch.data.local.db.DoubanWatchlistDataLayerTest --tests com.tracktosearch.data.repository.DoubanTraktStatusConsistencyCheckerTest --tests com.tracktosearch.data.repository.ConsistencyCheckerCancelTest --tests com.tracktosearch.data.repository.ConsistencyCheckerCrawlTest --tests com.tracktosearch.data.repository.DoubanSyncManagerCancelTest --no-daemon`：通过。
- `git diff --check`：通过；仅有 Git 的 LF/CRLF 提示，无空白错误。

## Concerns

- 本 Task 未实现通知文本/通知渠道或 Compose 结果页；这些留给后续 UI/UX Task。
- 一致性检查的手动 crawl 入口仍沿用原有内存阶段；持久化恢复契约已接入自动检查和任务表，手动 crawl UI 的展示接入需在后续 Task 完成。
- Room schema export 在项目中关闭，迁移由显式幂等 SQL 和测试覆盖。
