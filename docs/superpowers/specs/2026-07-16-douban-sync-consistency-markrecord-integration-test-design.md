# 豆瓣同步/一致性检查/标记记录 集成测试设计

**日期**: 2026-07-16
**状态**: 已批准
**关联**: 补全单元测试 + Compose UI 测试 + 端到端插桩测试

---

## 一、目标

针对豆瓣导入同步、检查一致性、标记记录三大功能模块的复杂弹窗、进度更新、取消处理、UI 跳转、息屏保持等容易出 bug 的场景，补全三层测试：

1. **单元测试**：Manager / ViewModel 状态机流转、取消链路、WakeLock acquire/release、异常处理
2. **Robolectric 组件测试**：弹窗渲染、状态分支、进度展示、按钮禁用逻辑
3. **插桩 UI 测试**：真实导航跳转、端到端交互、跨页面状态同步

---

## 二、模块优先级

按复杂度和 bug 风险排序：

### 模块 1: 豆瓣同步模块（最复杂）

**核心文件**:
- `data/repository/DoubanSyncManager.kt`（1738 行）
- `ui/screen/douban/DoubanSyncDialog.kt`（558 行）
- `ui/screen/watchlist/WatchlistViewModel.kt`（784 行，同步逻辑部分）

**已测**: DoubanSyncManager 的 restoreRollback、状态查询、cancel/resetProgress、防重入（18 个测试）
**未测关键路径**:
- syncBatchToTrakt Channel 流水线（阶段1并发3→阶段2并发5）
- 取消链路：cancel() → isCancelling → cancelled → syncJob.join() → uploadAll(CANCELLED)
- WakeLock acquire/release
- DoubanSyncDialog 5 种 confirmButton 状态分支
- WatchlistViewModel 同步完成后 5 秒清空横幅 + resetProgress

### 模块 2: 一致性检查模块（息屏场景关键）

**核心文件**:
- `data/repository/DoubanTraktStatusConsistencyChecker.kt`（626 行）
- `ui/screen/settings/ConsistencyCheckDialog.kt`（223 行）
- `service/ConsistencyCheckService.kt`（148 行，仅通知栏，不测）

**已测**: checkAndUnify 8 种状态组合、空表/一致状态/recordCheck（12 个测试）
**未测关键路径**:
- checkAndUnifyWithCrawl 完整 5 阶段链路
- cancel 链路：cancel() → isCancelling + isCancelled → 循环退出 → releaseWakeLock
- WakeLock acquire/release（PARTIAL_WAKE_LOCK，30 分钟超时）
- ConsistencyCheckDialog 状态分支（运行中/正在取消/完成）

### 模块 3: 标记记录模块（UI 跳转和显示正确性）

**核心文件**:
- `ui/screen/markrecord/MarkRecordViewModel.kt`
- `ui/screen/markrecord/MarkRecordScreen.kt`
- `ui/screen/markrecord/MarkRecordComponents.kt`
- `data/local/db/MarkActionRecordDao.kt`

**已测**: MarkRecordViewModel 22 个测试、MarkRecordComponents 17 个测试、DAO 9 个测试
**未测关键路径**:
- WATCHED Tab Trakt API 分页失败/边界
- ALL Tab 合并去重（同 traktId 多条）
- refresh 后状态徽标一致性
- MarkRecordItemRow 整体渲染（标题回退、海报、变灰效果）
- FilterSheetContent 交互
- Tab 切换、滚动加载、点击跳转详情页的端到端

---

## 三、测试基础设施补强

现有 UI 测试基础设施成熟于"静态状态渲染 + 一次性回调触发"，不支持复杂异步状态机。需补强：

### 3.1 MutableStateFlow 手动推送状态模式

在 mock ViewModel 中暴露 `MutableStateFlow<ProgressState>`，测试中通过 `value = newState` 模拟进度更新/取消/完成。

```kotlin
// 测试辅助：可控制的进度 StateFlow
class FakeSyncProgressHolder {
    val progress = MutableStateFlow(DoubanSyncProgress())
    fun emitLoading(current: Int, total: Int) { progress.value = progress.value.copy(isRunning = true, current = current, total = total) }
    fun emitCancelling() { progress.value = progress.value.copy(isCancelling = true, phase = "正在取消...") }
    fun emitComplete(success: Int, failed: Int) { progress.value = progress.value.copy(isRunning = false, isComplete = true, successCount = success, failedCount = failed) }
}
```

### 3.2 composeRule.waitUntil 等待异步状态

```kotlin
composeRule.waitUntil(5000) {
    composeRule.onAllNodesWithText("完成").fetchSemanticsNodes().isNotEmpty()
}
```

### 3.3 mainClock 控制（仅在动画弹窗场景）

```kotlin
composeRule.mainClock.autoAdvance = false
// 触发状态变化
composeRule.mainClock.advanceTimeBy(1000)
```

---

## 四、测试范围边界

### 4.1 测的内容

- Manager 状态机流转（运行中→正在取消→完成/取消）
- WakeLock acquire/release 调用验证
- 弹窗 5 种 confirmButton 状态分支
- 进度展示（current/total、百分比、延时倒计时）
- 取消按钮禁用逻辑（isCancelling 时禁用）
- 转后台/取消交互
- 标记记录 UI 跳转（Tab 切换、滚动加载、点击跳转详情页）
- 跨页面状态同步（同步完成→横幅清空→弹窗展示）

### 4.2 不测的内容（YAGNI）

- 真实网络请求（全部用 mock）
- 真实息屏触发（用单元测试验证 WakeLock acquire/release 调用即可）
- Service 通知栏展示（逻辑薄，风险低，ConsistencyCheckService/DoubanSyncService/DoubanBatchRemovalService）
- DoubanItemDetailViewModel（2756 行，独立模块，本次不纳入）
- 真实豆瓣爬取（已由 DoubanRepositoryTest/DoubanSpiderTest 覆盖）
- 真实 Trakt API 调用（已由 TraktRepositoryTest 覆盖）

---

## 五、测试清单

### 模块 1: 豆瓣同步（~75 个测试）

#### 1.1 单元测试：DoubanSyncManager（~40 个）

**取消链路（10 个）**:
- cancel 后 isCancelling() 返回 true
- cancel 后 progress.phase 包含"正在取消"
- cancel 后 progress.isCancelling 为 true
- cancel 后 syncJob 最终完成（isComplete=true）
- cancel 后 uploadAll(CANCELLED) 被调用
- cancel 后 releaseWakeLock 被调用
- 正在取消时按钮禁用（progress.isCancelling=true）
- 取消后 5 秒清空横幅 + resetProgress
- 取消后再 startSync 可正常启动
- 取消时 cookieExpired 状态保留

**syncBatchToTrakt 流水线（15 个）**:
- 空列表短路返回
- 阶段1详情页爬取成功→阶段2 Trakt 查询
- 阶段1详情页爬取失败→收集到 failedItems
- 阶段1反爬延迟触发（delayEvent 上报）
- 阶段2 searchByImdb 成功→traktId
- 阶段2 searchByImdb 返回 null→收集到 failedItems
- 阶段2 traktId 缓存命中跳过查询
- 批量写入 Trakt watchlist 成功
- 批量写入 Trakt history 成功
- 批量写入失败→收集到 failedItems
- successDoubanIds 在写入失败时 clear
- Channel 并发控制（阶段1并发3，阶段2并发5）
- onProgress 回调被正确调用
- existingFailures 去重（同 doubanId 不重复收集）
- skipFailuresIds 跳过指定失败项

**WakeLock（5 个）**:
- startSync 时 acquireWakeLock（60 分钟超时）
- 同步完成后 releaseWakeLock
- 同步异常时 releaseWakeLock（try/finally）
- 取消时 releaseWakeLock
- WakeLock 已持有时不重复 acquire

**checkTraktAvailable（5 个）**:
- token 有效返回 true
- token 为空返回 false
- token 失效返回 false
- token 失效时 cookieExpired=true
- token 失效时直接完成不进入同步流程

**persistFailures（5 个）**:
- 正常同步按 status 覆盖
- 重试模式用 REPLACE 语义不删未重试项
- 空失败列表不调用 DAO
- 同 doubanId 失败项累加 attemptCount
- 失败项 reason 字段正确填充

#### 1.2 Robolectric: DoubanSyncDialog（~25 个）

**confirmButton 状态分支（10 个）**:
- isRunning 时显示"转后台"+"取消"
- isComplete 且 phase 含"未登录 Trakt"时显示"登录 Trakt"+"完成"
- isComplete 且 cookieExpired 时显示"重新登录豆瓣"+"完成"
- isComplete 时显示"完成"
- isCancelling 时"转后台"和"取消"按钮禁用
- isCancelling 时按钮文案改为"正在取消..."
- 初始状态显示 CircularProgressIndicator + "取消"
- onDismissRequest 运行中不允许关闭
- 完成后允许点击外部关闭
- 点击"取消"触发 onCancel 回调

**进度展示（8 个）**:
- total>0 时显示精确进度条
- total=0 时显示 indeterminate 进度条
- 显示 current/total 文字
- 显示 phase 文案
- 显示当前条目标题 currentTitle
- 延时倒计时显示（DelayInfo）
- 最近 5 条失败滚动展示
- 完成统计显示（成功·跳过·缓存命中·失败）

**失败项列表（7 个）**:
- 失败项双层吸顶分组（一级可恢复/不可恢复）
- 二级按 FailureReason 分组
- 失败项显示标题+原因
- 空失败列表不显示失败区域
- 导出失败项按钮点击触发 onExport
- 导出后显示 ShareSheet
- 失败项数量为 0 时隐藏导出按钮

#### 1.3 插桩 UI: WatchlistScreen 同步交互（~10 个）

- 同步横幅在同步进行中显示
- 同步横幅显示进度文案
- 点击横幅打开 DoubanSyncDialog
- 同步完成后 5 秒横幅自动清空
- 同步完成后弹出 DoubanSyncDialog
- 点击"转后台"关闭弹窗但同步继续
- 点击"取消"触发取消并显示"正在取消"
- 取消完成后弹窗显示统计
- 同步未进行时不显示横幅
- cookieExpired 时弹窗显示重新登录按钮

---

### 模块 2: 一致性检查（~48 个测试）

#### 2.1 单元测试: ConsistencyChecker（~25 个）

**checkAndUnifyWithCrawl 完整链路（10 个）**:
- 未登录豆瓣时 cookieExpired=true 直接完成
- 已在运行时返回 false
- 阶段1爬豆瓣列表（想看+已看）
- 阶段2对比状态（8种组合）
- 阶段3批量更新 Trakt（watchlist↔history 切换）
- 阶段4逐条更新豆瓣（removeMark + markWish/markCollect）
- 阶段5完成统计 + recordCheck
- 爬取过程中反爬延迟触发
- 爬取失败时 errors 字段累加
- 完成后 checkProgress.isComplete=true

**cancel 链路（8 个）**:
- cancel 后 isCancelling() 返回 true
- cancel 后 isCancelled=true（区分取消与正常完成）
- cancel 后循环在下一个 checkpoint 退出
- cancel 后 releaseWakeLock 被调用
- cancel 后 checkProgress.isComplete=true 但 isCancelled=true
- cancel 后 WatchlistViewModel 不 emit 完成事件
- cancel 后 progress 显示"正在取消..."
- cancel 后再 checkAndUnifyWithCrawl 可正常启动

**WakeLock（4 个）**:
- checkAndUnifyWithCrawl 时 acquireWakeLock（PARTIAL_WAKE_LOCK，30 分钟超时）
- 完成后 releaseWakeLock
- 异常时 releaseWakeLock（try/finally）
- cancel 时 releaseWakeLock

**状态组合（3 个）**:
- 已看过优先于想看（同时存在时按已看过处理）
- 豆瓣有 Trakt 无→更新 Trakt
- Trakt 有豆瓣无→更新豆瓣

#### 2.2 Robolectric: ConsistencyCheckDialog（~15 个）

**状态分支（8 个）**:
- isRunning 时显示"转后台"+"取消"
- isComplete 时显示"完成"
- isCancelling 时按钮禁用
- 初始状态显示 CircularProgressIndicator + "取消"
- cookieExpired 时显示重新登录提示
- onDismissRequest 运行中不允许关闭
- 点击"取消"触发 onCancel
- 点击"转后台"触发 onDismiss

**进度展示（7 个）**:
- 显示 phase 文案
- 显示 current/total 进度条
- 显示当前条目标题
- 延时倒计时显示
- 完成统计显示（检查数/冲突数/Trakt更新数/豆瓣更新数/错误数）
- 取消时显示"正在取消..."
- 错误数>0 时显示错误提示

#### 2.3 插桩 UI: SettingsScreen 检查交互（~8 个）

- 设置页显示"检查一致性"按钮
- 点击按钮显示二次确认弹窗
- 确认后启动检查并显示弹窗
- 检查进行中显示进度
- 点击"取消"触发取消
- 取消后弹窗关闭
- 检查完成后显示统计
- 检查过程中息屏后 WakeLock 保持（验证 acquire 调用）

---

### 模块 3: 标记记录（~52 个测试）

#### 3.1 单元测试: MarkRecordViewModel 补充（~20 个）

**WATCHED Tab Trakt 分页（5 个）**:
- WATCHED Tab 第一页加载成功
- WATCHED Tab 第二页加载成功
- WATCHED Tab 最后一页不足 50 条 hasMore=false
- WATCHED Tab Trakt API 失败设置 error
- WATCHED Tab retry 后重新加载

**ALL Tab 合并（5 个）**:
- ALL Tab 合并 DAO + Trakt 历史按时间倒序
- ALL Tab Trakt 失败不影响 DAO 展示
- ALL Tab hasMore 恒为 false
- ALL Tab 同 traktId 不去重（显示两条）
- ALL Tab 只在 page==1 请求 Trakt 历史

**状态徽标一致性（5 个）**:
- loadFirstPage 后 currentStatusMap 正确计算
- loadNextPage 后新 item currentStatus 为 null
- refresh 后不清空 WatchlistWatchedIds 缓存
- refresh 后 currentPage=1
- switchTab 同 Tab 重复点击早退

**边界情况（5 个）**:
- updateSearchQuery 空字符串触发重载
- updateSearchQuery 特殊字符（%、_）转义
- updateFilter 空媒体类型集合
- loadNextPage 在 ALL Tab 安全返回
- switchTab 在加载中切换（旧请求不覆盖新状态）

#### 3.2 Robolectric: MarkRecordComponents（~20 个）

**MarkRecordItemRow 渲染（8 个）**:
- 显示 displayTitle
- displayTitle 空时回退到 title
- 两者都空时显示空状态文案
- 显示年份 (2024)
- 显示 episodeInfo S01E03
- posterUrl 为 null 时不崩溃
- isRecordChanged 时 alpha=0.6 变灰
- currentStatus 为 null 时不显示徽标

**ActionTypeChip（5 个）**:
- ADD_WATCHLIST 蓝色
- REMOVE_WATCHLIST 红色
- UNMARK_WATCHED 橙色
- WATCHED 绿色
- 未知 actionType 灰色 + WATCHED 文案

**CurrentStatusBadge（4 个）**:
- IN_WATCHLIST 绿色文案
- WATCHED 绿色文案
- NONE 灰色文案
- isRecordChanged 时显示"已变更"

**FilterSheetContent（3 个）**:
- 切换媒体类型 chip
- 切换日期预设 chip
- CUSTOM 选中时显示说明文字

#### 3.3 插桩 UI: MarkRecordScreen（~12 个）

- 默认显示 ALL Tab
- Tab 切换更新内容
- 搜索输入触发重载
- 滚动到底部触发 loadNextPage
- 点击卡片触发 onMovieClick
- 点击卡片触发 onShowClick
- 空状态显示
- 错误状态显示+重试按钮
- 加载状态显示 CircularProgressIndicator
- 筛选按钮打开 ModalBottomSheet
- 筛选确认后列表更新
- 返回按钮触发 onBack

---

## 六、预期测试数量汇总

| 模块 | 单元测试 | Robolectric | 插桩 UI | 小计 |
|------|---------|-------------|---------|------|
| 豆瓣同步 | 40 | 25 | 10 | 75 |
| 一致性检查 | 25 | 15 | 8 | 48 |
| 标记记录 | 20 | 20 | 12 | 52 |
| **合计** | **85** | **60** | **30** | **175** |

---

## 七、执行策略

### 7.1 按模块分批，每模块三层并行

1. **第一批：豆瓣同步模块**（最复杂，bug 风险最高）
2. **第二批：一致性检查模块**（息屏场景关键）
3. **第三批：标记记录模块**（UI 跳转和显示正确性）

### 7.2 每模块内部执行顺序

1. 先补单元测试（最快验证状态机逻辑）
2. 再补 Robolectric 组件测试（验证弹窗渲染和状态分支）
3. 最后补插桩 UI 测试（端到端交互验证）

### 7.3 验证检查点

- 每批完成后运行 `.\gradlew :app:testDebugUnitTest` 验证单元测试 + Robolectric
- 每批完成后运行 `.\gradlew :app:connectedDebugAndroidTest` 验证插桩测试
- 全部完成后运行全量测试验证无回归

---

## 八、风险与缓解

### 8.1 测试执行时间

- Robolectric 测试加入后单元测试时间可能从 2 分钟增至 4-5 分钟
- 插桩测试加入后从 5 分钟增至 10-15 分钟
- **缓解**：按模块分批验证，不每次跑全量

### 8.2 mock 复杂度

- DoubanSyncManager 有 10+ 依赖，mock 搭建复杂
- **缓解**：创建测试辅助类 `FakeSyncProgressHolder`、`MockDoubanSyncManagerFactory`

### 8.3 Channel 流水线测试

- syncBatchToTrakt 用 Channel 实现并发控制，测试需控制虚拟时间
- **缓解**：用 `runTest` + `advanceUntilIdle()` 控制，mock 各阶段返回值

### 8.4 WakeLock 验证

- WakeLock 是系统服务，单元测试需 mock PowerManager
- **缓解**：用 Robolectric 的 `ShadowPowerManager` 验证 acquire/release 调用
