package com.tracktosearch.data.local.db

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import java.security.SecureRandom

/**
 * Room 数据库 SQLCipher 密钥管理器。
 *
 * 首次启动时生成 32 字节随机密钥，存入 EncryptedSharedPreferences（由 Android Keystore 主密钥加密）。
 * 后续启动从 EncryptedSharedPreferences 读取同一密钥，保证数据库跨重启可解密。
 *
 * 密钥永不写入 APK、永不离开设备 Keystore，反编译无法获取。
 */
object DatabaseKeyProvider {

    private const val PREFS_NAME = "db_cipher_key"
    private const val KEY_DB_PASSPHRASE = "db_passphrase_hex"

    @Volatile
    private var cachedKey: ByteArray? = null

    /** 获取 SQLCipher 密钥（首次调用时生成并持久化，后续直接返回缓存） */
    @Synchronized
    fun getPassphrase(context: Context): ByteArray {
        cachedKey?.let { return it }

        val prefs = createEncryptedPrefs(context)
        val existing = prefs.getString(KEY_DB_PASSPHRASE, null)
        val key = if (existing != null) {
            hexToBytes(existing)
        } else {
            // 生成 32 字节（256 bit）随机密钥
            val newKey = ByteArray(32).also { SecureRandom().nextBytes(it) }
            prefs.edit().putString(KEY_DB_PASSPHRASE, bytesToHex(newKey)).apply()
            newKey
        }
        cachedKey = key
        return key
    }

    /**
     * 检查旧明文数据库是否存在（用于从明文→加密迁移时删除旧库）。
     * Room 默认数据库文件名见 DatabaseModule: tracktosearch.db
     */
    fun legacyPlaintextDbExists(context: Context): Boolean {
        return context.getDatabasePath("tracktosearch.db").exists()
    }

    /**
     * 是否已存在 SQLCipher 密钥。
     * 用于区分"首次从明文迁移到加密"（需删除旧明文库）和"已使用加密库"（正常打开）。
     */
    fun hasCipherKey(context: Context): Boolean {
        return try {
            createEncryptedPrefs(context).contains(KEY_DB_PASSPHRASE)
        } catch (_: Exception) {
            false
        }
    }

    /**
     * 数据库文件是否为**明文** SQLite（文件头 16 字节 = "SQLite format 3\0"；
     * SQLCipher 加密后文件头是密文，不可能命中）。
     *
     * 删库迁移前必须做这个校验：Keystore 损坏（系统还原等）时 EncryptedSharedPreferences
     * 打开会抛异常，hasCipherKey 落到 catch 返回 false——若只凭 "文件存在 && 无密钥"
     * 判定，会把用户的加密库当旧明文库删掉（含豆瓣同步回滚快照、本地评分等唯一数据）。
     */
    fun isPlaintextSqliteDb(context: Context): Boolean {
        val dbFile = context.getDatabasePath("tracktosearch.db")
        if (!dbFile.exists()) return false
        return try {
            dbFile.inputStream().use { input ->
                val header = ByteArray(16)
                var read = 0
                while (read < header.size) {
                    val n = input.read(header, read, header.size - read)
                    if (n < 0) break
                    read += n
                }
                read == 16 && String(header, Charsets.US_ASCII) == "SQLite format 3\u0000"
            }
        } catch (_: Exception) {
            false
        }
    }

    /**
     * 删除旧明文数据库文件及其 WAL/SHM 辅助文件。
     * 仅在首次从明文迁移到 SQLCipher 时调用，调用前需确保尚未用密钥打开过数据库。
     */
    fun deleteLegacyPlaintextDb(context: Context) {
        val dbFile = context.getDatabasePath("tracktosearch.db")
        listOf(
            dbFile,
            context.getDatabasePath("tracktosearch.db-wal"),
            context.getDatabasePath("tracktosearch.db-shm"),
            context.getDatabasePath("tracktosearch.db-journal")
        ).forEach { file ->
            if (file.exists()) file.delete()
        }
    }

    private fun createEncryptedPrefs(context: Context): SharedPreferences {
        val masterKey = MasterKey.Builder(context)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build()
        return EncryptedSharedPreferences.create(
            context,
            PREFS_NAME,
            masterKey,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
        )
    }

    private fun bytesToHex(bytes: ByteArray): String =
        bytes.joinToString("") { "%02x".format(it) }

    private fun hexToBytes(hex: String): ByteArray {
        val len = hex.length
        val data = ByteArray(len / 2)
        for (i in 0 until len step 2) {
            data[i / 2] = ((Character.digit(hex[i], 16) shl 4) + Character.digit(hex[i + 1], 16)).toByte()
        }
        return data
    }
}
