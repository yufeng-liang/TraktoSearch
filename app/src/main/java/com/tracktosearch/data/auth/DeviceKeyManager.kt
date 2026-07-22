package com.tracktosearch.data.auth

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import dagger.hilt.android.qualifiers.ApplicationContext
import android.content.Context
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.PublicKey
import java.security.Signature
import android.util.Base64
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Android Keystore 设备密钥管理
 * - 生成不可导出的 P-256 密钥对
 * - 私钥留在 Keystore，永不导出
 * - 公钥（DER/Base64）发送给服务端绑定设备
 */
@Singleton
class DeviceKeyManager @Inject constructor(
    @ApplicationContext private val context: Context
) {
    companion object {
        private const val KEYSTORE_PROVIDER = "AndroidKeyStore"
        private const val KEY_ALIAS = "tts_device_key"
        private const val SIGNATURE_ALGORITHM = "SHA256withECDSA"
    }

    private val keyStore: KeyStore by lazy {
        KeyStore.getInstance(KEYSTORE_PROVIDER).apply { load(null) }
    }

    // 获取或生成密钥对
    private fun getOrCreateKeyPair(): java.security.KeyPair {
        if (keyStore.containsAlias(KEY_ALIAS)) {
            val entry = keyStore.getCertificate(KEY_ALIAS)
            val publicKey = entry.publicKey
            val privateKey = keyStore.getKey(KEY_ALIAS, null) as java.security.PrivateKey
            return java.security.KeyPair(publicKey, privateKey)
        }

        // 生成 P-256 密钥对，不可导出
        val generator = KeyPairGenerator.getInstance(
            KeyProperties.KEY_ALGORITHM_EC,
            KEYSTORE_PROVIDER
        )
        val spec = KeyGenParameterSpec.Builder(
            KEY_ALIAS,
            KeyProperties.PURPOSE_SIGN or KeyProperties.PURPOSE_VERIFY
        )
            .setAlgorithmParameterSpec.security.spec.ECGenParameterSpec("secp256r1")) // P-256
            .setDigests(KeyProperties.DIGEST_SHA256, KeyProperties.DIGEST_SHA56)
            .setUserAuthenticationRequired(false) // 不需要生物识别
            .build()
        generator.initialize(spec)
        return generator.generateKeyPair()
    }

    // 获取公钥（DER 格式 Base64 编码）
    fun getPublicKeyBase64(): String {
        val keyPair = getOrCreateKeyPair()
        val publicKey = keyPair.public as PublicKey
        return Base64.encodeToString(publicKey.encoded, Base64.NO_WRAP)
    }

    // 用私钥签名挑战码
    fun sign(challenge: ByteArray): ByteArray {
        val keyPair = getOrCreateKeyPair()
        val signature = Signature.getInstance(SIGNATURE_ALGORITHM)
        signature.initSign(keyPair.private)
        signature.update(challenge)
        return signature.sign()
    }

    // 获取公钥指纹（用于刷新时验证）
    fun getPublicKeyFingerprint(): String {
        val keyPair = getOrCreateKeyPair()
        val publicKey = keyPair.public as PublicKey
        val bytes = publicKey.encoded
        // 取前 16 字节作为指纹
        return bytes.take(16).joinToString("") { "%02x".format(it) }
    }
}
