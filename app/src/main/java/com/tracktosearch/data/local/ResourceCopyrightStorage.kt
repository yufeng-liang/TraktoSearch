package com.tracktosearch.data.local

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.preferencesDataStore
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

private val Context.resourceCopyrightDataStore: DataStore<Preferences> by
    preferencesDataStore(name = "resource_copyright")

/**
 * 资源版权提示的本机持久化状态。
 *
 * 仅记录用户是否主动勾选过「不再显示此提示」。提示本身不属于账号数据，
 * 不参与云同步；清除应用数据会一并重置。
 */
@Singleton
class ResourceCopyrightStorage @Inject constructor(
    @ApplicationContext private val context: Context
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val _dismissed = MutableStateFlow(false)
    private val initializationComplete = CompletableDeferred<Unit>()

    /** 用户是否已勾选不再显示。UI 可先用内存快照，真正的动作守卫须走 [awaitDismissed]。 */
    val dismissed: StateFlow<Boolean> = _dismissed.asStateFlow()

    companion object {
        private val KEY_DISMISSED = booleanPreferencesKey("resource_copyright_dismissed")
    }

    init {
        scope.launch {
            try {
                val prefs = context.resourceCopyrightDataStore.data.first()
                _dismissed.value = prefs[KEY_DISMISSED] ?: false
            } finally {
                // 读盘失败保持默认 false：宁可多提示一次，也不能静默跳过。
                initializationComplete.complete(Unit)
            }
        }
    }

    /** 等待 DataStore 首值，避免冷启动把默认值误判成用户已勾选。 */
    suspend fun awaitDismissed(): Boolean {
        initializationComplete.await()
        return _dismissed.value
    }

    suspend fun setDismissed(dismissed: Boolean) {
        initializationComplete.await()
        _dismissed.value = dismissed
        withContext(Dispatchers.IO) {
            context.resourceCopyrightDataStore.edit { prefs ->
                prefs[KEY_DISMISSED] = dismissed
            }
        }
    }
}
