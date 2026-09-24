package com.tracktosearch.ui.screen.swiftie

import com.google.common.truth.Truth.assertThat
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin
import org.junit.Test

class SwiftieEggControllerTest {

    private fun action(
        count: Int,
        solved: Boolean = false,
        onboarded: Boolean = true,
        granted: Boolean = true
    ) = SwiftieEggController.resolveCloudAction(count, solved, onboarded, granted)

    @Test
    fun unsolved_cyclesEggPermissionLottie() {
        assertThat(action(0, granted = false)).isEqualTo(CloudAction.SWIFTIE_EGG)
        assertThat(action(1, granted = false)).isEqualTo(CloudAction.LOCATION_PERMISSION)
        assertThat(action(2, granted = false)).isEqualTo(CloudAction.RANDOM_LOTTIE)
        // 取模循环：关掉题面的人过两下还能再遇到彩蛋
        assertThat(action(3, granted = false)).isEqualTo(CloudAction.SWIFTIE_EGG)
    }

    @Test
    fun unsolved_permissionSlotFallsBackToLottieWhenAlreadyGranted() {
        assertThat(action(1, granted = true)).isEqualTo(CloudAction.RANDOM_LOTTIE)
    }

    @Test
    fun solved_collapsesToLegacyBehaviour() {
        assertThat(action(0, solved = true, granted = false))
            .isEqualTo(CloudAction.LOCATION_PERMISSION)
        assertThat(action(0, solved = true, granted = true))
            .isEqualTo(CloudAction.RANDOM_LOTTIE)
        assertThat(action(7, solved = true, granted = true))
            .isEqualTo(CloudAction.RANDOM_LOTTIE)
    }

    @Test
    fun onboardingIncomplete_ignoresClickAndDoesNotCount() {
        assertThat(action(0, onboarded = false)).isEqualTo(CloudAction.IGNORED)
        assertThat(SwiftieEggController.shouldCountCloudClick(false)).isFalse()
        assertThat(SwiftieEggController.shouldCountCloudClick(true)).isTrue()
    }

    @Test
    fun keyword_matchesCaseAndWhitespaceInsensitively() {
        assertThat(SwiftieEggController.matchesKeyword("  Taylor Swift ")).isTrue()
        assertThat(SwiftieEggController.matchesKeyword("TAYLORSWIFT")).isTrue()
        assertThat(SwiftieEggController.matchesKeyword("霉霉")).isTrue()
        assertThat(SwiftieEggController.matchesKeyword("泰勒斯威夫特")).isTrue()
        assertThat(SwiftieEggController.matchesKeyword("lover")).isTrue()
        assertThat(SwiftieEggController.matchesKeyword("swiftie")).isTrue()
    }

    @Test
    fun keyword_neverMatchesBareThirteenOrPartialText() {
        // 13 太短，会误伤真实搜索需求，明确不在清单里
        assertThat(SwiftieEggController.matchesKeyword("13")).isFalse()
        assertThat(SwiftieEggController.matchesKeyword("taylor swift 1989")).isFalse()
        assertThat(SwiftieEggController.matchesKeyword("")).isFalse()
        assertThat(SwiftieEggController.matchesKeyword("loverboy")).isFalse()
    }

    @Test
    fun answer_onlyThirteenIsCorrect() {
        assertThat(SwiftieEggController.isCorrect("13")).isTrue()
        assertThat(SwiftieEggController.isCorrect("12")).isFalse()
        assertThat(SwiftieEggController.isCorrect("")).isFalse()
        assertThat(SwiftieEggController.isCorrect("1")).isFalse()
        assertThat(SwiftieEggController.isCorrect("013")).isFalse()
    }

    // ───────────── 抖动资格：spec 约束 2 / §4 ─────────────

    @Test
    fun cloudNudge_lockedWithBudgetLeft_isTheOnlyGreenCase() {
        assertThat(SwiftieEggController.shouldShowCloudNudge(false, false, 0)).isTrue()
    }

    @Test
    fun cloudNudge_legacyMigratedUser_neverNudges() {
        // 存量迁移：unlocked=true 但 quizSolved=false，还留着一次解题机会，
        // 可他早就在用星云背景。给他抖等于白剧透。
        assertThat(SwiftieEggController.shouldShowCloudNudge(true, false, 0)).isFalse()
    }

    @Test
    fun cloudNudge_solvedEitherWay_neverNudges() {
        // quizSolved=true 时 resolveCloudAction 已经不再发题面，抖了是骗人
        assertThat(SwiftieEggController.shouldShowCloudNudge(false, true, 0)).isFalse()
        assertThat(SwiftieEggController.shouldShowCloudNudge(true, true, 0)).isFalse()
    }

    @Test
    fun cloudNudge_budgetIsThreeRoundsInclusive() {
        assertThat(SwiftieEggController.shouldShowCloudNudge(false, false, 2)).isTrue()
        assertThat(SwiftieEggController.shouldShowCloudNudge(false, false, 3)).isFalse()
        assertThat(SwiftieEggController.shouldShowCloudNudge(false, false, 99)).isFalse()
    }

    // ───────────── 抖动波形 ─────────────

    @Test
    fun cloudNudgeDegrees_startsAndEndsAtZero() {
        assertThat(SwiftieEggController.cloudNudgeDegrees(0f)).isWithin(1e-6f).of(0f)
        assertThat(SwiftieEggController.cloudNudgeDegrees(1f)).isWithin(1e-4f).of(0f)
    }

    @Test
    fun cloudNudgeDegrees_twoEqualOppositeSwings() {
        // 反对称：前后两摆等幅反向，否则看着像只往一边歪
        for (offset in listOf(0.1f, 0.2f, 0.3f, 0.4f)) {
            assertThat(SwiftieEggController.cloudNudgeDegrees(offset))
                .isWithin(1e-4f).of(-SwiftieEggController.cloudNudgeDegrees(1f - offset))
        }
        assertThat(SwiftieEggController.cloudNudgeDegrees(0.5f)).isWithin(1e-4f).of(0f)
    }

    @Test
    fun cloudNudgeDegrees_peakIsExactlyTheConstant() {
        var peak = 0f
        for (i in 0..1000) {
            val v = abs(SwiftieEggController.cloudNudgeDegrees(i / 1000f))
            assertThat(v).isAtMost(SwiftieEggController.NUDGE_MAX_DEGREES + 1e-3f)
            if (v > peak) peak = v
        }
        // 归一化必须让常量就是真实峰值，否则 NUDGE_MAX_DEGREES 是个说谎的名字
        assertThat(peak).isWithin(2e-3f).of(SwiftieEggController.NUDGE_MAX_DEGREES)
    }

    @Test
    fun cloudNudgeDegrees_clampsOutOfRangePhase() {
        assertThat(SwiftieEggController.cloudNudgeDegrees(-3f)).isWithin(1e-6f).of(0f)
        assertThat(SwiftieEggController.cloudNudgeDegrees(7f)).isWithin(1e-4f).of(0f)
    }

    @Test
    fun revealDecision_onlyCloudEntryWithCoordinatesReveals() {
        // spec 要求「其他入口（关键词、关于页长按）保持淡入」。判据是纯函数，
        // 所以这条断言真的覆盖得住，而不是只能去 instrumentation 里猜转场类型。
        val r = SwiftieEggController::shouldRevealFromCloud
        assertThat(r(true, true, false)).isTrue()
        assertThat(r(true, false, false)).isFalse()   // 拿不到坐标：退回淡入
        assertThat(r(false, true, false)).isFalse()   // 关键词 / 关于页入口
        assertThat(r(true, true, true)).isFalse()     // 减少动效
        assertThat(r(false, false, true)).isFalse()   // 三个都不成立
    }

    // ───────────── 揭示几何：spec §B 铺满判据 ─────────────

    @Test
    fun revealBaseRadius_atOneCoversCornersPlusFeather() {
        val maxCorner = 1400f
        val feather = 28f
        val base = SwiftieEggController.revealBaseRadius(maxCorner, feather, eased = 1f)
        assertThat(base).isWithin(1e-4f).of(maxCorner + feather)
        // 真正的判据是外沿带宽，用更严的 maxCorner + W 当增长终点
        assertThat(base).isAtLeast(maxCorner + 0.35f * feather)
    }

    @Test
    fun revealBaseRadius_startsAtNothingAndGrowsMonotonically() {
        val at0 = SwiftieEggController.revealBaseRadius(1400f, 28f, 0f)
        val at1 = SwiftieEggController.revealBaseRadius(1400f, 28f, 1f)
        assertThat(at0).isWithin(1e-6f).of(0f)
        var previous = -1f
        for (i in 0..50) {
            val v = SwiftieEggController.revealBaseRadius(1400f, 28f, i / 50f)
            assertThat(v).isAtLeast(previous)
            previous = v
        }
        assertThat(previous).isWithin(1e-4f).of(at1)
    }

    @Test
    fun revealWiggle_diesOutSoTheLastFrameIsAPerfectCircle() {
        assertThat(SwiftieEggController.revealWiggle(1f)).isWithin(1e-6f).of(0f)
        assertThat(SwiftieEggController.revealWiggle(0f))
            .isWithin(1e-6f).of(SwiftieEggController.REVEAL_WIGGLE_MAX)
    }

    @Test
    fun revealLobeSum_isBoundedAndNeverFlipsRadiusNegative() {
        var minFactor = 1f
        var i = 0
        while (i <= 720) {
            val theta = i * 2f * PI.toFloat() / 720f
            val lobe = SwiftieEggController.revealLobeSum(theta)
            assertThat(abs(lobe)).isAtMost(0.70f)
            // 星形判据：1 + A·S 必须恒正，否则 r(θ) 自交，遮罩会出尖角
            val factor = 1f + SwiftieEggController.REVEAL_WIGGLE_MAX * lobe
            if (factor < minFactor) minFactor = factor
            i++
        }
        assertThat(minFactor).isGreaterThan(0.7f)
    }

    @Test
    fun revealLobeSum_isDeterministicAcrossRepeatedEvaluation() {
        // 形状必须是常量表的纯函数。掺了随机数（哪怕只是 HashMap 顺序之类）的话，
        // 每帧算出不同的 r(θ)，遮罩会像抖屏而不是扩张 —— 而且单测抓不住真机上的抖。
        for (i in 0..200 step 7) {
            val theta = i * PI.toFloat() / 100f
            assertThat(SwiftieEggController.revealLobeSum(theta))
                .isEqualTo(SwiftieEggController.revealLobeSum(theta))
        }
    }

    @Test
    fun revealLobeSum_polynomialFormMatchesTrigonometricForm() {
        // AGSL 侧只写得出多项式形式（本仓库无可依证实证的 atan/sin 内建函数），
        // Kotlin 侧读起来清楚得多。两种写法等价必须由这条钉住 ——
        // 倍角系数或 cosφ/sinφ 的符号写错，这里直接红，而不是变成真机上难查的形状不对。
        var i = 0
        while (i <= 1440) {
            val theta = i * 2f * PI.toFloat() / 1440f
            val expected = SwiftieEggController.revealLobeSum(theta)
            val actual = SwiftieEggController.revealLobeSumFromCosSin(cos(theta), sin(theta))
            assertThat(actual).isWithin(2e-5f).of(expected)
            i++
        }
    }

    @Test
    fun revealFeatherPx_cappedByDpAndShrinksAtTheStart() {
        val cap = 28f
        // eased=1 时 0.18·maxCorner 远大于 28dp，取到上限
        assertThat(SwiftieEggController.revealFeatherPx(1400f, 1f, cap)).isWithin(1e-4f).of(cap)
        // edge 起手接近 0 时带宽必须一起缩掉，否则第一帧是「整屏蒙了一层灰」
        assertThat(SwiftieEggController.revealFeatherPx(1400f, 0.02f, cap)).isLessThan(cap)
        assertThat(SwiftieEggController.revealFeatherPx(1400f, 0f, cap)).isWithin(1e-6f).of(0f)
    }
}
