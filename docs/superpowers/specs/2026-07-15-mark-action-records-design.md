# 标记记录模块设计

**日期**: 2026-07-15
**范围**: 新增「标记记录」模块，支持按操作类型（想看/已看/移除）、日期范围、媒体类型筛选，记录包含影视快照信息、操作时间、操作类型；点击跳转详情页
**登录态**: 全模块需登录态，未登录时隐藏入口

---

## 1. 架构与数据来源

### 1.1 双来源策略

| 操作类型 | 数据来源 | 是否存本地 | 说明 |
|---|---|---|---|
| 加想看 | App 内操作流水 | 自建表 `mark_action_record` | Trakt 不留存，必须自建 |
| 移除想看 | App 内操作流水 | 自建表 | Trakt 不留存 |
| 取消已看 | App 内操作流水 | 自建表 | Trakt 不留存 |
| 已看（整剧/单集） | Trakt `/sync/history` API | 不存本地 | 实时分页拉取，1 小时进程内缓存 |

**设计理由**：
- 已看操作 Trakt 原生保存全量历史（带 `watched_at`），不回溯的话用户上线看到已看记录是空的
- 想看/移除操作 Trakt 不留存，必须自建流水
- 已看走 Trakt API、其他走自建表，职责清晰、无去重问题

### 1.2 不写入流水的情况

- `markAsWatched` / `markEpisodeWatched` / `markEpisodesWatched`（走 Trakt 历史，不重复记录）
- 豆瓣同步批量操作（已由 `DoubanSyncedItem` 表专门记录，避免一次导入刷屏）
- API 失败的操作（失败即未生效）

### 1.3 登录态处理

- 整个模块需登录态：`SettingsScreen` 入口在未登录时不渲染
- Trakt `/sync/history` 本身需登录态，已看 Tab 数据天然依赖登录
- 自建表数据在未登录时不展示（入口已隐藏），退出登录不清空数据

---

## 2. 数据模型

### 2.1 自建流水表 `MarkActionRecordEntity`

新增文件：`app/src/main/java/com/tracktosearch/data/local/entity/MarkActionRecordEntity.kt`

```kotlin
@Entity(
    tableName = "mark_action_record",
    indices = [
        Index("actionType"),
        Index("actedAt"),
        Index("mediaType"),
        Index("traktId"),
        Index(value = ["actionType", "actedAt"]),
        Index(value = ["mediaType", "actedAt"]),
        Index(value = ["actionType", "mediaType", "actedAt"])
    ]
)
data class MarkActionRecordEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val traktId: Int,
    val tmdbId: Int,
    val imdbId: String,
    val mediaType: String,         // "movie" / "show"
    val title: String,             // 快照，避免缓存失效时变空
    val displayTitle: String,
    val posterUrl: String?,        // 快照
    val year: Int?,
    val actionType: String,        // ADD_WATCHLIST / REMOVE_WATCHLIST / UNMARK_WATCHED
    val actedAt: Long,             // 操作时间戳（毫秒）
    val episodeInfo: String?       // "S01E03" / "S01E03-E05"，仅取消单集已看时填，其余为 null
)
```

**字段说明**：
- `title / displayTitle / posterUrl / year` 为快照字段，写入时从操作入参或缓存中取，避免后续条目不在缓存时列表变空白
- `episodeInfo` 仅在 `unmarkEpisodeWatched` 时填入单集标识；批量取消多集时聚合成 "S01E03-E05" 格式，避免一次操作产生几十条流水
- 复合索引覆盖常用筛选组合：`actionType + actedAt`、`mediaType + actedAt`、三字段组合

### 2.2 操作类型枚举

```kotlin
enum class MarkActionType(val value: String) {
    ADD_WATCHLIST("ADD_WATCHLIST"),       // 加想看
    REMOVE_WATCHLIST("REMOVE_WATCHLIST"), // 移除想看
    UNMARK_WATCHED("UNMARK_WATCHED");     // 取消已看（含整剧/单集）

    companion object {
        fun fromValue(v: String) = entries.firstOrNull { it.value == v }
    }
}
```

### 2.3 DAO

新增文件：`app/src/main/java/com/tracktosearch/data/local/dao/MarkActionRecordDao.kt`

```kotlin
@Dao
interface MarkActionRecordDao {
    @Insert
    suspend fun insert(record: MarkActionRecordEntity)

    @Insert
    suspend fun insertAll(records: List<MarkActionRecordEntity>)

    /**
     * 分页查询，支持按操作类型/媒体类型/时间范围/标题模糊匹配
     * @param actionTypes 操作类型集合，空则不限
     * @param mediaTypes 媒体类型集合，空则不限
     * @param startTime 起始时间戳（含），0 则不限
     * @param endTime 结束时间戳（含），0 则不限
     * @param titleQuery 标题模糊匹配（已加 %），null 则不限
     * @param limit 每页条数
     * @param offset 偏移量
     * @param ascending true=时间正序，false=时间倒序（默认）
     */
    @Query("""
        SELECT * FROM mark_action_record
        WHERE (:actionTypesEmpty OR actionType IN (:actionTypes))
          AND (:mediaTypesEmpty OR mediaType IN (:mediaTypes))
          AND (:startTime = 0 OR actedAt >= :startTime)
          AND (:endTime = 0 OR actedAt <= :endTime)
          AND (:titleQuery IS NULL OR title LIKE :titleQuery OR displayTitle LIKE :titleQuery)
        ORDER BY CASE WHEN :ascending = 1 THEN actedAt END ASC,
                 CASE WHEN :ascending = 0 THEN actedAt END DESC
        LIMIT :limit OFFSET :offset
    """)
    suspend fun query(
        actionTypes: List<String>,
        actionTypesEmpty: Boolean,
        mediaTypes: List<String>,
        mediaTypesEmpty: Boolean,
        startTime: Long,
        endTime: Long,
        titleQuery: String?,
        ascending: Boolean,
        limit: Int,
        offset: Int
    ): List<MarkActionRecordEntity>

    @Query("SELECT COUNT(*) FROM mark_action_record")
    suspend fun count(): Int

    /** 超上限时删最旧的 N 条 */
    @Query("DELETE FROM mark_action_record WHERE id IN (SELECT id FROM mark_action_record ORDER BY actedAt ASC LIMIT :n)")
    suspend fun deleteOldest(n: Int)

    @Query("DELETE FROM mark_action_record")
    suspend fun deleteAll()
}
```

### 2.4 AppDatabase 变更

`AppDatabase.kt`：
- `version = 10` → `version = 11`
- `entities` 数组追加 `MarkActionRecordEntity::class`
- `daos` 数组追加 `MarkActionRecordDao::class`

### 2.5 保留策略

- 永久保留，无时间过期
- 上限 10000 条：每次 `insert` 后检查 `count()`，超限则 `deleteOldest(超限数量)`
- 退出登录**不清空**（个人操作历史）
- 不提供"清空标记记录"入口

---

## 3. 写入时机

### 3.1 修改 `TraktRepository`

在以下方法的**API 成功分支后**（缓存更新完成后）异步插入一条记录：

| 方法 | 行号 | 写入类型 | episodeInfo |
|---|---|---|---|
| `addToWatchlist` | ~957 | `ADD_WATCHLIST` | null |
| `removeFromWatchlist` | ~982 | `REMOVE_WATCHLIST` | null |
| `removeWatched` | ~823 | `UNMARK_WATCHED` | null（整剧） |
| `unmarkEpisodeWatched` | ~774 | `UNMARK_WATCHED` | "S0xE0y"（需从 API 响应或调用方传入季集信息） |

**不写入**：`markAsWatched` / `markEpisodeWatched` / `markEpisodesWatched`（已看走 Trakt 历史）。

### 3.2 写入位置选择

写入逻辑放在 **Repository 层**（不在 ViewModel），保证所有调用方（`DetailViewModel` / `WatchlistViewModel` 批量操作 / 未来扩展）都自动记录。

### 3.3 影视信息快照来源

写入时从以下渠道获取快照字段：
- `traktId / tmdbId / mediaType`：方法入参已有
- `title / displayTitle / posterUrl / year`：优先从 `MediaDetailEntity` 缓存查；未命中则从 `MediaItemEntity` 查；都未命中则字段写空字符串/null（宁可列表项缺图，不可阻塞写入）

### 3.4 批量操作处理

`WatchlistViewModel.batchRemoveFromWatchlist` / `batchRemoveFromHistory` 批量调用时，每条成功都插入一条记录（N 条操作 = N 条流水），符合"操作流水"语义。

### 3.5 `unmarkEpisodeWatched` 的 episodeInfo 填充

当前 `unmarkEpisodeWatched(episodeTraktId: Int)` 方法签名不含季集信息。需要：
- 方案 A：在方法内调 Trakt `/sync/history` 反查该 episode 的 season/number（一次额外请求）
- 方案 B：修改方法签名，由调用方传入 `season` / `episode` 参数

**采用方案 B**：调用方（`DetailViewModel`）通常已有季集信息，避免额外网络请求。方法签名改为：
```kotlin
suspend fun unmarkEpisodeWatched(
    episodeTraktId: Int,
    season: Int = 0,
    episode: Int = 0,
    showTraktId: Int = 0,
    showTmdbId: Int = 0,
    showTitle: String = ""
): Result<Unit>
```
`season/episode/showTraktId/showTmdbId/showTitle` 默认值 0/空，仅用于写流水记录。调用方不传时记录的 `episodeInfo` 为 "S0E0"、`traktId` 为 0，列表项会显示异常但不崩溃。

---

## 4. Trakt /sync/history 拉取

### 4.1 API 调用

`TraktRepository` 新增方法：

```kotlin
suspend fun fetchWatchHistory(
    page: Int,
    limit: Int = 100
): Result<WatchHistoryPage>
```

返回结构：
```kotlin
data class WatchHistoryPage(
    val items: List<WatchHistoryItem>,
    val currentPage: Int,
    val totalPages: Int,
    val totalCount: Int
)

data class WatchHistoryItem(
    val traktId: Int,          // 影视 ID（movie→movie.traktId，episode→show.ids.trakt）
    val tmdbId: Int,
    val imdbId: String,
    val mediaType: String,     // "movie" / "show"
    val title: String,
    val posterUrl: String?,    // 需另查 TMDB 详情补全
    val year: Int?,
    val watchedAt: Long,       // watched_at 时间戳
    val episodeInfo: String?   // type=episode 时填 "S0xE0y"
)
```

### 4.2 映射规则

Trakt `/sync/history` 返回的每条记录：
- `type=movie`：`movie.ids.trakt / movie.ids.tmdb / movie.title / movie.year` → 一条已看记录，`episodeInfo = null`
- `type=episode`：`show.ids.trakt / show.ids.tmdb / show.title / episode.season / episode.number` → 一条已看记录，`episodeInfo = "S{season}E{number}"`，`title` 取 `show.title`

### 4.3 海报补全

Trakt `/sync/history` 响应不含 `poster_path`。补全策略：
- 优先从 `movieDetailCache / tvDetailCache` 取（TMDB 详情永久缓存）
- 未命中则记录的 `posterUrl = null`，列表项显示占位图
- 不为补海报额外请求 TMDB API（遵循"尽量减少网络请求"原则）

### 4.4 缓存

- 进程内 `TtlCache<String, WatchHistoryPage>`，key = `"watch_history_page_$page"`，TTL = 1 小时
- 下拉刷新时清空缓存重新拉取首页
- 切换 Tab 到"已看"时，若缓存未过期直接用缓存，否则拉取首页

### 4.5 分页加载

- 首次加载第 1 页（100 条）
- 列表滚动到底部前 20 条时，自动加载下一页
- 加载中显示底部 loading 指示器
- 加载失败显示"加载失败，点击重试"

---

## 5. UI 设计

### 5.1 新增文件

```
app/src/main/java/com/tracktosearch/ui/screen/markrecord/
├── MarkRecordScreen.kt          # 主界面
├── MarkRecordViewModel.kt       # ViewModel
└── MarkRecordComponents.kt      # 列表项、筛选弹窗等组件
```

### 5.2 页面布局

```
┌─────────────────────────────────┐
│ ←  标记记录              [筛选]  │  ← 毛玻璃 TopBar
├─────────────────────────────────┤
│ [全部] [想看] [已看] [移除]      │  ← ScrollableTabRow
├─────────────────────────────────┤
│ 🔍 搜索标题...                   │  ← BasicTextField
├─────────────────────────────────┤
│ ┌──┐ 标题A (2024)               │
│ │  │ [想看] 3天前      [当前:想看]│  ← 列表项
│ └──┘                            │
│ ┌──┐ 标题B (2023)               │
│ │  │ [已看] 5天前      [当前:未看]│  ← 矛盾时淡化
│ └──┘                            │
│            ···                  │
│         加载更多...              │
└─────────────────────────────────┘
```

### 5.3 列表项 `MarkRecordItem`

- 布局：`Row`，左侧 48×72dp 小海报，右侧信息区
- 信息区从上到下：
  - 标题 + 年份
  - 操作类型 chip（颜色：想看=蓝 / 已看=绿 / 移除=红）+ 相对时间（"3天前"）
  - 当前状态徽标（见 5.5）
- `episodeInfo` 非空时在标题下方显示 "S01E03"
- `Modifier.clickable` 跳详情页
- `Modifier.combinedClickable` 长按可选（暂不做多选，YAGNI）

### 5.4 筛选弹窗

`ModalBottomSheet`，参考 `DoubanFailuresScreen`：

- **媒体类型**（FilterChip 多选）：全部 / 电影 / 剧集
- **日期范围**：
  - 预设单选：近 7 天 / 近 30 天 / 全部
  - 自定义：`RangeSlider` 选时间区间（仅在预设选"自定义"时显示）
- **排序方式**（`SegmentedButton`）：时间倒序（默认）/ 时间正序

底部"重置" + "确定"按钮。

### 5.5 当前状态徽标

从 `WatchlistWatchedIds` 实时计算记录对应影视的当前状态：

| 记录操作 | 当前状态 | 显示 |
|---|---|---|
| 加想看 | 仍在想看 | 绿色徽标 "当前:想看" |
| 加想看 | 已不在想看 | 灰色徽标 "已变更"，整行 `Modifier.alpha(0.6f)` 淡化 |
| 移除想看 | 不在想看 | 灰色徽标 "当前:无标记" |
| 移除想看 | 又加回了想看 | 绿色徽标 "当前:想看" |
| 取消已看 | 未看 | 灰色徽标 "当前:未看" |
| 取消已看 | 又标记已看 | 绿色徽标 "当前:已看" |
| 已看（Trakt） | 仍已看 | 绿色徽标 "当前:已看" |
| 已看（Trakt） | 已取消 | 灰色徽标 "已变更"，整行 `Modifier.alpha(0.6f)` 淡化 |

**计算方式**：列表数据加载后，批量从 `TraktRepository.watchlistWatchedIds` 查询每条记录的当前状态，生成 `Map<traktId, CurrentStatus>`，UI 渲染时查 Map。

### 5.6 空状态

- 未登录：入口已隐藏，不会进入此页
- 已登录但无数据：
  - 全部 Tab：`Icons.Rounded.Inbox` + "暂无标记记录"
  - 想看/移除 Tab："暂无想看操作记录" / "暂无移除操作记录"
  - 已看 Tab："暂无已看记录" 或 "Trakt 历史加载失败，点击重试"

### 5.7 加载与错误

- 首次加载：全屏 `CircularProgressIndicator`
- 分页加载：列表底部 `CircularProgressIndicator`（小）
- 加载失败：`Icons.Rounded.ErrorOutline` + "加载失败" + "重试"按钮
- 网络错误信息映射为中文提示（复用现有 `mapNetworkError` 逻辑）

---

## 6. ViewModel

### 6.1 `MarkRecordViewModel`

```kotlin
@HiltViewModel
class MarkRecordViewModel @Inject constructor(
    private val markActionRecordDao: MarkActionRecordDao,
    private val traktRepository: TraktRepository
) : ViewModel() {

    data class MarkRecordUiState(
        val isLoading: Boolean = false,
        val items: List<MarkRecordItem> = emptyList(),
        val currentTab: MarkRecordTab = MarkRecordTab.ALL,
        val searchQuery: String = "",
        val filterMediaTypes: Set<String> = emptySet(),
        val filterDatePreset: DatePreset = DatePreset.ALL,
        val filterDateRange: Pair<Long, Long>? = null,
        val sortAscending: Boolean = false,
        val currentPage: Int = 0,
        val hasMore: Boolean = true,
        val isLoadingMore: Boolean = false,
        val error: String? = null,
        val currentStatusMap: Map<Int, CurrentMarkStatus> = emptyMap()
    )

    val uiState: StateFlow<MarkRecordUiState>

    fun switchTab(tab: MarkRecordTab)
    fun updateSearchQuery(query: String)
    fun updateFilter(mediaTypes: Set<String>, datePreset: DatePreset, dateRange: Pair<Long, Long>?, ascending: Boolean)
    fun loadNextPage()
    fun refresh()
    fun retry()
}

enum class MarkRecordTab(val actionTypes: List<String>?) {
    ALL(null),
    WATCHLIST(listOf("ADD_WATCHLIST")),
    WATCHED(null),  // 已看走 Trakt API，不查本地表
    REMOVED(listOf("REMOVE_WATCHLIST", "UNMARK_WATCHED"))
}

enum class DatePreset { SEVEN_DAYS, THIRTY_DAYS, CUSTOM, ALL }

enum class CurrentMarkStatus { IN_WATCHLIST, WATCHED, NONE }
```

### 6.2 数据加载逻辑

- Tab = WATCHLIST / REMOVED：查自建表（`MarkActionRecordDao.query`），支持无限滚动分页
- Tab = WATCHED：调 `traktRepository.fetchWatchHistory(page)`，支持无限滚动分页
- Tab = ALL：合并自建表 + Trakt 历史，按 `actedAt/watchedAt` 内存合并排序
  - ALL Tab 的 Trakt 历史只拉第 1 页（100 条最新已看），自建表也只查最近 100 条，合并后按时间倒序展示
  - ALL Tab **不支持无限滚动**（仅展示最近约 200 条），用户要看完整已看历史请切到"已看"Tab

### 6.3 分页

- 自建表：`LIMIT 50 OFFSET N`
- Trakt 历史：每页 100 条
- ALL Tab 不支持无限滚动（仅展示最近 100 条），想看/移除/已看 Tab 支持无限滚动

---

## 7. 导航

### 7.1 路由

`AppNavigation.kt` 的 `Routes` 对象新增：
```kotlin
const val MARK_RECORDS = "markRecords"
```

`NavHost` 新增：
```kotlin
composable(Routes.MARK_RECORDS) {
    MarkRecordScreen(
        onBack = { navController.popBackStack() },
        onMovieClick = { traktId, tmdbId, title, imdbId, traktRating ->
            navController.navigate(Routes.detailRoute("movie", traktId, tmdbId, title, imdbId, traktRating))
        },
        onShowClick = { traktId, tmdbId, title, imdbId, traktRating ->
            navController.navigate(Routes.detailRoute("show", traktId, tmdbId, title, imdbId, traktRating))
        }
    )
}
```

### 7.2 入口

`SettingsScreen.kt`：
- `statistics_entry` 之后新增 `mark_records_entry`，独占整行卡片，与 `StatisticsCard` 风格一致
- `SettingsScreen` 函数签名新增 `onMarkRecordsClick: () -> Unit` 参数
- 仅 `isLoggedIn = true` 时渲染
- `AppNavigation.kt` 调用处补 `onMarkRecordsClick = { navController.navigate(Routes.MARK_RECORDS) }`

### 7.3 详情页跳转

列表项点击调 `onMovieClick` / `onShowClick`，复用 `Routes.detailRoute(...)`。

**不回传 `watchlist_changed / watched_changed`**：标记记录页不需要触发列表刷新。详情页的返回值由 `MarkRecordScreen` 忽略即可。

---

## 8. 国际化

`strings.xml` 同步添加到 4 个 values 目录：

- `values/`（英文）
- `values-zh/`（中文）
- `values-ja/`（日文）
- `values-ko/`（韩文）

**新增字符串**（示例 key）：
- `mark_records_title` — "标记记录"
- `mark_records_tab_all` — "全部"
- `mark_records_tab_watchlist` — "想看"
- `mark_records_tab_watched` — "已看"
- `mark_records_tab_removed` — "移除"
- `mark_records_search_hint` — "搜索标题..."
- `mark_records_filter_media_type` — "媒体类型"
- `mark_records_filter_date_range` — "日期范围"
- `mark_records_filter_sort` — "排序方式"
- `mark_records_sort_desc` — "最新优先"
- `mark_records_sort_asc` — "最早优先"
- `mark_records_date_preset_7d` — "近 7 天"
- `mark_records_date_preset_30d` — "近 30 天"
- `mark_records_date_preset_all` — "全部"
- `mark_records_date_preset_custom` — "自定义"
- `mark_records_empty_all` — "暂无标记记录"
- `mark_records_empty_watchlist` — "暂无想看操作记录"
- `mark_records_empty_watched` — "暂无已看记录"
- `mark_records_empty_removed` — "暂无移除操作记录"
- `mark_records_load_failed` — "加载失败"
- `mark_records_retry` — "重试"
- `mark_records_current_in_watchlist` — "当前:想看"
- `mark_records_current_watched` — "当前:已看"
- `mark_records_current_none` — "当前:无标记"
- `mark_records_current_changed` — "已变更"
- `mark_records_action_add_watchlist` — "加想看"
- `mark_records_action_remove_watchlist` — "移除想看"
- `mark_records_action_unmark_watched` — "取消已看"
- `mark_records_action_watched` — "已看"
- `mark_records_settings_entry` — "标记记录"
- `mark_records_settings_entry_desc` — "查看你的想看/已看/移除操作历史"
- `mark_records_reset` — "重置"
- `mark_records_confirm` — "确定"

帮助页相关说明同步更新（若帮助页有"我的数据"章节）。

---

## 9. 帮助页更新

帮助页（`HelpScreen.kt`）相关章节新增「标记记录」说明：
- 入口位置：设置页顶部"标记记录"卡片
- 数据来源说明：已看记录来自 Trakt 历史，想看/移除记录为 App 内操作流水
- 历史数据说明：功能上线前的想看/移除操作无法追溯（Trakt 不留存）
- 已看记录的完整性：Trakt `/sync/history` 保留全量观看历史

---

## 10. 测试

### 10.1 自建表 DAO 测试

- `insert` + `query` 基本读写
- 按 `actionType` / `mediaType` / 时间范围 / 标题模糊匹配筛选
- 分页 `LIMIT/OFFSET`
- 排序正序/倒序
- `count` / `deleteOldest` / `deleteAll`

### 10.2 ViewModel 测试

- Tab 切换后查询参数正确
- 筛选条件变更后重新查询
- 分页加载下一页
- Trakt 历史拉取成功/失败的状态更新
- 当前状态徽标的 Map 生成逻辑

### 10.3 Repository 写入测试

- `addToWatchlist` 成功后流水表多一条 `ADD_WATCHLIST`
- `removeFromWatchlist` 成功后流水表多一条 `REMOVE_WATCHLIST`
- `removeWatched` 成功后流水表多一条 `UNMARK_WATCHED`（episodeInfo=null）
- `unmarkEpisodeWatched` 成功后流水表多一条 `UNMARK_WATCHED`（episodeInfo="S0xE0y"）
- API 失败时不写入
- 超过 10000 条上限时自动删最旧

### 10.4 Trakt /sync/history 测试

- `fetchWatchHistory` 成功返回数据
- movie / episode 映射正确
- 分页参数正确
- 缓存命中/未命中

---

## 11. 实现顺序

1. 数据层：`MarkActionRecordEntity` + `MarkActionRecordDao` + `AppDatabase` 升级
2. Repository：`TraktRepository` 写入逻辑 + `fetchWatchHistory` 方法
3. ViewModel：`MarkRecordViewModel` + 状态管理 + 分页
4. UI：`MarkRecordScreen` + 组件 + 筛选弹窗 + 空状态
5. 导航：路由 + 入口
6. 国际化：4 语言 strings.xml
7. 帮助页更新
8. 测试：DAO + ViewModel + Repository 写入 + Trakt 历史
9. 构建 debug 验证

---

## 12. 风险与注意事项

1. **`unmarkEpisodeWatched` 方法签名变更**：新增 `season/episode/showTraktId/showTmdbId/showTitle` 参数，调用方仅 `DetailViewModel` 一处（已 grep 确认），需同步更新
2. **Trakt /sync/history 分页性能**：用户历史可能上万条，首屏只加载第 1 页（100 条），滚动加载后续页，避免一次性拉取
3. **ALL Tab 合并复杂度**：自建表 + Trakt 历史内存合并，需处理时间排序、去重（理论上不会重复，因为已看不写自建表）
4. **当前状态徽标的批量查询**：每页 50 条记录要批量查 `WatchlistWatchedIds`，确保 O(1) 查询（WatchlistWatchedIds 是 Set 结构）
5. **数据库迁移**：version 10→11，新增表，无需数据迁移（新表为空）
