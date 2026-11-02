package com.tracktosearch.di

import android.content.Context
import com.tracktosearch.data.ai.provider.AiProviderClientFactory
import com.tracktosearch.data.ai.provider.AiProviderKeyStore
import com.tracktosearch.data.ai.provider.AiProviderSettingsRepository
import com.tracktosearch.data.ai.provider.AiProviderSettingsStore
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

/** BYOK Provider 配置装配：设置存储、Key 加密存储、配置外观均进程级单例。 */
@Module
@InstallIn(SingletonComponent::class)
object AiProviderModule {

    @Provides
    @Singleton
    fun provideAiProviderKeyStore(
        @ApplicationContext context: Context
    ): AiProviderKeyStore = AiProviderKeyStore(context)

    @Provides
    @Singleton
    fun provideAiProviderSettingsStore(
        @ApplicationContext context: Context
    ): AiProviderSettingsStore = AiProviderSettingsStore(context)

    @Provides
    @Singleton
    fun provideAiProviderSettingsRepository(
        settingsStore: AiProviderSettingsStore,
        keyStore: AiProviderKeyStore
    ): AiProviderSettingsRepository = AiProviderSettingsRepository(settingsStore, keyStore)

    /** 协议适配器工厂无状态（内部自带缓存），直接单例共享。 */
    @Provides
    @Singleton
    fun provideAiProviderClientFactory(): AiProviderClientFactory = AiProviderClientFactory
}
