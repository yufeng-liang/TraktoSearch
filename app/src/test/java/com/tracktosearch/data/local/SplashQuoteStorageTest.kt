package com.tracktosearch.data.local

import android.app.Application
import android.content.Context
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.io.File

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [33])
class SplashQuoteStorageTest {

    private val appContext: Context = RuntimeEnvironment.getApplication()

    @Before
    fun clearStorage() {
        // 本类首次访问 DataStore 前清掉上次测试进程留下的文件，避免序列进度跨运行污染。
        File(appContext.filesDir, "datastore/splash_quote.preferences_pb").delete()
    }

    @Test
    fun `旧版已展示用户升级后视为固定序列完成`() {
        assertThat(
            openingSequenceProgress(
                storedProgress = null,
                legacyDebutShown = true,
                sequenceSize = 3,
            )
        ).isEqualTo(3)
        assertThat(
            openingSequenceProgress(
                storedProgress = 1,
                legacyDebutShown = true,
                sequenceSize = 3,
            )
        ).isEqualTo(1)
    }

    @Test
    fun `固定序列按不同打开日推进且断签不跳过`() = runTest {
        val storage = SplashQuoteStorage(appContext)
        val sequenceSize = 3
        val firstDay = 100L

        assertThat(storage.openingSequenceIndex(firstDay, sequenceSize)).isEqualTo(0)

        // 错误条目不能越过第一条。
        storage.markOpeningQuoteShown(index = 1, today = firstDay, sequenceSize = sequenceSize)
        assertThat(storage.openingSequenceIndex(firstDay, sequenceSize)).isEqualTo(0)

        storage.markOpeningQuoteShown(index = 0, today = firstDay, sequenceSize = sequenceSize)
        // 同一天重复启动仍显示第一条，不消耗第二条。
        assertThat(storage.openingSequenceIndex(firstDay, sequenceSize)).isEqualTo(0)

        val secondOpenDay = firstDay + 5
        assertThat(storage.openingSequenceIndex(secondOpenDay, sequenceSize)).isEqualTo(1)
        storage.markOpeningQuoteShown(index = 1, today = secondOpenDay, sequenceSize = sequenceSize)

        val thirdOpenDay = secondOpenDay + 20
        assertThat(storage.openingSequenceIndex(thirdOpenDay, sequenceSize)).isEqualTo(2)
        storage.markOpeningQuoteShown(index = 2, today = thirdOpenDay, sequenceSize = sequenceSize)
        assertThat(storage.openingSequenceIndex(thirdOpenDay, sequenceSize)).isEqualTo(2)

        // 第四个实际打开日开始恢复普通日期轮换。
        assertThat(storage.openingSequenceIndex(thirdOpenDay + 1, sequenceSize)).isNull()
    }
}
