package com.tracktosearch.ui.screen.login

import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import org.junit.Test

/**
 * 跑马灯四档的取档规则。
 *
 * 三个入参（验码中、报错、正在打印）互不排斥，四档灯互斥，所以优先级本身就是设计的一部分，
 * 而它错了不会编译失败也不会在装机截图上一眼看出来 —— 只有正好赶上那个时刻才看得见。
 */
class MarqueeBulbSignalTest {

    @Test
    fun 验码期间是流水黄灯() {
        assertThat(signal(isLoading = true)).isEqualTo(BulbSignal.Verifying)
    }

    /**
     * 用户改完码再按一次取票，上一次的 `error` 要等新响应回来才清。
     * 这段时间里报错和验码同时为真，该看到的是新一次的流水，不是上一次的红灯。
     */
    @Test
    fun 上一次的报错盖不住这一次的验码() {
        assertThat(signal(isLoading = true, statusIsError = true))
            .isEqualTo(BulbSignal.Verifying)
    }

    @Test
    fun 验码结束才轮到结果灯() {
        assertThat(signal(statusIsError = true)).isEqualTo(BulbSignal.Failure)
        assertThat(signal(isPrinting = true)).isEqualTo(BulbSignal.Success)
    }

    /** 万一两个结果同时为真（响应成功但紧接着又报了错），红灯优先：出错比出票更该被看见。 */
    @Test
    fun 报错压过打印() {
        assertThat(signal(statusIsError = true, isPrinting = true))
            .isEqualTo(BulbSignal.Failure)
    }

    @Test
    fun 什么都没发生就是平常那一档() {
        assertThat(signal()).isEqualTo(BulbSignal.Sweep)
    }

    /**
     * 只有两个结果档带整排颜色。
     *
     * `MarqueeBulbs` 就是靠 `glow` 是不是 null 决定「整排同亮」还是「头灯扫」的：
     * 哪天给验码档配上一个 glow，黄灯就从流水变成整排常亮，跟「机器在忙」的意思相反。
     */
    @Test
    fun 只有结果档整排同色其余两档靠头灯扫() {
        for (signal in listOf(BulbSignal.Sweep, BulbSignal.Verifying)) {
            assertWithMessage("$signal 不该带整排颜色，它要靠头灯扫")
                .that(signal.glow).isNull()
        }
        for (signal in listOf(BulbSignal.Success, BulbSignal.Failure)) {
            assertWithMessage("$signal 是一个结论，整排得同色")
                .that(signal.glow).isNotNull()
        }
    }

    private fun signal(
        isLoading: Boolean = false,
        statusIsError: Boolean = false,
        isPrinting: Boolean = false,
    ): BulbSignal = bulbSignalFor(
        isLoading = isLoading,
        statusIsError = statusIsError,
        isPrinting = isPrinting
    )
}
