# 豆瓣同步失败项查看页设计规格

> **日期**: 2026-07-05
> **主题**: 为豆瓣同步失败项构建独立的影视查看页与豆瓣条目详情页
> **状态**: 已批准,待实现

## 1. 背景与目标

豆瓣同步失败项(Trakt 未收录 / 无 IMDb ID / 详情页爬取失败等)的影视其实在豆瓣真实存在。当前这些失败项只能在「重试对话框」中查看列表或导出 JSON,无法浏览其海报、详情,也无法在 App 内查找资源。

**目标**: 构建一个独立的失败项查看页(参照 WatchlistScreen 风格)+ 豆瓣条目详情页(精简版 DetailScreen),让用户能像浏览想看列表一样浏览失败项,并为每条失败项提供资源搜索入口。

## 2. 架构概览

```
设置页「查看同步失败项」入口
    ↓
DoubanFailuresScreen (失败项查看页)
  ├─ 胶囊切换条(想看/已看)— 复用 Watchlist 样式
  ├─ PrimaryTabRow(电影/电视剧/未分类)— 三 Tab
  └─ LazyVerticalGrid 3列卡片
        ↓ 点击卡片
  DoubanItemDetailScreen (豆瓣条目详情页)
    ├─ 头部(海报 + 标题 + 子标题(可编辑) + 评分 + 短评)
    ├─ 提示横幅「Trakt 未收录」+ 失败原因
    ├─ PrimaryTabRow
    │   ├─ Tab 0: 资源搜索(默认)
    │   │   ├─ 关键词栏(显示 title / subtitle)
    │   │   ├─ 「同时用子标题搜索」开关(若 subtitle 非空)
    │   │   └─ 搜索结果列表(复用 DetailScreen 资源结果项样式)
    │   └─ Tab 1: 详情信息
    │       ├─ 操作按钮区(打开豆瓣 / 重新尝试同步 / 删除)
    │       └─ 失败元信息(失败时间 / 重试次数 / 豆瓣 ID)
    └─ 单条重试启动后 → DoubanSyncDialog
```

**核心原则**:
- 失败项查看页纯本地数据,无网络请求(除资源搜索)
- 豆瓣条目详情页不依赖 Trakt/TMDB API
- 不破坏现有失败项数据流(向后兼容)
- 用户手动标注类型,零流量消耗

## 3. 数据层

### 3.1 字段扩展

**`DoubanSyncFailureEntity`**(Room Entity)新增两个可空字段:

```kotlin
val mediaType: String? = null,   // "movie" / "show" / null(未分类)
val subtitle: String? = null    // 用户手动编辑的子标题/外文标题/别名
```

**`DoubanSyncFailure`** 数据类同步新增相同字段,`fromEntity()` / `toEntity()` 同步更新。旧数据两个字段为 null,自动归入「未分类」Tab,subtitle 为空。

### 3.2 DAO 新增方法

**`DoubanSyncFailureDao`** 新增:

```kotlin
@Query("UPDATE douban_sync_failures SET mediaType = :mediaType WHERE doubanId = :doubanId")
suspend fun updateMediaType(doubanId: String, mediaType: String?)

@Query("UPDATE douban_sync_failures SET subtitle = :subtitle WHERE doubanId = :doubanId")
suspend fun updateSubtitle(doubanId: String, subtitle: String?)

@Query("SELECT * FROM douban_sync_failures WHERE doubanId = :doubanId")
suspend fun getById(doubanId: String): DoubanSyncFailureEntity?
```

### 3.3 Repository 扩展

**`DoubanRetryManager`** 新增方法(不新建 Repository,复用现有依赖):

```kotlin
suspend fun getAllFailures(): List<DoubanSyncFailure>
suspend fun getFailure(doubanId: String): DoubanSyncFailure?
suspend fun updateMediaType(doubanId: String, mediaType: String?)
suspend fun updateSubtitle(doubanId: String, subtitle: String?)
suspend fun deleteFailure(doubanId: String)   // 单条删除,不同于 clearAll
```

## 4. 导航层

**`AppNavigation.kt`** `Routes` object 新增:

```kotlin
const val DOUBAN_FAILURES = "doubanFailures"
const val DOUBAN_ITEM_DETAIL = "doubanItemDetail/{doubanId}"

fun doubanItemDetailRoute(doubanId: String): String = "doubanItemDetail/$doubanId"
```

**设计要点**:
- `DOUBAN_FAILURES` 无参数 — ViewModel 从 Room 加载全部失败项
- `DOUBAN_ITEM_DETAIL` 仅传 `doubanId` — ViewModel 按 doubanId 查询单条(避免 URL 过长,避免数据不一致)

## 5. UI 层 - 失败项查看页

### 5.1 DoubanFailuresScreen

**文件**: `app/src/main/java/com/tracktosearch/ui/screen/douban/DoubanFailuresScreen.kt`

**参数**:
```kotlin
@Composable
fun DoubanFailuresScreen(
    onBack: () -> Unit,
    onItemClick: (doubanId: String) -> Unit,
    viewModel: DoubanFailuresViewModel = hiltViewModel()
)
```

**UI 结构**:
```
TopAppBar(标题="同步失败项", 返回箭头, 右上角「清空」按钮)
├─ 胶囊切换条(想看/已看)— 复用 Watchlist 自定义胶囊样式
├─ PrimaryTabRow(电影/电视剧/未分类)— 数量徽标
└─ LazyVerticalGrid(columns = Fixed(3))
     └─ 失败项卡片(复用发现页 MovieCard 样式)
         ├─ 海报(豆瓣 posterUrl 直接用)
         ├─ 标题
         ├─ 评分角标(5分制转★显示)
         └─ 失败原因图标(右下角小角标,区分可恢复/不可恢复)
```

**卡片交互**:
- 点击:跳转 `DoubanItemDetailScreen`
- 长按:弹底部菜单 `标注为电影 / 标注为电视剧 / 清除类型标注 / 删除此条目`

**空状态**:
- 「想看」Tab 无失败项 → 「想看列表同步全部成功」
- 「已看」Tab 无失败项 → 「已看列表同步全部成功」
- 全部空 → 居中显示「没有同步失败项」+ 返回按钮

### 5.2 DoubanFailuresViewModel

```kotlin
@HiltViewModel
class DoubanFailuresViewModel @Inject constructor(
    private val doubanRetryManager: DoubanRetryManager
) : ViewModel() {
    val uiState: StateFlow<DoubanFailuresUiState>
    
    fun loadFailures()                                    // Room 加载全部失败项
    fun setMediaType(doubanId: String, mediaType: String?) // 持久化标注
    fun deleteFailure(doubanId: String)                   // 删除单条
    fun clearAllFailures()                                // 清空全部
}
```

**数据流**: Room 一次性加载 → 内存按 `status × mediaType` 分组 → UI 通过 `selectedMode × selectedTab` 索引。无网络请求。

**生命周期**: `onResume` 时重新调用 `loadFailures()`(同步进度对话框运行时可能并发修改失败项表)。

## 6. UI 层 - 豆瓣条目详情页

### 6.1 DoubanItemDetailScreen

**文件**: `app/src/main/java/com/tracktosearch/ui/screen/douban/DoubanItemDetailScreen.kt`

**参数**:
```kotlin
@Composable
fun DoubanItemDetailScreen(
    doubanId: String,
    onBack: () -> Unit,
    onRetryStarted: () -> Unit,
    viewModel: DoubanItemDetailViewModel = hiltViewModel()
)
```

**UI 结构**:
```
TopAppBar(返回箭头, 标题=条目标题)
└─ Column
    ├─ 头部区域(参照 DetailHeaderContent 精简版)
    │   ├─ 海报(2:3, 豆瓣 posterUrl, 支持点击放大)
    │   ├─ 标题 + 子标题(可空,点击编辑)
    │   ├─ 评分(5★显示)
    │   ├─ 标记时间 + 状态(想看/已看)
    │   └─ 短评(若有,引号包裹)
    ├─ 提示横幅(errorContainer 颜色)
    │   「此条目 Trakt 未收录,无法同步。失败原因:[原因]」
    ├─ PrimaryTabRow
    │   ├─ Tab 0: 资源搜索(默认选中)
    │   └─ Tab 1: 详情信息
    └─ TabContent
        ├─ Tab 0 资源搜索:
        │   ├─ 关键词栏(显示当前搜索词: title / subtitle)
        │   ├─ 「同时用子标题搜索」开关(若 subtitle 非空,默认开)
        │   └─ 搜索结果列表(复用 DetailScreen 资源结果项样式)
        └─ Tab 1 详情信息:
            ├─ 操作按钮区
            │   ├─ 「打开豆瓣页面」(Intent ACTION_VIEW → doubanUrl)
            │   ├─ 「重新尝试同步」(仅 failureReason.recoverable 时显示)
            │   └─ 「删除此失败记录」(次要按钮,删除后 onBack)
            └─ 失败元信息(失败时间 / 重试次数 / 豆瓣 ID)
```

### 6.2 DoubanItemDetailViewModel

```kotlin
@HiltViewModel
class DoubanItemDetailViewModel @Inject constructor(
    private val doubanRetryManager: DoubanRetryManager,
    private val doubanSyncManager: DoubanSyncManager,
    private val resourceRepository: ResourceRepository
) : ViewModel() {
    val uiState: StateFlow<DoubanItemDetailUiState>
    
    fun loadFailure(doubanId: String)
    fun updateSubtitle(doubanId: String, subtitle: String?)
    fun searchResources()                  // 用 title + 可选 subtitle 调 resourceRepository
    fun retrySingle()                      // 启动单条重试 → DoubanSyncDialog
    fun deleteFailure()                    // 删除此条后 onBack
}
```

**资源搜索逻辑**:
```kotlin
fun searchResources() {
    val keywords = mutableListOf(failure.title)
    failure.subtitle?.takeIf { it.isNotBlank() && uiState.searchWithSubtitle }?.let {
        keywords.add(it)
    }
    // 调 resourceRepository.searchAll(keywords),中英文并行
}
```

**子标题编辑**:
- `subtitle == null` 时头部显示「点击添加子标题」提示
- 点击弹 `AlertDialog` 含 `TextField`
- 确认后 `updateSubtitle(doubanId, newText)`,自动触发资源搜索刷新

**重试逻辑**:
- 调 `doubanSyncManager.startRetry(listOf(failure), setOf(failure.failureReason))`
- 启动后 `onRetryStarted()` 跳转 `DoubanSyncDialog`
- 成功后 Room 中该条删除,返回时若 `failure == null` 自动 `onBack()`

## 7. 设置页入口

在 `SettingsScreen.kt` 现有「重新同步豆瓣」「重试上次失败项」之后新增第三个 `SettingsItem`:

```kotlin
SettingsItem(
    icon = Icons.Default.BrokenImage,
    title = stringResource(R.string.settings_douban_view_failures),
    subtitle = stringResource(R.string.settings_douban_view_failures_desc, doubanRetryState.totalFailures),
    onClick = { onDoubanFailuresClick() }
)
```

**显示条件**: `doubanRetryState.hasFailures == true`(无失败项时不显示,避免混淆)。

## 8. 国际化

新增 string 资源 key(4 语言: values / values-zh / values-ja / values-ko):

**设置页入口**:
- `settings_douban_view_failures` — "查看同步失败项"
- `settings_douban_view_failures_desc` — "%1$d 项豆瓣条目同步失败"

**失败项查看页**:
- `screen_douban_failures_title` — "同步失败项"
- `screen_douban_failures_empty_wish` — "想看列表同步全部成功"
- `screen_douban_failures_empty_collect` — "已看列表同步全部成功"
- `screen_douban_failures_empty_all` — "没有同步失败项"
- `screen_douban_failures_clear_all` — "清空全部"
- `screen_douban_failures_clear_all_confirm` — "确定清空全部失败项?此操作不可撤销"
- `screen_douban_failures_mark_as_movie` — "标注为电影"
- `screen_douban_failures_mark_as_show` — "标注为电视剧"
- `screen_douban_failures_clear_mark` — "清除类型标注"
- `screen_douban_failures_delete_item` — "删除此条目"

**豆瓣条目详情页**:
- `screen_douban_item_detail_not_synced` — "此条目 Trakt 未收录,无法同步"
- `screen_douban_item_detail_failure_reason` — "失败原因:%1$s"
- `screen_douban_item_detail_open_douban` — "打开豆瓣页面"
- `screen_douban_item_detail_retry` — "重新尝试同步"
- `screen_douban_item_detail_delete` — "删除此失败记录"
- `screen_douban_item_detail_delete_confirm` — "确定删除此失败记录?"
- `screen_douban_item_detail_subtitle_hint` — "点击添加子标题(可用于资源搜索)"
- `screen_douban_item_detail_subtitle_edit` — "编辑子标题"
- `screen_douban_item_detail_search_with_subtitle` — "同时用子标题搜索"
- `screen_douban_item_detail_tab_resources` — "资源搜索"
- `screen_douban_item_detail_tab_info` — "详情信息"
- `screen_douban_item_detail_no_resources` — "未找到资源,试试子标题或更换关键词"

## 9. 错误处理与边界情况

| 场景 | 处理策略 |
|------|---------|
| Room 加载失败 | 显示错误占位 + 重试按钮 |
| 单条失败项被并发删除 | `failure == null` 时自动 `onBack()`,Toast「该失败项已不存在」 |
| `updateMediaType` / `updateSubtitle` 写入失败 | Toast「保存失败」+ UI 回滚 |
| 资源搜索无结果 | 显示空状态「未找到资源,试试子标题或更换关键词」 |
| 资源搜索网络失败 | 显示错误 + 重试按钮 |
| 单条重试启动失败(`isRunning()` 返回 false) | Toast「同步正在进行中,请稍后」 |
| 清空全部时 Room 异常 | Toast「清空失败」+ 状态不变 |
| subtitle 为纯空格 | 入库前 `trim()`,空串转 null |
| 大量失败项(>200) | LazyVerticalGrid 懒加载,无性能问题 |
| 跨页面状态同步 | `onResume` 时重新 `loadFailures()` |

## 10. 不在本次范围内(YAGNI)

- 失败项的批量操作(多选删除/批量重试)— 长按仅弹单条菜单
- 失败项的排序/筛选(按失败时间、按失败原因)
- 失败项的导出功能 — 已在「重试对话框」中存在,不重复
- 后台自动爬取补全 mediaType — 用户选择手动标注
- 资源搜索的筛选器(源/网盘类型)— 沿用 DetailScreen 的筛选器实现成本高,本次不做

## 11. 文件清单

**新建文件**:
- `app/src/main/java/com/tracktosearch/ui/screen/douban/DoubanFailuresScreen.kt`
- `app/src/main/java/com/tracktosearch/ui/screen/douban/DoubanItemDetailScreen.kt`

**修改文件**:
- `app/src/main/java/com/tracktosearch/data/local/db/DoubanEntities.kt` — Entity 字段 + DAO 方法
- `app/src/main/java/com/tracktosearch/data/repository/DoubanSyncFailure.kt` — 数据类字段
- `app/src/main/java/com/tracktosearch/data/repository/DoubanRetryManager.kt` — 新增方法
- `app/src/main/java/com/tracktosearch/ui/navigation/AppNavigation.kt` — 路由注册
- `app/src/main/java/com/tracktosearch/ui/screen/settings/SettingsScreen.kt` — 入口
- `app/src/main/res/values/strings.xml` + `values-zh` + `values-ja` + `values-ko` — 国际化
