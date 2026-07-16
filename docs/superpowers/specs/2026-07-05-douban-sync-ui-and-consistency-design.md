# 豆瓣同步 UI 优化与数据一致性设计

**日期**: 2026-07-05
**主题**: 同步进度流水线化、失败项分组吸顶、重试弹窗导出、重新导入模式选择
**状态**: 设计稿（待用户审查）

---

## 背景与问题

豆瓣同步功能在 Phase 1-3 已实现失败重试、取消/续传、通知栏图标等能力,但存在以下问题:

1. **同步进度 UI 跳跃**: 用户看到进度从 220 停顿后跳到 240,体感不流畅。根因是当前 awaitAll 串行阶段 + idx+1 进度更新方式。
2. **完成弹窗显示「0/0」**: 同步完成后 `finalProgress` 未设置 `current/total`,文案错误地显示「同步完成(0/0)」。
3. **失败项分组不够细**: 当前仅按「可恢复/不可恢复」一级分组,用户希望按失败原因二级细分,且标题吸顶。
4. **重试弹窗缺导出入口**: 用户同步完成后未及时导出失败项 JSON,在重试弹窗中又找不到导出按钮。
5. **重新导入数据一致性 bug**:
   - 当前 `forceOverwrite=false` 跳过已同步条目,豆瓣状态变化(想看↔看过)未被处理。
   - 即使 `forceOverwrite=true`,豆瓣「看过→想看」的反向同步也存在 bug(WISH 流程 `isWatched=true → 不覆盖`,Trakt 不会从 watched 移除)。
   - 已看页从 Trakt API 实时拉取,只要 markAsWatched 调用成功就会显示,这部分正确。

---

## 设计目标

- 让同步进度更平滑、可感知
- 修复完成弹窗的「0/0」bug
- 失败项列表按失败原因二级细分,所有层级标题吸顶
- 重试弹窗提供导出失败记录入口
- 重新导入时让用户选择模式,正确处理豆瓣状态变化

---

## 主题 1: 阶段 1→2 流水线 + 进度展示优化

### 当前架构

`syncBatchToTrakt` 分 4 阶段串行:
1. **阶段 1**: 并发爬详情页(并发度 3),`awaitAll` 等所有完成
2. **阶段 2**: 并发查 traktId(并发度 5),`awaitAll` 等所有完成
3. **阶段 3**: 冲突分类(本地计算)
4. **阶段 4**: 批量 POST Trakt(每种操作一次请求)

进度更新用 `onProgress(idx+1)`,idx 是并发任务索引,由于 Semaphore 限流,多个任务同时完成时进度跳跃。

### 设计: Channel-based 流水线

```
┌──────────────────┐    Channel     ┌──────────────────┐    Channel     ┌──────────────┐
│  阶段1: 详情页    │ ──────────→  │  阶段2: Trakt查询 │ ──────────→  │ 阶段3+4: 冲突 │
│ 并发度 3         │  SyncResolve  │ 并发度 5         │ ResolvedTrakt │  + 批量POST  │
│ Semaphore(3)    │               │ Semaphore(5)    │               │              │
└──────────────────┘               └──────────────────┘               └──────────────┘
       ↓ 失败                              ↓ 失败
   failed 列表                         failed 列表
```

**实现要点**:

- 阶段 1 生产者: `coroutineScope { pending.map { async { detailSemaphore.withPermit { fetchDetail() } } } }`,结果写入 `Channel<SyncResolve>(capacity = pending.size)`
- 阶段 2 消费者: `coroutineScope { repeat(5) { launch { for (r in channel) { ... } } } }`,结果写入累积列表
- 阶段 1/2 失败项直接进 `failed` 列表,不写入 Channel
- 阶段 3+4 等阶段 2 Channel 关闭后处理累积列表(保留批量 POST 优势)
- **进度更新**: `AtomicInt` 计数已完成数,每条详情页爬完 +1,UI 看到「已完成数」持续递增
- **取消处理**: Channel 关闭时所有消费者自然终止

### 收益

- 总时间减 10-15%(阶段 1 跑到 30 条时阶段 2 就开始)
- UI 进度持续走动,不再「220 → 跳到 240」

### 真正的瓶颈

阶段 1 详情页爬取(并发度 3 + 5-10s/页反爬延迟),无法绕过豆瓣反爬限制。除非提升并发度,但 3 已经是反爬安全上限。

---

## 主题 2: 完成弹窗修复 + 失败项吸顶子分类

### 「0/0」bug 修复

`finalProgress` 保留最后的 `current/total` 值,文案改为「同步完成」而非显示 `current/total`。

修改 `DoubanSyncDialog` 中的进度文案:
- 进行中: 显示「{phase} ({current}/{total})」
- 完成: 显示「同步完成」+ 总结行(成功·跳过·缓存命中·失败)

### 失败项列表结构

`LazyColumn` + `stickyHeader` 双层结构:

```
[吸顶] 可恢复 (5)                          ← 一级标题(可折叠)
  [吸顶] 详情页访问失败 (3)                 ← 二级标题(可折叠)
    • 条目1
    • 条目2
    • 条目3
  [吸顶] Trakt 写入超时 (1)
    • 条目4
  [吸顶] Trakt 写入失败 (1)
    • 条目5
[吸顶] 不可恢复 (2)                        ← 一级标题(可折叠)
  [吸顶] 无 IMDb ID (1)                    ← 二级标题(可折叠)
    • 条目6
  [吸顶] Trakt 未找到此条目 (1)
    • 条目7
```

### 失败原因分类

**可恢复(3 种)**:
- `DETAIL_FETCH_FAILED`: 详情页访问失败
- `TRAKT_WRITE_TIMEOUT`: Trakt 写入超时
- `TRAKT_WRITE_FAILED`: Trakt 写入失败

**不可恢复(2 种)**:
- `NO_IMDB_ID`: 无 IMDb ID
- `TRAKT_NOT_FOUND`: Trakt 未找到此条目

### 折叠行为

- **一级默认**: 可恢复展开、不可恢复折叠
- **二级默认**: 全展开
- **一级折叠时**: 二级隐藏(整个分组收起)
- **二级折叠时**: 仅条目隐藏,二级标题保留

### 技术实现

使用 `LazyColumn` + `stickyHeader` Composable(实验性 API,需 `@OptIn(ExperimentalFoundationApi::class)`):

```kotlin
LazyColumn {
    // 一级:可恢复
    if (recoverableGroups.isNotEmpty()) {
        stickyHeader(key = "recoverable_header") {
            FailureGroupHeader(
                title = "可恢复 (${recoverableTotal})",
                expanded = recoverableExpanded,
                onClick = { recoverableExpanded = !recoverableExpanded }
            )
        }
        if (recoverableExpanded) {
            for ((reason, items) in recoverableGroups) {
                stickyHeader(key = "recoverable_${reason}") {
                    FailureSubGroupHeader(
                        title = "${reasonLabel(reason)} (${items.size})",
                        expanded = subExpandedMap[reason] ?: true,
                        onClick = { ... }
                    )
                }
                if (subExpandedMap[reason] ?: true) {
                    items(items, key = { it.doubanId }) { failure ->
                        FailureItemRow(failure)
                    }
                }
            }
        }
    }
    // 一级:不可恢复(同结构)
}
```

---

## 主题 3: 重试弹窗加导出按钮

### 位置

`DoubanRetryDialog` 新增「导出失败记录」选项,与「重试本地失败」「导入 JSON」并列。注:主题 4 会移除原有的「增量同步」选项,最终 `DoubanRetryDialog` 三个选项: 重试本地失败 / 导入 JSON / 导出失败记录。

### 行为

点击后:
1. 调用 `DoubanFailureExporter.exportFromLocal(context)`
2. 该方法从 `douban_sync_failures` 表读取所有失败项
3. 生成 JSON 并触发 ShareSheet
4. 显示导出成功/失败提示

### 新增 API

`DoubanFailureExporter` 新增方法:
```kotlin
suspend fun exportFromLocal(context: Context): Uri?
```

从 Room 数据库读取所有失败项,转换为 `List<DoubanSyncFailure>`,复用现有 `exportFromList` 逻辑。

---

## 主题 4: 重新导入模式选择

### UI 入口

设置页两个独立按钮:

1. **「重新同步豆瓣」按钮**: 点击后弹「模式选择对话框」(A/B/C,附说明+示例)
2. **「重试上次失败项」按钮**: 仅当 `douban_sync_failures` 表非空时显示,点击弹 `DoubanRetryDialog`(仅「重试本地失败」「导入 JSON」两个选项 + 第四个「导出失败记录」选项)

### DoubanRetryDialog 改动

- 删除「增量同步」选项(原选项 2)
- 新增「导出失败记录」选项(第四个)
- 最终三个选项: 重试本地失败 / 导入 JSON / 导出失败记录

### 模式选择对话框

新建 `DoubanSyncModePickerDialog` Composable:

```
┌────────────────────────────────────────────┐
│ 选择重新导入模式                            │
├────────────────────────────────────────────┤
│                                            │
│  📥 仅同步新增条目                          │
│  仅同步豆瓣新加的想看/看过,跳过已同步条目  │
│  不处理状态变化和删除                       │
│  例:豆瓣新加 5 部想看 → 同步到 Trakt       │
│      豆瓣某片从想看改成看过 → 不处理        │
│                                            │
│  🔄 同步新增 + 状态变化                     │
│  同步新增 + 检测状态变化,撤销旧操作应用新操作│
│  不处理删除                                 │
│  例:豆瓣某片从想看改成看过 → 移除想看+标记已看│
│      豆瓣某片从看过改成想看 → 移除已看+标记想看│
│                                            │
│  ♻️ 完全重写(谨慎)                          │
│  清除 Trakt 上之前同步过的所有标记,         │
│  重新应用豆瓣当前状态                       │
│  风险:用户在 Trakt 上手动改的状态会丢失    │
│  例:豆瓣想看 100 部 → 清除后重新加 100 部   │
│      豆瓣看过 200 部 → 清除后重新加 200 部 │
│                                            │
│              [取消]  [开始同步]             │
└────────────────────────────────────────────┘
```

### SyncMode 枚举

```kotlin
enum class SyncMode {
    INCREMENTAL_ONLY,        // 模式 A:仅新增
    INCREMENTAL_WITH_CHANGES, // 模式 B:增量 + 状态变化
    FULL_REWRITE              // 模式 C:完全重写
}
```

### DoubanSyncManager 改动

新增方法:
```kotlin
fun startSync(mode: SyncMode): Boolean {
    if (isRunning()) return false
    cancelled = false
    syncJob = appScope.launch { runSync(mode) }
    return true
}
```

保留原 `startSync(forceOverwrite: Boolean)` 作为兼容入口(内部转换为 `INCREMENTAL_ONLY` 或 `FULL_REWRITE`)。

### 模式 A 实现: 仅增量同步

当前 `forceOverwrite=false` 的行为,无需改动:
- 跳过已同步条目(doubanId 在 syncedIds 中)
- 不处理状态变化
- 不处理删除

### 模式 B 实现: 增量 + 状态变化同步

核心改动: 增加「状态变化检测」逻辑。

**runSync 流程**:

1. 加载 `douban_synced_items` 表所有记录,建立 `Map<doubanId, DoubanSyncedItem>`
2. 跳过已同步且状态一致的条目
3. 对于「已同步但豆瓣状态变化」的条目:
   - WISH → COLLECT: `removeFromWatchlist` + `markAsWatched`
   - COLLECT → WISH: `removeFromWatched` + `addToWatchlist`
   - 更新 `douban_synced_items.status` 字段
4. 对于新增条目: 走原 `syncBatchToTrakt` 流程

**数据结构**:
- `DoubanSyncedItem` 已有 `status` 字段,无需 schema 改动
- 同步时对比 `doubanSyncedItem.status` 与豆瓣当前 `status`

**批量优化**:
- 状态变化项按变化类型分组(同方向变更可批量处理)
- `removeFromWatchlist` + `markAsWatched` 仍走批量 API

### 模式 C 实现: 完全重写

**runSync 流程**:

1. 读 `douban_synced_items` 表所有 traktId,按 status + mediaType 分组
2. 批量 `removeFromWatchlist` + `removeFromWatched`(按 mediaType 分组)
3. 清空 `douban_synced_items` 表
4. 走 `forceOverwrite=true` 的同步流程

**风险控制**:
- 模式 C 需要二次确认对话框,提示「将清除 N 项已同步标记,可能丢失手动修改的状态」
- N = `douban_synced_items` 表行数
- 用户确认后才执行

### TraktRepository 新增 API

```kotlin
suspend fun batchRemoveFromWatched(
    movieIds: List<Int>,
    showIds: List<Int>
)
```

调用 Trakt API `DELETE /sync/history` 批量移除 watched 记录。

参考现有 `batchRemoveFromWatchlist` 实现,使用相同 OkHttp + JSON 模式。

---

## 数据流

### 模式 A 数据流(当前行为)

```
豆瓣列表爬取 → syncedIds 跳过 → 新增条目走 syncBatchToTrakt → 写入 douban_synced_items
```

### 模式 B 数据流

```
豆瓣列表爬取
    ↓
对比 douban_synced_items.status
    ├─ 状态一致 → 跳过
    ├─ 状态变化 → 撤销旧操作 + 应用新操作 → 更新 douban_synced_items.status
    └─ 新增条目 → 走 syncBatchToTrakt → 写入 douban_synced_items
```

### 模式 C 数据流

```
读 douban_synced_items 全表
    ↓
按 status + mediaType 分组 → 批量 removeFromWatchlist/removeFromWatched
    ↓
清空 douban_synced_items 表
    ↓
走 forceOverwrite=true 的同步流程(所有豆瓣条目当作新增处理)
```

---

## 错误处理

### 模式 B 状态变化失败

- 撤销旧操作失败: 整个状态变化标记为失败,原因 `TRAKT_WRITE_FAILED`
- 应用新操作失败: 整个状态变化标记为失败,旧操作已撤销(无法回滚)
- 数据库 status 字段仅在「撤销+应用」全部成功后更新

### 模式 C 清空失败

- `removeFromWatchlist/removeFromWatched` 失败: 整个模式 C 同步失败,提示用户「清空失败,请重试」
- 不进入后续同步流程,避免数据丢失

### 流水线 Channel 异常

- 阶段 1 生产者异常: 关闭 Channel,阶段 2 消费者自然终止,已完成的进入累积列表
- 阶段 2 消费者异常: 该条目标记失败,其他消费者继续

---

## 测试要点

### 主题 1 测试

- 流水线模式下进度持续递增(不再跳跃)
- 阶段 1 失败项不进阶段 2
- 取消时 Channel 正确关闭,无协程泄漏

### 主题 2 测试

- 完成弹窗不再显示「0/0」
- 失败项列表按二级分组正确展示
- stickyHeader 滚动时正确吸顶
- 一级/二级折叠行为符合预期

### 主题 3 测试

- 重试弹窗导出按钮生成 JSON 文件
- 导出后 ShareSheet 正常弹出

### 主题 4 测试

- 模式 A: 新增条目正确同步,已同步条目跳过
- 模式 B:
  - 想看→看过: removeFromWatchlist + markAsWatched 调用
  - 看过→想看: removeFromWatched + addToWatchlist 调用
  - douban_synced_items.status 字段正确更新
- 模式 C:
  - 二次确认对话框显示正确的 N 值
  - 清空 + 重新同步流程完整执行
  - 用户取消时不执行任何操作

---

## 范围与 YAGNI

### 包含

- 阶段 1→2 流水线化
- 完成弹窗「0/0」修复
- 失败项双层吸顶分组
- 重试弹窗导出按钮
- 重新导入模式选择(A/B/C)
- TraktRepository 新增 batchRemoveFromWatched

### 不包含

- 阶段 4 拆分为分批 POST(用户未选择,保持批量优势)
- 模式 B 处理删除(用户明确选择不处理)
- 评分同步策略调整(已有逻辑保留)
- 失败项导出文件格式变更(沿用 JSON)

---

## 影响范围

### 新增文件

- `DoubanSyncModePickerDialog.kt`: 模式选择对话框
- (可选) `DoubanSyncMode.kt`: SyncMode 枚举

### 修改文件

- `DoubanSyncManager.kt`: 新增 startSync(mode) + 模式 B/C 实现 + 阶段 1→2 流水线
- `DoubanSyncDialog.kt`: 修复「0/0」 + 双层吸顶分组
- `DoubanRetryDialog.kt`: 删除「增量同步」选项,新增「导出失败记录」选项
- `DoubanFailureExporter.kt`: 新增 exportFromLocal 方法
- `TraktRepository.kt`: 新增 batchRemoveFromWatched
- `SettingsScreen.kt`: 拆分为两个独立按钮入口
- `strings.xml` (4 语言): 新增模式选择相关文案

### 数据库

无 schema 改动(`DoubanSyncedItem` 已有 status 字段)。

---

## 待确认事项

无,所有澄清问题已在头脑风暴中确认。
