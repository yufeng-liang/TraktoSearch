package com.tracktosearch.ui.screen.swiftie

import com.google.common.truth.Truth.assertThat
import java.io.File
import org.junit.Test

/**
 * 钉住「白云暗示」两条闸门的**位置**，而不是它们的内容。
 *
 * 这两条都是组合期的时序约束，纯逻辑测不到，改错了也不会红：
 * 1. 日签层压在搜索页之上，主界面在它背后照样组合。停留计时不等它让位，整轮抖动
 *    会在日签背后跑完并记上配额 —— 预算只有 3 次，等于功能永久失效。
 * 2. `beginCloudNudgeRound()` 一占位就吃掉整个进程的额度。可见性闸门必须排在它**前面**，
 *    否则冷启动那一帧的瞬态 true 会把闸门占死，首次启动的用户永远看不到暗示。
 * 两条都是真机上量出来的（录屏里云朵接触带逐帧 scene 分数全程 0.000，而 DataStore 已记账）。
 */
class CloudNudgeGateOrderingTest {

    private fun source(path: String): String {
        var dir: File? = File(System.getProperty("user.dir")).absoluteFile
        while (dir != null) {
            val hit = File(dir, path)
            if (hit.isFile) return hit.readText()
            dir = dir.parentFile
        }
        throw IllegalStateException("找不到 $path（cwd=${System.getProperty("user.dir")}）")
    }

    /**
     * 取 nudge 那段 LaunchedEffect，避免命中文件里其他 onboardingCompleted 读法。
     *
     * 必须先剥掉行注释：这段的注释里就写着 `beginCloudNudgeRound()` 与
     * `onboardingCompleted`，不剥的话下面每条断言都在跟散文比位置，测的是文笔不是代码。
     */
    private fun nudgeBlock(): String {
        val src = source("app/src/main/java/com/tracktosearch/ui/screen/search/SearchScreen.kt")
        val start = src.indexOf("val cloudNudge = remember")
        check(start >= 0) { "搜索页的抖动驱动块被改名或挪走了，本测试需要跟着调整" }
        val end = src.indexOf("completeCloudNudgeRound()", start)
        check(end >= 0)
        return src.substring(start, end)
            .lineSequence()
            .joinToString("\n") { line -> line.substringBefore("//") }
    }

    @Test
    fun nudge_gatesVisibilityBeforeOccupyingTheProcessWideRound() {
        val block = nudgeBlock()
        val occupy = block.indexOf("beginCloudNudgeRound()")
        assertThat(occupy).isGreaterThan(0)
        // 三条「现在轮不到抖」的判断都必须早于占位：晚一步就是白占，重启前再也不会抖。
        // 挑 guard 的写法而不是变量名，免得声明行自己就把断言喂成空过。
        listOf("if (!splashQuoteGone", "if (reducedMotion)").forEach { guard ->
            assertThat(block.indexOf(guard)).isGreaterThan(0)
            assertThat(block.indexOf(guard)).isAtMost(occupy)
        }
        val onboardingGuard = block.indexOf("!nudgeOnboardingDone")
        assertThat(onboardingGuard).isGreaterThan(0)
        assertThat(onboardingGuard).isAtMost(occupy)
    }

    @Test
    fun nudge_doesNotReadTheOptimisticOnboardingState() {
        val block = nudgeBlock()
        // 那份初值为 true 的 onboardingCompleted 是给云点击用的（没读到就当成已完成，
        // 免得吞掉正常点击）。抖动反过来：没读到就不该抖。
        assertThat(block).doesNotContain("onboardingCompleted")
        assertThat(block).contains("nudgeOnboardingDone")
        assertThat(block).contains("initialValue = false")
    }

    @Test
    fun shakeCount_isDrivenByTheConstantNotByRepetition() {
        val block = nudgeBlock()
        // 有人图省事在 repeat 外面再手搓一记，`NUDGE_SHAKES` 就开始说谎，
        // 而「整轮抖完才记账」的判据也跟着错位。只留一处 animateTo 就改不坏。
        assertThat(Regex("animateTo\\(").findAll(block).count()).isEqualTo(1)
        assertThat(Regex("snapTo\\(").findAll(block).count()).isEqualTo(1)
        assertThat(Regex("NUDGE_SHAKES").findAll(block).count()).isEqualTo(1)
        // 静默只夹在记与记之间：整段里 delay 恰好两处 —— 停留一次、记间一次
        assertThat(Regex("delay\\(").findAll(block).count()).isEqualTo(2)
    }

    @Test
    fun splashGate_startsClosedAndOpensOnlyAfterTheStampDecides() {
        val src = source("app/src/main/java/com/tracktosearch/MainActivity.kt")
        // 起底 false 是这条闸门唯一不「看不见地抖」的写法：stampJob 要读语言、读盘、解海报
        // 才定得下日签上不上屏，那中间搜索页早就组合完了。起底 true 会让它抢先占掉
        // beginCloudNudgeRound() 的进程闸门，等 stampJob 改回 false 时效应重启、闸门已耗尽。
        assertThat(src)
            .contains("quoteOverlayGone = kotlinx.coroutines.flow.MutableStateFlow(false)")
        // 日签没上屏（开关关着、海报没就绪）时必须放行，否则下游计时永久锁死
        assertThat(src).contains("SplashStartup.quoteOverlayGone.value = !stampReady")
        // 放行点排在 stampReady 定案之后，两者之间不让出主线程
        assertThat(src.indexOf("SplashStartup.quoteOverlayGone.value = !stampReady"))
            .isGreaterThan(src.indexOf("stampReady = splashQuote != null"))
        // 日签自己散场时放行
        assertThat(src).contains("SplashStartup.quoteOverlayGone.value = true")
    }
}
