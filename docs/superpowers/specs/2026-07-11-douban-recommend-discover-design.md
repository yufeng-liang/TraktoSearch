# 豆瓣「猜你喜欢」发现页栏目设计

## 背景与目标

当前发现页有 12 个栏目，其中 `trakt-recommendations` 是基于 Trakt 的「为你推荐」。用户希望新增基于豆瓣个性化推荐的「猜你喜欢」栏目，作为发现页第一个栏目，支持电影和电视剧。

根据 `douban-movie-api/ENDPOINTS_RESEARCH.md` 实测结论：
- 豆瓣 `m.douban.com/rexxar/api/v2/{movie|tv}/recommend` 端点**带登录 cookie** 时返回真·个性化单剧推荐
- 每条推荐带 `alg_strategy`（`user_movie`/`user_tv`）和 `reason_data`（推荐理由标签）
- **免登录**时返回通用热门片单（非单剧），体验差异大

本设计目标：
1. 在发现页新增「猜你喜欢」栏目，默认排序第一
2. 基于豆瓣登录态的个性化推荐（电影 + 电视剧 Tab 切换）
3. 卡片展示推荐理由标签
4. 未登录时显示引导登录卡片
5. 6 小时持久化缓存，按用户隔离，跨 App 重启复用

## 不在范围内

- `uncollect` 过滤（只推未看过的）——留作后续优化，需实测验证 API 参数
- 「全部」弹窗分页浏览——推荐只有约 8 条，无需分页
- 豆瓣片单/豆列推荐——仅展示单剧（`type == "subject"`），过滤片单
- 推荐算法本地优化——完全依赖豆瓣 API 的个性化结果

## 架构

### 数据流

```
用户打开发现页
    ↓
DiscoverViewModel.loadDoubanRecommend()
    ↓
检查 DoubanAuthStorage.isLoggedIn
    ├── 未登录 → UiState.NotLoggedIn（显示引导卡片）
    └── 已登录 → 取 cookie + userId
                    ↓
        doubanRecommendCache.getOrAwait("recommend_{type}_{userId}")
            ├── 缓存命中 → UiState.Success(items)
            └── 缓存未命中 → DoubanRepository.fetchRecommend(type, cookie)
                                ├── 成功 → 过滤 subject → 写缓存 → UiState.Success
                                └── 失败 → UiState.Error
```

### Tab 切换流程

```
用户点击「电影」/「电视剧」Tab
    ↓
switchRecommendTab(tab)
    ↓
检查 doubanRecommendCache["recommend_{tab}_{userId}"]
    ├── 命中 → 直接切换显示
    └── 未命中 → UiState.Loading → fetchRecommend → Success
```

## 组件设计

### 1. 数据层：DTO

新文件 `app/src/main/java/com/tracktosearch/data/remote/douban/dto/DoubanRecommendDtos.kt`：

```kotlin
@Serializable
data class DoubanRecommendResponse(
    val items: List<DoubanRecommendItem> = emptyList(),
    val total: Int = 0
)

@Serializable
data class DoubanRecommendItem(
    val type: String,           // "subject" | "playlist"
    val id: String,             // doubanId
    val title: String,
    val cover: String? = null,  // 豆瓣海报 URL（doubanio.com）
    val rating: DoubanRecommendRating? = null,
    val url: String? = null,
    val alg_json: AlgJson? = null
) {
    val isSubject: Boolean get() = type == "subject"
    val reasonTags: List<String>? get() = alg_json?.reason_data?.takeIf { it.isNotEmpty() }
}

@Serializable
data class DoubanRecommendRating(
    val value: String? = null,   // "9.4"
    val count: Int? = null
)

@Serializable
data class AlgJson(
    val alg_strategy: String? = null,  // "user_movie" / "user_tv" / "hot"
    val reason_data: List<String>? = null  // ["日本", "人生"]
)
```

### 2. 数据层：Repository 方法

在 `DoubanRepository.kt` 新增正式方法（复用 `fetchRecommendForTest` 的请求逻辑）：

```kotlin
suspend fun fetchRecommend(type: String, cookie: String): List<DoubanRecommendItem> = withContext(Dispatchers.IO) {
    val url = "https://m.douban.com/rexxar/api/v2/$type/recommend"
    val request = Request.Builder()
        .url(url)
        .header("User-Agent", mobileUa)
        .header("Cookie", cookie)
        .header("Referer", "https://m.douban.com/")
        .header("X-Requested-With", "XMLHttpRequest")
        .header("Accept", "application/json, text/javascript, */*; q=0.01")
        .build()
    client.newCall(request).execute().use { response ->
        if (!response.isSuccessful) {
            throw IOException("豆瓣推荐请求失败: ${response.code}")
        }
        val body = response.body?.string() ?: throw IOException("空响应")
        val parsed = json.decodeFromString<DoubanRecommendResponse>(body)
        // 仅保留单剧推荐，过滤片单/豆列
        parsed.items.filter { it.isSubject }
    }
}
```

错误处理：
- 401/403 → 抛出 `DoubanCookieExpiredException`，ViewModel 捕获后清除登录态、显示引导卡片
- 网络失败 → 抛出 IOException，ViewModel 显示错误重试行

### 3. 缓存层

在 `DoubanModule.kt` 新增：

```kotlin
@Qualifier
@Retention(AnnotationRetention.BINARY)
annotation class DoubanRecommendDataStore

@Provides
@DoubanRecommendDataStore
fun provideDoubanRecommendDataStore(
    @ApplicationContext context: Context
): DataStore<Preferences> = context.dataStore(name = "douban_recommend_cache")

@Provides
fun provideDoubanRecommendCache(
    @DoubanRecommendDataStore dataStore: DataStore<Preferences>,
    json: Json,
    scope: CoroutineScope
): PersistentTtlCache<List<DoubanRecommendItem>> =
    persistentTtlCache(
        ttlMillis = 6 * 60 * 60 * 1000L,  // 6 小时
        maxSize = 0,                      // 不限容量
        dataStore = dataStore,
        json = json,
        keyPrefix = "douban_recommend",
        scope = scope
    )
```

缓存特性：
- key 格式：`douban_recommend:recommend_{type}_{userId}`（前缀由 PersistentTtlCache 自动加）
- TTL 6 小时，与豆瓣热榜一致
- 写内存时异步写磁盘，不阻塞返回
- 启动时 `loadFromDisk()` 恢复
- 退出登录时 `clearAll()`

### 4. ViewModel 层

#### UiState 扩展

`DiscoverUiState` 新增字段：

```kotlin
// 密封类表示猜你喜欢状态
sealed class DoubanRecommendState {
    object NotLoggedIn : DoubanRecommendState()
    object Loading : DoubanRecommendState()
    data class Success(
        val movieItems: List<DoubanRecommendItem>,
        val tvItems: List<DoubanRecommendItem>,
        val currentTab: RecommendTab
    ) : DoubanRecommendState()
    data class Error(val message: String) : DoubanRecommendState()
}

enum class RecommendTab { MOVIE, TV }

// 在 DiscoverUiState 中添加
val doubanRecommendState: DoubanRecommendState = DoubanRecommendState.NotLoggedIn
```

#### 新增依赖注入

```kotlin
@HiltViewModel
class DiscoverViewModel @Inject constructor(
    // ... 现有依赖 ...
    private val doubanRepository: DoubanRepository,
    private val doubanAuthStorage: DoubanAuthStorage,
    private val doubanRecommendCache: PersistentTtlCache<List<DoubanRecommendItem>>
)
```

#### 新增方法

```kotlin
fun loadDoubanRecommend() {
    viewModelScope.launch {
        val credentials = doubanAuthStorage.getCredentials()
        if (credentials == null) {
            _uiState.update { it.copy(doubanRecommendState = DoubanRecommendState.NotLoggedIn) }
            return@launch
        }
        _uiState.update { it.copy(doubanRecommendState = DoubanRecommendState.Loading) }
        // 并发加载电影+电视剧
        try {
            val movieDeferred = async { loadRecommendTab("movie", credentials) }
            val tvDeferred = async { loadRecommendTab("tv", credentials) }
            val movieItems = movieDeferred.await()
            val tvItems = tvDeferred.await()
            _uiState.update {
                it.copy(doubanRecommendState = DoubanRecommendState.Success(
                    movieItems, tvItems, RecommendTab.MOVIE
                ))
            }
        } catch (e: DoubanCookieExpiredException) {
            doubanAuthStorage.clearCredentials()
            _uiState.update { it.copy(doubanRecommendState = DoubanRecommendState.NotLoggedIn) }
        } catch (e: Exception) {
            _uiState.update { it.copy(doubanRecommendState = DoubanRecommendState.Error(e.message ?: "加载失败")) }
        }
    }
}

private suspend fun loadRecommendTab(type: String, credentials: DoubanCredentials): List<DoubanRecommendItem> {
    val cacheKey = "recommend_${type}_${credentials.userId}"
    return doubanRecommendCache.getOrAwait(cacheKey) {
        doubanRepository.fetchRecommend(type, credentials.cookie)
    }
}

fun switchRecommendTab(tab: RecommendTab) {
    _uiState.update { state ->
        val current = state.doubanRecommendState
        if (current is DoubanRecommendState.Success) {
            state.copy(doubanRecommendState = current.copy(currentTab = tab))
        } else state
    }
}

fun retryDoubanRecommend() {
    loadDoubanRecommend()
}
```

#### 导航方法

```kotlin
fun resolveAndNavigateRecommend(item: DoubanRecommendItem, onNavigate: () -> Unit) {
    viewModelScope.launch {
        // 复用 doubanTmdbCache（title → TmdbSearchResult，永久缓存）
        // 流程与 resolveAndNavigate(DoubanHotItem) 一致
        // 1. doubanTmdbCache[item.title] 命中 → 拿 tmdbId
        // 2. 未命中 → TMDB 搜索 by title
        // 3. resolveTmdbAndNavigate(tmdbId, item.title, onNavigate)
    }
}
```

#### 加载时机

- `loadInitialSections()` 中调用 `loadDoubanRecommend()`（首屏优先，与豆瓣热榜同级）
- 下拉刷新时随 `loadRemainingSections(force = true)` 一起刷新

#### 想看/已看状态

卡片状态从 `WatchlistWatchedIds` 缓存计算：
- `isInWatchlist` = `watchlistWatchedIds.value?.traktIdByTmdb(tmdbId) != null`
- `isWatched` = `watchlistWatchedIds.value?.isWatchedByTmdb(tmdbId) == true`
- 由于推荐项初始无 tmdbId，状态在导航解析完成后才准确；初始可显示无状态（与现有豆瓣热榜卡片行为一致）

### 5. UI 层

#### 新 Composable

在 `DiscoverSections.kt` 新增 `DoubanRecommendSection`：

```kotlin
@Composable
fun DoubanRecommendSection(
    state: DoubanRecommendState,
    onTabSwitch: (RecommendTab) -> Unit,
    onMovieClick: (DoubanRecommendItem) -> Unit,
    onLoginClick: () -> Unit,
    onRetry: () -> Unit,
    modifier: Modifier = Modifier
)
```

布局结构：
- SectionHeader：标题「猜你喜欢」+ 右侧电影/电视剧 Tab 分段器（复用 tmdb-popular 栏目的 day/week 样式）
- 未登录：居中引导卡片（图标 + 提示文案 + 「去登录豆瓣」按钮）
- 加载中：LazyRow 骨架屏（复用 `DoubanHotCardSkeleton`）
- 加载成功：LazyRow of MovieCard
- 加载失败：ErrorRetryRow

#### 卡片

复用发现页 `MovieCard`（`DiscoverComponents.kt:67`），推荐理由通过 `subtitle` 参数传递：

```kotlin
MovieCard(
    title = item.title,
    posterPath = item.cover,  // 完整 doubanio.com URL，MovieCard 已支持
    year = "",                 // 推荐项响应中无年份字段，留空
    rating = item.rating?.value,
    subtitle = item.reasonTags?.joinToString(" · "),  // "日本 · 人生 · 惊悚"
    isResolving = resolvingSet.contains(item.id),
    isInWatchlist = false,     // 初始无 tmdbId，导航解析后更新
    isWatched = false,
    onClick = { onMovieClick(item) }
)
```

#### Tab 分段器

复用 tmdb-popular 栏目的样式（`DiscoverScreen.kt:244-336` 中的分段器实现）：
- 两个选项：电影 / 电视剧
- 选中态：主题色背景 + 白字
- 未选中：透明背景 + 灰字
- 切换调用 `viewModel.switchRecommendTab(tab)`

#### DiscoverScreen 集成

在 `DiscoverScreen.kt` 的 `sectionConfigs.filter { it.visible }.forEach` 循环中新增分支：

```kotlin
DiscoverSectionStorage.SECTION_ID_DOUBAN_RECOMMEND -> {
    DoubanRecommendSection(
        state = uiState.doubanRecommendState,
        onTabSwitch = viewModel::switchRecommendTab,
        onMovieClick = { item -> viewModel.resolveAndNavigateRecommend(item) { /* 导航 */ } },
        onLoginClick = { navController.navigate(DoubanLogin) },
        onRetry = viewModel::retryDoubanRecommend
    )
}
```

### 6. 栏目管理

在 `DiscoverSectionStorage.kt` 修改：

```kotlin
companion object {
    const val SECTION_ID_DOUBAN_RECOMMEND = "douban-recommend"

    val ALL_SECTION_IDS = listOf(
        SECTION_ID_DOUBAN_RECOMMEND,  // 新增，第一位
        SECTION_ID_DOUBAN_MOVIE,
        SECTION_ID_DOUBAN_WEEKLY,
        // ... 其余不变
    )
}
```

默认排序第一，用户可在设置页栏目管理中调整。

### 7. 国际化

在 `values/strings.xml`、`values-zh/strings.xml`、`values-ja/strings.xml`、`values-ko/strings.xml` 中同步添加：

| key | values (英) | values-zh (中) | values-ja (日) | values-ko (韩) |
|-----|-------------|----------------|-----------------|----------------|
| `discover_douban_recommend` | Guess You Like | 猜你喜欢 | あなたに似合う | 너를 위한 추천 |
| `discover_douban_recommend_movie` | Movies | 电影 | 映画 | 영화 |
| `discover_douban_recommend_tv` | TV Shows | 电视剧 | ドラマ | TV 드라마 |
| `discover_douban_recommend_login_prompt` | Log in to Douban for personalized recommendations | 登录豆瓣解锁个性化推荐 | 豆瓣にログインしてパーソナライズされたおすすめを入手 | 더우반에 로그인하여 맞춤 추천 받기 |
| `discover_douban_recommend_login_button` | Log in to Douban | 去登录豆瓣 | 豆瓣にログイン | 더우반 로그인 |

### 8. DI 模块

在 `DoubanModule.kt` 新增 DataStore 和缓存 Provider（见缓存层设计）。

## 错误处理

| 场景 | 处理方式 |
|------|---------|
| 未登录豆瓣 | 显示引导卡片（图标 + 文案 + 登录按钮） |
| Cookie 过期（401/403） | 清除登录态 → 显示引导卡片 |
| 网络失败 | 显示 ErrorRetryRow，点击重试 |
| 推荐列表为空 | 显示 EmptyRow |
| 单条推荐无 reason_data | 正常显示卡片，subtitle 留空 |
| 单条推荐无 rating | 正常显示卡片，评分角标不显示 |

## 测试

- 构建 debug 包验证编译通过
- 已登录豆瓣状态下打开发现页，确认「猜你喜欢」栏目在第一位
- 切换电影/电视剧 Tab，确认数据正确加载
- 确认推荐理由标签在卡片副标题区显示
- 退出豆瓣登录，确认栏目显示引导卡片
- 杀进程重开，确认缓存命中（秒进，不转圈）

## 文件清单

| 文件 | 改动类型 |
|------|---------|
| `data/remote/douban/dto/DoubanRecommendDtos.kt` | 新建 |
| `data/remote/douban/DoubanRepository.kt` | 新增 `fetchRecommend` 方法 + `DoubanCookieExpiredException` |
| `di/DoubanModule.kt` | 新增 DataStore + 缓存 Provider |
| `ui/screen/discover/DiscoverViewModel.kt` | 新增状态、方法、依赖注入 |
| `ui/screen/discover/DiscoverSections.kt` | 新增 `DoubanRecommendSection` |
| `ui/screen/discover/DiscoverScreen.kt` | 新增栏目分支 |
| `data/local/DiscoverSectionStorage.kt` | 新增栏目 ID |
| `res/values/strings.xml` | 新增 5 条字符串 |
| `res/values-zh/strings.xml` | 新增 5 条字符串 |
| `res/values-ja/strings.xml` | 新增 5 条字符串 |
| `res/values-ko/strings.xml` | 新增 5 条字符串 |
