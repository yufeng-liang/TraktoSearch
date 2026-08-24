package com.tracktosearch.ui.component

import android.graphics.Bitmap
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.layer.GraphicsLayer
import androidx.compose.ui.graphics.rememberGraphicsLayer
import androidx.compose.ui.util.lerp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.currentStateAsState
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlin.math.abs

/**
 * 玻璃控件的自适应亮度，对齐官方 catalog 的 AdaptiveLuminanceGlassContent。
 *
 * 做法：把 drawBackdrop 采到的**原始**背景（未经我们的 blur/lens 处理）额外录一份到一层
 * [GraphicsLayer]，读回缩成 5x5 求 Rec.709 加权平均亮度，再用这个亮度去推 brightness /
 * contrast / blur——背后偏亮时玻璃更亮更糊、对比度压平；偏暗时更暗更清透。
 * 因为录的是效果前的输入而不是我们自己的输出，不存在正反馈自激。
 *
 * 与官方的差异（有意）：
 * - 官方 while(isActive) 无间隔连采、每次都 tween(1000)，等于常驻动画。本项目已经把空闲满帧
 *   重绘掐掉了（见 [AmbientMotionState]），所以这里改成"实测亮度不变就指数退避"，
 *   稳定后采样降到 1.6s 一次且不跑动画，空闲仍是零帧。
 * - 官方 brightness 基线是 +0.1（静止也整体提亮一档）。本项目基线取 0，只在明显偏亮/偏暗
 *   时才偏移，避免改动现有中性观感。
 */
@Stable
class GlassLuminanceProbe internal constructor(internal val layer: GraphicsLayer) {

    private val animatable = Animatable(NeutralLuminance)

    /**
     * 平滑后的背景亮度 0..1。只在 drawBackdrop 的 effects 里读：kyant 用 observeReads 跟踪
     * effects 内的状态读，值变化只触发重绘不触发重组。
     */
    val luminance: Float get() = animatable.value

    /**
     * 映射到 -1..1 并保号平方：0.5 附近（signed≈0）几乎不动，越偏离才越用力，
     * 避免中性背景下参数抖动。官方同一条曲线。
     */
    internal val signedLuminance: Float
        get() {
            val centered = luminance.coerceIn(0f, 1f) * 2f - 1f
            return centered * abs(centered)
        }

    internal suspend fun adaptTo(measured: Float) {
        animatable.animateTo(measured, tween(AdaptDurationMillis))
    }

    internal fun isStableAt(measured: Float): Boolean =
        abs(measured - animatable.value) < StableThreshold
}

/**
 * 建一个亮度探针。调用方需要把 [GlassLuminanceProbe.layer] 交给 drawBackdrop 的
 * onDrawBackdrop 去录（见 [backdropGlass]），否则测不到东西。
 *
 * 两级停采：STARTED 以下（切后台/息屏）整个停；[LocalAmbientMotionActive] 为 false（没人操作、
 * 背景动效也已冻结）时挂起等下一次交互——此时屏幕内容不会变，读回纯属白烧。
 */
@Composable
internal fun rememberGlassLuminanceProbe(): GlassLuminanceProbe {
    val layer = rememberGraphicsLayer()
    val probe = remember(layer) { GlassLuminanceProbe(layer) }
    val lifecycleState by LocalLifecycleOwner.current.lifecycle.currentStateAsState()
    val started = lifecycleState.isAtLeast(Lifecycle.State.STARTED)
    val motionActive = LocalAmbientMotionActive.current
    LaunchedEffect(probe, started, motionActive) {
        if (!started) return@LaunchedEffect
        val buffer = IntArray(ThumbnailSide * ThumbnailSide)
        var interval = MinSampleIntervalMillis
        while (true) {
            if (!motionActive()) {
                snapshotFlow { motionActive() }.first { it }
                interval = MinSampleIntervalMillis
            }
            delay(interval)
            val measured = probe.measure(buffer)
            // measured == null：控件还没画过（首帧前 / Pager 里的离屏页），退避等它被画出来。
            if (measured == null || probe.isStableAt(measured)) {
                interval = (interval * 2).coerceAtMost(MaxSampleIntervalMillis)
                continue
            }
            interval = MinSampleIntervalMillis
            // animateTo 挂起到动画结束，天然把采样率限制在"一次适配一次采样"。
            probe.adaptTo(measured)
        }
    }
    return probe
}

/**
 * 只给圆形玻璃按钮开自适应亮度：读回是 GPU→CPU 同步操作，代价随控件面积走，顶栏/底栏这种
 * 整条面板不划算；而按钮恰好最需要"背后是海报还是白底"这层自适应。
 */
internal val GlassSurfaceRole.supportsAdaptiveLuminance: Boolean
    get() = this == GlassSurfaceRole.CircularControl || this == GlassSurfaceRole.DetailAction

/** blur 缩放：背后偏亮 → 更糊（最多 2x），偏暗 → 更清透（最低 0.35x）。官方是 8dp→16dp / 8dp→2dp。 */
internal fun adaptiveBlurScale(signedLuminance: Float): Float =
    if (signedLuminance > 0f) {
        lerp(1f, BrightBlurScale, signedLuminance)
    } else {
        lerp(1f, DarkBlurScale, -signedLuminance)
    }

/** brightness 偏移：偏亮加亮、偏暗压暗，基线 0。 */
internal fun adaptiveBrightness(signedLuminance: Float): Float =
    if (signedLuminance > 0f) {
        lerp(0f, BrightBrightness, signedLuminance)
    } else {
        lerp(0f, DarkBrightness, -signedLuminance)
    }

/** contrast：偏亮时压平（亮背景上不要把噪点对比拉出来），偏暗时保持基线。官方同向。 */
internal fun adaptiveContrast(baseContrast: Float, signedLuminance: Float): Float =
    if (signedLuminance > 0f) {
        lerp(baseContrast, BrightContrast, signedLuminance)
    } else {
        baseContrast
    }

/**
 * 读回图层并求平均亮度。返回 null 表示这一轮测不了（图层还没录过 / 读回失败 / 全透明）。
 *
 * toImageBitmap 是 GPU→CPU 同步读回，所以控件尺寸必须小（这里只给 42dp 级别的圆形按钮用），
 * 且靠上面的退避把频率压到最低。
 */
private suspend fun GlassLuminanceProbe.measure(buffer: IntArray): Float? {
    if (layer.isReleased) return null
    val layerSize = layer.size
    if (layerSize.width <= 0 || layerSize.height <= 0) return null
    val thumbnail = runCatching {
        val source = layer.toImageBitmap().asAndroidBitmap()
        // API 29+ 的读回可能是 HARDWARE 配置，createScaledBitmap 建不出同配置的目标会抛，
        // 且 HARDWARE 位图不能 readPixels，先落成软件位图。
        val software = if (source.config == Bitmap.Config.HARDWARE) {
            source.copy(Bitmap.Config.ARGB_8888, false)
        } else {
            source
        }
        val scaled = Bitmap.createScaledBitmap(software, ThumbnailSide, ThumbnailSide, true)
        if (scaled.config == Bitmap.Config.ARGB_8888) {
            scaled
        } else {
            scaled.copy(Bitmap.Config.ARGB_8888, false)
        }
    }.getOrNull() ?: return null
    runCatching { thumbnail.asImageBitmap().readPixels(buffer) }.getOrNull() ?: return null

    var sum = 0f
    var count = 0
    for (argb in buffer) {
        // 透明像素的 RGB 是预乘后的 0，算进平均会把玻璃误判成"背后很暗"。
        if ((argb ushr 24) < MinOpaqueAlpha) continue
        val r = (argb shr 16 and 0xFF) / 255f
        val g = (argb shr 8 and 0xFF) / 255f
        val b = (argb and 0xFF) / 255f
        sum += 0.2126f * r + 0.7152f * g + 0.0722f * b
        count++
    }
    return if (count == 0) null else sum / count
}

/** 5x5 = 25 个采样点，够描述一个 42dp 控件背后的明暗，代价接近零。官方同值。 */
private const val ThumbnailSide = 5
private const val MinOpaqueAlpha = 0x40
/** 初值 0.5 → signedLuminance = 0 → 首帧与未接探针时完全一致。 */
private const val NeutralLuminance = 0.5f
private const val StableThreshold = 0.02f
private const val AdaptDurationMillis = 500
private const val MinSampleIntervalMillis = 200L
private const val MaxSampleIntervalMillis = 1600L
private const val BrightBlurScale = 2f
private const val DarkBlurScale = 0.35f
private const val BrightBrightness = 0.32f
private const val DarkBrightness = -0.16f
private const val BrightContrast = 0.55f
