package com.tracktosearch.data.ai

import android.content.Context
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.test.runTest
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.io.File
import java.util.UUID

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class AiStorageTest {

    @Test
    fun sensitiveAiCache_isNotWrittenAsPlaintextFile() = runTest {
        val context = RuntimeEnvironment.getApplication()
        val friendId = "storage-test-${UUID.randomUUID()}"
        val preferences = context.getSharedPreferences("ai-test-${UUID.randomUUID()}", Context.MODE_PRIVATE)
        val storage = AiStorage(context, preferences)
        val secret = "audio-secret-${UUID.randomUUID()}"
        val payload = "{\"audioDataUrl\":\"${secret}${"x".repeat(70_000)}\"}"

        storage.write(friendId, AiCacheFeature.TTS, payload)

        val plainFiles = File(context.cacheDir, "ai_cache").walkTopDown()
            .filter { it.isFile }
            .toList()
        assertThat(plainFiles.any { file ->
            runCatching { file.readText().contains(secret) }.getOrDefault(false)
        }).isFalse()
        assertThat(storage.read(friendId, AiCacheFeature.TTS)).isEqualTo(payload)
        storage.clearFriend(friendId)
    }
}
