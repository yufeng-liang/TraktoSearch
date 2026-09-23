package com.tracktosearch.data.local

import android.app.Application
import android.content.Context
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.time.LocalDate

/**
 * 台词日历「那天的卡片读过没有」的持久化。
 *
 * 隔离靠**互不相交的 day 段**，不靠清盘：`preferencesDataStore` 那个委托是进程单例，
 * 各用例新建 [DailyStampReadStorage] 仍落在同一份磁盘数据上，跨用例互相看得见
 * （同 HapticStorageTest 顶部那段说明）。所以断言一律用 contains，且每个用例只碰自己那
 * 一段日子——写成 containsExactly 会去数别人留下的条目。
 *
 * 测的都是这个标记存在的理由：标记要跨进程活下来（用户点开看完、杀掉 App，再进来那一格
 * 不该又糊回去，那等于要求他把刚看过的东西再看一遍）；要立刻反映到内存镜像（日历靠它
 * 决定那一格是不是还得糊着，只落盘不更新镜像的话用户点完卡片格子不变）；写进去的必须是
 * 传进来的那天，不是「今天」。
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [33])
class DailyStampReadStorageTest {

    private val appContext: Context = RuntimeEnvironment.getApplication()

    @Test
    fun `标记过的日子换一个实例还读得到`() = runTest {
        DailyStampReadStorage(appContext).markRead(1_000L)

        // 新实例的镜像初值是空集，只能从磁盘读回来；读不到就说明 markRead 根本没落盘
        assertThat(DailyStampReadStorage(appContext).loadIntoMirror()).contains(1_000L)
    }

    @Test
    fun `标记立刻进内存镜像不必等下次启动`() = runTest {
        val storage = DailyStampReadStorage(appContext)

        storage.markRead(2_000L)

        // 刻意不调 loadIntoMirror：只落盘、不更新镜像的实现在这里露馅，
        // 而对用户来说那意味着点开卡片退出后，那一格还是糊的
        assertThat(storage.readDays.value).contains(2_000L)
    }

    @Test
    fun `记下的是传进来的那天而不是今天`() = runTest {
        val storage = DailyStampReadStorage(appContext)
        val today = LocalDate.now().toEpochDay()

        storage.markRead(3_000L)

        val mirrored = storage.readDays.value
        assertThat(mirrored).contains(3_000L)
        // 写成 LocalDate.now 就会在这里露出来：补看过的格子永远糊着，或没看的先清晰
        assertThat(mirrored).doesNotContain(today)
    }
}
