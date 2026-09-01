package com.tracktosearch.di

import com.tracktosearch.data.ai.AiKwsRecognizer
import com.tracktosearch.data.ai.SherpaOnnxKwsRecognizer
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
abstract class AiKwsModule {
    @Binds
    @Singleton
    abstract fun bindAiKwsRecognizer(impl: SherpaOnnxKwsRecognizer): AiKwsRecognizer
}
