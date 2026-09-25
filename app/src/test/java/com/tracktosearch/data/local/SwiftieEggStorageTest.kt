package com.tracktosearch.data.local

import androidx.datastore.preferences.core.edit
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], application = android.app.Application::class)
class SwiftieEggStorageTest {

    private val context = RuntimeEnvironment.getApplication()

    private suspend fun clearPrefs() {
        context.swiftieDataStore.edit { it.clear() }
    }

    @Test
    fun defaults_areAllLockedAndZero() = runTest {
        clearPrefs()
        val storage = SwiftieEggStorage(context)
        storage.awaitReady()

        assertThat(storage.unlocked.value).isFalse()
        assertThat(storage.quizSolved.value).isFalse()
        assertThat(storage.cloudClickCount.value).isEqualTo(0)
    }

    @Test
    fun incrementCloudClick_returnsPostIncrementValueAndPersists() = runTest {
        clearPrefs()
        val storage = SwiftieEggStorage(context)
        storage.awaitReady()

        assertThat(storage.incrementCloudClick()).isEqualTo(1)
        assertThat(storage.incrementCloudClick()).isEqualTo(2)
        assertThat(storage.cloudClickCount.value).isEqualTo(2)

        // 冷启动不重置：新实例读回同一个值
        val reopened = SwiftieEggStorage(context)
        reopened.awaitReady()
        assertThat(reopened.cloudClickCount.value).isEqualTo(2)
    }

    @Test
    fun markQuizSolved_doesNotImplyUnlocked() = runTest {
        clearPrefs()
        val storage = SwiftieEggStorage(context)
        storage.awaitReady()

        storage.markQuizSolved()

        assertThat(storage.quizSolved.value).isTrue()
        assertThat(storage.unlocked.value).isFalse()
    }

    @Test
    fun migrateLegacyNebulaUser_unlocksButKeepsQuizAvailable() = runTest {
        clearPrefs()
        val storage = SwiftieEggStorage(context)
        storage.awaitReady()

        storage.migrateLegacyNebulaUser("NEBULA")

        assertThat(storage.unlocked.value).isTrue()
        // 不夺走解题惊喜
        assertThat(storage.quizSolved.value).isFalse()
    }

    @Test
    fun migrateLegacyNebulaUser_isNoOpForOtherPresetsAndRunsOnlyOnce() = runTest {
        clearPrefs()
        val storage = SwiftieEggStorage(context)
        storage.awaitReady()

        storage.migrateLegacyNebulaUser("BLOOM")
        assertThat(storage.unlocked.value).isFalse()

        // 迁移已标记完成：即便之后又读到 NEBULA 也不再补锁
        storage.migrateLegacyNebulaUser("NEBULA")
        assertThat(storage.unlocked.value).isFalse()

        val prefs = context.swiftieDataStore.data.first()
        assertThat(prefs[SwiftieEggStorage.KEY_MIGRATED]).isTrue()
    }
}
