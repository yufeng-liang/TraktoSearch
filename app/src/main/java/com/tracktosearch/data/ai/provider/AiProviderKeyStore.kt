package com.tracktosearch.data.ai.provider

import android.content.Context
import android.content.SharedPreferences
import com.tracktosearch.data.local.createEncryptedPreferences

/**
 * API Key 加密存储：global 与 media 两条，各存一个 apiKey。
 *
 * Key 不能随 [AiProviderSettings] 落盘明文，所以单独放 EncryptedSharedPreferences
 *（经全项目唯一入口 [createEncryptedPreferences]，与访问令牌等同等加密强度），
 * 仅在内存中组装完整配置时由 [AiProviderSettingsRepository] 取出拼装。
 */
class AiProviderKeyStore(
    context: Context
) {
    private val prefs: SharedPreferences = createEncryptedPreferences(context, "ai_provider_keys")

    /** 保存指定 Provider 的 API Key（明文参数，落盘即密文）。 */
    fun setKey(id: String, key: String) {
        prefs.edit().putString(id, key).apply()
    }

    /** 读取指定 Provider 的 API Key，未配置返回空串。 */
    fun getKey(id: String): String = prefs.getString(id, null).orEmpty()

    /** 清空两条 Key（恢复出厂/退出登录场景）。 */
    fun clearKeys() {
        prefs.edit().clear().apply()
    }
}
