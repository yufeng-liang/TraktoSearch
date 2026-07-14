package com.tracktosearch.data.remote.cloud

import com.google.common.truth.Truth.assertThat
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class AesCryptoTest {

    @Test
    fun encrypt_decrypt_roundTripPreservesPlaintext() {
        val original = "Hello, World!"
        val encrypted = AesCrypto.encrypt(original)
        val decrypted = AesCrypto.decrypt(encrypted)
        assertThat(decrypted).isEqualTo(original)
    }

    @Test
    fun encrypt_decrypt_emptyString_roundTripWorks() {
        val original = ""
        val encrypted = AesCrypto.encrypt(original)
        val decrypted = AesCrypto.decrypt(encrypted)
        assertThat(decrypted).isEqualTo(original)
    }

    @Test
    fun encrypt_decrypt_chineseString_roundTripWorks() {
        val original = "豆瓣同步测试"
        val encrypted = AesCrypto.encrypt(original)
        val decrypted = AesCrypto.decrypt(encrypted)
        assertThat(decrypted).isEqualTo(original)
    }

    @Test
    fun encrypt_decrypt_emojiString_roundTripWorks() {
        val original = "电影🎬评分⭐"
        val encrypted = AesCrypto.encrypt(original)
        val decrypted = AesCrypto.decrypt(encrypted)
        assertThat(decrypted).isEqualTo(original)
    }

    @Test
    fun decrypt_invalidCiphertext_returnsNull() {
        val result = AesCrypto.decrypt("这不是有效的密文!!!")
        assertThat(result).isNull()
    }

    @Test
    fun hashUserId_sameInput_returnsSameHash() {
        val h1 = AesCrypto.hashUserId("12345")
        val h2 = AesCrypto.hashUserId("12345")
        assertThat(h1).isEqualTo(h2)
    }

    @Test
    fun hashUserId_differentInput_returnsDifferentHash() {
        val h1 = AesCrypto.hashUserId("12345")
        val h2 = AesCrypto.hashUserId("67890")
        assertThat(h1).isNotEqualTo(h2)
    }
}
