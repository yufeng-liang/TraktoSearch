package com.tracktosearch.ui.navigation

import android.content.Intent
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** 搜索页打开请求状态。使用 StateFlow 保留请求，避免 Activity 重建时丢失。 */
object SearchNavigator {
    const val EXTRA_OPEN_SEARCH = "com.tracktosearch.extra.OPEN_SEARCH"

    private val _pending = MutableStateFlow(false)
    val pending: StateFlow<Boolean> = _pending.asStateFlow()

    fun request() {
        _pending.value = true
    }

    fun consume() {
        _pending.value = false
    }

    fun isOpenSearchIntent(intent: Intent?): Boolean {
        return intent?.getBooleanExtra(EXTRA_OPEN_SEARCH, false) == true
    }
}
