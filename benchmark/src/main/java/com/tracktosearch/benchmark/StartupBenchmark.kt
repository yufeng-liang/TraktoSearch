package com.tracktosearch.benchmark

import androidx.benchmark.macro.BaselineProfileMode
import androidx.benchmark.macro.CompilationMode
import androidx.benchmark.macro.StartupMode
import androidx.benchmark.macro.StartupTimingMetric
import androidx.benchmark.macro.junit4.MacrobenchmarkRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * 冷启动耗时基准。
 *
 * 三个用例是同一场景的三种编译状态，用来分离「代码本身慢」和「没被 AOT 编译」：
 *
 * - [coldStartupNoCompilation]：清空编译产物，纯解释/JIT。相当于用户全新安装、profile 还没生效的最差情况。
 * - [coldStartupBaselineProfile]：只按 baseline profile 做部分 AOT。这是绝大多数真实用户的状态。
 *   用 [BaselineProfileMode.Require]，profile 没被正确安装时直接失败 —— 这正是本项目需要的校验：
 *   `app/src/main/baseline-prof.txt` 现在 33327 行，但此前无任何手段证明它真的生效、也无法发现覆盖率退化。
 * - [coldStartupFull]：全量 AOT，作为「编译不再是瓶颈」的理论下界。
 *
 * 三者的差值就是 baseline profile 的实际价值；`coldStartupBaselineProfile` 与 `coldStartupFull`
 * 的剩余差距是 profile 覆盖不到的部分，可据此判断要不要重新生成。
 *
 * 运行：
 * ```
 * ./gradlew :benchmark:connectedBenchmarkBenchmarkAndroidTest \
 *     -Pandroid.testInstrumentationRunnerArguments.class=com.tracktosearch.benchmark.StartupBenchmark
 * ```
 * 任务名里两个 Benchmark 不是笔误：前一个是 :app 的 benchmark 构建类型，后一个是本模块的。
 */
@RunWith(AndroidJUnit4::class)
class StartupBenchmark {

    @get:Rule
    val benchmarkRule = MacrobenchmarkRule()

    @Test
    fun coldStartupNoCompilation() = measureColdStartup(CompilationMode.None())

    @Test
    fun coldStartupBaselineProfile() =
        measureColdStartup(CompilationMode.Partial(BaselineProfileMode.Require))

    @Test
    fun coldStartupFull() = measureColdStartup(CompilationMode.Full())

    /**
     * @param compilationMode 每次迭代前把应用重置到该编译状态
     */
    private fun measureColdStartup(compilationMode: CompilationMode) = benchmarkRule.measureRepeated(
        packageName = TARGET_PACKAGE,
        metrics = listOf(StartupTimingMetric()),
        compilationMode = compilationMode,
        // 10 次：冷启动的 run-to-run 抖动比帧耗时大（磁盘缓存、DB 解密、SQLCipher loadLibs），
        // 少于 10 次的中位数不足以跟现有 ±7pp 噪声地板区分开。
        iterations = 10,
        startupMode = StartupMode.COLD,
        setupBlock = { pressHome() }
    ) {
        launchAndWaitForContent()
    }
}
