package com.tracktosearch.ui.navigation

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** 通知点击后的页面动作。使用 StateFlow 保留请求，避免 Activity 重建时丢失点击。 */
enum class NotificationTarget {
    DOUBAN_SYNC,
    CONSISTENCY_CHECK
}

object NotificationNavigator {
    private val _pendingTarget = MutableStateFlow<NotificationTarget?>(null)
    val pendingTarget: StateFlow<NotificationTarget?> = _pendingTarget.asStateFlow()

    fun request(target: NotificationTarget) {
        _pendingTarget.value = target
    }

    fun consume(target: NotificationTarget) {
        if (_pendingTarget.value == target) {
            _pendingTarget.value = null
        }
    }
}
