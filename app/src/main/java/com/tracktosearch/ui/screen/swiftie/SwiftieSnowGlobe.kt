package com.tracktosearch.ui.screen.swiftie

import android.graphics.RenderEffect
import android.graphics.RuntimeShader
import android.os.Build
import androidx.annotation.RequiresApi
import androidx.compose.animation.core.EaseInCubic
import androidx.compose.animation.core.EaseOutBack
import androidx.compose.animation.core.EaseOutCubic
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.asComposeRenderEffect
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.sin
import kotlin.random.Random

private const val TAU = 2f * PI.toFloat()

// ─────────────────────────── 分拍 ───────────────────────────
// 只有「每一拍多长」是本文件的常量，「哪一刻」全部由 SwiftieTimeline 加偏移算出来。
// 这样配乐换了（TOTAL_MS / REWIND_START / LOVER_BLOOM_END 变），球自己会跟着挪。

/** 玻璃球成型 + 小卡转体。与 `SwiftieErasStage.GLOBE_FORM_MS` 必须同长（轴按它让位）。 */
private const val FORM_MS: Long = 900L

/** 金箔雪开洒 + Lover House 后景淡入。 */
private const val SNOW_MS: Long = 900L

/** 心升起 → 灌满 → 呼吸一次。 */
private const val HEART_MS: Long = 800L

/** 引子：球成型前最后 200ms，先在满半径上浮一道极淡的高光弧。 */
private const val PRELUDE_MS: Long = 200L

private val FORM_START: Long = SwiftieTimeline.LOVER_BLOOM_START
private val SNOW_START: Long = FORM_START + FORM_MS
private val HEART_START: Long = SNOW_START + SNOW_MS
private val SEAL_START: Long = HEART_START + HEART_MS

/**
 * 印章那一拍的长度是**减出来的**，不另立常量。
 *
 * 前四拍相加再补上这一拍必须正好落在 [SwiftieTimeline.LOVER_BLOOM_END] 上 ——
 * 配乐若挪动，伸缩的只会是这一拍，印章不会被推进淡出段里去
 * （和 `SwiftieTimeline.FINAL_HOLD_MS` 同一套路：弹性留给最后一段）。
 */
private val SEAL_MS: Long = SwiftieTimeline.LOVER_BLOOM_END - SEAL_START

/**
 * 引子起点。落在倒滑段（[SwiftieTimeline.REWIND_START] 起）的尾巴上，
 * 那时候画面里只有正在卷收的小卡，一道无源的光弧刚好当"要发生什么"的预告。
 */
private val PRELUDE_START: Long = FORM_START - PRELUDE_MS

// ─────────────────────────── 尺寸 ───────────────────────────
// 一切几何都按直径 D 或半径 R 折算，没有一个绝对 dp。改直径不用重排任何一条线。

/**
 * 直径上限。屏宽 360dp 减去调用方两侧各 20dp 的 padding = 320dp。
 *
 * 旧的"绽放"是把 Lover 卡片 `graphicsLayer` 放大 1.5 倍，卡片左沿被推出屏幕、
 * 标题的 L 和日期的 2 被切掉。这里换成先量再算：**任何东西都不靠放大变大**。
 */
private val BALL_MAX_DIAMETER = 320.dp

/**
 * 球内留给小卡的宽度。
 *
 * 圆内那块可用矩形其实更宽（半高 55dp 处的弦长约 0.94×直径），0.70 是刻意收窄的余量 ——
 * 剩下的那圈交给玻璃厚度和折射，卡片贴着球缘会被色散糊掉边。
 */
private const val INNER_WIDTH_RATIO = 0.70f

/** 底座高。 */
private const val BASE_HEIGHT_RATIO = 0.17f

/** 座顶唇口盖住球下缘的量：玻璃要嵌进凹槽，不能搁在座上面。 */
private const val BASE_LIFT_RATIO = 0.055f

/** 球 + 底座的总高，用来反推"高度这边放得下多大的球"。 */
private const val STAGE_HEIGHT_RATIO = 1f + BASE_HEIGHT_RATIO - BASE_LIFT_RATIO

private const val BASE_BODY_WIDTH_RATIO = 0.76f
private const val BASE_SOCKET_WIDTH_RATIO = 0.70f

/** 印章可见字高占底座高的比例。320dp 的球折算下来约 20dp。 */
private const val SEAL_GLYPH_RATIO = 0.37f

// ─────────────────────────── 运动 ───────────────────────────

/** 小卡转体的峰值角度。−12° 是"看得出在转"与"字还读得出来"的交界。 */
private const val CARD_TURN_DEG = 12f

/** 转体的相机距离。默认 8 在 224dp 宽的卡上透视太狠，梯形变形会盖过转体本身。 */
private const val CARD_CAMERA_DISTANCE = 14f

/** 球自转的相机距离。球是圆的，透视强一点反而更有体积。 */
private const val GLOBE_CAMERA_DISTANCE = 10f

/** 自转幅度：只摆不整圈。转过去球内的小卡就侧成一条线了。 */
private const val SWING_DEG = 6f

/** 自转周期 8s，慢到"要盯着才看得出在动"。 */
private const val SWING_PERIOD_MS = 8_000f

/** 自转振幅的起步斜坡：sin 在 t=0 处角速度最大，直接给满会顿一下。 */
private const val SWING_RAMP_MS = 800L

/** 淡出段整只球上浮的距离，占舞台高度的比例。 */
private const val FADE_RISE_RATIO = 0.30f

// ─────────────────────── 玻璃（AGSL uniform）───────────────────────

/** 凸透镜强度：球心附近等效放大 1/(1−0.17) ≈ 1.20 倍。 */
private const val GLASS_BULGE = 0.17f

/** 色散：球缘三通道最大错开 0.9% 半径（320dp 球 ≈ 1.4dp），只够吐出一圈极细彩边。 */
private const val GLASS_DISPERSION = 0.009f

/** 菲涅尔增亮上限。再高球缘就发白，玻璃变成塑料。 */
private const val GLASS_RIM = 0.40f

// ─────────────────────── 金箔雪 ───────────────────────

/** 箔片数量。压到 16 是因为"能认出是箔片"比"多"重要；低端机再砍半。 */
private const val FLAKE_COUNT = 16

/** 内壁判定半径：箔片飘到这里就改沿弧线滑。 */
private const val FLAKE_WALL_RATIO = 0.90f

/** 越壁多少就沿弧转多少（弧度增益）与上限。 */
private const val FLAKE_SLIDE_GAIN = 2.2f
private const val FLAKE_SLIDE_MAX = 1.0f

private const val FLAKE_TOP = -1.0f
private const val FLAKE_BOTTOM = 1.12f
private const val FLAKE_FADE_IN = 0.06f
private const val FLAKE_FADE_OUT = 0.14f

/** 平面内翻滚的圈数系数：比"翻面"慢，两个周期错开才不像风车。 */
private const val FLAKE_TUMBLE_RATE = 0.35f

// ─────────────────────── 那颗心 ───────────────────────

private const val HEART_SIDE_RATIO = 0.26f
private const val HEART_U = -0.08f
private const val HEART_FROM_V = 0.06f
private const val HEART_TO_V = -0.70f
private const val HEART_RISE_END = 0.72f
private const val HEART_OUTLINE_END = 0.38f
private const val HEART_FILL_FROM = 0.30f
private const val HEART_FILL_TO = 0.80f
private const val HEART_BREATH = 0.15f

// ─────────────────────── 取色 ───────────────────────
// 与 SwiftiePalette 同规矩：球是"一件实物的复刻图"，**不随深色模式变化**。

private val UNIT_CENTER = Offset(0.5f, 0.5f)
private val UNIT_SIZE = Size(1f, 1f)

private val GLASS_EDGE = Color(0xFFDCEEFF)

/** 右下那条反射弧带一点座体的暖色 —— 光是从底座那边弹回来的。 */
private val GLASS_BOUNCE = Color(0xFFFFE3EF)

/**
 * 球体厚度。**建在单位空间里**（球心 0.5,0.5、半径 0.5），绘制时用 `withTransform`
 * 缩放到实际半径。
 *
 * 不按 px 半径建：成型那 900ms 里半径每帧都在变，`Brush` 内部按尺寸缓存原生 `Shader`，
 * 每帧换半径就等于每帧新建一个 native Shader（手链珠子那边踩过同一个坑，
 * 见 `SwiftieBraceletBeads.HIGHLIGHT_STEPS` 的注释）。单位空间只建一次，永远命中缓存。
 */
private val GLASS_BODY: Brush = Brush.radialGradient(
    0.00f to Color.Transparent,
    0.58f to Color.White.copy(alpha = 0.04f),
    0.82f to SwiftiePalette.SkyBlue.copy(alpha = 0.16f),
    0.95f to Color(0xFFEAF6FF).copy(alpha = 0.42f),
    1.00f to SwiftiePalette.SkyBlue.copy(alpha = 0.26f),
    center = UNIT_CENTER,
    radius = 0.5f
)

/** 箔片是玫红与金两种，不是白雪 —— 白点堆在球里只会读成噪点。 */
private val FOIL_ROSE = SwiftiePalette.Glitter
private val FOIL_GOLD = Color(0xFFE8C46A)
private val FOIL_GLINT = Color(0xFFFFF6E0)

private val HEART_OUTLINE = Color(0xFFFFF0F6)

/** 心的填充与灯箱那颗同一套渐变偏心（左上受光），只是这里在单位空间里。 */
private val HEART_FILL: Brush = Brush.radialGradient(
    0f to SwiftiePalette.GlitterLight,
    1f to SwiftiePalette.GlitterDeep,
    center = Offset(0.36f, 0.30f),
    radius = 0.85f
)

private val HEART_GLOW: Brush = Brush.radialGradient(
    0.00f to SwiftiePalette.GlitterLight.copy(alpha = 0.55f),
    0.42f to SwiftiePalette.Glitter.copy(alpha = 0.20f),
    1.00f to Color.Transparent,
    center = UNIT_CENTER,
    radius = 0.5f
)

/** 窗光的暖晕、底座的落地影：都靠这两条单位空间渐变 + 变换复用。 */
private val WINDOW_HALO: Brush = Brush.radialGradient(
    0.00f to Color(0xFFFFC46B).copy(alpha = 0.55f),
    0.55f to Color(0xFFFFB347).copy(alpha = 0.16f),
    1.00f to Color.Transparent,
    center = UNIT_CENTER,
    radius = 0.5f
)

private val SOFT_SHADOW: Brush = Brush.radialGradient(
    0.00f to Color(0xFF2A1218).copy(alpha = 0.34f),
    0.60f to Color(0xFF2A1218).copy(alpha = 0.14f),
    1.00f to Color.Transparent,
    center = UNIT_CENTER,
    radius = 0.5f
)

// Lover House：粉蓝配色，取 SwiftiePalette 的 CloudPink / SkyBlue 一带再分明暗档。
private val HOUSE_WALL_TOP = Color(0xFFFDF1F6)
private val HOUSE_WALL_BOTTOM = Color(0xFFEFD0DF)
private val HOUSE_ANNEX_TOP = Color(0xFFF4DCE7)
private val HOUSE_ANNEX_BOTTOM = Color(0xFFE3BDD0)
private val HOUSE_TRIM = Color(0xFFD49FB7)
private val ROOF_TOP = Color(0xFF9AD0EC)
private val ROOF_BOTTOM = Color(0xFF5E9FC9)
private val ROOF_EDGE = Color(0xFF43789B)
private val BRICK = Color(0xFFC98A9E)
private val BRICK_LINE = Color(0xFFA96C81)
private val SNOW_WHITE = Color(0xFFFDFAFC)
private val SNOW_SHADE = Color(0xFFE8EEF6)
private val WINDOW_WARM = Color(0xFFFFD79A)
private val WINDOW_MULLION = Color(0xFFB07C55)
private val DOOR_COLOR = Color(0xFFB4436A)

/** 描金：印章、底座金线共用一个金。 */
private val GOLD = Color(0xFFD4AF37)
private val GOLD_LIGHT = Color(0xFFF2DC9A)

// 底座：深玫瑰木。刻意压暗——描金印章要在它上面有对比，浅色座配金字等于没字。
private val BASE_TOP_COLOR = Color(0xFF6E4457)
private val BASE_MID_COLOR = Color(0xFF4A2C3B)
private val BASE_FOOT_COLOR = Color(0xFF2C1821)
private val BASE_SOCKET_LIP = Color(0xFF7E5266)
private val BASE_SOCKET_GROOVE = Color(0xFF23131B)
private val SEAL_INK_SHADOW = Color(0xFF1E0E14)

/**
 * 球体折射。三件事按顺序做：凸透镜位移 → 三通道色散 → 菲涅尔边缘增亮。
 *
 * 球外那一句 `return content.eval(coord)` 是必须的：少了它，整层的四角
 * （小卡的投影、房子被圆裁掉的那圈）也会跟着弯，看起来像整块画面泡进了水里。
 *
 * 内部一律用 `float`，只在最后收成 `half4`：`content.eval()` 返回 half，
 * 而 half 与 float 混算各家厂商的 AGSL 编译器宽严不一，统一到 float 最省事。
 */
private const val GLASS_AGSL = """
uniform shader content;
uniform float2 uCenter;
uniform float uRadius;
uniform float uBulge;
uniform float uDispersion;
uniform float uRim;

half4 main(float2 coord) {
    float2 delta = coord - uCenter;
    float dist = length(delta);
    if (dist >= uRadius) {
        return content.eval(coord);
    }
    float t = dist / uRadius;
    float2 dir = dist > 0.001 ? delta / dist : float2(0.0);

    // 1) 凸透镜。h 是单位球的高度场，也就是球面法线的 z 分量：球心 1、球缘 0。
    //    把采样点朝球心拉 uBulge*h*t*R —— 球心附近位移正比于半径，等效整体放大
    //    1/(1-uBulge)；球缘位移归零但导数发散，于是内容被挤成极细的一圈。
    //    这条位移在球缘处连续，所以不会像"直接按 t 缩放"那样在球边出一道硬阶。
    float h = sqrt(max(0.0, 1.0 - t * t));
    float2 lensed = coord - dir * (uBulge * h * t * uRadius);

    // 2) 色散。R / G / B 三次采样，各差一点折射率，径向错开。偏移按 t*t 涨，
    //    所以球心是干净的，只有贴着球缘才吐出那圈极细彩边。
    float2 split = dir * (uDispersion * t * t * uRadius);
    float4 low = float4(content.eval(lensed - split));
    float4 mid = float4(content.eval(lensed));
    float4 high = float4(content.eval(lensed + split));

    // 3) 菲涅尔（Schlick）。视线取正对屏幕的 (0,0,1)，于是 dot(n, v) 就是 h，
    //    pow(1-h, 5) 让贴着球缘的内容更亮 —— 玻璃越斜的地方反射越强。
    float fresnel = pow(1.0 - h, 5.0) * uRim;

    // 采样是预乘色：三个通道来自不同位置，混完可能得到 rgb > a 的非法预乘值
    //（表现是卡片边缘一圈发光的脏边）。菲涅尔也只能加在有内容的地方，
    // 否则球缘那片透明区域会凭空亮起一圈方雾。所以最后统一夹回 a。
    float4 color = float4(low.r, mid.g, high.b, mid.a);
    color.rgb = min(color.rgb + fresnel * color.a, color.a);
    return half4(color);
}
"""

/**
 * Lover 收尾的雪景球。
 *
 * ## 为什么是雪景球
 *
 * `Lover` 的歌词原句就是 *And so it goes / You two are dancing in a snow globe round and round*，
 * 官方 MV 整支设定在雪景球里的 Lover House（房子每个房间对应一张旧专辑），官方周边也是
 * 这只球。所以它不是硬安上去的隐喻 —— 它天生就是"把 126 秒的 12 个时代收进一件
 * 能捧在手里的纪念品"。旧的"绽放"（把卡片放大 1.5 倍）被否掉，就是因为放大只是放大。
 *
 * ## 六拍
 *
 * | 起点 | 长度 | 内容 |
 * |---|---|---|
 * | [SwiftieTimeline.REWIND_START] | 1500 | 卷收期：只有 [content] 在自己变矮；末 200ms 浮出引子光弧 |
 * | [FORM_START] | [FORM_MS] | 小卡转体 0→−12°→0；玻璃自球心成型；底座自下升起 |
 * | [SNOW_START] | [SNOW_MS] | 金箔雪开洒；Lover House 后景淡入 |
 * | [HEART_START] | [HEART_MS] | 心自球心升起：描边 → 自下灌满 → 呼吸一次 + 光晕 |
 * | [SEAL_START] | [SEAL_MS] | `7·3` 描金小印落在底座上；球起 ±6° 极慢自转 |
 * | [SwiftieTimeline.FADE_OUT_START] | 2998 | 整只球淡出并缓慢上浮，金箔雪落到最后一帧 |
 *
 * 底座上那枚 `7·3`：婚礼在 7 月 3 日，而 Lover 是第 7 张专辑、`Lover` 是其中第 3 首。
 * **不写年份、不写任何解释文字** —— 讲出来就不是彩蛋了。
 *
 * @param elapsedMs 序列的绝对已用毫秒，内部按 [SwiftieTimeline] 的常量自己分拍
 * @param content 球内那张卡片。**卷收由调用方负责**（`SwiftieEraCard` 里那 18 行曲目
 *   自下而上收起，只留专辑名 + 日期 + 第 3 首 + 爱心），本函数收到的就是一个已经或
 *   正在变矮的 Composable，尺寸约 320×110dp；这里只按 [INNER_WIDTH_RATIO] 把它收进圆内，
 *   **不做任何缩放**
 */
@Composable
fun SwiftieLoverSnowGlobe(
    elapsedMs: () -> Long,
    modifier: Modifier = Modifier,
    content: @Composable BoxScope.() -> Unit
) {
    // 低端机砍箔片、关自转（自转另见 swingDeg）。AGSL **不看**这个开关 ——
    // 折射是"这是玻璃"的唯一硬证据，砍掉就只剩一个圆；门禁只看 API 版本
    val lowRam = rememberIsLowRamDevice()
    val flakes = remember(lowRam) {
        buildGoldFlakes(if (lowRam) FLAKE_COUNT / 2 else FLAKE_COUNT)
    }

    BoxWithConstraints(modifier = modifier, contentAlignment = Alignment.Center) {
        // 两头都要兜：宽度这边是 320dp 上限，高度这边要连底座一起算得下。
        // 只兜一头就会重演旧实现那种"被裁掉一截"的事故
        val diameter = minOf(maxWidth, maxHeight / STAGE_HEIGHT_RATIO, BALL_MAX_DIAMETER)
        Box(
            modifier = Modifier
                .size(width = diameter, height = diameter * STAGE_HEIGHT_RATIO)
                .graphicsLayer {
                    // 收尾：整只球缓慢上浮。位移用二次曲线，起手速度是 0 ——
                    // 线性起手在 123000 那一帧会看见"被拽了一下"
                    val fade = fadeProgress(elapsedMs())
                    translationY = -fade * fade * size.height * FADE_RISE_RATIO
                }
        ) {
            // 球。自转挂在这一层，底座**不**跟着转：底座是放在桌上的，
            // 一起转就成了"整只被人拿起来晃"，与"定格纪念品"是两个意思
            Box(
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .size(diameter)
                    .graphicsLayer {
                        cameraDistance = GLOBE_CAMERA_DISTANCE
                        rotationY = swingDeg(elapsedMs(), lowRam)
                    }
            ) {
                GlobeInterior(
                    elapsedMs = elapsedMs,
                    innerWidth = diameter * INNER_WIDTH_RATIO,
                    content = content
                )
                // 玻璃壳 + 金箔雪 + 那颗心：画在球内容之上
                Spacer(
                    modifier = Modifier.fillMaxSize().drawWithCache {
                        // 循环里的 Path 建一条反复 rewind（与 SwiftieEraMotifs 同一条铁律）；
                        // 心的轮廓直接用项目里那条唯一的 unitHeartPath()，不再手写第二份
                        val flakePath = Path()
                        val heartPath = unitHeartPath()
                        onDrawBehind {
                            drawGlassShell(elapsedMs(), flakePath, heartPath, flakes)
                        }
                    }
                )
            }
            // 底座声明在球之后 = 画在球之上，座顶那圈唇口才能压住球下缘
            GlobeBase(
                elapsedMs = elapsedMs,
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .fillMaxWidth()
                    .height(diameter * BASE_HEIGHT_RATIO)
            )
        }
    }
}

/**
 * 球内容层：Lover House 后景 + 小卡。折射**只套这一层**。
 *
 * 套在整只球上会把高光弧和落雪一起弯 —— 那两样是"玻璃表面"的东西，弯了就穿模。
 */
@Composable
private fun GlobeInterior(
    elapsedMs: () -> Long,
    innerWidth: Dp,
    content: @Composable BoxScope.() -> Unit
) {
    Box(modifier = Modifier.fillMaxSize().then(glassRefraction { formProgress(elapsedMs()) })) {
        Spacer(
            modifier = Modifier
                .fillMaxSize()
                .graphicsLayer { alpha = houseAlpha(elapsedMs()) }
                .drawWithCache {
                    // 房子整块与时钟无关，淡入只走上面那层 alpha。
                    // 一旦在 onDrawBehind 里读 elapsedMs()，这十几条 Path 和几个渐变
                    // 就会每帧重建一次 —— 后景是静物，没有任何理由每帧重算
                    val parts = LoverHouseParts(size)
                    onDrawBehind { drawLoverHouse(parts) }
                }
        )
        Box(
            modifier = Modifier
                .align(Alignment.Center)
                .width(innerWidth)
                .graphicsLayer {
                    val elapsed = elapsedMs()
                    alpha = 1f - fadeProgress(elapsed)
                    // 转体是真透视变换（rotationY + cameraDistance），不是 scaleX 假装的
                    cameraDistance = CARD_CAMERA_DISTANCE
                    rotationY = cardTurnDeg(elapsed)
                },
            content = content
        )
    }
}

/**
 * 玻璃折射层。
 *
 * **API 门禁**：`RuntimeShader` 是 API 33（TIRAMISU）才有的类，本模块 `minSdk = 26`，
 * 不判版本会在 API 26–32 上抛 `NoClassDefFoundError`。低版本这里返回空 `Modifier`：
 * 少的只是折射，球体、高光弧、落雪、底座、小卡、心、印章照旧全画。
 *
 * 刻意**不给低版本写第二套玻璃** —— `AmbientMeshBackground` 那边有回退，是因为背景层
 * 没了着色器就整块没内容；这里少一层折射仍然是一只雪景球，为它再养一套等价实现
 * 只会多一处要同步维护的几何。
 */
@Composable
private fun glassRefraction(form: () -> Float): Modifier =
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        rememberGlassRefraction(form)
    } else {
        Modifier
    }

@RequiresApi(Build.VERSION_CODES.TIRAMISU)
@Composable
private fun rememberGlassRefraction(form: () -> Float): Modifier {
    // RuntimeShader 的构造函数就把 AGSL 编译了，所以只能建一次（编译代价落在挂载那一帧）。
    // runCatching 兜的是厂商差异：编不过就退回"没有折射"，而不是让收尾崩在用户脸上
    val shader = remember { runCatching { RuntimeShader(GLASS_AGSL) }.getOrNull() }
    if (shader == null) return Modifier
    return Modifier.graphicsLayer {
        val radius = size.minDimension / 2f * form()
        renderEffect = if (radius <= 1f) {
            // 球还没长出来就不挂效果：省一次离屏合成，也省掉那个每帧新建的 RenderEffect
            null
        } else {
            shader.setFloatUniform("uCenter", size.width / 2f, size.height / 2f)
            shader.setFloatUniform("uRadius", radius)
            shader.setFloatUniform("uBulge", GLASS_BULGE)
            shader.setFloatUniform("uDispersion", GLASS_DISPERSION)
            shader.setFloatUniform("uRim", GLASS_RIM)
            // uniform 每帧要换，而 RenderEffect 是不可变的 —— 只能每帧新建一个。
            // 取舍：换来的是"球内的东西真的被玻璃弯折"，而这只球只活 6.5s；
            // 常驻的背景层绝不能这么写（那边的时间是 mirage 自己在着色器里累加的）
            RenderEffect.createRuntimeShaderEffect(shader, "content").asComposeRenderEffect()
        }
    }
}

// ─────────────────────── 分拍取值 ───────────────────────
// 全是纯函数，只吃 elapsed。调用点一律在 graphicsLayer / draw lambda 里 ——
// 组合阶段一次都不读时钟，所以这只球从头到尾不触发一次重组。

private fun span(elapsed: Long, start: Long, durationMs: Long): Float =
    ((elapsed - start).toFloat() / durationMs).coerceIn(0f, 1f)

/** 球成型。EaseOutCubic：起手快、收得住，玻璃"涨"出来才不像匀速充气。 */
private fun formProgress(elapsed: Long): Float =
    EaseOutCubic.transform(span(elapsed, FORM_START, FORM_MS))

private fun fadeProgress(elapsed: Long): Float =
    span(elapsed, SwiftieTimeline.FADE_OUT_START, SwiftieTimeline.FADE_OUT_MS)

private fun houseAlpha(elapsed: Long): Float =
    span(elapsed, SNOW_START, SNOW_MS) * (1f - fadeProgress(elapsed))

/**
 * 小卡转体 0 → −12° → 0。
 *
 * 用 sin 的半个周期而不是两段线性：在 −12° 那一帧线性会"折"一下，
 * 而转体最该被看见的恰恰是加减速本身。
 */
private fun cardTurnDeg(elapsed: Long): Float =
    -CARD_TURN_DEG * sin(PI.toFloat() * span(elapsed, FORM_START, FORM_MS))

/** 极慢自转：±6°、周期 8s，只摆不整圈。 */
private fun swingDeg(elapsed: Long, lowRam: Boolean): Float {
    if (lowRam || elapsed < SEAL_START) return 0f
    val ramp = span(elapsed, SEAL_START, SWING_RAMP_MS)
    return SWING_DEG * ramp * sin(TAU * ((elapsed - SEAL_START) / SWING_PERIOD_MS))
}

/**
 * 玻璃壳。绘制顺序即物理层次：厚度 → 球内的雪 → 球内的心 → 内壁 → 球缘 → 表面高光。
 */
private fun DrawScope.drawGlassShell(
    elapsed: Long,
    flakePath: Path,
    heartPath: Path,
    flakes: List<GoldFlake>
) {
    val fade = fadeProgress(elapsed)
    val dim = 1f - fade
    val rMax = size.minDimension / 2f
    val center = Offset(size.width / 2f, size.height / 2f)
    val form = formProgress(elapsed)
    val radius = rMax * form

    // 引子：球成型前先在满半径上浮一道极淡的弧，球一开始长它就交棒给真高光。
    // 注：当前调用方（SwiftieErasStage）从 LOVER_BLOOM_START 才挂载本层，所以这一笔
    // 实际看不到。留着是因为分镜把它归在卷收期，调用方哪天提前挂载就能直接用上
    val prelude = span(elapsed, PRELUDE_START, PRELUDE_MS) * (1f - form)
    if (prelude > 0.01f) {
        drawSpecular(center, rMax, alpha = 0.22f * prelude * dim, bounce = false)
    }
    if (radius <= 1f) return

    // 玻璃厚度：球心透明、球缘淡蓝白。渐变在单位空间里建（见 GLASS_BODY 的注释），
    // 这里只把它缩放到当前半径 —— 成型期半径每帧都在变，按 px 建就是每帧一个原生 Shader
    withTransform({
        translate(center.x - radius, center.y - radius)
        scale(radius * 2f, radius * 2f, pivot = Offset.Zero)
    }) {
        drawCircle(brush = GLASS_BODY, radius = 0.5f, center = UNIT_CENTER, alpha = dim)
    }

    // 雪比玻璃亮度掉得慢（1 − fade²）：淡出末尾玻璃已经没了，箔片还在星云前飘着，
    // 这是"金箔雪继续落到最后一帧"最好看的读法
    drawGoldFoilSnow(elapsed, center, radius, flakePath, flakes, 1f - fade * fade)
    drawRisingHeart(elapsed, center, radius, heartPath, dim)

    // 内壁细环：没有这一圈，球内的东西看着像贴在球面上，而不是装在里面
    drawCircle(
        color = Color.White,
        radius = radius * 0.955f,
        center = center,
        alpha = 0.25f * dim,
        style = Stroke(width = radius * 0.022f)
    )
    drawCircle(
        color = GLASS_EDGE,
        radius = radius * 0.992f,
        center = center,
        alpha = 0.55f * dim,
        style = Stroke(width = radius * 0.014f)
    )
    drawSpecular(center, radius, alpha = dim, bounce = true)
}

/**
 * 高光。每条都是三段同心弧叠出来的：外一条宽而淡（散射）、中一条主亮、内一条窄而白
 * （镜面芯）。三条比 `Modifier.blur` 便宜得多，也比单条硬弧像玻璃 ——
 * 单条画出来就是一道白线，一眼假。
 *
 * 角度按 Compose 的约定：0° 是三点钟、顺时针增。196°..250° 从九点钟偏下扫到十一点钟。
 */
private fun DrawScope.drawSpecular(center: Offset, radius: Float, alpha: Float, bounce: Boolean) {
    if (alpha <= 0.01f) return
    arcStroke(center, radius * 0.86f, 196f, 54f, radius * 0.090f, Color.White, 0.16f * alpha)
    arcStroke(center, radius * 0.86f, 200f, 46f, radius * 0.050f, Color.White, 0.42f * alpha)
    arcStroke(center, radius * 0.87f, 208f, 26f, radius * 0.024f, Color.White, 0.85f * alpha)
    if (!bounce) return
    // 右下这条是从底座弹回来的反射，必须明显更弱：两条一样亮就读不出光源方向了
    arcStroke(center, radius * 0.80f, 26f, 46f, radius * 0.040f, GLASS_BOUNCE, 0.26f * alpha)
    arcStroke(center, radius * 0.90f, 96f, 30f, radius * 0.020f, GLASS_BOUNCE, 0.18f * alpha)
}

private fun DrawScope.arcStroke(
    center: Offset,
    radius: Float,
    startAngle: Float,
    sweep: Float,
    width: Float,
    color: Color,
    alpha: Float
) {
    drawArc(
        color = color,
        startAngle = startAngle,
        sweepAngle = sweep,
        useCenter = false,
        topLeft = Offset(center.x - radius, center.y - radius),
        size = Size(radius * 2f, radius * 2f),
        alpha = alpha,
        style = Stroke(width = width, cap = StrokeCap.Round)
    )
}

/** 一片金箔。全部量都是"占半径的比例"，所以换直径不用重算。 */
private class GoldFlake(
    val startX: Float,
    val periodMs: Float,
    val release: Float,
    val side: Float,
    val swayAmp: Float,
    val phase: Float,
    val spinMs: Float,
    val gold: Boolean
)

/**
 * 固定种子（2019 = Lover 的年份，与 `SwiftieEraMotifs` 里 Lover 母题同一颗），
 * 重组不跳位。
 */
private fun buildGoldFlakes(count: Int): List<GoldFlake> {
    val random = Random(2019)
    return List(count) { index ->
        GoldFlake(
            // 起点铺开到 ±0.82R：更靠边的箔片会直接贴上内壁，滑落那一段才有得看
            startX = -0.82f + random.nextFloat() * 1.64f,
            // 落速差三倍以上，才不会整批同步下落像一张帘子
            periodMs = 2_400f + random.nextFloat() * 2_600f,
            release = index / count.toFloat(),
            side = 0.055f + random.nextFloat() * 0.045f,
            swayAmp = 0.03f + random.nextFloat() * 0.06f,
            phase = random.nextFloat(),
            spinMs = 900f + random.nextFloat() * 1_400f,
            // 六成玫红四成金：全金会读成"碎屑"，玫红才是 Lover
            gold = random.nextFloat() > 0.6f
        )
    }
}

/**
 * 金箔雪：自球顶落下，触到球内壁后**沿弧线滑落**。
 *
 * 位置由绝对 elapsed 驱动而不是这一拍的进度，所以淡出段里它照旧在落 ——
 * 分镜要求"落到最后一帧"，那就不能让它跟着某一拍的 0..1 走完就停。
 */
private fun DrawScope.drawGoldFoilSnow(
    elapsed: Long,
    center: Offset,
    radius: Float,
    path: Path,
    flakes: List<GoldFlake>,
    dim: Float
) {
    val gate = span(elapsed, SNOW_START, SNOW_MS)
    if (gate <= 0f || dim <= 0.01f) return
    val wall = radius * FLAKE_WALL_RATIO
    flakes.forEach { flake ->
        val releaseAt = SNOW_START + (flake.release * SNOW_MS).toLong()
        if (elapsed < releaseAt) return@forEach
        val cycle = (elapsed - releaseAt) / flake.periodMs
        // 取小数部分循环下落：雪景球里的雪不会落完就停，一轮到底得接上下一轮
        val travel = cycle - floor(cycle)
        var x = (flake.startX + sin((travel + flake.phase) * TAU) * flake.swayAmp) * radius
        var y = (FLAKE_TOP + (FLAKE_BOTTOM - FLAKE_TOP) * travel) * radius
        val dist = hypot(x, y)
        if (dist > wall) {
            // 触壁不能直接把位置钳在弧上 —— 那会看成箔片贴着壁不动。改成按"越壁多少"
            // 沿弧转过一个角度：屏幕坐标 y 向下，右半侧角度增大即朝下滑，左半侧减小同理，
            // 绕过 ±π 由 cos/sin 自己接上，不用额外判边界
            val over = ((dist - wall) / wall * FLAKE_SLIDE_GAIN).coerceAtMost(FLAKE_SLIDE_MAX)
            val angle = atan2(y, x) + if (x >= 0f) over else -over
            x = cos(angle) * wall
            y = sin(angle) * wall
        }
        val fadeIn = (travel / FLAKE_FADE_IN).coerceAtMost(1f)
        val fadeOut = ((1f - travel) / FLAKE_FADE_OUT).coerceAtMost(1f)
        // 越靠球缘越暗：那圈玻璃最厚，箔片本该被压掉一点亮度
        val depth = 1f - 0.28f * (hypot(x, y) / radius)
        val alpha = gate * dim * fadeIn * fadeOut * depth
        if (alpha <= 0.02f) return@forEach
        drawFoilFlake(flake, Offset(center.x + x, center.y + y), radius, elapsed, path, alpha)
    }
}

/** 一片箔：不规则四边形 + 翻面高光。两个转动周期错开，才不像风车。 */
private fun DrawScope.drawFoilFlake(
    flake: GoldFlake,
    pos: Offset,
    radius: Float,
    elapsed: Long,
    path: Path,
    alpha: Float
) {
    // 薄片转到侧面就该窄成一条，转过去背面又亮起来。0.22 是不让它彻底消失的下限 ——
    // 归零会读成一闪一闪的坏点
    val flip = cos(TAU * (elapsed / flake.spinMs + flake.phase))
    val squash = max(abs(flip), 0.22f)
    val glint = 1f - abs(flip)
    val side = flake.side * radius
    val tumble = 360f * (elapsed / flake.spinMs * FLAKE_TUMBLE_RATE + flake.phase)
    rotate(degrees = tumble, pivot = pos) {
        withTransform({
            translate(pos.x - side * squash / 2f, pos.y - side / 2f)
            scale(side * squash, side, pivot = Offset.Zero)
        }) {
            // 撕开的箔片没有对称边，正方形会读成马赛克色块
            path.rewind()
            path.moveTo(0f, 0.18f)
            path.lineTo(0.62f, 0f)
            path.lineTo(1f, 0.72f)
            path.lineTo(0.28f, 1f)
            path.close()
            drawPath(path = path, color = if (flake.gold) FOIL_GOLD else FOIL_ROSE, alpha = alpha)
            // 折面高光只覆上半片：有"折了一下"的厚度，才不是一块平色片
            path.rewind()
            path.moveTo(0f, 0.18f)
            path.lineTo(0.62f, 0f)
            path.lineTo(0.72f, 0.42f)
            path.lineTo(0.16f, 0.52f)
            path.close()
            drawPath(path = path, color = FOIL_GLINT, alpha = alpha * (0.30f + 0.55f * glint))
        }
    }
}

/**
 * 自球心升起的那颗心：描边 → 自下而上灌满 → 一次呼吸 + 外圈光晕。
 *
 * 轮廓用项目里唯一的 [unitHeartPath]。之前灯箱、Lover 母题、曲目行、珠子各写了一份，
 * 四处调形状必漏其中几处，所以这里绝不再写第五份。
 *
 * 心**不进折射层**：它是整段收尾的情绪落点，被凸透镜拉过的心会歪，
 * 而"歪掉的心"比"没被折射的心"错得多。
 */
private fun DrawScope.drawRisingHeart(
    elapsed: Long,
    center: Offset,
    radius: Float,
    heart: Path,
    dim: Float
) {
    val progress = span(elapsed, HEART_START, HEART_MS)
    if (progress <= 0f || dim <= 0.01f) return
    // 从小卡正中冒头，停在屋脊上方。最后 28% 留给呼吸
    val rise = EaseOutCubic.transform((progress / HEART_RISE_END).coerceAtMost(1f))
    val cx = center.x + HEART_U * radius
    val cy = center.y + (HEART_FROM_V + (HEART_TO_V - HEART_FROM_V) * rise) * radius
    val breath = if (progress <= HEART_RISE_END) {
        0f
    } else {
        sin(PI.toFloat() * (progress - HEART_RISE_END) / (1f - HEART_RISE_END))
    }
    val side = radius * HEART_SIDE_RATIO * (0.55f + 0.45f * rise) * (1f + HEART_BREATH * breath)
    val fill = ((progress - HEART_FILL_FROM) / (HEART_FILL_TO - HEART_FILL_FROM)).coerceIn(0f, 1f)
    val outline = (progress / HEART_OUTLINE_END).coerceAtMost(1f)

    val glowAlpha = (0.32f + 0.5f * breath) * fill * dim
    drawUnitBrush(HEART_GLOW, Offset(cx, cy), side * 2.6f, side * 2.6f, glowAlpha)
    withTransform({
        translate(cx - side / 2f, cy - side / 2f)
        scale(side, side, pivot = Offset.Zero)
    }) {
        // 灌注：裁剪框就在单位空间里，不用换算成 px
        if (fill > 0f) {
            clipRect(left = 0f, top = 1f - fill, right = 1f, bottom = 1f) {
                drawPath(path = heart, brush = HEART_FILL, alpha = dim)
            }
        }
        // 描边灌满后也留着：它是心的轮廓光，去掉边缘立刻发虚
        drawPath(
            path = heart,
            color = HEART_OUTLINE,
            alpha = (0.30f + 0.70f * outline) * dim,
            style = Stroke(width = 0.055f)
        )
        if (fill > 0.55f) {
            // 左上一点镜面高光，与手链那颗心形珠同一处受光
            drawOval(
                color = Color.White,
                topLeft = Offset(0.29f, 0.25f),
                size = Size(0.19f, 0.12f),
                alpha = 0.65f * dim * fill
            )
        }
    }
}

private fun trianglePath(
    x1: Float, y1: Float,
    x2: Float, y2: Float,
    x3: Float, y3: Float
): Path = Path().apply {
    moveTo(x1, y1)
    lineTo(x2, y2)
    lineTo(x3, y3)
    close()
}

/**
 * Lover House 的静态几何。Path 与 Brush 只跟尺寸有关，一律在 `drawWithCache` 的缓存块里建。
 *
 * ## 为什么房子是"上下两截"
 *
 * 小卡 224×110dp 压在球心，等于把 |u| < 0.70、|v| < 0.34 那块挖空了。所以真正看得见的
 * 只有两条带：**屋顶 + 阁楼窗 + 烟囱**（卡片上方）、**一楼墙 + 门 + 两扇亮窗 + 雪堆 + 栅栏**
 * （卡片下方）。刻意这么排 —— 把房子整个塞进下半球虽然完整，上半球就空了，圆里一空就散；
 * 跨着卡片排反而读成"卡片在房子前面"，正是"后景"该有的纵深。
 *
 * u / v 是"占半径的比例"，球心为原点、v 向下。房子刻意比卡片宽（−0.78..0.80，卡片 ±0.70），
 * 左右两侧各露出一条墙，纵深才不是靠猜的。
 */
private class LoverHouseParts(size: Size) {
    val r = size.minDimension / 2f
    private val cx = size.width / 2f
    private val cy = size.height / 2f

    fun x(u: Float) = cx + u * r
    fun y(v: Float) = cy + v * r

    /** 球内裁剪。雪地、栅栏、墙角都要被玻璃切掉，否则会画到球外面去。 */
    val clip = Path().apply { addOval(Rect(cx - r, cy - r, cx + r, cy + r)) }

    val roof = trianglePath(x(-0.92f), y(-0.02f), x(-0.31f), y(-0.58f), x(0.30f), y(-0.02f))

    /** 屋脊积雪：屋顶上段再来一枚窄三角，雪才是"堆在脊上"而不是刷了道白边。 */
    val roofSnow = trianglePath(x(-0.55f), y(-0.30f), x(-0.31f), y(-0.61f), x(-0.07f), y(-0.30f))

    val annexRoof = trianglePath(x(0.06f), y(0.14f), x(0.44f), y(-0.16f), x(0.86f), y(0.14f))
    val annexRoofSnow = trianglePath(x(0.28f), y(0.01f), x(0.44f), y(-0.19f), x(0.60f), y(0.01f))

    /** 拱门。用 cubicTo 起拱，不用 quadraticTo —— 前者的名字从来没改过。 */
    val door = Path().apply {
        val left = x(-0.50f)
        val right = x(-0.30f)
        moveTo(left, y(0.58f))
        lineTo(left, y(0.31f))
        cubicTo(left, y(0.22f), right, y(0.22f), right, y(0.31f))
        lineTo(right, y(0.58f))
        close()
    }

    /** 栅栏共用一条 Path，逐根 `rewind()`（循环里绝不 new Path）。 */
    val picket = Path()

    val wallBrush = Brush.verticalGradient(
        0f to HOUSE_WALL_TOP,
        1f to HOUSE_WALL_BOTTOM,
        startY = y(-0.06f),
        endY = y(0.58f)
    )
    val annexBrush = Brush.verticalGradient(
        0f to HOUSE_ANNEX_TOP,
        1f to HOUSE_ANNEX_BOTTOM,
        startY = y(0.10f),
        endY = y(0.58f)
    )
    val roofBrush = Brush.verticalGradient(
        0f to ROOF_TOP,
        1f to ROOF_BOTTOM,
        startY = y(-0.58f),
        endY = y(-0.02f)
    )
    /** 雪地：上白下带一点冷影，才有"地面往里凹"的体积。 */
    val groundBrush = Brush.verticalGradient(
        0f to SNOW_WHITE,
        1f to SNOW_SHADE,
        startY = y(0.34f),
        endY = y(1.0f)
    )
}

/**
 * 画 Lover House。绘制顺序就是遮挡关系：雪地 → 烟囱 → 墙 → 屋顶 → 窗门 → 前雪堆 → 栅栏。
 * 烟囱必须在屋顶之前，屋顶才能压住它的根；前雪堆必须在墙之后，房子的脚才是"埋在雪里"。
 */
private fun DrawScope.drawLoverHouse(parts: LoverHouseParts) {
    val r = parts.r
    clipPath(parts.clip) {
        // 雪地用一枚扁椭圆而不是矩形：矩形会在圆里得到一条平直的地平线，像水位
        drawOval(
            brush = parts.groundBrush,
            topLeft = Offset(parts.x(-1.25f), parts.y(0.34f)),
            size = Size(2.5f * r, 1.6f * r)
        )

        // 烟囱（含砖缝与雪帽）
        drawRect(
            color = BRICK,
            topLeft = Offset(parts.x(-0.62f), parts.y(-0.66f)),
            size = Size(0.13f * r, 0.42f * r)
        )
        listOf(-0.56f, -0.46f).forEach { v ->
            drawLine(
                color = BRICK_LINE,
                start = Offset(parts.x(-0.62f), parts.y(v)),
                end = Offset(parts.x(-0.49f), parts.y(v)),
                strokeWidth = r * 0.008f,
                alpha = 0.7f
            )
        }
        drawRoundRect(
            color = SNOW_WHITE,
            topLeft = Offset(parts.x(-0.655f), parts.y(-0.71f)),
            size = Size(0.20f * r, 0.075f * r),
            cornerRadius = CornerRadius(r * 0.025f)
        )

        // 主体墙 + 背光那侧的暗边（光源与球面高光一致，在左上）
        drawRect(
            brush = parts.wallBrush,
            topLeft = Offset(parts.x(-0.78f), parts.y(-0.06f)),
            size = Size(0.94f * r, 0.64f * r)
        )
        drawRect(
            color = HOUSE_TRIM,
            topLeft = Offset(parts.x(0.08f), parts.y(-0.06f)),
            size = Size(0.08f * r, 0.64f * r),
            alpha = 0.32f
        )
        drawLine(
            color = HOUSE_TRIM,
            start = Offset(parts.x(-0.78f), parts.y(0.22f)),
            end = Offset(parts.x(0.16f), parts.y(0.22f)),
            strokeWidth = r * 0.014f,
            alpha = 0.6f
        )

        // 右翼（annex）：一栋房子有主体也有小翼，才不是一个梯形加两个方块
        drawRect(
            brush = parts.annexBrush,
            topLeft = Offset(parts.x(0.16f), parts.y(0.10f)),
            size = Size(0.64f * r, 0.48f * r)
        )
        drawPath(path = parts.annexRoof, brush = parts.roofBrush)
        drawPath(path = parts.annexRoof, color = ROOF_EDGE, style = Stroke(width = r * 0.012f))
        drawPath(path = parts.annexRoofSnow, color = SNOW_WHITE, alpha = 0.88f)

        // 主屋顶：檐板描一遍边，瓦面才有厚度
        drawPath(path = parts.roof, brush = parts.roofBrush)
        drawPath(path = parts.roof, color = ROOF_EDGE, style = Stroke(width = r * 0.016f))
        drawPath(path = parts.roofSnow, color = SNOW_WHITE, alpha = 0.90f)

        // 阁楼圆窗。位置刻意排在 v = −0.42：卡片压掉的是 |v| < 0.34，
        // 这一格必须在那之上，否则"亮灯的窗"整场都看不见
        val atticCenter = Offset(parts.x(-0.31f), parts.y(-0.42f))
        val atticRadius = r * 0.075f
        drawHalo(atticCenter, atticRadius * 3.4f, 0.85f)
        drawCircle(color = WINDOW_WARM, radius = atticRadius, center = atticCenter)
        drawCircle(
            color = WINDOW_MULLION,
            radius = atticRadius,
            center = atticCenter,
            style = Stroke(width = r * 0.010f)
        )
        drawLine(
            color = WINDOW_MULLION,
            start = Offset(atticCenter.x - atticRadius, atticCenter.y),
            end = Offset(atticCenter.x + atticRadius, atticCenter.y),
            strokeWidth = r * 0.007f
        )
        drawLine(
            color = WINDOW_MULLION,
            start = Offset(atticCenter.x, atticCenter.y - atticRadius),
            end = Offset(atticCenter.x, atticCenter.y + atticRadius),
            strokeWidth = r * 0.007f
        )

        // 门 + 铜把手
        drawPath(path = parts.door, color = DOOR_COLOR)
        drawPath(
            path = parts.door,
            color = ROOF_EDGE,
            alpha = 0.35f,
            style = Stroke(width = r * 0.010f)
        )
        drawCircle(
            color = GOLD_LIGHT,
            radius = r * 0.016f,
            center = Offset(parts.x(-0.33f), parts.y(0.44f))
        )

        // 一楼两扇亮窗，同样排在卡片下方（v = 0.36..0.51）
        drawLitWindow(parts, -0.22f, 0.36f, 0.18f, 0.15f)
        drawLitWindow(parts, 0.34f, 0.36f, 0.20f, 0.15f)

        // 房子脚前的雪堆：没有这一枚，房子看着像贴在雪地画片上
        drawOval(
            color = SNOW_WHITE,
            topLeft = Offset(parts.x(-1.0f), parts.y(0.54f)),
            size = Size(2.0f * r, 0.34f * r),
            alpha = 0.96f
        )
        drawFence(parts)
    }
}

/**
 * 把单位空间的渐变铺到一个矩形上（软光、软影都走这条）。
 *
 * 所有柔光都这么画，是为了让渐变实例能跨帧留住：按 px 的中心/半径建 `radialGradient`，
 * 尺寸一变就得重建原生 `Shader`，而这里几乎每一处柔光的尺寸都在动。
 *
 * 注意每条单位渐变**只在一个 Canvas 里用**（[HEART_GLOW] 在玻璃层、[WINDOW_HALO] 在房子层、
 * [SOFT_SHADOW] 在底座层）：`ShaderBrush` 是按 `DrawScope.size` 缓存原生 Shader 的，
 * 同一条渐变跨两个尺寸不同的 Canvas 用，缓存就会来回失效。
 */
private fun DrawScope.drawUnitBrush(
    brush: Brush,
    center: Offset,
    width: Float,
    height: Float,
    alpha: Float
) {
    if (alpha <= 0.01f) return
    withTransform({
        translate(center.x - width / 2f, center.y - height / 2f)
        scale(width, height, pivot = Offset.Zero)
    }) {
        drawRect(brush = brush, size = UNIT_SIZE, alpha = alpha)
    }
}

/** 暖光晕。 */
private fun DrawScope.drawHalo(center: Offset, diameter: Float, alpha: Float) {
    drawUnitBrush(WINDOW_HALO, center, diameter, diameter, alpha)
}

/** 一扇亮灯窗格：暖晕 + 暖光面 + 十字窗棂 + 窗框 + 下沿积雪。 */
private fun DrawScope.drawLitWindow(
    parts: LoverHouseParts,
    u: Float,
    v: Float,
    w: Float,
    h: Float
) {
    val r = parts.r
    val left = parts.x(u)
    val top = parts.y(v)
    val width = w * r
    val height = h * r
    drawHalo(Offset(left + width / 2f, top + height / 2f), max(width, height) * 2.6f, 0.9f)
    drawRect(color = WINDOW_WARM, topLeft = Offset(left, top), size = Size(width, height))
    drawLine(
        color = WINDOW_MULLION,
        start = Offset(left + width / 2f, top),
        end = Offset(left + width / 2f, top + height),
        strokeWidth = r * 0.007f
    )
    drawLine(
        color = WINDOW_MULLION,
        start = Offset(left, top + height * 0.45f),
        end = Offset(left + width, top + height * 0.45f),
        strokeWidth = r * 0.007f
    )
    drawRect(
        color = WINDOW_MULLION,
        topLeft = Offset(left, top),
        size = Size(width, height),
        style = Stroke(width = r * 0.011f)
    )
    // 窗台雪：一条压在下沿的白边
    drawRect(
        color = SNOW_WHITE,
        topLeft = Offset(left - width * 0.08f, top + height),
        size = Size(width * 1.16f, height * 0.16f)
    )
}

/**
 * 前院栅栏。横档先画、竖条后画（真栅栏的档是钉在背面的），尖顶让它不是一排火柴。
 * 两端由球的裁剪自然切断 —— 这正是"东西摆在球底、地面往上翻"该有的样子。
 */
private fun DrawScope.drawFence(parts: LoverHouseParts) {
    val r = parts.r
    val top = parts.y(0.62f)
    val bottom = parts.y(0.82f)
    listOf(0.665f, 0.745f).forEach { v ->
        drawRect(
            color = SNOW_SHADE,
            topLeft = Offset(parts.x(-0.92f), parts.y(v)),
            size = Size(1.84f * r, 0.022f * r)
        )
    }
    val pickWidth = 0.048f
    var u = -0.88f
    while (u <= 0.88f) {
        val left = parts.x(u)
        val right = parts.x(u + pickWidth)
        val shoulder = top + pickWidth * r * 0.6f
        parts.picket.rewind()
        parts.picket.moveTo(left, bottom)
        parts.picket.lineTo(left, shoulder)
        parts.picket.lineTo((left + right) / 2f, top)
        parts.picket.lineTo(right, shoulder)
        parts.picket.lineTo(right, bottom)
        parts.picket.close()
        drawPath(path = parts.picket, color = SNOW_WHITE)
        // 右侧一条冷影，竖条才有厚度
        drawLine(
            color = SNOW_SHADE,
            start = Offset(right - r * 0.006f, shoulder),
            end = Offset(right - r * 0.006f, bottom),
            strokeWidth = r * 0.010f
        )
        u += 0.115f
    }
}

/**
 * 印章数字的参考字号（px）。绘制时按底座实际尺寸缩放 —— 底座尺寸要到 draw 阶段才知道，
 * 而 `TextMeasurer` 只能在组合阶段用（与手链字母珠 `BEAD_LETTER_REFERENCE_PX` 同一套路）。
 */
private const val SEAL_REFERENCE_PX = 96f

/**
 * 数字可见高 / em。行盒（`layout.size.height`）含 ascent + descent，比可见字高大出近一半，
 * 直接拿它折算会得到一枚只有 13dp 的小印。截图迭代时先调这个值。
 */
private const val SEAL_CAP_RATIO = 0.70f

private const val SEAL_LAND = 0.55f
private const val SEAL_BOUNCE_END = 0.84f

/** 压印微亮的衰减长度（占印章那一拍的比例）。 */
private const val SEAL_GLOW_DECAY = 0.20f

/** 印记显影的长度。落下途中它还是半透的，压到座上才实 —— 像一枚正在落定的印。 */
private const val SEAL_INK_FADE = 0.28f

/** 起落高度（占底座高）。再高就会从玻璃球正中间穿下来，那不叫落印。 */
private const val SEAL_DROP_RATIO = 1.0f

/**
 * 预排 `7` 与 `3`。
 *
 * **字体是 [SwiftieFonts.Marker] 而不是 Script。** 本仓的 `swiftie_script.ttf`（Pacifico）是按
 * 灯箱标题子集化过的，cmap 里只有 `" !.CFHSTabcefgiklmnorstuvwy"` 这 27 个字形，
 * **没有数字** —— 拿它排 `7` 会掉到系统兜底字体或直接是豆腐块，和 `era_lover.ttf` 是同一个坑。
 * `swiftie_marker.ttf`（Gochi Hand）的子集含 `0-9`，而且彩蛋里所有数字（13 / 87 / X）都是
 * 这只手写体，印章跟着它才像"同一件东西上的字"。
 */
@Composable
private fun rememberSealDigits(): List<TextLayoutResult> {
    val measurer = rememberTextMeasurer()
    val density = LocalDensity.current
    return remember(measurer, density) {
        val style = TextStyle(
            fontFamily = SwiftieFonts.Marker,
            fontSize = with(density) { SEAL_REFERENCE_PX.toSp() }
        )
        listOf(measurer.measure("7", style), measurer.measure("3", style))
    }
}

@Composable
private fun GlobeBase(elapsedMs: () -> Long, modifier: Modifier) {
    val digits = rememberSealDigits()
    Spacer(
        modifier = modifier.drawWithCache {
            // 渐变只跟尺寸有关，建在缓存块里；时钟只在 onDrawBehind 里读
            val parts = GlobeBaseParts(size)
            onDrawBehind { drawGlobeBase(elapsedMs(), parts, digits) }
        }
    )
}

/** 底座的静态几何。 */
private class GlobeBaseParts(size: Size) {
    val width = size.width
    val height = size.height
    val cx = size.width / 2f
    val bodyWidth = width * BASE_BODY_WIDTH_RATIO
    val bodyLeft = cx - bodyWidth / 2f

    /** 座体顶边留一线给唇口，唇口才有地方"盖"上去。 */
    val bodyTop = height * 0.07f
    val corner = CornerRadius(height * 0.20f)
    val socketWidth = width * BASE_SOCKET_WIDTH_RATIO
    val sealGlyph = height * SEAL_GLYPH_RATIO
    val sealCenterY = height * 0.58f

    val bodyBrush = Brush.verticalGradient(
        0f to BASE_TOP_COLOR,
        0.45f to BASE_MID_COLOR,
        1f to BASE_FOOT_COLOR,
        startY = bodyTop,
        endY = height
    )
    val socketBrush = Brush.verticalGradient(
        0f to BASE_SOCKET_LIP,
        1f to BASE_MID_COLOR,
        startY = -height * 0.10f,
        endY = height * 0.22f
    )
}

/**
 * 底座：落地影 + 座体 + 顶面唇口/凹槽 + 两道金线 + 高光 + 压印微亮 + `7·3`。
 *
 * 座体自下升起用 EaseOutBack：冲过终点再收回来，落座那一下才有重量
 * （与 `SwiftieEraCard` 长出时同一条缓动，两处的"落地感"才是一致的）。
 */
private fun DrawScope.drawGlobeBase(
    elapsed: Long,
    parts: GlobeBaseParts,
    digits: List<TextLayoutResult>
) {
    val dim = 1f - fadeProgress(elapsed)
    val appear = span(elapsed, FORM_START, FORM_MS)
    if (dim <= 0.01f || appear <= 0f) return
    val settle = EaseOutBack.transform(appear)
    val alpha = dim * (appear * 2.4f).coerceAtMost(1f)
    val height = parts.height

    translate(top = (1f - settle) * height * 1.25f) {
        // 落地影：底座不能悬空，否则整只球读成一张贴纸
        drawUnitBrush(
            brush = SOFT_SHADOW,
            center = Offset(parts.cx, height * 1.02f),
            width = parts.width * 0.96f,
            height = height * 0.62f,
            alpha = 0.85f * alpha
        )
        drawRoundRect(
            brush = parts.bodyBrush,
            topLeft = Offset(parts.bodyLeft, parts.bodyTop),
            size = Size(parts.bodyWidth, height - parts.bodyTop),
            cornerRadius = parts.corner,
            alpha = alpha
        )
        // 顶面椭圆：一半探出画布上沿，压住球的下缘 —— 玻璃是嵌进凹槽的，不是搁在座上
        drawOval(
            brush = parts.socketBrush,
            topLeft = Offset(parts.cx - parts.socketWidth / 2f, -height * 0.09f),
            size = Size(parts.socketWidth, height * 0.30f),
            alpha = alpha
        )
        // 凹槽：唇口里侧一圈暗影，"嵌进去"全靠这一笔
        drawOval(
            color = BASE_SOCKET_GROOVE,
            topLeft = Offset(parts.cx - parts.socketWidth * 0.44f, -height * 0.06f),
            size = Size(parts.socketWidth * 0.88f, height * 0.20f),
            alpha = 0.55f * alpha
        )

        // 座体高光：上缘一条横向亮带，木蜡面才反光
        drawRect(
            color = Color.White,
            topLeft = Offset(parts.bodyLeft + parts.bodyWidth * 0.04f, height * 0.20f),
            size = Size(parts.bodyWidth * 0.92f, height * 0.06f),
            alpha = 0.16f * alpha
        )
        // 两道金线：一道压在高光下，一道贴着脚。底座这才像"座"而不是一块圆角方块
        listOf(0.34f to 0.030f, 0.86f to 0.020f).forEach { (v, thickness) ->
            drawRect(
                color = GOLD,
                topLeft = Offset(parts.bodyLeft + parts.bodyWidth * 0.05f, height * v),
                size = Size(parts.bodyWidth * 0.90f, height * thickness),
                alpha = 0.55f * alpha
            )
        }

        val sealProgress = span(elapsed, SEAL_START, SEAL_MS)
        // 压印那一刻底座微亮，之后 20% 拍程内衰减掉
        val impact = if (sealProgress < SEAL_LAND) {
            0f
        } else {
            (1f - (sealProgress - SEAL_LAND) / SEAL_GLOW_DECAY).coerceIn(0f, 1f)
        }
        if (impact > 0f) {
            drawRoundRect(
                color = Color.White,
                topLeft = Offset(parts.bodyLeft, parts.bodyTop),
                size = Size(parts.bodyWidth, height - parts.bodyTop),
                cornerRadius = parts.corner,
                alpha = 0.13f * impact * alpha
            )
        }
        drawSeal(parts, digits, sealProgress, alpha)
    }
}

/**
 * `7·3`：自上落下 + 一次轻微弹跳，落在底座正面。
 *
 * 婚礼在 **7 月 3 日**，Lover 是第 **7** 张专辑、`Lover` 是其中第 **3** 首。
 * 不写年份、不写任何解释文字。
 *
 * 分隔符**自己画一个圆点**，不排 `·`：本仓三只彩蛋字体的 cmap 都没有 U+00B7
 *（Marker 只有 `0-9 + = ? X` 和空格），排出来只能靠系统兜底字体，那个点的字重和
 * 手写数字对不上；画圆点还能把大小和离地高度调准。也**不用** `/` —— `7/3` 是日期格式，
 * `7·3` 才是刻在纪念品上的。
 */
private fun DrawScope.drawSeal(
    parts: GlobeBaseParts,
    digits: List<TextLayoutResult>,
    progress: Float,
    dim: Float
) {
    if (progress <= 0f) return
    val glyph = parts.sealGlyph
    val unitScale = glyph / (SEAL_CAP_RATIO * SEAL_REFERENCE_PX)
    val alpha = dim * (progress / SEAL_INK_FADE).coerceAtMost(1f)
    // 下落按 1 − t³：起手几乎不动、越落越快，这才是重力；线性掉下来像被人放下去的
    val fall = if (progress >= SEAL_LAND) 0f else 1f - EaseInCubic.transform(progress / SEAL_LAND)
    val bounce = if (progress in SEAL_LAND..SEAL_BOUNCE_END) {
        sin(PI.toFloat() * (progress - SEAL_LAND) / (SEAL_BOUNCE_END - SEAL_LAND))
    } else {
        0f
    }
    val dy = -fall * parts.height * SEAL_DROP_RATIO - bounce * glyph * 0.30f

    val width7 = digits[0].size.width * unitScale
    val width3 = digits[1].size.width * unitScale
    val dotRadius = glyph * 0.085f
    val gap = glyph * 0.16f
    val total = width7 + gap * 2f + dotRadius * 2f + width3
    val left = parts.cx - total / 2f
    val baseline = parts.sealCenterY + glyph / 2f + dy
    val dotCenter = Offset(left + width7 + gap + dotRadius, baseline - glyph * 0.42f)

    drawSealGlyph(digits[0], left, baseline, unitScale, alpha)
    // 圆点同样先压一层深影再描金，和数字的做法保持一致
    drawCircle(
        color = SEAL_INK_SHADOW,
        radius = dotRadius * 1.3f,
        center = Offset(dotCenter.x + dotRadius * 0.12f, dotCenter.y + dotRadius * 0.18f),
        alpha = 0.8f * alpha
    )
    drawCircle(color = GOLD, radius = dotRadius, center = dotCenter, alpha = alpha)
    drawSealGlyph(digits[1], left + width7 + gap * 2f + dotRadius * 2f, baseline, unitScale, alpha)
}

/**
 * 一枚描金数字：深色描边 + 描金填充 + 一层极淡投影。
 *
 * 描边宽度与投影偏移写在**参考字号那套坐标里**，所以数值看着偏大 ——
 * `withTransform` 会把线宽一起缩放（unitScale 约 0.9），落到屏上不到 1dp。
 * 底座是深玫瑰木，金字没有这圈深边就咬不住背景。
 */
private fun DrawScope.drawSealGlyph(
    layout: TextLayoutResult,
    left: Float,
    baseline: Float,
    unitScale: Float,
    alpha: Float
) {
    withTransform({
        // 按基线对齐，不按行盒顶端：行盒含 ascent，按它摆会整体偏高
        translate(left, baseline - layout.firstBaseline * unitScale)
        scale(unitScale, unitScale, pivot = Offset.Zero)
    }) {
        drawText(
            textLayoutResult = layout,
            color = SEAL_INK_SHADOW,
            alpha = 0.85f * alpha,
            drawStyle = Stroke(width = 3.2f)
        )
        drawText(
            textLayoutResult = layout,
            color = GOLD,
            alpha = alpha,
            shadow = Shadow(
                color = SEAL_INK_SHADOW,
                offset = Offset(1.6f, 2.4f),
                blurRadius = 2.4f
            )
        )
    }
}

