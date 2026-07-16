# 豆瓣同步失败重试机制 设计规格

## 背景

豆瓣标记同步流程中,用户取消同步后会得到一份失败项列表(例如 338 项失败,原因可能是"无 IMDb ID"、"详情页访问失败"等)。当前实现存在以下问题:

1. 失败项弹窗只展示前 10 条,无法滑动查看全部
2. 失败项数据结构是 `Pair<String, String>`,只保存标题和原因,无法导出/重试
3. 进度展示只有粗略阶段,看不到当前正在处理的具体条目
4. 重新导入只能整体重跑,无法只重试失败的项,浪费请求
5. 已成功的项会通过 `douban_synced_items` 表跳过,但失败项没有持久化,下次重启就丢失

## 决策

| # | 决策 | 选择 |
|---|------|------|
| 1 | 重试策略 | 智能区分:可恢复的自动重试,不可恢复的默认跳过但可手动勾选 |
| 2 | 增量同步定义 | 仅新增标记(基于 doubanId 去重) |
| 3 | 导出格式 | JSON + ShareSheet |
| 4 | 失败数据留存 | Room 表 + 重试后清除 |
| 5 | 重试数据源 | 三路并存(本地 Room / 用户 JSON / 增量同步) |
| 6 | 入口交互 | 检测到失败数据才弹选择对话框,否则直接走增量同步 |

## 数据结构

### `DoubanSyncFailure`(新增)

完整失败项数据类,包含豆瓣条目全部信息:

```kotlin
data class DoubanSyncFailure(
    val doubanId: String,
    val title: String,
    val posterUrl: String?,
    val rating: Int?,
    val comment: String?,
    val markedAt: String,
    val doubanUrl: String,
    val status: DoubanMarkStatus,
    val failureReason: FailureReason,
    val failedAt: Long,
    val attemptCount: Int = 0
)
```

### `FailureReason`(新增枚举)

| 枚举值 | 含义 | 可恢复 |
|--------|------|--------|
| `NO_IMDB_ID` | 详情页无 IMDb ID | 否 |
| `DETAIL_FETCH_FAILED` | 详情页访问失败 | 是 |
| `TRAKT_NOT_FOUND` | Trakt 反查无此条目 | 否 |
| `TRAKT_WRITE_TIMEOUT` | Trakt 写入超时 | 是 |
| `TRAKT_WRITE_FAILED` | Trakt 写入其他失败 | 是 |

### `DoubanSyncFailureEntity`(新增 Room Entity)

```kotlin
@Entity(tableName = "douban_sync_failures", indices = [Index("status"), Index("failureReason")])
data class DoubanSyncFailureEntity(
    @PrimaryKey val doubanId: String,
    val title: String,
    val posterUrl: String?,
    val rating: Int?,
    val comment: String?,
    val markedAt: String,
    val doubanUrl: String,
    val status: String,            // "wish" / "collect"
    val failureReason: String,     // FailureReason.name
    val failedAt: Long,
    val attemptCount: Int = 0
)
```

写入策略:每次同步完成后,按 status 分组覆盖写入(先删同 status 旧失败项,再插新的)。重试成功的项从表里删除。

### `DoubanSyncProgress` 改造

```kotlin
data class DoubanSyncProgress(
    // ... 保留现有字段
    val failedItems: List<DoubanSyncFailure> = emptyList(),    // 替换 Pair<String, String>
    val currentTitle: String? = null,                          // 当前正在处理的条目标题
    val recentFailures: List<DoubanSyncFailure> = emptyList()  // 最近 5 条失败(实时滚动)
)
```

## UI 改造

### `DoubanSyncDialog` 改造

- 失败项列表改用 `LazyColumn`(高度限制 240dp),支持滑动查看全部
- 按"可恢复/不可恢复"分组,每组带折叠展开
- 顶部新增"导出失败项"按钮 → 生成 JSON → ShareSheet
- 失败原因用不同颜色标签区分(橙色=可恢复,灰色=不可恢复)

### `DoubanRetryDialog`(新增)

点"重新同步豆瓣"时,若 `douban_sync_failures` 表非空,弹此对话框:

- 选项 1:重试上次失败的 N 项(从本地 Room 读)
- 选项 2:增量同步新增标记
- 选项 3:导入 JSON 文件重试

选中"重试上次失败"后再弹子对话框,列出可勾选的失败类型,默认勾选可恢复类型。

### 进度详情展示增强

- 实时显示当前正在处理的条目:`"详情页 (45/338) — 正在获取 唐诡奇谭"`
- 每完成一项立即更新计数
- 失败时在进度面板底部临时显示最近 5 条失败(滚动更新)

## 重试流程

### 重试数据源抽象

```kotlin
sealed class RetrySource {
    object LocalDatabase : RetrySource()
    data class ImportedJson(val failures: List<DoubanSyncFailure>) : RetrySource()
}

data class RetryRequest(
    val source: RetrySource,
    val selectedReasons: Set<FailureReason>
)
```

### 重试流程

1. 根据 `RetrySource` 加载失败项列表
   - `LocalDatabase`:从 `douban_sync_failures` 表读取
   - `ImportedJson`:解析 JSON 文件
2. 按 `selectedReasons` 过滤
3. 走 `syncBatchToTrakt` 子流程:
   - 跳过豆瓣列表爬取(已有 doubanUrl 等数据)
   - 直接进入"详情页 → TraktId 查询 → Trakt 写入"阶段
4. 重试成功 → 从 `douban_sync_failures` 表删除(仅 `LocalDatabase` 模式)
5. 仍然失败 → `attemptCount + 1`,更新 `failureReason`
6. `attemptCount >= 3` → UI 标记"已重试 3 次",不再自动建议重试

### 增量同步流程

1. 爬取豆瓣想看/已看列表(同现有流程)
2. 用 `doubanSyncedItemDao.getByIds()` 过滤已同步的 doubanId(断点续传)
3. 仅对新增 doubanId 走详情页 → TraktId → Trakt 写入流程
4. 失败项写入 `douban_sync_failures` 表(覆盖同 status 旧失败项)

## JSON 导出/导入格式

```json
{
  "version": 1,
  "exportedAt": "2026-07-05T12:00:00Z",
  "source": "TraktToSearch",
  "totalFailures": 338,
  "failures": [
    {
      "doubanId": "1234567",
      "title": "唐诡奇谭",
      "posterUrl": "https://...",
      "rating": 5,
      "comment": "好看",
      "markedAt": "2024-01-01",
      "doubanUrl": "https://book.douban.com/subject/1234567/",
      "status": "wish",
      "failureReason": "NO_IMDB_ID",
      "failedAt": 1720000000000,
      "attemptCount": 0
    }
  ]
}
```

- 导出:`cacheDir/TraktToSearch-失败项-20260705.json` → ShareSheet
- 导入:`ACTION_OPEN_DOCUMENT` 文件选择器 → 解析校验 → 走重试流程

## 改动文件清单

### 新增(6 个)

- `data/repository/DoubanSyncFailure.kt` — 失败项数据类 + `FailureReason` 枚举
- `data/local/db/DoubanSyncFailureDao.kt` — Room DAO
- `ui/screen/douban/DoubanRetryDialog.kt` — 重新导入选择对话框 + 重试选项子对话框
- `data/repository/DoubanFailureExporter.kt` — JSON 导出/导入工具
- `data/repository/DoubanRetryManager.kt` — 重试流程编排
- `data/repository/DoubanRetrySource.kt` — 重试数据源抽象(可合并到 DoubanRetryManager.kt)

### 改造(7 个)

- `data/repository/DoubanSyncManager.kt` — 失败项数据结构、重试入口、增量同步优化
- `ui/screen/douban/DoubanSyncDialog.kt` — 滑动列表、导出按钮、进度详情
- `data/remote/douban/DoubanRepository.kt` — `fetchDetail` 增加进度回调
- `data/local/db/DoubanEntities.kt` — 新增 `DoubanSyncFailureEntity`(或拆分文件)
- `data/local/db/AppDatabase.kt` — version +1,注册新实体
- `data/local/db/DatabaseModule.kt` — 新增 MIGRATION_4_5,提供新 DAO
- `di/DoubanModule.kt` — DI 注册 `DoubanRetryManager`
- `ui/screen/settings/SettingsScreen.kt` — 接入「有失败才弹」入口逻辑
- `service/DoubanSyncService.kt` — 通知文案适配(可选)

### 字符串资源

`values/` `values-zh/` `values-ja/` `values-ko/` 同步新增。

## 验证

构建 debug 包验证:

```
.\gradlew assembleDebug
```

确保构建成功且无功能影响。
