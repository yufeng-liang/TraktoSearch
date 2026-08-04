package com.tracktosearch.di

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import com.tracktosearch.data.remote.feedback.FeedbackApiService
import com.tracktosearch.data.remote.feedback.FeedbackDetailResponse
import com.tracktosearch.data.remote.feedback.MineResponse
import com.tracktosearch.data.remote.feedback.MessagesResponse
import com.tracktosearch.data.repository.FeedbackCacheStore
import com.tracktosearch.data.repository.FeedbackRepository
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

@Module
@InstallIn(SingletonComponent::class)
object FeedbackModule {
    @Provides @Singleton @FeedbackListCache
    fun provideFeedbackListCache(@ApplicationContext context: Context, json: Json): PersistentTtlCache<MineResponse> {
        val dataStore = context.feedbackListCacheStore
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        return persistentTtlCache(ttlMillis = Long.MAX_VALUE, maxSize = 1, dataStore = dataStore, json = json, keyPrefix = "feedback_list_v1", scope = scope)
    }

    @Provides @Singleton @FeedbackDetailCache
    fun provideFeedbackDetailCache(@ApplicationContext context: Context, json: Json): PersistentTtlCache<FeedbackDetailResponse> {
        val dataStore = context.feedbackDetailCacheStore
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        return persistentTtlCache(ttlMillis = Long.MAX_VALUE, maxSize = 100, dataStore = dataStore, json = json, keyPrefix = "feedback_detail_v1", scope = scope)
    }

    @Provides @Singleton @FeedbackMessagesCache
    fun provideFeedbackMessagesCache(@ApplicationContext context: Context, json: Json): PersistentTtlCache<MessagesResponse> {
        val dataStore = context.feedbackMessagesCacheStore
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        return persistentTtlCache(ttlMillis = Long.MAX_VALUE, maxSize = 1, dataStore = dataStore, json = json, keyPrefix = "feedback_messages_v1", scope = scope)
    }

    @Provides @Singleton
    fun provideFeedbackCacheStore(
        @FeedbackListCache listCache: PersistentTtlCache<MineResponse>,
        @FeedbackDetailCache detailCache: PersistentTtlCache<FeedbackDetailResponse>,
        @FeedbackMessagesCache messagesCache: PersistentTtlCache<MessagesResponse>
    ): FeedbackCacheStore = FeedbackCacheStore(listCache, detailCache, messagesCache)

    @Provides @Singleton
    fun provideFeedbackRepository(api: FeedbackApiService, cacheStore: FeedbackCacheStore): FeedbackRepository = FeedbackRepository(api, cacheStore)
}

@Qualifier annotation class FeedbackListCache
@Qualifier annotation class FeedbackDetailCache
@Qualifier annotation class FeedbackMessagesCache

private val Context.feedbackListCacheStore: DataStore<Preferences> by androidx.datastore.preferences.preferencesDataStore(name = "feedback_list_cache")
private val Context.feedbackDetailCacheStore: DataStore<Preferences> by androidx.datastore.preferences.preferencesDataStore(name = "feedback_detail_cache")
private val Context.feedbackMessagesCacheStore: DataStore<Preferences> by androidx.datastore.preferences.preferencesDataStore(name = "feedback_messages_cache")
