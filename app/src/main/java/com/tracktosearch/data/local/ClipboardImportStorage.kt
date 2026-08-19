package com.tracktosearch.data.local

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringSetPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

private val Context.clipboardImportDataStore: DataStore<Preferences> by preferencesDataStore(name = "clipboard_import")

/**
 * 剪贴板自动导入的忽略记录：用户已取消/已导入的分享文本指纹持久化，
 * 跨冷启动不再重复弹导入提示（按指纹去重）。
 */
@Singleton
class ClipboardImportStorage @Inject constructor(
    @ApplicationContext private val context: Context
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val loaded = CompletableDeferred<Unit>()

    private val _ignored = MutableStateFlow<Set<String>>(emptySet())
    val ignored: StateFlow<Set<String>> = _ignored.asStateFlow()

    init {
        scope.launch {
            val prefs = context.clipboardImportDataStore.data.first()
            _ignored.value = prefs[KEY_IGNORED] ?: emptySet()
            loaded.complete(Unit)
        }
    }

    /** 等待磁盘首读完成（冷启动时检测前调用，避免忽略记录未加载导致重复弹窗） */
    suspend fun awaitLoaded() = loaded.await()

    fun isIgnored(fingerprint: String): Boolean = _ignored.value.contains(fingerprint)

    suspend fun markIgnored(fingerprint: String) {
        context.clipboardImportDataStore.edit { prefs ->
            val current = prefs[KEY_IGNORED] ?: emptySet()
            // 限制条数防膨胀，仅保留最近添加的条目
            val combined = (current + fingerprint).toList()
            val updated = if (combined.size > MAX_IGNORED) combined.takeLast(MAX_IGNORED).toSet() else combined.toSet()
            prefs[KEY_IGNORED] = updated
        }
        _ignored.value = _ignored.value + fingerprint
    }

    companion object {
        private const val MAX_IGNORED = 100
        private val KEY_IGNORED = stringSetPreferencesKey("ignored_fingerprints")
    }
}
