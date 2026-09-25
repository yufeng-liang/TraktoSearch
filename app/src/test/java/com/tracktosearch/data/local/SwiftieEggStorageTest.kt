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

    @Test
    fun cloudNudgeShown_defaultsToZero() = runTest {
        clearPrefs()
        val storage = SwiftieEggStorage(context)
        storage.awaitReady()

        assertThat(storage.cloudNudgeShown.value).isEqualTo(0)
    }

    @Test
    fun cloudNudgeShown_incrementsAndSurvivesReopen() = runTest {
        clearPrefs()
        val storage = SwiftieEggStorage(context)
        storage.awaitReady()

        assertThat(storage.incrementCloudNudgeShown()).isEqualTo(1)
        assertThat(storage.incrementCloudNudgeShown()).isEqualTo(2)
        assertThat(storage.cloudNudgeShown.value).isEqualTo(2)

        // 预算是跨进程的：冷启动就重置的话「累计 3 次」永远到不了 3
        val reopened = SwiftieEggStorage(context)
        reopened.awaitReady()
        assertThat(reopened.cloudNudgeShown.value).isEqualTo(2)
    }

    @Test
    fun cloudNudgeShown_isIndependentOfCloudClickCount() = runTest {
        clearPrefs()
        val storage = SwiftieEggStorage(context)
        storage.awaitReady()

        storage.incrementCloudClick()
        storage.incrementCloudClick()

        assertThat(storage.cloudClickCount.value).isEqualTo(2)
        assertThat(storage.cloudNudgeShown.value).isEqualTo(0)

        // 必须 reopen 再断一次，否则这条测只钉住了「内存里两个 StateFlow 各是各的」，
        // 钉不住「磁盘键各是各的」。若把 KEY_CLOUD_NUDGE_SHOWN 误写成
        // intPreferencesKey("cloud_click_count")，上面两行照样全绿：
        // 两个 StateFlow 是独立字段，而 incrementCloudNudgeShown 单独跑在共享键上
        // 算出来的也正好是 1、2 —— 全计划只有这条测同时动两个计数器，
        // 所以它是唯一能抓住键名碰撞的地方。
        storage.incrementCloudNudgeShown()

        val reopened = SwiftieEggStorage(context)
        reopened.awaitReady()
        assertThat(reopened.cloudClickCount.value).isEqualTo(2)
        assertThat(reopened.cloudNudgeShown.value).isEqualTo(1)
    }
}
