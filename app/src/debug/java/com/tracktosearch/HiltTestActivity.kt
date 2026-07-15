package com.tracktosearch

import android.os.Bundle
import androidx.activity.ComponentActivity
import dagger.hilt.android.AndroidEntryPoint

/**
 * 专供 Compose Instrumented 测试使用的轻量 Activity。
 *
 * SettingsScreen 内部通过 `hiltViewModel<DoubanRetryViewModel>()` 获取 ViewModel,
 * `hiltViewModel()` 在构造 `HiltViewModelFactory` 时要求承载的 Activity 实现
 * `dagger.hilt.internal.GeneratedComponentManager`（即被 `@AndroidEntryPoint` 注解）。
 * `createComposeRule()` 使用的空壳 `ComponentActivity` 不满足此条件,故在 debug
 * source set 中提供此 `@AndroidEntryPoint` Activity,用 `createAndroidComposeRule`
 * 启动它作为 Compose 宿主。
 *
 * 仅存在于 debug build type,不会进入 release APK。
 */
@AndroidEntryPoint
class HiltTestActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Compose 内容由 composeRule.setContent 设置,此处不需要 setContent
    }
}
