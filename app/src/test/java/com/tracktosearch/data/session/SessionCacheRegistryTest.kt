package com.tracktosearch.data.session

import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.test.runTest
import org.junit.Test

class SessionCacheRegistryTest {

    @Test
    fun invalidateAll_incrementsGenerationBeforeInvalidatingRegisteredCaches() = runTest {
        val registry = SessionCacheRegistry()
        val generations = mutableListOf<Long>()
        var invalidatedCount = 0
        registry.register {
            generations += registry.currentGeneration()
            invalidatedCount++
        }
        val generationBefore = registry.currentGeneration()

        registry.invalidateAll()

        assertThat(registry.currentGeneration()).isEqualTo(generationBefore + 1)
        assertThat(generations).containsExactly(generationBefore + 1)
        assertThat(invalidatedCount).isEqualTo(1)
    }

    @Test
    fun isCurrent_returnsFalseForGenerationCapturedBeforeInvalidation() = runTest {
        val registry = SessionCacheRegistry()
        val capturedGeneration = registry.currentGeneration()

        registry.invalidateAll()

        assertThat(registry.isCurrent(capturedGeneration)).isFalse()
        assertThat(registry.isCurrent(registry.currentGeneration())).isTrue()
    }
}
