# 豆瓣「猜你喜欢」发现页栏目实现计划

> **面向 AI 代理的工作者：** 必需子技能：使用 superpowers:subagent-driven-development（推荐）或 superpowers:executing-plans 逐任务实现此计划。步骤使用复选框（`- [ ]`）语法来跟踪进度。

**目标：** 在发现页第一位新增「猜你喜欢」栏目，基于豆瓣登录态的个性化推荐，支持电影/电视剧 Tab 切换，卡片展示推荐理由标签，未登录显示引导卡片。

**架构：** 在 `DoubanRepository` 新增 `fetchRecommend(type, cookie)` 方法调用豆瓣 rexxar 端点；新增按用户隔离的 6h `PersistentTtlCache`；`DiscoverViewModel` 新增状态机和方法；`DiscoverSections.kt` 新增 `DoubanRecommendSection` Composable；`DiscoverSectionStorage` 新增栏目 ID。

**技术栈：** Kotlin + Jetpack Compose + Hilt + kotlinx.serialization + OkHttp + DataStore

---

## 文件清单

| 文件 | 改动 | 职责 |
|------|------|------|
| `app/src/main/java/com/tracktosearch/data/remote/douban/dto/DoubanRecommendDtos.kt` | 新建 | 豆瓣推荐响应 DTO |
| `app/src/main/java/com/tracktosearch/data/remote/douban/DoubanRepository.kt` | 修改 | 新增 `fetchRecommend` 方法 + `DoubanCookieExpiredException` |
| `app/src/main/java/com/tracktosearch/di/DoubanModule.kt` | 修改 | 新增 DataStore + 缓存 Provider + 修改 Repository Provider 注入 Json |
| `app/src/main/java/com/tracktosearch/data/local/DiscoverSectionStorage.kt` | 修改 | 新增栏目 ID |
| `app/src/main/java/com/tracktosearch/ui/screen/discover/DiscoverViewModel.kt` | 修改 | 新增状态、依赖、方法 |
| `app/src/main/java/com/tracktosearch/ui/screen/discover/DiscoverSections.kt` | 修改 | 新增 `DoubanRecommendSection` |
| `app/src/main/java/com/tracktosearch/ui/screen/discover/DiscoverScreen.kt` | 修改 | 新增栏目分支 + `onLoginClick` 参数透传 |
| `app/src/main/java/com/tracktosearch/ui/navigation/AppNavigation.kt` | 修改 | 透传 `onLoginClick` 到 DiscoverScreen |
| `app/src/main/java/com/tracktosearch/ui/screen/MainScreen.kt` | 修改 | 透传 `onLoginClick` 到 DiscoverScreen |
| `app/src/main/res/values/strings.xml` | 修改 | 新增 5 条字符串 |
| `app/src/main/res/values-zh/strings.xml` | 修改 | 新增 5 条字符串 |
| `app/src/main/res/values-ja/strings.xml` | 修改 | 新增 5 条字符串 |
| `app/src/main/res/values-ko/strings.xml` | 修改 | 新增 5 条字符串 |

---

## 任务 1：创建豆瓣推荐 DTO

**文件：**
- 创建：`app/src/main/java/com/tracktosearch/data/remote/douban/dto/DoubanRecommendDtos.kt`

- [ ] **步骤 1：编写 DTO 文件**

```kotlin
package com.tracktosearch.data.remote.douban.dto

import kotlinx.serialization.Serializable

/**
 * 豆瓣「为你推荐」rexxar 端点响应。
 *
 * 带登录 cookie 时返回个性化单剧推荐（type="subject"），混少量片单（type="playlist"）。
 * 免登录时返回通用热门片单。
 */
@Serializable
data class DoubanRecommendResponse(
    val items: List<DoubanRecommendItem> = emptyList(),
    val total: Int = 0
)

@Serializable
data class DoubanRecommendItem(
    val type: String = "",           // "subject"=单剧 | "playlist"=片单
    val id: String = "",             // doubanId
    val title: String = "",
    val cover: String? = null,       // 豆瓣海报完整 URL（doubanio.com）
    val rating: DoubanRecommendRating? = null,
    val url: String? = null,
    val alg_json: AlgJson? = null
) {
    val isSubject: Boolean get() = type == "subject"
    /** 推荐理由标签（如 ["日本", "人生"]），仅个性化推荐才有 */
    val reasonTags: List<String>? get() = alg_json?.reason_data?.takeIf { it.isNotEmpty() }
}

@Serializable
data class DoubanRecommendRating(
    val value: String? = null,       // "9.4"
    val count: Int? = null
)

@Serializable
data class AlgJson(
    val alg_strategy: String? = null,  // "user_movie" / "user_tv" / "hot"
    val reason_data: List<String>? = null
)
```

- [ ] **步骤 2：Commit**

```bash
git add app/src/main/java/com/tracktosearch/data/remote/douban/dto/DoubanRecommendDtos.kt
git commit -m "feat: 添加豆瓣推荐 DTO"
```

---

## 任务 2：在 DoubanRepository 新增 fetchRecommend 方法

**文件：**
- 修改：`app/src/main/java/com/tracktosearch/data/remote/douban/DoubanRepository.kt`
  - 构造函数新增 `json: Json` 参数（第 111-114 行）
  - 在文件末尾 class 闭合大括号前新增方法

- [ ] **步骤 1：修改 DoubanRepository 构造函数注入 Json**

找到 `app/src/main/java/com/tracktosearch/data/remote/douban/DoubanRepository.kt:111-114`：

```kotlin
class DoubanRepository(
    private val detailCache: PersistentTtlCache<DoubanDetailCacheEntry>,
    private val cloudDetailsPoolManager: CloudDetailsPoolManager? = null
) {
```

改为：

```kotlin
class DoubanRepository(
    private val detailCache: PersistentTtlCache<DoubanDetailCacheEntry>,
    private val cloudDetailsPoolManager: CloudDetailsPoolManager? = null,
    private val json: Json = Json { ignoreUnknownKeys = true; coerceInputValues = true }
) {
```

在文件顶部 import 区新增：

```kotlin
import kotlinx.serialization.json.Json
import com.tracktosearch.data.remote.douban.dto.DoubanRecommendItem
import com.tracktosearch.data.remote.douban.dto.DoubanRecommendResponse
```

- [ ] **步骤 2：新增 DoubanCookieExpiredException 自定义异常**

在 `DoubanRepository.kt` 类定义上方（`data class DelayInfo` 上方），新增：

```kotlin
/** 豆瓣 Cookie 过期异常（401/403 或响应为登录页） */
class DoubanCookieExpiredException(message: String = "豆瓣登录已过期") : Exception(message)
```

- [ ] **步骤 3：新增 fetchRecommend 方法**

在 `DoubanRepository.kt` 类内部（建议放在 `fetchUserProfile` 方法之后，文件末尾 class 闭合大括号前），新增：

```kotlin
/**
 * 抓取豆瓣「为你推荐」rexxar 端点（movie/tv），带登录 cookie 返回个性化单剧推荐。
 *
 * - 带登录 cookie: 返回个性化单剧推荐，每条带 alg_strategy("user_movie"/"user_tv") 和 reason_data 推荐理由
 * - 免登录: 返回通用热门片单（非单剧），不推荐使用
 *
 * @param type "movie" 或 "tv"
 * @param cookie 用户登录后的豆瓣 cookie
 * @return 个性化单剧列表（已过滤片单/豆列，仅保留 type="subject"）
 * @throws DoubanCookieExpiredException 当响应为登录页（cookie 过期）
 */
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
        val body = response.body?.string() ?: throw IOException("豆瓣推荐响应为空")
        // cookie 过期:rexxar 端点可能返回 401 或重定向到登录页
        if (response.code == 401 || response.code == 403) {
            throw DoubanCookieExpiredException()
        }
        // 防御性检查:响应是 HTML 登录页而非 JSON
        if (body.contains("<form id=\"lzform\"") || body.contains("\"login\":true")) {
            throw DoubanCookieExpiredException()
        }
        val parsed = json.decodeFromString<DoubanRecommendResponse>(body)
        // 仅保留单剧推荐，过滤片单/豆列
        parsed.items.filter { it.isSubject }
    }
}
```

- [ ] **步骤 4：Commit**

```bash
git add app/src/main/java/com/tracktosearch/data/remote/douban/DoubanRepository.kt
git commit -m "feat: DoubanRepository 新增 fetchRecommend 方法"
```

---

## 任务 3：在 DoubanModule 新增缓存 Provider 并更新 Repository Provider

**文件：**
- 修改：`app/src/main/java/com/tracktosearch/di/DoubanModule.kt`

- [ ] **步骤 1：新增 import 和 DataStore 扩展属性**

在 `DoubanModule.kt` 文件顶部 import 区新增：

```kotlin
import com.tracktosearch.data.remote.douban.dto.DoubanRecommendItem
```

在文件底部（`private val Context.doubanDetailCacheStore` 之后）新增 DataStore 扩展属性：

```kotlin
/** 豆瓣推荐缓存 DataStore */
private val Context.doubanRecommendCacheStore: DataStore<Preferences> by androidx.datastore.preferences.preferencesDataStore(name = "douban_recommend_cache")
```

- [ ] **步骤 2：新增缓存 Provider 和更新 Repository Provider**

在 `DoubanModule` object 内部（`provideDoubanFailureExporter` 方法之后，object 闭合大括号前），新增：

```kotlin
/**
 * 豆瓣「为你推荐」持久化缓存（按用户隔离，6 小时 TTL）。
 *
 * key 格式: recommend_{type}_{userId}（type=movie/tv）
 * 退出登录时调用 clearAll() 清除该用户缓存。
 */
@Provides
@Singleton
fun provideDoubanRecommendCache(
    @ApplicationContext context: Context,
    json: Json
): PersistentTtlCache<List<DoubanRecommendItem>> {
    val dataStore = context.doubanRecommendCacheStore
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    return persistentTtlCache(
        ttlMillis = 6 * 60 * 60 * 1000L, // 6 小时
        maxSize = 0,
        dataStore = dataStore,
        json = json,
        keyPrefix = "douban_recommend",
        scope = scope
    )
}
```

更新现有 `provideDoubanRepository` 方法，注入 Json 实例。找到 `provideDoubanRepository`（第 60-65 行）：

```kotlin
@Provides
@Singleton
fun provideDoubanRepository(
    detailCache: PersistentTtlCache<DoubanDetailCacheEntry>,
    cloudDetailsPoolManager: CloudDetailsPoolManager
): DoubanRepository = DoubanRepository(detailCache, cloudDetailsPoolManager)
```

改为：

```kotlin
@Provides
@Singleton
fun provideDoubanRepository(
    detailCache: PersistentTtlCache<DoubanDetailCacheEntry>,
    cloudDetailsPoolManager: CloudDetailsPoolManager,
    json: Json
): DoubanRepository = DoubanRepository(detailCache, cloudDetailsPoolManager, json)
```

- [ ] **步骤 3：构建验证编译通过**

运行：`.\gradlew assembleDebug`
预期：BUILD SUCCESSFUL

- [ ] **步骤 4：Commit**

```bash
git add app/src/main/java/com/tracktosearch/di/DoubanModule.kt
git commit -m "feat: 新增豆瓣推荐缓存 Provider"
```

---

## 任务 4：在 DiscoverSectionStorage 新增栏目 ID

**文件：**
- 修改：`app/src/main/java/com/tracktosearch/data/local/DiscoverSectionStorage.kt:38-51`

- [ ] **步骤 1：新增栏目 ID 常量并在列表首位插入**

找到 `DiscoverSectionStorage.kt:36-51`：

```kotlin
companion object {
    // 所有发现页栏目 ID（默认顺序）
    val ALL_SECTION_IDS = listOf(
        "douban-movie",
        "douban-weekly",
        "douban-top250",
        "douban-nowplaying",
        "tmdb-popular",
        "tmdb-upcoming",
        "trakt-trending-movies",
        "trakt-trending-shows",
        "trakt-anticipated",
        "trakt-recommendations",
        "trakt-show-recommendations",
        "trakt-lists"
    )
```

改为：

```kotlin
companion object {
    const val SECTION_ID_DOUBAN_RECOMMEND = "douban-recommend"

    // 所有发现页栏目 ID（默认顺序）
    val ALL_SECTION_IDS = listOf(
        SECTION_ID_DOUBAN_RECOMMEND,
        "douban-movie",
        "douban-weekly",
        "douban-top250",
        "douban-nowplaying",
        "tmdb-popular",
        "tmdb-upcoming",
        "trakt-trending-movies",
        "trakt-trending-shows",
        "trakt-anticipated",
        "trakt-recommendations",
        "trakt-show-recommendations",
        "trakt-lists"
    )
```

- [ ] **步骤 2：Commit**

```bash
git add app/src/main/java/com/tracktosearch/data/local/DiscoverSectionStorage.kt
git commit -m "feat: 发现页新增「猜你喜欢」栏目 ID"
```

---

## 任务 5：新增国际化字符串

**文件：**
- 修改：`app/src/main/res/values/strings.xml`
- 修改：`app/src/main/res/values-zh/strings.xml`
- 修改：`app/src/main/res/values-ja/strings.xml`
- 修改：`app/src/main/res/values-ko/strings.xml`

- [ ] **步骤 1：在 values/strings.xml 新增英文字符串**

找到 `app/src/main/res/values/strings.xml:1053`（`discover_douban_nowplaying` 行），在其后插入：

```xml
<string name="discover_douban_recommend">Guess You Like</string>
<string name="discover_douban_recommend_movie">Movies</string>
<string name="discover_douban_recommend_tv">TV Shows</string>
<string name="discover_douban_recommend_login_prompt">Log in to Douban for personalized recommendations</string>
<string name="discover_douban_recommend_login_button">Log in to Douban</string>
```

- [ ] **步骤 2：在 values-zh/strings.xml 新增中文字符串**

找到 `app/src/main/res/values-zh/strings.xml:1053`（`discover_douban_nowplaying` 行），在其后插入：

```xml
<string name="discover_douban_recommend">猜你喜欢</string>
<string name="discover_douban_recommend_movie">电影</string>
<string name="discover_douban_recommend_tv">电视剧</string>
<string name="discover_douban_recommend_login_prompt">登录豆瓣解锁个性化推荐</string>
<string name="discover_douban_recommend_login_button">去登录豆瓣</string>
```

- [ ] **步骤 3：在 values-ja/strings.xml 新增日文字符串**

找到 `app/src/main/res/values-ja/strings.xml:953`（`discover_douban_nowplaying` 行），在其后插入：

```xml
<string name="discover_douban_recommend">あなたに似合う</string>
<string name="discover_douban_recommend_movie">映画</string>
<string name="discover_douban_recommend_tv">テレビ番組</string>
<string name="discover_douban_recommend_login_prompt">豆瓣にログインしてパーソナライズされたおすすめを入手</string>
<string name="discover_douban_recommend_login_button">豆瓣にログイン</string>
```

- [ ] **步骤 4：在 values-ko/strings.xml 新增韩文字符串**

找到 `app/src/main/res/values-ko/strings.xml:953`（`discover_douban_nowplaying` 行），在其后插入：

```xml
<string name="discover_douban_recommend">너를 위한 추천</string>
<string name="discover_douban_recommend_movie">영화</string>
<string name="discover_douban_recommend_tv">TV 프로그램</string>
<string name="discover_douban_recommend_login_prompt">더우반에 로그인하여 맞춤 추천 받기</string>
<string name="discover_douban_recommend_login_button">더우반 로그인</string>
```

- [ ] **步骤 5：Commit**

```bash
git add app/src/main/res/values/strings.xml app/src/main/res/values-zh/strings.xml app/src/main/res/values-ja/strings.xml app/src/main/res/values-ko/strings.xml
git commit -m "feat: 新增猜你喜欢 4 语言字符串"
```

---

## 任务 6：扩展 DiscoverViewModel 支持猜你喜欢

**文件：**
- 修改：`app/src/main/java/com/tracktosearch/ui/screen/discover/DiscoverViewModel.kt`

- [ ] **步骤 1：新增 import**

在 `DiscoverViewModel.kt` 顶部 import 区新增：

```kotlin
import com.tracktosearch.data.local.DoubanAuthStorage
import com.tracktosearch.data.local.DoubanCredentials
import com.tracktosearch.data.remote.douban.DoubanCookieExpiredException
import com.tracktosearch.data.remote.douban.DoubanRepository
import com.tracktosearch.data.remote.douban.dto.DoubanRecommendItem
```

- [ ] **步骤 2：新增状态密封类和枚举**

在 `DiscoverUiState` data class 定义上方（第 47 行之前），新增：

```kotlin
/** 「猜你喜欢」Tab */
enum class RecommendTab { MOVIE, TV }

/** 「猜你喜欢」栏目状态 */
sealed class DoubanRecommendState {
    /** 未登录豆瓣 */
    object NotLoggedIn : DoubanRecommendState()
    /** 加载中 */
    object Loading : DoubanRecommendState()
    /** 加载成功 */
    data class Success(
        val movieItems: List<DoubanRecommendItem>,
        val tvItems: List<DoubanRecommendItem>,
        val currentTab: RecommendTab
    ) : DoubanRecommendState()
    /** 加载失败 */
    data class Error(val message: String) : DoubanRecommendState()
}
```

- [ ] **步骤 3：在 DiscoverUiState 新增字段**

找到 `DiscoverUiState` data class（第 48-93 行），在 `val isLoadingRecommendationsAll: Boolean = false` 之后（第 92 行，class 闭合大括号前）新增：

```kotlin
    // 豆瓣「猜你喜欢」状态
    val doubanRecommendState: DoubanRecommendState = DoubanRecommendState.NotLoggedIn,
    /** 当前正在解析的豆瓣推荐条目 ID（用于卡片转圈遮罩） */
    val resolvingRecommendItemId: String? = null
```

- [ ] **步骤 4：在 ViewModel 构造函数新增依赖注入**

找到 `DiscoverViewModel` 构造函数（第 96-106 行）：

```kotlin
@HiltViewModel
class DiscoverViewModel @Inject constructor(
    private val doubanHotApi: DoubanHotApiService,
    private val tmdbRepository: TmdbRepository,
    private val traktRepository: TraktRepository,
    private val searchHistoryStorage: SearchHistoryStorage,
    private val viewedItemStorage: ViewedItemStorage,
    private val discoverSectionStorage: DiscoverSectionStorage,
    private val tokenStorage: TokenStorage,
    private val sharedDoubanHotCache: PersistentTtlCache<DoubanHotData>,
    @ApplicationContext private val context: Context
) : ViewModel() {
```

改为：

```kotlin
@HiltViewModel
class DiscoverViewModel @Inject constructor(
    private val doubanHotApi: DoubanHotApiService,
    private val tmdbRepository: TmdbRepository,
    private val traktRepository: TraktRepository,
    private val searchHistoryStorage: SearchHistoryStorage,
    private val viewedItemStorage: ViewedItemStorage,
    private val discoverSectionStorage: DiscoverSectionStorage,
    private val tokenStorage: TokenStorage,
    private val sharedDoubanHotCache: PersistentTtlCache<DoubanHotData>,
    private val doubanRepository: DoubanRepository,
    private val doubanAuthStorage: DoubanAuthStorage,
    private val doubanRecommendCache: PersistentTtlCache<List<DoubanRecommendItem>>,
    @ApplicationContext private val context: Context
) : ViewModel() {
```

- [ ] **步骤 5：在 init 块中调用加载方法**

找到 `init` 块（第 139-145 行）：

```kotlin
init {
    // 首屏优先加载：豆瓣 + TMDB（国内用户首屏最常看到）
    // Trakt 栏目延迟加载，由 loadRemainingSections() 在用户滚动到底部附近时触发
    loadInitialSections()
    // 登录后加载全局想看/已看 ID 缓存
    loadWatchlistWatchedIds()
}
```

改为：

```kotlin
init {
    // 首屏优先加载：豆瓣 + TMDB（国内用户首屏最常看到）
    // Trakt 栏目延迟加载，由 loadRemainingSections() 在用户滚动到底部附近时触发
    loadInitialSections()
    // 猜你喜欢（首屏优先，与豆瓣热榜同级）
    loadDoubanRecommend()
    // 登录后加载全局想看/已看 ID 缓存
    loadWatchlistWatchedIds()
}
```

- [ ] **步骤 6：在 loadInitialSections 中新增猜你喜欢加载逻辑**

找到 `loadInitialSections` 方法（第 165-174 行）：

```kotlin
private fun loadInitialSections() {
    val configs = sectionConfigs.value
    val visibleIds = configs.filter { it.visible }.map { it.id }.toSet()
    if (DOUBAN_CATEGORIES.any { it in visibleIds }) {
        // 豆瓣热榜已有数据则跳过
        if (_uiState.value.doubanHotCategories.none { it.items.isNotEmpty() }) loadDoubanHot()
    }
    if ("tmdb-popular" in visibleIds && !_uiState.value.isLoadingPopular && _uiState.value.tmdbPopularMovies.isEmpty() && _uiState.value.popularError == null) loadTmdbPopular()
    if ("tmdb-upcoming" in visibleIds && !_uiState.value.isLoadingUpcoming && _uiState.value.tmdbUpcomingMovies.isEmpty() && _uiState.value.upcomingError == null) loadTmdbUpcoming()
}
```

改为（在末尾新增对猜你喜欢的处理）：

```kotlin
private fun loadInitialSections() {
    val configs = sectionConfigs.value
    val visibleIds = configs.filter { it.visible }.map { it.id }.toSet()
    if (DOUBAN_CATEGORIES.any { it in visibleIds }) {
        // 豆瓣热榜已有数据则跳过
        if (_uiState.value.doubanHotCategories.none { it.items.isNotEmpty() }) loadDoubanHot()
    }
    if ("tmdb-popular" in visibleIds && !_uiState.value.isLoadingPopular && _uiState.value.tmdbPopularMovies.isEmpty() && _uiState.value.popularError == null) loadTmdbPopular()
    if ("tmdb-upcoming" in visibleIds && !_uiState.value.isLoadingUpcoming && _uiState.value.tmdbUpcomingMovies.isEmpty() && _uiState.value.upcomingError == null) loadTmdbUpcoming()
    // 猜你喜欢（首屏优先加载，与豆瓣热榜同级）
    if (DiscoverSectionStorage.SECTION_ID_DOUBAN_RECOMMEND in visibleIds) {
        if (_uiState.value.doubanRecommendState is DoubanRecommendState.NotLoggedIn ||
            _uiState.value.doubanRecommendState is DoubanRecommendState.Error) {
            loadDoubanRecommend()
        }
    }
}
```

- [ ] **步骤 7：在 loadVisibleSections 中新增猜你喜欢刷新**

找到 `loadVisibleSections` 方法（第 188-193 行）：

```kotlin
private fun loadVisibleSections() {
    loadDoubanHot()
    loadTmdbPopular()
    loadTmdbUpcoming()
    loadRemainingSections(force = true)
}
```

改为：

```kotlin
private fun loadVisibleSections() {
    loadDoubanHot()
    loadDoubanRecommend()
    loadTmdbPopular()
    loadTmdbUpcoming()
    loadRemainingSections(force = true)
}
```

- [ ] **步骤 8：新增 loadDoubanRecommend 方法**

在 `loadWatchlistWatchedIds` 方法（第 148-153 行）之后，新增：

```kotlin
/** 加载豆瓣「猜你喜欢」推荐（电影 + 电视剧） */
fun loadDoubanRecommend() {
    val credentials = doubanAuthStorage.getCredentials()
    if (credentials == null) {
        _uiState.value = _uiState.value.copy(doubanRecommendState = DoubanRecommendState.NotLoggedIn)
        return
    }
    _uiState.value = _uiState.value.copy(doubanRecommendState = DoubanRecommendState.Loading)
    viewModelScope.launch {
        try {
            // 并发加载电影+电视剧（各自缓存按 userId 隔离）
            val movieDeferred = async { loadRecommendTab("movie", credentials) }
            val tvDeferred = async { loadRecommendTab("tv", credentials) }
            val movieItems = movieDeferred.await()
            val tvItems = tvDeferred.await()
            _uiState.value = _uiState.value.copy(
                doubanRecommendState = DoubanRecommendState.Success(
                    movieItems = movieItems,
                    tvItems = tvItems,
                    currentTab = RecommendTab.MOVIE
                )
            )
        } catch (e: DoubanCookieExpiredException) {
            // cookie 过期，清除登录态，显示引导卡片
            doubanAuthStorage.clearCredentials()
            _uiState.value = _uiState.value.copy(doubanRecommendState = DoubanRecommendState.NotLoggedIn)
        } catch (e: Exception) {
            _uiState.value = _uiState.value.copy(
                doubanRecommendState = DoubanRecommendState.Error(e.message ?: "加载失败")
            )
        }
    }
}

/** 加载单个 Tab 的推荐数据（缓存优先，未命中走豆瓣 API） */
private suspend fun loadRecommendTab(type: String, credentials: DoubanCredentials): List<DoubanRecommendItem> {
    val cacheKey = "recommend_${type}_${credentials.userId}"
    return doubanRecommendCache.getOrAwait(cacheKey) {
        doubanRepository.fetchRecommend(type, credentials.cookie)
    }
}

/** 切换猜你喜欢 Tab（电影/电视剧） */
fun switchRecommendTab(tab: RecommendTab) {
    val current = _uiState.value.doubanRecommendState
    if (current is DoubanRecommendState.Success) {
        _uiState.value = _uiState.value.copy(
            doubanRecommendState = current.copy(currentTab = tab)
        )
    }
}

/** 重试猜你喜欢加载 */
fun retryDoubanRecommend() {
    loadDoubanRecommend()
}
```

- [ ] **步骤 9：新增导航方法 resolveAndNavigateRecommend**

在 `resolveAndNavigate(item: DoubanHotItem, onNavigate)` 方法（第 904 行开始）之后，新增：

```kotlin
/** 豆瓣推荐卡片点击：通过标题搜索 TMDB，再转换为 Trakt ID 后跳转详情页 */
fun resolveAndNavigateRecommend(
    item: DoubanRecommendItem,
    isMovieTab: Boolean,
    onNavigate: (traktId: Int, tmdbId: Int, title: String, imdbId: String, traktRating: Double, inWatchlist: Boolean, isWatched: Boolean) -> Unit
) {
    val cleanTitle = item.title.trim()
    val mediaType = if (isMovieTab) MediaType.MOVIE else MediaType.SHOW

    // 同步检查缓存 → 命中则秒进不转圈
    val cachedTmdb = doubanTmdbCache.get(cleanTitle)
    if (cachedTmdb != null && cachedTmdb.id > 0) {
        // 想看/已看缓存
        val wlCached = _watchlistWatchedIds.value?.traktIdByTmdb(cachedTmdb.id, mediaType)
        if (wlCached != null && wlCached > 0) {
            val inWl = _watchlistWatchedIds.value?.isInWatchlist(wlCached, cachedTmdb.id, mediaType) == true
            val isW = _watchlistWatchedIds.value?.isWatched(wlCached, cachedTmdb.id, mediaType) == true
            onNavigate(wlCached, cachedTmdb.id, cachedTmdb.title, "", 0.0, inWl, isW)
            return
        }
        // ID 转换缓存
        val idCached = traktRepository.getCachedTraktId(cachedTmdb.id, mediaType)
        if (idCached != null && idCached > 0) {
            val inWl = _watchlistWatchedIds.value?.isInWatchlist(idCached, cachedTmdb.id, mediaType) == true
            val isW = _watchlistWatchedIds.value?.isWatched(idCached, cachedTmdb.id, mediaType) == true
            onNavigate(idCached, cachedTmdb.id, cachedTmdb.title, "", 0.0, inWl, isW)
            return
        }
        // 负缓存命中：之前搜过没找到
        if (idCached == 0) {
            viewModelScope.launch { _toastEvent.emit(R.string.card_resolve_not_found) }
            return
        }
    }

    viewModelScope.launch {
        _uiState.value = _uiState.value.copy(resolvingRecommendItemId = item.id)
        try {
            // 1. 用 TMDB 搜索（优先从缓存获取）
            val searchResult = doubanTmdbCache.get(cleanTitle) ?: run {
                val result = tmdbRepository.searchMovie(cleanTitle)
                if (result != null && result.id > 0) {
                    doubanTmdbCache.put(cleanTitle, result)
                }
                result
            }
            if (searchResult == null || searchResult.id <= 0) {
                _uiState.value = _uiState.value.copy(resolvingRecommendItemId = null)
                _toastEvent.emit(R.string.card_resolve_not_found)
                return@launch
            }

            // 2. 优先从全局缓存中查找
            val cachedTraktId = _watchlistWatchedIds.value?.traktIdByTmdb(searchResult.id, mediaType)
            if (cachedTraktId != null && cachedTraktId > 0) {
                val inWl = _watchlistWatchedIds.value?.isInWatchlist(cachedTraktId, searchResult.id, mediaType) == true
                val isW = _watchlistWatchedIds.value?.isWatched(cachedTraktId, searchResult.id, mediaType) == true
                onNavigate(cachedTraktId, searchResult.id, searchResult.title, "", 0.0, inWl, isW)
                _uiState.value = _uiState.value.copy(resolvingRecommendItemId = null)
                return@launch
            }

            // 3. ID 转换缓存查找
            val idCached = traktRepository.getCachedTraktId(searchResult.id, mediaType)
            if (idCached != null && idCached > 0) {
                val inWl = _watchlistWatchedIds.value?.isInWatchlist(idCached, searchResult.id, mediaType) == true
                val isW = _watchlistWatchedIds.value?.isWatched(idCached, searchResult.id, mediaType) == true
                onNavigate(idCached, searchResult.id, searchResult.title, "", 0.0, inWl, isW)
                _uiState.value = _uiState.value.copy(resolvingRecommendItemId = null)
                return@launch
            }
            // 负缓存命中
            if (idCached == 0) {
                _toastEvent.emit(R.string.card_resolve_not_found)
                _uiState.value = _uiState.value.copy(resolvingRecommendItemId = null)
                return@launch
            }

            // 4. 缓存未命中，用 Trakt search/tmdb/{id} 转换
            val traktResult = traktRepository.searchByTmdb(searchResult.id, mediaType)
            traktResult.onSuccess { searchResults ->
                val first = searchResults.firstOrNull()
                val traktId = if (mediaType == MediaType.MOVIE) first?.movie?.ids?.trakt else first?.show?.ids?.trakt
                val imdbId = if (mediaType == MediaType.MOVIE) first?.movie?.ids?.imdb ?: "" else first?.show?.ids?.imdb ?: ""
                if (traktId != null && traktId > 0) {
                    val inWl = _watchlistWatchedIds.value?.isInWatchlist(traktId, searchResult.id, mediaType) == true
                    val isW = _watchlistWatchedIds.value?.isWatched(traktId, searchResult.id, mediaType) == true
                    onNavigate(traktId, searchResult.id, searchResult.title, imdbId, 0.0, inWl, isW)
                } else {
                    _toastEvent.emit(R.string.card_resolve_not_found)
                }
            }
            _uiState.value = _uiState.value.copy(resolvingRecommendItemId = null)
        } catch (e: Exception) {
            _uiState.value = _uiState.value.copy(resolvingRecommendItemId = null)
        }
    }
}
```

- [ ] **步骤 10：构建验证编译通过**

运行：`.\gradlew assembleDebug`
预期：BUILD SUCCESSFUL

- [ ] **步骤 11：Commit**

```bash
git add app/src/main/java/com/tracktosearch/ui/screen/discover/DiscoverViewModel.kt
git commit -m "feat: DiscoverViewModel 新增猜你喜欢状态和方法"
```

---

## 任务 7：新增 DoubanRecommendSection Composable

**文件：**
- 修改：`app/src/main/java/com/tracktosearch/ui/screen/discover/DiscoverSections.kt`

- [ ] **步骤 1：新增 import**

在 `DiscoverSections.kt` 顶部 import 区新增：

```kotlin
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.runtime.Immutable
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import com.tracktosearch.data.remote.douban.dto.DoubanRecommendItem
```

- [ ] **步骤 2：新增 DoubanRecommendSection Composable**

在 `DiscoverSections.kt` 文件末尾新增：

```kotlin
/**
 * 豆瓣「猜你喜欢」栏目。
 *
 * - 未登录豆瓣:显示居中引导卡片（图标 + 文案 + 登录按钮）
 * - 加载中:骨架屏
 * - 加载成功:LazyRow of MovieCard + 电影/电视剧 Tab 切换
 * - 加载失败:ErrorRetryRow
 *
 * 卡片副标题区显示推荐理由标签（如「日本 · 人生 · 惊悚」）。
 */
@Composable
internal fun DoubanRecommendSection(
    state: DoubanRecommendState,
    onTabSwitch: (RecommendTab) -> Unit,
    onItemClick: (DoubanRecommendItem) -> Unit,
    onLoginClick: () -> Unit,
    onRetry: () -> Unit
) {
    Column {
        // 栏目标题 + 电影/电视剧 Tab 分段器
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(bottom = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = stringResource(R.string.discover_douban_recommend),
                style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold)
            )
            Spacer(modifier = Modifier.width(12.dp))
            // 仅在 Success 状态显示 Tab 分段器
            if (state is DoubanRecommendState.Success) {
                val isMovie = state.currentTab == RecommendTab.MOVIE
                val movieLabel = stringResource(R.string.discover_douban_recommend_movie)
                val tvLabel = stringResource(R.string.discover_douban_recommend_tv)
                val tabPadding = 12.dp
                val tabHeight = 30.dp
                // 同步测量文字宽度，避免 onTextLayout 异步回调导致切回页面时宽度跳变
                val textMeasurer = rememberTextMeasurer()
                val movieTextWidthPx = remember(movieLabel) {
                    textMeasurer.measure(
                        text = movieLabel,
                        style = TextStyle(fontSize = 13.sp, fontWeight = FontWeight.Medium)
                    ).size.width
                }
                val tvTextWidthPx = remember(tvLabel) {
                    textMeasurer.measure(
                        text = tvLabel,
                        style = TextStyle(fontSize = 13.sp, fontWeight = FontWeight.Medium)
                    ).size.width
                }
                val density = LocalDensity.current
                val movieTabWidthDp = with(density) { movieTextWidthPx.toDp() + tabPadding * 2 }
                val tvTabWidthDp = with(density) { tvTextWidthPx.toDp() + tabPadding * 2 }
                val capsuleWidth = movieTabWidthDp + tvTabWidthDp
                val indicatorOffset by animateDpAsState(
                    targetValue = if (isMovie) 0.dp else movieTabWidthDp,
                    animationSpec = tween(200),
                    label = "recommendIndicator"
                )
                val indicatorWidth by animateDpAsState(
                    targetValue = if (isMovie) movieTabWidthDp else tvTabWidthDp,
                    animationSpec = tween(200),
                    label = "recommendIndicatorWidth"
                )
                Box(
                    modifier = Modifier
                        .height(tabHeight)
                        .width(capsuleWidth)
                        .clip(RoundedCornerShape(7.dp))
                        .background(MaterialTheme.colorScheme.surfaceVariant)
                ) {
                    // 滑块指示器
                    Box(
                        modifier = Modifier
                            .offset(x = indicatorOffset)
                            .width(indicatorWidth)
                            .fillMaxHeight()
                            .padding(3.dp)
                            .clip(RoundedCornerShape(5.dp))
                            .background(MaterialTheme.colorScheme.primary)
                    )
                    // 文字选项
                    Row {
                        Box(
                            modifier = Modifier
                                .width(movieTabWidthDp)
                                .fillMaxHeight()
                                .clickable(
                                    interactionSource = remember { MutableInteractionSource() },
                                    indication = null
                                ) { onTabSwitch(RecommendTab.MOVIE) },
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                text = movieLabel,
                                fontSize = 13.sp,
                                fontWeight = if (isMovie) FontWeight.Medium else FontWeight.Normal,
                                color = if (isMovie)
                                    MaterialTheme.colorScheme.onPrimary
                                else
                                    MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        Box(
                            modifier = Modifier
                                .width(tvTabWidthDp)
                                .fillMaxHeight()
                                .clickable(
                                    interactionSource = remember { MutableInteractionSource() },
                                    indication = null
                                ) { onTabSwitch(RecommendTab.TV) },
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                text = tvLabel,
                                fontSize = 13.sp,
                                fontWeight = if (!isMovie) FontWeight.Medium else FontWeight.Normal,
                                color = if (!isMovie)
                                    MaterialTheme.colorScheme.onPrimary
                                else
                                    MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
            }
        }

        // 内容区
        when (state) {
            is DoubanRecommendState.NotLoggedIn -> {
                // 引导登录卡片
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 24.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Text(
                        text = stringResource(R.string.discover_douban_recommend_login_prompt),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    OutlinedButton(onClick = onLoginClick) {
                        Text(stringResource(R.string.discover_douban_recommend_login_button))
                    }
                }
            }
            is DoubanRecommendState.Loading -> {
                LazyRow(horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                    items(5) { DoubanHotCardSkeleton() }
                }
            }
            is DoubanRecommendState.Success -> {
                val items = if (state.currentTab == RecommendTab.MOVIE) state.movieItems else state.tvItems
                if (items.isEmpty()) {
                    EmptyRow()
                } else {
                    LazyRow(horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                        itemsIndexed(items) { _, item ->
                            MovieCard(
                                title = item.title,
                                posterPath = item.cover,  // 豆瓣海报完整 URL，MovieCard 已支持
                                year = "",
                                rating = item.rating?.value,
                                subtitle = item.reasonTags?.joinToString(" · "),
                                isResolving = false,
                                onClick = { onItemClick(item) }
                            )
                        }
                    }
                }
            }
            is DoubanRecommendState.Error -> {
                ErrorRetryRow(error = state.message, onRetry = onRetry)
            }
        }
    }
}
```

- [ ] **步骤 3：补充缺失的 import**

在 `DiscoverSections.kt` 顶部 import 区补充（如尚未导入）：

```kotlin
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.getValue
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
```

- [ ] **步骤 4：构建验证编译通过**

运行：`.\gradlew assembleDebug`
预期：BUILD SUCCESSFUL

- [ ] **步骤 5：Commit**

```bash
git add app/src/main/java/com/tracktosearch/ui/screen/discover/DiscoverSections.kt
git commit -m "feat: 新增 DoubanRecommendSection Composable"
```

---

## 任务 8：在 DiscoverScreen 集成猜你喜欢栏目

**文件：**
- 修改：`app/src/main/java/com/tracktosearch/ui/screen/discover/DiscoverScreen.kt`

- [ ] **步骤 1：DiscoverScreen 函数签名新增 onLoginClick 参数**

找到 `DiscoverScreen` 函数定义（第 94-101 行）：

```kotlin
fun DiscoverScreen(
    onMovieClick: (traktId: Int, tmdbId: Int, title: String, imdbId: String, traktRating: Double, inWatchlist: Boolean, isWatched: Boolean) -> Unit,
    onShowClick: (traktId: Int, tmdbId: Int, title: String, imdbId: String, traktRating: Double, inWatchlist: Boolean, isWatched: Boolean) -> Unit,
    onListClick: (listId: Int, listName: String) -> Unit = { _, _ -> },
    onFilterDiscoverClick: () -> Unit = {},
    modifier: Modifier = Modifier,
    viewModel: DiscoverViewModel = hiltViewModel()
)
```

改为：

```kotlin
fun DiscoverScreen(
    onMovieClick: (traktId: Int, tmdbId: Int, title: String, imdbId: String, traktRating: Double, inWatchlist: Boolean, isWatched: Boolean) -> Unit,
    onShowClick: (traktId: Int, tmdbId: Int, title: String, imdbId: String, traktRating: Double, inWatchlist: Boolean, isWatched: Boolean) -> Unit,
    onListClick: (listId: Int, listName: String) -> Unit = { _, _ -> },
    onFilterDiscoverClick: () -> Unit = {},
    onDoubanLoginClick: () -> Unit = {},
    modifier: Modifier = Modifier,
    viewModel: DiscoverViewModel = hiltViewModel()
)
```

- [ ] **步骤 2：新增 import**

在 `DiscoverScreen.kt` 顶部 import 区新增：

```kotlin
import com.tracktosearch.data.local.DiscoverSectionStorage
```

- [ ] **步骤 3：在栏目渲染循环中新增猜你喜欢分支**

找到 `sectionConfigs.filter { it.visible }.forEach { config -> when (config.id) {` 循环（第 209-210 行附近）：

```kotlin
sectionConfigs.filter { it.visible }.forEach { config ->
    when (config.id) {
        // 豆瓣热榜各榜单
        "douban-movie", "douban-weekly", "douban-top250", "douban-nowplaying" -> {
```

在 `"douban-movie", ...` 分支之前（即 when 的第一个分支位置），新增：

```kotlin
        // 豆瓣「猜你喜欢」
        DiscoverSectionStorage.SECTION_ID_DOUBAN_RECOMMEND -> {
            item(key = config.id) {
                DoubanRecommendSection(
                    state = uiState.doubanRecommendState,
                    onTabSwitch = viewModel::switchRecommendTab,
                    onItemClick = { item ->
                        val isMovieTab = (uiState.doubanRecommendState as? DoubanRecommendState.Success)?.currentTab == RecommendTab.MOVIE
                        viewModel.resolveAndNavigateRecommend(item, isMovieTab) { traktId, tmdbId, title, imdbId, traktRating, inWatchlist, isWatched ->
                            if (isMovieTab) {
                                onMovieClick(traktId, tmdbId, title, imdbId, traktRating, inWatchlist, isWatched)
                            } else {
                                onShowClick(traktId, tmdbId, title, imdbId, traktRating, inWatchlist, isWatched)
                            }
                        }
                    },
                    onLoginClick = onDoubanLoginClick,
                    onRetry = viewModel::retryDoubanRecommend
                )
            }
        }
```

- [ ] **步骤 4：构建验证编译通过**

运行：`.\gradlew assembleDebug`
预期：BUILD SUCCESSFUL

- [ ] **步骤 5：Commit**

```bash
git add app/src/main/java/com/tracktosearch/ui/screen/discover/DiscoverScreen.kt
git commit -m "feat: DiscoverScreen 集成猜你喜欢栏目"
```

---

## 任务 9：透传 onDoubanLoginClick 到 DiscoverScreen

**文件：**
- 修改：`app/src/main/java/com/tracktosearch/ui/screen/MainScreen.kt`
- 修改：`app/src/main/java/com/tracktosearch/ui/navigation/AppNavigation.kt`

- [ ] **步骤 1：查找 DiscoverScreen 在 MainScreen 的调用位置**

运行搜索：在 `MainScreen.kt` 中查找 `DiscoverScreen(` 调用。

- [ ] **步骤 2：在 MainScreen 函数签名新增 onDoubanLoginClick 参数**

找到 `MainScreen.kt` 中的 `MainScreen` 函数定义。在其参数列表中（通常在 `onFilterDiscoverClick` 附近），新增参数：

```kotlin
onDoubanLoginClick: () -> Unit = {},
```

- [ ] **步骤 3：在 MainScreen 中 DiscoverScreen 调用处透传参数**

在 `MainScreen.kt` 中 `DiscoverScreen(...)` 调用处，新增参数：

```kotlin
onDoubanLoginClick = onDoubanLoginClick,
```

- [ ] **步骤 4：在 AppNavigation 中 MainScreen 调用处透传参数**

找到 `AppNavigation.kt` 中 `MainScreen(...)` 调用（第 272-280 行附近），在参数列表中新增：

```kotlin
onDoubanLoginClick = {
    navController.navigate(Routes.DOUBAN_LOGIN)
},
```

- [ ] **步骤 5：构建验证编译通过**

运行：`.\gradlew assembleDebug`
预期：BUILD SUCCESSFUL

- [ ] **步骤 6：Commit**

```bash
git add app/src/main/java/com/tracktosearch/ui/screen/MainScreen.kt app/src/main/java/com/tracktosearch/ui/navigation/AppNavigation.kt
git commit -m "feat: 透传 onDoubanLoginClick 到 DiscoverScreen"
```

---

## 任务 10：端到端构建验证

- [ ] **步骤 1：完整构建 debug 包**

运行：`.\gradlew assembleDebug`
预期：BUILD SUCCESSFUL

- [ ] **步骤 2：验证栏目顺序**

确认 `DiscoverSectionStorage.ALL_SECTION_IDS` 的第一项是 `SECTION_ID_DOUBAN_RECOMMEND`。

- [ ] **步骤 3：最终 Commit（如有未提交的修复）**

```bash
git add -A
git commit -m "feat: 豆瓣猜你喜欢栏目完整实现"
```

---

## 自检结果

### 1. 规格覆盖度

| 规格章节 | 对应任务 |
|---------|---------|
| 数据层 DTO | 任务 1 |
| Repository 方法 | 任务 2 |
| 缓存层 | 任务 3 |
| ViewModel UiState 扩展 | 任务 6（步骤 2-3） |
| ViewModel 依赖注入 | 任务 6（步骤 4） |
| ViewModel 加载时机 | 任务 6（步骤 5-7） |
| ViewModel 新增方法 | 任务 6（步骤 8-9） |
| UI 层 Composable | 任务 7 |
| DiscoverScreen 集成 | 任务 8 |
| 栏目管理 | 任务 4 |
| 国际化 | 任务 5 |
| 导航透传 | 任务 9 |
| 错误处理（401/403、网络失败、空列表） | 任务 2（步骤 3）+ 任务 6（步骤 8）+ 任务 7（步骤 2） |

### 2. 占位符扫描

无 TODO/待定内容，所有代码步骤都包含完整代码。

### 3. 类型一致性

- `DoubanRecommendItem` 在任务 1 定义，任务 2、3、6、7 引用——一致
- `DoubanRecommendState` 在任务 6 步骤 2 定义，任务 7 引用——一致
- `RecommendTab` 在任务 6 步骤 2 定义，任务 7、8 引用——一致
- `DoubanCookieExpiredException` 在任务 2 步骤 2 定义，任务 6 引用——一致
- `resolveAndNavigateRecommend(item, isMovieTab, onNavigate)` 签名在任务 6 步骤 9 定义，任务 8 步骤 3 引用——一致
- `fetchRecommend(type, cookie)` 在任务 2 步骤 3 定义，任务 6 步骤 8 引用——一致

所有类型和方法签名跨任务一致。
