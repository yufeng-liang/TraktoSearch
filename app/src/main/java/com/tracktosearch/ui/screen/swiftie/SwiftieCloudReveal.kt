package com.tracktosearch.ui.screen.swiftie

import android.graphics.RenderEffect
import android.graphics.RuntimeShader
import android.os.Build
import androidx.annotation.RequiresApi
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.isSpecified
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.asComposeRenderEffect
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.sin

/**
 * 揭示遮罩的 AGSL。
 *
 * **只依赖 `length`、`min`/`max` 与算术，不调任何三角函数、不用 smoothstep**：本仓库唯一的
 * AGSL 先例是 `SwiftieSnowGlobe.kt:332`，穷举它真正调用过的内建函数只有
 * length/sqrt/max/min/pow 与 `shader.eval`（外加 `float2`/`float4`/`half4` 构造器），
 * `atan` 与 `smoothstep` 在本仓库无可依证的先例（Skia 文档当前取不到），所以
 * θ 用倍角多项式、插值手写 Hermite。形状函数与 Kotlin 侧的等价由单测钉住。
 *
 * 形状系数在这里是**第三份抄本**（前两份是 `SwiftieEggController` 的三角形式与倍角
 * 多项式形式）。着色器字符串里写不进 Kotlin 常量，所以 `0.34`/`0.22`/`0.14`、
 * `cosφ`/`sinφ` 四个数与外沿带占比 `0.35` 都是字面量：改常量表必须同步改这里。
 * 漏改不会让算术测变红，只有 `SwiftieCloudRevealTest` 的字符串断言会 —— 而它只有
 * `0.35` 那一条是从 `SwiftieEggController.REVEAL_OUTER_BAND_RATIO` 抠出来对比的，
 * 其余全是字面量对字面量。这份抄本已经出过一次事故（见 `revealLobe` 里的幂次注释），
 * 所以那条测钉的是**整行表达式**而不是系数子串：只比数值抓不到变量接错。
 */
internal const val REVEAL_AGSL = """
uniform shader content;
uniform float2 uCenter;
uniform float uRadius;
uniform float uWiggle;
uniform float uFeather;

// c = dx/r 即 cosθ、s = dy/r 即 sinθ，倍角全是多项式；
// sin(kθ+φ) = cosφ·sin(kθ) + sinφ·cos(kθ)，两个 φ 的 cos/sin 烤成常量。
// 幂次（s2/s3）与倍角值（sin3/cos3）必须是两组不同的变量：sin5θ = 16s⁵-20s³+5s
// 要的是 s³，拿 sin3θ = 3s-4s³ 去顶会让 lobe 值域从 [-0.43,0.69] 炸到 [-4.80,5.03]，
// 起手帧半径系数直接变负 —— 遮罩自交成一圈带尖内凹的海星。这份抄本出过这个事故。
float revealLobe(float c, float s) {
    float s2 = s * s;
    float s3 = s2 * s;
    float c2 = c * c;
    float c3 = c2 * c;
    float l2 = 2.0 * c * s;
    float sin3 = 3.0 * s - 4.0 * s3;
    float cos3 = 4.0 * c3 - 3.0 * c;
    float l3 = -0.32329 * sin3 + 0.94630 * cos3;
    float sin5 = 16.0 * s3 * s2 - 20.0 * s3 + 5.0 * s;
    float cos5 = 16.0 * c3 * c2 - 20.0 * c3 + 5.0 * c;
    float l5 = 0.764842 * sin5 + 0.644218 * cos5;
    return 0.34 * l2 + 0.22 * l3 + 0.14 * l5;
}

half4 main(float2 coord) {
    float2 d = coord - uCenter;
    float r = length(d);
    // 起点主半径不足一像素：整层透明。铺满了就不会走到这里（Kotlin 侧已摘掉 effect）
    if (uRadius < 0.5) { return half4(0.0, 0.0, 0.0, 0.0); }
    float edge = uRadius;
    if (uWiggle > 0.0 && r > 0.5) {
        edge = uRadius * (1.0 + uWiggle * revealLobe(d.x / r, d.y / r));
    }
    // 手写 smoothstep。不对称：内沿（已揭开一侧）带宽 W，外沿只 0.35W ——
    // 外沿那边是还没揭开的搜索页，拖长了会把搜索页糊进边界。
    float lo = edge - uFeather;
    float hi = edge + 0.35 * uFeather;
    float span = max(hi - lo, 0.0001);
    float t = min(max((r - lo) / span, 0.0), 1.0);
    float a = 1.0 - t * t * (3.0 - 2.0 * t);
    // 一律 float 运算、最后一步才收 half：content.eval() 返回 half，half 与 float
    // 混算各家厂商编译器宽严不一（见 SwiftieSnowGlobe 的同款处理）。
    // 层内是预乘 alpha，整个颜色乘 a 等价于 DstIn 一个白色 alpha=a 的遮罩。
    // 写成 half4(rgb, a*a) 会把已预乘的 rgb 再除回去又乘一次，边缘出一圈暗边。
    float4 src = float4(content.eval(coord));
    return half4(src * a);
}
"""

/** 羽化带宽上限。28dp 在 1080p 上约 77px，够软又不糊成一坨。 */
val CloudRevealFeatherDefault: Dp = 28.dp

/**
 * 以 [originInLayer] 为中心、向四周不均匀扩张地把整层揭示出来。
 *
 * [originInLayer] 与 [progress] 都是 lambda，值只在绘制阶段读 —— 每帧只失效 draw，
 * 不把整棵子树重组一遍（同 `SwiftieDiffusion` 的 origin/progress 约定）。原点
 * [Offset.Unspecified] 时退回画面中心。
 *
 * [useShader] 为假时走硬边 clipPath：形状、时长、节奏全一样，只少羽化。刻意不给
 * 低版本写第二套软边实现，理由同 `SwiftieSnowGlobe.kt:533-539`。
 *
 * **`progress()` 不许产 NaN**。[enabled] 为假时原样返回 `this`，一个修饰符都不加。
 * 下游 `revealBaseRadius` / `revealFeatherPx` / `revealWiggle` 里的
 * `coerceIn(0f, 1f)` **压不住 NaN**：NaN 与 0f、1f 两个比较都为 false，按 `coerceIn`
 * 的实现落到**上限 1f**，也就是 `eased = NaN` 会被当成末帧直接全展开（fail-open）。
 * 这是刻意的取舍 —— 闪一下全揭示，比在屏幕上永久留一层半透明遮罩更可取 ——
 * 但它不是钳位，别把它当护栏：`progress()` 自己得保证分母非零。
 */
@Composable
fun Modifier.swiftieCloudReveal(
    originInLayer: () -> Offset,
    progress: () -> Float,
    enabled: Boolean,
    featherDp: Dp,
    useShader: Boolean
): Modifier {
    if (!enabled) return this
    val shaderCapable = useShader && Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU
    return if (shaderCapable) {
        revealWithShader(originInLayer, progress, featherDp)
    } else {
        revealWithClip(originInLayer, progress)
    }
}

@RequiresApi(Build.VERSION_CODES.TIRAMISU)
@Composable
private fun Modifier.revealWithShader(
    originInLayer: () -> Offset,
    progress: () -> Float,
    featherDp: Dp
): Modifier {
    // RuntimeShader 的构造函数就把 AGSL 编译了，所以只建一次，编译代价落在挂载那一帧。
    // runCatching 兜的是厂商差异：编不过就退回硬边裁切，而不是让揭示崩在用户脸上。
    val shader = remember { runCatching { RuntimeShader(REVEAL_AGSL) }.getOrNull() }
    if (shader == null) return revealWithClip(originInLayer, progress)
    // 唯一的 dp→px 换算就在这一行。往下 revealFeatherPx / revealBaseRadius 收的
    // 全是**像素**：把 28f 当 dp 字面量直接传，高密度屏上羽化带会窄 2–3 倍。
    val featherCap = with(LocalDensity.current) { featherDp.toPx() }
    return graphicsLayer {
        val p = progress().coerceIn(0f, 1f)
        renderEffect = if (p >= 1f) {
            // 铺满了就整个摘掉：这一层要挂 126s，继续挂等于每帧白付一次全屏离屏合成
            null
        } else {
            val raw = originInLayer()
            val center = if (raw.isSpecified) {
                raw
            } else {
                Offset(size.width / 2f, size.height / 2f)
            }
            val maxCorner = maxCornerDistance(center, size.width, size.height)
            val feather = SwiftieEggController.revealFeatherPx(maxCorner, p, featherCap)
            shader.setFloatUniform("uCenter", center.x, center.y)
            shader.setFloatUniform("uRadius",
                SwiftieEggController.revealBaseRadius(maxCorner, featherCap, p))
            shader.setFloatUniform("uWiggle", SwiftieEggController.revealWiggle(p))
            shader.setFloatUniform("uFeather", feather)
            // uniform 每帧要换，而 RenderEffect 不可变 —— 只能每帧新建一个。
            // 这只遮罩只活 520ms，同 SwiftieSnowGlobe.kt:567 的取舍
            RenderEffect.createRuntimeShaderEffect(shader, "content").asComposeRenderEffect()
        }
    }
}

/** 原点到四角的最远距离。扩到这个半径才算真铺满，角上不留缺口。 */
private fun maxCornerDistance(origin: Offset, width: Float, height: Float): Float =
    hypot(max(origin.x, width - origin.x), max(origin.y, height - origin.y))

private fun Modifier.revealWithClip(
    originInLayer: () -> Offset,
    progress: () -> Float
): Modifier = drawWithContent {
    val p = progress().coerceIn(0f, 1f)
    if (p <= 0f) return@drawWithContent            // 什么都还没揭开
    if (p >= 1f) { drawContent(); return@drawWithContent }
    val raw = originInLayer()
    val center = if (raw.isSpecified) raw else Offset(size.width / 2f, size.height / 2f)
    val maxCorner = maxCornerDistance(center, size.width, size.height)
    clipPath(
        cloudRevealStarPath(
            center = center,
            // 硬边路径没有羽化带，所以铺满判据只要 maxCorner：feather 实参传 0f。
            // 与 AGSL 路径传 featherCap 的区别是刻意的，统一成一个值会让最后一帧
            // 留一圈裁不尽的边。
            base = SwiftieEggController.revealBaseRadius(maxCorner, 0f, p),
            wiggle = SwiftieEggController.revealWiggle(p)
        )
    ) {
        this@drawWithContent.drawContent()
    }
}

/** 把 r(θ) 采成闭合星形 Path。72 段在全屏跨度下看不出折角。 */
private fun cloudRevealStarPath(center: Offset, base: Float, wiggle: Float): Path {
    val steps = 72
    val twoPi = 2f * PI.toFloat()
    return Path().apply {
        for (i in 0 until steps) {
            val theta = i * twoPi / steps
            val r = base * (1f + wiggle * SwiftieEggController.revealLobeSum(theta))
            val x = center.x + r * cos(theta)
            val y = center.y + r * sin(theta)
            if (i == 0) moveTo(x, y) else lineTo(x, y)
        }
        close()
    }
}
