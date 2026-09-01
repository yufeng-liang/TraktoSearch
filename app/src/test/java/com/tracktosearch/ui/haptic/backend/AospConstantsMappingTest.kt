package com.tracktosearch.ui.haptic.backend

import android.view.HapticFeedbackConstants
import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import com.tracktosearch.ui.haptic.HapticSemantic
import org.junit.Test

/**
 * [aospConstantFor] 的语义到常量映射单测。
 *
 * tier 0 是整条降级链的地板，它选错常量不崩也不报错，只会让某个交互在某些 Android 版本上
 * 震成另一档；更糟的一种是在老设备上返回一个该版本 framework 还认不出的常量值 ——
 * `performHapticFeedback` 静默返回 false，整个交互一点反馈都没有，日志里也什么都没有。
 * 九个语义带版本门控，门控写成大于而不是大于等于、或者退化目标抄错一格，都落在这一类静默故障里。
 * 这张表因此逐格钉死，而不是只抽查几个。
 *
 * 期望值刻意用写死的整型字面量（见私有 companion 里那批常量），不写成 `HapticFeedbackConstants.X`：
 * 一是让期望值与被测代码不同源，被测代码改坏时期望值不会跟着一起变；
 * 二是这样整份测试一个 android 类都不加载。字面量自身抄错的风险由「常量取值与 SDK 一致」
 * 那条测试兜住 —— 它是全文唯一引用 SDK 常量的地方，而 `static final int` 会在编译期内联成
 * 字面量，所以那条也仍是纯 JVM，不必上 Robolectric。
 *
 * 全部取值对着 `platforms/android-37.0` 核过两处：`javap -constants` 取常量值，
 * `data/api-versions.xml` 取引入版本。
 *
 * **一处与设计文档不符，已知且刻意按平台事实钉住**：文档「语义词表」把 `GESTURE_END` 标成
 * API 34，而 `api-versions.xml` 里它是 `since="30"`，与 `CONFIRM`、`REJECT` 同批引入。
 * 本测试按 30 断言，与实现一致。若 main 最终裁定严格照文档走（把实现里那一处
 * `Build.VERSION_CODES.R` 改成 `UPSIDE_DOWN_CAKE`），本文件里 GESTURE_END 的三处期望值
 * 与 [gates] 里的门控版本要一起改，改完这张表仍应逐格自洽。
 */
class AospConstantsMappingTest {

    /**
     * API 26（项目 minSdk）上的全表。两处门控都没到，13 个语义必须全部落在
     * minSdk 之前就存在的三个老常量上。
     */
    private val expectedAtMinSdk: Map<HapticSemantic, Int> = mapOf(
        HapticSemantic.TAP to VIRTUAL_KEY,
        HapticSemantic.LIGHT_TAP to CLOCK_TICK,
        HapticSemantic.SEGMENT_TICK to CLOCK_TICK,
        HapticSemantic.FREQUENT_TICK to CLOCK_TICK,
        HapticSemantic.TOGGLE_ON to VIRTUAL_KEY,
        HapticSemantic.TOGGLE_OFF to CLOCK_TICK,
        HapticSemantic.CONFIRM to VIRTUAL_KEY,
        HapticSemantic.REJECT to LONG_PRESS,
        HapticSemantic.DRAG_START to LONG_PRESS,
        HapticSemantic.THRESHOLD_ARMED to VIRTUAL_KEY,
        HapticSemantic.GESTURE_END to CLOCK_TICK,
        HapticSemantic.SCROLL_EDGE to CLOCK_TICK,
        HapticSemantic.POPUP_SHOW to CLOCK_TICK,
    )

    /**
     * API 30 上的全表。`CONFIRM`、`REJECT`、`GESTURE_END` 三个用上新常量，
     * API 34 引入的那六个仍停在各自的退化目标上。
     */
    private val expectedAtR: Map<HapticSemantic, Int> = mapOf(
        HapticSemantic.TAP to VIRTUAL_KEY,
        HapticSemantic.LIGHT_TAP to CLOCK_TICK,
        HapticSemantic.SEGMENT_TICK to CLOCK_TICK,
        HapticSemantic.FREQUENT_TICK to CLOCK_TICK,
        HapticSemantic.TOGGLE_ON to VIRTUAL_KEY,
        HapticSemantic.TOGGLE_OFF to CLOCK_TICK,
        HapticSemantic.CONFIRM to CONFIRM,
        HapticSemantic.REJECT to REJECT,
        HapticSemantic.DRAG_START to LONG_PRESS,
        HapticSemantic.THRESHOLD_ARMED to VIRTUAL_KEY,
        HapticSemantic.GESTURE_END to GESTURE_END,
        HapticSemantic.SCROLL_EDGE to CLOCK_TICK,
        HapticSemantic.POPUP_SHOW to CLOCK_TICK,
    )

    /**
     * API 35 上的全表。两处门控都过了，九个门控语义全部用上语义正确的常量；
     * API 35 自身没有引入新的触感常量，所以这一档与 API 34 完全一致。
     */
    private val expectedAtVanillaIceCream: Map<HapticSemantic, Int> = mapOf(
        HapticSemantic.TAP to VIRTUAL_KEY,
        HapticSemantic.LIGHT_TAP to CLOCK_TICK,
        HapticSemantic.SEGMENT_TICK to SEGMENT_TICK,
        HapticSemantic.FREQUENT_TICK to SEGMENT_FREQUENT_TICK,
        HapticSemantic.TOGGLE_ON to TOGGLE_ON,
        HapticSemantic.TOGGLE_OFF to TOGGLE_OFF,
        HapticSemantic.CONFIRM to CONFIRM,
        HapticSemantic.REJECT to REJECT,
        HapticSemantic.DRAG_START to DRAG_START,
        HapticSemantic.THRESHOLD_ARMED to GESTURE_THRESHOLD_ACTIVATE,
        HapticSemantic.GESTURE_END to GESTURE_END,
        HapticSemantic.SCROLL_EDGE to CLOCK_TICK,
        HapticSemantic.POPUP_SHOW to CLOCK_TICK,
    )

    /**
     * 一个带版本门控的语义：[sinceSdk] 起用 [gated]，以下用 [fallback]。
     *
     * [fallback] 一列就是设计文档「语义词表」最右列箭头右侧的退化目标，逐条重抄。
     */
    private data class Gate(val sinceSdk: Int, val gated: Int, val fallback: Int)

    /**
     * 九个带门控的语义。另外四个（[HapticSemantic.TAP]、[HapticSemantic.LIGHT_TAP]、
     * [HapticSemantic.SCROLL_EDGE]、[HapticSemantic.POPUP_SHOW]）无门控，见 [ungated]。
     */
    private val gates: Map<HapticSemantic, Gate> = mapOf(
        HapticSemantic.SEGMENT_TICK to Gate(SDK_UPSIDE_DOWN_CAKE, SEGMENT_TICK, CLOCK_TICK),
        HapticSemantic.FREQUENT_TICK to Gate(SDK_UPSIDE_DOWN_CAKE, SEGMENT_FREQUENT_TICK, CLOCK_TICK),
        HapticSemantic.TOGGLE_ON to Gate(SDK_UPSIDE_DOWN_CAKE, TOGGLE_ON, VIRTUAL_KEY),
        HapticSemantic.TOGGLE_OFF to Gate(SDK_UPSIDE_DOWN_CAKE, TOGGLE_OFF, CLOCK_TICK),
        HapticSemantic.DRAG_START to Gate(SDK_UPSIDE_DOWN_CAKE, DRAG_START, LONG_PRESS),
        HapticSemantic.THRESHOLD_ARMED to
            Gate(SDK_UPSIDE_DOWN_CAKE, GESTURE_THRESHOLD_ACTIVATE, VIRTUAL_KEY),
        HapticSemantic.CONFIRM to Gate(SDK_R, CONFIRM, VIRTUAL_KEY),
        HapticSemantic.REJECT to Gate(SDK_R, REJECT, LONG_PRESS),
        // 文档标 34，平台事实是 30，见类 KDoc
        HapticSemantic.GESTURE_END to Gate(SDK_R, GESTURE_END, CLOCK_TICK),
    )

    /**
     * 四个无门控语义与它们恒定的常量。
     *
     * 前两个的常量本身就低于 minSdk；后两个是 AOSP 根本没有对应常量，词表直接给了 `CLOCK_TICK`。
     */
    private val ungated: Map<HapticSemantic, Int> = mapOf(
        HapticSemantic.TAP to VIRTUAL_KEY,
        HapticSemantic.LIGHT_TAP to CLOCK_TICK,
        HapticSemantic.SCROLL_EDGE to CLOCK_TICK,
        HapticSemantic.POPUP_SHOW to CLOCK_TICK,
    )

    @Test
    fun `API 26 上 13 个语义全部落在 minSdk 之前就有的老常量上`() {
        val actual = HapticSemantic.entries.associateWith { aospConstantFor(it, SDK_MIN) }
        assertThat(actual).containsExactlyEntriesIn(expectedAtMinSdk)
        // 上一条已经逐格比过，这条单独把「新常量漏到老设备上」这类故障挑出来报，
        // 免得一堆格子里只有一个是这个原因时看不出性质
        assertWithMessage("API 26 上出现了 API 30 之后才引入的常量，这台设备的 framework 认不出它，静默不震")
            .that(actual.values)
            .containsNoneIn(constantsNewerThanMinSdk)
        // 只允许这三个：多一个就说明有语义悄悄换了退化目标
        assertThat(actual.values.toSet()).containsExactly(LONG_PRESS, VIRTUAL_KEY, CLOCK_TICK)
    }

    @Test
    fun `API 30 上 CONFIRM REJECT GESTURE_END 用上新常量，API 34 那批仍在退化目标上`() {
        val actual = HapticSemantic.entries.associateWith { aospConstantFor(it, SDK_R) }
        assertThat(actual).containsExactlyEntriesIn(expectedAtR)
    }

    @Test
    fun `API 35 上 API 34 引入的六个常量全部用上`() {
        val actual = HapticSemantic.entries.associateWith { aospConstantFor(it, SDK_VANILLA_ICE_CREAM) }
        assertThat(actual).containsExactlyEntriesIn(expectedAtVanillaIceCream)
        // 六个 API 34 常量各自只被一个语义用，谁被复用就说明有一格抄错成了邻居的常量
        val upsideDownCakeConstants = listOf(
            TOGGLE_ON,
            TOGGLE_OFF,
            GESTURE_THRESHOLD_ACTIVATE,
            DRAG_START,
            SEGMENT_TICK,
            SEGMENT_FREQUENT_TICK,
        )
        assertWithMessage("API 34 那六个常量在 API 35 上应各被一个语义用到，且互不重复")
            .that(actual.values.filter { it in upsideDownCakeConstants })
            .containsExactlyElementsIn(upsideDownCakeConstants)
    }

    @Test
    fun `九个门控语义的退化目标逐条对上语义词表最右列`() {
        gates.forEach { (semantic, gate) ->
            assertWithMessage("$semantic 在 API ${gate.sinceSdk - 1} 上的退化目标应是 ${gate.fallback}")
                .that(aospConstantFor(semantic, gate.sinceSdk - 1))
                .isEqualTo(gate.fallback)
            // 退化目标与门控常量相同就等于这处门控什么都没做，属死代码；
            // 也可能是有人把退化目标误抄成了新常量，那在老设备上就是静默不震
            assertWithMessage("$semantic 的退化目标与门控常量都是 ${gate.gated}，这处门控是死代码")
                .that(gate.fallback)
                .isNotEqualTo(gate.gated)
        }
    }

    @Test
    fun `门控在常量引入的那一档就要翻转，写成大于会整批晚一个版本`() {
        gates.forEach { (semantic, gate) ->
            assertWithMessage(
                "$semantic 在 API ${gate.sinceSdk} 上就该用 ${gate.gated}，" +
                    "还停在 ${gate.fallback} 说明门控写成了大于而不是大于等于，整批晚一个版本才生效",
            )
                .that(aospConstantFor(semantic, gate.sinceSdk))
                .isEqualTo(gate.gated)
        }
    }

    @Test
    fun `无门控的恰好是那四个语义，且在全域恒定`() {
        assertWithMessage("门控表加恒定表必须覆盖全部语义，新增语义要回来补进其中一张")
            .that(gates.keys + ungated.keys)
            .containsExactlyElementsIn(HapticSemantic.entries)
        val constantAcrossRange = HapticSemantic.entries.filter { semantic ->
            (SDK_MIN..SDK_PROBE_MAX).map { aospConstantFor(semantic, it) }.distinct().size == 1
        }
        assertWithMessage("全域取值恒定的语义集合变了：要么有语义被误加了门控，要么有门控被误删")
            .that(constantAcrossRange)
            .containsExactlyElementsIn(ungated.keys)
        ungated.forEach { (semantic, constant) ->
            assertWithMessage("$semantic 无门控，全域都该是 $constant")
                .that(aospConstantFor(semantic, SDK_PROBE_MAX))
                .isEqualTo(constant)
        }
    }

    @Test
    fun `任何档位都不会返回引入版本高于该档位的常量`() {
        HapticSemantic.entries.forEach { semantic ->
            for (sdkInt in SDK_MIN..SDK_PROBE_MAX) {
                val constant = aospConstantFor(semantic, sdkInt)
                // 表里没登记的常量按「引入版本无穷大」算，一并判红：
                // 映射里出现新常量时必须回到这张表登记它的引入版本，否则这条不变式就成了空转
                val since = introducedAt[constant]
                assertWithMessage(
                    "$semantic 在 API $sdkInt 上返回常量 $constant，" +
                        "本测试登记的引入版本是 ${since ?: "未登记"}；" +
                        "高于当前档位的常量在该版本 framework 里没有映射，performHapticFeedback 静默返回 false",
                )
                    .that(since ?: Int.MAX_VALUE)
                    .isAtMost(sdkInt)
            }
        }
    }

    @Test
    fun `全域只在 API 30 与 API 34 两处变化，没有第三处门控`() {
        HapticSemantic.entries.forEach { semantic ->
            for (sdkInt in SDK_MIN..SDK_PROBE_MAX) {
                val expected = when {
                    sdkInt >= SDK_UPSIDE_DOWN_CAKE -> expectedAtVanillaIceCream.getValue(semantic)
                    sdkInt >= SDK_R -> expectedAtR.getValue(semantic)
                    else -> expectedAtMinSdk.getValue(semantic)
                }
                assertWithMessage(
                    "$semantic 在 API $sdkInt 上返回 ${aospConstantFor(semantic, sdkInt)}，" +
                        "与它所在档位（26 / 30 / 34 三档之一）的期望值 $expected 不符，说明多了一处门控",
                )
                    .that(aospConstantFor(semantic, sdkInt))
                    .isEqualTo(expected)
            }
        }
    }

    @Test
    fun `本测试写死的常量取值与 SDK 的 HapticFeedbackConstants 一致`() {
        // 全文唯一引用 SDK 常量的地方，为的是兜住上面那批字面量手抄出错。
        // static final int 在编译期就被内联成字面量，所以这条也不会在运行期加载任何 android 类
        assertThat(LONG_PRESS).isEqualTo(HapticFeedbackConstants.LONG_PRESS)
        assertThat(VIRTUAL_KEY).isEqualTo(HapticFeedbackConstants.VIRTUAL_KEY)
        assertThat(CLOCK_TICK).isEqualTo(HapticFeedbackConstants.CLOCK_TICK)
        assertThat(GESTURE_END).isEqualTo(HapticFeedbackConstants.GESTURE_END)
        assertThat(CONFIRM).isEqualTo(HapticFeedbackConstants.CONFIRM)
        assertThat(REJECT).isEqualTo(HapticFeedbackConstants.REJECT)
        assertThat(TOGGLE_ON).isEqualTo(HapticFeedbackConstants.TOGGLE_ON)
        assertThat(TOGGLE_OFF).isEqualTo(HapticFeedbackConstants.TOGGLE_OFF)
        assertThat(GESTURE_THRESHOLD_ACTIVATE)
            .isEqualTo(HapticFeedbackConstants.GESTURE_THRESHOLD_ACTIVATE)
        assertThat(DRAG_START).isEqualTo(HapticFeedbackConstants.DRAG_START)
        assertThat(SEGMENT_TICK).isEqualTo(HapticFeedbackConstants.SEGMENT_TICK)
        assertThat(SEGMENT_FREQUENT_TICK).isEqualTo(HapticFeedbackConstants.SEGMENT_FREQUENT_TICK)
        // 12 个常量里有重复取值就说明抄错了：常量值互不相同是平台事实
        assertThat(introducedAt.keys).hasSize(EXPECTED_CONSTANT_COUNT)
    }

    /**
     * 常量取值全部取自 `javap -constants` 对 `platforms/android-37.0/android.jar` 的输出，
     * 引入版本全部取自同一 platform 下的 `data/api-versions.xml`。
     *
     * 名字与 `HapticFeedbackConstants` 的字段同名是刻意的：这样每一格期望值读起来就是常量名。
     * 语义枚举在本文件里一律写成 `HapticSemantic.XXX`，不会与这批同名常量混淆。
     */
    private companion object {
        /** `LONG_PRESS`，API 3，低于 minSdk，恒可用 */
        const val LONG_PRESS = 0

        /** `VIRTUAL_KEY`，API 5，低于 minSdk，恒可用 */
        const val VIRTUAL_KEY = 1

        /** `CLOCK_TICK`，API 21，低于 minSdk，恒可用；本表用得最多的退化目标 */
        const val CLOCK_TICK = 4

        /** `GESTURE_END`，API 30 —— 设计文档标 34 有误，见类 KDoc */
        const val GESTURE_END = 13

        /** `CONFIRM`，API 30 */
        const val CONFIRM = 16

        /** `REJECT`，API 30 */
        const val REJECT = 17

        /** `TOGGLE_ON`，API 34 */
        const val TOGGLE_ON = 21

        /** `TOGGLE_OFF`，API 34 */
        const val TOGGLE_OFF = 22

        /** `GESTURE_THRESHOLD_ACTIVATE`，API 34 */
        const val GESTURE_THRESHOLD_ACTIVATE = 23

        /** `DRAG_START`，API 34 */
        const val DRAG_START = 25

        /** `SEGMENT_TICK`，API 34 */
        const val SEGMENT_TICK = 26

        /** `SEGMENT_FREQUENT_TICK`，API 34 */
        const val SEGMENT_FREQUENT_TICK = 27

        /** 项目 minSdk，也是实际会被调到的最低档位 */
        const val SDK_MIN = 26

        /** API 30 = `Build.VERSION_CODES.R`，`CONFIRM` / `REJECT` / `GESTURE_END` 的引入版本 */
        const val SDK_R = 30

        /** API 34 = `Build.VERSION_CODES.UPSIDE_DOWN_CAKE`，另外六个常量的引入版本 */
        const val SDK_UPSIDE_DOWN_CAKE = 34

        /** 任务书指定的第三档。API 35 自身没有引入新的触感常量，所以这一档与 34 同值 */
        const val SDK_VANILLA_ICE_CREAM = 35

        /**
         * 全域扫描上限，比 compileSdk 37 高出几档。
         *
         * 高出几档是为了让「给某个未来版本偷加一处门控」也被扫到 —— 那种改动在 26 / 30 / 35
         * 三个抽样档位上完全看不出来，但会在真机升级到那个版本后突然换掉手感。
         */
        const val SDK_PROBE_MAX = 45

        /** 本文件登记的常量个数，用来挡住「两个常量抄成同一个取值」 */
        const val EXPECTED_CONSTANT_COUNT = 12

        /**
         * 常量取值 → 引入版本。
         *
         * 「返回的常量不得新于当前档位」那条不变式靠它判定，因此新增映射时必须回来登记，
         * 否则那条测试会把未登记的常量按不可用算并判红。
         */
        val introducedAt: Map<Int, Int> = mapOf(
            LONG_PRESS to 3,
            VIRTUAL_KEY to 5,
            CLOCK_TICK to 21,
            GESTURE_END to 30,
            CONFIRM to 30,
            REJECT to 30,
            TOGGLE_ON to 34,
            TOGGLE_OFF to 34,
            GESTURE_THRESHOLD_ACTIVATE to 34,
            DRAG_START to 34,
            SEGMENT_TICK to 34,
            SEGMENT_FREQUENT_TICK to 34,
        )

        /** 引入版本高于 minSdk 的那批常量取值，即「API 26 上一个都不许出现」的黑名单 */
        val constantsNewerThanMinSdk: Set<Int> =
            introducedAt.filterValues { it > SDK_MIN }.keys
    }
}
