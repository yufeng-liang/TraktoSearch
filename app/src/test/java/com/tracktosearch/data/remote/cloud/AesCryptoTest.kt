package com.tracktosearch.data.remote.cloud

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class AesCryptoTest {

    @Test
    fun hashUserId_sameInput_returnsSameHash() {
        val first = AesCrypto.hashUserId("12345")
        val second = AesCrypto.hashUserId("12345")

        assertThat(first).isEqualTo(second)
    }

    @Test
    fun hashUserId_differentInput_returnsDifferentHash() {
        val first = AesCrypto.hashUserId("12345")
        val second = AesCrypto.hashUserId("67890")

        assertThat(first).isNotEqualTo(second)
    }

    @Test
    fun hashUserId_returns32HexCharacters() {
        val hash = AesCrypto.hashUserId("douban-user")

        assertThat(hash).hasLength(32)
        assertThat(hash).matches("[0-9a-f]{32}")
    }
}
