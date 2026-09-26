package com.tracktosearch.ui.component

import com.google.common.truth.Truth.assertThat
import java.io.File
import org.junit.Test

/**
 * 钉住天气动画起播闸门的**位置**，而不是它的内容。
 *
 * 这一条是组合期的时序约束，纯逻辑测不到（`shouldReplayWeather` 那几条已经覆盖了节流
 * 与减少动效），改错了也不会红：
 *
 * 日签开屏层是压在 AppNavigation **之上**的 Compose 层，不是另一个窗口，主界面在它背后
 * 照样从第一帧开始组合。而导航树又是在日签数据就绪之后才组合的（MainActivity 的
 * navComposed 等 stampJob.join()），所以搜索页一组合、Lottie composition 一到位，
 * 起播条件就全满足了 —— 整轮动画会在日签背后跑完。当天首看日签停留 8 秒，长过最长的
 * 天气动画（rainy/thunder 7.01s），于是用户一次都看不见，只看到定格帧。
 *
 * 同一个文件里白云暗示抖动早就用 `SplashStartup.quoteOverlayGone` 修过同型问题
 * （见 CloudNudgeGateOrderingTest），这里钉的是天气动画也得接同一根线。
 */
class CloudWeatherVisibilityGateTest {

    private fun source(path: String): String {
        var dir: File? = File(System.getProperty("user.dir")).absoluteFile
        while (dir != null) {
            val hit = File(dir, path)
            if (hit.isFile) return hit.readText()
            dir = dir.parentFile
        }
        throw IllegalStateException("找不到 $path（cwd=${System.getProperty("user.dir")}）")
    }

    private fun eggSource(): String =
        source("app/src/main/java/com/tracktosearch/ui/component/CloudEasterEgg.kt")

    /**
     * 取起播那个 LaunchedEffect 的正文（到 LottieAnimation 为止）。
     *
     * 必须先剥掉行注释：这段的注释里就写着 `claimWeatherPlay` 与 `quoteOverlayGone`，
     * 不剥的话下面每条断言都在跟散文比位置，测的是文笔不是代码。
     */
    private fun playEffectBlock(): String {
        val src = eggSource()
        val start = src.indexOf("LaunchedEffect(isCurrentTab")
        check(start >= 0) { "起播效应被改名或挪走了，本测试需要跟着调整" }
        val end = src.indexOf("LottieAnimation(", start)
        check(end >= 0) { "起播效应后面找不到 LottieAnimation，结构变了" }
        return src.substring(start, end)
            .lineSequence()
            .joinToString("\n") { line -> line.substringBefore("//") }
    }

    /**
     * 未让位就盖章 = 整轮在日签背后跑完，用户一次看不见。
     * 闸门必须排在 `claimWeatherPlay` **之前**：它只是「现在还轮不到播」，
     * 不是「这一轮播过了」。
     */
    @Test
    fun weather_waitsForTheQuoteOverlayToLeaveBeforeClaimingThePlay() {
        val block = playEffectBlock()
        val gate = block.indexOf("!splashQuoteGone")
        val claim = block.indexOf("claimWeatherPlay(")
        assertThat(gate).isGreaterThan(0)
        assertThat(claim).isGreaterThan(0)
        assertThat(gate).isLessThan(claim)
    }

    /**
     * 闸门没开时不能置 `settled`（「本轮已了结」）。提前置位等于把这轮吞掉：
     * 等日签真让位时效应重启，settled 已是 true，云停在定格帧再也不播。
     */
    @Test
    fun weather_doesNotSettleBeforeTheGateOpens() {
        val block = playEffectBlock()
        val gate = block.indexOf("!splashQuoteGone")
        val settle = block.indexOf("settled = true")
        assertThat(gate).isGreaterThan(0)
        assertThat(settle).isGreaterThan(0)
        assertThat(settle).isGreaterThan(gate)
    }

    /**
     * 闸门必须是效应的 key：不然 `quoteOverlayGone` 由 false 翻 true 时效应不重启，
     * 云停在第一帧再也不播 —— 「看不见地播完」变成「永远不播」，一样废。
     */
    @Test
    fun weather_reArmsWhenTheGateOpens() {
        val keys = Regex("LaunchedEffect\\(([^)]*)\\)")
            .find(eggSource())
            ?.groupValues
            ?.get(1)
        checkNotNull(keys) { "找不到起播效应的 key 列表" }
        assertThat(keys).contains("isCurrentTab")
        assertThat(keys).contains("composition")
        assertThat(keys).contains("splashQuoteGone")
    }

    /**
     * 起底 false 的闸门只有一份：日签层与搜索页共用 `SplashStartup.quoteOverlayGone`。
     * 这里读的是同一根线，不是另起一个组合内 remember —— 组合内那份在
     * `beyondViewportPageCount = 0` 下活不过一次切 Tab，也读不到日签的散场。
     */
    @Test
    fun weather_readsTheSharedSplashGate() {
        assertThat(eggSource()).contains("SplashStartup.quoteOverlayGone")
        assertThat(eggSource()).contains("collectAsStateWithLifecycle()")
    }
}
