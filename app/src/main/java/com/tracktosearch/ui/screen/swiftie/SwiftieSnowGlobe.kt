package com.tracktosearch.ui.screen.swiftie

import android.graphics.RenderEffect
import android.graphics.RuntimeShader
import android.os.Build
import androidx.annotation.RequiresApi
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
import androidx.compose.ui.graphics.PathFillType
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.asComposePath
import androidx.compose.ui.graphics.asComposeRenderEffect
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.core.content.res.ResourcesCompat
import com.tracktosearch.R
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlin.math.PI
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

/** 雪开洒 + Lover House 后景淡入。 */
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
 * 直径上限。
 *
 * 旧的"绽放"是把 Lover 卡片 `graphicsLayer` 放大 1.5 倍，卡片左沿被推出屏幕、
 * 标题的 L 和日期的 2 被切掉。这里换成先量再算：**任何东西都不靠放大变大**。
 *
 * 372dp 而不是原来的 320dp：320 是按 360dp 窄屏减两侧 20dp padding 推出来的，
 * 但那一档在 523dp 宽的机子上让整只球只占屏宽的六成，球以下小半屏是空的 ——
 * 这是全场最后一帧，不该收着。窄屏不受影响：`minOf(maxWidth, …)` 仍会先夹到 maxWidth。
 * 同时 [HOUSE_FIT] 把摆件收到 0.82，所以房子的绝对尺寸没变，多出来的是球内那圈空气。
 */
private val BALL_MAX_DIAMETER = 372.dp

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

/** 印章可见字高占底座高的比例。320dp 的球折算下来约 18dp；emoji 图标随字高走。 */
private const val SEAL_GLYPH_RATIO = 0.34f

// ─────────────────────────── 运动 ───────────────────────────

/** 小卡转体的峰值角度。−12° 是"看得出在转"与"字还读得出来"的交界。 */
private const val CARD_TURN_DEG = 12f

/** 转体的相机距离。默认 8 在 224dp 宽的卡上透视太狠，梯形变形会盖过转体本身。 */
private const val CARD_CAMERA_DISTANCE = 14f

/**
 * 小卡让位后的尺寸。
 *
 * 0.56 与 [CARD_SETTLE_DROP] 是一起量出来的：这一对让小卡的**下缘刚好压在球的内底**、
 * 上缘落在拱门下沿以下，于是屋顶、阁楼圆窗、拱门、一楼两扇亮窗全部露得出来 ——
 * 房子的暖光是这只球的主角，被一块牌子盖住就白画了。
 *
 * 再小一点 `2019-08-23` 那行就糊了（它已经只剩约 24px 高），再大一点上缘就切进门里。
 */
private const val CARD_SETTLE_SCALE = 0.56f

/** 让位缩放的轴心：底边中点。缩小本身就把卡往下带，见调用处的注释。 */
private val CARD_SETTLE_ORIGIN = TransformOrigin(0.5f, 1f)

/** 让位时额外下压的量，按小卡自己的高度算。与 [CARD_SETTLE_SCALE] 配对，别单改一个。 */
private const val CARD_SETTLE_DROP = 0.50f

/**
 * 球自转的相机距离。**必须够远**：rotationY 一转，近侧半边被透视放大、远侧缩小，
 * 投影出来的圆心就整体向一侧漂 —— 相机 10f 时漂移可达 ±30px（占球半径 5%），
 * 用户看到的就是"球没在底座上放正"。60f 后残余 ≤5px，透视的体积感还在。
 */
private const val GLOBE_CAMERA_DISTANCE = 60f

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

// ─────────────────────── 雪 ───────────────────────

/** 雪片数。压到 16 是因为"每一片都认得出是六角雪"比"多"重要；低端机再砍半。 */
private const val FLAKE_COUNT = 16

/** 内壁判定半径：雪片飘到这里就改沿弧线滑。 */
private const val FLAKE_WALL_RATIO = 0.90f

/** 越壁多少就沿弧转多少（弧度增益）与上限。 */
private const val FLAKE_SLIDE_GAIN = 2.2f
private const val FLAKE_SLIDE_MAX = 1.0f

private const val FLAKE_TOP = -1.0f
private const val FLAKE_BOTTOM = 1.12f
private const val FLAKE_FADE_IN = 0.06f
private const val FLAKE_FADE_OUT = 0.14f

/**
 * 面内自转一圈要多久（逐片在这个区间里取）。
 *
 * 10–18 秒一圈是「缓慢」的下限：再快就是搅拌，整只球读成洗衣机。旧箔片那 0.9–2.3s
 * 一圈加一个压扁「翻面」，正是它被读成金属碎屑而不是雪的原因。
 */
private const val FLAKE_SPIN_MIN_MS = 10_000f
private const val FLAKE_SPIN_MAX_MS = 18_000f

// ─────────────────────── 那颗心 ───────────────────────

private const val HEART_SIDE_RATIO = 0.26f
// 落点基本贴着屋脊正上方：偏得多了整颗球的重心被拽向一侧，读起来像球没摆正
private const val HEART_U = -0.04f
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

/**
 * 雪白与淡冰蓝两种。
 *
 * 这一版**推翻**了旧注释那句"白点堆在球里只会读成噪点"—— 那句对实心圆点成立，
 * 对带六条臂的描边雪花不成立：形状本身就够认出是雪，不需要靠金属色来救。
 * 需求方 2026-09-25 定案要真雪，金与玫红那两片料一并撤掉。
 */
private val SNOW_WHITE = Color(0xFFF2F7FF)
private val SNOW_ICE = Color(0xFFBFE0F5)

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

private val HOUSE_WALL_TOP = Color(0xFFFFF8F5)
private val HOUSE_WALL_BOTTOM = Color(0xFFF4D1D8)
private val HOUSE_TRIM = Color(0xFFD78BA5)
private val ROOF_TOP = Color(0xFFF49AB7)
private val ROOF_BOTTOM = Color(0xFFC95A83)
private val ROOF_EDGE = Color(0xFF9E3F68)
private val FLOWER_PINK = Color(0xFFF29AB6)
private val FLOWER_DEEP = Color(0xFFC84C78)
private val SNOW_WHITE = Color(0xFFFFFBFC)
private val SNOW_SHADE = Color(0xFFE9D8E2)
private val WINDOW_WARM = Color(0xFFFFD98D)
private val WINDOW_MULLION = Color(0xFFB87863)
private val DOOR_COLOR = Color(0xFFB84970)

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
 * ## 六拍 + 一拍定格
 *
 * | 起点 | 长度 | 内容 |
 * |---|---|---|
 * | [SwiftieTimeline.REWIND_START] | 1500 | 卷收期：只有 [content] 在自己变矮；末 200ms 浮出引子光弧 |
 * | [FORM_START] | [FORM_MS] | 小卡转体 0→−12°→0；玻璃自球心成型；底座自下升起 |
 * | [SNOW_START] | [SNOW_MS] | 雪开洒；Lover House 后景淡入；小卡缩到 0.56 沉进球下半 |
 * | [HEART_START] | [HEART_MS] | 心自球心升起：描边 → 自下灌满 → 呼吸一次 + 光晕 |
 * | [SEAL_START] | [SEAL_MS] | `7·3` 描金小印落在底座上；球起 ±6° 极慢自转 |
 * | [SwiftieTimeline.LOVER_BLOOM_END] | 2998 | **满亮定格**：六拍演完，结构不再动，雪片照旧缓慢旋着落、±6° 自转照旧摆。配乐这 2998ms 还在放，淡出不在这儿起手 |
 * | [SwiftieTimeline.FADE_OUT_START] | [SwiftieTimeline.TAIL_FADE_MS] | 整只球淡出并缓慢上浮，雪落到最后一帧。起点就是配乐的最后一帧（[SwiftieTimeline.TOTAL_MS]），所以这 1.2 秒**完全跑在配乐之外**，收在 [SwiftieTimeline.END_MS] |
 *
 * 底座上那枚 `7·3`：婚礼在 7 月 3 日，而 Lover 是第 7 张专辑、`Lover` 是其中第 3 首。
 * **不写年份、不写任何解释文字** —— 讲出来就不是彩蛋了。
 *
 * @param elapsedMs 序列的绝对已用毫秒，内部按 [SwiftieTimeline] 的常量自己分拍
 * @param content 球内那张卡片。**卷收由调用方负责**（`SwiftieEraCard` 里的曲目列
 *   自下而上收起，只留专辑名 + 日期 + 第 3 首 + 爱心），本函数收到的就是一个已经或
 *   正在变矮的 Composable，尺寸约 320×110dp；这里按 [INNER_WIDTH_RATIO] 把它收进圆内，
 *   并在房子淡入那一拍缩到 [CARD_SETTLE_SCALE] 沉到球的下半（见 [cardSettleProgress]）——
 *   **除此之外不做任何缩放**
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
        buildSnowFlakes(if (lowRam) FLAKE_COUNT / 2 else FLAKE_COUNT)
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
                // 玻璃壳 + 雪 + 那颗心：画在球内容之上
                Spacer(
                    modifier = Modifier.fillMaxSize().drawWithCache {
                        // 每条轮廓在缓存里建一次、逐帧只变换，绝不 rewind（与
                        // SwiftieEraMotifs 同一条铁律）；心的轮廓直接用项目里那条唯一的
                        // unitHeartPath()，不再手写第二份
                        val snowflakePath = unitSnowflakePath()
                        val heartPath = unitHeartPath()
                        onDrawBehind {
                            drawGlassShell(elapsedMs(), snowflakePath, heartPath, flakes)
                        }
                    }
                )
            }
            GlobeBase(
                elapsedMs = elapsedMs,
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .fillMaxWidth()
                    .height(diameter * BASE_HEIGHT_RATIO)
            )
            GlobeDateTrail(
                elapsedMs = elapsedMs,
                diameter = diameter,
                modifier = Modifier.fillMaxSize()
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
                    // 房子淡入时小卡缩到 0.66 并沉到球的下半 —— 见 cardSettleProgress。
                    // 缩放的轴心放在**底边中点**：这样缩小本身就把整张卡往下带，
                    // 不必再算一个与卡片高度耦合的 translationY（那个高度在卷收期还在变）
                    val settle = cardSettleProgress(elapsed)
                    val shrink = 1f - settle * (1f - CARD_SETTLE_SCALE)
                    transformOrigin = CARD_SETTLE_ORIGIN
                    scaleX = shrink
                    scaleY = shrink
                    // 再往下压小半张：轴心只把卡收到「略低于球心」，
                    // 而屋顶的坡面一直铺到球心下面一点
                    translationY = settle * size.height * CARD_SETTLE_DROP
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

/**
 * 小卡「让位」的进度：与房子淡入**同一拍**（[SNOW_START] 起 [SNOW_MS]）。
 *
 * 球成型那一拍小卡还是主角（它刚从整页的卡片转过来，观众的眼睛跟着它）；
 * 房子一淡入，主角就换成球本身了 —— 小卡这时若还是原尺寸压在球心，
 * 屋顶、烟囱、阁楼窗全被它盖住，读出来是「一张卡片糊在气泡上」，
 * 而这一段的整个用意是那只**纪念品**。
 *
 * EaseOutCubic 与球成型同一条缓动：两件事一前一后发生，用同一条曲线才像同一只手做的。
 */
private fun cardSettleProgress(elapsed: Long): Float =
    EaseOutCubic.transform(span(elapsed, SNOW_START, SNOW_MS))

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
    snowflakePath: Path,
    heartPath: Path,
    flakes: List<SnowFlake>
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

    // 雪比玻璃亮度掉得慢（1 − fade²）：淡出末尾玻璃已经没了，雪片还在星云前飘着，
    // 这是"雪继续落到最后一帧"最好看的读法
    drawSnowfall(elapsed, center, radius, snowflakePath, flakes, 1f - fade * fade)
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
 * 角度按 Compose 的约定：0° 是三点钟、顺时针增。主弧 206°..256°：从九点钟偏上扫到
 * 十一点钟 —— 刻意抬离水平中线，主光压在左上而不是正左，球的视觉重心才不会向左歪。
 */
private fun DrawScope.drawSpecular(center: Offset, radius: Float, alpha: Float, bounce: Boolean) {
    if (alpha <= 0.01f) return
    arcStroke(center, radius * 0.86f, 206f, 50f, radius * 0.075f, Color.White, 0.16f * alpha)
    arcStroke(center, radius * 0.86f, 210f, 42f, radius * 0.045f, Color.White, 0.40f * alpha)
    arcStroke(center, radius * 0.87f, 218f, 24f, radius * 0.022f, Color.White, 0.85f * alpha)
    if (!bounce) return
    // 右下这条是从底座弹回来的反射。要弱于主弧，但完全压住又会把球的视觉重心
    // 全让给左上那组 —— 保持"光源方向"的同时给右侧留一点配重
    arcStroke(center, radius * 0.80f, 26f, 46f, radius * 0.044f, GLASS_BOUNCE, 0.32f * alpha)
    arcStroke(center, radius * 0.90f, 96f, 30f, radius * 0.022f, GLASS_BOUNCE, 0.22f * alpha)
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

/** 一片雪。全部量都是"占半径的比例"，所以换直径不用重算。 */
private class SnowFlake(
    val startX: Float,
    val periodMs: Float,
    val release: Float,
    val side: Float,
    val swayAmp: Float,
    val phase: Float,
    val spinMs: Float,
    val ice: Boolean
)

/**
 * 固定种子（2019 = Lover 的年份，与 `SwiftieEraMotifs` 里 Lover 母题同一颗），
 * 重组不跳位。
 */
private fun buildSnowFlakes(count: Int): List<SnowFlake> {
    val random = Random(2019)
    return List(count) { index ->
        SnowFlake(
            // 起点铺开到 ±0.82R：更靠边的雪片会直接贴上内壁，滑落那一段才有得看
            startX = -0.82f + random.nextFloat() * 1.64f,
            // 落速差接近两倍，才不会整批同步下落像一张帘子。整批比旧箔片慢一档：
            // 雪该飘着下来，不该掉渣
            periodMs = 4_200f + random.nextFloat() * 3_200f,
            release = index / count.toFloat(),
            // 描边的六角比亚面填色的四边形要大一圈才认得出是雪（臂长 = side / 2）
            side = 0.085f + random.nextFloat() * 0.05f,
            swayAmp = 0.03f + random.nextFloat() * 0.06f,
            phase = random.nextFloat(),
            spinMs = FLAKE_SPIN_MIN_MS + random.nextFloat() * (FLAKE_SPIN_MAX_MS - FLAKE_SPIN_MIN_MS),
            // 六成雪白四成冰蓝：同一色铺满会糊成一片，有一冷一白才分得出前后层次
            ice = random.nextFloat() > 0.6f
        )
    }
}

/**
 * 雪：自球顶落下，触到球内壁后**沿弧线滑落**，一路面内缓慢自转。
 *
 * 位置由绝对 elapsed 驱动而不是这一拍的进度，所以淡出段里它照旧在落 ——
 * 分镜要求"落到最后一帧"，那就不能让它跟着某一拍的 0..1 走完就停。
 */
private fun DrawScope.drawSnowfall(
    elapsed: Long,
    center: Offset,
    radius: Float,
    unit: Path,
    flakes: List<SnowFlake>,
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
            // 触壁不能直接把位置钳在弧上 —— 那会看成雪片贴着壁不动。改成按"越壁多少"
            // 沿弧转过一个角度：屏幕坐标 y 向下，右半侧角度增大即朝下滑，左半侧减小同理，
            // 绕过 ±π 由 cos/sin 自己接上，不用额外判边界
            val over = ((dist - wall) / wall * FLAKE_SLIDE_GAIN).coerceAtMost(FLAKE_SLIDE_MAX)
            val angle = atan2(y, x) + if (x >= 0f) over else -over
            x = cos(angle) * wall
            y = sin(angle) * wall
        }
        val fadeIn = (travel / FLAKE_FADE_IN).coerceAtMost(1f)
        val fadeOut = ((1f - travel) / FLAKE_FADE_OUT).coerceAtMost(1f)
        // 越靠球缘越暗：那圈玻璃最厚，里面的东西本该被压掉一点亮度
        val depth = 1f - 0.28f * (hypot(x, y) / radius)
        val alpha = gate * dim * fadeIn * fadeOut * depth
        if (alpha <= 0.02f) return@forEach
        drawSnowFlake(flake, Offset(center.x + x, center.y + y), radius, elapsed, unit, alpha)
    }
}

/**
 * 六角雪的单位轮廓：中心在原点、臂尖半径 0.5，六条主臂各带一对侧枝。
 *
 * 只在 `drawWithCache` 里建一次，逐片靠旋转与缩放复用同一条 Path —— 每帧给 16 片
 * 各拼 18 段线既浪费又违反「循环里不重建对象」这条铁律（与 `unitHeartPath()` 同套路）。
 */
private fun unitSnowflakePath(): Path {
    val path = Path()
    val arm = 0.5f
    // 侧枝长在臂的 62% 处、张角 ±52°、长 0.24 臂：更靠尖会挤成星号，更靠根读成十字
    val baseAt = 0.62f
    val branchLen = 0.24f * arm
    val cos52 = cos(52f / 180f * PI.toFloat())
    val sin52 = sin(52f / 180f * PI.toFloat())
    for (index in 0 until 6) {
        val rad = index * 60f / 180f * PI.toFloat()
        val ux = cos(rad)
        val uy = sin(rad)
        path.moveTo(0f, 0f)
        path.lineTo(ux * arm, uy * arm)
        val bx = ux * arm * baseAt
        val by = uy * arm * baseAt
        // 把臂方向分别旋 +52° 与 −52° 得到两条侧枝的方向（关于臂轴对称）
        val d1x = ux * cos52 - uy * sin52
        val d1y = ux * sin52 + uy * cos52
        val d2x = ux * cos52 + uy * sin52
        val d2y = uy * cos52 - ux * sin52
        path.moveTo(bx, by)
        path.lineTo(bx + d1x * branchLen, by + d1y * branchLen)
        path.moveTo(bx, by)
        path.lineTo(bx + d2x * branchLen, by + d2y * branchLen)
    }
    return path
}

/**
 * 一片雪：单位六角轮廓按 `side` 缩放、按 `spinMs` 缓慢自转。
 *
 * 旧箔片那套「压扁模拟翻面 + 折面高光」整个删了 —— 正是那两样把它读成金属碎屑。
 * 真雪在玻璃球里就是这么慢悠悠地转，不做透视。
 *
 * 笔宽给在单位空间里（0.13）：画布缩放会连带放大笔尖，所以片越大线越粗，
 * 那正是大雪花该有的样子，不用逐片补一个笔画宽度。
 */
private fun DrawScope.drawSnowFlake(
    flake: SnowFlake,
    pos: Offset,
    radius: Float,
    elapsed: Long,
    unit: Path,
    alpha: Float
) {
    val side = flake.side * radius
    val spin = 360f * (elapsed / flake.spinMs + flake.phase)
    rotate(degrees = spin, pivot = pos) {
        withTransform({
            translate(pos.x, pos.y)
            scale(side, side, pivot = Offset.Zero)
        }) {
            drawPath(
                path = unit,
                color = if (flake.ice) SNOW_ICE else SNOW_WHITE,
                alpha = alpha,
                style = Stroke(width = 0.13f, cap = StrokeCap.Round)
            )
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
/**
 * 摆件占球半径的比例。
 *
 * 1.0（原值）时房子横跨 1.78r —— 球宽的 89%，屋檐左角与玻璃只剩 0.08r 的空隙，
 * 截图上读作「一栈房子塞进了气泡」。真的雪景球里摆件占七成宽，四周留着空气，
 * 那圈空气正是「这是个玻璃罩着的小世界」的全部说服力。
 *
 * 0.82 之后房子横跨 1.46r（73%），屋檐左角落在 0.75r 上。球的直径同时从 320dp
 * 提到 [BALL_MAX_DIAMETER] 的 372dp，所以房子的**绝对**大小几乎没变
 * （285dp → 271dp），换来的纯粹是四周那圈空气。
 */
private const val HOUSE_FIT = 0.82f

private class LoverHouseParts(size: Size) {
    /** 玻璃球半径。只有裁剪用它 —— 雪地要一直铺到玻璃边上。 */
    val ballR = size.minDimension / 2f

    /**
     * 摆件的作图半径。房子、栅栏、雪堆的**位置与尺寸一律按它算**，
     * 所以改 [HOUSE_FIT] 一处就是整个摆件等比缩放。
     */
    val r = ballR * HOUSE_FIT
    private val cx = size.width / 2f
    private val cy = size.height / 2f

    fun x(u: Float) = cx + u * r
    fun y(v: Float) = cy + v * r

    /** 球内裁剪。雪地、栅栏、墙角都要被玻璃切掉，否则会画到球外面去。 */
    val clip = Path().apply { addOval(Rect(cx - ballR, cy - ballR, cx + ballR, cy + ballR)) }

    /** 一栋居中的婚礼庄园：单屋顶、心形窗、门廊与花拱。 */
    val roof = trianglePath(x(-0.72f), y(0.12f), x(0f), y(-0.58f), x(0.72f), y(0.12f))
    val roofHighlight = trianglePath(x(-0.55f), y(0.08f), x(0f), y(-0.49f), x(0.18f), y(0.08f))
    val door = Path().apply {
        val left = x(-0.16f)
        val right = x(0.16f)
        moveTo(left, y(0.64f))
        lineTo(left, y(0.38f))
        cubicTo(left, y(0.24f), right, y(0.24f), right, y(0.38f))
        lineTo(right, y(0.64f))
        close()
    }
    val groundBrush = Brush.verticalGradient(
        0f to SNOW_WHITE,
        1f to SNOW_SHADE,
        startY = y(0.42f),
        endY = y(1.0f)
    )
    val wallBrush = Brush.verticalGradient(
        0f to HOUSE_WALL_TOP,
        1f to HOUSE_WALL_BOTTOM,
        startY = y(0.08f),
        endY = y(0.66f)
    )
    val roofBrush = Brush.verticalGradient(
        0f to ROOF_TOP,
        1f to ROOF_BOTTOM,
        startY = y(-0.58f),
        endY = y(0.12f)
    )
}

/** 婚礼庄园：一栋粉白小屋，暖灯、心形阁楼窗与门前花拱。 */
private fun DrawScope.drawLoverHouse(parts: LoverHouseParts) {
    val r = parts.r
    clipPath(parts.clip) {
        drawOval(
            brush = parts.groundBrush,
            topLeft = Offset(parts.x(-1.15f), parts.y(0.42f)),
            size = Size(2.3f * r, 1.45f * r)
        )

        drawRect(
            brush = parts.wallBrush,
            topLeft = Offset(parts.x(-0.56f), parts.y(0.08f)),
            size = Size(1.12f * r, 0.58f * r)
        )
        drawLine(
            color = HOUSE_TRIM,
            start = Offset(parts.x(-0.56f), parts.y(0.29f)),
            end = Offset(parts.x(0.56f), parts.y(0.29f)),
            strokeWidth = r * 0.014f,
            alpha = 0.65f
        )

        drawPath(path = parts.roof, brush = parts.roofBrush)
        drawPath(path = parts.roof, color = ROOF_EDGE, style = Stroke(width = r * 0.016f))
        drawPath(path = parts.roofHighlight, color = Color.White, alpha = 0.22f)

        drawPath(path = parts.door, color = DOOR_COLOR)
        drawPath(path = parts.door, color = ROOF_EDGE, alpha = 0.36f, style = Stroke(width = r * 0.010f))
        drawCircle(color = GOLD_LIGHT, radius = r * 0.018f, center = Offset(parts.x(0.10f), parts.y(0.49f)))
        drawLitWindow(parts, -0.42f, 0.34f, 0.22f, 0.17f)
        drawLitWindow(parts, 0.20f, 0.34f, 0.22f, 0.17f)

        // 门前花拱：粉色花簇沿弧线排布，避免小屋像一块平面贴纸。
        var angle = PI.toFloat()
        while (angle <= TAU) {
            val x = parts.x(0.48f * cos(angle))
            val y = parts.y(0.56f - 0.18f * sin(angle))
            drawHalo(Offset(x, y), r * 0.13f, 0.32f)
            drawCircle(color = FLOWER_DEEP, radius = r * 0.034f, center = Offset(x, y))
            drawCircle(color = FLOWER_PINK, radius = r * 0.020f, center = Offset(x - r * 0.018f, y - r * 0.014f))
            angle += PI.toFloat() / 6f
        }
        drawLine(
            color = FLOWER_DEEP,
            start = Offset(parts.x(-0.48f), parts.y(0.56f)),
            end = Offset(parts.x(0.48f), parts.y(0.56f)),
            strokeWidth = r * 0.022f,
            alpha = 0.80f,
            cap = StrokeCap.Round
        )
        drawOval(
            color = SNOW_WHITE,
            topLeft = Offset(parts.x(-0.92f), parts.y(0.58f)),
            size = Size(1.84f * r, 0.30f * r),
            alpha = 0.96f
        )
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

/** 底座铭牌与卡片的两枚来源数字，使用同一套参考字号以便做平滑变形。 */
private const val SEAL_REFERENCE_PX = 96f

/** 数字行程占总拍程的比例：走完就把位置交给铭牌，之后只做戒指与牵手的依次登场。 */
private const val TRAIL_LAND = 0.72f

/** 数字可见高 / em。 */
private const val SEAL_CAP_RATIO = 0.70f

/** 7、3 先抵达，再依次出现戒指与男女牵手。 */
private const val RING_START = 0.74f
private const val RING_END = 0.88f
private const val HANDS_START = 0.86f

/** 戒指钻石只闪一次。 */
private const val RING_GLINT_START = 0.80f
private const val RING_GLINT_END = 0.96f

/** 过渡数字从卡片原色切换到暖白金色的强度。 */
private const val SOURCE_DIGIT_ALPHA = 0.94f

/** 铭牌上的两枚 emoji（戒指 / 牵手）相对数字参考字号的倍数。 */
private const val SEAL_EMOJI_SCALE = 1.05f

/** 戒指 emoji 单独缩放：💍 的钻太大，与数字抢视觉（用户定 0.6x）。 */
private const val SEAL_RING_SCALE = 0.6f

/**
 * 预排 `7`、`3` 与两枚 emoji（💍 / 👫）。
 *
 * **数字必须是 Path 而不是 TextLayoutResult。** `swiftie_marker.ttf`（Gochi Hand）的字形
 * 由多段手写笔画轮廓叠成，Skia 的文本填充按 even-odd 处理重叠区，笔画交叠处会被
 * 挖成空心管 —— 同一只字体在出题页走的是 `drawPath`（SwiftiePoster 的 `art.fixed`，
 * 手工描摹的实心路径）所以那边是实的。这里用 `Paint.getTextPath` 取轮廓后**显式按
 * Winding 填充**，交叠区才是实的。
 *
 * emoji 走系统彩色字体（NotoColorEmoji），位图字形没有填充规则问题，直接
 * TextLayoutResult 绘制；它们只用来取尺寸，出现/落位的节奏在绘制侧控制。
 */
private class SealArt(
    val digitPaths: List<Path>,
    val digitAdvances: List<Float>,
    val digitInkCenters: List<Float>,
    val ring: TextLayoutResult,
    val hands: TextLayoutResult
)

@Composable
private fun rememberSealArt(): SealArt {
    val context = LocalContext.current
    val measurer = rememberTextMeasurer()
    val density = LocalDensity.current
    return remember(context, measurer, density) {
        val typeface = ResourcesCompat.getFont(context, R.font.swiftie_honey)
        val paint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
            this.typeface = typeface
            textSize = SEAL_REFERENCE_PX
        }
        fun digit(text: String): Triple<Path, Float, Float> {
            val outline = android.graphics.Path()
            paint.getTextPath(text, 0, text.length, 0f, 0f, outline)
            val bounds = android.graphics.RectF()
            outline.computeBounds(bounds, true)
            val path = Path().apply {
                fillType = PathFillType.NonZero
                addPath(outline.asComposePath())
            }
            return Triple(path, paint.measureText(text), bounds.centerY())
        }
        val (path7, advance7, inkCenter7) = digit("7")
        val (path3, advance3, inkCenter3) = digit("3")
        val emojiStyle = TextStyle(
            fontSize = with(density) { (SEAL_REFERENCE_PX * SEAL_EMOJI_SCALE).toSp() }
        )
        SealArt(
            digitPaths = listOf(path7, path3),
            digitAdvances = listOf(advance7, advance3),
            digitInkCenters = listOf(inkCenter7, inkCenter3),
            ring = measurer.measure("💍", emojiStyle),
            hands = measurer.measure("👫", emojiStyle)
        )
    }
}

@Composable
private fun GlobeDateTrail(
    elapsedMs: () -> Long,
    diameter: Dp,
    modifier: Modifier
) {
    val art = rememberSealArt()
    // 两个数字各取贴板不同区域的真闪粉（出题页算式同一张贴板）。
    // 窗口必须整个落在贴板的闪粉带内部：贴板上是原海报的算式，
    // 跨到带外的暗缝就会把"空心"印进字形里。两处都已裁图核验为实心闪粉
    val glitter7 = rememberGlitterPatchBrush(0f, 40f)
    val glitter3 = rememberGlitterPatchBrush(96f, 128f)
    Spacer(
        modifier = modifier.drawWithCache {
            // DrawScope 本身就是 Density，直径在这里直接折成 px
            val diameterPx = diameter.toPx()
            onDrawBehind {
                drawDateTrail(elapsedMs(), size, diameterPx, art, glitter7, glitter3)
            }
        }
    )
}

private fun DrawScope.drawDateTrail(
    elapsed: Long,
    stage: Size,
    diameter: Float,
    art: SealArt,
    glitter7: Brush,
    glitter3: Brush
) {
    val progress = span(elapsed, SEAL_START, SEAL_MS)
    if (progress <= 0f) return
    val glyph = (diameter * BASE_HEIGHT_RATIO) * SEAL_GLYPH_RATIO
    val u = glyph / (SEAL_CAP_RATIO * SEAL_REFERENCE_PX)
    val w7 = art.digitAdvances[0] * u
    val w3 = art.digitAdvances[1] * u
    val gap = glyph * 0.16f
    val dotRadius = glyph * 0.095f
    val emojiGap = glyph * 0.22f
    val ringW = art.ring.size.width * u * SEAL_RING_SCALE
    val handsW = art.hands.size.width * u
    // 总宽算到每个元素的墨迹右缘（emoji 的布局盒即墨迹），整行才真正居中
    val total = w7 + gap * 2f + dotRadius * 2f + w3 + emojiGap + ringW + emojiGap + handsW
    val left = stage.width / 2f - total / 2f
    // 两道金线画在底座高 34% 与 86% 处，铭牌墨迹中心锚在它们的正中（底座高 60%）
    val baseHeight = diameter * BASE_HEIGHT_RATIO
    val plaqueCenter = stage.height - 0.40f * baseHeight
    val baseline7 = plaqueCenter - art.digitInkCenters[0] * u
    val baseline3 = plaqueCenter - art.digitInkCenters[1] * u
    val target7 = Offset(left, baseline7)
    val target3 = Offset(left + w7 + gap * 2f + dotRadius * 2f, baseline3)
    val source7 = Offset(stage.width / 2f - w7 * 0.55f, diameter * 0.48f)
    val source3 = Offset(stage.width / 2f - w3 * 0.20f, diameter * 0.58f)
    val travel = EaseOutCubic.transform((progress / TRAIL_LAND).coerceIn(0f, 1f))
    val alpha = (progress / 0.10f).coerceIn(0f, 1f)
    drawDigit(art.digitPaths[0], source7, target7, u, travel, alpha, glitter7)
    drawDigit(art.digitPaths[1], source3, target3, u, travel, alpha, glitter3)
    if (travel <= 0.01f) return
    val dotCenter = Offset(target7.x + w7 + gap + dotRadius, plaqueCenter)
    drawCircle(color = SEAL_INK_SHADOW, radius = dotRadius * 1.3f, center = Offset(dotCenter.x + dotRadius * 0.12f, dotCenter.y + dotRadius * 0.18f), alpha = 0.8f * alpha)
    drawCircle(color = GOLD, radius = dotRadius, center = dotCenter, alpha = alpha)
    val ringP = ((progress - RING_START) / (RING_END - RING_START)).coerceIn(0f, 1f)
    val handsP = ((progress - HANDS_START) / (1f - HANDS_START)).coerceIn(0f, 1f)
    drawEmoji(
        art.ring,
        center = Offset(target3.x + w3 + emojiGap + ringW / 2f, plaqueCenter),
        u = u,
        scale = SEAL_RING_SCALE,
        p = ringP,
        alpha = alpha
    )
    drawEmoji(
        art.hands,
        center = Offset(target3.x + w3 + emojiGap + ringW + emojiGap + handsW / 2f, plaqueCenter),
        u = u,
        scale = 1f,
        p = handsP,
        alpha = alpha
    )
}

/**
 * 一枚 emoji 图标：以墨迹盒中心对齐铭牌中心，出现时用 EaseOutBack 弹一下
 * （与底座落座同一条缓动，"啪地放上去"的重量感一致）。
 */
private fun DrawScope.drawEmoji(
    layout: TextLayoutResult,
    center: Offset,
    u: Float,
    scale: Float,
    p: Float,
    alpha: Float
) {
    if (p <= 0f) return
    val appear = EaseOutBack.transform(p)
    val w = layout.size.width * u * scale * appear
    val h = layout.size.height * u * scale * appear
    withTransform({
        translate(center.x - w / 2f, center.y - h / 2f)
        scale(u * scale * appear, u * scale * appear, pivot = Offset.Zero)
    }) {
        drawText(textLayoutResult = layout, alpha = alpha * p)
    }
}

/**
 * 一枚数字：飞行途中是卡片上的平粉，落定后交给真闪粉贴图（出题页算式同一块板）。
 * 墨影先铺一遍，深木底上闪粉才托得住。
 */
private fun DrawScope.drawDigit(
    path: Path,
    source: Offset,
    target: Offset,
    u: Float,
    travel: Float,
    alpha: Float,
    glitter: Brush
) {
    val px = source.x + (target.x - source.x) * travel
    val py = source.y + (target.y - source.y) * travel
    withTransform({
        translate(px, py)
        scale(u, u, pivot = Offset.Zero)
    }) {
        if (travel < 1f) {
            drawPath(path, SwiftiePalette.Glitter, alpha = alpha * (1f - travel))
        }
        if (travel > 0f) {
            withTransform({ translate(0.10f, 0.14f) }) {
                drawPath(path, SEAL_INK_SHADOW, alpha = 0.85f * alpha * travel)
            }
            drawPath(path, brush = glitter, alpha = alpha * travel)
        }
    }
}


@Composable
private fun GlobeBase(elapsedMs: () -> Long, modifier: Modifier) {
    Spacer(
        modifier = modifier.drawWithCache {
            // 渐变只跟尺寸有关，建在缓存块里；时钟只在 onDrawBehind 里读
            val parts = GlobeBaseParts(size)
            onDrawBehind { drawGlobeBase(elapsedMs(), parts) }
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
 * 底座：落地影 + 座体 + 顶面唇口/凹槽 + 两道金线 + 高光。
 *
 * 日期铭牌（7.3 + 戒指 + 牵手）由全舞台的 [GlobeDateTrail] 绘制 ——
 * 数字要从球内卡片飞到底座，跨两个局部坐标系，留在任何一侧都会被裁断。
 *
 * 座体自下升起用 EaseOutBack：冲过终点再收回来，落座那一下才有重量
 * （与 `SwiftieEraCard` 长出时同一条缓动，两处的"落地感"才是一致的）。
 */
private fun DrawScope.drawGlobeBase(
    elapsed: Long,
    parts: GlobeBaseParts
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

        // 数字落定那一刻底座微亮，随后 0.14 拍程内衰减掉。
        val landProgress = span(elapsed, SEAL_START, SEAL_MS)
        val impact = if (landProgress < TRAIL_LAND) {
            0f
        } else {
            (1f - (landProgress - TRAIL_LAND) / 0.14f).coerceIn(0f, 1f)
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
    }
}

/**
 * 分隔符**自己画一个圆点**，不排 `·`：本仓三只彩蛋字体的 cmap 都没有 U+00B7
 *（Marker 只有 `0-9 + = ? X` 和空格），排出来只能靠系统兜底字体，那个点的字重和
 * 手写数字对不上；画圆点还能把大小和离地高度调准。也**不用** `/` —— `7/3` 是日期格式，
 * `7.3` 才是刻在纪念品上的。
 */
