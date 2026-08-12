package com.tracktosearch.data.local

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.preferencesDataStore
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Singleton

private val Context.crashLogDataStore: DataStore<Preferences> by preferencesDataStore(name = "crash_log_settings")

/**
 * 崩溃日志上报用户授权存储。
 *
 * 隐私规范：
 * - 默认拒绝上报（crashLogEnabled = false）
 * - 首次崩溃后弹窗询问用户授权（prompted = false 时弹窗）
 * - 用户可在设置页随时开启/关闭
 * - CrashHandler 仍会写入本地日志文件，仅在上传阶段根据开关决定是否上报
 */
@Singleton
class CrashLogStorage @Inject constructor(
    @ApplicationContext private val context: Context
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /** 用户是否授权上报崩溃日志到云端（默认 false） */
    private val _enabled = MutableStateFlow(false)
    val enabled: StateFlow<Boolean> = _enabled.asStateFlow()

    /** 是否已弹过首次授权弹窗（默认 false，弹过一次后置 true） */
    private val _prompted = MutableStateFlow(false)
    val prompted: StateFlow<Boolean> = _prompted.asStateFlow()

    init {
        scope.launch {
            val prefs = context.crashLogDataStore.data.first()
            _enabled.value = prefs[KEY_ENABLED] ?: false
            _prompted.value = prefs[KEY_PROMPTED] ?: false
        }
    }

    suspend fun setEnabled(enabled: Boolean) {
        context.crashLogDataStore.edit { prefs ->
            prefs[KEY_ENABLED] = enabled
            // 开启即视为已授权引导，避免下次崩溃时再次弹首次授权弹窗（设置页开关=自动上报语义）
            if (enabled) prefs[KEY_PROMPTED] = true
        }
        _enabled.value = enabled
        if (enabled) _prompted.value = true
    }

    suspend fun setPrompted(prompted: Boolean) {
        context.crashLogDataStore.edit { prefs ->
            prefs[KEY_PROMPTED] = prompted
        }
        _prompted.value = prompted
    }

    /** 同步读取授权状态（仅在启动时使用，避免协程） */
    fun isEnabledSync(): Boolean = _enabled.value

    /** 同步读取是否已弹过授权弹窗 */
    fun isPromptedSync(): Boolean = _prompted.value

    companion object {
        private val KEY_ENABLED = booleanPreferencesKey("crash_log_enabled")
        private val KEY_PROMPTED = booleanPreferencesKey("crash_log_prompted")
    }
}
