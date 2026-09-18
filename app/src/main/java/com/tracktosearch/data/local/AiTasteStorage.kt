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

private val Context.aiTasteDataStore: DataStore<Preferences> by preferencesDataStore(name = "ai_taste")

/**
 * 「AI 锐评看单」隐私偏好存储。
 *
 * - [tasteUploadEnabled]：功能数据上传开关（默认开启），设置页「AI 与隐私」分组控制
 * - [tasteConsentDecided]：用户是否对首次使用说明弹窗做出过决定（默认 false = 从未见过弹窗）
 *
 * 守卫链路见 AiSpriteViewModel：未决定 → 弹说明弹窗（同意才上传）；
 * 已决定但开关关闭 → 弹「去设置」提示；两者都通过才真正发起请求。
 */
@Singleton
class AiTasteStorage @Inject constructor(
    @ApplicationContext private val context: Context
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val _tasteUploadEnabled = MutableStateFlow(true)
    private val _tasteConsentDecided = MutableStateFlow(false)
    private val initializationComplete = CompletableDeferred<Unit>()

    companion object {
        private val KEY_TASTE_UPLOAD_ENABLED = booleanPreferencesKey("ai_taste_upload_enabled")
        private val KEY_TASTE_CONSENT_DECIDED = booleanPreferencesKey("ai_taste_consent_decided")
    }

    /** 锐评功能是否允许上传已看数据（默认 true） */
    val tasteUploadEnabled: StateFlow<Boolean> = _tasteUploadEnabled.asStateFlow()

    /** 用户是否已对首次说明弹窗做出决定（默认 false：从未见过弹窗） */
    val tasteConsentDecided: StateFlow<Boolean> = _tasteConsentDecided.asStateFlow()

    /**
     * 等待 DataStore 首值后再读取上传开关。
     *
     * UI 可以先用 [tasteUploadEnabled] 的内存快照避免转圈；隐私守卫必须走这里，
     * 否则冷启动早期会把默认值误判成用户已经保存的选择。
     */
    suspend fun awaitTasteUploadEnabled(): Boolean {
        initializationComplete.await()
        return _tasteUploadEnabled.value
    }

    /** 等待 DataStore 首值后再读取首次说明是否已决定。 */
    suspend fun awaitTasteConsentDecided(): Boolean {
        initializationComplete.await()
        return _tasteConsentDecided.value
    }

    init {
        scope.launch {
            try {
                val prefs = context.aiTasteDataStore.data.first()
                _tasteUploadEnabled.value = prefs[KEY_TASTE_UPLOAD_ENABLED] ?: true
                _tasteConsentDecided.value = prefs[KEY_TASTE_CONSENT_DECIDED] ?: false
            } finally {
                initializationComplete.complete(Unit)
            }
        }
    }

    suspend fun setTasteUploadEnabled(enabled: Boolean) {
        initializationComplete.await()
        _tasteUploadEnabled.value = enabled
        withContext(Dispatchers.IO) {
            context.aiTasteDataStore.edit { prefs ->
                prefs[KEY_TASTE_UPLOAD_ENABLED] = enabled
            }
        }
    }

    suspend fun setConsentDecided(decided: Boolean) {
        initializationComplete.await()
        _tasteConsentDecided.value = decided
        withContext(Dispatchers.IO) {
            context.aiTasteDataStore.edit { prefs ->
                prefs[KEY_TASTE_CONSENT_DECIDED] = decided
            }
        }
    }
}
