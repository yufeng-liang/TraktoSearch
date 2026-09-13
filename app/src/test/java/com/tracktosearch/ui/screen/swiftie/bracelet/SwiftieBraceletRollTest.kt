package com.tracktosearch.ui.screen.swiftie.bracelet

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * 手链入场的两条纯函数：一颗珠子此刻滚到哪（[braceletRollProgress]）、绳子跟到哪
 * （[braceletCordT]）。
 *
 * 几何（珠子停在弧线哪一点）在 `planStrand` 里，画不出来也测不到；可测的是**时间**：
 * 谁先出发、谁先停、三条怎么错开、停住那一刻是不是真的全停了。这些数字一错，
 * 屏幕上就是「珠子从半路冒出来」或者「绳子追不上珠子」—— 那两种都只能靠眼睛发现，
 * 所以拿断言钉住。
 */
class SwiftieBraceletRollTest {

    /** 三条链的珠数：后两条固定 11 颗，昵称那条 10 或 11（见 `nicknameStrandBeadCount`）。 */
    private val strandBeadCounts = listOf(11, 11)

    /** 一条链里相邻两颗的出发间隔。 */
    private fun releaseMs(beadCount: Int) = BRACELET_ROLL_SPAN_MS.toFloat() / beadCount

    @Test
    fun aBeadIsInvisibleUntilItsOwnLaunchMoment() {
        val count = 11
        // 第一颗在 0ms 出发：那一刻它还没进画面，下一毫秒才出现
        assertThat(braceletRollProgress(0L, 0, 0, count)).isNull()
        assertThat(braceletRollProgress(1L, 0, 0, count)).isNotNull()
        // 第二颗要等一个发车间隔（100ms）
        val second = releaseMs(count).toLong()
        assertThat(braceletRollProgress(second - 1L, 0, 1, count)).isNull()
        assertThat(braceletRollProgress(second + 1L, 0, 1, count)).isNotNull()
    }

    @Test
    fun theStrandsLeaveInTheirOwnOrder() {
        // 后一条要比前一条晚 BRACELET_STAGGER_MS 出发，同序号的那颗才对得上
        (0 until strandBeadCounts.size).zipWithNext { back, front ->
            for (index in 0 until strandBeadCounts[back]) {
                val backProgress = braceletRollProgress(500L, back, index, strandBeadCounts[back])
                val frontProgress =
                    braceletRollProgress(500L + BRACELET_STAGGER_MS, front, index, strandBeadCounts[front])
                assertThat(frontProgress).isEqualTo(backProgress)
            }
        }
    }

    @Test
    fun progressNeverGoesBackwardsOrPastOne() {
        val count = 11
        val launch = 0L
        val travel = (releaseMs(count) * 3.2f).toLong()
        var previous = 0f
        // 从出发前一路采到走完（每 20ms 一格）
        for (offset in 0L..(travel + 100L) step 20L) {
            val progress = braceletRollProgress(launch + offset, 0, 0, count)
            if (progress == null) continue
            assertThat(progress).isAtLeast(previous)
            assertThat(progress).isAtMost(1f)
            previous = progress
        }
        // 走完之后恒为 1f，不会继续涨
        assertThat(braceletRollProgress(travel + 5_000L, 0, 0, count)).isEqualTo(1f)
    }

    @Test
    fun threeStrandsOverlapInsteadOfQueueingUp() {
        // 出发间隔 100ms、单颗走 320ms ⇒ 同时有三颗在路上。这条钉住的是「流速」：
        // 一旦有人把 RELEASE_RATIO 收到 1，同一时刻就只剩一颗在滚，整条会读成接力
        val count = 11
        val inFlight = (0 until count).count { index ->
            val progress = braceletRollProgress(250L, 0, index, count)
            progress != null && progress < 1f
        }
        assertThat(inFlight).isAtLeast(3)
    }

    @Test
    fun theSlowestStrandIsTheEvenNicknameOneNotTheLongerOne() {
        // 珠数少 ⇒ 间隔大 ⇒ 单颗走得更久。10 颗那条比 11 颗的更慢，所以结算时刻要按它取
        assertThat(braceletStrandSettleMs(10)).isGreaterThan(braceletStrandSettleMs(11))
        assertThat(braceletStrandSettleMs(10)).isEqualTo(1_342L)
        assertThat(braceletStrandSettleMs(11)).isEqualTo(1_320L)
        assertThat(braceletStrandSettleMs(0)).isEqualTo(0L)
    }

    @Test
    fun afterTheSettledConstantEveryBeadOfEveryStrandIsStopped() {
        // 末条（昵称）最后出发，所以结算时刻 = 两档错开 + 最慢那条的时长
        assertThat(BRACELET_SETTLED_MS)
            .isEqualTo(2 * BRACELET_STAGGER_MS + braceletStrandSettleMs(10))
        listOf(10, 11).forEach { count ->
            for (strandIndex in 0..2) {
                for (beadIndex in 0 until count) {
                    assertThat(braceletRollProgress(BRACELET_SETTLED_MS, strandIndex, beadIndex, count))
                        .isEqualTo(1f)
                }
            }
        }
        // 而且它确实是最慢那条的收尾：早 100ms 时末颗珠子还在路上。
        // 不拿「早 1ms」去试 —— 缓动收尾是 1−(1−t)³，最后那点距离小到 float 精度里就是 1f，
        // 那 1ms 的差根本表达不出来
        val lastBead = 9
        assertThat(braceletRollProgress(BRACELET_SETTLED_MS - 100L, 2, lastBead, 10))
            .isLessThan(1f)
    }

    @Test
    fun theCordIsInvisibleUntilTheFirstBeadLeavesThenFollowsTheLeadingOne() {
        val count = 11
        // 一颗都没出发：绳子起点还没进画面，画出来只会是一个孤零零的圆点
        assertThat(braceletCordT(listOf(null, null, null), count)).isEqualTo(0f)
        // 头一颗刚走一点：绳子跟着探头，且比珠心多探半颗
        val halfPitch = 0.5f / count
        assertThat(braceletCordT(listOf(0f, null), count)).isEqualTo(halfPitch)
        assertThat(braceletCordT(listOf(0.3f, null), count)).isWithin(1e-6f).of(0.3f + halfPitch)
        // 取的是**最前面**那颗，不是最后出发那颗
        assertThat(braceletCordT(listOf(0.2f, 0.7f, null), count)).isWithin(1e-6f).of(0.7f + halfPitch)
    }

    @Test
    fun theCordReachesTheFarEndExactlyWhenTheLastBeadArrives() {
        val count = 11
        val lastBeadT = (count - 0.5f) / count
        // 末颗珠子到位时，绳子正好探到弧线尽头 —— 末尾那一小截藏在珠子里，不会缺一角
        assertThat(braceletCordT(List(count) { lastBeadT }, count)).isEqualTo(1f)
        // 再多探也封在 1f：子曲线跑到弧线之外就是画到链子外面去了
        assertThat(braceletCordT(List(count) { 1f }, count)).isEqualTo(1f)
    }

    @Test
    fun everyStrandOfTheRealSetIsCoveredByTheSettledConstant() {
        // 没有昵称时只有两条：中间那条（11 颗）走在最后
        assertThat(BRACELET_STAGGER_MS + braceletStrandSettleMs(11))
            .isAtMost(BRACELET_SETTLED_MS)
        // 有昵称时它排在最后，珠数按字数在 10（偶数个字）与 11（奇数个字）之间 ——
        // 两条都是最后出发，10 颗那条还更慢，是这里的最坏情况
        (1..NICKNAME_MAX_BEADS).forEach { tokens ->
            val count = nicknameStrandBeadCount(tokens)
            assertThat(2 * BRACELET_STAGGER_MS + braceletStrandSettleMs(count))
                .isAtMost(BRACELET_SETTLED_MS)
        }
        // 昵称是偶数个字时它正好是结算时刻：再早一毫秒它就没停稳
        assertThat(2 * BRACELET_STAGGER_MS + braceletStrandSettleMs(nicknameStrandBeadCount(2)))
            .isEqualTo(BRACELET_SETTLED_MS)
    }
}
