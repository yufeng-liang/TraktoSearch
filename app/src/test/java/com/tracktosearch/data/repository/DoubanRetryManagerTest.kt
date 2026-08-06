package com.tracktosearch.data.repository

import com.google.common.truth.Truth.assertThat
import com.tracktosearch.data.local.db.DoubanSyncFailureDao
import com.tracktosearch.data.local.db.DoubanSyncFailureEntity
import com.tracktosearch.data.local.db.DoubanSyncedItem
import com.tracktosearch.data.local.db.DoubanSyncedItemDao
import com.tracktosearch.data.remote.douban.DoubanDetailCacheEntry
import com.tracktosearch.data.remote.douban.DoubanDetailInfo
import com.tracktosearch.data.remote.douban.DoubanMarkStatus
import io.mockk.clearMocks
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runTest
import org.junit.Before
import org.junit.Test

/**
 * DoubanRetryManager 单元测试。
 *
 * 覆盖点：
 * - refreshRetryState：有失败项统计、空表零状态
 * - startRetryFromLocal/startRetryFromJson：有数据调用 startRetry、空数据返回 false
 * - clearAllFailures：调用 dao.clearAll 并重置 RetryState
 * - getAllFailures/getFailure：fromEntity 映射、存在/不存在
 * - inferMediaTypeFromDetail：综艺/纪录片强覆盖、已有标注不覆盖、episodeCount 推断、条目不存在
 * - batchUpdateMediaType/batchDeleteFailures：空列表不调用、有列表调用 dao 并并发上传
 * - updateSubtitle：空串转 null、非空 trim
 * - updateStatus/deleteFailure：调用对应 dao 方法
 * - refreshMediaTypesFromCloudPool：无 null 返回 0、有数据填充、mediaType 为 null 跳过
 *
 * 注意：DoubanRetryManager init 块会启动协程监听 doubanSyncManager.progress 的 isComplete 变化，
 * 测试中必须 mock progress 返回有效的 MutableStateFlow（初始 isComplete=false 避免触发副作用）。
 *
 * 关于参数匹配：DAO 方法（updateMediaType/updateSubtitle/updateStatus/updateMediaTypeBatch）
 * 有默认参数 now: Long，实际调用会传3个参数。coVerify 时用 any() 匹配 now，
 * 其余参数用原始值（mockk 会自动包装为 eq()）。
 */
class DoubanRetryManagerTest {

    // init 块依赖：必须返回有效 StateFlow，否则 NPE
    private val syncProgress = MutableStateFlow(DoubanSyncProgress())

    private val doubanSyncManager = mockk<DoubanSyncManager>(relaxed = true).also {
        every { it.progress } returns syncProgress
    }
    private val doubanSyncFailureDao = mockk<DoubanSyncFailureDao>(relaxed = true)
    private val cloudDetailsPoolManager = mockk<CloudDetailsPoolManager>(relaxed = true)
    private val doubanSyncedItemDao = mockk<DoubanSyncedItemDao>(relaxed = true)

    private val manager = DoubanRetryManager(
        doubanSyncFailureDao,
        doubanSyncManager,
        cloudDetailsPoolManager,
        doubanSyncedItemDao
    )

    @Before
    fun setUp() {
        // 清除前序测试的 stub 和调用记录，确保 coVerify(exactly = 0) 不受干扰
        // 注意：clearMocks 会清除 progress 的 stub，但 init 块已执行过（持有 syncProgress 引用），不受影响
        clearMocks(doubanSyncFailureDao, doubanSyncManager, cloudDetailsPoolManager, doubanSyncedItemDao)
        // 重新 stub progress（虽然 init 块不再访问，但保持 mock 状态一致）
        every { doubanSyncManager.progress } returns syncProgress
        coEvery { doubanSyncedItemDao.getByDoubanId(any()) } returns null
    }

    // ============================================================
    // 辅助函数
    // ============================================================

    /** 构造本地失败项 entity，所有字段可定制 */
    private fun buildFailureEntity(
        doubanId: String = "db-001",
        title: String = "测试电影",
        posterUrl: String? = "https://img.example.com/p.jpg",
        rating: Int? = 5,
        comment: String? = "好片",
        markedAt: String = "2024-01-15",
        doubanUrl: String = "https://book.douban.com/subject/db-001/",
        status: String = "wish",
        failureReason: String = "TRAKT_WRITE_FAILED",
        failedAt: Long = 1000L,
        updatedAt: Long = 1000L,
        attemptCount: Int = 1,
        mediaType: String? = "movie",
        mediaTypeCleared: Boolean = false,
        subtitle: String? = null
    ): DoubanSyncFailureEntity = DoubanSyncFailureEntity(
        doubanId = doubanId,
        title = title,
        posterUrl = posterUrl,
        rating = rating,
        comment = comment,
        markedAt = markedAt,
        doubanUrl = doubanUrl,
        status = status,
        failureReason = failureReason,
        failedAt = failedAt,
        updatedAt = updatedAt,
        attemptCount = attemptCount,
        mediaType = mediaType,
        mediaTypeCleared = mediaTypeCleared,
        subtitle = subtitle
    )

    /** 构造包含完整豆瓣快照字段的同步条目。 */
    private fun buildSyncedItem(
        doubanId: String = "db-synced-001",
        subtitle: String? = "原始子标题"
    ): DoubanSyncedItem = DoubanSyncedItem(
        doubanId = doubanId,
        imdbId = "tt1234567",
        traktId = 314,
        title = "快照电影",
        status = "collect",
        rating = 4,
        syncedAt = 2000L,
        mediaType = "movie",
        tmdbId = 2718,
        displayTitle = "快照电影显示标题",
        year = 2024,
        genres = "剧情",
        posterUrl = "https://img.example.com/snapshot.jpg",
        listedAt = "2024-02-03",
        pendingSync = true,
        doubanUrl = "https://movie.douban.com/subject/$doubanId/",
        comment = "原始短评",
        markedAt = "2024-02-02 12:34:56",
        subtitle = subtitle
    )

    /** 构造豆瓣详情信息，必填字段 + 可选覆盖 */
    private fun buildDetailInfo(
        imdbId: String? = null,
        isTvShow: Boolean = false,
        genres: List<String> = emptyList(),
        episodeCount: Int? = null,
        title: String? = null,
        posterUrl: String? = null,
        year: String? = null,
        countries: List<String> = emptyList(),
        directors: List<String> = emptyList()
    ): DoubanDetailInfo = DoubanDetailInfo(
        imdbId = imdbId,
        isTvShow = isTvShow,
        title = title,
        posterUrl = posterUrl,
        genres = genres,
        year = year,
        countries = countries,
        directors = directors,
        episodeCount = episodeCount
    )

    /** 构造全局池缓存条目 */
    private fun buildCacheEntry(
        imdbId: String? = null,
        isTvShow: Boolean = false,
        mediaType: String? = null
    ): DoubanDetailCacheEntry = DoubanDetailCacheEntry(
        imdbId = imdbId,
        isTvShow = isTvShow,
        mediaType = mediaType
    )

    // ============================================================
    // refreshRetryState 测试
    // ============================================================

    @Test
    fun refreshRetryState_有失败项_更新RetryState统计() = runTest {
        // 构造含不同 failureReason 和 attemptCount 的 entity
        val entities = listOf(
            buildFailureEntity(doubanId = "db-1", failureReason = "TRAKT_WRITE_FAILED", attemptCount = 1),
            buildFailureEntity(doubanId = "db-2", failureReason = "NO_IMDB_ID", attemptCount = 3),
            buildFailureEntity(doubanId = "db-3", failureReason = "TRAKT_NOT_FOUND", attemptCount = 2)
        )
        coEvery { doubanSyncFailureDao.getAll() } returns entities

        manager.refreshRetryState()

        val state = manager.retryState.value
        assertThat(state.totalFailures).isEqualTo(3)
        // 可恢复：TRAKT_WRITE_FAILED（true）→ 1；NO_IMDB_ID（false）、TRAKT_NOT_FOUND（false）→ 2
        assertThat(state.recoverableCount).isEqualTo(1)
        assertThat(state.nonRecoverableCount).isEqualTo(2)
        assertThat(state.maxAttemptCount).isEqualTo(3)
        assertThat(state.byReason[FailureReason.TRAKT_WRITE_FAILED]).isEqualTo(1)
        assertThat(state.byReason[FailureReason.NO_IMDB_ID]).isEqualTo(1)
        assertThat(state.byReason[FailureReason.TRAKT_NOT_FOUND]).isEqualTo(1)
        assertThat(state.hasFailures).isTrue()
    }

    @Test
    fun refreshRetryState_空表_RetryState全为零() = runTest {
        coEvery { doubanSyncFailureDao.getAll() } returns emptyList()

        manager.refreshRetryState()

        val state = manager.retryState.value
        assertThat(state.totalFailures).isEqualTo(0)
        assertThat(state.recoverableCount).isEqualTo(0)
        assertThat(state.nonRecoverableCount).isEqualTo(0)
        assertThat(state.maxAttemptCount).isEqualTo(0)
        assertThat(state.byReason).isEmpty()
        assertThat(state.hasFailures).isFalse()
    }

    // ============================================================
    // startRetryFromLocal 测试
    // ============================================================

    @Test
    fun startRetryFromLocal_有失败项_调用SyncManagerStartRetry() = runTest {
        val entities = listOf(buildFailureEntity(doubanId = "db-1"))
        coEvery { doubanSyncFailureDao.getAll() } returns entities
        // startRetry 是非 suspend 函数，用 every
        every { doubanSyncManager.startRetry(any(), any()) } returns true

        val result = manager.startRetryFromLocal(setOf(FailureReason.TRAKT_WRITE_FAILED))

        assertThat(result).isTrue()
        verify(exactly = 1) { doubanSyncManager.startRetry(any(), any()) }
    }

    @Test
    fun startRetryFromLocal_无失败项_returnsFalse() = runTest {
        coEvery { doubanSyncFailureDao.getAll() } returns emptyList()

        val result = manager.startRetryFromLocal(setOf(FailureReason.TRAKT_WRITE_FAILED))

        assertThat(result).isFalse()
        verify(exactly = 0) { doubanSyncManager.startRetry(any(), any()) }
    }

    // ============================================================
    // startRetryFromJson 测试
    // ============================================================

    @Test
    fun startRetryFromJson_有数据_调用SyncManagerStartRetry() = runTest {
        val failures = listOf(DoubanSyncFailure.fromEntity(buildFailureEntity(doubanId = "db-1")))
        every { doubanSyncManager.startRetry(any(), any()) } returns true

        val result = manager.startRetryFromJson(failures, setOf(FailureReason.TRAKT_WRITE_FAILED))

        assertThat(result).isTrue()
        verify(exactly = 1) { doubanSyncManager.startRetry(any(), any()) }
    }

    @Test
    fun startRetryFromJson_空列表_returnsFalse() {
        val result = manager.startRetryFromJson(emptyList(), setOf(FailureReason.TRAKT_WRITE_FAILED))

        assertThat(result).isFalse()
        verify(exactly = 0) { doubanSyncManager.startRetry(any(), any()) }
    }

    // ============================================================
    // clearAllFailures 测试
    // ============================================================

    @Test
    fun clearAllFailures_调用DaoClearAll并重置RetryState() = runTest {
        // 先设置一个非空状态，验证 clearAll 后重置
        coEvery { doubanSyncFailureDao.getAll() } returns listOf(buildFailureEntity())
        manager.refreshRetryState()
        assertThat(manager.retryState.value.totalFailures).isEqualTo(1)

        manager.clearAllFailures()

        coVerify(exactly = 1) { doubanSyncFailureDao.clearAll() }
        val state = manager.retryState.value
        assertThat(state.totalFailures).isEqualTo(0)
        assertThat(state.hasFailures).isFalse()
    }

    // ============================================================
    // getAllFailures / getFailure 测试
    // ============================================================

    @Test
    fun getAllFailures_返回DoubanSyncFailure列表() = runTest {
        val entities = listOf(
            buildFailureEntity(doubanId = "db-1", title = "电影A"),
            buildFailureEntity(doubanId = "db-2", title = "电影B")
        )
        coEvery { doubanSyncFailureDao.getAll() } returns entities

        val result = manager.getAllFailures()

        assertThat(result).hasSize(2)
        assertThat(result[0].doubanId).isEqualTo("db-1")
        assertThat(result[0].title).isEqualTo("电影A")
        assertThat(result[1].doubanId).isEqualTo("db-2")
        assertThat(result[1].title).isEqualTo("电影B")
    }

    @Test
    fun getFailure_存在_returnsDoubanSyncFailure() = runTest {
        val entity = buildFailureEntity(doubanId = "db-1", title = "测试电影")
        coEvery { doubanSyncFailureDao.getById("db-1") } returns entity

        val result = manager.getFailure("db-1")

        assertThat(result).isNotNull()
        assertThat(result!!.doubanId).isEqualTo("db-1")
        assertThat(result.title).isEqualTo("测试电影")
    }

    @Test
    fun getFailure_不存在_returnsNull() = runTest {
        coEvery { doubanSyncFailureDao.getById("db-999") } returns null

        val result = manager.getFailure("db-999")

        assertThat(result).isNull()
    }

    @Test
    fun getFailure_fallbackPreservesSyncedItemSnapshotFields() = runTest {
        val item = buildSyncedItem()
        coEvery { doubanSyncFailureDao.getById(item.doubanId) } returns null
        coEvery { doubanSyncedItemDao.getByDoubanId(item.doubanId) } returns item

        val result = manager.getFailure(item.doubanId)

        assertThat(result).isNotNull()
        assertThat(result!!.doubanId).isEqualTo(item.doubanId)
        assertThat(result.title).isEqualTo(item.title)
        assertThat(result.posterUrl).isEqualTo(item.posterUrl)
        assertThat(result.rating).isEqualTo(item.rating)
        assertThat(result.status.path).isEqualTo(item.status)
        assertThat(result.mediaType).isEqualTo(item.mediaType)
        assertThat(result.doubanUrl).isEqualTo(item.doubanUrl)
        assertThat(result.comment).isEqualTo(item.comment)
        assertThat(result.markedAt).isEqualTo(item.markedAt)
        assertThat(result.subtitle).isEqualTo(item.subtitle)
    }

    // ============================================================
    // inferMediaTypeFromDetail 测试（重点，逻辑复杂）
    // ============================================================

    @Test
    fun inferMediaTypeFromDetail_综艺genres_强覆盖为Variety() = runTest {
        // existing.mediaType = "movie"，genres 含"综艺" → 强覆盖为 "variety"
        val entity = buildFailureEntity(doubanId = "db-1", mediaType = "movie")
        coEvery { doubanSyncFailureDao.getById("db-1") } returns entity
        val detail = buildDetailInfo(genres = listOf("综艺"), episodeCount = 10)

        val result = manager.inferMediaTypeFromDetail("db-1", detail)

        assertThat(result).isTrue()
        // updateMediaType 有默认参数 now，实际调用传3个参数，用 any() 匹配 now
        coVerify(exactly = 1) {
            doubanSyncFailureDao.updateMediaType("db-1", "variety", any())
        }
        coVerify(exactly = 1) {
            cloudDetailsPoolManager.uploadUserMarkedMediaType("db-1", "variety")
        }
    }

    @Test
    fun inferMediaTypeFromDetail_纪录片genres_强覆盖为Documentary() = runTest {
        val entity = buildFailureEntity(doubanId = "db-1", mediaType = "movie")
        coEvery { doubanSyncFailureDao.getById("db-1") } returns entity
        val detail = buildDetailInfo(genres = listOf("纪录片"), episodeCount = 5)

        val result = manager.inferMediaTypeFromDetail("db-1", detail)

        assertThat(result).isTrue()
        coVerify(exactly = 1) {
            doubanSyncFailureDao.updateMediaType("db-1", "documentary", any())
        }
        coVerify(exactly = 1) {
            cloudDetailsPoolManager.uploadUserMarkedMediaType("db-1", "documentary")
        }
    }

    @Test
    fun inferMediaTypeFromDetail_已有标注且非强覆盖_returnsFalse不更新() = runTest {
        // existing.mediaType = "movie"（非 null），genres 无强覆盖类型 → 返回 false，不更新
        val entity = buildFailureEntity(doubanId = "db-1", mediaType = "movie")
        coEvery { doubanSyncFailureDao.getById("db-1") } returns entity
        val detail = buildDetailInfo(genres = listOf("剧情"), episodeCount = 10)

        val result = manager.inferMediaTypeFromDetail("db-1", detail)

        assertThat(result).isFalse()
        coVerify(exactly = 0) {
            doubanSyncFailureDao.updateMediaType(any(), any(), any())
        }
        coVerify(exactly = 0) {
            cloudDetailsPoolManager.uploadUserMarkedMediaType(any(), any())
        }
    }

    @Test
    fun inferMediaTypeFromDetail_episodeCount大于0_推断为Show() = runTest {
        // mediaType=null，episodeCount=10 → "show"
        val entity = buildFailureEntity(doubanId = "db-1", mediaType = null)
        coEvery { doubanSyncFailureDao.getById("db-1") } returns entity
        val detail = buildDetailInfo(genres = listOf("剧情"), episodeCount = 10)

        val result = manager.inferMediaTypeFromDetail("db-1", detail)

        assertThat(result).isTrue()
        coVerify(exactly = 1) {
            doubanSyncFailureDao.updateMediaType("db-1", "show", any())
        }
        coVerify(exactly = 1) {
            cloudDetailsPoolManager.uploadUserMarkedMediaType("db-1", "show")
        }
    }

    @Test
    fun inferMediaTypeFromDetail_episodeCount为零_推断为Movie() = runTest {
        // mediaType=null，episodeCount=0 → 0 > 0 为 false → "movie"
        val entity = buildFailureEntity(doubanId = "db-1", mediaType = null)
        coEvery { doubanSyncFailureDao.getById("db-1") } returns entity
        val detail = buildDetailInfo(genres = listOf("剧情"), episodeCount = 0)

        val result = manager.inferMediaTypeFromDetail("db-1", detail)

        assertThat(result).isTrue()
        coVerify(exactly = 1) {
            doubanSyncFailureDao.updateMediaType("db-1", "movie", any())
        }
        coVerify(exactly = 1) {
            cloudDetailsPoolManager.uploadUserMarkedMediaType("db-1", "movie")
        }
    }

    @Test
    fun inferMediaTypeFromDetail_episodeCount为Null_推断为Movie() = runTest {
        // mediaType=null，episodeCount=null → null != null 为 false → "movie"
        val entity = buildFailureEntity(doubanId = "db-1", mediaType = null)
        coEvery { doubanSyncFailureDao.getById("db-1") } returns entity
        val detail = buildDetailInfo(genres = listOf("剧情"), episodeCount = null)

        val result = manager.inferMediaTypeFromDetail("db-1", detail)

        assertThat(result).isTrue()
        coVerify(exactly = 1) {
            doubanSyncFailureDao.updateMediaType("db-1", "movie", any())
        }
        coVerify(exactly = 1) {
            cloudDetailsPoolManager.uploadUserMarkedMediaType("db-1", "movie")
        }
    }

    @Test
    fun inferMediaTypeFromDetail_条目不存在_returnsFalse() = runTest {
        coEvery { doubanSyncFailureDao.getById("db-999") } returns null
        val detail = buildDetailInfo(genres = listOf("剧情"), episodeCount = 10)

        val result = manager.inferMediaTypeFromDetail("db-999", detail)

        assertThat(result).isFalse()
        coVerify(exactly = 0) {
            doubanSyncFailureDao.updateMediaType(any(), any(), any())
        }
    }

    // ============================================================
    // batchUpdateMediaType / batchDeleteFailures 测试
    // ============================================================

    @Test
    fun batchUpdateMediaType_空列表_不调用Dao() = runTest {
        manager.batchUpdateMediaType(emptyList(), "movie")

        coVerify(exactly = 0) {
            doubanSyncFailureDao.updateMediaTypeBatch(any(), any(), any())
        }
        coVerify(exactly = 0) {
            cloudDetailsPoolManager.uploadUserMarkedMediaType(any(), any())
        }
    }

    @Test
    fun batchUpdateMediaType_有列表_调用DaoUpdateMediaTypeBatch并并发上传池() = runTest {
        val ids = listOf("db-1", "db-2", "db-3")
        coEvery { cloudDetailsPoolManager.uploadUserMarkedMediaType(any(), any()) } returns true

        manager.batchUpdateMediaType(ids, "movie")

        coVerify(exactly = 1) {
            doubanSyncFailureDao.updateMediaTypeBatch(ids, "movie", any())
        }
        // 并发上传池，每个 id 调用一次
        coVerify(exactly = 3) {
            cloudDetailsPoolManager.uploadUserMarkedMediaType(any(), "movie")
        }
    }

    @Test
    fun batchDeleteFailures_空列表_不调用Dao() = runTest {
        manager.batchDeleteFailures(emptyList())

        coVerify(exactly = 0) {
            doubanSyncFailureDao.deleteByDoubanIds(any())
        }
    }

    @Test
    fun batchDeleteFailures_有列表_调用DaoDeleteByDoubanIds() = runTest {
        val ids = listOf("db-1", "db-2")

        manager.batchDeleteFailures(ids)

        coVerify(exactly = 1) {
            doubanSyncFailureDao.deleteByDoubanIds(ids)
        }
    }

    // ============================================================
    // updateSubtitle 测试（边界）
    // ============================================================

    @Test
    fun updateSubtitle_空串_转为Null存储() = runTest {
        // subtitle=" " → trim 后为 "" → isNotBlank 为 false → takeIf 返回 null → 存储 null
        val existing = buildSyncedItem(doubanId = "db-1")
        coEvery { doubanSyncedItemDao.getByDoubanId("db-1") } returns existing

        manager.updateSubtitle("db-1", " ")

        // 验证调用 updateSubtitle 时 subtitle 参数为 null
        // 注意：null as String? 用于显式标注类型，避免 Kotlin 类型推断为 Nothing?
        coVerify(exactly = 1) {
            doubanSyncFailureDao.updateSubtitle("db-1", null as String?, any())
        }
        coVerify(exactly = 1) {
            doubanSyncedItemDao.update(existing.copy(subtitle = null))
        }
    }

    @Test
    fun updateSubtitle_非空_trim后存储() = runTest {
        // subtitle=" 标题 " → trim 后为 "标题" → isNotBlank 为 true → 存储 "标题"
        val existing = buildSyncedItem(doubanId = "db-1")
        coEvery { doubanSyncedItemDao.getByDoubanId("db-1") } returns existing

        manager.updateSubtitle("db-1", " 标题 ")

        coVerify(exactly = 1) {
            doubanSyncFailureDao.updateSubtitle("db-1", "标题", any())
        }
        coVerify(exactly = 1) {
            doubanSyncedItemDao.update(existing.copy(subtitle = "标题"))
        }
    }

    // ============================================================
    // updateStatus / deleteFailure 测试
    // ============================================================

    @Test
    fun updateStatus_调用DaoUpdateStatusWithPath() = runTest {
        manager.updateStatus("db-1", DoubanMarkStatus.WISH)

        coVerify(exactly = 1) {
            doubanSyncFailureDao.updateStatus("db-1", "wish", any())
        }
        coVerify(exactly = 1) {
            doubanSyncedItemDao.updateStatusAndPendingSync(
                doubanId = "db-1",
                status = "wish",
                pendingSync = false,
                now = any()
            )
        }
    }

    @Test
    fun updateStatus_调用DaoUpdateStatusWithCollectPath() = runTest {
        manager.updateStatus("db-1", DoubanMarkStatus.COLLECT)

        coVerify(exactly = 1) {
            doubanSyncFailureDao.updateStatus("db-1", "collect", any())
        }
    }

    @Test
    fun deleteFailure_调用DaoDeleteByDoubanId() = runTest {
        manager.deleteFailure("db-1")

        coVerify(exactly = 1) {
            doubanSyncFailureDao.deleteByDoubanId("db-1")
        }
    }

    // ============================================================
    // refreshMediaTypesFromCloudPool 测试
    // ============================================================

    @Test
    fun refreshMediaTypesFromCloudPool_无NullMediaType_returnsZero() = runTest {
        coEvery { doubanSyncFailureDao.getDoubanIdsWithNullMediaType() } returns emptyList()

        val result = manager.refreshMediaTypesFromCloudPool()

        assertThat(result).isEqualTo(0)
        coVerify(exactly = 0) {
            cloudDetailsPoolManager.downloadDetails(any())
        }
        coVerify(exactly = 0) {
            doubanSyncFailureDao.updateMediaTypeIfNull(any(), any())
        }
    }

    @Test
    fun refreshMediaTypesFromCloudPool_池中有数据_填充并返回数量() = runTest {
        val nullIds = listOf("db-1", "db-2")
        coEvery { doubanSyncFailureDao.getDoubanIdsWithNullMediaType() } returns nullIds
        coEvery { cloudDetailsPoolManager.downloadDetails(nullIds) } returns mapOf(
            "db-1" to buildCacheEntry(imdbId = null, isTvShow = false, mediaType = "movie"),
            "db-2" to buildCacheEntry(imdbId = null, isTvShow = true, mediaType = "show")
        )

        val result = manager.refreshMediaTypesFromCloudPool()

        assertThat(result).isEqualTo(2)
        coVerify(exactly = 1) {
            doubanSyncFailureDao.updateMediaTypeIfNull("db-1", "movie")
        }
        coVerify(exactly = 1) {
            doubanSyncFailureDao.updateMediaTypeIfNull("db-2", "show")
        }
    }

    @Test
    fun refreshMediaTypesFromCloudPool_池中mediaType为Null_跳过() = runTest {
        val nullIds = listOf("db-1")
        coEvery { doubanSyncFailureDao.getDoubanIdsWithNullMediaType() } returns nullIds
        coEvery { cloudDetailsPoolManager.downloadDetails(nullIds) } returns mapOf(
            "db-1" to buildCacheEntry(imdbId = null, isTvShow = false, mediaType = null)
        )

        val result = manager.refreshMediaTypesFromCloudPool()

        // entry.mediaType 为 null → continue 跳过，updated 不增加
        assertThat(result).isEqualTo(0)
        coVerify(exactly = 0) {
            doubanSyncFailureDao.updateMediaTypeIfNull(any(), any())
        }
    }
}
