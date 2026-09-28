package com.tracktosearch.data.local

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey

/**
 * 全项目创建 EncryptedSharedPreferences 的唯一入口。
 *
 * security-crypto 1.1.0 起 `MasterKey` / `EncryptedSharedPreferences` 被官方整库标记弃用，
 * 官方未给出替代实现；改成自建 Keystore AES-GCM 需要同时处理已落盘数据（访问令牌、豆瓣凭据、
 * 搜索历史、浏览历史、数据库密钥、AI 缓存）的兼容，不属于清理编译警告的范围。
 *
 * 因此把弃用 API 收敛到这一处并显式抑制：调用方（TokenStorage / DoubanAuthStorage /
 * SearchHistoryStorage / ViewedItemStorage / DatabaseKeyProvider / AiStorage）不再各自
 * 触发 DEPRECATION 诊断，将来真做迁移也只需要改这一个函数。
 */
@Suppress("DEPRECATION")
internal fun createEncryptedPreferences(context: Context, fileName: String): SharedPreferences {
    val masterKey = MasterKey.Builder(context)
        .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
        .build()
    return EncryptedSharedPreferences.create(
        context,
        fileName,
        masterKey,
        EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
        EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
    )
}
