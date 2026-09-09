package com.tracktosearch.ui.haptic.backend

import android.content.Context
import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import com.tracktosearch.ui.haptic.HapticCapabilities
import com.tracktosearch.ui.haptic.HapticSemantic
import com.tracktosearch.ui.haptic.HapticStrength
import io.mockk.every
import io.mockk.mockk
import io.mockk.unmockkAll
import java.util.concurrent.TimeUnit
import org.junit.After
import org.junit.Before
import org.junit.Test

/**
 * tier 3（RichTap）两个纯函数的判定单测：13 条语义到预置效果的映射，
 * 以及把逐点包络压成 SDK 固定四点包络的重采样。
 *
 * 这一层错一位全是静默失败，没有一处会红：
 *
 * - 效果 ID 越出 10001..10050，SDK 不报错，改把它当 AOSP `EFFECT_` 常量播
 *   （反编译 `playExtPrebaked` 字节码确认了这条分支），照样震，只是手感完全不是那个效果。
 * - 强度越出 0..255，`playExtPrebaked` 抛 `IllegalArgumentException`，而本层所有调用都在
 *   单线程 executor 里被 `catch (Throwable)` 吃掉并整层禁用：用户只觉得触感忽然全没了。
 * - 两个语义撞到同一个「效果 ID 加强度」，梯度塌掉一档，两种交互的体感变得一模一样。
 * - 包络该返 null 时返回了一段能播的东西，就是拿别的波形假装成功，上层再也不会降级。
 *
 * 期望值一律照设计文档 docs/superpowers/plans/2026-09-01-haptics-overhaul.md 的
 * 「语义词表」tier 3 列重抄成十进制字面量，刻意不写成 `PrebakedEffectId.RT_CLICK` 或
 * `RichTapStrength.FULL`：期望值与实现同源时，常量被改坏期望值跟着一起变，测试永远是绿的。
 *
 * 本类只测纯函数：两个函数零 Android 依赖，不用 Robolectric 也不用 MockK。
 * `RichTapBackend` 类要 Context、要 `RichTapUtils` 单例、要反射 ROM 侧的 `createEnvelope`，
 * 大部分行为在 JVM 单测里验不了；但有一条既验得了、也必须验 ——
 * 整层不可用时 `stop()` 不许白建那条永不退出的线程 —— 它拆在本文件末尾的
 * [RichTapStopThreadGuardTest] 里，那一组同样是纯 JVM。
 */
class RichTapMappingTest {
    /**
     * 13 条语义到「预置效果 ID 加强度」的全表。改这张表就是改产品手感，动手前先回设计文档。
     *
     * 设计文档 tier 3 列只钉了 ID：`frequentTick`、`toggleOff`、`scrollEdge` 三处写的是
     * 「低幅」，`gestureEnd` 与 `popupShow` 连 ID 都和 `lightTap` 撞在同一个 `RT_SOFT_CLICK`。
     * 那五处的强度是实现方补的，补法的两条约束在下面各有一个用例钉住。
     */
    private val expectedEffect: Map<HapticSemantic, RichTapEffect> = mapOf(
        HapticSemantic.TAP to RichTapEffect(10001, 255), // RT_CLICK 满幅
        HapticSemantic.LIGHT_TAP to RichTapEffect(10003, 255), // RT_SOFT_CLICK 满幅
        HapticSemantic.SEGMENT_TICK to RichTapEffect(10004, 255), // RT_TICK 满幅
        HapticSemantic.FREQUENT_TICK to RichTapEffect(10004, 76), // RT_TICK 低幅，0.3 档
        // RT_TOGGLE_SWITCH 满幅。tier 1 的 toggleOn 是 0.7，这一层刻意更重，别「对齐」回去
        HapticSemantic.TOGGLE_ON to RichTapEffect(10009, 255),
        HapticSemantic.TOGGLE_OFF to RichTapEffect(10009, 128), // 同一开关效果压到 0.5 档
        HapticSemantic.CONFIRM to RichTapEffect(10007, 255), // RT_SUCCESS
        HapticSemantic.REJECT to RichTapEffect(10006, 255), // RT_FAILURE
        HapticSemantic.DRAG_START to RichTapEffect(10010, 255), // RT_LONG_PRESS
        HapticSemantic.THRESHOLD_ARMED to RichTapEffect(10008, 255), // RT_RAMP_UP
        // RT_SOFT_CLICK 0.7 档。tier 1 的 gestureEnd 是 0.5，但这一层必须重于 POPUP_SHOW 的 0.5 档，
        // 改成 LIGHT 去「对齐」tier 1 就和 POPUP_SHOW 撞成同一个手感
        HapticSemantic.GESTURE_END to RichTapEffect(10003, 178),
        HapticSemantic.SCROLL_EDGE to RichTapEffect(10004, 102), // RT_TICK 低幅，0.4 档
        HapticSemantic.POPUP_SHOW to RichTapEffect(10003, 128), // RT_SOFT_CLICK 0.5 档
    )

    /** 设计文档 tier 3 列点名的 8 个预置效果，映射只许在这几个里选。 */
    private val documentedEffectIds: Set<Int> =
        setOf(10001, 10003, 10004, 10006, 10007, 10008, 10009, 10010)

    @Test
    fun `13 条语义映射逐条对上设计文档的 tier 3 效果表`() {
        assertThat(HapticSemantic.entries).hasSize(EXPECTED_SEMANTIC_COUNT)
        val actual = HapticSemantic.entries.associateWith { richTapEffectFor(it) }
        assertThat(actual).containsExactlyEntriesIn(expectedEffect)
    }

    @Test
    fun `每个效果 ID 都落在 SDK 的 10001 到 10050 预置区间内`() {
        HapticSemantic.entries.forEach { semantic ->
            val id = richTapEffectFor(semantic).effectId
            assertWithMessage(
                "$semantic 映到 $id；越出 $PREBAKED_ID_MIN..$PREBAKED_ID_MAX 时 SDK 不报错，" +
                    "改把这个数当 AOSP EFFECT_ 常量播，照样震但手感完全不是这个效果",
            ).that(id).isIn(PREBAKED_ID_MIN..PREBAKED_ID_MAX)
        }
    }

    @Test
    fun `每个强度都落在 SDK 校验的 0 到 255 之内`() {
        HapticSemantic.entries.forEach { semantic ->
            val strength = richTapEffectFor(semantic).strength
            assertWithMessage(
                "$semantic 的强度是 $strength；playExtPrebaked 对越界值抛 IllegalArgumentException，" +
                    "而它在单线程 executor 里被 catch (Throwable) 吃掉后整层 tier 3 就永久禁用了",
            ).that(strength).isIn(MIN_STRENGTH..MAX_STRENGTH)
        }
    }

    @Test
    fun `映射只用设计文档点名的那 8 个预置效果`() {
        val used = HapticSemantic.entries.map { richTapEffectFor(it).effectId }.toSet()
        assertThat(used).containsExactlyElementsIn(documentedEffectIds)
        // 这四个是「名字看着更对」的陷阱：RT_CONFIRM / RT_REJECT 的名字正好撞上语义名，
        // 而设计文档选的是 RT_SUCCESS 与 RT_FAILURE；RT_DOUBLE_CLICK 与 RT_THUD 紧邻在用的 ID，
        // 差一位就落进来。四个都在 10001..10050 内，落进来照样能震，不会报错
        val traps = listOf(
            10002 to "RT_DOUBLE_CLICK 紧邻 RT_CLICK 10001，差一位就撞上",
            10005 to "RT_THUD 夹在 RT_TICK 10004 与 RT_FAILURE 10006 之间，两边都可能差一位撞上",
            10022 to "RT_CONFIRM 的名字撞 CONFIRM 语义，但设计文档给 CONFIRM 选的是 RT_SUCCESS 10007",
            10023 to "RT_REJECT 的名字撞 REJECT 语义，但设计文档给 REJECT 选的是 RT_FAILURE 10006",
        )
        traps.forEach { (id, reason) ->
            assertWithMessage("$id 不该出现在语义映射里：$reason").that(used).doesNotContain(id)
        }
    }

    @Test
    fun `13 个语义的 效果加强度 组合互不相同`() {
        // 两个语义连强度都撞在一起，用户就分不出这是两种交互，梯度塌掉一档；
        // 而且这种撞车不会红、不会崩，只在手上「怎么点哪儿都一个味」
        val all = HapticSemantic.entries.map { richTapEffectFor(it) }
        assertThat(all).hasSize(EXPECTED_SEMANTIC_COUNT)
        assertThat(all).containsNoDuplicates()
    }

    @Test
    fun `共用同一效果的三组语义靠强度分档，次序与语义梯度一致`() {
        val shared = HapticSemantic.entries
            .groupBy { richTapEffectFor(it).effectId }
            .filterValues { it.size > 1 }
        assertWithMessage("共用同一效果 ID 的语义组应当只有软点击、刻度、开关这三组，实际是 $shared")
            .that(shared.keys)
            .containsExactly(10003, 10004, 10009)
        // RT_SOFT_CLICK 三档：词表把 GESTURE_END 排得比 POPUP_SHOW 重一档，
        // 同一效果内的强度必须照这个次序排
        assertStrengthDescending(
            HapticSemantic.LIGHT_TAP,
            HapticSemantic.GESTURE_END,
            HapticSemantic.POPUP_SHOW,
        )
        // RT_TICK 三档：SEGMENT_TICK 是刻度感的正档，SCROLL_EDGE 会被反复顶到所以更轻
        assertStrengthDescending(
            HapticSemantic.SEGMENT_TICK,
            HapticSemantic.SCROLL_EDGE,
            HapticSemantic.FREQUENT_TICK,
        )
        // RT_TOGGLE_SWITCH 两档：开比关重，词表把 TOGGLE_OFF 直接排在开关族的轻半边
        assertStrengthDescending(HapticSemantic.TOGGLE_ON, HapticSemantic.TOGGLE_OFF)
        val lightest = HapticSemantic.entries.minBy { richTapEffectFor(it).strength }
        assertWithMessage("一次手势里连发几十次的语义必须是全表最轻的一档，否则一次滑块拖动累出几十记重震")
            .that(lightest)
            .isEqualTo(HapticSemantic.FREQUENT_TICK)
    }

    /** 断言这几个语义落在同一个效果 ID 上，且强度按给定顺序严格递减。 */
    private fun assertStrengthDescending(vararg fromHeavy: HapticSemantic) {
        val effects = fromHeavy.map { it to richTapEffectFor(it) }
        val ids = effects.map { it.second.effectId }.toSet()
        assertWithMessage("${fromHeavy.toList()} 应当共用同一个效果 ID，实际是 $ids").that(ids).hasSize(1)
        effects.zipWithNext { (heavySemantic, heavy), (lightSemantic, light) ->
            assertWithMessage(
                "同一个效果里 $heavySemantic 必须重于 $lightSemantic，" +
                    "实际 ${heavy.strength} 与 ${light.strength}",
            ).that(heavy.strength).isGreaterThan(light.strength)
        }
    }

    @Test
    fun `强度梯队五档严格降序，且对齐设计文档给出的 scale`() {
        // 全文唯一直接读实现里常量值的用例：它的活就是把那五个常量钉在设计文档 tier 1 列的
        // scale 上（0.7 / 0.5 / 0.4，外加满幅与实现方补的 0.3），别处的期望值一律写字面量。
        // 钉的是这五个**档位取值**的出处，不是「哪个语义拿哪一档」—— 后者以 tier 3 列与
        // 同一效果 ID 内部的次序为准，TOGGLE_ON 与 GESTURE_END 两处就刻意不与 tier 1 同档
        val ladder = listOf(
            Triple("FULL", RichTapStrength.FULL, 1.0f),
            Triple("MEDIUM", RichTapStrength.MEDIUM, 0.7f),
            Triple("LIGHT", RichTapStrength.LIGHT, 0.5f),
            Triple("FAINT", RichTapStrength.FAINT, 0.4f),
            Triple("FEATHER", RichTapStrength.FEATHER, 0.3f),
        )
        assertThat(ladder.map { it.second }).containsExactly(255, 178, 128, 102, 76).inOrder()
        ladder.forEach { (name, value, scale) ->
            assertWithMessage(
                "$name 是 $value，应当约等于 $scale × $MAX_STRENGTH；" +
                    "这五个档位的取值就是从 tier 1 列的 scale 抄来的，改了它们等于改两层的对应关系",
            ).that(value / MAX_STRENGTH.toFloat()).isWithin(SCALE_TOLERANCE).of(scale)
        }
    }

    // ========================================================================
    // richTapEnvelopeOf：任意长度的逐点包络压成 SDK 固定四点包络
    // ========================================================================

    @Test
    fun `单调上升的包络：第三点贴到末尾前一毫秒`() {
        // 扩散那段：0 一路爬到 0.9，700 ms。峰落在末点，而 SDK 的两条兜底通路都拿
        // max(scales[1], scales[2]) 当整段峰值，所以第三点要尽量贴着末尾才不失真
        val envelope = requireEnvelope(intArrayOf(700), floatArrayOf(0.9f))
        assertThat(envelope.relativeTimeMs.toList()).containsExactly(0, 350, 699, 700).inOrder()
        assertScales("扩散 700 ms", envelope.scales, floatArrayOf(0f, 0.45f, 0.8987143f, 0.9f))
        assertThat(envelope.frequencies.toList()).containsExactly(0, 0, 0, 0)
    }

    @Test
    fun `峰在中间的包络：第二点踩在峰上，第三点取峰与末尾的中点`() {
        // 签名的一记闪：100 ms 冲到满幅，再 100 ms 落回 0
        val envelope = requireEnvelope(intArrayOf(100, 100), floatArrayOf(1f, 0f))
        assertThat(envelope.relativeTimeMs.toList()).containsExactly(0, 100, 150, 200).inOrder()
        assertScales("签名闪 200 ms", envelope.scales, floatArrayOf(0f, 1f, 0.5f, 0f))
    }

    @Test
    fun `22 点单峰包络压成四点，峰值原样落在第二点`() {
        // 彩蛋签名那种逐笔画的包络就是这个量级：22 个控制点，SDK 只吃 4 个
        val timings = IntArray(22) { 10 }
        val amplitudes = floatArrayOf(
            0.05f, 0.1f, 0.2f, 0.3f, 0.4f, 0.5f, 0.6f, 0.7f, 0.8f, 0.9f, 1f,
            0.95f, 0.9f, 0.8f, 0.7f, 0.5f, 0.4f, 0.3f, 0.2f, 0.1f, 0.05f, 0f,
        )
        val envelope = requireEnvelope(timings, amplitudes, "22 点单峰")
        // 峰在第 11 个控制点即 110 ms 处，第三点取 (110 + 220) / 2
        assertThat(envelope.relativeTimeMs.toList()).containsExactly(0, 110, 165, 220).inOrder()
        // 165 ms 落在 0.5 与 0.4 两个控制点正中间，线性插值得 0.45
        assertScales("22 点单峰", envelope.scales, floatArrayOf(0f, 1f, 0.45f, 0f))
        assertWithMessage("峰值必须落在中间两位：兜底通路拿 max(scales[1], scales[2]) 当整段峰值")
            .that(maxOf(envelope.scales[1], envelope.scales[2]))
            .isWithin(TOLERANCE)
            .of(1f)
    }

    @Test
    fun `总时长 3 毫秒是下限：刚好排出四个互不相同的时刻，2 毫秒表达不了`() {
        val envelope = requireEnvelope(intArrayOf(1, 1, 1), floatArrayOf(1f, 0.5f, 0f), "3 ms 下限")
        assertThat(envelope.relativeTimeMs.toList()).containsExactly(0, 1, 2, 3).inOrder()
        assertScales("3 ms 下限", envelope.scales, floatArrayOf(0f, 1f, 0.5f, 0f))
        // 少 1 ms 就排不出四个互不相同的时刻。这条同时钉住 envelopeSampleTimes 里两处 coerceIn
        // 的前提：总时长小于 3 时那两个区间会翻转，coerceIn 直接抛 IllegalArgumentException，
        // 而 playEnvelope 是在主线程上调它的，抛出去就是崩在点击路径上
        assertWithMessage("总时长 2 ms 应当返回 null，而不是抛异常")
            .that(richTapEnvelopeOf(intArrayOf(1, 1), floatArrayOf(1f, 0.5f)))
            .isNull()
    }

    @Test
    fun `总时长 30000 毫秒刚好可用，多 1 毫秒返回 null`() {
        val envelope = requireEnvelope(intArrayOf(15_000, 15_000), floatArrayOf(1f, 0f), "30 秒上限")
        assertThat(envelope.relativeTimeMs.toList())
            .containsExactly(0, 15_000, 22_500, 30_000)
            .inOrder()
        assertWithMessage("总时长 30001 ms 越过上限，应当返回 null")
            .that(richTapEnvelopeOf(intArrayOf(15_000, 15_001), floatArrayOf(1f, 0f)))
            .isNull()
    }

    @Test
    fun `表达不了的输入一律返回 null，不抛异常`() {
        // 返回 null 是「这一层表达不了」的约定信号，上层据此降级；抛异常会被 submit 的
        // catch (Throwable) 吃掉并整层禁用，硬凑一段能播的东西则是拿别的波形假装成功
        val cases = listOf(
            Triple("空数组", IntArray(0), FloatArray(0)),
            Triple("两个数组长度不等", intArrayOf(100, 100), floatArrayOf(1f)),
            Triple("有负的时长", intArrayOf(100, -1), floatArrayOf(0.5f, 1f)),
            Triple("振幅越过 1", intArrayOf(100), floatArrayOf(1.5f)),
            Triple("振幅是负数", intArrayOf(100), floatArrayOf(-0.1f)),
            // NaN 要单独钉：它跟任何数比大小都是 false，所以 `it < 0f || it > 1f` 这一道拦不住它，
            // 后面 peakAmplitudeIndex 的 `>`、`<= 0f` 与 isSingleHumped 的两处比较同样一路放行，
            // 最终插值出一段 scale 含 NaN 的四点包络。SDK 的 playEnvelope 校验 scale >= 0
            // （NaN 不满足）抛 IllegalArgumentException，而那一抛落在 submit 的 catch (Throwable) 里
            // —— 结果不是崩，是 tier 3 整层被永久禁用。两个下标各钉一次：NaN 在首位会被
            // peakAmplitudeIndex 选成峰，在末位则走另一条分支
            Triple("首位振幅是 NaN", intArrayOf(100, 100), floatArrayOf(Float.NaN, 0f)),
            Triple("末位振幅是 NaN", intArrayOf(100, 100), floatArrayOf(1f, Float.NaN)),
            Triple("整段静默", intArrayOf(100, 100), floatArrayOf(0f, 0f)),
            Triple("笔画之间夹静默的双峰", intArrayOf(40, 20, 40), floatArrayOf(1f, 0f, 1f)),
            Triple("到峰之前先回落", intArrayOf(50, 50, 50), floatArrayOf(0.8f, 0.2f, 1f)),
        )
        cases.forEach { (label, timings, amplitudes) ->
            assertWithMessage("$label：这一层表达不了，必须返回 null 让上层降级，且不许抛异常")
                .that(richTapEnvelopeOf(timings, amplitudes))
                .isNull()
        }
    }

    @Test
    fun `凡是能表达的输入，输出都过得了 SDK 那几项校验`() {
        // SDK 只读三个数组的前 4 项（短于 4 直接数组越界），逐项要求非负；
        // 两条兜底通路还拿 relativeTime[3] 当整段时长
        val cases = listOf(
            Triple("单调上升", intArrayOf(700), floatArrayOf(0.9f)),
            Triple("峰在中间", intArrayOf(100, 100), floatArrayOf(1f, 0f)),
            Triple("峰值持平也算单峰", intArrayOf(50, 50, 50), floatArrayOf(0.6f, 0.6f, 0f)),
            Triple("单调下降", intArrayOf(120, 120), floatArrayOf(0.8f, 0.1f)),
            Triple("首段零时长，起手即满幅", intArrayOf(0, 300), floatArrayOf(1f, 0f)),
            Triple("3 毫秒下限", intArrayOf(1, 1, 1), floatArrayOf(1f, 0.5f, 0f)),
        )
        cases.forEach { (label, timings, amplitudes) ->
            val envelope = requireEnvelope(timings, amplitudes, label)
            val times = envelope.relativeTimeMs
            assertWithMessage("$label 的三个数组长度都必须是 $ENVELOPE_POINTS，短了会让 SDK 数组越界")
                .that(listOf(times.size, envelope.scales.size, envelope.frequencies.size))
                .containsExactly(ENVELOPE_POINTS, ENVELOPE_POINTS, ENVELOPE_POINTS)
            assertWithMessage("$label 的四个时刻必须严格递增").that(times.toList()).isInStrictOrder()
            assertWithMessage("$label 的首个时刻必须是 0").that(times.first()).isEqualTo(0)
            assertWithMessage("$label 的末个时刻必须等于总时长，兜底通路拿它当整段时长")
                .that(times.last())
                .isEqualTo(timings.sum())
            assertWithMessage("$label 的频率必须四点全 0，SDK 拒收负数，目标机也没有频率通道")
                .that(envelope.frequencies.toList())
                .containsExactly(0, 0, 0, 0)
            envelope.scales.forEachIndexed { index, scale ->
                assertWithMessage("$label 第 $index 点的 scale 是 $scale，SDK 拒收负数")
                    .that(scale).isAtLeast(0f)
                assertWithMessage("$label 第 $index 点的 scale 是 $scale，越过 1 就超出振幅上限")
                    .that(scale).isAtMost(1f)
            }
        }
    }

    /** 取出必须能表达的那段四点包络；返回 null 就直接判红，省掉每个用例里一处 `!!`。 */
    private fun requireEnvelope(
        timingsMs: IntArray,
        amplitudes: FloatArray,
        label: String = "这段包络",
    ): RichTapEnvelope {
        val envelope = richTapEnvelopeOf(timingsMs, amplitudes)
        assertWithMessage("$label 应当能压成四点包络，实际返回了 null").that(envelope).isNotNull()
        return requireNotNull(envelope)
    }

    /**
     * 逐点比 scale。用容差而不是 `isEqualTo`：插值走的是
     * `previousValue + (value - previousValue) * span / total` 这串 float 乘除，
     * 结果与十进制期望值差得到 1 ulp，精确相等会随编译器的常量折叠时红时绿。
     */
    private fun assertScales(label: String, actual: FloatArray, expected: FloatArray) {
        assertWithMessage("$label 的 scale 应当有 ${expected.size} 点")
            .that(actual.size)
            .isEqualTo(expected.size)
        expected.forEachIndexed { index, value ->
            assertWithMessage("$label 第 $index 点的 scale 应当是 $value，实际是 ${actual[index]}")
                .that(actual[index])
                .isWithin(TOLERANCE)
                .of(value)
        }
    }

    @Test
    fun `档位强度只缩放强度位，效果 ID 与边界都守住`() {
        val base = RichTapEffect(10007, 200)

        assertWithMessage("跟随系统档不干预：原样返回")
            .that(base.atStrength(HapticStrength.SYSTEM))
            .isEqualTo(base)
        assertWithMessage("轻档 ×0.6：200 → 120")
            .that(base.atStrength(HapticStrength.LIGHT))
            .isEqualTo(RichTapEffect(10007, 120))
        assertWithMessage("强档 ×1.3 封顶 255：200 → 260 → 255")
            .that(base.atStrength(HapticStrength.STRONG))
            .isEqualTo(RichTapEffect(10007, 255))

        val feather = RichTapEffect(10004, 76)
        assertWithMessage("轻档触底至少 1：76×0.6=45.6→46 正常；换 1 档也不许缩成 0")
            .that(RichTapEffect(10004, 1).atStrength(HapticStrength.LIGHT).strength)
            .isEqualTo(1)
        assertWithMessage("效果 ID 一个都不许动：动 ID 就是换效果=换性格")
            .that(feather.atStrength(HapticStrength.STRONG).effectId)
            .isEqualTo(10004)
    }

    private companion object {
        /** 语义总数，与设计文档「语义词表」一致。 */
        const val EXPECTED_SEMANTIC_COUNT = 13

        /** `PrebakedEffectId.PREBAKED_ID_MIN`：预置效果区间下界。 */
        const val PREBAKED_ID_MIN = 10001

        /** `PrebakedEffectId.PREBAKED_ID_MAX`：预置效果区间上界，50 个 ID 连续排到这里。 */
        const val PREBAKED_ID_MAX = 10050

        /** `playExtPrebaked` 校验的强度下界。 */
        const val MIN_STRENGTH = 0

        /** `playExtPrebaked` 校验的强度上界，越界抛 `IllegalArgumentException`。 */
        const val MAX_STRENGTH = 255

        /** 四点包络的控制点数，SDK 写死 4：只读前 4 项，短于 4 会越界。 */
        const val ENVELOPE_POINTS = 4

        /** 插值结果的容差，比到小数第五位足够认出算错一档，又躲得开 float 的 1 ulp 误差。 */
        const val TOLERANCE = 1e-5f

        /** 强度梯队对齐 scale 的容差：178 除 255 是 0.698，与 0.7 差 0.002。 */
        const val SCALE_TOLERANCE = 0.005f
    }
}

/**
 * `RichTapBackend.stop()` 的空转线程回归。**纯 JVM**：整组只问 `Context` 一次
 * `applicationContext`，不进 Robolectric 沙箱。
 *
 * 要挡住的缺陷：`stop()` 原先只过 `submit()` 的 `released` 闸、不看有没有真初始化过，
 * 于是本机不支持 RichTap 时照样 `executor.execute` 一次。单线程 executor 的核心线程
 * `allowCoreThreadTimeOut` 是 false，一旦建出来就永久 park 在 `workQueue.take()` 上；
 * 而 backend 是 `@Singleton` 不会被回收、生产上也没人调 `release()`，于是那条任务体恒为
 * 空转的线程再也退不掉。触发路径是现成的：Hilt 模块把 `quietDown` 钩子接成 `richTap.stop()`，
 * 任何非 RichTap 机型上用户把触感开关拨到 `OFF` 就会调进来，彩蛋的三条闸门也会。
 *
 * 两条断言分工：返回值那条钉住「没派发」这个契约信号，线程那条钉住真正的后果 ——
 * 光看返回值的话，把 `submit` 改成恒返 false 也能骗过去。而线程是按名字认的，
 * 所以另配一条正对照：可用形态下构造期的预热必须真的建出一条同名线程，
 * 否则线程名一改，上面那条就变成在空集上通过。
 */
class RichTapStopThreadGuardTest {
    /** backend 只对它调 `applicationContext`，别的 android 方法一个都不碰。 */
    private val context = mockk<Context>()

    @Before
    fun setUp() {
        every { context.applicationContext } returns context
    }

    @After
    fun tearDown() {
        unmockkAll()
    }

    @Test
    fun `整层不可用时 stop 不投任务，也不建出那条永不退出的线程`() {
        // 两种「本层不该上场」的形态各验一次：本机没有 RichTap，以及硬规则 1 的转子马达锁 tier 0
        val shapes = listOf(
            "本机不支持 RichTap" to nonRichTapMachine(),
            "转子马达锁 tier 0" to nonRichTapMachine()
                .copy(hasAmplitudeControl = false, richTapSupported = true),
        )
        shapes.forEach { (label, capabilities) ->
            val before = executorThreads()
            val backend = RichTapBackend(context, capabilities)
            try {
                assertWithMessage("$label：整层本该不可用，否则后面两条断言说明不了任何事")
                    .that(backend.isAvailable())
                    .isFalse()
                assertWithMessage("$label：从没 init 成功过，stop() 必须直接返回 false，一个任务都不投")
                    .that(backend.stop())
                    .isFalse()
                // 闸门是「有没有 init 成功过」而不是一次性闩，反复调也不该开始投任务
                assertThat(backend.stop()).isFalse()
                assertWithMessage(
                    "$label：stop() 新建出了 $EXECUTOR_THREAD_NAME 线程。它 park 在 " +
                        "workQueue.take() 上永不退出，而 backend 是 @Singleton、生产上没人调 " +
                        "release()，于是每台非 RichTap 机型都白挂一条恒空转的线程",
                )
                    .that(executorThreads() - before)
                    .isEmpty()
            } finally {
                backend.release()
            }
        }
    }

    @Test
    fun `正对照：可用形态下构造期的预热真会建出同名线程`() {
        // 上一条按线程名认线程，名字一改它就成了在空集上通过。这条把名字钉住：available 为 true
        // 时构造期就 submit 一次预热，executor.execute 会同步 start 出核心线程。预热任务本身在
        // 纯 JVM 上必然抛（RichTapUtils.init 要真 Context），但 submit 的 catch (Throwable)
        // 吃掉之后线程照样留着 —— 那正是上一条要防的东西
        val before = executorThreads()
        val backend = RichTapBackend(context, nonRichTapMachine().copy(richTapSupported = true))
        try {
            assertWithMessage(
                "可用形态下构造期没建出 $EXECUTOR_THREAD_NAME 线程：线程名或 executor 形状变了，" +
                    "上一条用例已经失去意义",
            )
                .that(awaitNewExecutorThread(before))
                .isNotEmpty()
        } finally {
            backend.release()
        }
    }

    /**
     * 目标机那一类的能力快照，只是 [HapticCapabilities.richTapSupported] 为 false ——
     * 也就是「非 RichTap 机型」这一大类，缺陷正是在这类机器上白挂线程。
     */
    private fun nonRichTapMachine() = HapticCapabilities(
        hasVibrator = true,
        hasAmplitudeControl = true,
        supportedPrimitives = emptySet(),
        compositionSizeMax = 0,
        envelopeSupported = false,
        envelopeMaxSize = 0,
        richTapSupported = false,
        hapticPlayerSupported = false,
        miuiSupported = true,
        oplusSupported = false,
    )

    /**
     * 当前活着的、名字是 [EXECUTOR_THREAD_NAME] 的线程。
     *
     * 按对象身份存（`Thread` 没重写 `equals`），用例只比增量：同一个 JVM 里可能还留着别处
     * 刚 shutdown、尚未退干净的同名线程，比绝对数量会时红时绿。
     */
    private fun executorThreads(): Set<Thread> =
        Thread.getAllStackTraces().keys.filterTo(mutableSetOf()) { it.name == EXECUTOR_THREAD_NAME }

    /**
     * 等新的那条线程出现。`executor.execute` 是同步 `start()` 的，正常第一轮就拿到；
     * 留个短轮询只为躲开线程刚 start 那一瞬的可见性抖动，拿不到才是真的没建。
     */
    private fun awaitNewExecutorThread(before: Set<Thread>): Set<Thread> {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(SPAWN_TIMEOUT_SECONDS)
        var spawned = executorThreads() - before
        while (spawned.isEmpty() && System.nanoTime() < deadline) {
            Thread.sleep(SPAWN_POLL_MS)
            spawned = executorThreads() - before
        }
        return spawned
    }

    private companion object {
        /** backend 那条单线程的名字。照实现里的 `EXECUTOR_THREAD_NAME` 抄成字面量：那边是 private。 */
        const val EXECUTOR_THREAD_NAME = "haptic-richtap"

        /** 正对照等线程出现的上限，正常远快于此。 */
        const val SPAWN_TIMEOUT_SECONDS = 5L

        /** 轮询间隔。 */
        const val SPAWN_POLL_MS = 20L
    }
}
