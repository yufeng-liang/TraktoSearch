package com.tracktosearch.ui.haptic.backend

import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import com.tracktosearch.ui.haptic.HapticSemantic
import org.junit.Test

/**
 * tier 2（OPlus）纯映射函数 [oplusEffectFor] 与 [OplusHapticEffects] 那 17 个效果常量的单测。
 *
 * 这一层错得最狠的一种方式是**静默错档**：`LinearmotorVibrator.vibrate` 返回 void，
 * ColorOS 侧收到一个抄错一位的效果 ID 时既不抛也不报，马达照样会动 —— 只是动成了另一个效果。
 * 68 抄成 69（弱颗粒变强颗粒）、73 抄成 74（吸附变呼吸式扩散）在真机上的表现只是
 * 「手感好像不太对」，日志里一个字都没有。这张表是唯一能挡住它的地方，所以逐格钉死。
 *
 * 期望值一律写成十进制字面量，照设计文档
 * docs/superpowers/plans/2026-09-01-haptics-overhaul.md 的「厂商通路矩阵 → OPPO 通路细节」
 * 那张 ID 表与末尾那段「语义词表接到 OPPO 的映射」重抄，刻意不写成
 * `OplusHapticEffects.EFFECT_XXX`：期望值与被测代码同源时，常量被改坏期望值跟着一起变，
 * 这个测试永远是绿的。
 *
 * 只测纯函数。[oplusEffectFor]、[OplusEffectSpec]、[OplusHapticEffects]、[OplusEffectStrength]
 * 都零 android 依赖、不碰 Context 也不碰反射，纯 JVM 可跑，不用 Robolectric。
 * [OplusBackend] 类本身要 Context 加一整套 `com.oplus.os` 反射与 `getSystemService` 取服务，
 * JVM 里起不来，不在本文件射程内。
 *
 * 设计文档只钉死了 12 条效果 ID，另外两处是实现方的判断，本测试一并钉住，改动时要一起改：
 * [HapticSemantic.POPUP_SHOW] 的落点（文档没给），以及 13 个语义的强度档（文档只给了效果 ID）。
 */
class OplusMappingTest {

    /**
     * 13 个语义在 tier 2 上各发哪个效果、配哪一档强度。改这张表就是改产品手感，动手前先回设计文档。
     *
     * 具名传参不是啰嗦：[OplusEffectSpec] 两个字段都是 Int，位置传参在字段声明顺序被调换后
     * 照样编译、照样绿，而两个 Int 传反在真机上就是发错效果配错音量。
     */
    private val expectedSpecs: Map<HapticSemantic, OplusEffectSpec> = mapOf(
        // 2 中等短振一次 / 中档：通用点击的顶档，实心的一击
        HapticSemantic.TAP to OplusEffectSpec(effectType = 2, strength = 1),
        // 1 弱短振一次 / 轻档：一屏里反复出现的列表项
        HapticSemantic.LIGHT_TAP to OplusEffectSpec(effectType = 1, strength = 0),
        // 0 最弱短振一次 / 轻档：Tab、单选、分段控件的刻度感
        HapticSemantic.SEGMENT_TICK to OplusEffectSpec(effectType = 0, strength = 0),
        // 68 弱颗粒感 / 轻档：OPPO 给连续刻度专做的效果
        HapticSemantic.FREQUENT_TICK to OplusEffectSpec(effectType = 68, strength = 0),
        // 2 中等短振一次 / 中档：与 TAP 同落点，状态「立起来」
        HapticSemantic.TOGGLE_ON to OplusEffectSpec(effectType = 2, strength = 1),
        // 1 弱短振一次 / 轻档：与 LIGHT_TAP 同落点，关比开轻
        HapticSemantic.TOGGLE_OFF to OplusEffectSpec(effectType = 1, strength = 0),
        // 3 中等短振两次 / 重档：成功那一笔，两下连击就是「办成了」的语法
        HapticSemantic.CONFIRM to OplusEffectSpec(effectType = 3, strength = 2),
        // 9 大幅度 / 中档：失败语义。刻意不给重档，避免语义词表明令要避免的「重到惊吓」
        HapticSemantic.REJECT to OplusEffectSpec(effectType = 9, strength = 1),
        // 49 立体触感 / 重档：起手那记沉的「抓住了」
        HapticSemantic.DRAG_START to OplusEffectSpec(effectType = 49, strength = 2),
        // 50 扩散 / 中档：到阈值的上冲感，「可以松手了」
        HapticSemantic.THRESHOLD_ARMED to OplusEffectSpec(effectType = 50, strength = 1),
        // 73 吸附到中位 / 轻档：手势落定与面板吸附
        HapticSemantic.GESTURE_END to OplusEffectSpec(effectType = 73, strength = 0),
        // 154 滑条到边界 / 轻档：会被反复顶到所以最轻
        HapticSemantic.SCROLL_EDGE to OplusEffectSpec(effectType = 154, strength = 0),
        // 1 弱短振一次 / 轻档：设计文档的 OPPO 映射漏了这一条，实现方定为与 LIGHT_TAP 同落点
        HapticSemantic.POPUP_SHOW to OplusEffectSpec(effectType = 1, strength = 0),
    )

    /**
     * 17 个交互效果常量的实际取值。往 [OplusHapticEffects] 加常量就要回来补一行，
     * 否则「留着不用的恰好是那 7 个」那条断言会漏掉新来的那个。
     *
     * 用 `with` 把常量名写成不带类名的形式，只为让这张表一行一个常量、不折行。
     */
    private val declaredEffects: Map<String, Int> = with(OplusHapticEffects) {
        mapOf(
            "EFFECT_WEAKEST_SHORT_VIBRATE_ONCE" to EFFECT_WEAKEST_SHORT_VIBRATE_ONCE,
            "EFFECT_WEAK_SHORT_VIBRATE_ONCE" to EFFECT_WEAK_SHORT_VIBRATE_ONCE,
            "EFFECT_MODERATE_SHORT_VIBRATE_ONCE" to EFFECT_MODERATE_SHORT_VIBRATE_ONCE,
            "EFFECT_MODERATE_SHORT_VIBRATE_TWICE" to EFFECT_MODERATE_SHORT_VIBRATE_TWICE,
            "EFFECT_OTHER_BIG_SCALE" to EFFECT_OTHER_BIG_SCALE,
            "EFFECT_OTHER_SMALL_SCALE" to EFFECT_OTHER_SMALL_SCALE,
            "EFFECT_CUSTOMIZED_THREE_DIMENSION_TOUCH" to EFFECT_CUSTOMIZED_THREE_DIMENSION_TOUCH,
            "EFFECT_CUSTOMIZED_SPREAD_OUT" to EFFECT_CUSTOMIZED_SPREAD_OUT,
            "EFFECT_CUSTOMIZED_CONVERGE" to EFFECT_CUSTOMIZED_CONVERGE,
            "EFFECT_CUSTOMIZED_WEAK_GRANULAR" to EFFECT_CUSTOMIZED_WEAK_GRANULAR,
            "EFFECT_CUSTOMIZED_STRONG_GRANULAR" to EFFECT_CUSTOMIZED_STRONG_GRANULAR,
            "EFFECT_CUSTOMIZED_ATTACH_TO_MIDDLE" to EFFECT_CUSTOMIZED_ATTACH_TO_MIDDLE,
            "EFFECT_CUSTOMIZED_BREATHE_SPREAD_OUT" to EFFECT_CUSTOMIZED_BREATHE_SPREAD_OUT,
            "EFFECT_OTHER_STRENGTH_LEVEL_BAR_EDGE" to EFFECT_OTHER_STRENGTH_LEVEL_BAR_EDGE,
            "EFFECT_WEAK_EMULATION_KEYBOARD_DOWN" to EFFECT_WEAK_EMULATION_KEYBOARD_DOWN,
            "EFFECT_WEAK_EMULATION_KEYBOARD_UP" to EFFECT_WEAK_EMULATION_KEYBOARD_UP,
            "EFFECT_SCROLL_ON_TIME_PICKER" to EFFECT_SCROLL_ON_TIME_PICKER,
        )
    }

    /**
     * 当前没有语义用到、刻意留在 [OplusHapticEffects] 里的 7 个常量：名字 → 设计文档给的取值。
     *
     * 这 7 个的取值没有任何映射断言会碰到，抄错也不会红。304 与 305 要等 T6 的彩蛋键盘才第一次
     * 被用上，那时候再发现抄错就晚了；其余五个个个紧邻某个真在用的 ID（10 挨着 9、51 挨着 50、
     * 69 挨着 68、74 挨着 73、408 是另一条滚动通路），差一位就正好落进来，而落进来照样能震。
     */
    private val reservedEffects: Map<String, Int> = mapOf(
        "EFFECT_OTHER_SMALL_SCALE" to 10,
        "EFFECT_CUSTOMIZED_CONVERGE" to 51,
        "EFFECT_CUSTOMIZED_STRONG_GRANULAR" to 69,
        "EFFECT_CUSTOMIZED_BREATHE_SPREAD_OUT" to 74,
        "EFFECT_WEAK_EMULATION_KEYBOARD_DOWN" to 304,
        "EFFECT_WEAK_EMULATION_KEYBOARD_UP" to 305,
        "EFFECT_SCROLL_ON_TIME_PICKER" to 408,
    )

    @Test
    fun `13 条语义映射逐条对上设计文档的 OPPO 映射表`() {
        val actual = HapticSemantic.entries.associateWith { oplusEffectFor(it) }
        assertThat(actual).containsExactlyEntriesIn(expectedSpecs)
    }

    @Test
    fun `映射用到的 effectType 全在 17 个交互效果常量之内`() {
        assertWithMessage("往 OplusHapticEffects 加了常量就要同步补进 declaredEffects")
            .that(declaredEffects)
            .hasSize(EXPECTED_EFFECT_COUNT)
        val declared = declaredEffects.values.toSet()
        HapticSemantic.entries.forEach { semantic ->
            val effectType = oplusEffectFor(semantic).effectType
            assertWithMessage(
                "$semantic 映到效果 $effectType，不在 OplusHapticEffects 那 17 个交互效果里；" +
                    "ColorOS 收到不认识的 ID 不会抛错，Builder 默认的 -1 更是直接被对方丢掉",
            )
                .that(declared)
                .contains(effectType)
        }
    }

    @Test
    fun `13 个语义的强度档只取 LIGHT MEDIUM STRONG 三档`() {
        assertWithMessage("强度档常量应是 0 LIGHT、1 MEDIUM、2 STRONG，照设计文档「OPPO 通路细节」")
            .that(listOf(OplusEffectStrength.LIGHT, OplusEffectStrength.MEDIUM, OplusEffectStrength.STRONG))
            .containsExactly(0, 1, 2)
            .inOrder()
        HapticSemantic.entries.forEach { semantic ->
            val strength = oplusEffectFor(semantic).strength
            assertWithMessage(
                "$semantic 的强度档是 $strength，合法档位只有 0 / 1 / 2；" +
                    "3 到 2400 之间的值会被 setEffectStrength 原样收下但不是合法档位，" +
                    "大于 2400 会被它悄悄改成 -1，也就是这一次振动的强度被交回系统 —— 两种都不报错",
            )
                .that(LEGAL_STRENGTHS)
                .contains(strength)
        }
    }

    @Test
    fun `留着不用的恰好是那 7 个常量，取值也对得上设计文档`() {
        val used = HapticSemantic.entries.map { oplusEffectFor(it).effectType }.toSet()
        val unused = declaredEffects.filterValues { it !in used }
        assertWithMessage(
            "17 个常量应当是 10 个在用加 7 个留着不用；这条同时兜住那 7 个的取值 —— " +
                "它们没有任何映射断言会碰到，抄错要等 T6 用上键盘那两个时才发作",
        )
            .that(unused)
            .containsExactlyEntriesIn(reservedEffects)
        assertThat(declaredEffects.values).containsNoDuplicates()
    }

    @Test
    fun `短振族内的效果 ID 次序与语义梯度一致`() {
        // 0 / 1 / 2 / 3 这一族是同一个波形的四档轻重，ID 越大越重，所以族内可以直接比大小。
        // 抄反两格不会红也不会崩，只会让「列表项比按钮还重」这种手感悄悄反过来
        val ladder = listOf(
            HapticSemantic.SEGMENT_TICK,
            HapticSemantic.LIGHT_TAP,
            HapticSemantic.TAP,
            HapticSemantic.CONFIRM,
        )
        val ids = ladder.map { oplusEffectFor(it).effectType }
        ids.forEach { id ->
            assertWithMessage("$ladder 这四个语义都该落在短振族 0..3 里，$id 不在族内，这条次序断言就没意义了")
                .that(SHORT_VIBRATE_FAMILY)
                .contains(id)
        }
        assertWithMessage("短振族里的 ID 应严格递增：${ladder.zip(ids)}")
            .that(ids)
            .isInStrictOrder()
        assertWithMessage("关比开轻是语义词表定的方向，两者又都在短振族里，ID 必须 OFF 小于 ON")
            .that(oplusEffectFor(HapticSemantic.TOGGLE_OFF).effectType)
            .isLessThan(oplusEffectFor(HapticSemantic.TOGGLE_ON).effectType)
    }

    @Test
    fun `共用落点的只有开关族与弹窗两组，其余语义各占一个`() {
        // 两个不相干的语义撞到同一个落点，梯度就塌了一档：体感上两种交互变得一模一样。
        // 共用落点还有第二重后果 —— 换成只调强度后同一落点在轻/强档也只是同一效果的轻重变化：
        // TOGGLE_ON 上移到 TAP、POPUP_SHOW 上移到 LIGHT_TAP，两组在 ColorOS 上都是原地不动。
        // 这是设计文档钉死 toggleOn 与 tap 同为 2 的固有结果，不是实现的错，但改这两条时要知道
        val shared = HapticSemantic.entries
            .groupBy { oplusEffectFor(it) }
            .filterValues { it.size > 1 }
        assertWithMessage("共用同一个落点的语义组应当只有两组，实际 $shared")
            .that(shared)
            .hasSize(2)
        assertThat(shared.getValue(OplusEffectSpec(effectType = 2, strength = 1)))
            .containsExactly(HapticSemantic.TAP, HapticSemantic.TOGGLE_ON)
        assertThat(shared.getValue(OplusEffectSpec(effectType = 1, strength = 0)))
            .containsExactly(
                HapticSemantic.LIGHT_TAP,
                HapticSemantic.TOGGLE_OFF,
                HapticSemantic.POPUP_SHOW,
            )
        assertWithMessage("13 个语义应落在 10 个互不相同的落点上")
            .that(HapticSemantic.entries.map { oplusEffectFor(it) }.distinct())
            .hasSize(EXPECTED_DISTINCT_SPEC_COUNT)
    }

    private companion object {
        /** [OplusHapticEffects] 里交互效果常量的个数，与设计文档那张 17 行的表一致。 */
        const val EXPECTED_EFFECT_COUNT = 17

        /** 13 个语义去重后的落点数：开关族两条各撞进点击族一次、弹窗再撞一次，13 减 3 等于 10。 */
        const val EXPECTED_DISTINCT_SPEC_COUNT = 10

        /** `setEffectStrength` 的三个合法档位，0 LIGHT、1 MEDIUM、2 STRONG。 */
        val LEGAL_STRENGTHS = listOf(0, 1, 2)

        /**
         * 短振族：`EFFECT_{WEAKEST,WEAK,MODERATE}_SHORT_VIBRATE_ONCE` 与
         * `EFFECT_MODERATE_SHORT_VIBRATE_TWICE`，同一个波形的四档轻重，族内 ID 越大越重。
         * 68、73、154 这些自定义效果是各自独立的波形，跟它们比 ID 大小没有意义。
         */
        val SHORT_VIBRATE_FAMILY = listOf(0, 1, 2, 3)
    }
}
