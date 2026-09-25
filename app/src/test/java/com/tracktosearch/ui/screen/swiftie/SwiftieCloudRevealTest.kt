package com.tracktosearch.ui.screen.swiftie

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * AGSL 是形状系数的第三份抄本，编译器和单测都看不见它里面的算术。
 * 这条测「抄没抄错」，不是「对不对」—— 对不对由 SwiftieEggControllerTest 的
 * 多项式等价测负责（那份只比两份 Kotlin，碰不到这里）。改了常量表没同步改 AGSL，这里红。
 *
 * 钉的是**整行表达式**，不是系数子串。曾经只钉子串，结果 AGSL 把 `sin3θ` 当 `s³`
 * 喂给 sin5θ 的展开式、lobe 值域从 [-0.43,0.69] 炸到 [-4.80,5.03]，四个系数子串
 * 却一个不少，全套测全绿。只比数值抓不到变量接错。
 */
class SwiftieCloudRevealTest {

    @Test
    fun revealAgsl_carriesTheSameLobeCoefficientsAsKotlin() {
        assertThat(REVEAL_AGSL).contains("return 0.34 * l2 + 0.22 * l3 + 0.14 * l5")
        assertThat(REVEAL_AGSL).contains("-0.32329 * sin3 + 0.94630 * cos3")
        assertThat(REVEAL_AGSL).contains("0.764842 * sin5 + 0.644218 * cos5")
    }

    @Test
    fun revealAgsl_keepsPowersSeparateFromMultipleAngleValues() {
        // 事故回归测：sin5θ = 16s⁵-20s³+5s 要的是 s 的三次幂，不是 sin3θ。
        // 幂次必须先有自己的变量，倍角值另算，且五次项只能用幂次变量拼。
        assertThat(REVEAL_AGSL).contains("float s2 = s * s")
        assertThat(REVEAL_AGSL).contains("float s3 = s2 * s")
        assertThat(REVEAL_AGSL).contains("float c2 = c * c")
        assertThat(REVEAL_AGSL).contains("float c3 = c2 * c")
        assertThat(REVEAL_AGSL).contains("float sin3 = 3.0 * s - 4.0 * s3")
        assertThat(REVEAL_AGSL).contains("float cos3 = 4.0 * c3 - 3.0 * c")
        assertThat(REVEAL_AGSL).contains("float sin5 = 16.0 * s3 * s2 - 20.0 * s3 + 5.0 * s")
        assertThat(REVEAL_AGSL).contains("float cos5 = 16.0 * c3 * c2 - 20.0 * c3 + 5.0 * c")
        // 反向兜底：一旦有人把倍角值直接当幂次传进五次项，上面两条整行断言就会因为
        // 右边式子变化而红；这条把「sin3 不得出现在五次项里」也钉死。
        val sin5Line = REVEAL_AGSL.lineSequence().first { it.contains("float sin5") }
        val cos5Line = REVEAL_AGSL.lineSequence().first { it.contains("float cos5") }
        assertThat(sin5Line).doesNotContain("sin3")
        assertThat(cos5Line).doesNotContain("cos3")
    }

    @Test
    fun revealAgsl_multipliesRadiusByWiggleAndFeathersAsymmetrically() {
        // 铺满判据与不对称羽化都只活在 AGSL 里，改错方向不会有 Kotlin 测变红
        assertThat(REVEAL_AGSL).contains("edge = uRadius * (1.0 + uWiggle * revealLobe")
        assertThat(REVEAL_AGSL).contains("float lo = edge - uFeather")
        assertThat(REVEAL_AGSL).contains("float hi = edge + 0.35 * uFeather")
    }

    @Test
    fun revealAgsl_scalesWholePremultipliedColourAndStaysInFloat() {
        // 预乘 alpha：只能整色乘 a，不能只动第四个分量
        assertThat(REVEAL_AGSL).contains("float4 src = float4(content.eval(coord))")
        assertThat(REVEAL_AGSL).contains("return half4(src * a)")
        // 本仓库唯一先例（SwiftieSnowGlobe）实测过：eval 返回 half，half 与 float 混算
        // 各家厂商编译器宽严不一。编不过不会抛给任何人看，只是 runCatching 静默退回
        // 硬边裁切 —— 羽化消失而 Kotlin 侧一条测都不会红。所以不许裸混算。
        assertThat(REVEAL_AGSL).doesNotContain("return content.eval(coord) * a")
        assertThat(REVEAL_AGSL).doesNotContain("return float4(0.0, 0.0, 0.0, 0.0)")
    }

    @Test
    fun revealAgsl_outerBandRatioStillEqualsTheKotlinConstant() {
        // 外沿带占比在 Kotlin 侧的唯一真值是 REVEAL_OUTER_BAND_RATIO；着色器字符串里
        // 写不进 Kotlin 常量，只能抄字面量。把那个数抠出来跟常量对一次，改常量忘了同步
        // 就在这里红 —— 否则表现是最后一帧四角留一圈半透明的边，而所有算术测全绿。
        val literal = REVEAL_AGSL
            .substringAfter("float hi = edge + ")
            .substringBefore(" * uFeather")
        assertThat(literal.toFloat())
            .isWithin(1e-6f).of(SwiftieEggController.REVEAL_OUTER_BAND_RATIO)
    }

    @Test
    fun revealAgsl_callsNoBuiltinWithoutRepoPrecedent() {
        // 本仓库唯一 AGSL 先例（SwiftieSnowGlobe 的 GLASS_AGSL）实证过的内建函数只有
        // length/sqrt/max/min/pow 与 shader.eval。atan 与 smoothstep 无先例可依证
        // （Skia 文档当前取不到），所以 θ 走倍角多项式、插值手写 Hermite。
        // 有人「顺手优化回去」时这里红：真机上不会抛异常给任何人看，只是 RuntimeShader
        // 编不过 → runCatching 静默退回硬边裁切 → 羽化消失，Kotlin 侧一条测都不会变红。
        assertThat(REVEAL_AGSL).doesNotContain("atan")
        assertThat(REVEAL_AGSL).doesNotContain("smoothstep(")
    }
}
