package com.tracktosearch.ui.haptic

import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import org.junit.Test

/**
 * [HapticSemantic.boosted] 的判定单测：「增强」档的全部行为就是这一张映射表，
 * 改错了不崩也不报错，只会让某个交互悄悄震成另一档 —— 或者更糟，把滑块拖动的
 * FREQUENT_TICK 顶成实心点击，一次拖动累出几十记重震。
 *
 * 期望值刻意照设计文档「语义词表」逐条重抄，没有写成
 * `entries.associateWith { it.boosted() }` 那种从被测代码推导的形式：
 * 期望值与实现同源时，实现被改坏期望值跟着一起变，这个测试永远是绿的。
 *
 * 除逐条比对，还钉住三条下游会依赖的不变式：映射无环（反复调用必收敛）、
 * 不动点恰好是那五个顶档、最长链三步。被测代码零 android 依赖，纯 JVM 跑，不用 Robolectric。
 */
class HapticSemanticBoostTest {

    /**
     * 「增强」档的全部 13 条映射。改这张表就是改产品行为，动手前先回看设计文档。
     */
    private val expectedBoost: Map<HapticSemantic, HapticSemantic> = mapOf(
        HapticSemantic.TAP to HapticSemantic.TAP,
        HapticSemantic.LIGHT_TAP to HapticSemantic.TAP,
        HapticSemantic.SEGMENT_TICK to HapticSemantic.LIGHT_TAP,
        HapticSemantic.FREQUENT_TICK to HapticSemantic.SEGMENT_TICK,
        HapticSemantic.TOGGLE_ON to HapticSemantic.TAP,
        HapticSemantic.TOGGLE_OFF to HapticSemantic.TOGGLE_ON,
        HapticSemantic.CONFIRM to HapticSemantic.CONFIRM,
        HapticSemantic.REJECT to HapticSemantic.REJECT,
        HapticSemantic.DRAG_START to HapticSemantic.DRAG_START,
        HapticSemantic.THRESHOLD_ARMED to HapticSemantic.THRESHOLD_ARMED,
        HapticSemantic.GESTURE_END to HapticSemantic.TAP,
        HapticSemantic.SCROLL_EDGE to HapticSemantic.SEGMENT_TICK,
        HapticSemantic.POPUP_SHOW to HapticSemantic.LIGHT_TAP,
    )

    /** 五个顶档：各自在自己的含义上已到顶，是升级链的不动点。 */
    private val topTier: Set<HapticSemantic> = setOf(
        HapticSemantic.TAP,
        HapticSemantic.CONFIRM,
        HapticSemantic.REJECT,
        HapticSemantic.DRAG_START,
        HapticSemantic.THRESHOLD_ARMED,
    )

    @Test
    fun `13 条映射逐条对上语义词表`() {
        val actual = HapticSemantic.entries.associateWith { it.boosted() }
        assertThat(actual).containsExactlyEntriesIn(expectedBoost)
    }

    @Test
    fun `语义表恰好 13 项，新增语义必须回来补映射`() {
        assertThat(HapticSemantic.entries).hasSize(EXPECTED_SEMANTIC_COUNT)
        // 穷举 when 会在加语义时编译期报错，但那只逼人补一条分支；这条逼人回来核对整张表
        assertThat(expectedBoost.keys).containsExactlyElementsIn(HapticSemantic.entries)
    }

    @Test
    fun `顶档语义的 boosted 返回自身`() {
        topTier.forEach { semantic ->
            assertWithMessage("$semantic 是顶档，boosted() 必须返回自身")
                .that(semantic.boosted())
                .isEqualTo(semantic)
        }
    }

    @Test
    fun `不动点恰好是那五个顶档`() {
        // 非顶档语义若被改成返回自身，「增强」档对它就成了空操作，用户选了增强却没变化
        val fixedPoints = HapticSemantic.entries.filter { it.boosted() == it }
        assertThat(fixedPoints).containsExactlyElementsIn(topTier)
    }

    @Test
    fun `反复 boosted 必收敛到顶档，映射不成环`() {
        HapticSemantic.entries.forEach { start ->
            val chain = boostChain(start)
            assertWithMessage("$start 的升级链 $chain 走到 $MAX_CHAIN_STEPS 步上限仍没停，映射成环")
                .that(chain.size)
                .isLessThan(MAX_CHAIN_STEPS)
            assertWithMessage("$start 的升级链 $chain 重复经过同一个语义，映射互指成环")
                .that(chain)
                .containsNoDuplicates()
            val landed = chain.lastOrNull() ?: start
            assertWithMessage("$start 沿 boosted() 收敛到 $landed，但 $landed 不在顶档集合里")
                .that(topTier)
                .contains(landed)
        }
    }

    @Test
    fun `最长升级链三步，走满三步的恰好是两个连发语义`() {
        val toTopThroughSegmentTick = listOf(
            HapticSemantic.SEGMENT_TICK,
            HapticSemantic.LIGHT_TAP,
            HapticSemantic.TAP,
        )
        assertThat(boostChain(HapticSemantic.FREQUENT_TICK))
            .containsExactlyElementsIn(toTopThroughSegmentTick).inOrder()
        // SCROLL_EDGE 同样落在 SEGMENT_TICK 上，链长与 FREQUENT_TICK 一样是三步。
        // 交接说明里「最长链 3 步：FREQUENT_TICK …，其余 ≤2 步」那句漏了它，这里按实际映射钉住
        assertThat(boostChain(HapticSemantic.SCROLL_EDGE))
            .containsExactlyElementsIn(toTopThroughSegmentTick).inOrder()
        HapticSemantic.entries.forEach { start ->
            val chain = boostChain(start)
            assertWithMessage("$start 的升级链 $chain 比文档钉的最长链还长")
                .that(chain.size)
                .isAtMost(LONGEST_CHAIN_STEPS)
        }
        // 只有这两个满格，其余都短：这条挡住「给别的语义悄悄接长一截升级链」
        val longest = HapticSemantic.entries.filter { boostChain(it).size == LONGEST_CHAIN_STEPS }
        assertThat(longest).containsExactly(HapticSemantic.FREQUENT_TICK, HapticSemantic.SCROLL_EDGE)
    }

    /**
     * 从 [start] 出发反复调 boosted()，直到落在不动点上，返回途经的语义（不含 [start] 自身）。
     *
     * [MAX_CHAIN_STEPS] 是死循环防线而非断言：映射若被改成互指，没这个上限这里会把整个测试
     * 进程挂住 —— 测试 worker 被拖死时后面的测试类会静默漏跑，比一条红更难查。
     * 超限就把截断的链原样交回去，让调用方的断言判红。
     */
    private fun boostChain(start: HapticSemantic): List<HapticSemantic> {
        val chain = mutableListOf<HapticSemantic>()
        var current = start
        while (current.boosted() != current && chain.size < MAX_CHAIN_STEPS) {
            current = current.boosted()
            chain += current
        }
        return chain
    }

    private companion object {
        /** 语义总数，与设计文档「语义词表」一致。 */
        const val EXPECTED_SEMANTIC_COUNT = 13

        /** 收敛步数上限：不重复经过同一语义时，链最长也就走遍全表。 */
        const val MAX_CHAIN_STEPS = EXPECTED_SEMANTIC_COUNT

        /** 最长链：FREQUENT_TICK 与 SCROLL_EDGE 都要三步才到 TAP。 */
        const val LONGEST_CHAIN_STEPS = 3
    }
}
