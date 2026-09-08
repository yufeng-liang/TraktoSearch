package com.tracktosearch.ui.haptic.backend

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * [HapticPlayerBackend] 里 HE 转换的纯函数单测：控制点台阶 → [HePattern] → JSON。
 *
 * HE 解析器在 ROM 侧**不报错只静默丢弃**（`DynamicEffect.create` 只存字符串，解析在
 * `start()` 里失败也不抛回调用方），所以合规性全靠 `hapticPlayerPatternOf` 在生成侧
 * 挡住 —— 这组测试钉的就是那些「破了也不崩、只是马达悄悄不震」的规则：
 *
 * - 曲线首点 `{Time:0, Intensity:0}`、末点 `{Time:Duration, Intensity:0}`（真机实测缺了整条作废）
 * - 曲线至少 4 点（真机实测 3 点报 `Bad point num` 被丢）
 * - 曲线点 Intensity 是 0..1 的乘数（真机实测传 60 按越界丢弃）
 * - event ≤ 16、单 event ≤ 5000 ms、总长 ≤ 50 s
 *
 * 真机验证记录（小米 14 Pro / HyperOS 3，2026-09-08）：两笔画包络实播成功，
 * logcat `AacRichTapConvert: he_event_num:2, f0:135Hz`、总时长分毫不差。
 */
class HapticPlayerMappingTest {

    @Test
    fun `单峰上升包络转成一个 event，曲线首末归零`() {
        // 扩散吞屏的形状：700ms 切 7 格，从 0.9/7 线性爬到 0.9
        val timings = IntArray(7) { 100 }
        val amplitudes = FloatArray(7) { 0.9f * (it + 1) / 7f }

        val pattern = hapticPlayerPatternOf(timings, amplitudes)

        assertThat(pattern).isNotNull()
        val events = pattern!!.events
        assertThat(events).hasSize(1)
        val event = events.single()
        assertThat(event.relativeTimeMs).isEqualTo(0)
        assertThat(event.durationMs).isEqualTo(700)
        assertThat(event.intensity).isEqualTo(90)
        assertThat(event.curve).hasSize(9) // 7 个中间点 + 强制首末归零
        assertThat(event.curve.first().timeMs).isEqualTo(0)
        assertThat(event.curve.first().intensity).isEqualTo(0f)
        assertThat(event.curve.last().timeMs).isEqualTo(700)
        assertThat(event.curve.last().intensity).isEqualTo(0f)
        // 中间点按占峰值比例缩放：首格 1/7、末格 7/7
        assertThat(events.single().curve[1].intensity).isWithin(0.001f).of(1f / 7f)
        assertThat(events.single().curve[7].intensity).isWithin(0.001f).of(1f)
    }

    @Test
    fun `签名段形态：多笔画多间隙转成等量 event，间隙即静默`() {
        // 两笔各 400ms，中间 500ms 抬笔 —— 手写包络的缩影
        val timings = intArrayOf(400, 500, 400)
        val amplitudes = floatArrayOf(0.25f, 0f, 0.25f)

        val pattern = hapticPlayerPatternOf(timings, amplitudes)

        assertThat(pattern).isNotNull()
        val events = pattern!!.events
        assertThat(events).hasSize(2)
        assertThat(events[0].relativeTimeMs).isEqualTo(0)
        assertThat(events[1].relativeTimeMs).isEqualTo(900) // 400 + 500
        events.forEach { event ->
            assertThat(event.durationMs).isEqualTo(400)
            assertThat(event.intensity).isEqualTo(25)
            // 单点等幅段：1/3、2/3 两个补出的峰值点 + 首末归零，共 4 点
            assertThat(event.curve).hasSize(4)
            assertThat(event.curve.map { it.timeMs }).containsExactly(0, 133, 266, 400).inOrder()
            assertThat(event.curve[1].intensity).isWithin(0.001f).of(1f)
            assertThat(event.curve[2].intensity).isWithin(0.001f).of(1f)
        }
    }

    @Test
    fun `多峰不归零的连续曲线合成单个 event，中间点抽稀到上限内`() {
        // 摆动余震的形状：17 个采样点全程非零（|sin| 采样不过零），1700ms
        val timings = IntArray(17) { 100 }
        val amplitudes = FloatArray(17) { 0.2f + 0.6f * (it % 4) / 3f }

        val pattern = hapticPlayerPatternOf(timings, amplitudes)

        assertThat(pattern).isNotNull()
        val events = pattern!!.events
        assertThat(events).hasSize(1)
        val curve = events.single().curve
        assertThat(curve.size).isAtMost(HeLimits.MAX_CURVE_POINTS)
        assertThat(curve.size).isAtLeast(HeLimits.MIN_CURVE_POINTS)
        assertThat(curve.first().intensity).isEqualTo(0f)
        assertThat(curve.last().timeMs).isEqualTo(1700)
        assertThat(curve.last().intensity).isEqualTo(0f)
        // 抽稀后时刻仍严格递增（重复时刻会被 ROM 侧判乱序）
        assertThat(curve.map { it.timeMs }.zipWithNext().all { (a, b) -> a < b }).isTrue()
    }

    @Test
    fun `超长段拆成多个 event，各段不超上限且时刻连续`() {
        // 6200ms 的连续非零段：HE 单 event 上限 5000ms，必须拆
        val timings = intArrayOf(3100, 3100)
        val amplitudes = floatArrayOf(0.5f, 0.5f)

        val pattern = hapticPlayerPatternOf(timings, amplitudes)

        assertThat(pattern).isNotNull()
        val events = pattern!!.events
        assertThat(events.size).isAtLeast(2)
        events.forEach { assertThat(it.durationMs).isAtMost(HeLimits.MAX_EVENT_DURATION_MS) }
        // 拆分后仍然首尾相接覆盖整段
        val end = events.last().relativeTimeMs + events.last().durationMs
        assertThat(end).isEqualTo(6200)
        assertThat(events.first().relativeTimeMs).isEqualTo(0)
    }

    @Test
    fun `非法入参一律返回 null 让引擎降级`() {
        // 空、不等长、非正时长、振幅越界、NaN、全静默
        assertThat(hapticPlayerPatternOf(IntArray(0), FloatArray(0))).isNull()
        assertThat(hapticPlayerPatternOf(intArrayOf(100), floatArrayOf(0.5f, 0.5f))).isNull()
        assertThat(hapticPlayerPatternOf(intArrayOf(0, 100), floatArrayOf(0.5f, 0.5f))).isNull()
        assertThat(hapticPlayerPatternOf(intArrayOf(100), floatArrayOf(1.5f))).isNull()
        assertThat(hapticPlayerPatternOf(intArrayOf(100), floatArrayOf(-0.1f))).isNull()
        assertThat(hapticPlayerPatternOf(intArrayOf(100), floatArrayOf(Float.NaN))).isNull()
        assertThat(hapticPlayerPatternOf(intArrayOf(100, 100), floatArrayOf(0f, 0f))).isNull()
    }

    @Test
    fun `run 数超过 16 或总长超过 50 秒返回 null`() {
        // 17 个 run 要 34 个控制点（run 与间隙交替），不是 17 个点
        val manyRunsTimings = IntArray((HeLimits.MAX_EVENTS + 1) * 2) { 50 }
        val manyRunsAmplitudes = FloatArray((HeLimits.MAX_EVENTS + 1) * 2) {
            if (it % 2 == 0) 0.5f else 0f
        }
        assertThat(hapticPlayerPatternOf(manyRunsTimings, manyRunsAmplitudes)).isNull()

        assertThat(hapticPlayerPatternOf(intArrayOf(HeLimits.MAX_TOTAL_MS + 1), floatArrayOf(0.5f)))
            .isNull()
    }

    @Test
    fun `JSON 渲染满足 HE V1 的字面规则`() {
        val pattern = hapticPlayerPatternOf(
            intArrayOf(400, 500, 400),
            floatArrayOf(0.25f, 0f, 0.25f),
        )!!

        val json = heJsonOf(pattern)

        // Version 是整数 1（真机实测字符串 "1.0" 会解析失败）
        assertThat(json).contains("\"Version\":1")
        assertThat(json).contains("\"Pattern\":[")
        assertThat(json).contains("\"Type\":\"continuous\"")
        // 两个 event
        assertThat(json).contains("\"RelativeTime\":0")
        assertThat(json).contains("\"RelativeTime\":900")
        // 曲线点 Intensity 是 0..1 的小数，四位定点、无科学计数法
        assertThat(json).contains("\"Intensity\":0.0000")
        assertThat(json).contains("\"Intensity\":1.0000")
        assertThat(json).doesNotContain("E-")
        // 顶层 Parameters.Intensity 是 0..100 的整数
        assertThat(json).contains("\"Parameters\":{\"Intensity\":25,\"Frequency\":50")
    }
}
