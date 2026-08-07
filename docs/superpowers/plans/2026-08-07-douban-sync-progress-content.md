# 豆瓣同步进度信息保真实现计划

> **面向 AI 代理的工作者：** 必需子技能：使用 `subagent-driven-development` 逐任务实现此计划。步骤使用复选框（`- [ ]`）语法跟踪进度。

**目标：** 让豆瓣同步对话框、前台通知和 Watchlist 横幅在每个阶段展示与真实处理动作一致的条目和状态，并在云端同步结束后才发布完成态。

**架构：** 在 `DoubanSyncState.kt` 增加稳定的批次队列条目和有界队列跟踪器；`DoubanSyncProgressPublisher` 以同一锁发布阶段、当前活动条目和待处理条目。`DoubanSyncManager` 在详情/Trakt 并发流水线、状态变化、写入和云端上传边界更新队列与阶段。UI 和前台服务只消费阶段化状态，不再用旧的 `recentItems` 推断当前动作。

**技术栈：** Kotlin、Coroutines、StateFlow、Jetpack Compose、Compose UI Test、JUnit/Truth、MockK、Room。

---

## 文件职责

- 修改 `app/src/main/java/com/tracktosearch/data/repository/DoubanSyncState.kt`：新增 `UPLOADING` 阶段、上传子阶段、队列条目和有界队列跟踪器；维护阶段到资源的映射。
- 修改 `app/src/main/java/com/tracktosearch/data/repository/DoubanSyncManager.kt`：在列表、状态变化、详情/Trakt 并发流水线、写入、上传、取消和终态边界发布真实队列。
- 修改 `app/src/main/java/com/tracktosearch/ui/screen/douban/DoubanSyncDialog.kt`：按阶段展示“最近获取”“正在处理”“待处理”，隐藏不属于当前阶段的条目。
- 修改 `app/src/main/java/com/tracktosearch/service/DoubanSyncService.kt`：通知优先展示活动条目，运行中的非列表阶段不回退到旧的最近条目。
- 修改 `app/src/main/res/values/strings.xml`、`values-zh/strings.xml`、`values-ja/strings.xml`、`values-ko/strings.xml`：增加上传阶段、队列标题、待处理数量和四语言阶段文案。
- 修改 `app/src/test/java/com/tracktosearch/data/repository/DoubanSyncProgressPublisherTest.kt`：覆盖队列发布、活动项和取消清理。
- 创建 `app/src/test/java/com/tracktosearch/data/repository/DoubanSyncQueueTrackerTest.kt`：覆盖顺序、去重、有界快照和完成移动。
- 修改 `app/src/test/java/com/tracktosearch/data/repository/DoubanSyncManagerBatchPipelineTest.kt`：验证详情失败、无 IMDb、Trakt 未命中和豆瓣独立模式的队列生命周期。
- 修改 `app/src/test/java/com/tracktosearch/data/repository/DoubanSyncManagerLifecycleTest.kt`：验证上传阶段发生在完成态之前，且豆瓣独立模式仍上传云端数据。
- 修改 `app/src/test/java/com/tracktosearch/ui/screen/douban/DoubanSyncDialogTest.kt`：验证不同阶段只显示对应区域。
- 如现有通知测试可直接接入，则补充或创建 `app/src/test/java/com/tracktosearch/service/DoubanSyncServiceTest.kt`，否则在现有服务测试位置增加同等断言。

## 任务 1：批次队列和进度阶段模型

**文件：**

- 修改：`app/src/main/java/com/tracktosearch/data/repository/DoubanSyncState.kt`
- 修改：`app/src/main/java/com/tracktosearch/data/repository/DoubanSyncManager.kt:59-270`
- 测试：`app/src/test/java/com/tracktosearch/data/repository/DoubanSyncQueueTrackerTest.kt`
- 测试：`app/src/test/java/com/tracktosearch/data/repository/DoubanSyncProgressPublisherTest.kt`

- [ ] **步骤 1：编写队列跟踪器失败测试。** 覆盖以下具体契约：初始快照把所有条目放入 `pendingItems`；`start(id)` 将条目移入 `processingItems`；`complete(id)` 从两者移除；处理顺序按批次原顺序稳定；快照最多返回 3 个活动项和 5 个待处理项，但 `pendingItemCount` 保留完整数量；重复 `start/complete` 不增加数量。

```kotlin
@Test
fun `队列按真实 doubanId 移动并限制展示数量`() {
    val tracker = DoubanSyncQueueTracker(
        (1..8).map { DoubanSyncQueueItem("id-$it", "条目-$it") }
    )

    tracker.start("id-2")
    tracker.start("id-1")
    tracker.complete("id-2")

    val snapshot = tracker.snapshot()
    assertThat(snapshot.processingItems.map { it.doubanId }).containsExactly("id-1").inOrder()
    assertThat(snapshot.pendingItems.map { it.doubanId })
        .containsExactly("id-3", "id-4", "id-5", "id-6", "id-7").inOrder()
    assertThat(snapshot.pendingItemCount).isEqualTo(6)
}
```

- [ ] **步骤 2：运行测试确认失败。**

运行：

```bash
./gradlew.bat :app:testDebugUnitTest --tests '*DoubanSyncQueueTrackerTest' --tests '*DoubanSyncProgressPublisherTest' --no-daemon --console=plain
```

预期：队列类型或新断言尚未存在而失败；若旧测试先通过，保留其输出并只记录新增断言失败。

- [ ] **步骤 3：实现最小模型和发布 API。**

在 `DoubanSyncState.kt` 增加：

```kotlin
data class DoubanSyncQueueItem(val doubanId: String, val title: String)

data class DoubanSyncQueueSnapshot(
    val processingItems: List<DoubanSyncQueueItem> = emptyList(),
    val pendingItems: List<DoubanSyncQueueItem> = emptyList(),
    val pendingItemCount: Int = 0
)
```

实现 `DoubanSyncQueueTracker`：内部使用 `LinkedHashMap`/`LinkedHashSet` 按输入顺序保存条目，`start` 只从 pending 移入 processing，`complete` 从两组移除，`snapshot` 做 3/5 条有界截取。

在 `DoubanSyncProgress` 加入 `processingItems`、`pendingItems`、`pendingItemCount`，并在 `DoubanSyncProgressPublisher` 加入：

```kotlin
fun publishQueue(snapshot: DoubanSyncQueueSnapshot, currentTitle: String? = null)
fun clearQueue()
fun publishStage(stage: DoubanSyncStage, subStage: DoubanSyncSubStage, phase: String)
```

发布函数必须复用现有 `lock`，取消或 `isCancelling` 时拒绝迟到的运行态更新；`publishCancelling` 清空队列；现有批处理回调在 `currentTitle == null` 时从活动队列首项取摘要。

新增 `DoubanSyncStage.UPLOADING` 和上传子阶段：`PREPARING_UPLOAD`、`CHECKING_CONSISTENCY`、`UPLOADING_PERSONAL_DATA`、`UPLOADING_FAILURES`、`UPLOADING_DETAILS`、`FILLING_MEDIA_TYPE`，并更新 `stageFromSubStage`、`compactLabelRes`、`labelRes`、`labelRes/secondaryLabelRes`。

- [ ] **步骤 4：运行测试确认通过。**

运行同一步骤 2 的命令，预期 `BUILD SUCCESSFUL` 且相关测试通过。

- [ ] **步骤 5：提交模型变更。**

```bash
git add app/src/main/java/com/tracktosearch/data/repository/DoubanSyncState.kt app/src/main/java/com/tracktosearch/data/repository/DoubanSyncManager.kt app/src/test/java/com/tracktosearch/data/repository/DoubanSyncQueueTrackerTest.kt app/src/test/java/com/tracktosearch/data/repository/DoubanSyncProgressPublisherTest.kt
git diff --cached --check
git commit -m "feat(同步): 增加批次队列进度模型"
```

## 任务 2：同步管理器接入真实处理队列

**文件：**

- 修改：`app/src/main/java/com/tracktosearch/data/repository/DoubanSyncManager.kt:361-1855,2070-2720`
- 测试：`app/src/test/java/com/tracktosearch/data/repository/DoubanSyncManagerBatchPipelineTest.kt`
- 测试：`app/src/test/java/com/tracktosearch/data/repository/DoubanSyncManagerLifecycleTest.kt`

- [ ] **步骤 1：增加批处理队列生命周期测试。** 通过现有反射入口/真实 manager 回调断言：

  - 详情任务开始后条目进入 `processingItems`，未启动条目留在 `pendingItems`。
  - 详情失败、无 IMDb、Trakt 查询完成后对应 ID 不再留在活动/待处理队列。
  - 新的 WISH/COLLECT 批次不残留上一批队列；列表抓取阶段只保留当前列表的 `recentItems`。
  - `写入 Trakt`、`写入本地` 和 `UPLOADING` 阶段的队列为空。

- [ ] **步骤 2：运行定向测试确认失败。**

```bash
./gradlew.bat :app:testDebugUnitTest --tests '*DoubanSyncManagerBatchPipelineTest' --tests '*DoubanSyncManagerLifecycleTest' --no-daemon --console=plain
```

预期：新增队列和上传时序断言失败，既有业务断言保持可诊断状态。

- [ ] **步骤 3：接入详情/Trakt 并发流水线。** 在 `syncBatchToTrakt` 与 `syncBatchToDoubanLocal` 计算 `pending` 后创建 `DoubanSyncQueueTracker`，并在详情 worker、Trakt worker 中按真实 `doubanId` 调用 `start/complete`：

```kotlin
val queue = DoubanSyncQueueTracker(
    pending.map { DoubanSyncQueueItem(it.doubanId, it.title) }
)
progressPublisher.publishQueue(queue.snapshot())

queue.start(item.doubanId)
progressPublisher.publishQueue(queue.snapshot(), item.title)
onProgress(completedCount.get() + skippedCount, "详情页", 0, item.title, null)

// 详情失败、无 IMDb 或 Trakt 查询结束后
queue.complete(item.doubanId)
progressPublisher.publishQueue(queue.snapshot())
onProgress(completedCount.get() + skippedCount, subPhase, 0, null, failure)
```

保持现有 `completedCount`、ETA 和失败持久化口径；队列只描述条目生命周期，不改变成功/失败计数。批次为空或取消时调用 `clearQueue()`。进入批量写入前清空队列，并把 `currentTitle` 置空。

- [ ] **步骤 4：修正列表和状态变化阶段。** WISH/COLLECT 列表阶段开始时清空 `recentPreviewBuffer`，进入解析阶段时清空 UI 可见的 `recentItems`。`processStatusChanges` 只对实际存在于 `syncedItemsMap` 的条目计数，使用有界队列逐项显示状态变化，完成或取消时清空队列；`current/total` 使用状态变化条目数，不沿用列表抓取数量。

- [ ] **步骤 5：纳入云端同步阶段并延后终态。** 在 `uploadToCloudAfterSync` 中按顺序调用 `publishStage`：准备上传、状态一致性检查、个人数据、失败项、详情池、媒体类型补全。把 `runSyncLegacy`、`runSyncIncremental`、`runResume`、`runRetry` 的非取消完成构造移动到 `uploadToCloudAfterSync` 之后；取消仍立即发布取消态并执行现有部分上传。上传辅助调用继续使用现有 `runCatching`，一致性检查异常交给外层失败态处理。

- [ ] **步骤 6：运行定向测试确认通过。** 重跑步骤 2 的命令，预期队列生命周期和“完成态晚于 uploadAll”测试通过。

- [ ] **步骤 7：提交管理器接线。**

```bash
git add app/src/main/java/com/tracktosearch/data/repository/DoubanSyncManager.kt app/src/test/java/com/tracktosearch/data/repository/DoubanSyncManagerBatchPipelineTest.kt app/src/test/java/com/tracktosearch/data/repository/DoubanSyncManagerLifecycleTest.kt
git diff --cached --check
git commit -m "fix(同步): 校正全流程条目进度"
```

## 任务 3：按阶段渲染对话框和前台通知

**文件：**

- 修改：`app/src/main/java/com/tracktosearch/ui/screen/douban/DoubanSyncDialog.kt`
- 修改：`app/src/main/java/com/tracktosearch/service/DoubanSyncService.kt`
- 修改：`app/src/main/java/com/tracktosearch/ui/screen/watchlist/DoubanSyncBanner.kt`（仅在需要适配新阶段资源时）
- 修改：`app/src/main/res/values/strings.xml`
- 修改：`app/src/main/res/values-zh/strings.xml`
- 修改：`app/src/main/res/values-ja/strings.xml`
- 修改：`app/src/main/res/values-ko/strings.xml`
- 测试：`app/src/test/java/com/tracktosearch/ui/screen/douban/DoubanSyncDialogTest.kt`
- 测试：`app/src/test/java/com/tracktosearch/service/DoubanSyncServiceTest.kt`

- [ ] **步骤 1：先扩展 Compose 测试。** 增加三组状态：

```kotlin
@Test
fun `解析阶段显示处理和待处理而不显示最近获取`() { /* recentItems + processingItems + pendingItems */ }

@Test
fun `列表阶段显示最近获取而不显示处理队列`() { /* recentItems + queue */ }

@Test
fun `写入和上传阶段隐藏所有旧条目`() { /* recentItems + queue + UPLOADING */ }
```

断言必须检查标题和条目标题，确保同一条旧列表内容不会跨阶段显示。

- [ ] **步骤 2：运行对话框测试确认失败。**

```bash
./gradlew.bat :app:testDebugUnitTest --tests '*DoubanSyncDialogTest' --no-daemon --console=plain
```

预期：新增阶段化断言失败。

- [ ] **步骤 3：实现对话框阶段分支。**

只在 `stage == FETCHING_LIST` 且有 `recentItems` 时渲染“最近获取”；只在 `stage == PARSING_DATA` 且子阶段属于详情/Trakt/重试时渲染“正在处理”和“待处理”。活动项最多渲染 3 个，待处理项最多渲染 5 个，`pendingItemCount > pendingItems.size` 时增加“还有 N 项待处理”摘要。`UPDATING_LIST`/`UPLOADING` 不渲染旧条目卡片；当前阶段文案和 ETA/延时信息保持可见。

- [ ] **步骤 4：更新通知回退规则。** `DoubanSyncService` 运行态只使用 `currentTitle` 或 `processingItems.firstOrNull()`；只有非运行完成态才允许使用 `recentItems` 作为历史摘要。上传阶段使用阶段/子阶段文案，不带解析批次的 461 条数量。

- [ ] **步骤 5：补齐四语言资源并运行测试。** 新增资源至少包括上传阶段名称、上传子阶段、处理队列标题、待处理队列标题、待处理数量和“当前批次无条目”的阶段文案，四套资源保持同名。重跑步骤 2 和服务通知测试，预期全部通过。

- [ ] **步骤 6：提交 UI 和资源变更。**

```bash
git add app/src/main/java/com/tracktosearch/ui/screen/douban/DoubanSyncDialog.kt app/src/main/java/com/tracktosearch/service/DoubanSyncService.kt app/src/main/java/com/tracktosearch/ui/screen/watchlist/DoubanSyncBanner.kt app/src/main/res/values/strings.xml app/src/main/res/values-zh/strings.xml app/src/main/res/values-ja/strings.xml app/src/main/res/values-ko/strings.xml app/src/test/java/com/tracktosearch/ui/screen/douban/DoubanSyncDialogTest.kt app/src/test/java/com/tracktosearch/service/DoubanSyncServiceTest.kt
git diff --cached --check
git commit -m "feat(同步): 按阶段展示实时处理条目"
```

## 任务 4：整体验证和收尾

**文件：** 不新增业务文件；只检查前述变更和生成报告。

- [ ] **步骤 1：运行同步相关 JVM 测试。**

```bash
./gradlew.bat :app:testDebugUnitTest --tests '*DoubanSync*' --tests '*DoubanPendingItemsDialogTest' --no-daemon --console=plain
```

预期：`BUILD SUCCESSFUL`，检查 `app/build/test-results/testDebugUnitTest` XML，确认无隐藏失败。

- [ ] **步骤 2：运行完整 JVM 测试。**

```bash
./gradlew.bat :app:testDebugUnitTest --no-daemon --console=plain
```

若外层超时，先检查 Gradle/Java 进程、测试 XML 和报告，不把工具超时直接当成测试失败。

- [ ] **步骤 3：构建 debug APK。**

```bash
./gradlew.bat :app:assembleDebug --no-daemon --console=plain
```

预期：输出 `app/build/outputs/apk/debug/app-debug.apk` 且明确出现 `BUILD SUCCESSFUL`。

- [ ] **步骤 4：设备验证同步对话框。** 先执行 `adb devices`、设备 API/版本核对和 `installDebug` 或直接安装 APK；通过可控测试数据验证列表抓取、解析/匹配、写入、上传和完成态，抓取 UI 树、截图和 `adb logcat -b crash -d`。确认解析阶段不出现“最近获取”，上传阶段不出现 461 条解析进度，完成态在 `uploadAll` 后出现。

- [ ] **步骤 5：检查 Git 边界。**

```bash
git diff --check
git status --short
git log -4 --oneline
```

确认未暂存的 `AGENTS.md`、JVM 日志、截图、构建产物和临时目录未被本任务提交。
