package com.tracktosearch.ui.screen.ai

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class AudioControllerTest {

    @Test
    fun bundledAssetPath_extractsPackagedAuditionPath() {
        assertThat(
            bundledAssetPath("file:///android_asset/ai_auditions/usagi.mp3")
        ).isEqualTo("ai_auditions/usagi.mp3")
    }

    @Test
    fun bundledAssetPath_rejectsNetworkAndUnsafePaths() {
        assertThat(bundledAssetPath("https://example.com/audio.mp3")).isNull()
        assertThat(bundledAssetPath("file:///android_asset/../secret.mp3")).isNull()
        assertThat(bundledAssetPath("file:///android_asset/")).isNull()
    }

    @Test
    fun bundledAuditionAssets_areFileDescriptorAccessibleAndNonEmpty() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val ids = listOf(
            "chiikawa",
            "hachiware",
            "usagi",
            "flying-squirrel",
            "shisa",
            "kurimanju",
            "rakko"
        )

        ids.forEach { id ->
            context.assets.openFd("ai_auditions/$id.mp3").use { descriptor ->
                assertThat(descriptor.length).isGreaterThan(0L)
            }
        }
    }
}
