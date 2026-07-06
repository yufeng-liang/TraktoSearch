package com.tracktosearch.data.remote.cloud

import android.util.Base64
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec
import java.security.MessageDigest
import java.security.SecureRandom

/**
 * AES-256-CBC 加解密工具。
 *
 * 用于豆瓣失败项云端同步时对 JSON content 加密,防止 Gitee token 泄露时数据明文。
 *
 * key 打包进 App,反编译能看到,但数据非高敏感(豆瓣标记的失败项,不含密码/cookie),
 * 可接受此权衡。
 */
object AesCrypto {

    private const val ALGORITHM = "AES"
    private const val TRANSFORMATION = "AES/CBC/PKCS5Padding"
    private const val KEY = "TraktToSearch_DoubanFailures_CloudSync_2026"

    private val secretKey: SecretKeySpec by lazy {
        // SHA-256 摘要取前 32 字节作为 AES-256 key
        val digest = MessageDigest.getInstance("SHA-256").digest(KEY.toByteArray(Charsets.UTF_8))
        SecretKeySpec(digest, ALGORITHM)
    }

    /**
     * 加密并 base64 编码。
     * @return base64(IV + 密文),IV 占前 16 字节
     */
    fun encrypt(plaintext: String): String {
        val iv = ByteArray(16).also { SecureRandom().nextBytes(it) }
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, secretKey, IvParameterSpec(iv))
        val ciphertext = cipher.doFinal(plaintext.toByteArray(Charsets.UTF_8))
        // 拼接 IV + 密文
        val combined = iv + ciphertext
        return Base64.encodeToString(combined, Base64.NO_WRAP)
    }

    /**
     * 解密 base64 编码的密文。
     * @param encrypted base64(IV + 密文)
     * @return 解密后的明文,失败返回 null
     */
    fun decrypt(encrypted: String): String? {
        return try {
            val combined = Base64.decode(encrypted, Base64.NO_WRAP)
            if (combined.size <= 16) return null
            val iv = combined.copyOfRange(0, 16)
            val ciphertext = combined.copyOfRange(16, combined.size)
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(Cipher.DECRYPT_MODE, secretKey, IvParameterSpec(iv))
            val plaintext = cipher.doFinal(ciphertext)
            String(plaintext, Charsets.UTF_8)
        } catch (e: Exception) {
            null
        }
    }

    /**
     * 对 doubanUserId 做 SHA-256 哈希,取前 16 字节作为文件路径标识。
     * 用于在云端隔离不同用户的失败数据文件,避免直接暴露豆瓣 ID。
     */
    fun hashUserId(doubanUserId: String): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(doubanUserId.toByteArray(Charsets.UTF_8))
        return digest.copyOfRange(0, 16).joinToString("") { "%02x".format(it) }
    }
}
