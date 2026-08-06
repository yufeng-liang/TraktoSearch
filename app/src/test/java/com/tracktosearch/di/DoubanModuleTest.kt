package com.tracktosearch.di

import com.google.common.truth.Truth.assertThat
import kotlinx.serialization.json.Json
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class DoubanModuleTest {

    @Test
    fun rexxarDetailCache_usesPermanentTtlAndVersionedKey() {
        val cache = DoubanModule.provideDoubanRexxarDetailCache(
            context = RuntimeEnvironment.getApplication(),
            json = Json { ignoreUnknownKeys = true }
        )

        assertThat(readField(cache, "ttlMillis")).isEqualTo(Long.MAX_VALUE)
        assertThat(readField(cache, "keyPrefix")).isEqualTo("douban_rexxar_detail_v3")
    }

    private fun readField(instance: Any, fieldName: String): Any? {
        var type: Class<*>? = instance.javaClass
        while (type != null) {
            try {
                return type.getDeclaredField(fieldName).apply {
                    isAccessible = true
                }.get(instance)
            } catch (_: NoSuchFieldException) {
                type = type.superclass
            }
        }
        error("Field not found: $fieldName")
    }
}
