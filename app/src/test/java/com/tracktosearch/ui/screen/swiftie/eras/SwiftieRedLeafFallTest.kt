package com.tracktosearch.ui.screen.swiftie.eras

import androidx.compose.ui.graphics.Color
import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import com.tracktosearch.ui.screen.swiftie.SwiftieTimeline
import org.junit.Test
import kotlin.math.roundToInt

/**
 * Red 的枫叶剧本。
 *
 * 这一张的叶分两层画：还没松手的在页面背景（L1，卡片之下），已经松手的在落叶层
 * （L3，卡片之上）。「归谁画」这件事在代码里看不见 —— 改错一个比较符号都不会
 * 编译失败，只会在交接那一帧留下一个色斑或一次闪烁。所以把交接的那几毫秒
 * 逐个钉死：松手前一帧归 L1、松手那一帧归 L3、落完前一帧还在、落完那一帧才没。
 *
 * 姿态本身不测：两层共用 `drawRedBranchLeaf` 那一个入口，静态姿态就是飘落分支
 * 在 u=0 处的取值，想算出两个结果来都难。
 */
class SwiftieRedLeafFallTest {

    /** Red 一整段的时长（含它自己收尾那 500ms 淡出）。 */
    private val redSegmentMs = SwiftieTimeline.cardDurationMs(
        SwiftieErasData.RED_INDEX,
        SwiftieTimeline.ERA_TRACK_COUNTS[SwiftieErasData.RED_INDEX]
    )

    @Test
    fun redIndexReallyIsTheRedCard() {
        // 剧本、挂载门控与背景分发都按 `RED_INDEX` 认人。账本一旦重排，落叶会静默地
        // 挂到别的时代上，而那张卡片自己看着一切正常
        assertThat(SwiftieErasData.ALL[SwiftieErasData.RED_INDEX].name).isEqualTo("Red")
        assertThat(SwiftieErasData.STAGE[SwiftieErasData.RED_INDEX].backdrop)
            .isEqualTo(SwiftieEraBackdrop.KNIT_AUTUMN)
        val start = SwiftieTimeline.eraStartMs(SwiftieErasData.RED_INDEX)
        assertThat(SwiftieTimeline.eraIndexAt(start)).isEqualTo(SwiftieErasData.RED_INDEX)
        assertThat(redLeafFallActiveAt(start)).isTrue()
        assertThat(redLeafFallActiveAt(start - 1L)).isFalse()
        assertThat(redLeafFallActiveAt(start + redSegmentMs)).isFalse()
    }

    @Test
    fun branchIsFullUntilTheFirstLeafLetsGo() {
        // 需求方要的「一开始都长在树枝上」：换张淡入那 500ms（本段时钟是负数）
        // 以及第一片松手之前，五片必须全在枝上
        RedBranchLeaves.forEach { assertThat(it.attachedAt(-1L)).isTrue() }
        val firstRelease = RedBranchLeaves.mapNotNull { it.fall?.releaseAtMs }.min()
        RedBranchLeaves.forEach { assertThat(it.attachedAt(firstRelease - 1L)).isTrue() }
        // 交接边界必须正好压在松手那一毫秒上：早一帧晚一帧，两层要么同时画这一片
        // （叠出一个深一档的色斑），要么都漏掉它（凭空少一帧）
        RedBranchLeaves.forEach { leaf ->
            val fall = leaf.fall ?: return@forEach
            assertThat(leaf.attachedAt(fall.releaseAtMs - 1L)).isTrue()
            assertThat(leaf.attachedAt(fall.releaseAtMs)).isFalse()
            // 刚松手绝不能算「已落出画面」，否则它从两层同时消失
            assertThat(leaf.fallenOffAt(fall.releaseAtMs)).isFalse()
            assertThat(leaf.fallenOffAt(fall.releaseAtMs + fall.fallMs - 1L)).isFalse()
            assertThat(leaf.fallenOffAt(fall.releaseAtMs + fall.fallMs)).isTrue()
        }
        // 段末还剩两片在枝上：深秋的树不落空
        assertThat(RedBranchLeaves.count { it.attachedAt(redSegmentMs) }).isAtLeast(2)
    }

    @Test
    fun branchKeepsLeavesAndReleasesAreStaggered() {
        val released = RedBranchLeaves.mapNotNull { it.fall }
        // 需求是「树枝上的两三片」松手
        assertThat(released).hasSize(3)
        // 深秋的树不会一夜落空：至少两片留到段末，否则这一张收尾是两根光枝
        assertThat(RedBranchLeaves.size - released.size).isAtLeast(2)
        // 松手时刻错开：挤在一起读作同时剪断，不像风把叶吹落
        val times = released.map { it.releaseAtMs }.sorted()
        val smallestGap = times.zipWithNext().minOf { (earlier, later) -> later - earlier }
        assertThat(smallestGap).isAtLeast(600L)
    }

    @Test
    fun everyFallCompletesBeforeTheCrossfade() {
        RedBranchLeaves.mapNotNull { it.fall }.forEach { fall ->
            assertThat(fall.releaseAtMs).isAtLeast(0L)
            // 落点必须在本段内收干净（还要早于换张淡出那 500ms）。跨过边界的话，
            // 一片叶会在半空中随整层卸载一起消失，看着像卡了一下
            assertThat(fall.releaseAtMs + fall.fallMs).isAtMost(redSegmentMs - 500L)
        }
    }

    @Test
    fun eraColorStaysOnTheSubjectAndHuesStayAutumnWarm() {
        // #EB3440 是这一张的识别色（`backdropColors` 的中档）。它从五片主体上消失的话，
        // 一张满屏橘黄落叶就不再是 Red 了
        assertThat(RedBranchLeaves.map { it.hue.fill })
            .contains(SwiftieErasData.STAGE[SwiftieErasData.RED_INDEX].backdropColors[1])
        // 真实枫叶的秋色可以一路落到褐，但不能进绿或柠檬黄 —— 那两色一进来这张就换专辑了。
        // 判据取「红分量必须压过绿分量」：暖色一律满足，绿与柠檬黄都不满足
        val outOfRange = MapleHues.flatMap { listOf(it.fill, it.back) }
            .filter { it.red <= it.green }
            .map(::hex)
        assertThat(outOfRange).isEmpty()
    }

    @Test
    fun branchLeavesHangOnTheBoughAndClearTheCardTop() {
        // 五片沿枝一字排开，参数必须单调且都落在枝身上（0..1），有人滑到梢头外就悬空了
        val ts = RedBranchLeaves.map { it.boughT }
        ts.forEach {
            assertThat(it).isAtLeast(0f)
            assertThat(it).isAtMost(1f)
        }
        assertThat(ts.zipWithNext().all { (earlier, later) -> later > earlier }).isTrue()
        // 叶柄末端钉在枝上，叶身就从那一点朝下垂，垂到 MAPLE_SPAN × half。
        // 折算到屏高要按**参考机**的短边/长边比（1440×3200，与 benchmark 那台同一台）：
        // 锚点纵向是屏高分位，而 half 是 minDimension（这里就是屏宽）分位，两个单位不换算
        // 就会把「刚好挂在卡片顶上」读成「被卡片吞掉」—— 2026-09-20 那轮就是这么翻的车
        RedBranchLeaves.forEach { leaf ->
            val bottom = knitBoughY(leaf.boughT) + MAPLE_SPAN * leaf.half * REFERENCE_MIN_DIM_OVER_HEIGHT
            assertWithMessage("t=${leaf.boughT} 那片叶下探到 $bottom").that(bottom).isAtMost(0.195f)
        }
    }

    private companion object {
        /** 参考机（1440×3200）的短边 ÷ 长边，把 minDimension 分位折算成屏高分位。 */
        const val REFERENCE_MIN_DIM_OVER_HEIGHT = 1440f / 3200f
    }

    private fun hex(color: Color): String = "#%02X%02X%02X".format(
        (color.red * 255f).roundToInt(),
        (color.green * 255f).roundToInt(),
        (color.blue * 255f).roundToInt()
    )
}
