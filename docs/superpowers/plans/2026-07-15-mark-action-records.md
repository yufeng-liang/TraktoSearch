# 标记记录模块 实现计划

> **面向 AI 代理的工作者：** 必需子技能：使用 superpowers:subagent-driven-development（推荐）或 superpowers:executing-plans 逐任务实现此计划。步骤使用复选框（`- [ ]`）语法来跟踪进度。

**目标：** 新增「标记记录」模块，支持按操作类型（想看/已看/移除）、日期范围、媒体类型筛选，记录包含影视快照信息、操作时间、操作类型；点击跳转详情页

**架构：** 双来源策略——自建 Room 流水表记录想看/移除/取消已看操作，已看记录走 Trakt `/sync/history` API 分页拉取（1 小时进程内缓存）。UI 仿 `DoubanFailuresScreen` 的 Tab+筛选+搜索+空状态模式，列表项带「当前状态徽标」实时反映影视的最新标记状态。

**技术栈：** Kotlin + Jetpack Compose + Hilt + Room + Coroutines/Flow + Retrofit

**规格文档：** [docs/superpowers/specs/2026-07-15-mark-action-records-design.md](file:///f:/trae-project/docs/superpowers/specs/2026-07-15-mark-action-records-design.md)

---

## 文件结构

### 新建文件

| 文件 | 职责 |
|---|---|
| `app/src/main/java/com/tracktosearch/data/local/db/MarkActionRecordEntity.kt` | 流水表 Entity + 枚举 `MarkActionType` |
| `app/src/main/java/com/tracktosearch/data/local/db/MarkActionRecordDao.kt` | 流水表 DAO |
| `app/src/main/java/com/tracktosearch/data/remote/trakt/dto/TraktHistoryDtos.kt` | Trakt `/sync/history` 响应 DTO（movie/episode 两种） |
| `app/src/main/java/com/tracktosearch/ui/screen/markrecord/MarkRecordViewModel.kt` | ViewModel + UiState + 枚举 |
| `app/src/main/java/com/tracktosearch/ui/screen/markrecord/MarkRecordScreen.kt` | 主界面 |
| `app/src/main/java/com/tracktosearch/ui/screen/markrecord/MarkRecordComponents.kt` | 列表项、筛选弹窗、当前状态徽标等组件 |
| `app/src/test/java/com/tracktosearch/data/local/db/MarkActionRecordDaoTest.kt` | DAO 测试 |
| `app/src/test/java/com/tracktosearch/ui/screen/markrecord/MarkRecordViewModelTest.kt` | ViewModel 测试 |

### 修改文件

| 文件 | 改动 |
|---|---|
| `app/src/main/java/com/tracktosearch/data/local/db/AppDatabase.kt` | version 10→11，注册 Entity + DAO |
| `app/src/main/java/com/tracktosearch/data/local/db/DatabaseModule.kt` | 新增 `MIGRATION_10_11` + `provideMarkActionRecordDao` |
| `app/src/main/java/com/tracktosearch/data/remote/trakt/TraktApiService.kt` | 新增 `getEpisodeHistory` 方法 |
| `app/src/main/java/com/tracktosearch/data/repository/TraktRepository.kt` | 4 个操作方法成功分支后写流水；新增 `fetchWatchHistory` 方法；`unmarkEpisodeWatched` 改签名 |
| `app/src/main/java/com/tracktosearch/ui/screen/detail/DetailViewModel.kt` | `unmarkEpisodeWatched` 调用处补传季集信息 |
| `app/src/main/java/com/tracktosearch/ui/navigation/AppNavigation.kt` | 新增 `Routes.MARK_RECORDS` + `composable` + SettingsScreen 入口回调 |
| `app/src/main/java/com/tracktosearch/ui/screen/settings/SettingsScreen.kt` | 新增 `onMarkRecordsClick` 参数 + `mark_records_entry` 卡片 |
| `app/src/main/java/com/tracktosearch/ui/screen/help/HelpScreen.kt` | 帮助页新增「标记记录」说明 |
| `app/src/main/res/values/strings.xml` | 英文字符串 |
| `app/src/main/res/values-zh/strings.xml` | 中文字符串 |
| `app/src/main/res/values-ja/strings.xml` | 日文字符串 |
| `app/src/main/res/values-ko/strings.xml` | 韩文字符串 |

---

## 任务分解

### 任务 1：流水表 Entity + 枚举

**文件：**
- 创建：`app/src/main/java/com/tracktosearch/data/local/db/MarkActionRecordEntity.kt`

- [ ] **步骤 1：创建 Entity 和枚举**

```kotlin
package com.tracktosearch.data.local.db

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * 标记操作流水实体。
 * 记录 App 内发生的「加想看/移除想看/取消已看」操作（已看不记录，走 Trakt /sync/history）。
 */
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
    val title: String,             // 快照，避免缓存失效时列表变空白
    val displayTitle: String,
    val posterUrl: String?,
    val year: Int?,
    val actionType: String,        // ADD_WATCHLIST / REMOVE_WATCHLIST / UNMARK_WATCHED
    val actedAt: Long,             // 操作时间戳（毫秒）
    val episodeInfo: String?       // "S01E03" / "S01E03-E05"，仅取消单集已看时填
)

/** 操作类型枚举 */
enum class MarkActionType(val value: String) {
    ADD_WATCHLIST("ADD_WATCHLIST"),
    REMOVE_WATCHLIST("REMOVE_WATCHLIST"),
    UNMARK_WATCHED("UNMARK_WATCHED");

    companion object {
        fun fromValue(v: String) = entries.firstOrNull { it.value == v }
    }
}
```

- [ ] **步骤 2：Commit**

```bash
git add app/src/main/java/com/tracktosearch/data/local/db/MarkActionRecordEntity.kt
git commit -m "feat: 新增标记记录流水表 Entity"
```

---

### 任务 2：流水表 DAO

**文件：**
- 创建：`app/src/main/java/com/tracktosearch/data/local/db/MarkActionRecordDao.kt`
- 测试：`app/src/test/java/com/tracktosearch/data/local/db/MarkActionRecordDaoTest.kt`

- [ ] **步骤 1：编写失败的 DAO 测试**

```kotlin
package com.tracktosearch.data.local.db

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class MarkActionRecordDaoTest {

    private lateinit var db: AppDatabase
    private lateinit var dao: MarkActionRecordDao

    @Before
    fun setup() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        dao = db.markActionRecordDao()
    }

    @After
    fun teardown() { db.close() }

    private fun sampleRecord(
        actionType: String = MarkActionType.ADD_WATCHLIST.value,
        mediaType: String = "movie",
        traktId: Int = 1,
        title: String = "Test Movie",
        actedAt: Long = 1000L
    ) = MarkActionRecordEntity(
        traktId = traktId, tmdbId = 10, imdbId = "tt1",
        mediaType = mediaType, title = title, displayTitle = title,
        posterUrl = null, year = 2024, actionType = actionType,
        actedAt = actedAt, episodeInfo = null
    )

    @Test
    fun insert_and_query_all() = runTest {
        dao.insert(sampleRecord(traktId = 1, actedAt = 1000L))
        dao.insert(sampleRecord(traktId = 2, actedAt = 2000L))
        val result = dao.query(
            actionTypes = emptyList(), actionTypesEmpty = true,
            mediaTypes = emptyList(), mediaTypesEmpty = true,
            startTime = 0, endTime = 0, titleQuery = null,
            ascending = false, limit = 50, offset = 0
        )
        assertThat(result).hasSize(2)
        // 倒序：actedAt=2000 在前
        assertThat(result[0].traktId).isEqualTo(2)
    }

    @Test
    fun query_filter_by_actionType() = runTest {
        dao.insert(sampleRecord(actionType = MarkActionType.ADD_WATCHLIST.value, traktId = 1))
        dao.insert(sampleRecord(actionType = MarkActionType.REMOVE_WATCHLIST.value, traktId = 2))
        val result = dao.query(
            actionTypes = listOf(MarkActionType.REMOVE_WATCHLIST.value), actionTypesEmpty = false,
            mediaTypes = emptyList(), mediaTypesEmpty = true,
            startTime = 0, endTime = 0, titleQuery = null,
            ascending = false, limit = 50, offset = 0
        )
        assertThat(result).hasSize(1)
        assertThat(result[0].actionType).isEqualTo(MarkActionType.REMOVE_WATCHLIST.value)
    }

    @Test
    fun query_filter_by_mediaType() = runTest {
        dao.insert(sampleRecord(mediaType = "movie", traktId = 1))
        dao.insert(sampleRecord(mediaType = "show", traktId = 2))
        val result = dao.query(
            actionTypes = emptyList(), actionTypesEmpty = true,
            mediaTypes = listOf("show"), mediaTypesEmpty = false,
            startTime = 0, endTime = 0, titleQuery = null,
            ascending = false, limit = 50, offset = 0
        )
        assertThat(result).hasSize(1)
        assertThat(result[0].mediaType).isEqualTo("show")
    }

    @Test
    fun query_filter_by_time_range() = runTest {
        dao.insert(sampleRecord(traktId = 1, actedAt = 1000L))
        dao.insert(sampleRecord(traktId = 2, actedAt = 2000L))
        dao.insert(sampleRecord(traktId = 3, actedAt = 3000L))
        val result = dao.query(
            actionTypes = emptyList(), actionTypesEmpty = true,
            mediaTypes = emptyList(), mediaTypesEmpty = true,
            startTime = 1500, endTime = 2500, titleQuery = null,
            ascending = false, limit = 50, offset = 0
        )
        assertThat(result).hasSize(1)
        assertThat(result[0].traktId).isEqualTo(2)
    }

    @Test
    fun query_filter_by_title() = runTest {
        dao.insert(sampleRecord(title = "Inception", traktId = 1))
        dao.insert(sampleRecord(title = "Interstellar", traktId = 2))
        val result = dao.query(
            actionTypes = emptyList(), actionTypesEmpty = true,
            mediaTypes = emptyList(), mediaTypesEmpty = true,
            startTime = 0, endTime = 0, titleQuery = "%Incep%",
            ascending = false, limit = 50, offset = 0
        )
        assertThat(result).hasSize(1)
        assertThat(result[0].title).isEqualTo("Inception")
    }

    @Test
    fun query_pagination() = runTest {
        repeat(5) { i -> dao.insert(sampleRecord(traktId = i + 1, actedAt = (i + 1) * 1000L)) }
        val page1 = dao.query(
            actionTypes = emptyList(), actionTypesEmpty = true,
            mediaTypes = emptyList(), mediaTypesEmpty = true,
            startTime = 0, endTime = 0, titleQuery = null,
            ascending = false, limit = 2, offset = 0
        )
        val page2 = dao.query(
            actionTypes = emptyList(), actionTypesEmpty = true,
            mediaTypes = emptyList(), mediaTypesEmpty = true,
            startTime = 0, endTime = 0, titleQuery = null,
            ascending = false, limit = 2, offset = 2
        )
        assertThat(page1).hasSize(2)
        assertThat(page2).hasSize(2)
        assertThat(page1[0].traktId).isEqualTo(5)  // 倒序最新
        assertThat(page2[1].traktId).isEqualTo(1)  // 最旧
    }

    @Test
    fun query_ascending() = runTest {
        dao.insert(sampleRecord(traktId = 1, actedAt = 1000L))
        dao.insert(sampleRecord(traktId = 2, actedAt = 2000L))
        val result = dao.query(
            actionTypes = emptyList(), actionTypesEmpty = true,
            mediaTypes = emptyList(), mediaTypesEmpty = true,
            startTime = 0, endTime = 0, titleQuery = null,
            ascending = true, limit = 50, offset = 0
        )
        assertThat(result[0].traktId).isEqualTo(1)  // 正序最旧在前
    }

    @Test
    fun count_and_deleteOldest() = runTest {
        repeat(3) { i -> dao.insert(sampleRecord(traktId = i + 1, actedAt = (i + 1) * 1000L)) }
        assertThat(dao.count()).isEqualTo(3)
        dao.deleteOldest(1)
        assertThat(dao.count()).isEqualTo(2)
        // 删的是最旧的（actedAt=1000，traktId=1）
        val all = dao.query(
            actionTypes = emptyList(), actionTypesEmpty = true,
            mediaTypes = emptyList(), mediaTypesEmpty = true,
            startTime = 0, endTime = 0, titleQuery = null,
            ascending = true, limit = 50, offset = 0
        )
        assertThat(all.map { it.traktId }).doesNotContain(1)
    }

    @Test
    fun deleteAll() = runTest {
        dao.insert(sampleRecord(traktId = 1))
        dao.insert(sampleRecord(traktId = 2))
        dao.deleteAll()
        assertThat(dao.count()).isEqualTo(0)
    }
}
```

- [ ] **步骤 2：运行测试验证失败**

运行：`.\gradlew testDebugUnitTest --tests "com.tracktosearch.data.local.db.MarkActionRecordDaoTest" -i"`
预期：编译失败，`MarkActionRecordDao` 和 `markActionRecordDao()` 未定义

- [ ] **步骤 3：实现 DAO**

创建 `app/src/main/java/com/tracktosearch/data/local/db/MarkActionRecordDao.kt`：

```kotlin
package com.tracktosearch.data.local.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query

@Dao
interface MarkActionRecordDao {
    @Insert
    suspend fun insert(record: MarkActionRecordEntity)

    @Insert
    suspend fun insertAll(records: List<MarkActionRecordEntity>)

    /**
     * 分页查询，支持按操作类型/媒体类型/时间范围/标题模糊匹配
     * @param actionTypes 操作类型集合，空则不限（actionTypesEmpty 必须同步传 true）
     * @param mediaTypes 媒体类型集合，空则不限
     * @param startTime 起始时间戳（含），0 则不限
     * @param endTime 结束时间戳（含），0 则不限
     * @param titleQuery 标题模糊匹配（已加 %），null 则不限
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

- [ ] **步骤 4：在 AppDatabase 注册 Entity 和 DAO**

修改 `app/src/main/java/com/tracktosearch/data/local/db/AppDatabase.kt`：

```kotlin
@Database(
    entities = [MediaItemEntity::class, MediaDetailEntity::class, NotificationRecordEntity::class, DoubanSyncedItem::class, DoubanSyncFailureEntity::class, DoubanSyncPendingItemEntity::class, DoubanSyncRollbackEntity::class, UserReviewEntity::class, MarkActionRecordEntity::class],
    version = 11,
    exportSchema = false
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun mediaItemDao(): MediaItemDao
    abstract fun mediaDetailDao(): MediaDetailDao
    abstract fun notificationRecordDao(): NotificationRecordDao
    abstract fun doubanSyncedItemDao(): DoubanSyncedItemDao
    abstract fun doubanSyncFailureDao(): DoubanSyncFailureDao
    abstract fun doubanSyncPendingItemDao(): DoubanSyncPendingItemDao
    abstract fun doubanSyncRollbackDao(): DoubanSyncRollbackDao
    abstract fun userReviewDao(): UserReviewDao
    abstract fun markActionRecordDao(): MarkActionRecordDao
}
```

- [ ] **步骤 5：在 DatabaseModule 添加 Migration 和 Provider**

修改 `app/src/main/java/com/tracktosearch/data/local/db/DatabaseModule.kt`，在 `MIGRATION_9_10` 后追加：

```kotlin
    private val MIGRATION_10_11 = object : Migration(10, 11) {
        override fun migrate(db: SupportSQLiteDatabase) {
            // v10 → v11: 新增 mark_action_record 表（App 内标记操作流水）
            db.execSQL(
                """CREATE TABLE IF NOT EXISTS mark_action_record (
                    id INTEGER NOT NULL PRIMARY KEY AUTOINCREMENT,
                    traktId INTEGER NOT NULL,
                    tmdbId INTEGER NOT NULL,
                    imdbId TEXT NOT NULL,
                    mediaType TEXT NOT NULL,
                    title TEXT NOT NULL,
                    displayTitle TEXT NOT NULL,
                    posterUrl TEXT,
                    year INTEGER,
                    actionType TEXT NOT NULL,
                    actedAt INTEGER NOT NULL,
                    episodeInfo TEXT
                )""".trimIndent()
            )
            db.execSQL("CREATE INDEX IF NOT EXISTS index_mark_action_record_actionType ON mark_action_record(actionType)")
            db.execSQL("CREATE INDEX IF NOT EXISTS index_mark_action_record_actedAt ON mark_action_record(actedAt)")
            db.execSQL("CREATE INDEX IF NOT EXISTS index_mark_action_record_mediaType ON mark_action_record(mediaType)")
            db.execSQL("CREATE INDEX IF NOT EXISTS index_mark_action_record_traktId ON mark_action_record(traktId)")
            db.execSQL("CREATE INDEX IF NOT EXISTS index_mark_action_record_actionType_actedAt ON mark_action_record(actionType, actedAt)")
            db.execSQL("CREATE INDEX IF NOT EXISTS index_mark_action_record_mediaType_actedAt ON mark_action_record(mediaType, actedAt)")
            db.execSQL("CREATE INDEX IF NOT EXISTS index_mark_action_record_actionType_mediaType_actedAt ON mark_action_record(actionType, mediaType, actedAt)")
        }
    }
```

并在 `provideAppDatabase` 的 `.addMigrations(...)` 链末尾追加 `MIGRATION_10_11`：

```kotlin
            .addMigrations(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4, MIGRATION_4_5, MIGRATION_5_6, MIGRATION_6_7, MIGRATION_7_8, MIGRATION_8_9, MIGRATION_9_10, MIGRATION_10_11)
```

在文件末尾追加 Provider：

```kotlin
    @Provides
    @Singleton
    fun provideMarkActionRecordDao(db: AppDatabase): MarkActionRecordDao = db.markActionRecordDao()
```

- [ ] **步骤 6：运行测试验证通过**

运行：`.\gradlew testDebugUnitTest --tests "com.tracktosearch.data.local.db.MarkActionRecordDaoTest" -i"`
预期：9 个测试全部 PASS

- [ ] **步骤 7：Commit**

```bash
git add app/src/main/java/com/tracktosearch/data/local/db/MarkActionRecordDao.kt app/src/main/java/com/tracktosearch/data/local/db/AppDatabase.kt app/src/main/java/com/tracktosearch/data/local/db/DatabaseModule.kt app/src/test/java/com/tracktosearch/data/local/db/MarkActionRecordDaoTest.kt
git commit -m "feat: 新增标记记录 DAO + 数据库迁移 v10→v11"
```

---

### 任务 3：Trakt /sync/history DTO 和 API

**文件：**
- 创建：`app/src/main/java/com/tracktosearch/data/remote/trakt/dto/TraktHistoryDtos.kt`
- 修改：`app/src/main/java/com/tracktosearch/data/remote/trakt/TraktApiService.kt`

- [ ] **步骤 1：创建 history 响应 DTO**

创建 `app/src/main/java/com/tracktosearch/data/remote/trakt/dto/TraktHistoryDtos.kt`：

```kotlin
package com.tracktosearch.data.remote.trakt.dto

import kotlinx.serialization.Serializable

/**
 * Trakt /sync/history 响应项。
 * 一条记录可能对应一部电影（type=movie）或一集（type=episode，内含 show 信息）。
 */
@Serializable
data class TraktHistoryEntry(
    val id: Long = 0,
    val watched_at: String? = null,
    val type: String = "",            // "movie" / "episode"
    val movie: TraktHistoryMovie? = null,
    val episode: TraktHistoryEpisode? = null,
    val show: TraktHistoryShow? = null
)

@Serializable
data class TraktHistoryMovie(
    val title: String = "",
    val year: Int? = null,
    val ids: TraktHistoryIds = TraktHistoryIds()
)

@Serializable
data class TraktHistoryShow(
    val title: String = "",
    val year: Int? = null,
    val ids: TraktHistoryIds = TraktHistoryIds()
)

@Serializable
data class TraktHistoryEpisode(
    val season: Int = 0,
    val number: Int = 0,
    val title: String = "",
    val ids: TraktHistoryIds = TraktHistoryIds()
)

@Serializable
data class TraktHistoryIds(
    val trakt: Int = 0,
    val tmdb: Int = 0,
    val imdb: String = ""
)
```

- [ ] **步骤 2：在 TraktApiService 新增 episode history 方法**

修改 `app/src/main/java/com/tracktosearch/data/remote/trakt/TraktApiService.kt`，在 `getShowHistory` 后追加：

```kotlin
    @GET("sync/history")
    suspend fun getEpisodeHistory(
        @Query("type") type: String = "episodes",
        @Query("extended") extended: String = "full",
        @Query("page") page: Int = 1,
        @Query("limit") limit: Int = 100
    ): Response<List<TraktHistoryEntry>>
```

- [ ] **步骤 3：Commit**

```bash
git add app/src/main/java/com/tracktosearch/data/remote/trakt/dto/TraktHistoryDtos.kt app/src/main/java/com/tracktosearch/data/remote/trakt/TraktApiService.kt
git commit -m "feat: 新增 Trakt /sync/history DTO 和 episode history API"
```

---

### 任务 4：TraktRepository 写入流水 + fetchWatchHistory

**文件：**
- 修改：`app/src/main/java/com/tracktosearch/data/repository/TraktRepository.kt`

- [ ] **步骤 1：注入 MarkActionRecordDao 并新增 fetchWatchHistory**

在 `TraktRepository` 类的构造函数参数中追加（保持现有其他依赖不变）：

```kotlin
private val markActionRecordDao: MarkActionRecordDao
```

在类中新增常量（companion object 内）：

```kotlin
private const val TTL_WATCH_HISTORY = 60 * 60 * 1000L  // 已看历史 1 小时缓存
private const val MAX_MARK_RECORDS = 10000              // 流水表上限
```

在类中新增缓存字段（与其他 cache 字段放一起）：

```kotlin
private val watchHistoryCache = TtlCache<String, WatchHistoryPage>(TTL_WATCH_HISTORY)
```

在类中新增数据类（与 `WatchlistWatchedIds` 等放一起）：

```kotlin
/** Trakt /sync/history 分页结果 */
data class WatchHistoryPage(
    val items: List<WatchHistoryItem>,
    val currentPage: Int,
    val totalPages: Int,
    val totalCount: Int
)

/** 已看记录（统一表示 movie/episode） */
data class WatchHistoryItem(
    val traktId: Int,
    val tmdbId: Int,
    val imdbId: String,
    val mediaType: String,         // "movie" / "show"
    val title: String,
    val displayTitle: String,
    val posterUrl: String?,
    val year: Int?,
    val watchedAt: Long,
    val episodeInfo: String?       // "S01E03"，仅 episode 类型
)
```

在类中新增 `fetchWatchHistory` 方法：

```kotlin
/**
 * 拉取 Trakt 已看历史（含电影和剧集）。
 * 分页拉取，每页 100 条。movie 和 episode 并行拉取后合并按 watched_at 倒序排序。
 * @param page 页码，从 1 开始
 */
suspend fun fetchWatchHistory(page: Int): Result<WatchHistoryPage> {
    val cacheKey = "watch_history_page_$page"
    watchHistoryCache.get(cacheKey)?.let { return Result.success(it) }
    return try {
        coroutineScope {
            val movieDef = async { traktApiService.getMovieHistory(page = page, limit = 100) }
            val episodeDef = async { traktApiService.getEpisodeHistory(page = page, limit = 100) }
            val movieResp = movieDef.await()
            val episodeResp = episodeDef.await()
            if (!movieResp.isSuccessful || !episodeResp.isSuccessful) {
                return@coroutineScope Result.failure(Exception("fetchWatchHistory failed: movie=${movieResp.code()}, episode=${episodeResp.code()}"))
            }
            val movieEntries = movieResp.body() ?: emptyList()
            val episodeEntries = episodeResp.body() ?: emptyList()
            val movieTotal = movieResp.headers()["X-Pagination-Item-Count"]?.toIntOrNull() ?: 0
            val episodeTotal = episodeResp.headers()["X-Pagination-Item-Count"]?.toIntOrNull() ?: 0
            val moviePageCount = movieResp.headers()["X-Pagination-Page-Count"]?.toIntOrNull() ?: 1
            val episodePageCount = episodeResp.headers()["X-Pagination-Page-Count"]?.toIntOrNull() ?: 1
            val totalPages = maxOf(moviePageCount, episodePageCount)
            val totalCount = movieTotal + episodeTotal

            val items = mutableListOf<WatchHistoryItem>()
            // movie 记录
            for (entry in movieEntries) {
                val m = entry.movie ?: continue
                val watchedAt = parseTraktDate(entry.watched_at)
                val (posterUrl, _) = lookupPosterFromCache(m.ids.trakt, MediaType.MOVIE)
                items.add(WatchHistoryItem(
                    traktId = m.ids.trakt,
                    tmdbId = m.ids.tmdb,
                    imdbId = m.ids.imdb,
                    mediaType = "movie",
                    title = m.title,
                    displayTitle = m.title,
                    posterUrl = posterUrl,
                    year = m.year,
                    watchedAt = watchedAt,
                    episodeInfo = null
                ))
            }
            // episode 记录
            for (entry in episodeEntries) {
                val ep = entry.episode ?: continue
                val show = entry.show ?: continue
                val watchedAt = parseTraktDate(entry.watched_at)
                val (posterUrl, _) = lookupPosterFromCache(show.ids.trakt, MediaType.SHOW)
                items.add(WatchHistoryItem(
                    traktId = show.ids.trakt,
                    tmdbId = show.ids.tmdb,
                    imdbId = show.ids.imdb,
                    mediaType = "show",
                    title = show.title,
                    displayTitle = show.title,
                    posterUrl = posterUrl,
                    year = show.year,
                    watchedAt = watchedAt,
                    episodeInfo = "S${ep.season}E${ep.number}"
                ))
            }
            // 按 watchedAt 倒序
            items.sortByDescending { it.watchedAt }
            val result = WatchHistoryPage(items, page, totalPages, totalCount)
            watchHistoryCache.put(cacheKey, result)
            Result.success(result)
        }
    } catch (e: CancellationException) { throw e } catch (e: Exception) {
        Result.failure(e)
    }
}

/** 从 TMDB 详情缓存查海报路径（永久缓存，命中率高） */
private fun lookupPosterFromCache(traktId: Int, type: MediaType): Pair<String?, String?> {
    val cache = if (type == MediaType.MOVIE) movieDetailCache else tvDetailCache
    val detail = cache.get(traktId.toString())
    return Pair(detail?.posterUrl, detail?.backdropUrl)
}

/** 清空已看历史缓存（下拉刷新时调用） */
fun clearWatchHistoryCache() {
    watchHistoryCache.clear()
}

/** 解析 Trakt ISO8601 时间字符串为毫秒时间戳 */
private fun parseTraktDate(dateStr: String?): Long {
    if (dateStr.isNullOrBlank()) return System.currentTimeMillis()
    return try {
        // Trakt 返回 UTC ISO8601，如 "2014-10-11T17:00:00.000Z"
        java.time.Instant.parse(dateStr).toEpochMilli()
    } catch (e: Exception) {
        System.currentTimeMillis()
    }
}
```

- [ ] **步骤 2：新增 insertMarkRecord 私有方法**

在类中新增（与其他 private helper 放一起）：

```kotlin
/**
 * 异步写入一条标记操作流水。失败仅记录日志，不影响主操作。
 * 超过 MAX_MARK_RECORDS 上限时自动删最旧的。
 */
private suspend fun insertMarkRecord(
    traktId: Int,
    tmdbId: Int,
    mediaType: MediaType,
    actionType: MarkActionType,
    episodeInfo: String? = null
) {
    try {
        // 从缓存查影视信息快照
        val detail = if (mediaType == MediaType.MOVIE) movieDetailCache.get(traktId.toString())
                     else tvDetailCache.get(traktId.toString())
        val mediaItem = if (detail != null) null else {
            // 降级查 MediaItemEntity（watchlist/history 离线缓存）
            val typeStr = when {
                mediaType == MediaType.MOVIE && actionType == MarkActionType.ADD_WATCHLIST -> "watchlist_movie"
                mediaType == MediaType.SHOW && actionType == MarkActionType.ADD_WATCHLIST -> "watchlist_show"
                mediaType == MediaType.MOVIE -> "history_movie"
                else -> "history_show"
            }
            null  // MediaItemDao 查询可选，这里简化处理：优先用 detail 缓存
        }
        val title = detail?.title ?: ""
        val displayTitle = detail?.displayTitle ?: title
        val posterUrl = detail?.posterUrl
        val year = detail?.year
        val imdbId = detail?.imdbId ?: ""
        val mediaTypeStr = if (mediaType == MediaType.MOVIE) "movie" else "show"

        markActionRecordDao.insert(
            MarkActionRecordEntity(
                traktId = traktId,
                tmdbId = tmdbId,
                imdbId = imdbId,
                mediaType = mediaTypeStr,
                title = title,
                displayTitle = displayTitle,
                posterUrl = posterUrl,
                year = year,
                actionType = actionType.value,
                actedAt = System.currentTimeMillis(),
                episodeInfo = episodeInfo
            )
        )
        // 超上限删最旧
        val count = markActionRecordDao.count()
        if (count > MAX_MARK_RECORDS) {
            markActionRecordDao.deleteOldest(count - MAX_MARK_RECORDS)
        }
    } catch (e: CancellationException) { throw e } catch (e: Exception) {
        Log.w("TraktRepository", "insertMarkRecord failed: ${e.message}")
    }
}
```

- [ ] **步骤 3：在 addToWatchlist 成功分支后写流水**

修改 `addToWatchlist` 方法（约 967-972 行），在 `movieWatchlistCache.clear(); showWatchlistCache.clear()` 之后、`Result.success(...)` 之前插入：

```kotlin
                // 写入标记操作流水（异步，失败不影响主操作）
                insertMarkRecord(traktId, tmdbId, type, MarkActionType.ADD_WATCHLIST)
```

- [ ] **步骤 4：在 removeFromWatchlist 成功分支后写流水**

修改 `removeFromWatchlist` 方法（约 992-997 行），在 `movieWatchlistCache.clear(); showWatchlistCache.clear()` 之后、`Result.success(...)` 之前插入：

```kotlin
                // 写入标记操作流水
                insertMarkRecord(traktId, tmdbId, type, MarkActionType.REMOVE_WATCHLIST)
```

- [ ] **步骤 5：在 removeWatched 成功分支后写流水**

修改 `removeWatched` 方法（约 833-849 行），在所有 `cache.clear()` 之后、`Result.success(...)` 之前插入：

```kotlin
                // 写入标记操作流水（取消已看）
                insertMarkRecord(traktId, tmdbId, type, MarkActionType.UNMARK_WATCHED)
```

- [ ] **步骤 6：修改 unmarkEpisodeWatched 签名并写流水**

修改 `unmarkEpisodeWatched` 方法（约 774-788 行），完整替换为：

```kotlin
    /**
     * 取消某集已看标记。
     * @param season 季号（用于流水记录）
     * @param episode 集号（用于流水记录）
     * @param showTraktId 剧集 Trakt ID（用于流水记录）
     * @param showTmdbId 剧集 TMDB ID（用于流水记录）
     * @param showTitle 剧名（用于流水记录）
     */
    suspend fun unmarkEpisodeWatched(
        episodeTraktId: Int,
        season: Int = 0,
        episode: Int = 0,
        showTraktId: Int = 0,
        showTmdbId: Int = 0,
        showTitle: String = ""
    ): Result<Unit> {
        return try {
            val request = TraktSyncRequest(
                episodes = listOf(TraktSyncItem(TraktIds(trakt = episodeTraktId)))
            )
            val response = traktApiService.removeFromHistory(request)
            if (response.isSuccessful) {
                // 写入标记操作流水（取消单集已看）
                if (season > 0 && episode > 0) {
                    try {
                        val detail = if (showTraktId > 0) tvDetailCache.get(showTraktId.toString()) else null
                        markActionRecordDao.insert(
                            MarkActionRecordEntity(
                                traktId = showTraktId,
                                tmdbId = showTmdbId,
                                imdbId = detail?.imdbId ?: "",
                                mediaType = "show",
                                title = if (showTitle.isNotBlank()) showTitle else (detail?.title ?: ""),
                                displayTitle = if (showTitle.isNotBlank()) showTitle else (detail?.displayTitle ?: detail?.title ?: ""),
                                posterUrl = detail?.posterUrl,
                                year = detail?.year,
                                actionType = MarkActionType.UNMARK_WATCHED.value,
                                actedAt = System.currentTimeMillis(),
                                episodeInfo = "S${season}E${episode}"
                            )
                        )
                        val count = markActionRecordDao.count()
                        if (count > MAX_MARK_RECORDS) {
                            markActionRecordDao.deleteOldest(count - MAX_MARK_RECORDS)
                        }
                    } catch (e: CancellationException) { throw e } catch (e: Exception) {
                        Log.w("TraktRepository", "unmarkEpisodeWatched 流水写入失败: ${e.message}")
                    }
                }
                Result.success(Unit)
            } else {
                Result.failure(Exception("Failed to unmark episode watched: ${response.code()}"))
            }
        } catch (e: CancellationException) { throw e } catch (e: Exception) {
            Result.failure(e)
        }
    }
```

- [ ] **步骤 7：更新 DetailViewModel 调用处**

修改 `app/src/main/java/com/tracktosearch/ui/screen/detail/DetailViewModel.kt` 中所有 `unmarkEpisodeWatched` 调用，补传季集信息。用 Grep 找到调用处：

运行：在 `DetailViewModel.kt` 中搜索 `unmarkEpisodeWatched`，将每处调用从：
```kotlin
traktRepository.unmarkEpisodeWatched(episodeTraktId)
```
改为（补传调用方已有的 season/episode/showTraktId/showTmdbId/showTitle 变量，变量名以实际代码为准）：
```kotlin
traktRepository.unmarkEpisodeWatched(
    episodeTraktId = episodeTraktId,
    season = season,
    episode = episode,
    showTraktId = showTraktId,
    showTmdbId = showTmdbId,
    showTitle = showTitle
)
```

**注意**：调用处必须已有这些变量在作用域内。若变量名不同（如 `seasonNumber` / `episodeNumber`），按实际代码调整。

- [ ] **步骤 8：在 clearWatchlistWatchedCache 中清空已看历史缓存**

修改 `clearWatchlistWatchedCache` 方法（约 154 行），在 `watchlistWatchedIds = null` 之后追加：

```kotlin
        watchHistoryCache.clear()
```

- [ ] **步骤 9：构建验证**

运行：`.\gradlew assembleDebug`
预期：BUILD SUCCESSFUL

- [ ] **步骤 10：Commit**

```bash
git add app/src/main/java/com/tracktosearch/data/repository/TraktRepository.kt app/src/main/java/com/tracktosearch/ui/screen/detail/DetailViewModel.kt
git commit -m "feat: TraktRepository 写入标记流水 + fetchWatchHistory + unmarkEpisodeWatched 改签名"
```

---

### 任务 5：ViewModel + 枚举

**文件：**
- 创建：`app/src/main/java/com/tracktosearch/ui/screen/markrecord/MarkRecordViewModel.kt`
- 测试：`app/src/test/java/com/tracktosearch/ui/screen/markrecord/MarkRecordViewModelTest.kt`

- [ ] **步骤 1：编写失败的 ViewModel 测试**

```kotlin
package com.tracktosearch.ui.screen.markrecord

import com.google.common.truth.Truth.assertThat
import com.tracktosearch.data.local.db.MarkActionRecordDao
import com.tracktosearch.data.local.db.MarkActionRecordEntity
import com.tracktosearch.data.local.db.MarkActionType
import com.tracktosearch.data.repository.TraktRepository
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Before
import org.junit.Test

class MarkRecordViewModelTest {

    private lateinit var dao: MarkActionRecordDao
    private lateinit var traktRepo: TraktRepository
    private lateinit var viewModel: MarkRecordViewModel

    @Before
    fun setup() = runTest {
        Dispatchers.setMain(UnconfinedTestDispatcher())
        dao = mockk(relaxed = true)
        traktRepo = mockk(relaxed = true)
        coEvery { dao.count() } returns 0
        coEvery {
            dao.query(any(), any(), any(), any(), any(), any(), any(), any(), any(), any())
        } returns emptyList()
        viewModel = MarkRecordViewModel(dao, traktRepo)
    }

    @After
    fun teardown() { Dispatchers.resetMain() }

    private fun sampleEntity(actionType: String, traktId: Int, actedAt: Long) = MarkActionRecordEntity(
        traktId = traktId, tmdbId = 1, imdbId = "tt1", mediaType = "movie",
        title = "Test", displayTitle = "Test", posterUrl = null, year = 2024,
        actionType = actionType, actedAt = actedAt, episodeInfo = null
    )

    @Test
    fun initial_state_is_all_tab_loading() = runTest {
        val state = viewModel.uiState.value
        assertThat(state.currentTab).isEqualTo(MarkRecordTab.ALL)
    }

    @Test
    fun switch_tab_updates_current_tab() = runTest {
        viewModel.switchTab(MarkRecordTab.WATCHED)
        assertThat(viewModel.uiState.value.currentTab).isEqualTo(MarkRecordTab.WATCHED)
    }

    @Test
    fun watchlist_tab_queries_dao_with_add_watchlist_type() = runTest {
        coEvery {
            dao.query(
                actionTypes = listOf(MarkActionType.ADD_WATCHLIST.value),
                actionTypesEmpty = false,
                any(), any(), any(), any(), any(), any(), any(), any()
            )
        } returns listOf(sampleEntity(MarkActionType.ADD_WATCHLIST.value, 1, 1000L))
        viewModel.switchTab(MarkRecordTab.WATCHLIST)
        // 等待 init 加载完成
        kotlinx.coroutines.delay(100)
        assertThat(viewModel.uiState.value.items).hasSize(1)
    }

    @Test
    fun removed_tab_queries_dao_with_remove_and_unmark_types() = runTest {
        coEvery {
            dao.query(
                actionTypes = listOf(MarkActionType.REMOVE_WATCHLIST.value, MarkActionType.UNMARK_WATCHED.value),
                actionTypesEmpty = false,
                any(), any(), any(), any(), any(), any(), any(), any()
            )
        } returns listOf(sampleEntity(MarkActionType.REMOVE_WATCHLIST.value, 1, 1000L))
        viewModel.switchTab(MarkRecordTab.REMOVED)
        kotlinx.coroutines.delay(100)
        assertThat(viewModel.uiState.value.items).hasSize(1)
    }

    @Test
    fun update_search_query_triggers_reload() = runTest {
        viewModel.updateSearchQuery("Inception")
        kotlinx.coroutines.delay(100)
        coVerify(atLeast = 1) {
            dao.query(any(), any(), any(), any(), any(), any(), eq("%Inception%"), any(), any(), any())
        }
    }
}
```

- [ ] **步骤 2：运行测试验证失败**

运行：`.\gradlew testDebugUnitTest --tests "com.tracktosearch.ui.screen.markrecord.MarkRecordViewModelTest" -i"`
预期：编译失败，`MarkRecordViewModel` 未定义

- [ ] **步骤 3：实现 ViewModel**

创建 `app/src/main/java/com/tracktosearch/ui/screen/markrecord/MarkRecordViewModel.kt`：

```kotlin
package com.tracktosearch.ui.screen.markrecord

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.tracktosearch.data.local.db.MarkActionRecordDao
import com.tracktosearch.data.local.db.MarkActionRecordEntity
import com.tracktosearch.data.local.db.MarkActionType
import com.tracktosearch.data.repository.TraktRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** 标记记录 Tab */
enum class MarkRecordTab(val actionTypes: List<String>?) {
    ALL(null),                                                    // 全部（合并自建表+Trakt 历史）
    WATCHLIST(listOf(MarkActionType.ADD_WATCHLIST.value)),        // 想看
    WATCHED(null),                                                // 已看（走 Trakt API）
    REMOVED(listOf(MarkActionType.REMOVE_WATCHLIST.value, MarkActionType.UNMARK_WATCHED.value))  // 移除
}

/** 日期范围预设 */
enum class DatePreset { SEVEN_DAYS, THIRTY_DAYS, CUSTOM, ALL }

/** 当前标记状态（用于徽标显示） */
enum class CurrentMarkStatus { IN_WATCHLIST, WATCHED, NONE }

/** UI 层统一的记录项（自建表 + Trakt 历史合并后的数据模型） */
data class MarkRecordItem(
    val traktId: Int,
    val tmdbId: Int,
    val imdbId: String,
    val mediaType: String,          // "movie" / "show"
    val title: String,
    val displayTitle: String,
    val posterUrl: String?,
    val year: Int?,
    val actionType: String,         // ADD_WATCHLIST / REMOVE_WATCHLIST / UNMARK_WATCHED / WATCHED（Trakt 历史）
    val actedAt: Long,
    val episodeInfo: String?,
    val currentStatus: CurrentMarkStatus?    // null=未知（未查到），由 ViewModel 异步填充
)

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

@HiltViewModel
class MarkRecordViewModel @Inject constructor(
    private val markActionRecordDao: MarkActionRecordDao,
    private val traktRepository: TraktRepository
) : ViewModel() {

    private val _uiState = MutableStateFlow(MarkRecordUiState(isLoading = true))
    val uiState: StateFlow<MarkRecordUiState> = _uiState.asStateFlow()

    private val pageSize = 50

    init {
        loadFirstPage()
    }

    fun switchTab(tab: MarkRecordTab) {
        if (_uiState.value.currentTab == tab) return
        _uiState.update { it.copy(currentTab = tab, currentPage = 0, hasMore = true, items = emptyList(), isLoading = true, error = null) }
        loadFirstPage()
    }

    fun updateSearchQuery(query: String) {
        _uiState.update { it.copy(searchQuery = query, currentPage = 0, hasMore = true, items = emptyList(), isLoading = true) }
        loadFirstPage()
    }

    fun updateFilter(
        mediaTypes: Set<String>,
        datePreset: DatePreset,
        dateRange: Pair<Long, Long>?,
        ascending: Boolean
    ) {
        _uiState.update {
            it.copy(
                filterMediaTypes = mediaTypes,
                filterDatePreset = datePreset,
                filterDateRange = dateRange,
                sortAscending = ascending,
                currentPage = 0, hasMore = true, items = emptyList(), isLoading = true
            )
        }
        loadFirstPage()
    }

    fun loadNextPage() {
        val state = _uiState.value
        if (state.isLoading || state.isLoadingMore || !state.hasMore) return
        _uiState.update { it.copy(isLoadingMore = true) }
        viewModelScope.launch {
            loadPage(state.currentPage + 1)
        }
    }

    fun refresh() {
        _uiState.update { it.copy(currentPage = 0, hasMore = true, items = emptyList(), isLoading = true, error = null) }
        traktRepository.clearWatchHistoryCache()
        loadFirstPage()
    }

    fun retry() {
        _uiState.update { it.copy(error = null, isLoading = true) }
        loadFirstPage()
    }

    private fun loadFirstPage() {
        viewModelScope.launch {
            loadPage(1)
            updateCurrentStatusMap()
        }
    }

    /** 计算当前页所有记录的当前标记状态 */
    private suspend fun updateCurrentStatusMap() {
        val items = _uiState.value.items
        if (items.isEmpty()) return
        val ids = watchlistWatchedIds ?: runCatching { traktRepository.loadWatchlistWatchedIds() }.getOrNull() ?: return
        val map = mutableMapOf<Int, CurrentMarkStatus>()
        for (item in items) {
            val type = if (item.mediaType == "movie") MediaType.MOVIE else MediaType.SHOW
            val inWl = ids.isInWatchlist(item.traktId, null, type)
            val watched = ids.isWatched(item.traktId, null, type)
            map[item.traktId] = when {
                watched -> CurrentMarkStatus.WATCHED
                inWl -> CurrentMarkStatus.IN_WATCHLIST
                else -> CurrentMarkStatus.NONE
            }
        }
        _uiState.update { it.copy(currentStatusMap = map) }
    }

    private suspend fun loadPage(page: Int) {
        val state = _uiState.value
        try {
            val newItems = when (state.currentTab) {
                MarkRecordTab.WATCHED -> loadFromTraktHistory(page)
                MarkRecordTab.ALL -> {
                    // ALL Tab: 自建表第 1 页（50 条）+ Trakt 第 1 页（100 条），合并后取最新 50 条
                    val localItems = loadFromDao(page, state)
                    val traktItems = if (page == 1) loadFromTraktHistory(1) else emptyList()
                    val merged = (localItems + traktItems).sortedByDescending { it.actedAt }.take(pageSize)
                    merged
                }
                else -> loadFromDao(page, state)
            }
            val allItems = if (page == 1) newItems else _uiState.value.items + newItems
            val hasMore = newItems.size == pageSize && state.currentTab != MarkRecordTab.ALL
            _uiState.update {
                it.copy(
                    items = allItems,
                    currentPage = page,
                    hasMore = hasMore,
                    isLoading = false,
                    isLoadingMore = false,
                    error = null
                )
            }
        } catch (e: CancellationException) { throw e } catch (e: Exception) {
            _uiState.update { it.copy(isLoading = false, isLoadingMore = false, error = e.message ?: "加载失败") }
        }
    }

    private suspend fun loadFromDao(page: Int, state: MarkRecordUiState): List<MarkRecordItem> {
        val (startTime, endTime) = computeTimeRange(state)
        val titleQuery = state.searchQuery.takeIf { it.isNotBlank() }?.let { "%$it%" }
        val records = markActionRecordDao.query(
            actionTypes = state.currentTab.actionTypes ?: emptyList(),
            actionTypesEmpty = state.currentTab.actionTypes == null,
            mediaTypes = state.filterMediaTypes.toList(),
            mediaTypesEmpty = state.filterMediaTypes.isEmpty(),
            startTime = startTime,
            endTime = endTime,
            titleQuery = titleQuery,
            ascending = state.sortAscending,
            limit = pageSize,
            offset = (page - 1) * pageSize
        )
        return records.map { it.toMarkRecordItem() }
    }

    private suspend fun loadFromTraktHistory(page: Int): List<MarkRecordItem> {
        val result = traktRepository.fetchWatchHistory(page)
        if (!result.isSuccess) throw result.exceptionOrNull() ?: Exception("fetchWatchHistory failed")
        val historyPage = result.getOrNull()!!
        return historyPage.items.map { it.toMarkRecordItem() }
    }

    private fun computeTimeRange(state: MarkRecordUiState): Pair<Long, Long> {
        val now = System.currentTimeMillis()
        return when (state.filterDatePreset) {
            DatePreset.SEVEN_DAYS -> Pair(now - 7L * 24 * 60 * 60 * 1000, 0)
            DatePreset.THIRTY_DAYS -> Pair(now - 30L * 24 * 60 * 60 * 1000, 0)
            DatePreset.CUSTOM -> state.filterDateRange ?: Pair(0, 0)
            DatePreset.ALL -> Pair(0, 0)
        }
    }

    // 引用 TraktRepository 的 watchlistWatchedIds（公开访问）
    private val watchlistWatchedIds get() = traktRepository.getWatchlistWatchedIds()
}

// MediaType 引用（避免顶部 import 冲突，这里用全限定名）
private val MediaType = com.tracktosearch.data.repository.MediaType.Companion
private val MediaType.Companion.MOVIE get() = com.tracktosearch.data.repository.MediaType.MOVIE
private val MediaType.Companion.SHOW get() = com.tracktosearch.data.repository.MediaType.SHOW

private fun MarkActionRecordEntity.toMarkRecordItem() = MarkRecordItem(
    traktId = traktId, tmdbId = tmdbId, imdbId = imdbId,
    mediaType = mediaType, title = title, displayTitle = displayTitle,
    posterUrl = posterUrl, year = year, actionType = actionType,
    actedAt = actedAt, episodeInfo = episodeInfo, currentStatus = null
)

private fun TraktRepository.WatchHistoryItem.toMarkRecordItem() = MarkRecordItem(
    traktId = traktId, tmdbId = tmdbId, imdbId = imdbId,
    mediaType = mediaType, title = title, displayTitle = displayTitle,
    posterUrl = posterUrl, year = year,
    actionType = "WATCHED",   // Trakt 历史统一标记为 WATCHED
    actedAt = watchedAt, episodeInfo = episodeInfo, currentStatus = null
)
```

**注意**：上面 `MediaType` 的全限定名引用写法有问题，实际实现时改为在文件顶部 `import com.tracktosearch.data.repository.MediaType`，并删除文件底部的 `private val MediaType = ...` 那几行。`updateCurrentStatusMap` 中的 `MediaType.MOVIE` / `MediaType.SHOW` 直接用即可。

- [ ] **步骤 4：运行测试验证通过**

运行：`.\gradlew testDebugUnitTest --tests "com.tracktosearch.ui.screen.markrecord.MarkRecordViewModelTest" -i"`
预期：5 个测试 PASS

- [ ] **步骤 5：Commit**

```bash
git add app/src/main/java/com/tracktosearch/ui/screen/markrecord/MarkRecordViewModel.kt app/src/test/java/com/tracktosearch/ui/screen/markrecord/MarkRecordViewModelTest.kt
git commit -m "feat: 新增 MarkRecordViewModel + 状态管理 + 分页"
```

---

### 任务 6：UI 组件（列表项 + 筛选弹窗 + 当前状态徽标）

**文件：**
- 创建：`app/src/main/java/com/tracktosearch/ui/screen/markrecord/MarkRecordComponents.kt`

- [ ] **步骤 1：创建组件文件**

创建 `app/src/main/java/com/tracktosearch/ui/screen/markrecord/MarkRecordComponents.kt`：

```kotlin
package com.tracktosearch.ui.screen.markrecord

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AssistChip
import androidx.compose.material3.AssistChipDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import com.tracktosearch.R
import com.tracktosearch.ui.formatRelativeTime
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 标记记录列表项。
 * @param item 记录数据
 * @param onClick 点击跳转详情页
 */
@Composable
fun MarkRecordItem(
    item: MarkRecordItem,
    onClick: () -> Unit
) {
    val isChanged = isRecordChanged(item)
    val rowAlpha = if (isChanged) 0.6f else 1f

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .alpha(rowAlpha)
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        // 小海报 48×72dp
        AsyncImage(
            model = item.posterUrl,
            contentDescription = item.title,
            modifier = Modifier
                .size(width = 48.dp, height = 72.dp)
                .clip(RoundedCornerShape(4.dp))
        )
        Spacer(Modifier.width(12.dp))
        // 信息区
        Column(modifier = Modifier.weight(1f)) {
            // 标题 + 年份
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = item.displayTitle.ifBlank { item.title },
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.Medium,
                    maxLines = 1
                )
                item.year?.let {
                    Spacer(Modifier.width(4.dp))
                    Text(
                        text = "($it)",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
            // episodeInfo
            item.episodeInfo?.let {
                Text(
                    text = it,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Spacer(Modifier.size(4.dp))
            // 操作类型 chip + 相对时间
            Row(verticalAlignment = Alignment.CenterVertically) {
                ActionTypeChip(item.actionType)
                Spacer(Modifier.width(8.dp))
                Text(
                    text = formatRelativeTime(item.actedAt),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Spacer(Modifier.size(4.dp))
            // 当前状态徽标
            CurrentStatusBadge(item)
        }
    }
}

/** 判断记录的操作类型与当前状态是否矛盾 */
private fun isRecordChanged(item: MarkRecordItem): Boolean {
    val status = item.currentStatus ?: return false
    return when (item.actionType) {
        "ADD_WATCHLIST" -> status != CurrentMarkStatus.IN_WATCHLIST
        "REMOVE_WATCHLIST" -> status == CurrentMarkStatus.IN_WATCHLIST
        "UNMARK_WATCHED" -> status == CurrentMarkStatus.WATCHED
        "WATCHED" -> status != CurrentMarkStatus.WATCHED
        else -> false
    }
}

@Composable
private fun ActionTypeChip(actionType: String) {
    val (text, color) = when (actionType) {
        "ADD_WATCHLIST" -> R.string.mark_records_action_add_watchlist to Color(0xFF2196F3)
        "REMOVE_WATCHLIST" -> R.string.mark_records_action_remove_watchlist to Color(0xFFF44336)
        "UNMARK_WATCHED" -> R.string.mark_records_action_unmark_watched to Color(0xFFFF9800)
        "WATCHED" -> R.string.mark_records_action_watched to Color(0xFF4CAF50)
        else -> R.string.mark_records_action_watched to Color.Gray
    }
    AssistChip(
        onClick = {},
        label = { Text(stringResource(text), fontSize = 11.sp) },
        colors = AssistChipDefaults.assistChipColors(
            containerColor = color.copy(alpha = 0.15f),
            labelColor = color
        )
    )
}

@Composable
private fun CurrentStatusBadge(item: MarkRecordItem) {
    val status = item.currentStatus ?: return
    val (textRes, color) = when (status) {
        CurrentMarkStatus.IN_WATCHLIST -> R.string.mark_records_current_in_watchlist to Color(0xFF4CAF50)
        CurrentMarkStatus.WATCHED -> R.string.mark_records_current_watched to Color(0xFF4CAF50)
        CurrentMarkStatus.NONE -> {
            if (isRecordChanged(item)) R.string.mark_records_current_changed to Color.Gray
            else R.string.mark_records_current_none to Color.Gray
        }
    }
    Text(
        text = stringResource(textRes),
        style = MaterialTheme.typography.labelSmall,
        color = color,
        fontSize = 11.sp
    )
}

private fun stringResource(resId: Int): String =
    androidx.compose.ui.res.stringResource(resId)
```

**注意**：`stringResource` 在 `@Composable` 上下文里应直接用 `androidx.compose.ui.res.stringResource(resId)`。上面末尾的 `private fun stringResource` 是错误的，实际实现时删除它，在 `ActionTypeChip` 和 `CurrentStatusBadge` 内直接调 `androidx.compose.ui.res.stringResource(text)` / `androidx.compose.ui.res.stringResource(textRes)`。同时顶部不需要 `import com.tracktosearch.ui.formatRelativeTime`，相对时间格式化在任务 7 的 Screen 里实现并传入，或在这里单独实现。

修正后的 `formatRelativeTime` 放在本文件底部：

```kotlin
/** 格式化时间戳为相对时间字符串 */
private fun formatRelativeTime(timestampMs: Long): String {
    if (timestampMs <= 0L) return ""
    val diff = System.currentTimeMillis() - timestampMs
    val minutes = diff / (60 * 1000L)
    val hours = diff / (60 * 60 * 1000L)
    val days = diff / (24 * 60 * 60 * 1000L)
    return when {
        minutes < 1 -> "刚刚"
        minutes < 60 -> "$minutes 分钟前"
        hours < 24 -> "$hours 小时前"
        days < 30 -> "$days 天前"
        else -> SimpleDateFormat("yyyy-MM-dd", Locale.getDefault()).format(Date(timestampMs))
    }
}
```

**国际化修正**：上面 `"刚刚" / "$minutes 分钟前"` 等是硬编码中文，违反项目规范。实际实现时改为使用 string resource：

```kotlin
private fun formatRelativeTime(timestampMs: Long, context: android.content.Context): String {
    if (timestampMs <= 0L) return ""
    val diff = System.currentTimeMillis() - timestampMs
    val minutes = diff / (60 * 1000L)
    val hours = diff / (60 * 60 * 1000L)
    val days = diff / (24 * 60 * 60 * 1000L)
    return when {
        minutes < 1 -> context.getString(R.string.mark_records_time_just_now)
        minutes < 60 -> context.getString(R.string.mark_records_time_minutes_ago, minutes.toInt())
        hours < 24 -> context.getString(R.string.mark_records_time_hours_ago, hours.toInt())
        days < 30 -> context.getString(R.string.mark_records_time_days_ago, days.toInt())
        else -> SimpleDateFormat("yyyy-MM-dd", Locale.getDefault()).format(Date(timestampMs))
    }
}
```

`MarkRecordItem` Composable 增加 `context` 参数获取：`val context = androidx.compose.ui.platform.LocalContext.current`，然后调 `formatRelativeTime(item.actedAt, context)`。

- [ ] **步骤 2：Commit**

```bash
git add app/src/main/java/com/tracktosearch/ui/screen/markrecord/MarkRecordComponents.kt
git commit -m "feat: 新增标记记录列表项 + 当前状态徽标组件"
```

---

### 任务 7：主界面 Screen

**文件：**
- 创建：`app/src/main/java/com/tracktosearch/ui/screen/markrecord/MarkRecordScreen.kt`

- [ ] **步骤 1：创建 Screen**

创建 `app/src/main/java/com/tracktosearch/ui/screen/markrecord/MarkRecordScreen.kt`：

```kotlin
package com.tracktosearch.ui.screen.markrecord

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ArrowBack
import androidx.compose.material.icons.rounded.FilterList
import androidx.compose.material.icons.rounded.Inbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Scaffold
import androidx.compose.material3.ScrollableTabRow
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.material3.OutlinedTextField
import androidx.compose.runtime.collectAsState
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.tracktosearch.R
import com.tracktosearch.ui.component.ScrollToTopButton

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MarkRecordScreen(
    onBack: () -> Unit,
    onMovieClick: (traktId: Int, tmdbId: Int, title: String, imdbId: String, traktRating: Double) -> Unit,
    onShowClick: (traktId: Int, tmdbId: Int, title: String, imdbId: String, traktRating: Double) -> Unit,
    viewModel: MarkRecordViewModel = hiltViewModel()
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val listState = rememberLazyListState()
    var showFilterSheet by remember { mutableStateOf(false) }

    // 滚动到底部前 20 条时加载下一页
    LaunchedEffect(listState, uiState.items) {
        snapshotFlow {
            val lastVisible = listState.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: 0
            val total = listState.layoutInfo.totalItemsCount
            lastVisible >= total - 20
        }.distinctUntilChanged().filter { it }.collect {
            viewModel.loadNextPage()
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.mark_records_title)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.Rounded.ArrowBack, contentDescription = "返回")
                    }
                },
                actions = {
                    IconButton(onClick = { showFilterSheet = true }) {
                        Icon(Icons.Rounded.FilterList, contentDescription = "筛选")
                    }
                }
            )
        }
    ) { padding ->
        Column(modifier = Modifier.fillMaxSize().padding(padding)) {
            // Tab
            ScrollableTabRow(
                selectedTabIndex = uiState.currentTab.ordinal,
                edgePadding = 0.dp
            ) {
                MarkRecordTab.entries.forEach { tab ->
                    Tab(
                        selected = uiState.currentTab == tab,
                        onClick = { viewModel.switchTab(tab) },
                        text = {
                            Text(stringResource(when (tab) {
                                MarkRecordTab.ALL -> R.string.mark_records_tab_all
                                MarkRecordTab.WATCHLIST -> R.string.mark_records_tab_watchlist
                                MarkRecordTab.WATCHED -> R.string.mark_records_tab_watched
                                MarkRecordTab.REMOVED -> R.string.mark_records_tab_removed
                            }))
                        }
                    )
                }
            }
            // 搜索框
            OutlinedTextField(
                value = uiState.searchQuery,
                onValueChange = { viewModel.updateSearchQuery(it) },
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
                placeholder = { Text(stringResource(R.string.mark_records_search_hint)) },
                singleLine = true,
                keyboardOptions = KeyboardOptions(
                    capitalization = KeyboardCapitalization.None,
                    imeAction = ImeAction.Search
                )
            )
            // 内容
            Box(modifier = Modifier.fillMaxSize()) {
                when {
                    uiState.isLoading -> {
                        CircularProgressIndicator(
                            modifier = Modifier.align(Alignment.Center)
                        )
                    }
                    uiState.error != null -> {
                        Column(
                            modifier = Modifier.align(Alignment.Center),
                            horizontalAlignment = Alignment.CenterHorizontally
                        ) {
                            Text(uiState.error!!, color = MaterialTheme.colorScheme.error)
                            androidx.compose.material3.TextButton(onClick = { viewModel.retry() }) {
                                Text(stringResource(R.string.mark_records_retry))
                            }
                        }
                    }
                    uiState.items.isEmpty() -> {
                        Column(
                            modifier = Modifier.align(Alignment.Center),
                            horizontalAlignment = Alignment.CenterHorizontally
                        ) {
                            Icon(Icons.Rounded.Inbox, contentDescription = null, modifier = Modifier.size(48.dp))
                            Text(
                                stringResource(when (uiState.currentTab) {
                                    MarkRecordTab.ALL -> R.string.mark_records_empty_all
                                    MarkRecordTab.WATCHLIST -> R.string.mark_records_empty_watchlist
                                    MarkRecordTab.WATCHED -> R.string.mark_records_empty_watched
                                    MarkRecordTab.REMOVED -> R.string.mark_records_empty_removed
                                }),
                                modifier = Modifier.padding(top = 8.dp)
                            )
                        }
                    }
                    else -> {
                        LazyColumn(state = listState) {
                            items(uiState.items, key = { "${it.traktId}_${it.actedAt}_${it.actionType}" }) { item ->
                                MarkRecordItem(
                                    item = item,
                                    onClick = {
                                        val onClick = if (item.mediaType == "movie") onMovieClick else onShowClick
                                        onClick(item.traktId, item.tmdbId, item.displayTitle.ifBlank { item.title }, item.imdbId, 0.0)
                                    }
                                )
                            }
                            if (uiState.isLoadingMore) {
                                item {
                                    Box(
                                        modifier = Modifier.fillMaxWidth().padding(16.dp),
                                        contentAlignment = Alignment.Center
                                    ) {
                                        CircularProgressIndicator(modifier = Modifier.size(24.dp))
                                    }
                                }
                            }
                        }
                        ScrollToTopButton(listState = listState)
                    }
                }
            }
        }
    }

    // 筛选弹窗
    if (showFilterSheet) {
        val sheetState = rememberModalBottomSheetState()
        ModalBottomSheet(
            onDismissRequest = { showFilterSheet = false },
            sheetState = sheetState
        ) {
            FilterSheetContent(
                mediaTypes = uiState.filterMediaTypes,
                datePreset = uiState.filterDatePreset,
                dateRange = uiState.filterDateRange,
                ascending = uiState.sortAscending,
                onConfirm = { mediaTypes, preset, range, asc ->
                    viewModel.updateFilter(mediaTypes, preset, range, asc)
                    showFilterSheet = false
                },
                onReset = {
                    viewModel.updateFilter(emptySet(), DatePreset.ALL, null, false)
                    showFilterSheet = false
                }
            )
        }
    }
}
```

**注意**：上面用到了 `snapshotFlow` / `distinctUntilChanged` / `filter`，需要补充 import：
```kotlin
import androidx.compose.runtime.snapshotFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filter
```
`stringResource` 需 `import androidx.compose.ui.res.stringResource`。`clip` 需 `import androidx.compose.ui.draw.clip`。这些 import 在实现时补齐。

- [ ] **步骤 2：创建筛选弹窗内容组件**

在 `MarkRecordComponents.kt` 末尾追加：

```kotlin
@Composable
fun FilterSheetContent(
    mediaTypes: Set<String>,
    datePreset: DatePreset,
    dateRange: Pair<Long, Long>?,
    ascending: Boolean,
    onConfirm: (Set<String>, DatePreset, Pair<Long, Long>?, Boolean) -> Unit,
    onReset: () -> Unit
) {
    var selectedMediaTypes by remember { mutableStateOf(mediaTypes) }
    var selectedPreset by remember { mutableStateOf(datePreset) }
    var selectedRange by remember { mutableStateOf(dateRange) }
    var selectedAscending by remember { mutableStateOf(ascending) }

    Column(modifier = Modifier.fillMaxWidth().padding(16.dp)) {
        // 媒体类型
        Text(
            stringResource(R.string.mark_records_filter_media_type),
            style = MaterialTheme.typography.titleSmall,
            modifier = Modifier.padding(bottom = 8.dp)
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilterChip(
                selected = selectedMediaTypes.isEmpty() || "movie" in selectedMediaTypes,
                onClick = {
                    selectedMediaTypes = if ("movie" in selectedMediaTypes) selectedMediaTypes - "movie" else selectedMediaTypes + "movie"
                },
                label = { Text("电影") }  // 实际用 stringResource(R.string.mark_records_media_movie)
            )
            FilterChip(
                selected = selectedMediaTypes.isEmpty() || "show" in selectedMediaTypes,
                onClick = {
                    selectedMediaTypes = if ("show" in selectedMediaTypes) selectedMediaTypes - "show" else selectedMediaTypes + "show"
                },
                label = { Text("剧集") }  // 实际用 stringResource(R.string.mark_records_media_show)
            )
        }
        Spacer(Modifier.height(16.dp))
        // 日期范围
        Text(
            stringResource(R.string.mark_records_filter_date_range),
            style = MaterialTheme.typography.titleSmall,
            modifier = Modifier.padding(bottom = 8.dp)
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            DatePreset.entries.forEach { preset ->
                FilterChip(
                    selected = selectedPreset == preset,
                    onClick = { selectedPreset = preset },
                    label = { Text(stringResource(when (preset) {
                        DatePreset.SEVEN_DAYS -> R.string.mark_records_date_preset_7d
                        DatePreset.THIRTY_DAYS -> R.string.mark_records_date_preset_30d
                        DatePreset.CUSTOM -> R.string.mark_records_date_preset_custom
                        DatePreset.ALL -> R.string.mark_records_date_preset_all
                    })) }
                )
            }
        }
        // 自定义日期范围（RangeSlider）仅在 CUSTOM 时显示
        if (selectedPreset == DatePreset.CUSTOM) {
            // 简化：实际用 DateRangePicker 或两个 OutlinedTextField
            Text("自定义日期范围（待实现）", modifier = Modifier.padding(top = 8.dp))
        }
        Spacer(Modifier.height(16.dp))
        // 排序
        Text(
            stringResource(R.string.mark_records_filter_sort),
            style = MaterialTheme.typography.titleSmall,
            modifier = Modifier.padding(bottom = 8.dp)
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilterChip(
                selected = !selectedAscending,
                onClick = { selectedAscending = false },
                label = { Text(stringResource(R.string.mark_records_sort_desc)) }
            )
            FilterChip(
                selected = selectedAscending,
                onClick = { selectedAscending = true },
                label = { Text(stringResource(R.string.mark_records_sort_asc)) }
            )
        }
        Spacer(Modifier.height(24.dp))
        // 底部按钮
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            androidx.compose.material3.TextButton(onClick = onReset) {
                Text(stringResource(R.string.mark_records_reset))
            }
            androidx.compose.material3.Button(onClick = {
                onConfirm(selectedMediaTypes, selectedPreset, selectedRange, selectedAscending)
            }) {
                Text(stringResource(R.string.mark_records_confirm))
            }
        }
    }
}
```

**注意**：`"电影" / "剧集"` 是硬编码中文，实际实现时改为 `stringResource(R.string.mark_records_media_movie)` / `stringResource(R.string.mark_records_media_show)`，并在 strings.xml 补这两个 key。自定义日期范围的 RangeSlider 实现较复杂，作为可选增强，初版可只支持预设（7天/30天/全部）。

- [ ] **步骤 3：Commit**

```bash
git add app/src/main/java/com/tracktosearch/ui/screen/markrecord/MarkRecordScreen.kt app/src/main/java/com/tracktosearch/ui/screen/markrecord/MarkRecordComponents.kt
git commit -m "feat: 新增 MarkRecordScreen 主界面 + 筛选弹窗"
```

---

### 任务 8：导航 + 设置页入口

**文件：**
- 修改：`app/src/main/java/com/tracktosearch/ui/navigation/AppNavigation.kt`
- 修改：`app/src/main/java/com/tracktosearch/ui/screen/settings/SettingsScreen.kt`

- [ ] **步骤 1：在 Routes 新增常量**

修改 `app/src/main/java/com/tracktosearch/ui/navigation/AppNavigation.kt`，在 `Routes` object 的 `DOUBAN_SPIDER_TEST` 后追加：

```kotlin
    const val MARK_RECORDS = "markRecords"
```

- [ ] **步骤 2：在 NavHost 新增 composable**

在 `AppNavigation.kt` 的 `NavHost` 中（其他 `composable(...)` 旁边）追加：

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

顶部补充 import：
```kotlin
import com.tracktosearch.ui.screen.markrecord.MarkRecordScreen
```

- [ ] **步骤 3：在 SettingsScreen 新增入口参数**

修改 `app/src/main/java/com/tracktosearch/ui/screen/settings/SettingsScreen.kt`，在 `fun SettingsScreen(...)` 参数列表中 `onStatisticsClick: () -> Unit = {}` 后追加：

```kotlin
    onMarkRecordsClick: () -> Unit = {},
```

- [ ] **步骤 4：在 statistics_entry 后新增 mark_records_entry**

在 `SettingsScreen.kt` 的 `item(key = "statistics_entry") { ... }` 块之后（`item(key = "group_appearance")` 之前）追加：

```kotlin
            // 标记记录（仅登录可见，独占整行卡片，与统计卡片风格一致）
            if (isLoggedIn) {
                item(key = "mark_records_entry") {
                    MarkRecordsEntryCard(onClick = onMarkRecordsClick)
                }
            }
```

在文件末尾（或 `StatisticsCard` 旁边）新增 Composable：

```kotlin
@Composable
private fun MarkRecordsEntryCard(onClick: () -> Unit) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 4.dp)
            .clickable(onClick = onClick),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                imageVector = Icons.Rounded.History,  // 或 Icons.Rounded.ListAlt
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary
            )
            Spacer(Modifier.width(16.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = stringResource(R.string.mark_records_settings_entry),
                    style = MaterialTheme.typography.titleMedium
                )
                Text(
                    text = stringResource(R.string.mark_records_settings_entry_desc),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}
```

**注意**：需要补充 import：`androidx.compose.material3.Card` / `CardDefaults` / `androidx.compose.material.icons.rounded.History`。具体图标可按项目已有的 Material Icons 调整。

- [ ] **步骤 5：在 AppNavigation 传入 onMarkRecordsClick**

找到 `NavHost` 中 `composable(Routes.MAIN)` 内调用 `SettingsScreen(...)` 的地方，在 `onStatisticsClick = { ... }` 后追加：

```kotlin
                    onMarkRecordsClick = { navController.navigate(Routes.MARK_RECORDS) },
```

- [ ] **步骤 6：构建验证**

运行：`.\gradlew assembleDebug`
预期：BUILD SUCCESSFUL

- [ ] **步骤 7：Commit**

```bash
git add app/src/main/java/com/tracktosearch/ui/navigation/AppNavigation.kt app/src/main/java/com/tracktosearch/ui/screen/settings/SettingsScreen.kt
git commit -m "feat: 新增标记记录导航路由 + 设置页入口"
```

---

### 任务 9：国际化字符串

**文件：**
- 修改：4 个 strings.xml

- [ ] **步骤 1：在 values/strings.xml 追加英文字符串**

在 `app/src/main/res/values/strings.xml` 的 `<resources>` 内追加：

```xml
    <!-- 标记记录 -->
    <string name="mark_records_title">Mark Records</string>
    <string name="mark_records_tab_all">All</string>
    <string name="mark_records_tab_watchlist">Watchlist</string>
    <string name="mark_records_tab_watched">Watched</string>
    <string name="mark_records_tab_removed">Removed</string>
    <string name="mark_records_search_hint">Search title...</string>
    <string name="mark_records_filter_media_type">Media Type</string>
    <string name="mark_records_filter_date_range">Date Range</string>
    <string name="mark_records_filter_sort">Sort</string>
    <string name="mark_records_sort_desc">Latest First</string>
    <string name="mark_records_sort_asc">Earliest First</string>
    <string name="mark_records_date_preset_7d">Last 7 Days</string>
    <string name="mark_records_date_preset_30d">Last 30 Days</string>
    <string name="mark_records_date_preset_all">All Time</string>
    <string name="mark_records_date_preset_custom">Custom</string>
    <string name="mark_records_empty_all">No mark records yet</string>
    <string name="mark_records_empty_watchlist">No watchlist actions</string>
    <string name="mark_records_empty_watched">No watched records</string>
    <string name="mark_records_empty_removed">No removal actions</string>
    <string name="mark_records_load_failed">Load failed</string>
    <string name="mark_records_retry">Retry</string>
    <string name="mark_records_current_in_watchlist">Current: Watchlist</string>
    <string name="mark_records_current_watched">Current: Watched</string>
    <string name="mark_records_current_none">Current: None</string>
    <string name="mark_records_current_changed">Changed</string>
    <string name="mark_records_action_add_watchlist">Added to Watchlist</string>
    <string name="mark_records_action_remove_watchlist">Removed from Watchlist</string>
    <string name="mark_records_action_unmark_watched">Unmarked Watched</string>
    <string name="mark_records_action_watched">Watched</string>
    <string name="mark_records_settings_entry">Mark Records</string>
    <string name="mark_records_settings_entry_desc">View your watchlist/watched/removed action history</string>
    <string name="mark_records_reset">Reset</string>
    <string name="mark_records_confirm">OK</string>
    <string name="mark_records_media_movie">Movie</string>
    <string name="mark_records_media_show">Show</string>
    <string name="mark_records_time_just_now">Just now</string>
    <string name="mark_records_time_minutes_ago">%1$d min ago</string>
    <string name="mark_records_time_hours_ago">%1$d hr ago</string>
    <string name="mark_records_time_days_ago">%1$d days ago</string>
```

- [ ] **步骤 2：在 values-zh/strings.xml 追加中文字符串**

```xml
    <!-- 标记记录 -->
    <string name="mark_records_title">标记记录</string>
    <string name="mark_records_tab_all">全部</string>
    <string name="mark_records_tab_watchlist">想看</string>
    <string name="mark_records_tab_watched">已看</string>
    <string name="mark_records_tab_removed">移除</string>
    <string name="mark_records_search_hint">搜索标题...</string>
    <string name="mark_records_filter_media_type">媒体类型</string>
    <string name="mark_records_filter_date_range">日期范围</string>
    <string name="mark_records_filter_sort">排序方式</string>
    <string name="mark_records_sort_desc">最新优先</string>
    <string name="mark_records_sort_asc">最早优先</string>
    <string name="mark_records_date_preset_7d">近 7 天</string>
    <string name="mark_records_date_preset_30d">近 30 天</string>
    <string name="mark_records_date_preset_all">全部</string>
    <string name="mark_records_date_preset_custom">自定义</string>
    <string name="mark_records_empty_all">暂无标记记录</string>
    <string name="mark_records_empty_watchlist">暂无想看操作记录</string>
    <string name="mark_records_empty_watched">暂无已看记录</string>
    <string name="mark_records_empty_removed">暂无移除操作记录</string>
    <string name="mark_records_load_failed">加载失败</string>
    <string name="mark_records_retry">重试</string>
    <string name="mark_records_current_in_watchlist">当前:想看</string>
    <string name="mark_records_current_watched">当前:已看</string>
    <string name="mark_records_current_none">当前:无标记</string>
    <string name="mark_records_current_changed">已变更</string>
    <string name="mark_records_action_add_watchlist">加想看</string>
    <string name="mark_records_action_remove_watchlist">移除想看</string>
    <string name="mark_records_action_unmark_watched">取消已看</string>
    <string name="mark_records_action_watched">已看</string>
    <string name="mark_records_settings_entry">标记记录</string>
    <string name="mark_records_settings_entry_desc">查看你的想看/已看/移除操作历史</string>
    <string name="mark_records_reset">重置</string>
    <string name="mark_records_confirm">确定</string>
    <string name="mark_records_media_movie">电影</string>
    <string name="mark_records_media_show">剧集</string>
    <string name="mark_records_time_just_now">刚刚</string>
    <string name="mark_records_time_minutes_ago">%1$d 分钟前</string>
    <string name="mark_records_time_hours_ago">%1$d 小时前</string>
    <string name="mark_records_time_days_ago">%1$d 天前</string>
```

- [ ] **步骤 3：在 values-ja/strings.xml 追加日文字符串**

```xml
    <!-- 标記記録 -->
    <string name="mark_records_title">マーク記録</string>
    <string name="mark_records_tab_all">すべて</string>
    <string name="mark_records_tab_watchlist">ウォッチリスト</string>
    <string name="mark_records_tab_watched">視聴済み</string>
    <string name="mark_records_tab_removed">削除</string>
    <string name="mark_records_search_hint">タイトル検索...</string>
    <string name="mark_records_filter_media_type">メディアタイプ</string>
    <string name="mark_records_filter_date_range">期間</string>
    <string name="mark_records_filter_sort">並び替え</string>
    <string name="mark_records_sort_desc">新着順</string>
    <string name="mark_records_sort_asc">古い順</string>
    <string name="mark_records_date_preset_7d">過去7日間</string>
    <string name="mark_records_date_preset_30d">過去30日間</string>
    <string name="mark_records_date_preset_all">すべて</string>
    <string name="mark_records_date_preset_custom">カスタム</string>
    <string name="mark_records_empty_all">マーク記録がありません</string>
    <string name="mark_records_empty_watchlist">ウォッチリスト操作記録がありません</string>
    <string name="mark_records_empty_watched">視聴記録がありません</string>
    <string name="mark_records_empty_removed">削除操作記録がありません</string>
    <string name="mark_records_load_failed">読み込み失敗</string>
    <string name="mark_records_retry">再試行</string>
    <string name="mark_records_current_in_watchlist">現在:ウォッチリスト</string>
    <string name="mark_records_current_watched">現在:視聴済み</string>
    <string name="mark_records_current_none">現在:なし</string>
    <string name="mark_records_current_changed">変更済み</string>
    <string name="mark_records_action_add_watchlist">ウォッチリストに追加</string>
    <string name="mark_records_action_remove_watchlist">ウォッチリストから削除</string>
    <string name="mark_records_action_unmark_watched">視聴済み解除</string>
    <string name="mark_records_action_watched">視聴済み</string>
    <string name="mark_records_settings_entry">マーク記録</string>
    <string name="mark_records_settings_entry_desc">ウォッチリスト/視聴済み/削除の操作履歴を表示</string>
    <string name="mark_records_reset">リセット</string>
    <string name="mark_records_confirm">OK</string>
    <string name="mark_records_media_movie">映画</string>
    <string name="mark_records_media_show">ドラマ</string>
    <string name="mark_records_time_just_now">たった今</string>
    <string name="mark_records_time_minutes_ago">%1$d 分前</string>
    <string name="mark_records_time_hours_ago">%1$d 時間前</string>
    <string name="mark_records_time_days_ago">%1$d 日前</string>
```

- [ ] **步骤 4：在 values-ko/strings.xml 追加韩文字符串**

```xml
    <!-- 표시 기록 -->
    <string name="mark_records_title">표시 기록</string>
    <string name="mark_records_tab_all">전체</string>
    <string name="mark_records_tab_watchlist">관심목록</string>
    <string name="mark_records_tab_watched">시청함</string>
    <string name="mark_records_tab_removed">제거</string>
    <string name="mark_records_search_hint">제목 검색...</string>
    <string name="mark_records_filter_media_type">미디어 유형</string>
    <string name="mark_records_filter_date_range">날짜 범위</string>
    <string name="mark_records_filter_sort">정렬</string>
    <string name="mark_records_sort_desc">최신순</string>
    <string name="mark_records_sort_asc">오래된순</string>
    <string name="mark_records_date_preset_7d">최근 7일</string>
    <string name="mark_records_date_preset_30d">최근 30일</string>
    <string name="mark_records_date_preset_all">전체</string>
    <string name="mark_records_date_preset_custom">사용자 지정</string>
    <string name="mark_records_empty_all">표시 기록이 없습니다</string>
    <string name="mark_records_empty_watchlist">관심목록 작업 기록이 없습니다</string>
    <string name="mark_records_empty_watched">시청 기록이 없습니다</string>
    <string name="mark_records_empty_removed">제거 작업 기록이 없습니다</string>
    <string name="mark_records_load_failed">로드 실패</string>
    <string name="mark_records_retry">재시도</string>
    <string name="mark_records_current_in_watchlist">현재:관심목록</string>
    <string name="mark_records_current_watched">현재:시청함</string>
    <string name="mark_records_current_none">현재:없음</string>
    <string name="mark_records_current_changed">변경됨</string>
    <string name="mark_records_action_add_watchlist">관심목록 추가</string>
    <string name="mark_records_action_remove_watchlist">관심목록 제거</string>
    <string name="mark_records_action_unmark_watched">시청 해제</string>
    <string name="mark_records_action_watched">시청함</string>
    <string name="mark_records_settings_entry">표시 기록</string>
    <string name="mark_records_settings_entry_desc">관심목록/시청/제거 작업 기록 보기</string>
    <string name="mark_records_reset">초기화</string>
    <string name="mark_records_confirm">확인</string>
    <string name="mark_records_media_movie">영화</string>
    <string name="mark_records_media_show">드라마</string>
    <string name="mark_records_time_just_now">방금</string>
    <string name="mark_records_time_minutes_ago">%1$d 분 전</string>
    <string name="mark_records_time_hours_ago">%1$d 시간 전</string>
    <string name="mark_records_time_days_ago">%1$d 일 전</string>
```

- [ ] **步骤 5：Commit**

```bash
git add app/src/main/res/values/strings.xml app/src/main/res/values-zh/strings.xml app/src/main/res/values-ja/strings.xml app/src/main/res/values-ko/strings.xml
git commit -m "feat: 新增标记记录 4 语言国际化字符串"
```

---

### 任务 10：帮助页更新

**文件：**
- 修改：`app/src/main/java/com/tracktosearch/ui/screen/help/HelpScreen.kt`

- [ ] **步骤 1：在帮助页新增「标记记录」说明章节**

在 `HelpScreen.kt` 中找到合适的章节（如「数据管理」或「我的」相关章节），追加一个 `HelpSection`：

```kotlin
            HelpSection(title = stringResource(R.string.mark_records_settings_entry)) {
                HelpBullet(stringResource(R.string.mark_records_help_entry_location))
                HelpBullet(stringResource(R.string.mark_records_help_data_source))
                HelpBullet(stringResource(R.string.mark_records_help_history_limit))
            }
```

- [ ] **步骤 2：在 4 个 strings.xml 追加帮助说明字符串**

中文：
```xml
    <string name="mark_records_help_entry_location">入口：设置页顶部「标记记录」卡片（需登录）</string>
    <string name="mark_records_help_data_source">已看记录来自 Trakt 观看历史；想看/移除记录为 App 内操作流水</string>
    <string name="mark_records_help_history_limit">功能上线前的想看/移除操作无法追溯（Trakt 不留存）</string>
```

英文：
```xml
    <string name="mark_records_help_entry_location">Entry: Settings → Mark Records card (login required)</string>
    <string name="mark_records_help_data_source">Watched records from Trakt history; watchlist/removed records from in-app action log</string>
    <string name="mark_records_help_history_limit">Watchlist/removal actions before feature launch cannot be traced (Trakt doesn\'t retain them)</string>
```

日文：
```xml
    <string name="mark_records_help_entry_location">入口：設定ページ上部の「マーク記録」カード（ログイン必要）</string>
    <string name="mark_records_help_data_source">視聴記録はTrakt履歴から、ウォッチリスト/削除記録はアプリ内操作ログから取得</string>
    <string name="mark_records_help_history_limit">機能リリース前のウォッチリスト/削除操作は追溯できません（Traktに保存されないため）</string>
```

韩文：
```xml
    <string name="mark_records_help_entry_location">입구: 설정 페이지 상단의 「표시 기록」 카드 (로그인 필요)</string>
    <string name="mark_records_help_data_source">시청 기록은 Trakt 히스토리에서, 관심목록/제거 기록은 앱 내 작업 로그에서 가져옵니다</string>
    <string name="mark_records_help_history_limit">기능 출시 전의 관심목록/제거 작업은 추적할 수 없습니다 (Trakt에 보관되지 않음)</string>
```

- [ ] **步骤 3：Commit**

```bash
git add app/src/main/java/com/tracktosearch/ui/screen/help/HelpScreen.kt app/src/main/res/values/strings.xml app/src/main/res/values-zh/strings.xml app/src/main/res/values-ja/strings.xml app/src/main/res/values-ko/strings.xml
git commit -m "docs: 帮助页新增「标记记录」说明"
```

---

### 任务 11：最终构建验证

- [ ] **步骤 1：运行全部单元测试**

运行：`.\gradlew testDebugUnitTest -i`
预期：所有测试 PASS，无新增失败

- [ ] **步骤 2：构建 debug 包**

运行：`.\gradlew assembleDebug`
预期：BUILD SUCCESSFUL

- [ ] **步骤 3：手动验证清单**

（由用户在设备上验证，不在计划步骤内执行）
- [ ] 设置页登录后可见「标记记录」入口卡片
- [ ] 点击进入，默认「全部」Tab，显示最近记录
- [ ] 切换「想看/已看/移除」Tab，数据正确
- [ ] 搜索框输入标题，列表筛选正确
- [ ] 筛选弹窗选媒体类型/日期范围/排序，列表更新
- [ ] 点击列表项跳转详情页
- [ ] 列表项当前状态徽标正确显示
- [ ] App 内做加想看/移除想看/取消已看操作后，记录出现在「标记记录」页
- [ ] 退出登录后设置页入口消失

---

## 自检

### 1. 规格覆盖度

| 规格章节 | 对应任务 | 状态 |
|---|---|---|
| 1. 架构与数据来源 | 任务 3（Trakt API）、任务 4（Repository 写入） | ✅ |
| 2. 数据模型 | 任务 1（Entity）、任务 2（DAO） | ✅ |
| 3. 写入时机 | 任务 4（4 个方法写流水） | ✅ |
| 4. Trakt /sync/history 拉取 | 任务 3（DTO+API）、任务 4（fetchWatchHistory） | ✅ |
| 5. UI 设计 | 任务 6（组件）、任务 7（Screen） | ✅ |
| 6. ViewModel | 任务 5 | ✅ |
| 7. 导航 | 任务 8 | ✅ |
| 8. 国际化 | 任务 9 | ✅ |
| 9. 帮助页 | 任务 10 | ✅ |
| 10. 测试 | 任务 2（DAO）、任务 5（ViewModel） | ✅ |
| 11. 实现顺序 | 任务 1→11 顺序与规格一致 | ✅ |
| 12. 风险与注意事项 | 任务 4 步骤 6（unmarkEpisodeWatched 改签名）、任务 4 步骤 2（insertMarkRecord 含超限清理） | ✅ |

### 2. 占位符扫描

- 任务 6 步骤 1 的 `stringResource` 私有函数是错误示范，已在注释中说明正确实现方式
- 任务 6 步骤 1 的 `formatRelativeTime` 初版硬编码中文，已修正为带 context 参数的版本
- 任务 7 步骤 2 的 `"电影"/"剧集"` 硬编码，已注明改用 stringResource
- 任务 7 步骤 2 的自定义日期范围标注"待实现"，初版可只支持预设，已在注释说明
- 所有 import 缺失已在注释中标明需补齐

### 3. 类型一致性

- `MarkActionType` 枚举值在任务 1 定义，任务 2 测试、任务 4 Repository、任务 5 ViewModel 均引用 `MarkActionType.ADD_WATCHLIST.value` 等，一致
- `MarkRecordTab` 在任务 5 定义，任务 7 Screen 引用 `MarkRecordTab.entries` / `MarkRecordTab.ALL`，一致
- `MarkRecordItem` 在任务 5 定义，任务 6 组件引用其字段（`traktId/tmdbId/title/mediaType/actionType/currentStatus`），一致
- `WatchHistoryPage` / `WatchHistoryItem` 在任务 4 定义，任务 5 ViewModel 的 `loadFromTraktHistory` 引用 `historyPage.items`，一致
- `fetchWatchHistory(page: Int)` 签名在任务 4 定义，任务 5 调用一致
- `insertMarkRecord` 签名在任务 4 定义，4 个调用点参数一致
- `unmarkEpisodeWatched` 改签名后参数名 `season/episode/showTraktId/showTmdbId/showTitle`，任务 4 步骤 7 调用处一致

### 4. 规格遗漏检查

- 规格 5.5 提到"当前状态徽标批量查询 O(1)"：任务 5 的 `updateCurrentStatusMap` 用 Set 查询，符合
- 规格 2.5 保留策略"上限 10000 条"：任务 4 步骤 2 的 `insertMarkRecord` 实现了超限清理，符合
- 规格 1.3 "退出登录不清空数据"：任务 4 步骤 8 的 `clearWatchlistWatchedCache` 只清缓存不删表，符合
