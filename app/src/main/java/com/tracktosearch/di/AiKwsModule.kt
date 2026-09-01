package com.tracktosearch.di

import com.tracktosearch.data.ai.AiKwsRecognizer
import com.tracktosearch.data.ai.AiVoiceCapture
import com.tracktosearch.data.ai.MicVoiceCapture
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

    @Binds
    @Singleton
    abstract fun bindAiVoiceCapture(impl: MicVoiceCapture): AiVoiceCapture
}
