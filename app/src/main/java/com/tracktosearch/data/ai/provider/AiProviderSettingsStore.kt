package com.tracktosearch.data.ai.provider

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.serialization.json.Json

/** 配置主体的 DataStore 实例（文件名 ai_provider_settings，与项目其他 Storage 同惯例）。 */
private val Context.aiProviderSettingsDataStore: DataStore<Preferences> by
    preferencesDataStore(name = "ai_provider_settings")

/**
 * 配置主体存储：AiProviderSettings（不含 Key 明文）JSON 序列化进 DataStore Preferences。
 *
 * Key 明文单独走 [AiProviderKeyStore]（EncryptedSharedPreferences），两者仅在
 * Repository 组装内存配置时汇合，落盘数据永不包含明文。
 *
 * 不加 Hilt 注解：单例装配由后续集成的 Hilt 模块负责。
 */
class AiProviderSettingsStore(
    private val context: Context
) {
    private val json = Json { ignoreUnknownKeys = true }

    /** 当前完整设置流；坏数据按默认值回退，不让解析异常卡死下游。 */
    val settings: Flow<AiProviderSettings> =
        context.aiProviderSettingsDataStore.data.map { prefs ->
            prefs[KEY_SETTINGS]?.let(::decodeSettings) ?: AiProviderSettings()
        }

    /** 读-改-写当前设置：transform 收到旧值、返回新值，整体在 DataStore 事务内完成。 */
    suspend fun update(transform: (AiProviderSettings) -> AiProviderSettings) {
        context.aiProviderSettingsDataStore.edit { prefs ->
            val current = prefs[KEY_SETTINGS]?.let(::decodeSettings) ?: AiProviderSettings()
            prefs[KEY_SETTINGS] = json.encodeToString(AiProviderSettings.serializer(), transform(current))
        }
    }

    /** 解析落盘 JSON；结构变化或损坏时返回 null，由调用方回退默认值。 */
    private fun decodeSettings(raw: String): AiProviderSettings? = try {
        json.decodeFromString(AiProviderSettings.serializer(), raw)
    } catch (_: Exception) {
        null
    }

    private companion object {
        val KEY_SETTINGS = stringPreferencesKey("settings_json")
    }
}
