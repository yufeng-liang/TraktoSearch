package com.tracktosearch.benchmark

import androidx.benchmark.macro.BaselineProfileMode
import androidx.benchmark.macro.CompilationMode
import androidx.benchmark.macro.FrameTimingMetric
import androidx.benchmark.macro.MacrobenchmarkScope
import androidx.benchmark.macro.StartupMode
import androidx.benchmark.macro.junit4.MacrobenchmarkRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * 帧耗时基准，对应性能报告里的四个滚动/切换场景与详情返回路径。
 *
 * 每个场景都有 cold / warm 两个用例。这是本项目最需要的一组对照：报告里
 * `me_scroll` 冷 300 掉帧 / 热 0 掉帧、`tab_switch` 冷 202 / 热 0 —— 卡顿几乎全部集中在
 * 首次进入某个表面（海报解码 + 首次组合 + JIT），而已落地的优化改善的都是稳态滚动。
 * 分开测才能看出冷路径有没有真的动过。
 *
 * 编译状态统一用 [BaselineProfileMode.Require]：测的是绝大多数真实用户的状态，
 * 同时顺带校验 `app/src/main/baseline-prof.txt` 确实被安装生效。
 *
 * 运行：
 * ```
 * ./gradlew :benchmark:connectedBenchmarkBenchmarkAndroidTest \
 *     -Pandroid.testInstrumentationRunnerArguments.class=com.tracktosearch.benchmark.ScrollBenchmark
 * ```
 * 任务名里两个 Benchmark 不是笔误：前一个是 :app 的 benchmark 构建类型，后一个是本模块的。
 *
 * 前置：设备亮屏解锁。「我的」页依赖已登录的 Trakt 数据，未登录设备上该场景会测到空列表。
 */
@RunWith(AndroidJUnit4::class)
class ScrollBenchmark {

    @get:Rule
    val benchmarkRule = MacrobenchmarkRule()

    // ---- 发现页 ----

    @Test
    fun discoverScrollCold() = frameBenchmark(StartupMode.COLD, MainTab.DISCOVER)

    @Test
    fun discoverScrollWarm() = frameBenchmark(StartupMode.WARM, MainTab.DISCOVER)

    // ---- 我的（观看列表网格，报告里最重的场景） ----

    @Test
    fun watchlistScrollCold() = frameBenchmark(StartupMode.COLD, MainTab.ME)

    @Test
    fun watchlistScrollWarm() = frameBenchmark(StartupMode.WARM, MainTab.ME)

    // ---- 设置页 ----

    @Test
    fun settingsScrollCold() = frameBenchmark(StartupMode.COLD, MainTab.SETTINGS)

    @Test
    fun settingsScrollWarm() = frameBenchmark(StartupMode.WARM, MainTab.SETTINGS)

    // ---- 切 Tab ----

    /**
     * 四个 tab 依次切一轮。报告里这个场景冷态 202 掉帧、P99 200ms，是切换瞬间多页
     * backdrop 同时重采的结果。
     */
    @Test
    fun tabSwitchCold() = benchmarkRule.measureRepeated(
        packageName = TARGET_PACKAGE,
        metrics = listOf(FrameTimingMetric()),
        compilationMode = COMPILATION_MODE,
        iterations = ITERATIONS,
        startupMode = StartupMode.COLD,
        setupBlock = {
            pressHome()
            launchAndWaitForContent()
        }
    ) {
        MainTab.entries.forEach { openTab(it) }
    }

    // ---- 详情进入 + 返回 ----

    /**
     * 报告里返回首帧尖峰 300~680ms（最新基线 571ms）的那条路径，根因是 NavHost 移除详情页时
     * 整窗显示列表重固。已试的 4 个方案全部回滚，这里先把它变成可重复测量的数字，
     * 后续任何尝试都能对着同一组指标比。
     */
    @Test
    fun detailEnterAndBack() = benchmarkRule.measureRepeated(
        packageName = TARGET_PACKAGE,
        metrics = listOf(FrameTimingMetric()),
        compilationMode = COMPILATION_MODE,
        iterations = ITERATIONS,
        startupMode = StartupMode.WARM,
        setupBlock = {
            pressHome()
            launchAndWaitForContent()
            openTab(MainTab.DISCOVER)
        }
    ) {
        openFirstDetailAndBack()
    }

    /**
     * 通用滚动基准：启动到指定 tab，滚 [SCROLL_COUNT] 屏。
     *
     * @param startupMode COLD 每次迭代杀进程重启（测冷路径），WARM 保留进程只重启 Activity（测稳态）
     * @param tab 目标底部导航 tab
     */
    private fun frameBenchmark(
        startupMode: StartupMode,
        tab: MainTab
    ) = benchmarkRule.measureRepeated(
        packageName = TARGET_PACKAGE,
        metrics = listOf(FrameTimingMetric()),
        compilationMode = COMPILATION_MODE,
        iterations = ITERATIONS,
        startupMode = startupMode,
        setupBlock = {
            pressHome()
            launchAndWaitForContent()
            openTab(tab)
        },
        measureBlock = { scrollSweep() }
    )

    private fun MacrobenchmarkScope.scrollSweep() {
        repeat(SCROLL_COUNT) { scrollDownOnce() }
    }

    private companion object {
        val COMPILATION_MODE = CompilationMode.Partial(BaselineProfileMode.Require)

        /** 10 次迭代取中位数，与 [StartupBenchmark] 对齐。 */
        const val ITERATIONS = 10

        /** 每次测量滚 4 屏：够覆盖列表回收 + 屏外海报预取，又不至于滚到分页加载触发网络。 */
        const val SCROLL_COUNT = 4
    }
}
