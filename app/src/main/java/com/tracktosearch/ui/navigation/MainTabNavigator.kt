package com.tracktosearch.ui.navigation

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * 跳转到主界面指定页签的请求（0=搜索 1=发现 2=我的 3=设置）。
 * 使用 StateFlow 保留请求，避免 Activity 重建时丢失；
 * MainScreen 组合后消费。供独立路由（如搜索/统一搜索页）跳回主界面指定页签使用，
 * 与 [SearchNavigator] / [NotificationNavigator] 同一套机制。
 */
object MainTabNavigator {
    private val _pendingTab = MutableStateFlow<Int?>(null)
    val pendingTab: StateFlow<Int?> = _pendingTab.asStateFlow()

    fun requestTab(tab: Int) {
        _pendingTab.value = tab
    }

    fun consume() {
        _pendingTab.value = null
    }
}
