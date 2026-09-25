package com.tracktosearch.ui.screen.swiftie

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * AGSL 是形状系数的第三份抄本，编译器和单测都看不见它里面的算术。
 * 这条测「抄没抄错」，不是「对不对」—— 对不对由 SwiftieEggControllerTest 的
 * 多项式等价测负责。改了常量表没同步改 AGSL，这里红。
 */
class SwiftieCloudRevealTest {

    @Test
    fun revealAgsl_carriesTheSameLobeCoefficientsAsKotlin() {
        assertThat(REVEAL_AGSL).contains("0.34 * l2")
        assertThat(REVEAL_AGSL).contains("0.22 * l3")
        assertThat(REVEAL_AGSL).contains("0.14 * l5")
        assertThat(REVEAL_AGSL).contains("-0.32329")   // cos(1.9)
        assertThat(REVEAL_AGSL).contains("0.94630")    // sin(1.9)
        assertThat(REVEAL_AGSL).contains("0.764842")   // cos(0.7)
        assertThat(REVEAL_AGSL).contains("0.644218")   // sin(0.7)
    }

    @Test
    fun revealAgsl_multipliesRadiusByWiggleAndFeathersAsymmetrically() {
        // 铺满判据与不对称羽化都只活在 AGSL 里，改错方向不会有 Kotlin 测变红
        assertThat(REVEAL_AGSL).contains("1.0 + uWiggle")
        assertThat(REVEAL_AGSL).contains("edge - uFeather")
        assertThat(REVEAL_AGSL).contains("0.35 * uFeather")
        // 预乘 alpha：只能整色乘 a，不能只动第四个分量
        assertThat(REVEAL_AGSL).contains("content.eval(coord) * a")
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
