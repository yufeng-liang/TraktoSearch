# 豆瓣电影标记 → Trakt 同步器 实现计划

> **面向 AI 代理的工作者：** 必需子技能：使用 superpowers:subagent-driven-development（推荐）或 superpowers:executing-plans 逐任务实现此计划。步骤使用复选框（`- [ ]`）语法来跟踪进度。

**目标：** 让用户在 App 内通过 WebView 登录豆瓣，爬取「想看/看过」标记，同步写入 Trakt 账号，使 watchlist 页展示合并后的完整列表。

**架构：** 豆瓣 WebView 登录抓 Cookie → Jsoup 爬取 HTML 解析条目 → imdbId 反查 Trakt → 调用已有 TraktRepository 方法推送 watchlist/history/ratings → Room 记录同步状态 → 前台对话框 + Foreground Service 显示进度。

**技术栈：** Kotlin + Compose + Hilt + Room + OkHttp + Jsoup + EncryptedSharedPreferences + Foreground Service

**规格文档：** `docs/superpowers/specs/2026-07-04-douban-trakt-sync-design.md`

---

## 文件结构

### 新建文件
| 文件 | 职责 |
|------|------|
| `data/local/db/DoubanEntities.kt` | DoubanSyncedItem 实体 + DoubanSyncedItemDao |
| `data/local/DoubanAuthStorage.kt` | 豆瓣 userId + Cookie 加密存储 |
| `data/remote/douban/DoubanSpider.kt` | HTML 解析（Jsoup），返回数据类 |
| `data/remote/douban/DoubanRepository.kt` | 爬取协调：分页 + 详情页 + 反爬延迟 |
| `data/repository/DoubanSyncManager.kt` | 同步协调：爬取→匹配→覆盖→推送 Trakt |
| `service/DoubanSyncService.kt` | Foreground Service + 通知栏进度 |
| `ui/screen/douban/DoubanLoginScreen.kt` | WebView 登录页 + ViewModel |
| `ui/screen/douban/DoubanSyncDialog.kt` | 前台同步进度对话框 |
| `di/DoubanModule.kt` | Hilt 依赖注入 |

### 修改文件
| 文件 | 改动 |
|------|------|
| `data/local/db/AppDatabase.kt` | 升级 v3→v4，添加 DoubanSyncedItem |
| `data/local/db/DatabaseModule.kt` | 添加 MIGRATION_3_4 + DAO provider |
| `data/remote/trakt/TraktApiService.kt` | 新增 searchByImdb + DELETE sync/watchlist + POST sync/ratings |
| `data/repository/TraktRepository.kt` | 新增 getCachedTraktIdByImdb / searchByImdb |
| `ui/screen/login/LoginScreen.kt` | 添加「或从豆瓣导入」次按钮 |
| `ui/screen/settings/SettingsScreen.kt` | 添加「豆瓣重新导入」入口 |
| `ui/screen/settings/SettingsViewModel.kt` | 添加触发豆瓣同步方法 |
| `ui/navigation/AppNavigation.kt` | 添加豆瓣登录路由 |
| `res/values/strings.xml` + 3 个语言版本 | 豆瓣相关字符串 |

---

## 任务 1：数据库基础设施（DoubanSyncedItem + DAO + Migration）

**文件：**
- 创建：`app/src/main/java/com/tracktosearch/data/local/db/DoubanEntities.kt`
- 修改：`app/src/main/java/com/tracktosearch/data/local/db/AppDatabase.kt`
- 修改：`app/src/main/java/com/tracktosearch/data/local/db/DatabaseModule.kt`

- [ ] **步骤 1：创建 DoubanSyncedItem 实体 + DAO**

创建 `app/src/main/java/com/tracktosearch/data/local/db/DoubanEntities.kt`：

```kotlin
package com.tracktosearch.data.local.db

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * 豆瓣→Trakt 同步记录，用于「重新导入」时跳过已同步条目
 */
@Entity(
    tableName = "douban_synced_items",
    indices = [Index("imdbId"), Index("status")]
)
data class DoubanSyncedItem(
    @PrimaryKey val doubanId: String,   // 豆瓣条目 ID（从链接解析）
    val imdbId: String?,
    val traktId: Int?,
    val title: String,
    val status: String,                 // "wish" | "collect"
    val rating: Int?,                   // 1-5
    val syncedAt: Long,
    val mediaType: String               // "movie" | "show"
)
```

```kotlin
package com.tracktosearch.data.local.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query

@Dao
interface DoubanSyncedItemDao {
    @Query("SELECT * FROM douban_synced_items WHERE doubanId = :doubanId")
    suspend fun getByDoubanId(doubanId: String): DoubanSyncedItem?

    @Query("SELECT doubanId FROM douban_synced_items")
    suspend fun getAllSyncedDoubanIds(): List<String>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(items: List<DoubanSyncedItem>)

    @Query("DELETE FROM douban_synced_items")
    suspend fun clearAll()

    @Query("SELECT COUNT(*) FROM douban_synced_items")
    suspend fun count(): Int
}
```

- [ ] **步骤 2：升级 AppDatabase 到 v4**

修改 `AppDatabase.kt`，在 `@Database` 注解的 `entities` 数组添加 `DoubanSyncedItem::class`，`version = 4`：

```kotlin
@Database(
    entities = [
        MediaItemEntity::class,
        MediaDetailEntity::class,
        NotificationRecordEntity::class,
        DoubanSyncedItem::class
    ],
    version = 4,
    exportSchema = false
)
abstract class AppDatabase : RoomDatabase() {
    // ... 现有抽象方法 ...
    abstract fun doubanSyncedItemDao(): DoubanSyncedItemDao
}
```

- [ ] **步骤 3：添加 MIGRATION_3_4 + DAO provider**

修改 `DatabaseModule.kt`，在现有 MIGRATION 后添加：

```kotlin
val MIGRATION_3_4 = object : Migration(3, 4) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("""
            CREATE TABLE IF NOT EXISTS `douban_synced_items` (
                `doubanId` TEXT NOT NULL,
                `imdbId` TEXT,
                `traktId` INTEGER,
                `title` TEXT NOT NULL,
                `status` TEXT NOT NULL,
                `rating` INTEGER,
                `syncedAt` INTEGER NOT NULL,
                `mediaType` TEXT NOT NULL,
                PRIMARY KEY(`doubanId`)
            )
        """.trimIndent())
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_douban_synced_items_imdbId` ON `douban_synced_items` (`imdbId`)")
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_douban_synced_items_status` ON `douban_synced_items` (`status`)")
    }
}
```

在 `provideAppDatabase` 的 `.addMigrations(...)` 链中追加 `MIGRATION_3_4`。

添加 DAO provider：
```kotlin
@Provides
@Singleton
fun provideDoubanSyncedItemDao(db: AppDatabase): DoubanSyncedItemDao = db.doubanSyncedItemDao()
```

- [ ] **步骤 4：构建验证**

运行：`.\gradlew assembleDebug`
预期：BUILD SUCCESSFUL，数据库升级无报错

- [ ] **步骤 5：Commit**

```bash
git add app/src/main/java/com/tracktosearch/data/local/db/
git commit -m "feat: 新增豆瓣同步状态表 DoubanSyncedItem + 数据库 v3→v4 迁移"
```

---

## 任务 2：豆瓣认证存储（DoubanAuthStorage）

**文件：**
- 创建：`app/src/main/java/com/tracktosearch/data/local/DoubanAuthStorage.kt`

- [ ] **步骤 1：实现 DoubanAuthStorage**

仿照 `TokenStorage.kt` 的 EncryptedSharedPreferences 模式：

```kotlin
package com.tracktosearch.data.local

import android.content.Context
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class DoubanAuthStorage @Inject constructor(
    @ApplicationContext private val context: Context
) {
    companion object {
        private const val FILE_NAME = "douban_auth_encrypted"
        private const val KEY_USER_ID = "douban_user_id"
        private const val KEY_COOKIE = "douban_cookie"
    }

    private val _isLoggedIn = MutableStateFlow(false)
    val isLoggedIn: StateFlow<Boolean> = _isLoggedIn.asStateFlow()

    private val prefs by lazy {
        val masterKey = MasterKey.Builder(context)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build()
        EncryptedSharedPreferences.create(
            context, FILE_NAME, masterKey,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
        )
    }

    init {
        _isLoggedIn.value = prefs.getString(KEY_USER_ID, null) != null
    }

    fun getCredentials(): DoubanCredentials? {
        val userId = prefs.getString(KEY_USER_ID, null) ?: return null
        val cookie = prefs.getString(KEY_COOKIE, null) ?: return null
        return DoubanCredentials(userId, cookie)
    }

    fun saveCredentials(userId: String, cookie: String) {
        prefs.edit().apply {
            putString(KEY_USER_ID, userId)
            putString(KEY_COOKIE, cookie)
        }.apply()
        _isLoggedIn.value = true
    }

    fun clearCredentials() {
        prefs.edit().clear().apply()
        _isLoggedIn.value = false
    }
}

data class DoubanCredentials(val userId: String, val cookie: String)
```

- [ ] **步骤 2：构建验证 + Commit**

```bash
.\gradlew assembleDebug
git add app/src/main/java/com/tracktosearch/data/local/DoubanAuthStorage.kt
git commit -m "feat: 新增 DoubanAuthStorage 豆瓣凭据加密存储"
```

---

## 任务 3：Trakt API 扩展（searchByImdb + ratings）

**文件：**
- 修改：`app/src/main/java/com/tracktosearch/data/remote/trakt/TraktApiService.kt`
- 修改：`app/src/main/java/com/tracktosearch/data/repository/TraktRepository.kt`

- [ ] **步骤 1：TraktApiService 新增端点**

在 `TraktApiService.kt` 现有 `searchByTmdb` 附近添加：

```kotlin
@GET("search/imdb/{id}")
suspend fun searchByImdb(
    @Path("id") id: String,
    @Query("type") type: String
): Response<List<TraktSearchResult>>
```

确认是否已有 `DELETE sync/watchlist` 和 `POST sync/ratings`，若没有则添加：

```kotlin
@HTTP(method = "DELETE", path = "sync/watchlist", hasBody = true)
suspend fun removeFromWatchlist(@Body request: TraktSyncRequest): Response<TraktSyncResponse>

@POST("sync/ratings")
suspend fun addRating(@Body request: TraktSyncRequest): Response<TraktSyncResponse>
```

注意：`removeFromWatchlist` 用 `@HTTP(method = "DELETE", hasBody = true)` 而非 `@DELETE`，因为 DELETE 带 body 需要显式声明。

- [ ] **步骤 2：TraktRepository 新增 getCachedTraktIdByImdb**

在 `TraktRepository.kt` 的 `getCachedTraktId` 附近添加：

```kotlin
private val searchByImdbCache = TtlCache<List<TraktSearchResult>>(maxSize = 100, ttlMillis = Long.MAX_VALUE)

fun getCachedTraktIdByImdb(imdbId: String, type: MediaType): Int? {
    val key = "${imdbId}_${type.name}"
    if (notFoundImdbIds.contains(key)) return 0
    val cached = searchByImdbCache.get(key) ?: return null
    for (result in cached) {
        val traktId = when (type) {
            MediaType.MOVIE -> result.movie?.ids?.trakt
            MediaType.SHOW -> result.show?.ids?.trakt
            else -> null
        }
        if (traktId != null && traktId > 0) return traktId
    }
    notFoundImdbIds.add(key)
    return 0
}

suspend fun searchByImdb(imdbId: String, type: MediaType): Result<List<TraktSearchResult>> {
    val typeStr = when (type) {
        MediaType.MOVIE -> "movie"
        MediaType.SHOW -> "show"
        else -> return Result.failure(IllegalArgumentException("Unsupported type"))
    }
    return runCatching {
        val response = traktApiService.searchByImdb(imdbId, typeStr)
        if (response.isSuccessful) {
            val results = response.body() ?: emptyList()
            searchByImdbCache.put("${imdbId}_${type.name}", results)
            results
        } else {
            emptyList()
        }
    }
}
```

同步在 TraktRepository 顶部新增 `notFoundImdbIds` 集合（仿照 `notFoundTmdbIds`）。

- [ ] **步骤 3：构建验证 + Commit**

```bash
.\gradlew assembleDebug
git add app/src/main/java/com/tracktosearch/data/remote/trakt/TraktApiService.kt app/src/main/java/com/tracktosearch/data/repository/TraktRepository.kt
git commit -m "feat: Trakt API 新增 searchByImdb 端点，支持按 IMDb ID 反查 Trakt ID"
```

---

## 任务 4：豆瓣爬虫（DoubanSpider + DoubanRepository）

**文件：**
- 创建：`app/src/main/java/com/tracktosearch/data/remote/douban/DoubanSpider.kt`
- 创建：`app/src/main/java/com/tracktosearch/data/remote/douban/DoubanRepository.kt`
- 创建：`app/src/main/java/com/tracktosearch/di/DoubanModule.kt`

- [ ] **步骤 1：定义豆瓣条目数据类 + DoubanSpider 解析逻辑**

创建 `DoubanSpider.kt`：

```kotlin
package com.tracktosearch.data.remote.douban

import org.jsoup.Jsoup
import org.jsoup.nodes.Document

/** 豆瓣标记列表中解析出的单条条目（未含 imdbId，需详情页爬取补全） */
data class DoubanMarkItem(
    val doubanId: String,        // 从链接 /subject/123456/ 解析
    val title: String,
    val rating: Int?,            // 1-5，null 表示未评分
    val comment: String?,        // 短评
    val markedAt: String,        // 标记时间 yyyy-MM-dd
    val doubanUrl: String,
    val posterUrl: String?
)

/** 详情页补充信息 */
data class DoubanDetailInfo(
    val imdbId: String?,
    val isTvShow: Boolean,       // 类型含「电视剧」即为 true
    val genres: List<String>
)

object DoubanSpider {
    private const val UA = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36"

    /** 解析单页标记列表 HTML，返回条目列表。空列表表示无更多条目 */
    fun parseMarkList(html: String): List<DoubanMarkItem> {
        val doc: Document = Jsoup.parse(html)
        val items = doc.select("div.item")
        return items.mapNotNull { el ->
            val titleEl = el.selectFirst("em") ?: return@mapNotNull null
            val title = titleEl.text().trim()
            if (title.isEmpty()) return@mapNotNull null

            val linkEl = el.selectFirst("a[href]") ?: return@mapNotNull null
            val doubanUrl = linkEl.attr("href")
            val doubanId = Regex("""subject/(\d+)""").find(doubanUrl)?.groupValues?.get(1) ?: return@mapNotNull null

            val rating = el.selectFirst("span[class~=rating\\d-t]")?.className()
                ?.let { Regex("rating(\\d)-t").find(it)?.groupValues?.get(1)?.toIntOrNull() }

            val comment = el.selectFirst("span.comment")?.text()?.takeIf { it.isNotEmpty() }
            val markedAt = el.selectFirst("span.date")?.text() ?: ""
            val posterUrl = el.selectFirst("img")?.attr("src")

            DoubanMarkItem(doubanId, title, rating, comment, markedAt, doubanUrl, posterUrl)
        }
    }

    /** 解析详情页 HTML，提取 imdbId 和类型 */
    fun parseDetail(html: String): DoubanDetailInfo {
        val doc = Jsoup.parse(html)
        val genres = doc.select("span[property=v:genre]").map { it.text() }
        val isTvShow = genres.any { it.contains("电视剧") || it.contains("综艺") }

        // IMDb ID 在 <span class="pl">IMDb:</span> 后面的文本节点
        val imdbId = doc.select("span.pl").firstOrNull { it.text().contains("IMDb") }
            ?.nextSibling()?.toString()?.trim()?.takeIf { it.startsWith("tt") }

        return DoubanDetailInfo(imdbId, isTvShow, genres)
    }

    /** 判断 HTML 是否为登录页（Cookie 过期） */
    fun isLoginPage(html: String): Boolean {
        val doc = Jsoup.parse(html)
        return doc.selectFirst("form#lzform") != null || doc.title().contains("登录")
    }
}
```

- [ ] **步骤 2：实现 DoubanRepository 爬取协调**

创建 `DoubanRepository.kt`：

```kotlin
package com.tracktosearch.data.remote.douban

import okhttp3.OkHttpClient
import okhttp3.Request
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.delay
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.random.Random

@Singleton
class DoubanRepository @Inject constructor() {

    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .build()

    private val ua = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36"

    /** 爬取指定状态的标记列表，回调每页结果。返回是否因 Cookie 过期中断 */
    suspend fun fetchMarkList(
        userId: String,
        cookie: String,
        status: DoubanMarkStatus,
        onPage: (List<DoubanMarkItem>, pageIndex: Int) -> Unit,
        onProgress: (current: Int) -> Unit = {}
    ): Boolean {
        var start = 0
        var pageIndex = 1
        var cookieExpired = false

        while (true) {
            val url = "https://movie.douban.com/people/${userId}/${status.path}?start=${start}&sort=time&mode=grid"
            val html = fetchHtml(url, cookie)
            if (DoubanSpider.isLoginPage(html)) {
                cookieExpired = true
                break
            }
            val items = DoubanSpider.parseMarkList(html)
            if (items.isEmpty()) break

            onPage(items, pageIndex)
            onProgress(start + items.size)
            start += 15
            pageIndex++
            // 反爬延迟：每页间 5-10 秒
            delay(Random.nextLong(5000, 10000))
        }
        return !cookieExpired
    }

    /** 爬取条目详情页，返回 imdbId 等补充信息 */
    suspend fun fetchDetail(doubanUrl: String, cookie: String): DoubanDetailInfo? {
        delay(Random.nextLong(3000, 5000)) // 反爬延迟 3-5 秒
        val html = fetchHtml(doubanUrl, cookie)
        if (DoubanSpider.isLoginPage(html)) return null
        return DoubanSpider.parseDetail(html)
    }

    private fun fetchHtml(url: String, cookie: String): String {
        val request = Request.Builder()
            .url(url)
            .header("User-Agent", ua)
            .header("Cookie", cookie)
            .header("Referer", "https://movie.douban.com/")
            .build()
        return client.newCall(request).execute().use { response ->
            response.body?.string() ?: ""
        }
    }
}

enum class DoubanMarkStatus(val path: String) {
    WISH("wish"),       // 想看
    COLLECT("collect")  // 看过
}
```

- [ ] **步骤 3：创建 Hilt Module**

创建 `di/DoubanModule.kt`：

```kotlin
package com.tracktosearch.di

import com.tracktosearch.data.remote.douban.DoubanRepository
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object DoubanModule {
    @Provides
    @Singleton
    fun provideDoubanRepository(): DoubanRepository = DoubanRepository()
}
```

- [ ] **步骤 4：构建验证 + Commit**

```bash
.\gradlew assembleDebug
git add app/src/main/java/com/tracktosearch/data/remote/douban/ app/src/main/java/com/tracktosearch/di/DoubanModule.kt
git commit -m "feat: 新增豆瓣爬虫 DoubanSpider + DoubanRepository"
```

---

## 任务 5：豆瓣→Trakt 同步管理器（DoubanSyncManager）

**文件：**
- 创建：`app/src/main/java/com/tracktosearch/data/repository/DoubanSyncManager.kt`

- [ ] **步骤 1：实现 DoubanSyncManager**

```kotlin
package com.tracktosearch.data.repository

import com.tracktosearch.data.local.DoubanAuthStorage
import com.tracktosearch.data.local.db.DoubanSyncedItem
import com.tracktosearch.data.local.db.DoubanSyncedItemDao
import com.tracktosearch.data.remote.douban.DoubanMarkStatus
import com.tracktosearch.data.remote.douban.DoubanRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject
import javax.inject.Singleton

data class DoubanSyncProgress(
    val isRunning: Boolean = false,
    val current: Int = 0,
    val total: Int = 0,
    val phase: String = "",        // "爬取想看" / "爬取看过" / "同步到 Trakt"
    val successCount: Int = 0,
    val failedCount: Int = 0,
    val failedItems: List<Pair<String, String>> = emptyList(), // 标题 to 失败原因
    val isComplete: Boolean = false
)

@Singleton
class DoubanSyncManager @Inject constructor(
    private val doubanRepository: DoubanRepository,
    private val doubanAuthStorage: DoubanAuthStorage,
    private val traktRepository: TraktRepository,
    private val doubanSyncedItemDao: DoubanSyncedItemDao
) {
    private val _progress = MutableStateFlow(DoubanSyncProgress())
    val progress: StateFlow<DoubanSyncProgress> = _progress.asStateFlow()

    private var cancelled = false

    fun cancel() { cancelled = true }

    suspend fun startSync(forceOverwrite: Boolean = false): DoubanSyncProgress {
        cancelled = false
        val creds = doubanAuthStorage.getCredentials()
            ?: return _progress.value.copy(isComplete = true, phase = "未登录豆瓣")

        // 加载 Trakt 已有标记缓存
        traktRepository.loadWatchlistWatchedIds()
        val watchlistWatchedIds = traktRepository.getWatchlistWatchedIds()

        // 加载已同步记录（非强制覆盖时用于跳过）
        val syncedIds = if (!forceOverwrite) doubanSyncedItemDao.getAllSyncedDoubanIds().toSet() else emptySet()

        val allFailed = mutableListOf<Pair<String, String>>()
        var totalSuccess = 0

        // 先爬「想看」再爬「看过」
        for (status in listOf(DoubanMarkStatus.WISH, DoubanMarkStatus.COLLECT)) {
            if (cancelled) break
            val phaseName = if (status == DoubanMarkStatus.WISH) "爬取想看列表" else "爬取看过列表"
            _progress.value = _progress.value.copy(isRunning = true, phase = phaseName, total = 0, current = 0)

            val pageItems = mutableListOf<DoubanMarkItem>()
            val ok = doubanRepository.fetchMarkList(creds.userId, creds.cookie, status,
                onPage = { items, _ -> pageItems.addAll(items) },
                onProgress = { cur ->
                    _progress.value = _progress.value.copy(current = cur, total = cur)
                }
            )

            if (!ok) {
                _progress.value = _progress.value.copy(phase = "豆瓣登录已过期", isComplete = true, isRunning = false)
                return _progress.value
            }

            // 逐条同步到 Trakt
            val syncPhase = if (status == DoubanMarkStatus.WISH) "同步想看到 Trakt" else "同步看过到 Trakt"
            _progress.value = _progress.value.copy(phase = syncPhase, total = pageItems.size, current = 0)

            val batchToInsert = mutableListOf<DoubanSyncedItem>()
            for ((idx, item) in pageItems.withIndex()) {
                if (cancelled) break
                _progress.value = _progress.value.copy(current = idx + 1)

                if (!forceOverwrite && item.doubanId in syncedIds) continue

                // 爬详情页拿 imdbId
                val detail = try {
                    doubanRepository.fetchDetail(item.doubanUrl, creds.cookie)
                } catch (e: Exception) {
                    allFailed.add(item.title to "详情页访问失败")
                    continue
                }
                val imdbId = detail?.imdbId
                if (imdbId.isNullOrEmpty()) {
                    allFailed.add(item.title to "无 IMDb ID")
                    continue
                }

                val mediaType = if (detail.isTvShow) MediaType.SHOW else MediaType.MOVIE

                // 用 imdbId 查 traktId
                var traktId = traktRepository.getCachedTraktIdByImdb(imdbId, mediaType)
                if (traktId == null) {
                    traktId = try {
                        traktRepository.searchByImdb(imdbId, mediaType).getOrNull()
                            ?.firstOrNull()?.let {
                                when (mediaType) {
                                    MediaType.MOVIE -> it.movie?.ids?.trakt
                                    MediaType.SHOW -> it.show?.ids?.trakt
                                    else -> null
                                }
                            }
                    } catch (e: Exception) { null }
                }
                if (traktId == null || traktId <= 0) {
                    allFailed.add(item.title to "Trakt 未找到此条目")
                    continue
                }

                // 冲突处理（豆瓣优先覆盖）
                val isInWatchlist = watchlistWatchedIds?.isInWatchlist(traktId, null, mediaType) == true
                val isWatched = watchlistWatchedIds?.isWatched(traktId, null, mediaType) == true

                try {
                    when (status) {
                        DoubanMarkStatus.WISH -> {
                            if (isWatched) {
                                // 豆瓣想看 + Trakt 看过 → 不覆盖（保留 Trakt 已看）
                            } else if (!isInWatchlist) {
                                traktRepository.addToWatchlist(traktId, mediaType, tmdbId = 0)
                            }
                        }
                        DoubanMarkStatus.COLLECT -> {
                            if (isInWatchlist) {
                                traktRepository.removeFromWatchlist(traktId, mediaType, tmdbId = 0)
                            }
                            if (!isWatched) {
                                traktRepository.markAsWatched(traktId, mediaType, tmdbId = 0)
                            }
                        }
                    }
                    // 同步评分
                    if (item.rating != null && item.rating > 0) {
                        traktRepository.addRating(traktId, item.rating, mediaType)
                    }
                    totalSuccess++
                    batchToInsert.add(
                        DoubanSyncedItem(
                            doubanId = item.doubanId,
                            imdbId = imdbId,
                            traktId = traktId,
                            title = item.title,
                            status = status.path,
                            rating = item.rating,
                            syncedAt = System.currentTimeMillis(),
                            mediaType = mediaType.name.lowercase()
                        )
                    )
                } catch (e: Exception) {
                    allFailed.add(item.title to "Trakt 写入失败: ${e.message}")
                }
            }

            if (batchToInsert.isNotEmpty()) {
                doubanSyncedItemDao.insertAll(batchToInsert)
            }
        }

        val finalProgress = DoubanSyncProgress(
            isRunning = false,
            isComplete = true,
            successCount = totalSuccess,
            failedCount = allFailed.size,
            failedItems = allFailed,
            phase = if (cancelled) "已取消" else "同步完成"
        )
        _progress.value = finalProgress
        return finalProgress
    }
}
```

> 注意：`DoubanMarkItem` 需 import。`addToWatchlist`/`markAsWatched`/`removeFromWatchlist`/`addRating` 的精确签名以 TraktRepository 现有实现为准，执行时核对参数。

- [ ] **步骤 2：构建验证 + Commit**

```bash
.\gradlew assembleDebug
git add app/src/main/java/com/tracktosearch/data/repository/DoubanSyncManager.kt
git commit -m "feat: 新增 DoubanSyncManager 豆瓣→Trakt 同步协调器"
```

---

## 任务 6：同步前台服务（DoubanSyncService）

**文件：**
- 创建：`app/src/main/java/com/tracktosearch/service/DoubanSyncService.kt`
- 修改：`app/src/main/AndroidManifest.xml`

- [ ] **步骤 1：AndroidManifest 注册 Service + 通知渠道**

在 `<application>` 内添加：
```xml
<service
    android:name=".service.DoubanSyncService"
    android:foregroundServiceType="dataSync"
    android:exported="false" />
```

确认 `POST_NOTIFICATIONS` 权限已声明（Android 13+）。

- [ ] **步骤 2：实现 DoubanSyncService**

```kotlin
package com.tracktosearch.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import com.tracktosearch.MainActivity
import com.tracktosearch.R
import com.tracktosearch.data.repository.DoubanSyncManager
import com.tracktosearch.data.repository.DoubanSyncProgress
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import javax.inject.Inject

@AndroidEntryPoint
class DoubanSyncService : Service() {

    @Inject lateinit var doubanSyncManager: DoubanSyncManager

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var syncJob: Job? = null

    companion object {
        const val CHANNEL_ID = "douban_sync"
        const val NOTIF_ID = 9001
        const val ACTION_START = "com.tracktosearch.START_DOUBAN_SYNC"
        const val ACTION_CANCEL = "com.tracktosearch.CANCEL_DOUBAN_SYNC"

        fun start(context: Context) {
            val intent = Intent(context, DoubanSyncService::class.java).setAction(ACTION_START)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }

        fun cancel(context: Context) {
            val intent = Intent(context, DoubanSyncService::class.java).setAction(ACTION_CANCEL)
            context.startService(intent)
        }
    }

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_CANCEL -> {
                doubanSyncManager.cancel()
                stopSelf()
                return START_NOT_STICKY
            }
            ACTION_START -> {
                startForeground(NOTIF_ID, buildNotification(0, 0, "准备同步..."))
                syncJob = scope.launch {
                    launch { collectProgress() }
                    doubanSyncManager.startSync()
                    stopSelf()
                }
            }
        }
        return START_STICKY
    }

    private suspend fun collectProgress() {
        doubanSyncManager.progress.collectLatest { p: DoubanSyncProgress ->
            if (p.isRunning) {
                val notif = buildNotification(p.current, p.total, p.phase)
                getSystemService(NotificationManager::class.java).notify(NOTIF_ID, notif)
            }
        }
    }

    private fun buildNotification(current: Int, total: Int, phase: String): Notification {
        val contentIntent = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val cancelIntent = PendingIntent.getService(
            this, 1,
            Intent(this, DoubanSyncService::class.java).setAction(ACTION_CANCEL),
            PendingIntent.FLAG_IMMUTABLE
        )

        val builder = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle("豆瓣标记同步")
            .setContentText("$phase ($current/$total)")
            .setContentIntent(contentIntent)
            .addAction(R.drawable.ic_close, "取消", cancelIntent)
            .setOngoing(true)

        if (total > 0) {
            builder.setProgress(total, current, false)
        } else {
            builder.setProgress(0, 0, true)
        }
        return builder.build()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID, "豆瓣同步", NotificationManager.IMPORTANCE_LOW
            ).apply { description = "豆瓣标记同步到 Trakt 的进度通知" }
            getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        syncJob?.cancel()
        scope.cancel()
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
```

> 注意：`R.drawable.ic_notification` 和 `R.drawable.ic_close` 需确认是否存在，若不存在用 `android.R.drawable.stat_sys_download` 和 `android.R.drawable.ic_menu_close_clear_cancel` 替代。

- [ ] **步骤 3：构建验证 + Commit**

```bash
.\gradlew assembleDebug
git add app/src/main/AndroidManifest.xml app/src/main/java/com/tracktosearch/service/DoubanSyncService.kt
git commit -m "feat: 新增 DoubanSyncService 前台同步服务 + 通知栏进度"
```

---

## 任务 7：同步进度对话框（DoubanSyncDialog）

**文件：**
- 创建：`app/src/main/java/com/tracktosearch/ui/screen/douban/DoubanSyncDialog.kt`

- [ ] **步骤 1：实现 DoubanSyncDialog**

```kotlin
package com.tracktosearch.ui.screen.douban

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.tracktosearch.R
import com.tracktosearch.data.repository.DoubanSyncManager
import com.tracktosearch.service.DoubanSyncService
import androidx.compose.ui.platform.LocalContext
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class DoubanSyncViewModel @Inject constructor(
    val doubanSyncManager: DoubanSyncManager
) : androidx.lifecycle.ViewModel() {

    val progress = doubanSyncManager.progress

    fun startSync() {
        viewModelScope.launch { doubanSyncManager.startSync() }
    }

    fun moveToBackground() {
        // Service 会接管协程，这里只需通知 UI 关闭对话框
    }

    fun cancel() {
        doubanSyncManager.cancel()
    }
}

@Composable
fun DoubanSyncDialog(
    onDismiss: () -> Unit,
    viewModel: DoubanSyncViewModel = hiltViewModel()
) {
    val context = LocalContext.current
    val progress by viewModel.progress.collectAsStateWithLifecycle()
    val p = progress

    AlertDialog(
        onDismissRequest = {
            if (!p.isRunning) onDismiss()
        },
        title = { Text("豆瓣标记同步") },
        text = {
            Column {
                Text("${p.phase} (${p.current}/${p.total})")
                Spacer(modifier = Modifier.height(12.dp))
                if (p.total > 0) {
                    LinearProgressIndicator(
                        progress = { p.current.toFloat() / p.total },
                        modifier = Modifier.fillMaxWidth()
                    )
                } else if (p.isRunning) {
                    LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                }
                if (p.isComplete) {
                    Spacer(modifier = Modifier.height(12.dp))
                    Text("成功 ${p.successCount} 条，失败 ${p.failedCount} 条")
                    if (p.failedItems.isNotEmpty()) {
                        Spacer(modifier = Modifier.height(8.dp))
                        Text("失败项：", style = MaterialTheme.typography.labelMedium)
                        p.failedItems.take(10).forEach { (title, reason) ->
                            Text("• $title：$reason", style = MaterialTheme.typography.bodySmall)
                        }
                        if (p.failedItems.size > 10) {
                            Text("...共 ${p.failedItems.size} 项失败", style = MaterialTheme.typography.bodySmall)
                        }
                    }
                }
            }
        },
        confirmButton = {
            when {
                p.isComplete -> {
                    TextButton(onClick = onDismiss) { Text("完成") }
                }
                p.isRunning -> {
                    Row {
                        TextButton(onClick = {
                            DoubanSyncService.start(context)
                            onDismiss()
                        }) { Text("转后台") }
                        Spacer(modifier = Modifier.width(8.dp))
                        TextButton(onClick = { viewModel.cancel() }) { Text("取消") }
                    }
                }
            }
        }
    )
}
```

- [ ] **步骤 2：构建验证 + Commit**

```bash
.\gradlew assembleDebug
git add app/src/main/java/com/tracktosearch/ui/screen/douban/DoubanSyncDialog.kt
git commit -m "feat: 新增 DoubanSyncDialog 前台同步进度对话框"
```

---

## 任务 8：豆瓣 WebView 登录页（DoubanLoginScreen）

**文件：**
- 创建：`app/src/main/java/com/tracktosearch/ui/screen/douban/DoubanLoginScreen.kt`

- [ ] **步骤 1：实现 DoubanLoginScreen**

```kotlin
package com.tracktosearch.ui.screen.douban

import android.annotation.SuppressLint
import android.graphics.Bitmap
import android.webkit.CookieManager
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.tracktosearch.R
import com.tracktosearch.data.local.DoubanAuthStorage
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class DoubanLoginViewModel @Inject constructor(
    val doubanAuthStorage: DoubanAuthStorage,
    val doubanSyncManager: com.tracktosearch.data.repository.DoubanSyncManager
) : ViewModel() {

    private val _loginSuccess = MutableStateFlow(false)
    val loginSuccess: StateFlow<Boolean> = _loginSuccess

    fun onLoginSuccess(userId: String, cookie: String) {
        doubanAuthStorage.saveCredentials(userId, cookie)
        _loginSuccess.value = true
        // 首次登录自动触发同步
        viewModelScope.launch { doubanSyncManager.startSync() }
    }
}

@SuppressLint("SetJavaScriptEnabled")
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DoubanLoginScreen(
    onBack: () -> Unit,
    onSyncStart: () -> Unit,
    viewModel: DoubanLoginViewModel = hiltViewModel()
) {
    val loginSuccess by viewModel.loginSuccess.collectAsStateWithLifecycle()

    LaunchedEffect(loginSuccess) {
        if (loginSuccess) onSyncStart()
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("登录豆瓣") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                    }
                }
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
        ) {
            Text(
                text = "请在下方登录豆瓣账号，登录成功后将自动同步您的标记到 Trakt",
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.padding(16.dp)
            )
            AndroidView(
                factory = { ctx ->
                    WebView(ctx).apply {
                        settings.javaScriptEnabled = true
                        settings.domStorageEnabled = true
                        CookieManager.getInstance().setAcceptCookie(true)
                        CookieManager.getInstance().setAcceptThirdPartyCookies(this, true)
                        webViewClient = object : WebViewClient() {
                            override fun onPageFinished(view: WebView?, url: String?) {
                                super.onPageFinished(view, url)
                                val cookie = CookieManager.getInstance().getCookie("https://movie.douban.com")
                                if (cookie != null) {
                                    // 从 dbcl2 cookie 解析 userId
                                    val dbcl2 = cookie.split(";")
                                        .map { it.trim() }
                                        .firstOrNull { it.startsWith("dbcl2=") }
                                    if (dbcl2 != null) {
                                        val value = dbcl2.removePrefix("dbcl2=")
                                        // dbcl2="userid:xxxxx" 格式，取冒号前
                                        val userId = value.trim('"').split(":").firstOrNull()
                                        if (!userId.isNullOrEmpty()) {
                                            viewModel.onLoginSuccess(userId, cookie)
                                        }
                                    }
                                }
                            }
                        }
                        loadUrl("https://www.douban.com/")
                    }
                },
                modifier = Modifier.fillMaxSize()
            )
        }
    }
}
```

- [ ] **步骤 2：构建验证 + Commit**

```bash
.\gradlew assembleDebug
git add app/src/main/java/com/tracktosearch/ui/screen/douban/DoubanLoginScreen.kt
git commit -m "feat: 新增 DoubanLoginScreen WebView 登录页"
```

---

## 任务 9：登录页改造 + 设置页入口 + 导航 + 字符串

**文件：**
- 修改：`app/src/main/java/com/tracktosearch/ui/screen/login/LoginScreen.kt`
- 修改：`app/src/main/java/com/tracktosearch/ui/screen/settings/SettingsScreen.kt`
- 修改：`app/src/main/java/com/tracktosearch/ui/screen/settings/SettingsViewModel.kt`
- 修改：`app/src/main/java/com/tracktosearch/ui/navigation/AppNavigation.kt`
- 修改：4 个 `strings.xml`

- [ ] **步骤 1：添加字符串资源**

在 `res/values/strings.xml`、`res/values-zh/strings.xml`、`res/values-ja/strings.xml`、`res/values-ko/strings.xml` 中添加：

```xml
<string name="login_douban_import">或从豆瓣导入标记</string>
<string name="douban_login_title">登录豆瓣</string>
<string name="douban_login_hint">请在下方登录豆瓣账号，登录成功后将自动同步您的标记到 Trakt</string>
<string name="settings_douban_resync">从豆瓣重新导入</string>
<string name="settings_douban_resync_desc">重新同步豆瓣标记到 Trakt</string>
<string name="douban_sync_title">豆瓣标记同步</string>
<string name="douban_sync_complete">同步完成</string>
<string name="douban_sync_cancel">取消</string>
<string name="douban_sync_background">转后台</string>
```

4 种语言对应翻译：
- 中文：如上
- 英文：`Or import from Douban` / `Login to Douban` / `Login to your Douban account below. Your marks will be synced to Trakt automatically.` / `Re-import from Douban` / `Re-sync Douban marks to Trakt` / `Douban Marks Sync` / `Sync Complete` / `Cancel` / `Background`
- 日文：`または豆瓣からインポート` / `豆瓣にログイン` / `下で豆瓣アカウントにログインすると、マークがTraktに自動同期されます` / `豆瓣から再インポート` / `豆瓣マークをTraktに再同期` / `豆瓣マーク同期` / `同期完了` / `キャンセル` / `バックグラウンド`
- 韩文：`또는 더우반에서 가져오기` / `더우반 로그인` / `아래에서 더우반 계정에 로그인하면 마크가 Trakt에 자동 동기화됩니다` / `더우반에서 다시 가져오기` / `더우반 마크를 Trakt에 재동기화` / `더우반 마크 동기화` / `동기화 완료` / `취소` / `백그라운드`

- [ ] **步骤 2：LoginScreen 添加豆瓣入口**

在 `LoginScreen.kt` 的 `TextButton(onClick = onGuestMode)` 上方添加：

```kotlin
Spacer(modifier = Modifier.height(16.dp))

TextButton(
    onClick = onDoubanImport,
    modifier = Modifier.fillMaxWidth()
) {
    Text(
        text = stringResource(R.string.login_douban_import),
        style = MaterialTheme.typography.bodyLarge
    )
}
```

并在 `LoginScreen` 函数签名添加 `onDoubanImport: () -> Unit = {}` 参数。

- [ ] **步骤 3：AppNavigation 注册豆瓣路由**

在 `Routes` object 添加：
```kotlin
const val DOUBAN_LOGIN = "doubanLogin"
```

在 `NavHost` 中添加：
```kotlin
composable(Routes.DOUBAN_LOGIN) {
    CompositionLocalProvider(LocalAnimatedVisibilityScope provides this@composable) {
        DoubanLoginScreen(
            onBack = { navController.popBackStack() },
            onSyncStart = {
                // 导航回 login 或直接进 main，并弹出同步对话框
                navController.navigate(Routes.LOGIN) {
                    popUpTo(Routes.LOGIN) { inclusive = true }
                }
            }
        )
    }
}
```

并在 `LoginScreen` 的 `composable` 块中传入 `onDoubanImport = { navController.navigate(Routes.DOUBAN_LOGIN) }`。

同步对话框状态管理：在 `LoginScreen` 中用 `var showSyncDialog by remember { mutableStateOf(false) }`，当 `loginSuccess` 触发时设为 true，显示 `DoubanSyncDialog`。

- [ ] **步骤 4：SettingsScreen 添加豆瓣重新导入入口**

在 `SettingsScreen.kt` 的数据管理 section（IMDb 导入入口附近）添加：

```kotlin
item {
    val doubanAuthStorage = (viewModel as? SettingsViewModel)?.let {
        // 通过 ViewModel 暴露豆瓣登录状态
    }
    SettingsItem(
        icon = Icons.Default.CloudDownload,
        title = stringResource(R.string.settings_douban_resync),
        subtitle = stringResource(R.string.settings_douban_resync_desc),
        onClick = { onDoubanResync() }
    )
}
```

在 `SettingsScreen` 函数签名添加 `onDoubanResync: () -> Unit = {}` 参数。在 `AppNavigation` 的 `composable(Routes.SETTINGS)` 中传入 `onDoubanResync = { navController.navigate(Routes.DOUBAN_LOGIN) }`。

> 注意：若用户已登录豆瓣，重新导入应直接弹同步对话框而非重新登录。执行时根据 `DoubanAuthStorage.isLoggedIn` 判断。

- [ ] **步骤 5：构建验证 + Commit**

```bash
.\gradlew assembleDebug
git add app/src/main/res/ app/src/main/java/com/tracktosearch/ui/screen/login/ app/src/main/java/com/tracktosearch/ui/screen/settings/ app/src/main/java/com/tracktosearch/ui/navigation/
git commit -m "feat: 登录页添加豆瓣导入入口 + 设置页重新导入 + 导航路由 + 多语言字符串"
```

---

## 任务 10：端到端集成验证

- [ ] **步骤 1：完整构建验证**

```bash
.\gradlew assembleDebug
```
预期：BUILD SUCCESSFUL

- [ ] **步骤 2：手动功能验证清单**

用小规模豆瓣账号（10-20 条标记）验证：
1. 登录页点击「或从豆瓣导入标记」→ 进入豆瓣 WebView 登录页
2. WebView 内登录豆瓣 → 自动检测 dbcl2 cookie → 触发同步对话框
3. 同步对话框显示进度（当前/总数 + 进度条）
4. 点击「转后台」→ 对话框关闭，通知栏显示进度
5. 点击通知「取消」→ 同步中断
6. 同步完成后对话框显示成功/失败计数
7. 进入 watchlist 页，确认豆瓣标记已合并到 Trakt 列表
8. 设置页点击「从豆瓣重新导入」→ 重新触发同步

- [ ] **步骤 3：更新帮助与说明页**

在 `HelpScreen.kt` 添加豆瓣同步功能的说明条目，4 种语言同步。

- [ ] **步骤 4：最终 Commit**

```bash
git add app/src/main/java/com/tracktosearch/ui/screen/help/ app/src/main/res/
git commit -m "feat: 豆瓣→Trakt 同步功能完成 + 帮助页更新"
```

---

## 自检

**规格覆盖度：**
- ✅ 豆瓣 WebView 登录（任务 8）
- ✅ Cookie + userId 抓取（任务 8）
- ✅ 加密存储（任务 2）
- ✅ 豆瓣爬虫 + Jsoup 解析（任务 4）
- ✅ 详情页 imdbId 爬取（任务 4）
- ✅ Trakt searchByImdb 反查（任务 3）
- ✅ 豆瓣优先覆盖冲突处理（任务 5）
- ✅ 同步状态库 Room（任务 1）
- ✅ 前台对话框 + 转后台 Service（任务 6、7）
- ✅ 登录页改造（任务 9）
- ✅ 设置页重新导入入口（任务 9）
- ✅ 评分同步（任务 5）
- ✅ 错误处理/失败列表（任务 5、7）

**类型一致性：**
- `DoubanMarkItem` / `DoubanDetailInfo` 在任务 4 定义，任务 5 引用 ✅
- `DoubanSyncProgress` 在任务 5 定义，任务 6、7 引用 ✅
- `DoubanAuthStorage.getCredentials()` 返回 `DoubanCredentials`，任务 5 引用 ✅
- `TraktRepository.addToWatchlist/markAsWatched/removeFromWatchlist/addRating` 签名以现有实现为准，任务 5 标注了核对要求 ✅

**占位符扫描：** 无 TODO/待定。任务 9 步骤 4 的「执行时根据 isLoggedIn 判断」是条件分支说明，非占位符。

**范围检查：** 10 个任务覆盖完整功能，可在单个计划内完成。
