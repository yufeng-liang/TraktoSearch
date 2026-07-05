package com.tracktosearch.di

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import com.tracktosearch.data.local.db.DoubanSyncFailureDao
import com.tracktosearch.data.remote.douban.DoubanDetailCacheEntry
import com.tracktosearch.data.remote.douban.DoubanRepository
import com.tracktosearch.data.repository.DoubanFailureExporter
import com.tracktosearch.data.repository.DoubanRetryManager
import com.tracktosearch.data.repository.DoubanSyncManager
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
import javax.inject.Singleton

/**
 * 豆瓣相关依赖注入模块。
 *
 * DoubanAuthStorage / DoubanSyncManager / DoubanRetryManager / DoubanFailureExporter
 * 通过 @Inject constructor 由 Hilt 自动注入,DoubanRepository 需注入持久化缓存,在此显式 provide。
 */
@Module
@InstallIn(SingletonComponent::class)
object DoubanModule {

    /** 豆瓣详情页解析结果持久化缓存（doubanId → imdbId/isTvShow，永久） */
    @Provides
    @Singleton
    fun provideDoubanDetailCache(
        @ApplicationContext context: Context,
        json: Json
    ): PersistentTtlCache<DoubanDetailCacheEntry> {
        val dataStore = context.doubanDetailCacheStore
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        return persistentTtlCache(
            ttlMillis = Long.MAX_VALUE, // 永久缓存（doubanId→imdbId 映射不会变）
            maxSize = 2000,
            dataStore = dataStore,
            json = json,
            keyPrefix = "douban_detail",
            scope = scope
        )
    }

    @Provides
    @Singleton
    fun provideDoubanRepository(
        detailCache: PersistentTtlCache<DoubanDetailCacheEntry>
    ): DoubanRepository = DoubanRepository(detailCache)

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
}

/** 豆瓣详情页缓存 DataStore */
private val Context.doubanDetailCacheStore: DataStore<Preferences> by androidx.datastore.preferences.preferencesDataStore(name = "douban_detail_cache")
