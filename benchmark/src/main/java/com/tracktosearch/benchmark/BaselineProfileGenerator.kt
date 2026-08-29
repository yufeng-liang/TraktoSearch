package com.tracktosearch.benchmark

import androidx.benchmark.macro.junit4.BaselineProfileRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Baseline profile 与 startup profile 生成器。
 *
 * 与现状的关系：`app/src/main/baseline-prof.txt`（33327 行）目前是手工流程产出的 ——
 * 真机跑一遍页面，`adb shell cmd package dump-profiles`，再用 `tools/perf/filter_baseline_prof.py`
 * 过滤。那套流程能用，但有两个缺口：覆盖了哪些路径取决于人手滑了哪些页面，无法复现；
 * 而且完全没有 startup profile，AGP 拿不到做 dex 类布局优化的输入。
 *
 * 这个生成器补的就是这两点。产物落在 `app/src/release/generated/baselineProfiles/`，
 * AGP 会把它和 `src/main/baseline-prof.txt` 合并，**不覆盖**手工那份 ——
 * 先跑起来比对两者覆盖率，再决定是否替换。
 *
 * 运行：
 * ```
 * ./gradlew :app:generateReleaseBaselineProfile
 * ```
 * 采集走 baselineprofile 插件派生的 nonMinifiedRelease 变体（关掉 R8 才能采到未混淆签名），
 * 该变体的签名已在 app/build.gradle.kts 里换成 debug，不需要正式发布密钥。
 *
 * 前置：设备亮屏解锁、已登录 Trakt（否则「我的」页只会录到空列表分支）。
 */
@RunWith(AndroidJUnit4::class)
class BaselineProfileGenerator {

    @get:Rule
    val baselineProfileRule = BaselineProfileRule()

    /**
     * 只覆盖冷启动到首屏可交互。
     *
     * 单独一个用例、`includeInStartupProfile = true`：startup profile 决定 dex 里类的排布顺序，
     * 混入滚动/切 tab 的类只会把启动真正需要的类推散，反而降低启动时的顺序读命中率。
     */
    @Test
    fun startup() = baselineProfileRule.collect(
        packageName = TARGET_PACKAGE,
        includeInStartupProfile = true
    ) {
        pressHome()
        launchAndWaitForContent()
    }

    /**
     * 覆盖四个 tab 的首次组合、各页滚动、以及详情进入/返回。
     *
     * `includeInStartupProfile = false`：这些类进 baseline profile（消除 JIT），
     * 但不参与 dex 布局排序。
     */
    @Test
    fun userJourney() = baselineProfileRule.collect(
        packageName = TARGET_PACKAGE,
        includeInStartupProfile = false
    ) {
        pressHome()
        launchAndWaitForContent()

        // 逐个 tab：切过去 + 滚两屏，覆盖首次组合与列表回收两条路径
        MainTab.entries.forEach { tab ->
            if (openTab(tab)) {
                repeat(2) { scrollDownOnce() }
                scrollUpOnce()
            }
        }

        // 详情进入 + 返回：覆盖 NavHost 转场、共享元素、详情页首屏
        openTab(MainTab.DISCOVER)
        openFirstDetailAndBack()
    }
}
