package com.tracktosearch.benchmark

import androidx.benchmark.macro.BaselineProfileMode
import androidx.benchmark.macro.CompilationMode
import androidx.benchmark.macro.FrameTimingMetric
import androidx.benchmark.macro.MacrobenchmarkScope
import androidx.benchmark.macro.StartupMode
import androidx.benchmark.macro.junit4.MacrobenchmarkRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.uiautomator.By
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.UiObject2
import androidx.test.uiautomator.Until
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * 共享元素转场的帧耗时基准：三条往返路径各测一遍，给转场系统重写留下一组重写前后能直接对比的数字。
 *
 * 三个用例的 measureBlock 里只有一次「打开 + 关闭」，别的什么都不放。FrameTimingMetric 是对整个
 * measureBlock 求聚合的，多塞一次滚动或一次切 tab，转场那几十帧就会被稀释成看不出差别的中位数 ——
 * 所以导航到起点的动作全部留在 setupBlock。
 *
 * 与 [ScrollBenchmark] 的另一处区别是失败方式：这里每一步导航都断言目标节点出现，不出现就抛。
 * `BenchmarkActions.kt` 里的助手返回 false 而不抛，是因为未登录设备上「我的」页合理地空着，
 * 滚一遍空列表仍是有意义的观测；但转场如果根本没发生，就不会产生任何坏帧，指标反而漂亮，
 * 结论会被彻底带反。这种情况必须让整轮用例失败，而不是给出一个假的好数字。
 *
 * 选择器统一用 `By.res(tag).pkg(TARGET_PACKAGE)`，带包名限定：万一中途退到了桌面，断言直接失败，
 * 不会像 `By.desc("设置")` 那样误点到桌面上的系统设置图标。
 * 这些断言同时充当等待，开屏引言（SplashQuoteOverlay）会在等待期间自然结束，不需要额外补点击或
 * sleep 去关它。
 *
 * 前置条件全靠手工准备，跑之前逐条确认：
 * - 设备亮屏解锁。
 * - 应用已过激活关卡。未激活设备冷启动停在激活登录页，本文件的三条路径一条都不存在。
 * - 新手引导已完成。
 * - 已登录会话：「我的」观看列表网格要求 `isTraktConnected || isDoubanLoggedIn`，设置页统计卡片
 *   还额外要求 `isLoggedIn && (isDoubanMode || isTraktConnected)`。未登录设备上这两个节点根本不存在，
 *   用例会按设计大声失败。
 *
 * 运行：
 * ```
 * ./gradlew :benchmark:connectedBenchmarkBenchmarkAndroidTest \
 *     -Pandroid.testInstrumentationRunnerArguments.class=com.tracktosearch.benchmark.TransitionBenchmark
 * ```
 * 任务名里两个 Benchmark 不是笔误：前一个是 :app 的 benchmark 构建类型，后一个是本模块的。
 */
@RunWith(AndroidJUnit4::class)
class TransitionBenchmark {

    @get:Rule
    val benchmarkRule = MacrobenchmarkRule()

    /**
     * 观看列表海报卡 → 详情页 → 返回。
     *
     * 和 [ScrollBenchmark.detailEnterAndBack] 测的是同一类路径，但那个用例按坐标点发现页首行、
     * 点空了也只是软退回；这里用 testTag 点到网格里的卡片，转场两端都断言，路径是确定的。
     * `Until.findObject` 返回节点树里第一个匹配项，也就是网格首张可见卡片。
     */
    @Test
    fun watchlistPosterToDetailAndBack() = transitionBenchmark(
        setup = {
            pressHome()
            launchAndWaitForContent()
            openTabOrFail(MainTab.ME)
            device.awaitTag(POSTER_CARD)
        },
        roundTrip = {
            device.awaitTag(POSTER_CARD).click()
            device.awaitTag(DETAIL_SCREEN)
            device.pressBack()
            device.awaitTag(POSTER_CARD)
        }
    )

    /**
     * 设置页统计卡片 → 统计页 → 返回。
     *
     * 卡片与统计页头部用 `sharedBounds`（key `settings-statistics-entry`）配对，是展开/收起型转场，
     * 形态和详情页那种整页替换不同，重写时两种都要有数字盯着。
     */
    @Test
    fun settingsStatisticsCardToScreenAndBack() = transitionBenchmark(
        setup = {
            pressHome()
            launchAndWaitForContent()
            openTabOrFail(MainTab.SETTINGS)
            device.awaitTag(SETTINGS_STATISTICS_CARD)
        },
        roundTrip = {
            device.awaitTag(SETTINGS_STATISTICS_CARD).click()
            device.awaitTag(STATISTICS_SCREEN)
            device.pressBack()
            device.awaitTag(SETTINGS_STATISTICS_CARD)
        }
    )

    /**
     * 详情页头部海报 → 全屏大图 → 关闭。
     *
     * 关闭动作只能用 `pressBack()`：overlay 里的手势在共享转场运行期间是关掉的
     * （`PosterFullscreenOverlay` 传的是 `gesturesEnabled = !animatedVisibilityScope.transition.isRunning`），
     * 转场刚起步时点屏幕会被直接丢掉；返回键则由 overlay 自己接管（`backHandlerEnabled = true`，
     * 未放大时一次返回即关闭），这才是这条路径设计好的关闭入口。
     *
     * setupBlock 里要断言 [DETAIL_HEADER_POSTER] 存在：头部海报只有海报 URL 非空时才可点、才带这个 tag，
     * tag 不在就说明这条路径本来不成立，让用例失败是对的。
     *
     * 关闭后用 `Until.gone` 确认 overlay 真的没了，而不是只等它开始收起 —— 收起是这次往返的另一半。
     */
    @Test
    fun detailPosterFullscreenOpenAndClose() = transitionBenchmark(
        setup = {
            pressHome()
            launchAndWaitForContent()
            openTabOrFail(MainTab.ME)
            device.awaitTag(POSTER_CARD).click()
            device.awaitTag(DETAIL_SCREEN)
            device.awaitTag(DETAIL_HEADER_POSTER)
        },
        roundTrip = {
            device.awaitTag(DETAIL_HEADER_POSTER).click()
            device.awaitTag(POSTER_FULLSCREEN_OVERLAY)
            device.pressBack()
            device.awaitTagGone(POSTER_FULLSCREEN_OVERLAY)
        }
    )

    /**
     * 三个用例共用的配置。
     *
     * 用 WARM 而不是 COLD：转场是稳态交互，冷启动那一段（海报解码、首次组合、JIT）会盖过要测的东西。
     * WARM 下进程还活着，setupBlock 重新拉起 Activity 再导航到起点，measure 里只剩往返那一次。
     *
     * 有一处测量边界要知道：断言用的 `Until.findObject` 等到节点进语义树就返回，不等动画播完，
     * 所以测量窗口的右边界是「节点出现」而不是「转场结束」。重写前后用的是同一把尺子，比较仍然成立。
     *
     * @param setup 导航到起点，所有断言都在这里；抛异常即让该次迭代失败
     * @param roundTrip 只放一次「打开 + 关闭」往返
     */
    private fun transitionBenchmark(
        setup: MacrobenchmarkScope.() -> Unit,
        roundTrip: MacrobenchmarkScope.() -> Unit
    ) = benchmarkRule.measureRepeated(
        packageName = TARGET_PACKAGE,
        metrics = listOf(FrameTimingMetric()),
        compilationMode = COMPILATION_MODE,
        iterations = ITERATIONS,
        startupMode = StartupMode.WARM,
        setupBlock = setup,
        measureBlock = roundTrip
    )

    /**
     * 等 testTag 为 [tag] 的节点出现并返回它，等不到就抛。
     *
     * 用单参的 `By.res(tag)`：Compose 的 `testTagsAsResourceId` 把 testTag 原样写进无障碍节点的
     * resource-id，不加包名前缀（实机 uiautomator dump 里就是 `resource-id="poster_card"`），
     * 所以两参的 `By.res(pkg, id)` 会去匹配 `com.tracktosearch:id/poster_card` 而永远落空。
     * 包名限定由链上的 `.pkg()` 补，作用等价。
     */
    private fun UiDevice.awaitTag(tag: String, timeoutMs: Long = UI_TIMEOUT_MS): UiObject2 =
        wait(Until.findObject(By.res(tag).pkg(TARGET_PACKAGE)), timeoutMs)
            ?: error("未找到节点 $tag：设备可能停在激活页/引导页，或该页面依赖的登录态缺失")

    /** 等 testTag 为 [tag] 的节点消失，[timeoutMs] 内还在就抛。 */
    private fun UiDevice.awaitTagGone(tag: String, timeoutMs: Long = UI_TIMEOUT_MS) {
        val gone = wait(Until.gone(By.res(tag).pkg(TARGET_PACKAGE)), timeoutMs)
        if (gone != true) {
            error("节点 $tag 在 ${timeoutMs}ms 内没有消失：关闭动作没生效，这次采样测的不是完整往返")
        }
    }

    /**
     * 切到指定底部导航 tab，切不到就抛。
     *
     * [openTab] 返回 false 而不抛对滚动基准是对的，转场基准需要硬失败，理由见类文档。
     */
    private fun MacrobenchmarkScope.openTabOrFail(tab: MainTab) {
        if (!openTab(tab)) {
            error("未点到底部导航「${tab.zh}」：应用可能不在主界面 —— 激活页、引导页、详情页都没有底部导航栏")
        }
    }

    private companion object {
        val COMPILATION_MODE = CompilationMode.Partial(BaselineProfileMode.Require)

        /** 10 次迭代取中位数，与 [ScrollBenchmark] 对齐，两边的帧耗时才好横向比。 */
        const val ITERATIONS = 10

        /** 等节点出现的统一超时，与 `BenchmarkActions.kt` 里的同名常量同值（那个是 file-private，不去放宽它）。 */
        const val UI_TIMEOUT_MS = 8_000L

        // 以下六个 testTag 由 UI 侧改动加在对应组件上。前提是应用根节点开了 `testTagsAsResourceId`，
        // 否则 testTag 不会映射成无障碍节点的 resource-id，By.res 一个都匹配不到。
        const val POSTER_CARD = "poster_card"
        const val DETAIL_SCREEN = "detail_screen"
        const val DETAIL_HEADER_POSTER = "detail_header_poster"
        const val POSTER_FULLSCREEN_OVERLAY = "poster_fullscreen_overlay"
        const val SETTINGS_STATISTICS_CARD = "settings_statistics_card"
        const val STATISTICS_SCREEN = "statistics_screen"
    }
}
