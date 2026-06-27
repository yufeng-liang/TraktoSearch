package com.tracktosearch.ui.util

import androidx.compose.runtime.compositionLocalOf

/**
 * 全局 scrollToTop 回调提供者。
 * 各页面在进入时注册自己的 scrollToTop 实现，
 * 点击状态栏时调用当前注册的回调。
 */
val LocalScrollToTopProvider = compositionLocalOf { ScrollToTopProvider() }

class ScrollToTopProvider {
    private var currentAction: (() -> Unit)? = null

    fun register(action: () -> Unit) {
        currentAction = action
    }

    fun unregister() {
        currentAction = null
    }

    fun scrollToTop() {
        currentAction?.invoke()
    }
}
