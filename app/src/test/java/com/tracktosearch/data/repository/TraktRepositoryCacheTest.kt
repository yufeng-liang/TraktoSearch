package com.tracktosearch.data.repository

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import com.tracktosearch.data.local.UserProfileStorage
import com.tracktosearch.data.local.db.MarkActionRecordDao
import com.tracktosearch.data.local.db.MediaDetailDao
import com.tracktosearch.data.remote.trakt.TraktApiService
import com.tracktosearch.data.repository.TraktRepository.WatchlistWatchedIds
import io.mockk.mockk
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.lang.reflect.Method

/**
 * TraktRepository 缓存一致性逻辑单元测试。
 *
 * 聚焦：
 * - 4 个 private 缓存更新方法（addToWatchlistCache/removeFromWatchlistCache/addToWatchedCache/removeFromWatchedCache）
 *   核心业务契约：加已看自动从想看移除、取消已看自动加回想看
 * - WatchlistWatchedIds 嵌套类纯函数（isInWatchlist/isWatched/traktIdByTmdb）
 * - getLocallyWatchedOnlyTraktIds / getLocallyWatchlistOnlyTraktIds 集合差集
 * - batchCheckStatus 空列表短路 / 缓存命中
 *
 * private 方法通过反射测试。watchlistWatchedIds 字段通过反射预设。
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class TraktRepositoryCacheTest {

    private lateinit var traktApiService: TraktApiService
    private lateinit var userProfileStorage: UserProfileStorage
    private lateinit var markActionRecordDao: MarkActionRecordDao
    private lateinit var mediaDetailDao: MediaDetailDao
    private lateinit var repository: TraktRepository

    private val context: Context get() = ApplicationProvider.getApplicationContext()

    @Before
    fun setup() = runTest {
        traktApiService = mockk(relaxed = true)
        userProfileStorage = mockk(relaxed = true)
        markActionRecordDao = mockk(relaxed = true)
        mediaDetailDao = mockk(relaxed = true)

        repository = TraktRepository(
            traktApiService, userProfileStorage, markActionRecordDao,
            mediaDetailDao, Json { ignoreUnknownKeys = true }, context
        )
    }

    // ==================== 反射辅助 ====================

    private fun setWatchlistWatchedIds(ids: WatchlistWatchedIds?) {
        val field = TraktRepository::class.java.getDeclaredField("watchlistWatchedIds")
        field.isAccessible = true
        field.set(repository, ids)
    }

    private fun getWatchlistWatchedIds(): WatchlistWatchedIds? {
        val field = TraktRepository::class.java.getDeclaredField("watchlistWatchedIds")
        field.isAccessible = true
        return field.get(repository) as WatchlistWatchedIds?
    }

    private fun invokePrivate(methodName: String, traktId: Int, tmdbId: Int, type: MediaType) {
        val method: Method = TraktRepository::class.java.getDeclaredMethod(
            methodName, Int::class.javaPrimitiveType, Int::class.javaPrimitiveType, MediaType::class.java
        )
        method.isAccessible = true
        method.invoke(repository, traktId, tmdbId, type)
    }

    // ==================== addToWatchlistCache ====================

    @Test
    fun `addToWatchlistCache_MOVIE_添加traktId和tmdbId`() {
        setWatchlistWatchedIds(WatchlistWatchedIds())
        invokePrivate("addToWatchlistCache", 100, 200, MediaType.MOVIE)

        val ids = getWatchlistWatchedIds()!!
        assertThat(ids.movieWatchlistTraktIds).contains(100)
        assertThat(ids.movieWatchlistTmdbIds).contains(200)
    }

    @Test
    fun `addToWatchlistCache_SHOW_添加到show集合不影响movie集合`() {
        setWatchlistWatchedIds(WatchlistWatchedIds())
        invokePrivate("addToWatchlistCache", 100, 200, MediaType.SHOW)

        val ids = getWatchlistWatchedIds()!!
        assertThat(ids.showWatchlistTraktIds).contains(100)
        assertThat(ids.showWatchlistTmdbIds).contains(200)
        assertThat(ids.movieWatchlistTraktIds).doesNotContain(100)
    }

    @Test
    fun `addToWatchlistCache_tmdbId为0时不添加tmdbId`() {
        setWatchlistWatchedIds(WatchlistWatchedIds())
        invokePrivate("addToWatchlistCache", 100, 0, MediaType.MOVIE)

        val ids = getWatchlistWatchedIds()!!
        assertThat(ids.movieWatchlistTraktIds).contains(100)
        assertThat(ids.movieWatchlistTmdbIds).isEmpty()
    }

    @Test
    fun `addToWatchlistCache_缓存为null时无操作`() {
        setWatchlistWatchedIds(null)
        invokePrivate("addToWatchlistCache", 100, 200, MediaType.MOVIE)
        // 不崩溃即通过
        assertThat(getWatchlistWatchedIds()).isNull()
    }

    @Test
    fun `addToWatchlistCache_PERSON类型不变更缓存`() {
        val initial = WatchlistWatchedIds(movieWatchlistTraktIds = setOf(1))
        setWatchlistWatchedIds(initial)
        invokePrivate("addToWatchlistCache", 100, 200, MediaType.PERSON)

        val ids = getWatchlistWatchedIds()!!
        assertThat(ids.movieWatchlistTraktIds).doesNotContain(100)
        assertThat(ids).isEqualTo(initial)
    }

    // ==================== removeFromWatchlistCache ====================

    @Test
    fun `removeFromWatchlistCache_MOVIE_从想看移除`() {
        setWatchlistWatchedIds(WatchlistWatchedIds(
            movieWatchlistTraktIds = setOf(100, 200),
            movieWatchlistTmdbIds = setOf(1000, 2000)
        ))
        invokePrivate("removeFromWatchlistCache", 100, 1000, MediaType.MOVIE)

        val ids = getWatchlistWatchedIds()!!
        assertThat(ids.movieWatchlistTraktIds).contains(200)
        assertThat(ids.movieWatchlistTraktIds).doesNotContain(100)
        assertThat(ids.movieWatchlistTmdbIds).contains(2000)
        assertThat(ids.movieWatchlistTmdbIds).doesNotContain(1000)
    }

    @Test
    fun `removeFromWatchlistCache_移除不存在的ID无副作用`() {
        val initial = WatchlistWatchedIds(movieWatchlistTraktIds = setOf(100))
        setWatchlistWatchedIds(initial)
        invokePrivate("removeFromWatchlistCache", 999, 9999, MediaType.MOVIE)

        val ids = getWatchlistWatchedIds()!!
        assertThat(ids.movieWatchlistTraktIds).contains(100)
        assertThat(ids.movieWatchlistTraktIds).doesNotContain(999)
    }

    // ==================== addToWatchedCache（核心一致性）====================

    @Test
    fun `addToWatchedCache_MOVIE_加已看同时从想看移除`() {
        // 初始：100 在想看中
        setWatchlistWatchedIds(WatchlistWatchedIds(
            movieWatchlistTraktIds = setOf(100),
            movieWatchlistTmdbIds = setOf(1000)
        ))
        invokePrivate("addToWatchedCache", 100, 1000, MediaType.MOVIE)

        val ids = getWatchlistWatchedIds()!!
        // 已加到已看
        assertThat(ids.movieWatchedTraktIds).contains(100)
        assertThat(ids.movieWatchedTmdbIds).contains(1000)
        // 从想看移除（一致性核心）
        assertThat(ids.movieWatchlistTraktIds).doesNotContain(100)
        assertThat(ids.movieWatchlistTmdbIds).doesNotContain(1000)
    }

    @Test
    fun `addToWatchedCache_SHOW_加已看同时从想看移除`() {
        setWatchlistWatchedIds(WatchlistWatchedIds(
            showWatchlistTraktIds = setOf(200),
            showWatchlistTmdbIds = setOf(2000)
        ))
        invokePrivate("addToWatchedCache", 200, 2000, MediaType.SHOW)

        val ids = getWatchlistWatchedIds()!!
        assertThat(ids.showWatchedTraktIds).contains(200)
        assertThat(ids.showWatchlistTraktIds).doesNotContain(200)
    }

    @Test
    fun `addToWatchedCache_不在想看中时仅加已看不移除`() {
        setWatchlistWatchedIds(WatchlistWatchedIds())
        invokePrivate("addToWatchedCache", 100, 1000, MediaType.MOVIE)

        val ids = getWatchlistWatchedIds()!!
        assertThat(ids.movieWatchedTraktIds).contains(100)
        // 想看本就为空，不减
        assertThat(ids.movieWatchlistTraktIds).isEmpty()
    }

    @Test
    fun `addToWatchedCache_不影响其他类型的集合`() {
        setWatchlistWatchedIds(WatchlistWatchedIds(
            showWatchlistTraktIds = setOf(500),
            showWatchedTraktIds = setOf(600)
        ))
        invokePrivate("addToWatchedCache", 100, 1000, MediaType.MOVIE)

        val ids = getWatchlistWatchedIds()!!
        assertThat(ids.movieWatchedTraktIds).contains(100)
        // show 集合不受影响
        assertThat(ids.showWatchlistTraktIds).contains(500)
        assertThat(ids.showWatchedTraktIds).contains(600)
    }

    // ==================== removeFromWatchedCache（核心一致性）====================

    @Test
    fun `removeFromWatchedCache_MOVIE_取消已看同时加回想看`() {
        // 初始：100 在已看中
        setWatchlistWatchedIds(WatchlistWatchedIds(
            movieWatchedTraktIds = setOf(100),
            movieWatchedTmdbIds = setOf(1000)
        ))
        invokePrivate("removeFromWatchedCache", 100, 1000, MediaType.MOVIE)

        val ids = getWatchlistWatchedIds()!!
        // 从已看移除
        assertThat(ids.movieWatchedTraktIds).doesNotContain(100)
        assertThat(ids.movieWatchedTmdbIds).doesNotContain(1000)
        // 加回想看（一致性核心）
        assertThat(ids.movieWatchlistTraktIds).contains(100)
        assertThat(ids.movieWatchlistTmdbIds).contains(1000)
    }

    @Test
    fun `removeFromWatchedCache_SHOW_取消已看同时加回想看`() {
        setWatchlistWatchedIds(WatchlistWatchedIds(
            showWatchedTraktIds = setOf(200),
            showWatchedTmdbIds = setOf(2000)
        ))
        invokePrivate("removeFromWatchedCache", 200, 2000, MediaType.SHOW)

        val ids = getWatchlistWatchedIds()!!
        assertThat(ids.showWatchedTraktIds).doesNotContain(200)
        assertThat(ids.showWatchlistTraktIds).contains(200)
    }

    @Test
    fun `removeFromWatchedCache_不在已看中时仅加想看不移除`() {
        setWatchlistWatchedIds(WatchlistWatchedIds())
        invokePrivate("removeFromWatchedCache", 100, 1000, MediaType.MOVIE)

        val ids = getWatchlistWatchedIds()!!
        assertThat(ids.movieWatchlistTraktIds).contains(100)
        assertThat(ids.movieWatchedTraktIds).isEmpty()
    }

    // ==================== WatchlistWatchedIds 嵌套类纯函数 ====================

    @Test
    fun `isInWatchlist_traktId命中返回true`() {
        val ids = WatchlistWatchedIds(movieWatchlistTraktIds = setOf(100))
        assertThat(ids.isInWatchlist(100, null, MediaType.MOVIE)).isTrue()
    }

    @Test
    fun `isInWatchlist_tmdbId命中返回true`() {
        val ids = WatchlistWatchedIds(movieWatchlistTmdbIds = setOf(1000))
        assertThat(ids.isInWatchlist(null, 1000, MediaType.MOVIE)).isTrue()
    }

    @Test
    fun `isInWatchlist_未命中返回false`() {
        val ids = WatchlistWatchedIds()
        assertThat(ids.isInWatchlist(999, 9999, MediaType.MOVIE)).isFalse()
    }

    @Test
    fun `isInWatchlist_SHOW类型只查show集合`() {
        val ids = WatchlistWatchedIds(movieWatchlistTraktIds = setOf(100))
        assertThat(ids.isInWatchlist(100, null, MediaType.SHOW)).isFalse()
    }

    @Test
    fun `isWatched_traktId命中返回true`() {
        val ids = WatchlistWatchedIds(movieWatchedTraktIds = setOf(100))
        assertThat(ids.isWatched(100, null, MediaType.MOVIE)).isTrue()
    }

    @Test
    fun `isWatched_SHOW类型只查show集合`() {
        val ids = WatchlistWatchedIds(showWatchedTraktIds = setOf(200))
        assertThat(ids.isWatched(200, null, MediaType.SHOW)).isTrue()
        assertThat(ids.isWatched(200, null, MediaType.MOVIE)).isFalse()
    }

    @Test
    fun `isWatched_PERSON类型返回false`() {
        val ids = WatchlistWatchedIds(movieWatchedTraktIds = setOf(100))
        assertThat(ids.isWatched(100, null, MediaType.PERSON)).isFalse()
    }

    @Test
    fun `traktIdByTmdb_MOVIE映射正确`() {
        val ids = WatchlistWatchedIds(movieTmdbToTrakt = mapOf(1000 to 100))
        assertThat(ids.traktIdByTmdb(1000, MediaType.MOVIE)).isEqualTo(100)
        assertThat(ids.traktIdByTmdb(9999, MediaType.MOVIE)).isNull()
    }

    @Test
    fun `traktIdByTmdb_SHOW映射正确`() {
        val ids = WatchlistWatchedIds(showTmdbToTrakt = mapOf(2000 to 200))
        assertThat(ids.traktIdByTmdb(2000, MediaType.SHOW)).isEqualTo(200)
        assertThat(ids.traktIdByTmdb(2000, MediaType.MOVIE)).isNull()
    }

    // ==================== getLocallyWatchedOnlyTraktIds / getLocallyWatchlistOnlyTraktIds ====================

    @Test
    fun `getLocallyWatchedOnlyTraktIds_MOVIE_返回已看减想看的差集`() {
        setWatchlistWatchedIds(WatchlistWatchedIds(
            movieWatchedTraktIds = setOf(100, 200, 300),
            movieWatchlistTraktIds = setOf(200)  // 200 同时在想看和已看
        ))
        val result = repository.getLocallyWatchedOnlyTraktIds(MediaType.MOVIE)
        // 差集：{100, 200, 300} - {200} = {100, 300}
        assertThat(result).containsExactly(100, 300)
    }

    @Test
    fun `getLocallyWatchedOnlyTraktIds_保留同时在想看和已看的项`() {
        setWatchlistWatchedIds(WatchlistWatchedIds(
            movieWatchedTraktIds = setOf(100, 200),
            movieWatchlistTraktIds = setOf(200)
        ))
        val result = repository.getLocallyWatchedOnlyTraktIds(MediaType.MOVIE)
        // 200 被排除（因为在想看中），100 保留
        assertThat(result).containsExactly(100)
    }

    @Test
    fun `getLocallyWatchedOnlyTraktIds_缓存为null返回空集`() {
        setWatchlistWatchedIds(null)
        assertThat(repository.getLocallyWatchedOnlyTraktIds(MediaType.MOVIE)).isEmpty()
    }

    @Test
    fun `getLocallyWatchedOnlyTraktIds_SHOW类型`() {
        setWatchlistWatchedIds(WatchlistWatchedIds(
            showWatchedTraktIds = setOf(1, 2, 3),
            showWatchlistTraktIds = setOf(2)
        ))
        assertThat(repository.getLocallyWatchedOnlyTraktIds(MediaType.SHOW)).containsExactly(1, 3)
    }

    @Test
    fun `getLocallyWatchlistOnlyTraktIds_MOVIE_返回想看减已看的差集`() {
        setWatchlistWatchedIds(WatchlistWatchedIds(
            movieWatchlistTraktIds = setOf(100, 200, 300),
            movieWatchedTraktIds = setOf(200)
        ))
        val result = repository.getLocallyWatchlistOnlyTraktIds(MediaType.MOVIE)
        assertThat(result).containsExactly(100, 300)
    }

    @Test
    fun `getLocallyWatchlistOnlyTraktIds_缓存为null返回空集`() {
        setWatchlistWatchedIds(null)
        assertThat(repository.getLocallyWatchlistOnlyTraktIds(MediaType.MOVIE)).isEmpty()
    }

    // ==================== batchCheckStatus ====================

    @Test
    fun `batchCheckStatus_空列表短路返回空Map`() = runTest {
        setWatchlistWatchedIds(WatchlistWatchedIds())
        val result = repository.batchCheckStatus(emptyList(), MediaType.MOVIE)
        assertThat(result).isEmpty()
    }

    @Test
    fun `batchCheckStatus_缓存命中返回正确状态`() = runTest {
        setWatchlistWatchedIds(WatchlistWatchedIds(
            movieWatchlistTraktIds = setOf(100),
            movieWatchedTraktIds = setOf(200)
        ))
        val result = repository.batchCheckStatus(listOf(100, 200, 300), MediaType.MOVIE)
        assertThat(result[100]).isEqualTo(Pair(true, false))   // 在想看，不在已看
        assertThat(result[200]).isEqualTo(Pair(false, true))   // 不在想看，在已看
        assertThat(result[300]).isEqualTo(Pair(false, false))  // 都不在
    }
}
