package com.tracktosearch.di

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import com.tracktosearch.data.local.db.DoubanSyncFailureDao
import com.tracktosearch.data.remote.douban.DoubanDetailCacheEntry
import com.tracktosearch.data.remote.douban.DoubanRepository
import com.tracktosearch.data.remote.douban.DoubanRexxarApiService
import com.tracktosearch.data.remote.douban.DoubanRexxarDetail
import com.tracktosearch.data.remote.douban.DoubanRexxarPhotoCacheEntry
import com.tracktosearch.data.remote.douban.DoubanRexxarRepository
import com.tracktosearch.data.remote.douban.DoubanRexxarShortCommentPage
import com.tracktosearch.data.remote.douban.dto.DoubanRecommendItem
import com.tracktosearch.data.repository.CloudDetailsPoolManager
import com.tracktosearch.data.repository.DoubanFailureExporter
import com.tracktosearch.data.util.PersistentTtlCache
import com.tracktosearch.data.util.persistentTtlCache
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.serialization.json.Json
import javax.inject.Qualifier
import javax.inject.Singleton

/** 限定符:区分 traktId→doubanId 映射缓存与其他 PersistentTtlCache<String> */
@Qualifier
annotation class DoubanIdMapping

@Qualifier
annotation class DoubanRexxarDetailCache

@Qualifier
annotation class DoubanRexxarPhotosCache

@Qualifier
annotation class DoubanRexxarCommentsCache

/**
 * 豆瓣相关依赖注入模块。
 *
 * DoubanAuthStorage / DoubanSyncManager / DoubanRetryManager / DoubanFailureExporter
 * 通过 @Inject constructor 由 Hilt 自动注入,DoubanRepository 需注入持久化缓存,在此显式 provide。
 */
@Module
@InstallIn(SingletonComponent::class)
object DoubanModule {

    /**
     * 豆瓣详情页解析结果持久化缓存（doubanId → 详情字段，永久）。
     *
     * keyPrefix 使用 `douban_detail_v3` 让旧缓存（v1/v2 字段不全）自动失效，
     * 强制重新抓取补全扩展字段（编剧/主演/语言/首播/评分分布/演职员等）。
     */
    @Provides
    @Singleton
    fun provideDoubanDetailCache(
        @ApplicationContext context: Context,
        json: Json
    ): PersistentTtlCache<DoubanDetailCacheEntry> {
        val dataStore = context.doubanDetailCacheStore
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        return persistentTtlCache(
            ttlMillis = Long.MAX_VALUE, // 永久缓存（doubanId→详情字段不会变）
            maxSize = 2000,
            dataStore = dataStore,
            json = json,
            keyPrefix = "douban_detail_v3",
            scope = scope
        )
    }

    @Provides
    @Singleton
    fun provideDoubanRepository(
        detailCache: PersistentTtlCache<DoubanDetailCacheEntry>,
        cloudDetailsPoolManager: CloudDetailsPoolManager,
        @DoubanIdMapping idMappingCache: PersistentTtlCache<String>,
        json: Json
    ): DoubanRepository = DoubanRepository(detailCache, cloudDetailsPoolManager, json, idMappingCache)

    /** Rexxar 详情缓存，跨重启保留 7 天，并使用 PersistentTtlCache 保存原始过期时间。 */
    @Provides
    @Singleton
    @DoubanRexxarDetailCache
    fun provideDoubanRexxarDetailCache(
        @ApplicationContext context: Context,
        json: Json
    ): PersistentTtlCache<DoubanRexxarDetail> {
        return persistentTtlCache(
            ttlMillis = 7 * 24 * 60 * 60 * 1000L,
            maxSize = 500,
            dataStore = context.doubanRexxarCacheStore,
            json = json,
            // v2：详情模型补充演职员、简介、地区、语言、片长、别名和 IMDb，旧缓存字段不完整。
            keyPrefix = "douban_rexxar_detail_v2",
            scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        )
    }

    /** Rexxar 剧照 URL 集合永久缓存，每 24 小时通过条目时间戳刷新接口并合并新增分页。 */
    @Provides
    @Singleton
    @DoubanRexxarPhotosCache
    fun provideDoubanRexxarPhotosCache(
        @ApplicationContext context: Context,
        json: Json
    ): PersistentTtlCache<DoubanRexxarPhotoCacheEntry> {
        return persistentTtlCache(
            ttlMillis = Long.MAX_VALUE,
            maxSize = 500,
            dataStore = context.doubanRexxarCacheStore,
            json = json,
            keyPrefix = "douban_rexxar_photos_v2",
            scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        )
    }

    /** Rexxar 公开短评分页缓存，跨重启保留 6 小时。 */
    @Provides
    @Singleton
    @DoubanRexxarCommentsCache
    fun provideDoubanRexxarCommentsCache(
        @ApplicationContext context: Context,
        json: Json
    ): PersistentTtlCache<DoubanRexxarShortCommentPage> {
        return persistentTtlCache(
            ttlMillis = 6 * 60 * 60 * 1000L,
            maxSize = 500,
            dataStore = context.doubanRexxarCacheStore,
            json = json,
            keyPrefix = "douban_rexxar_comments_v1",
            scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        )
    }

    @Provides
    @Singleton
    fun provideDoubanRexxarRepository(
        service: DoubanRexxarApiService,
        @DoubanRexxarDetailCache detailCache: PersistentTtlCache<DoubanRexxarDetail>,
        @DoubanRexxarPhotosCache photosCache: PersistentTtlCache<DoubanRexxarPhotoCacheEntry>,
        @DoubanRexxarCommentsCache commentsCache: PersistentTtlCache<DoubanRexxarShortCommentPage>
    ): DoubanRexxarRepository = DoubanRexxarRepository(
        service = service,
        detailCache = detailCache,
        photosCache = photosCache,
        commentsCache = commentsCache
    )

    /**
     * DoubanFailureExporter 需要 DoubanSyncFailureDao + Json,显式 provide 以便注入 Json 实例。
     * DoubanSyncManager / DoubanRetryManager 通过 @Inject constructor 自动注入。
     */
    @Provides
    @Singleton
    fun provideDoubanFailureExporter(
        doubanSyncFailureDao: DoubanSyncFailureDao,
        json: Json
    ): DoubanFailureExporter = DoubanFailureExporter(doubanSyncFailureDao, json)

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
            keyPrefix = "douban_recommend_v1",
            scope = scope
        )
    }

    /**
     * traktId→doubanId 永久映射缓存。
     *
     * key 格式: {traktId}_{movie|show}
     * value: doubanId 字符串
     * 用于详情页预查 doubanId，避免每次都搜索豆瓣。
     * 永不过期（映射关系静态不变）。
     */
    @Provides
    @Singleton
    @DoubanIdMapping
    fun provideDoubanIdMappingCache(
        @ApplicationContext context: Context,
        json: Json
    ): PersistentTtlCache<String> {
        val dataStore = context.doubanIdMappingCacheStore
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        return persistentTtlCache(
            ttlMillis = Long.MAX_VALUE,
            maxSize = 5000,
            dataStore = dataStore,
            json = json,
            keyPrefix = "douban_id_mapping_v1",
            scope = scope
        )
    }
}

/** 豆瓣详情页缓存 DataStore */
private val Context.doubanDetailCacheStore: DataStore<Preferences> by androidx.datastore.preferences.preferencesDataStore(name = "douban_detail_cache")

/** 豆瓣推荐缓存 DataStore */
private val Context.doubanRecommendCacheStore: DataStore<Preferences> by androidx.datastore.preferences.preferencesDataStore(name = "douban_recommend_cache")

/** traktId→doubanId 映射缓存 DataStore */
private val Context.doubanIdMappingCacheStore: DataStore<Preferences> by androidx.datastore.preferences.preferencesDataStore(name = "douban_id_mapping_cache")

/** Rexxar 详情、剧照 URL、短评分页缓存共用的 DataStore */
private val Context.doubanRexxarCacheStore: DataStore<Preferences> by androidx.datastore.preferences.preferencesDataStore(name = "douban_rexxar_cache")
