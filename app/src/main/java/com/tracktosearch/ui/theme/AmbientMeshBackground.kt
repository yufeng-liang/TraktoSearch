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
    AmbientMeshBackground(
        modifier = modifier,
        preset = preset,
        enabled = enabled,
        motionActive = motionActive,
        washoutOverride = null,
    )
}

/**
 * 背景光晕层，可覆盖两道压制量。
 *
 * [washoutOverride] 只给 `scrimpreview` 预览宿主铺候选档用（见
 * `app/src/scrimpreview/`），生产路径一律传 null 走 [meshWashout] 的现行值。
 */
@Composable
internal fun AmbientMeshBackground(
    modifier: Modifier = Modifier,
    preset: MeshPreset = MeshPreset.BLOOM,
    enabled: Boolean = true,
    motionActive: () -> Boolean = { true },
    washoutOverride: MeshWashout?,
) {
    val colorScheme = MaterialTheme.colorScheme
    val isDark = colorScheme.isDarkScheme
    val washout = washoutOverride ?: meshWashout(preset, isDark)
    val palette = remember(colorScheme, isDark, preset, washout) {
        ambientPaletteFor(preset, colorScheme, isDark, washout)
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
                val scrim = colorScheme.background.copy(alpha = washout.scrim)
                Box(Modifier.fillMaxSize().background(scrim))
            }
        }
    }
}

/**
 * 光晕的两道压制量：色点向页面背景混合的比例 [mix]，以及蒙层的 alpha [scrim]。
 *
 * 关键取舍：让背景"不喧宾夺主"主要靠 [paperPalette] / [themePalette] 在**源头**把配色向
 * 背景色混合，而不是靠加厚这层灰蒙层。蒙层是全屏均匀降低对比度，加厚会把整片彩色一起
 * 压成脏灰（就是旧实现 0.40/0.55 的结果）——花了 shader 的成本却看不到颜色。
 * 源头降饱和只压极值，色相和弥散结构还留着，蒙层就能做得很薄。
 *
 * 两道都是把颜色朝同一个 [MeshPreset] 无关的 `colorScheme.background` 拉近，对色点之间的
 * 间距是同一个缩放因子，所以观感上一阶等价、只有合计量有意义：
 * 合计 = 1 − (1 − mix) × (1 − scrim)。例外是 BLOOM / LAVA_LAMP——它们走
 * GrainGradient / Metaballs 并传了 `colorBack = background`，着色器**内部**还会再朝背景
 * 合成一次，合计量还要再乘一道，只能上真机量。
 */
internal data class MeshWashout(val mix: Float, val scrim: Float)

/**
 * 六预设 × 深浅两档的现行压制量。
 *
 * 深色档 2026-09-27 分两轮放松，判据是**真机实测彩度**（`scripts/scrim-shot.sh` 铺的
 * 对照网格：一屏一个自由量、四格冻在同一相位、每格叠上真页面里那些裸压在光晕上的文字
 * 与卡片，量每格底部净背景条的 max−min 通道，0–255 档）：
 *   第一轮动 mix —— 主题色系 0.62 → 0.30（雷诺阿粉 17.0 → 30.3、星夜蓝 22.4 → 40.9、
 *     票根 14.9 → 22.6）、星云 0.45 → 0.20（10.2 → 16.9）、海滩 0.40 → 0.18（34.5 → 54.2）
 *   第二轮动 scrim —— 主题色系 0.18 → 0.09（30.3 → 33.9）、星云与海滩 0.22 → 0.11
 *     （16.9 → 19.6 / 54.2 → 62.2）。这一刀收益只有 mix 那一刀的四分之一，收到一半
 *     而不是收光是因为网格每格只放了一行 12sp 卡外标题，真页面是一整屏那样的标题，
 *     它对文字的代价被系统性低估了。
 * 两轮的结论一致：抱怨的是"看不出颜色"，主犯是 mix，蒙层是次要的。
 *
 * 水墨不参与这两轮：它是纯黑白，彩度量纲失效（八个候选实测恒在 1.7–2.2，只有亮度在动），
 * 判据只能是明暗对比。它那层 0.30 蒙层是六档最厚、也是唯一有实际职责的一处——压色点里
 * 那颗纯白，压在浅字上真会糊，所以跟着撤的是观感不是噪声。
 *
 * 需求方明确撤掉了"渲后最坏对比度"这条门槛，所以这里没有对比度下限可引。早先记在 mix
 * 上的那些 4.27:1 / 5.4:1 / 3.9:1 推导随本次作废（其中 4.27 是次要文字口径、正文实算
 * 5.73，本来就不是同一条线）。浅色档两轮都没动。
 */
internal fun meshWashout(preset: MeshPreset, isDark: Boolean): MeshWashout = when {
    // 浅色档仍按"最暗那一档色与正文色的对比度"反推：票根浅色正文是 TicketInkLight
    // #3E2A1E（次要文字 #574536），INK 的纯黑最难压，浅色主题给 0.62。
    preset == MeshPreset.INK -> MeshWashout(if (isDark) 0.50f else 0.62f, if (isDark) 0.30f else 0.26f)
    // 星云五档源色全是浅色（#E9C8DC / #F89FBD 这一族），朝近黑背景混过去时彩度和亮度
    // 会**双降**：0.45 那档实测彩度只有 10.2，比主题色系当时的现值还低四成，读作
    // "发亮的灰"而不是"有颜色的灰"。压到 0.20 抬回 16.9。
    // 浅色档的蒙层 0.01 形同已撤：mix 已经保证了可读性，再压一道纯属压颜色。
    preset == MeshPreset.NEBULA -> MeshWashout(if (isDark) 0.20f else 0.21f, if (isDark) 0.11f else 0.01f)
    // 浅色档 0.32 照旧：BEACH 四档全是亮色，不必多压，保留鲜艳。
    // 深色档 mix 与蒙层各降了一轮，是六档里最艳的一档——压它的只有观感判断。
    preset == MeshPreset.BEACH -> MeshWashout(if (isDark) 0.18f else 0.32f, if (isDark) 0.11f else 0.16f)
    else -> MeshWashout(if (isDark) 0.30f else 0.42f, if (isDark) 0.09f else 0.10f)
}

/** 光晕实际喂给着色器的色点：固定配色预设走 [paperPalette]，主题色驱动走 [themePalette]。 */
internal fun ambientPaletteFor(
    preset: MeshPreset,
    colorScheme: ColorScheme,
    isDark: Boolean,
    washout: MeshWashout,
): List<Color> = if (preset.isPaperPreset) {
    paperPalette(preset, colorScheme.background, washout.mix)
} else {
    themePalette(colorScheme, washout.mix)
}

/**
 * 把主题色向背景色混合，得到低饱和的"弥散"光斑色。
 * 直接用 primary/secondary/tertiary 原色会过艳，只能靠重蒙层压，结果发灰；
 * 在源头降对比反而更干净，也让蒙层可以做薄。
 */
private fun themePalette(colorScheme: ColorScheme, mix: Float): List<Color> {
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
private fun paperPalette(preset: MeshPreset, background: Color, mix: Float): List<Color> {
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
