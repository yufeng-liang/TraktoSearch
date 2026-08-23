package com.tracktosearch.di

import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import javax.inject.Named
import javax.inject.Singleton

/**
 * 协程调度器注入。
 *
 * ViewModel 里的纯计算（列表合并、排序、索引构建）默认跑在调用方上下文，
 * 也就是 viewModelScope 的 Dispatchers.Main.immediate；数据量大时会直接卡帧。
 * 通过注入把计算调度器显式化：生产环境用 Dispatchers.Default，
 * 单元测试注入 TestDispatcher 以保持 runTest 的确定性推进。
 */
@Module
@InstallIn(SingletonComponent::class)
object DispatcherModule {

    const val COMPUTE_DISPATCHER = "computeDispatcher"

    /** CPU 密集计算调度器（列表合并/排序/搜索索引构建）。 */
    @Provides
    @Singleton
    @Named(COMPUTE_DISPATCHER)
    fun provideComputeDispatcher(): CoroutineDispatcher = Dispatchers.Default
}
