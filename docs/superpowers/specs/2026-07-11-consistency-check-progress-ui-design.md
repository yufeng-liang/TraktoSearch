# 状态一致性检查进度展示设计

## 背景

当前设置页「检查状态一致性」入口只有一个"正在检查..."文字提示，无进度展示、无转后台、无延时信息。用户无法知道时间花在哪里。

需求：
1. 手动检查时先爬豆瓣列表拿最新状态（能发现外部手动改的状态变化）
2. 进度弹窗展示当前阶段、进度、条目标题、延时倒计时
3. 弹窗「转后台」按钮 → 通知栏显示进度 + Watchlist 横幅显示进度
4. 点击横幅/通知 → 重新弹出弹窗
5. 完成后自动弹出弹窗展示结果

## 两类检查的区别

| 维度 | 同步后自动检查 | 设置页手动检查 |
|------|--------------|--------------|
| 触发时机 | `uploadToCloudAfterSync` 内 | 用户点击设置页入口 |
| 数据来源 | 本地表快照（同步时刚写入） | 爬豆瓣列表拿最新状态 |
| 是否爬列表 | 否 | 是（想看+已看两个列表） |
| 耗时 | 秒级 | 分钟级 |
| 进度展示 | 无（静默执行） | 弹窗+横幅+通知栏 |

同步后自动检查保持当前实现不变（只读本地表对比，快），本次改动仅针对设置页手动检查。

## 1. ConsistencyCheckResult 扩展为进度数据类

参考 `DoubanSyncProgress` 结构扩展：

```kotlin
data class ConsistencyCheckResult(
    val isRunning: Boolean = false,
    val phase: String = "",           // "爬取豆瓣列表" / "对比状态" / "更新Trakt" / "更新豆瓣" / "完成"
    val subPhase: String = "",        // "想看列表" / "已看列表" / "详情页拿ck" 等
    val current: Int = 0,
    val total: Int = 0,
    val currentTitle: String? = null,  // 当前处理的条目标题
    val totalChecked: Int = 0,
    val conflictsFound: Int = 0,
    val doubanUpdated: Int = 0,
    val traktUpdated: Int = 0,
    val skipped: Int = 0,
    val errors: Int = 0,
    val delayInfo: DelayInfo? = null,  // 豆瓣反爬延迟信息
    val cookieExpired: Boolean = false,
    val isComplete: Boolean = false,
    val startTimeMs: Long = 0
)
```

## 2. DoubanTraktStatusConsistencyChecker 改造

### 新增 checkAndUnifyWithCrawl 方法

```kotlin
/**
 * 手动触发：爬豆瓣列表拿最新状态后对比。
 * 与 checkAndUnify（仅本地表对比）的区别：先爬豆瓣想看+已看列表。
 */
suspend fun checkAndUnifyWithCrawl(): ConsistencyCheckResult
```

### 流程

```
阶段1: 爬取豆瓣列表
  subPhase="想看列表" → fetchMarkList(WISH) → 累积到 latestDoubanStatuses Map
  subPhase="已看列表" → fetchMarkList(COLLECT) → 累积到 latestDoubanStatuses Map
  每页间 5-10 秒反爬延迟（delayEvent 自动触发）

阶段2: 对比状态
  subPhase="对比状态"
  遍历 latestDoubanStatuses，与 Trakt 缓存对比
  分类收集冲突项（同现有 checkAndUnify 逻辑）

阶段3: 批量更新 Trakt 侧（如有冲突）
  subPhase="更新Trakt"
  批量 API 调用，速度快

阶段4: 逐条更新豆瓣侧（如有冲突）
  subPhase="详情页拿ck" + markInterest
  并发度 2，反爬延迟

阶段5: 完成
  isComplete = true
```

### 进度更新机制

- `_checkProgress: MutableStateFlow<ConsistencyCheckResult>`
- 每个阶段开始/完成时更新 phase + subPhase
- `fetchMarkList` 的 `onProgress` 回调更新 current/total
- 豆瓣标记时更新 currentTitle
- 订阅 `doubanRepository.delayEvent` 合并到 delayInfo

### Application scope

Checker 内部新增 `appScope`（同 DoubanSyncManager 模式），手动检查在 appScope 中跑，不依赖调用者（SettingsViewModel/Activity 销毁不影响）。

### 取消支持

```kotlin
fun cancel()
```

用户在弹窗点「取消」或通知栏「取消」时调用。

## 3. ConsistencyCheckDialog（新建）

文件：`app/src/main/java/com/tracktosearch/ui/screen/settings/ConsistencyCheckDialog.kt`

参考 `DoubanSyncDialog` 简化版：

```kotlin
@Composable
fun ConsistencyCheckDialog(
    onDismiss: () -> Unit,
    onBackground: () -> Unit,
    viewModel: SettingsViewModel = hiltViewModel()
)
```

### UI 结构

```
AlertDialog
├─ text
│  ├─ 主进度（phase + current/total）
│  ├─ 子阶段（subPhase）
│  ├─ 当前条目标题（currentTitle）
│  ├─ 进度条（LinearProgressIndicator）
│  ├─ 延时倒计时（delayInfo）
│  └─ 完成时结果统计（冲突数/Trakt更新数/豆瓣更新数/错误数）
└─ confirmButton
   ├─ isRunning: 「转后台」+「取消」
   └─ isComplete: 「完成」
```

### 延时倒计时

复用 DoubanSyncDialog 的 `LaunchedEffect(delayInfo)` 模式，每秒刷新剩余秒数。

## 4. SettingsViewModel 改造

```kotlin
val checkProgress: StateFlow<ConsistencyCheckResult> = statusConsistencyChecker.checkProgress

fun startManualConsistencyCheck() {
    viewModelScope.launch { statusConsistencyChecker.checkAndUnifyWithCrawl() }
}

fun cancelConsistencyCheck() {
    statusConsistencyChecker.cancel()
}
```

移除原 `checkStatusConsistency()` 方法（改为 `startManualConsistencyCheck`）。

## 5. SettingsScreen 改造

点击「检查状态一致性」入口：
- 如果检查未运行 → 弹出 `ConsistencyCheckDialog` + 调用 `startManualConsistencyCheck()`
- 如果检查已运行 → 直接弹出 `ConsistencyCheckDialog`（恢复进度展示）

```kotlin
var showConsistencyDialog by remember { mutableStateOf(false) }

SettingsItemCard(
    icon = Icons.Rounded.SyncAlt,
    title = stringResource(R.string.settings_douban_status_consistency),
    onClick = {
        if (!consistencyCheckState.isRunning) {
            viewModel.startManualConsistencyCheck()
        }
        showConsistencyDialog = true
    }
)

if (showConsistencyDialog) {
    ConsistencyCheckDialog(
        onDismiss = {
            val p = consistencyCheckState
            if (!p.isRunning) showConsistencyDialog = false
        },
        onBackground = { showConsistencyDialog = false }
    )
}
```

## 6. WatchlistScreen + WatchlistViewModel 横幅

### WatchlistViewModel

新增字段：
```kotlin
val consistencyCheckProgress: StateFlow<ConsistencyCheckResult?> = 
    statusConsistencyChecker.checkProgress
        .map { if (it.isRunning || it.isComplete) it else null }
        .stateIn(viewModelScope, SharingStarted.Lazily, null)

private val _consistencyCheckCompleteEvent = MutableSharedFlow<Unit>()
val consistencyCheckCompleteEvent = _consistencyCheckCompleteEvent.asSharedFlow()
```

init 块监听：
```kotlin
init {
    viewModelScope.launch {
        statusConsistencyChecker.checkProgress.collect { progress ->
            if (progress.isComplete) {
                _consistencyCheckCompleteEvent.emit(Unit)
                delay(5000)
                // 5 秒后横幅自动消失（checkProgress 仍保留结果数据）
            }
        }
    }
}
```

### WatchlistScreen

在现有豆瓣同步横幅下方新增状态检查横幅（UI 结构复用同步横幅模式）：

```kotlin
val checkProgress = uiState.consistencyCheckProgress
if (checkProgress != null) {
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { showConsistencyDialog = true },
        color = MaterialTheme.colorScheme.tertiaryContainer
    ) {
        Row(...) {
            Icon(
                imageVector = if (checkProgress.isRunning) Icons.Rounded.SyncAlt 
                    else Icons.Rounded.CheckCircle,
                modifier = Modifier
                    .size(18.dp)
                    .then(if (checkProgress.isRunning) Modifier.graphicsLayer { rotationZ = spinRotation } else Modifier)
            )
            Column(modifier = Modifier.weight(1f)) {
                Text(text = if (checkProgress.isComplete) "检查完成" 
                    else "${checkProgress.phase} (${checkProgress.current}/${checkProgress.total})")
                if (checkProgress.isRunning && checkProgress.total > 0) {
                    LinearProgressIndicator(
                        progress = { (checkProgress.current.toFloat() / checkProgress.total).coerceIn(0f, 1f) }
                    )
                }
            }
        }
    }
}
```

完成事件自动弹出：
```kotlin
LaunchedEffect(Unit) {
    viewModel.consistencyCheckCompleteEvent.collect {
        showConsistencyDialog = true
    }
}
```

## 7. ConsistencyCheckService（新建通知栏）

文件：`app/src/main/java/com/tracktosearch/service/ConsistencyCheckService.kt`

参考 `DoubanSyncService`，仅通知栏进度展示，不启动检查（检查在 Checker 的 appScope 中跑）。

```kotlin
class ConsistencyCheckService : Service() {
    // NOTIF_ID = 9002（与 DoubanSyncService 的 9001 区分）
    // CHANNEL_ID = "consistency_check"
    
    fun start(context: Context) { ... }
    
    // collectLatest statusConsistencyChecker.checkProgress
    // isComplete → stopSelf
    // isRunning → 更新通知（phase + current/total）
}
```

### AndroidManifest 注册

```xml
<service
    android:name=".service.ConsistencyCheckService"
    android:exported="false"
    android:foregroundServiceType="dataSync" />
```

### 通知渠道

`IMPORTANCE_LOW`，id = `"consistency_check"`，与豆瓣同步通知渠道独立。

## 8. 转后台/回前台机制

```
弹窗「转后台」按钮 → ConsistencyCheckService.start(context) + onBackground() 隐藏弹窗
  → 通知栏显示进度
  → Watchlist 横幅显示进度（tertiaryContainer 色区分）

点击横幅 → showConsistencyDialog = true → 重新弹出 Dialog
点击通知 → 启动 MainActivity（回到前台）

完成 → checkProgress.isComplete = true
  → WatchlistViewModel 发 consistencyCheckCompleteEvent
  → WatchlistScreen 自动弹出 Dialog 展示结果
  → Service collectLatest 检测 isComplete → stopSelf
  → 5 秒后横幅消失
```

## 9. 4 语言字符串

| key | 中文 | 英文 | 日文 | 韩文 |
|-----|------|------|------|------|
| `consistency_check_title` | 状态一致性检查 | Status Consistency Check | 状態一貫性チェック | 상태 일관성 확인 |
| `consistency_check_background` | 转后台 | Background | バックグラウンド | 백그라운드 |
| `consistency_check_cancel` | 取消 | Cancel | キャンセル | 취소 |
| `consistency_check_complete_banner` | 检查完成 | Check complete | チェック完了 | 확인 완료 |
| `consistency_check_phase_crawl` | 爬取豆瓣列表 | Crawling Douban lists | 豆瓣リスト取得中 | 더우반 목록 가져오는 중 |
| `consistency_check_phase_compare` | 对比状态 | Comparing statuses | 状態比較中 | 상태 비교 중 |
| `consistency_check_phase_update_trakt` | 更新 Trakt | Updating Trakt | Trakt更新中 | Trakt 업데이트 중 |
| `consistency_check_phase_update_douban` | 更新豆瓣 | Updating Douban | 豆瓣更新中 | 더우반 업데이트 중 |
| `consistency_check_phase_done` | 完成 | Done | 完了 | 완료 |
| `consistency_check_summary` | 检查 %1$d 项，发现 %2$d 个冲突，Trakt 更新 %3$d 项，豆瓣更新 %4$d 项，错误 %5$d 项 | Checked %1$d items, found %2$d conflicts, Trakt updated %3$d, Douban updated %4$d, errors %5$d | %1$d件チェック、%2$d件の競合、Trakt更新%3$d件、豆瓣更新%4$d件、エラー%5$d件 | %1$d건 확인, %2$d개 충돌, Trakt 업데이트 %3$d건, 더우반 업데이트 %4$d건, 오류 %5$d건 |
| `consistency_check_sub_wish` | 想看列表 | Wish list | 見たいリスト | 보고싶어요 목록 |
| `consistency_check_sub_collect` | 已看列表 | Watched list | 見たリスト | 봤어요 목록 |
| `consistency_check_sub_detail_ck` | 详情页拿ck | Fetching ck | 詳細ページck取得 | 상세 페이지 ck 가져오는 중 |

## 涉及文件

| 文件 | 改动 |
|------|------|
| `DoubanTraktStatusConsistencyChecker.kt` | 扩展 ConsistencyCheckResult，新增 checkAndUnifyWithCrawl + appScope + cancel + delayEvent 订阅 |
| `ConsistencyCheckDialog.kt` | 新建：进度弹窗 |
| `ConsistencyCheckService.kt` | 新建：通知栏 Foreground Service |
| `SettingsViewModel.kt` | 暴露 checkProgress，新增 startManualConsistencyCheck/cancelConsistencyCheck |
| `SettingsScreen.kt` | 点击入口弹出 Dialog |
| `WatchlistViewModel.kt` | 观察 checkProgress，暴露横幅状态 + 完成事件 |
| `WatchlistScreen.kt` | 新增状态检查横幅 + Dialog 渲染 |
| `AndroidManifest.xml` | 注册 ConsistencyCheckService |
| `strings.xml` × 4 | 新增 13 条字符串 |
