package com.tracktosearch.data.remote.cloud

import java.security.MessageDigest

/**
 * 云端同步数据哈希工具。
 *
 * 原 AES-256-CBC 加解密已迁移到 auth-worker 服务端（CLOUD_SYNC_AES_KEY Secret），
 * 客户端不再需要加密/解密能力，密钥不再编译进 APK。
 *
 * 保留 [hashUserId] 用于计算云端文件路径标识（SHA-256 前 16 字节），
 * 这是路径计算逻辑，不涉及加密密钥。
 */
object AesCrypto {

    /**
     * 对 doubanUserId 做 SHA-256 哈希,取前 16 字节作为文件路径标识。
     * 用于在云端隔离不同用户的失败数据文件,避免直接暴露豆瓣 ID。
     */
    fun hashUserId(doubanUserId: String): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(doubanUserId.toByteArray(Charsets.UTF_8))
        return digest.copyOfRange(0, 16).joinToString("") { "%02x".format(it) }
    }
}
