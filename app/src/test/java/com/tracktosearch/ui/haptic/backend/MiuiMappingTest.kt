package com.tracktosearch.ui.haptic.backend

import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import com.tracktosearch.ui.haptic.HapticSemantic
import org.junit.Test

/**
 * tier 2（MIUI）那三个纯函数、26 个效果 ID 与 String 键字段表的判定单测。
 *
 * 这一层的全部产品行为就是「哪个语义发哪个效果」的两张表（ext ID 与 String 键字段），
 * 而这两张表抄错一位都是**静默失败**：不崩、不报错，只会让探测对着一个不存在的效果/
 * 字段返回 false，从此这个语义永远降级 —— 手上只觉得「好像轻了点」。
 *
 * 所以期望值一律写成字面量，照设计文档
 * docs/superpowers/plans/2026-09-01-haptics-overhaul.md 的「MIUI 语义效果表（tier 2）」
 * 与 2026-09-09 真机实测的 51 个 `EFFECT_KEY_*` 字段全表重抄，
 * 刻意不写成 `MiuiHapticEffects.MIUI_TAP_NORMAL` 那种从被测代码取值的形式：
 * 期望值与实现同源时，常量被改坏期望值跟着一起变，这个测试永远是绿的。
 *
 * 只测纯函数。[miuiEffectFor]、[miuiProbeIds] 与 [miuiEffectKeyFieldFor] 都是文件顶层
 * 函数，零 Context、零反射、零 android 依赖，纯 JVM 可跑，不用 Robolectric。
 * MiuiBackend 类本身要 Context 加一整套 `miui.util.HapticFeedbackUtil` 反射，
 * JVM 里起不来，不在本文件射程内。
 */
class MiuiMappingTest {

    /**
     * 13 个语义在 tier 2 上各发哪个效果 ID。改这张表就是改产品手感，动手前先回设计文档。
     */
    private val expectedEffect: Map<HapticSemantic, Int> = mapOf(
        HapticSemantic.TAP to 0x10000001, // MIUI_TAP_NORMAL 标准点击
        HapticSemantic.LIGHT_TAP to 0x10000002, // MIUI_TAP_LIGHT 轻点击
        HapticSemantic.SEGMENT_TICK to 0x10000015, // MIUI_GEAR_LIGHT 轻齿轮
        HapticSemantic.FREQUENT_TICK to 0x10000007, // MIUI_MESH_LIGHT 轻网格
        HapticSemantic.TOGGLE_ON to 0x10000004, // MIUI_SWITCH 开关
        HapticSemantic.TOGGLE_OFF to 0x10000004, // 同一个 ID：这一层表达不出开关方向
        HapticSemantic.CONFIRM to 0x10000012, // MIUI_BUTTON_LARGE 大按钮
        HapticSemantic.REJECT to 0x10000018, // MIUI_ALERT 警示
        HapticSemantic.DRAG_START to 0x1000000b, // MIUI_PICK_UP 拾起（2026-09-09 改档，原 hold）
        HapticSemantic.THRESHOLD_ARMED to 0x10000010, // MIUI_BOUNDARY_SPATIAL 空间边界
        HapticSemantic.GESTURE_END to 0x10000003, // MIUI_FLICK 甩动
        HapticSemantic.SCROLL_EDGE to 0x1000000c, // MIUI_SCROLL_EDGE 滚动到边
        HapticSemantic.POPUP_SHOW to 0x1000000a, // MIUI_POPUP_LIGHT 轻弹窗（2026-09-09 改档，原 popup_normal）
    )

    /**
     * 26 个常量按设计文档那张表的顺序排开。名字只用在失败信息里定位，
     * 断言靠的是「第 index 项必须等于 [FIRST_EFFECT_ID] + index」这条连续性。
     */
    private val constantTable: List<Pair<String, Int>> = listOf(
        "MIUI_VIRTUAL_RELEASE" to MiuiHapticEffects.MIUI_VIRTUAL_RELEASE,
        "MIUI_TAP_NORMAL" to MiuiHapticEffects.MIUI_TAP_NORMAL,
        "MIUI_TAP_LIGHT" to MiuiHapticEffects.MIUI_TAP_LIGHT,
        "MIUI_FLICK" to MiuiHapticEffects.MIUI_FLICK,
        "MIUI_SWITCH" to MiuiHapticEffects.MIUI_SWITCH,
        "MIUI_MESH_HEAVY" to MiuiHapticEffects.MIUI_MESH_HEAVY,
        "MIUI_MESH_NORMAL" to MiuiHapticEffects.MIUI_MESH_NORMAL,
        "MIUI_MESH_LIGHT" to MiuiHapticEffects.MIUI_MESH_LIGHT,
        "MIUI_LONG_PRESS" to MiuiHapticEffects.MIUI_LONG_PRESS,
        "MIUI_POPUP_NORMAL" to MiuiHapticEffects.MIUI_POPUP_NORMAL,
        "MIUI_POPUP_LIGHT" to MiuiHapticEffects.MIUI_POPUP_LIGHT,
        "MIUI_PICK_UP" to MiuiHapticEffects.MIUI_PICK_UP,
        "MIUI_SCROLL_EDGE" to MiuiHapticEffects.MIUI_SCROLL_EDGE,
        "MIUI_TRIGGER_DRAWER" to MiuiHapticEffects.MIUI_TRIGGER_DRAWER,
        "MIUI_FLICK_LIGHT" to MiuiHapticEffects.MIUI_FLICK_LIGHT,
        "MIUI_HOLD" to MiuiHapticEffects.MIUI_HOLD,
        "MIUI_BOUNDARY_SPATIAL" to MiuiHapticEffects.MIUI_BOUNDARY_SPATIAL,
        "MIUI_BOUNDARY_TIME" to MiuiHapticEffects.MIUI_BOUNDARY_TIME,
        "MIUI_BUTTON_LARGE" to MiuiHapticEffects.MIUI_BUTTON_LARGE,
        "MIUI_BUTTON_MIDDLE" to MiuiHapticEffects.MIUI_BUTTON_MIDDLE,
        "MIUI_BUTTON_SMALL" to MiuiHapticEffects.MIUI_BUTTON_SMALL,
        "MIUI_GEAR_LIGHT" to MiuiHapticEffects.MIUI_GEAR_LIGHT,
        "MIUI_GEAR_HEAVY" to MiuiHapticEffects.MIUI_GEAR_HEAVY,
        "MIUI_KEYBOARD" to MiuiHapticEffects.MIUI_KEYBOARD,
        "MIUI_ALERT" to MiuiHapticEffects.MIUI_ALERT,
        "MIUI_ZAXIS_SWITCH" to MiuiHapticEffects.MIUI_ZAXIS_SWITCH,
    )

    /**
     * 要逐个探测的 ID：13 条映射去重后的结果，顺序照 `HapticSemantic` 的声明序。
     *
     * 12 个而不是 13 个 —— 开关族两条共用 `MIUI_SWITCH`。
     */
    private val expectedProbeIds: List<Int> = listOf(
        0x10000001, // TAP
        0x10000002, // LIGHT_TAP
        0x10000015, // SEGMENT_TICK
        0x10000007, // FREQUENT_TICK
        0x10000004, // TOGGLE_ON，TOGGLE_OFF 在这里被去重掉
        0x10000012, // CONFIRM
        0x10000018, // REJECT
        0x1000000b, // DRAG_START（PICK_UP，2026-09-09 改档）
        0x10000010, // THRESHOLD_ARMED
        0x10000003, // GESTURE_END
        0x1000000c, // SCROLL_EDGE
        0x1000000a, // POPUP_SHOW（POPUP_LIGHT，2026-09-09 改档）
    )

    @Test
    fun `13 条语义映射逐条对上设计文档的 tier 2 效果表`() {
        val actual = HapticSemantic.entries.associateWith { miuiEffectFor(it) }
        assertThat(actual).containsExactlyEntriesIn(expectedEffect)
    }

    /**
     * 13 个语义在 String 键通路上各用哪个 `EFFECT_KEY_*` 字段。
     *
     * HyperOS 3 上 ext ID 通路对三方 App 全关，这张表才是那类机器真正跑的映射 ——
     * 抄错一个字段名，`Class.getField` 抛 `NoSuchFieldException`，String 通路的探测返回
     * null，整层悄悄退回 ext 通路再继续空转 —— 和 ext 表抄错一样是静默失败，
     * 所以期望值照 ROM 实测的 51 字段全表逐字重抄。
     */
    private val expectedKeyField: Map<HapticSemantic, String> = mapOf(
        HapticSemantic.TAP to "EFFECT_KEY_TAP_NORMAL",
        HapticSemantic.LIGHT_TAP to "EFFECT_KEY_TAP_LIGHT",
        HapticSemantic.SEGMENT_TICK to "EFFECT_KEY_GEAR_LIGHT",
        HapticSemantic.FREQUENT_TICK to "EFFECT_KEY_MESH_LIGHT",
        HapticSemantic.TOGGLE_ON to "EFFECT_KEY_SWITCH",
        HapticSemantic.TOGGLE_OFF to "EFFECT_KEY_SWITCH", // 与 ext 表同口径：这层表达不出方向
        HapticSemantic.CONFIRM to "EFFECT_KEY_BUTTON_LARGE",
        HapticSemantic.REJECT to "EFFECT_KEY_ALERT",
        HapticSemantic.DRAG_START to "EFFECT_KEY_PICKUP", // 拾起（2026-09-09 改档，原 hold）
        HapticSemantic.THRESHOLD_ARMED to "EFFECT_KEY_BOUNDARY_SPATIAL",
        HapticSemantic.GESTURE_END to "EFFECT_KEY_FLICK",
        HapticSemantic.SCROLL_EDGE to "EFFECT_KEY_SCROLL_EDGE",
        HapticSemantic.POPUP_SHOW to "EFFECT_KEY_POPUP_LIGHT", // 轻弹窗（2026-09-09 改档）
    )

    @Test
    fun `13 条语义的 String 键字段逐字对上 ROM 实测的常量表`() {
        val actual = HapticSemantic.entries.associateWith { miuiEffectKeyFieldFor(it) }
        assertThat(actual).containsExactlyEntriesIn(expectedKeyField)
    }

    @Test
    fun `String 键与 ext ID 两条映射选的是同一个效果`() {
        // 同一个语义在两条通路上必须指向同名效果：老 MIUI（ext 通路）与 HyperOS
        // （String 键通路）之间换机时，同一个交互不该换手感。缺映射会 getValue 抛异常判红，
        // 逼着往这张对照表补一行
        val idToField = mapOf(
            MiuiHapticEffects.MIUI_TAP_NORMAL to "EFFECT_KEY_TAP_NORMAL",
            MiuiHapticEffects.MIUI_TAP_LIGHT to "EFFECT_KEY_TAP_LIGHT",
            MiuiHapticEffects.MIUI_GEAR_LIGHT to "EFFECT_KEY_GEAR_LIGHT",
            MiuiHapticEffects.MIUI_MESH_LIGHT to "EFFECT_KEY_MESH_LIGHT",
            MiuiHapticEffects.MIUI_SWITCH to "EFFECT_KEY_SWITCH",
            MiuiHapticEffects.MIUI_BUTTON_LARGE to "EFFECT_KEY_BUTTON_LARGE",
            MiuiHapticEffects.MIUI_ALERT to "EFFECT_KEY_ALERT",
            MiuiHapticEffects.MIUI_PICK_UP to "EFFECT_KEY_PICKUP",
            MiuiHapticEffects.MIUI_BOUNDARY_SPATIAL to "EFFECT_KEY_BOUNDARY_SPATIAL",
            MiuiHapticEffects.MIUI_FLICK to "EFFECT_KEY_FLICK",
            MiuiHapticEffects.MIUI_SCROLL_EDGE to "EFFECT_KEY_SCROLL_EDGE",
            MiuiHapticEffects.MIUI_POPUP_LIGHT to "EFFECT_KEY_POPUP_LIGHT",
        )
        HapticSemantic.entries.forEach { semantic ->
            assertWithMessage("$semantic 在两条通路上选了不同名的效果")
                .that(miuiEffectKeyFieldFor(semantic))
                .isEqualTo(idToField.getValue(miuiEffectFor(semantic)))
        }
    }

    @Test
    fun `26 个效果 ID 从 0x10000000 起严格连续到 0x10000019`() {
        assertThat(constantTable).hasSize(EXPECTED_EFFECT_COUNT)
        constantTable.forEachIndexed { index, (name, value) ->
            val expected = FIRST_EFFECT_ID + index
            assertWithMessage("$name 应是 ${hex(expected)}，实际 ${hex(value)}；抄错一位不会报错，只会静默不震")
                .that(value)
                .isEqualTo(expected)
        }
        // 上面逐项比对已经隐含了不重复，这两条是给「有人往表中间插一行」留的直白提示
        assertThat(constantTable.map { it.second }).containsNoDuplicates()
        assertThat(constantTable.last().second).isEqualTo(LAST_EFFECT_ID)
    }

    @Test
    fun `映射用到的 ID 全在这 26 个常量之内`() {
        val known = constantTable.map { it.second }.toSet()
        HapticSemantic.entries.forEach { semantic ->
            val id = miuiEffectFor(semantic)
            assertWithMessage("$semantic 映到 ${hex(id)}，不在 MiuiHapticFeedbackConstants 的 26 个 ID 里")
                .that(known)
                .contains(id)
        }
    }

    @Test
    fun `miuiProbeIds 去重成 12 个，顺序照语义声明序`() {
        val actual = miuiProbeIds()
        assertThat(actual).hasSize(EXPECTED_PROBE_COUNT)
        assertThat(actual).containsNoDuplicates()
        assertThat(actual).containsExactlyElementsIn(expectedProbeIds).inOrder()
    }

    @Test
    fun `探测列表与语义映射严格互相覆盖，不漏探也不多探`() {
        // 漏探一个 ID 的后果：supports 查缓存查不到，那个语义永远走不到 tier 2，
        // 而且跟「ROM 说这个效果不支持」的表现一模一样，事后根本分不清是哪一种
        val mapped = HapticSemantic.entries.map { miuiEffectFor(it) }.toSet()
        assertThat(miuiProbeIds().toSet()).containsExactlyElementsIn(mapped)
    }

    @Test
    fun `只有开关族共用同一个 ID，其余语义各占一个`() {
        // 两个不相干的语义撞到同一个 ID，梯度就塌了一档：体感上两种交互变得一模一样
        val shared = HapticSemantic.entries
            .groupBy { miuiEffectFor(it) }
            .filterValues { it.size > 1 }
        assertWithMessage("共用同一个 ID 的语义组应当只有开关族一组，实际 $shared")
            .that(shared.keys)
            .hasSize(1)
        assertThat(shared.values.single())
            .containsExactly(HapticSemantic.TOGGLE_ON, HapticSemantic.TOGGLE_OFF)
    }

    @Test
    fun `刻意不入表的与重档的 ID 不许被任何语义用上`() {
        // 这六个全都紧邻某个真在用的 ID，差一位就正好落进来，且落进来照样能震 ——
        // 不会红、不会崩，只是手感悄悄错档。所以单独钉一条，让改表的人必须解释为什么
        val forbidden = listOf(
            MiuiHapticEffects.MIUI_VIRTUAL_RELEASE to "虚拟键抬起，与这 13 个语义无关，且紧邻表首最容易被差一位撞上",
            MiuiHapticEffects.MIUI_MESH_HEAVY to "连发语义只许走轻网格，重网格会在一次拖动里累出几十下重震",
            MiuiHapticEffects.MIUI_MESH_NORMAL to "同上，普通网格也偏重",
            MiuiHapticEffects.MIUI_LONG_PRESS to "长按刻意不入语义表：combinedClickable 在 onLongClick 之前已自行发过一次",
            MiuiHapticEffects.MIUI_POPUP_NORMAL to "2026-09-09 改档弃用：被动弹窗走更轻的 POPUP_LIGHT（HyperOS 属性 6,1 vs 6,2），改回来先过设计文档",
            MiuiHapticEffects.MIUI_HOLD to "2026-09-09 改档弃用：hold 语义是「保持」且原生默认弱档（属性 4,0），起手那记要沉的，走 PICK_UP",
            MiuiHapticEffects.MIUI_GEAR_HEAVY to "刻度感只许走轻齿轮，重齿轮会把 Tab 切换震成撞击",
            MiuiHapticEffects.MIUI_KEYBOARD to "留给彩蛋答题期的键盘反馈，不归这 13 个语义",
        )
        val used = HapticSemantic.entries.map { miuiEffectFor(it) }.toSet()
        forbidden.forEach { (id, reason) ->
            assertWithMessage("${hex(id)} 不该出现在语义映射里：$reason")
                .that(used)
                .doesNotContain(id)
        }
    }

    /** 失败信息里把 ID 印成 `0x1000000f` 这种形状，比十进制的 268435471 好认。 */
    private fun hex(id: Int): String = "0x%08x".format(id)

    private companion object {
        /** 效果 ID 表的首项，`miui.view.MiuiHapticFeedbackConstants` 从这里起。 */
        const val FIRST_EFFECT_ID = 0x10000000

        /** 表尾，26 个 ID 连续到这里。 */
        const val LAST_EFFECT_ID = 0x10000019

        /** 常量总数，与设计文档那张 26 行的表一致。 */
        const val EXPECTED_EFFECT_COUNT = 26

        /** 去重后要逐个探测的 ID 个数：13 个语义里开关族两条共用一个。 */
        const val EXPECTED_PROBE_COUNT = 12
    }
}
