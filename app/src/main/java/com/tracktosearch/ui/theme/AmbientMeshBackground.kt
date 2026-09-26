package com.tracktosearch.ui.theme

import android.os.Build
import androidx.annotation.RequiresApi
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.tooling.preview.Preview
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.currentStateAsState
import io.androidpoet.mirage.GrainGradient
import io.androidpoet.mirage.GrainGradientShape
import io.androidpoet.mirage.Metaballs
import io.androidpoet.mirage.core.ShaderFit
import io.androidpoet.mirage.core.SizingParams
import io.androidpoet.mirage.MeshGradient as ShaderMeshGradient
import io.github.om252345.composemeshgradient.MeshGradient as LegacyMeshGradient
import io.github.om252345.composemeshgradient.rememberMeshGradientState
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/**
 * 主页面背景彩色弥散光晕预设。
 *
 * [INK] 与 [BEACH] 是 Paper Shaders 官网 mesh-gradient 的原始预设（Ink / Beach），用它们自带的固定
 * 配色，不跟随主题色；Paper 的 Purple 预设按需求未收录。[NEBULA] 当初照抄的是官方 Default 预设，
 * 配色现已改向霉粉彩蛋出题页的天空（见 [paperPalette] 的注释），但运动参数仍是官方原值。
 * 后三个是主题色驱动：调色板取自当前主题种子色，四个页面共享同一层，仅运动方式不同。
 *
 * 枚举顺序与设置里的选项顺序一致；持久化按 name() 存，改顺序不影响已保存的值。
 */
enum class MeshPreset {
    NEBULA,     // 出题页天空：云隙粉 / 天蓝 / 柔粉 / 深天蓝 / 薰衣草（原官方 Default 四色已换）
    INK,        // Paper "Ink"：纯黑白，旋转 90°
    BEACH,      // Paper "Beach"：青蓝 / 湖蓝 / 亮青 / 沙黄
    AURORA,     // 极光：色斑沿轨迹流动
    LAVA_LAMP,  // 熔岩灯：blob 融合漂浮
    BLOOM;      // 弥散绽放：呼吸式扩散

    /** Paper 预设用固定配色，可读性蒙层也要更厚，因此需要区分。 */
    internal val isPaperPreset: Boolean
        get() = this == NEBULA || this == INK || this == BEACH

    fun toStorage(): String = name

    companion object {
        /** 默认 BLOOM：星云是霉粉彩蛋解锁内容，未解锁时选项不可见，不能当默认值。 */
        fun fromStorage(value: String?): MeshPreset {
            if (value == null) return BLOOM
            return runCatching { valueOf(value) }.getOrDefault(BLOOM)
        }
    }
}
/**
 * 背景光晕层。
 *
 * API 33+ 走 AGSL 着色器（mirage，Paper Shaders 的 AGSL 移植）：弥散完全在片元着色器里算，
 * 没有网格控制点，因此不会出现网格塌陷造成的不规则硬边；自带 grain 噪声，顺带消掉
 * 大面积渐变的色带。API 26~32 无 RuntimeShader，回退到 composemeshgradient 的 4x4 网格。
 */
@Composable
fun AmbientMeshBackground(
    modifier: Modifier = Modifier,
    preset: MeshPreset = MeshPreset.BLOOM,
    enabled: Boolean = true,
    motionActive: () -> Boolean = { true },
) {
    val colorScheme = MaterialTheme.colorScheme
    val isDark = colorScheme.isDarkScheme
    val palette = remember(colorScheme, isDark, preset) {
        if (preset.isPaperPreset) {
            paperPalette(preset, colorScheme.background, isDark)
        } else {
            themePalette(colorScheme, isDark)
        }
    }

    // 息屏/切后台时冻结动画。mirage 的时间是逐帧累加进 MutableFloatState 的（见
    // core/ShaderTime.kt），speed = 0f 停在当前累加值而不是回到 0，所以不会跳变；
    // 且 time 不再变化后着色器不再失效，等于零帧开销。
    // 注：Compose 的 MonotonicFrameClock 在窗口不可见时本就不发帧，这里主要是把
    // "不可见还在跑" 的边界情况（分屏、被半透明 Activity 覆盖等）也确定性地掐掉。
    val lifecycleState by LocalLifecycleOwner.current.lifecycle.currentStateAsState()
    // motionActive：没人操作时也停帧。shader 时间是逐帧累加的，只要还在跑就等于整窗满帧重绘，
    // 静止阅读时白烧 GPU；停帧后时间冻在当前值，恢复时不跳变。
    val speedScale = if (lifecycleState.isAtLeast(Lifecycle.State.STARTED) && motionActive()) {
        1f
    } else {
        0f
    }
    val baseBackground = colorScheme.background

    Box(modifier = modifier.fillMaxSize().background(baseBackground)) {
        if (enabled) {
            // 停帧（speed=0）时把 shader 层缓存成离屏纹理：转场/其他重绘触发父层重录时
            // 只做纹理 blit，不再每帧执行全屏 AGSL shader（真机返回段 draw+GPU 42ms → 约 7ms）。
            // 恢复流动时移除离屏策略，shader 每帧直接绘制；内容变化会自然使缓存失效重录。
            val shaderBoxModifier = Modifier
                .fillMaxSize()
                .then(
                    if (speedScale <= 0f) {
                        Modifier.graphicsLayer {
                            compositingStrategy = CompositingStrategy.Offscreen
                        }
                    } else {
                        Modifier
                    }
                )
            Box(modifier = shaderBoxModifier) {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    ShaderAmbient(preset, palette, colorScheme.background, speedScale)
                } else {
                    LegacyMeshAmbient(preset, palette, speedScale > 0f)
                }
                val scrim = colorScheme.background.copy(alpha = scrimAlpha(preset, isDark))
                Box(Modifier.fillMaxSize().background(scrim))
            }
        }
    }
}

/**
 * 可读性蒙层强度。
 *
 * 关键取舍：让背景"不喧宾夺主"主要靠 [paperPalette] / [themePalette] 在**源头**把配色向
 * 背景色混合，而不是靠加厚这层灰蒙层。蒙层是全屏均匀降低对比度，加厚会把整片彩色一起
 * 压成脏灰（就是旧实现 0.40/0.55 的结果）——花了 shader 的成本却看不到颜色。
 * 源头降饱和只压极值，色相和弥散结构还留着，蒙层就能做得很薄。
 */
private fun scrimAlpha(preset: MeshPreset, isDark: Boolean): Float = when {
    preset == MeshPreset.INK -> if (isDark) 0.30f else 0.26f
    // NEBULA 五档都是浅色、mix 也只压到 0.21，可读性已由 mix 保证
    // （浅底最坏 3.69:1、深底 4.27:1），蒙层再压一道就纯属压颜色了，所以浅色档几乎撤掉。
    // 深色档保留 0.22：那一屏正文最密，且浅色彩斑压在浅字上最容易糊。
    preset == MeshPreset.NEBULA -> if (isDark) 0.22f else 0.01f
    preset.isPaperPreset -> if (isDark) 0.22f else 0.16f     // 走到这里只剩 BEACH
    else -> if (isDark) 0.18f else 0.10f
}
/**
 * 把主题色向背景色混合，得到低饱和的"弥散"光斑色。
 * 直接用 primary/secondary/tertiary 原色会过艳，只能靠重蒙层压，结果发灰；
 * 在源头降对比反而更干净，也让蒙层可以做薄。
 */
private fun themePalette(colorScheme: ColorScheme, isDark: Boolean): List<Color> {
    val mix = if (isDark) 0.62f else 0.42f
    fun soft(color: Color): Color = lerp(color, colorScheme.background, mix)
    return listOf(
        soft(colorScheme.primary),
        soft(colorScheme.tertiary),
        soft(colorScheme.secondary),
        soft(colorScheme.primaryContainer),
        soft(colorScheme.tertiaryContainer),
    )
}

/**
 * 固定配色档位：向页面背景色混合一次，把亮度/饱和的极值收进来——
 * 这是"不喧宾夺主"的主要手段。
 *
 * INK 与 BEACH 照抄 paper-design/shaders 官方同名预设的色相与相对关系。
 * NEBULA 原本照抄官方 Default 预设，现已改向霉粉彩蛋出题页的天空，见下方 [MeshPreset.NEBULA] 分支。
 */
private fun paperPalette(preset: MeshPreset, background: Color, isDark: Boolean): List<Color> {
    // 混合比例按"最暗/最亮那一档色与正文色的 WCAG 对比度"反推。正文取当前主题的实际值：
    // 票根浅色是 TicketInkLight #3E2A1E（次要文字 #574536）、票根深色是 TicketInkDark #F7EDE3。
    // 早先这里记的 #5D4638 全仓不存在，是失效注释。
    //   BEACH 全是亮色，0.32 就有约 5.4:1，不必多压，保留鲜艳。
    //   INK 纯黑最难，浅色主题给 0.62（约 3.9:1）。
    //   NEBULA 五档全是浅色，浅色主题不再需要压到 0.52：0.21 就有 3.69:1
    //     （同主题下换色前的官方 Default 是 3.62:1，同级线就在这附近），压多了只会重新变灰。
    //     深色主题反过来必须压重：0.30 只剩 2.40:1，0.45 才回到 4.27:1，那是下限不是审美值。
    val mix = when (preset) {
        MeshPreset.INK -> if (isDark) 0.50f else 0.62f
        MeshPreset.BEACH -> if (isDark) 0.40f else 0.32f
        MeshPreset.NEBULA -> if (isDark) 0.45f else 0.21f
        else -> if (isDark) 0.45f else 0.52f
    }
    // lerp(Color, Color, Float) 在 ui 1.12.1 走 Oklab 而非通道线性（调用栈
    // ColorKt.lerp → Color.convert → Connector.transformToColor → Oklab.xyzaToColor）。
    // 网页端对色必须同口径，否则混出来的色对不上真机。
    fun tame(color: Color): Color = lerp(color, background, mix)
    return when (preset) {
        // 观感参照是彩蛋出题页那张位图天空（R.drawable.swiftie_poster_sky，代码里没有色源）。
        // 对位图全图 85978 点采样：粉 73.3% / 蓝 14.4% / 紫 12.0%，蓝天集中在左上角一角。
        //
        // 判据不是"总彩度"，而是 tame+scrim 之后的**渲染色度排序**：shader 输出是各档色的
        // 凸组合（权重恒正），极值必落在某个色点顶点上，所以逐档渲后值就是整幅画面的上下界，
        // 与动画帧无关。上一版四色渲成 蓝.086 > 粉.070 > 蓝.061 > 粉.035——第一名是蓝，
        // 肉眼就读作"蓝灰"，尽管它的平均彩度 .063 比换色前的官方 Default .058 还高一点。
        // 现在这五档渲成 粉.086 > 紫.075 > 蓝.066 > 蓝.061 > 粉.035，逐位对上旧 Default 的
        // 粉.084 > 紫.075 > 蓝紫.067，把第一名还给粉。
        // 逐档来源：
        //   云隙粉 #E9C8DC、天蓝 #7DB7DC —— 位图实测取样，未动。
        //   柔粉 #F89FBD —— 上一版 #FEB3C8 深一档。那颗粉已经踩在自己明度/色相的 sRGB 色域
        //     边界上（源彩度 .091，渲后 .0705，天花板 .0719），不降明度就没有加彩度的余地，
        //     所以"粉第一"只能靠降明度换出色域、或把蓝压下去；这里两边各让一点。
        //   深天蓝 #5F96C1 —— 上一版 #4A97CF 降一档，给粉让位。代价是蓝天浅一档、层次弱一点。
        //   薰衣草 #CFA8E8 —— 新增，有彩度门槛：旧那颗 #C9A8DE 渲后只 .062，排在蓝和粉后面
        //     等于没加（实测和现状肉眼无差）。.075 是"看得见但不抢粉"的下限。
        // 另有一条并片阈值：两档渲后 ΔE 小于约 .05 等于只剩一档。上一版色点 1 与 3 只差 .042，
        // 四档实际三档可辨；现在最小间距 .066（色点 3 与 5）。
        // 色点从 4 增到 5 只是把某档色的占比摊薄，不动算法——mirage 的 u_colors 上限是 10。
        // 历史坑：更早一版拿 #C9A8DE 替紫罗兰 #9F50D3 是空操作，它在 mix 0.21 下渲成 #d3b8e2，
        // 与旧紫罗兰在 mix 0.52 下渲成的 #d4b1e7 只差 ΔE 0.021——降彩度和降压制互相抵消。
        MeshPreset.NEBULA -> listOf(
            Color(0xFFE9C8DC), Color(0xFF7DB7DC), Color(0xFFF89FBD),
            Color(0xFF5F96C1), Color(0xFFCFA8E8),
        )
        MeshPreset.INK -> listOf(Color(0xFFFFFFFF), Color(0xFF000000))
        MeshPreset.BEACH -> listOf(
            Color(0xFFBCECF6), Color(0xFF00AAFF), Color(0xFF00F7FF), Color(0xFFFFD447),
        )
        else -> emptyList()
    }.map(::tame)
}
@RequiresApi(Build.VERSION_CODES.TIRAMISU)
@Composable
private fun ShaderAmbient(
    preset: MeshPreset,
    palette: List<Color>,
    backColor: Color,
    speedScale: Float,
) {
    // Cover：铺满并裁掉溢出。默认的 Contain 是给独立图形用的，长屏当背景会显小。
    val sizing = remember { SizingParams(fit = ShaderFit.Cover) }
    when (preset) {
        MeshPreset.AURORA -> ShaderMeshGradient(
            modifier = Modifier.fillMaxSize(),
            colors = palette,
            distortion = 0.9f,
            swirl = 0.25f,
            grainOverlay = 0.10f,
            speed = 0.22f * speedScale,
            sizing = sizing,
        )
        MeshPreset.LAVA_LAMP -> Metaballs(
            modifier = Modifier.fillMaxSize(),
            colorBack = backColor,
            colors = palette,
            count = 7f,
            size = 1.05f,
            speed = 0.20f * speedScale,
            sizing = sizing,
        )
        MeshPreset.BLOOM -> GrainGradient(
            modifier = Modifier.fillMaxSize(),
            colorBack = backColor,
            colors = palette,
            shape = GrainGradientShape.Blob,
            softness = 0.85f,
            intensity = 0.50f,
            noise = 0.20f,
            speed = 0.25f * speedScale,
            sizing = sizing,
        )
        // distortion / swirl / rotation 与 Paper Shaders 官方预设一致；
        // speed 按需求单独定档（Paper 原值 NEBULA/INK 都是 1.0，BEACH 是 0.1）：
        // NEBULA / INK 都压到 0.20，BEACH 原值太慢近似静止，提到 0.15。
        MeshPreset.NEBULA -> ShaderMeshGradient(
            modifier = Modifier.fillMaxSize(),
            colors = palette,
            distortion = 0.8f,
            swirl = 0.1f,
            speed = 0.20f * speedScale,
            sizing = sizing,
        )
        MeshPreset.INK -> ShaderMeshGradient(
            modifier = Modifier.fillMaxSize(),
            colors = palette,
            distortion = 1f,
            swirl = 0.2f,
            speed = 0.20f * speedScale,
            sizing = remember { SizingParams(fit = ShaderFit.Cover, rotation = 90f) },
        )
        MeshPreset.BEACH -> ShaderMeshGradient(
            modifier = Modifier.fillMaxSize(),
            colors = palette,
            distortion = 0.8f,
            swirl = 0.35f,
            speed = 0.15f * speedScale,
            sizing = sizing,
        )
    }
}
private const val TWO_PI = 2f * PI.toFloat()
private const val LEGACY_GRID = 4

/**
 * API 26~32 回退实现。AGSL 不可用，Paper 预设只能借用它的配色，运动方式退化为网格漂移。
 *
 * 两条硬约束：
 *
 * 1. 边界控制点必须钉死在矩形边上。网格只覆盖控制点围成的区域，一旦四角或边中点往内收，
 *    网格就会从屏幕边缘缩进露出底色，看起来就是一条不规则的硬边——原实现是 3x3 且九个点
 *    全在动，而 3x3 里除中心点外全是边界点，所以整块都在漏。
 * 2. 运动只能用 phase 的整数倍谐波。像 cos(0.85 * phase) 这种非整数倍在 phase 绕回 2π
 *    时不连续，会周期性地"跳"一下。
 */
@Composable
private fun LegacyMeshAmbient(
    preset: MeshPreset,
    palette: List<Color>,
    animating: Boolean,
) {
    val basePoints = remember {
        Array(LEGACY_GRID * LEGACY_GRID) { i ->
            Offset(
                x = (i % LEGACY_GRID) / (LEGACY_GRID - 1f),
                y = (i / LEGACY_GRID) / (LEGACY_GRID - 1f),
            )
        }
    }
    val colors = remember(palette) {
        Array(LEGACY_GRID * LEGACY_GRID) { i -> palette[i % palette.size] }
    }
    val state = rememberMeshGradientState(points = basePoints, colors = colors)

    val amplitude = when (preset) {
        MeshPreset.LAVA_LAMP -> 0.16f
        MeshPreset.BLOOM -> 0.07f
        else -> 0.10f
    }
    val periodMs = when (preset) {
        MeshPreset.LAVA_LAMP -> 29_000L
        MeshPreset.BLOOM -> 18_000L
        MeshPreset.BEACH -> 40_000L
        else -> 23_000L
    }
    // 累加"动画自己的时间"而不是直接用帧时间戳：冻结期间不累加，恢复后从原相位继续，
    // 不会因为墙上时钟走过而跳一段。用裸数组而非 State，避免每帧写入触发重组。
    val elapsedMs = remember { LongArray(1) }
    LaunchedEffect(preset, colors, animating) {
        if (!animating) return@LaunchedEffect
        val working = basePoints.toMutableList()
        var lastNanos = 0L
        while (true) {
            val frameNanos = withFrameNanos { it }
            if (lastNanos != 0L) {
                elapsedMs[0] += (frameNanos - lastNanos) / 1_000_000L
            }
            lastNanos = frameNanos
            val phase = (elapsedMs[0] % periodMs) / periodMs.toFloat() * TWO_PI
            for (i in working.indices) {
                val col = i % LEGACY_GRID
                val row = i / LEGACY_GRID
                val isBorder =
                    row == 0 || col == 0 || row == LEGACY_GRID - 1 || col == LEGACY_GRID - 1
                if (isBorder) continue
                val base = basePoints[i]
                working[i] = Offset(
                    x = base.x + amplitude * sin(phase + i * 0.9f),
                    y = base.y + amplitude * cos(phase + i * 0.7f),
                )
            }
            state.snapAllPoints(working)
        }
    }

    LegacyMeshGradient(
        modifier = Modifier.fillMaxSize(),
        width = LEGACY_GRID,
        height = LEGACY_GRID,
        state = state,
    )
}

@Preview
@Composable
private fun AmbientMeshBackgroundPreview() {
    MaterialTheme {
        AmbientMeshBackground(
            modifier = Modifier.fillMaxSize(),
            preset = MeshPreset.NEBULA,
            enabled = true,
        )
    }
}
