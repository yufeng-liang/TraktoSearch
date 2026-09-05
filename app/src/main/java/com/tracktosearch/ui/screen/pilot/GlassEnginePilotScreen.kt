@file:OptIn(dev.chrisbanes.haze.ExperimentalHazeApi::class)

package com.tracktosearch.ui.screen.pilot

import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Favorite
import androidx.compose.material.icons.rounded.Home
import androidx.compose.material.icons.rounded.Person
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.kyant.backdrop.Backdrop
import com.kyant.backdrop.backdrops.LayerBackdrop
import com.kyant.backdrop.backdrops.layerBackdrop
import com.kyant.backdrop.backdrops.rememberLayerBackdrop
import com.kyant.backdrop.drawBackdrop
import com.kyant.backdrop.effects.blur
import com.kyant.backdrop.effects.colorControls
import com.kyant.backdrop.effects.lens
import com.kyant.backdrop.effects.vibrancy
import com.kyant.backdrop.highlight.Highlight
import com.kyant.backdrop.highlight.HighlightStyle
import com.kyant.backdrop.shadow.InnerShadow
import com.kyant.backdrop.shadow.Shadow
import com.tracktosearch.ui.component.GlassScene
import com.tracktosearch.ui.component.GlassSurfaceRole
import com.tracktosearch.ui.component.NeumorphicFrostedSurface
import com.tracktosearch.ui.component.NeumorphicIconButton
import com.tracktosearch.ui.component.isAppDarkTheme
import com.tracktosearch.ui.theme.LocalVisualEffectMode
import com.tracktosearch.ui.theme.VisualEffectMode
import dev.chrisbanes.haze.HazeInput
import dev.chrisbanes.haze.HazePerformanceMode
import dev.chrisbanes.haze.HazeSourceSelection
import dev.chrisbanes.haze.HazeState
import dev.chrisbanes.haze.blur.HazeBlurStyle
import dev.chrisbanes.haze.blur.hazeBlur
import dev.chrisbanes.haze.blur.materials.HazeMaterials
import dev.chrisbanes.haze.hazeSource
import kotlin.math.roundToInt

/**
 * 玻璃引擎试点页(仅 DEBUG 注册)。
 *
 * 目的：在同一屏上对比两套玻璃引擎的「模糊 / 玻璃」两种模式，并实时调参：
 * - Haze（模糊参照）：复用 NeumorphicFrostedSurface / NeumorphicIconButton，
 *   由 LocalVisualEffectMode 分发给成熟 hazeBlur。模糊分支支持调 blurRadius / tintAlpha。
 * - Backdrop（新引擎，com.kyant.backdrop）：drawBackdrop + blur / lens 效果，
 *   自带镜面高光(Highlight)与投影(Shadow)，10 项参数全部实时可调。
 *
 * 结论口径：玻璃模式目标是彻底脱离 haze-glass，以 backdrop 的液态玻璃为主；
 * 模糊模式保留成熟 haze 作为参照，参数调好后可直接搬进真实页面。
 */
@Composable
fun GlassEnginePilotScreen(onBack: () -> Unit) {
    var engine by rememberSaveable { mutableStateOf(PilotEngine.BACKDROP) }
    var mode by rememberSaveable { mutableStateOf(VisualEffectMode.GLASS) }
    var selectedScene by rememberSaveable { mutableStateOf("card") }
    val hazeState = remember { HazeState() }
    val backgroundColor = MaterialTheme.colorScheme.background
    val isDark = isAppDarkTheme()
    // 背景色画进 backdrop 层，保证玻璃区域之外不透出空洞。
    // onDraw 单独 remember 稳定化，避免每次重组重建 LayerBackdrop。
    val backdrop = rememberLayerBackdrop(
        onDraw = remember(backgroundColor) {
            {
                drawRect(backgroundColor)
                drawContent()
            }
        }
    )
    // 每场景独立参数：切换场景保留各自调参结果；重置时恢复默认。
    val defaultBackdropParams = remember(isDark) {
        mapOf(
            "bottomBar" to pilotParams("bottomBar", isDark),
            "card" to pilotParams("card", isDark),
            "button" to pilotParams("button", isDark)
        )
    }
    val defaultHazeParams = remember(isDark) {
        mapOf(
            "bottomBar" to HazeBlurParams(blurRadius = 36.dp, tintAlpha = if (isDark) 0.12f else 0.20f),
            "card" to HazeBlurParams(blurRadius = 40.dp, tintAlpha = if (isDark) 0.15f else 0.25f),
            "button" to HazeBlurParams(blurRadius = 28.dp, tintAlpha = if (isDark) 0.08f else 0.15f)
        )
    }
    val sceneBackdropParams = remember {
        mutableStateMapOf(
            "bottomBar" to defaultBackdropParams.getValue("bottomBar"),
            "card" to defaultBackdropParams.getValue("card"),
            "button" to defaultBackdropParams.getValue("button")
        )
    }
    val sceneHazeParams = remember {
        mutableStateMapOf(
            "bottomBar" to defaultHazeParams.getValue("bottomBar"),
            "card" to defaultHazeParams.getValue("card"),
            "button" to defaultHazeParams.getValue("button")
        )
    }
    val currentBackdrop = sceneBackdropParams.getValue(selectedScene)
    val currentHaze = sceneHazeParams.getValue(selectedScene)
    // 调参面板总高（含导航条避让），演示对象定位在其上方
    val panelHeight = 330.dp

    Box(
        Modifier
            .fillMaxSize()
            .background(backgroundColor)
    ) {
        // 背景内容：Haze 引擎只挂 hazeSource；Backdrop 引擎挂 layerBackdrop。
        // 刻意不共存，避免采样层互相干扰导致 Haze 模糊失效。
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .hazeSource(hazeState)
                .layerBackdrop(backdrop),
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            items(14) { index -> PilotColorfulCard(index) }
        }

        // 顶部控制条（普通 Material 表面，不参与玻璃采样）
        PilotControlBar(
            engine = engine,
            onEngineChange = { engine = it },
            mode = mode,
            onModeChange = { mode = it },
            selectedScene = selectedScene,
            onSceneChange = { selectedScene = it },
            onBack = onBack
        )

        // 玻璃演示对象：仅渲染选中场景，避免互相遮挡
        if (selectedScene == "card") {
            PilotGlassCard(
                engine = engine,
                mode = mode,
                hazeState = hazeState,
                backdrop = backdrop,
                backdropParams = sceneBackdropParams.getValue("card"),
                hazeParams = sceneHazeParams.getValue("card"),
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .padding(top = 400.dp)
            )
        }
        if (selectedScene == "button") {
            Row(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(bottom = panelHeight + 96.dp),
                horizontalArrangement = Arrangement.spacedBy(22.dp)
            ) {
                repeat(3) {
                    PilotGlassButton(
                        engine = engine,
                        mode = mode,
                        hazeState = hazeState,
                        backdrop = backdrop,
                        backdropParams = sceneBackdropParams.getValue("button"),
                        hazeParams = sceneHazeParams.getValue("button"),
                        onClick = {}
                    )
                }
            }
        }
        if (selectedScene == "bottomBar") {
            PilotGlassBottomBar(
                engine = engine,
                mode = mode,
                hazeState = hazeState,
                backdrop = backdrop,
                backdropParams = sceneBackdropParams.getValue("bottomBar"),
                hazeParams = sceneHazeParams.getValue("bottomBar"),
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(bottom = panelHeight + 20.dp)
            )
        }

        // 底部实时调参面板
        PilotParamPanel(
            engine = engine,
            mode = mode,
            selectedScene = selectedScene,
            backdropParams = currentBackdrop,
            onBackdropParamsChange = { sceneBackdropParams[selectedScene] = it },
            hazeParams = currentHaze,
            onHazeParamsChange = { sceneHazeParams[selectedScene] = it },
            onReset = {
                sceneBackdropParams[selectedScene] = defaultBackdropParams.getValue(selectedScene)
                sceneHazeParams[selectedScene] = defaultHazeParams.getValue(selectedScene)
            },
            modifier = Modifier.align(Alignment.BottomCenter)
        )
    }
}

private enum class PilotEngine { HAZE, BACKDROP }

/** 各场景的 backdrop 玻璃光学参数（对应 GlassSurfaceRole 的语义，按场景深度调参）。 */
private data class BackdropGlassParams(
    val shape: RoundedCornerShape,
    val blurRadius: Dp,
    val refractionHeight: Dp,
    val refractionAmount: Dp,
    val depthEffect: Boolean,
    val chromaticAberration: Boolean,
    val highlightWidth: Dp,
    val highlightAlpha: Float,
    val shadowRadius: Dp,
    val shadowAlpha: Float,
    val innerShadowRadius: Dp = 0.dp
)

/** Haze 模糊分支的可调参数（模糊模式保留成熟 haze，仅暴露影响观感的两项）。 */
private data class HazeBlurParams(
    val blurRadius: Dp,
    val tintAlpha: Float
)

/**
 * 场景参数（backdrop 默认值）：
 * - bottomBar：Lip 感，折射强 + 轻微色散，投影重（悬浮感）
 * - card：清透聚焦，深度折射 + 色散，投影轻
 * - button：圆形控件，高光最亮（iOS 控制中心镜面边缘），折射收敛避免噪点
 */
private fun pilotParams(role: String, isDark: Boolean): BackdropGlassParams = when (role) {
    "bottomBar" -> BackdropGlassParams(
        shape = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp),
        blurRadius = 18.dp,
        refractionHeight = 10.dp,
        refractionAmount = 36.dp,
        depthEffect = false,
        chromaticAberration = true,
        highlightWidth = 0.75.dp,
        highlightAlpha = if (isDark) 0.85f else 0.90f,
        shadowRadius = 28.dp,
        shadowAlpha = if (isDark) 0.50f else 0.22f
    )

    "card" -> BackdropGlassParams(
        shape = RoundedCornerShape(24.dp),
        blurRadius = 14.dp,
        refractionHeight = 8.dp,
        refractionAmount = 26.dp,
        depthEffect = true,
        chromaticAberration = true,
        highlightWidth = 0.5.dp,
        highlightAlpha = if (isDark) 0.70f else 0.75f,
        shadowRadius = 20.dp,
        shadowAlpha = if (isDark) 0.45f else 0.16f,
        innerShadowRadius = 10.dp
    )

    else -> BackdropGlassParams(
        shape = RoundedCornerShape(50),
        blurRadius = 12.dp,
        refractionHeight = 6.dp,
        refractionAmount = 18.dp,
        depthEffect = true,
        chromaticAberration = false,
        highlightWidth = 0.9.dp,
        highlightAlpha = if (isDark) 1.0f else 0.95f,
        shadowRadius = 14.dp,
        shadowAlpha = if (isDark) 0.45f else 0.18f
    )
}

/**
 * backdrop 玻璃表面统一入口：模糊模式 = blur + 轻微对比度增强；
 * 玻璃模式 = blur + lens 折射（含色散/深度选项）。高光与投影由 drawBackdrop 内建。
 */
@Composable
private fun Modifier.pilotBackdropGlass(
    backdrop: Backdrop,
    mode: VisualEffectMode,
    params: BackdropGlassParams,
    pressed: Boolean = false
): Modifier {
    val isDark = isAppDarkTheme()
    return drawBackdrop(
        backdrop = backdrop,
        shape = { params.shape },
        effects = {
            // 官方文档规定 effect 顺序：color filter ⇒ blur ⇒ lens
            if (mode == VisualEffectMode.GLASS) {
                vibrancy()
            } else {
                colorControls(contrast = 1.03f, saturation = 1.05f)
            }
            blur(params.blurRadius.toPx())
            if (mode == VisualEffectMode.GLASS) {
                lens(
                    refractionHeight = params.refractionHeight.toPx(),
                    refractionAmount = (
                        params.refractionAmount * if (pressed) 1.14f else 1f
                    ).toPx(),
                    depthEffect = params.depthEffect,
                    chromaticAberration = params.chromaticAberration
                )
            }
        },
        highlight = {
            Highlight(
                width = params.highlightWidth,
                alpha = (
                    params.highlightAlpha * if (pressed) 1.18f else 1f
                ).coerceAtMost(1f),
                style = HighlightStyle.Default
            )
        },
        shadow = {
            Shadow(
                radius = params.shadowRadius,
                offset = DpOffset(0.dp, params.shadowRadius / 4f),
                color = Color.Black.copy(alpha = params.shadowAlpha)
            )
        },
        innerShadow = if (params.innerShadowRadius > 0.dp) {
            {
                InnerShadow(
                    radius = params.innerShadowRadius,
                    offset = DpOffset(0.dp, (-params.innerShadowRadius) / 4f),
                    color = Color.White.copy(alpha = if (isDark) 0.05f else 0.45f)
                )
            }
        } else {
            null
        }
    )
}

/** Haze 模糊分支的 HazeBlurStyle：tint 透明度 + 模糊半径。 */
@Composable
private fun rememberHazeBlurStyle(params: HazeBlurParams): HazeBlurStyle {
    // 官方 Quick start 写法: HazeBlurStyle { blurRadius(...) },tint 用 backgroundColor。
    // 不使用 HazeMaterials.thin 预设,避免其内置参数干扰模糊采样。
    val tint = MaterialTheme.colorScheme.surface.copy(alpha = params.tintAlpha)
    return remember(params, tint) {
        HazeBlurStyle {
            blurRadius(params.blurRadius)
            backgroundColor(tint)
        }
    }
}

/** 顶部控制条：返回 + 引擎/模式/场景切换。 */
@Composable
private fun PilotControlBar(
    engine: PilotEngine,
    onEngineChange: (PilotEngine) -> Unit,
    mode: VisualEffectMode,
    onModeChange: (VisualEffectMode) -> Unit,
    selectedScene: String,
    onSceneChange: (String) -> Unit,
    onBack: () -> Unit
) {
    Column(
        Modifier
            .fillMaxWidth()
            // 背景先画:铺满整行含状态栏区域,再避让状态栏,让状态栏区域显示标题栏填充色而非内容
            .background(MaterialTheme.colorScheme.surface)
            .statusBarsPadding()
            .padding(bottom = 10.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(onClick = onBack) {
                Icon(
                    imageVector = Icons.AutoMirrored.Rounded.ArrowBack,
                    contentDescription = "返回",
                    tint = MaterialTheme.colorScheme.onSurface
                )
            }
            Text(
                text = "玻璃引擎试点",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSurface
            )
        }
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            PilotChip("引擎: Haze", engine == PilotEngine.HAZE) { onEngineChange(PilotEngine.HAZE) }
            PilotChip("引擎: Backdrop", engine == PilotEngine.BACKDROP) { onEngineChange(PilotEngine.BACKDROP) }
        }
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 6.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            PilotChip("模式: 模糊", mode == VisualEffectMode.BLUR) { onModeChange(VisualEffectMode.BLUR) }
            PilotChip("模式: 玻璃", mode == VisualEffectMode.GLASS) { onModeChange(VisualEffectMode.GLASS) }
        }
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            PilotChip("场景: 底栏", selectedScene == "bottomBar") { onSceneChange("bottomBar") }
            PilotChip("场景: 卡片", selectedScene == "card") { onSceneChange("card") }
            PilotChip("场景: 按钮", selectedScene == "button") { onSceneChange("button") }
        }
    }
}

@Composable
private fun PilotChip(label: String, selected: Boolean, onClick: () -> Unit) {
    FilterChip(
        selected = selected,
        onClick = onClick,
        colors = FilterChipDefaults.filterChipColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerHighest,
            labelColor = MaterialTheme.colorScheme.onSurfaceVariant,
            selectedContainerColor = MaterialTheme.colorScheme.primary,
            selectedLabelColor = MaterialTheme.colorScheme.onPrimary
        ),
        label = { Text(label, fontSize = MaterialTheme.typography.bodySmall.fontSize) }
    )
}

/** 底部实时调参面板：按引擎/模式切换参数组。 */
@Composable
private fun PilotParamPanel(
    engine: PilotEngine,
    mode: VisualEffectMode,
    selectedScene: String,
    backdropParams: BackdropGlassParams,
    onBackdropParamsChange: (BackdropGlassParams) -> Unit,
    hazeParams: HazeBlurParams,
    onHazeParamsChange: (HazeBlurParams) -> Unit,
    onReset: () -> Unit,
    modifier: Modifier = Modifier
) {
    Surface(
        modifier = modifier.fillMaxWidth(),
        color = MaterialTheme.colorScheme.surface,
        tonalElevation = 3.dp
    ) {
        Column(
            Modifier
                .fillMaxWidth()
                .navigationBarsPadding()
                .padding(horizontal = 16.dp, vertical = 4.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "调参 · " + selectedScene + " · " + engine.name + " " + mode.name,
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.weight(1f)
                )
                TextButton(onClick = onReset) {
                    Text("重置")
                }
            }
            when {
                engine == PilotEngine.BACKDROP -> {
                    LazyColumn(Modifier.height(244.dp)) {
                        item {
                            PilotSliderRow("模糊半径(px)", backdropParams.blurRadius.value, 0f..48f) {
                                onBackdropParamsChange(backdropParams.copy(blurRadius = it.dp))
                            }
                        }
                        item {
                            PilotSliderRow("折射高度(px)", backdropParams.refractionHeight.value, 0f..28f) {
                                onBackdropParamsChange(backdropParams.copy(refractionHeight = it.dp))
                            }
                        }
                        item {
                            PilotSliderRow("折射量(px)", backdropParams.refractionAmount.value, 0f..96f) {
                                onBackdropParamsChange(backdropParams.copy(refractionAmount = it.dp))
                            }
                        }
                        item {
                            PilotToggleRow("深度效果", backdropParams.depthEffect) {
                                onBackdropParamsChange(backdropParams.copy(depthEffect = it))
                            }
                        }
                        item {
                            PilotToggleRow("色散", backdropParams.chromaticAberration) {
                                onBackdropParamsChange(backdropParams.copy(chromaticAberration = it))
                            }
                        }
                        item {
                            PilotSliderRow("高光宽度(dp)", backdropParams.highlightWidth.value, 0f..2f) {
                                onBackdropParamsChange(backdropParams.copy(highlightWidth = it.dp))
                            }
                        }
                        item {
                            PilotSliderRow("高光强度", backdropParams.highlightAlpha, 0f..1f) {
                                onBackdropParamsChange(backdropParams.copy(highlightAlpha = it))
                            }
                        }
                        item {
                            PilotSliderRow("投影半径(dp)", backdropParams.shadowRadius.value, 0f..48f) {
                                onBackdropParamsChange(backdropParams.copy(shadowRadius = it.dp))
                            }
                        }
                        item {
                            PilotSliderRow("投影透明度", backdropParams.shadowAlpha, 0f..0.8f) {
                                onBackdropParamsChange(backdropParams.copy(shadowAlpha = it))
                            }
                        }
                        item {
                            PilotSliderRow("内阴影半径(dp)", backdropParams.innerShadowRadius.value, 0f..24f) {
                                onBackdropParamsChange(backdropParams.copy(innerShadowRadius = it.dp))
                            }
                        }
                    }
                }

                mode == VisualEffectMode.BLUR -> {
                    LazyColumn(Modifier.height(244.dp)) {
                        item {
                            PilotSliderRow("模糊半径(dp)", hazeParams.blurRadius.value, 0f..48f) {
                                onHazeParamsChange(hazeParams.copy(blurRadius = it.dp))
                            }
                        }
                        item {
                            PilotSliderRow("底色透明度", hazeParams.tintAlpha, 0f..1f) {
                                onHazeParamsChange(hazeParams.copy(tintAlpha = it))
                            }
                        }
                        item {
                            Text(
                                text = "Haze 模糊为成熟方案，仅暴露影响观感的两项参数。",
                                fontSize = 12.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(vertical = 8.dp)
                            )
                        }
                    }
                }

                else -> {
                    Text(
                        text = "Glass 模式统一使用 Backdrop；当前面板参数直接作用于真实折射与高光。",
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(vertical = 12.dp)
                    )
                }
            }
        }
    }
}

@Composable
private fun PilotSliderRow(
    label: String,
    value: Float,
    range: ClosedFloatingPointRange<Float>,
    decimals: Int = 0,
    onValueChange: (Float) -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 1.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = label,
            modifier = Modifier.width(104.dp),
            fontSize = 12.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Slider(
            value = value.coerceIn(range.start, range.endInclusive),
            onValueChange = onValueChange,
            valueRange = range,
            modifier = Modifier.weight(1f)
        )
        Text(
            text = if (decimals > 0) String.format("%." + decimals + "f", value) else value.roundToInt().toString(),
            modifier = Modifier.width(46.dp),
            textAlign = TextAlign.End,
            fontSize = 12.sp
        )
    }
}

@Composable
private fun PilotToggleRow(label: String, checked: Boolean, onCheckedChange: (Boolean) -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 1.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = label,
            modifier = Modifier.width(170.dp),
            fontSize = 12.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(Modifier.weight(1f))
        Switch(checked = checked, onCheckedChange = onCheckedChange)
    }
}

/** 背景彩色卡片：高饱和渐变，让模糊/折射肉眼可辨。 */
@Composable
private fun PilotColorfulCard(index: Int) {
    val palette = listOf(
        Color(0xFFFF6B6B), Color(0xFF4ECDC4), Color(0xFFA78BFA),
        Color(0xFFFFA94D), Color(0xFF51CF66), Color(0xFF3B82F6),
        Color(0xFFF472B6), Color(0xFFFACC15)
    )
    val base = palette[index % palette.size]
    Box(
        Modifier
            .fillMaxWidth()
            .height(64.dp + (26 * (index % 3)).dp)
            .clip(RoundedCornerShape(16.dp))
            .background(
                Brush.linearGradient(
                    colors = listOf(base, base.copy(alpha = 0.55f))
                )
            )
            .padding(14.dp),
        contentAlignment = Alignment.BottomEnd
    ) {
        Text(
            text = "No." + (index + 1),
            color = Color.White.copy(alpha = 0.9f),
            fontWeight = FontWeight.Bold
        )
    }
}

/** 玻璃卡片 demo。 */
@Composable
private fun PilotGlassCard(
    engine: PilotEngine,
    mode: VisualEffectMode,
    hazeState: HazeState,
    backdrop: Backdrop,
    backdropParams: BackdropGlassParams,
    hazeParams: HazeBlurParams,
    modifier: Modifier = Modifier
) {
    Box(modifier) {
        when (engine) {
            PilotEngine.HAZE -> {
                if (mode == VisualEffectMode.BLUR) {
                    // 官方直连方式：clip + hazeBlur，tint 走 style，不再叠加 background 覆盖模糊
                    Box(
                        Modifier
                            .size(width = 230.dp, height = 140.dp)
                            .clip(RoundedCornerShape(24.dp))
                            .hazeBlur(
                                input = HazeInput.Sources(hazeState, selection = HazeSourceSelection.Behind),
                                style = rememberHazeBlurStyle(hazeParams),
                                performanceMode = HazePerformanceMode.Adaptive
                            )
                    ) {
                        PilotSurfaceLabel("Haze·BLUR")
                    }
                } else {
                    CompositionLocalProvider(LocalVisualEffectMode provides mode) {
                        NeumorphicFrostedSurface(
                            modifier = Modifier.size(width = 230.dp, height = 140.dp),
                            isDark = isAppDarkTheme(),
                            shape = RoundedCornerShape(24.dp),
                            hazeState = hazeState,
                            hazeStyle = rememberHazeBlurStyle(hazeParams),
                            glassRole = GlassSurfaceRole.SearchField,
                            scene = GlassScene(contentLoad = 0.2f, readabilityDemand = 0.4f)
                        ) {
                            PilotSurfaceLabel("Haze·GLASS")
                        }
                    }
                }
            }

            PilotEngine.BACKDROP -> Box(
                Modifier
                    .size(width = 230.dp, height = 140.dp)
                    .pilotBackdropGlass(backdrop, mode, backdropParams)
            ) {
                PilotSurfaceLabel("Backdrop·" + mode.name)
            }
        }
    }
}

/** 圆形玻璃按钮 demo。 */
@Composable
private fun PilotGlassButton(
    engine: PilotEngine,
    mode: VisualEffectMode,
    hazeState: HazeState,
    backdrop: Backdrop,
    backdropParams: BackdropGlassParams,
    hazeParams: HazeBlurParams,
    onClick: () -> Unit
) {
    val interactionSource = remember { MutableInteractionSource() }
    val pressed by interactionSource.collectIsPressedAsState()
    val pressScale by animateFloatAsState(
        targetValue = if (pressed) 0.985f else 1f,
        animationSpec = spring(
            dampingRatio = Spring.DampingRatioMediumBouncy,
            stiffness = Spring.StiffnessMedium
        ),
        label = "pilot_button_press_scale"
    )
    when (engine) {
        PilotEngine.HAZE -> {
            if (mode == VisualEffectMode.BLUR) {
                // 官方直连方式：clip + hazeBlur
                Box(
                    Modifier
                        .size(52.dp)
                        .clip(RoundedCornerShape(50))
                        .hazeBlur(
                            input = HazeInput.Sources(hazeState, selection = HazeSourceSelection.Behind),
                            style = rememberHazeBlurStyle(hazeParams),
                            performanceMode = HazePerformanceMode.Adaptive
                        )
                        .clickable(onClick = onClick),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = Icons.Rounded.Favorite,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurface
                    )
                }
            } else {
                CompositionLocalProvider(LocalVisualEffectMode provides mode) {
                    NeumorphicIconButton(
                        onClick = onClick,
                        isDark = isAppDarkTheme(),
                        hazeState = hazeState,
                        hazeStyle = rememberHazeBlurStyle(hazeParams),
                        size = 52.dp
                    ) {
                        Icon(
                            imageVector = Icons.Rounded.Favorite,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onSurface
                        )
                    }
                }
            }
        }

        PilotEngine.BACKDROP -> Box(
            Modifier
                .size(52.dp)
                .scale(pressScale)
                .pilotBackdropGlass(backdrop, mode, backdropParams, pressed = pressed)
                .clickable(
                    interactionSource = interactionSource,
                    indication = null,
                    onClick = onClick
                ),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                imageVector = Icons.Rounded.Favorite,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurface
            )
        }
    }
}

/** 底部玻璃栏 demo。 */
@Composable
private fun PilotGlassBottomBar(
    engine: PilotEngine,
    mode: VisualEffectMode,
    hazeState: HazeState,
    backdrop: Backdrop,
    backdropParams: BackdropGlassParams,
    hazeParams: HazeBlurParams,
    modifier: Modifier = Modifier
) {
    val icons = listOf(Icons.Rounded.Home, Icons.Rounded.Search, Icons.Rounded.Person)
    var selectedIndex by rememberSaveable { mutableStateOf(0) }
    Box(
        modifier
            .fillMaxWidth()
            .navigationBarsPadding()
            .padding(horizontal = 12.dp)
            .height(76.dp)
    ) {
        when (engine) {
            PilotEngine.HAZE -> {
                if (mode == VisualEffectMode.BLUR) {
                    // 官方直连方式：clip + hazeBlur
                    Box(
                        Modifier
                            .fillMaxSize()
                            .clip(RoundedCornerShape(topStart = 26.dp, topEnd = 26.dp))
                            .hazeBlur(
                                input = HazeInput.Sources(hazeState, selection = HazeSourceSelection.Behind),
                                style = rememberHazeBlurStyle(hazeParams),
                                performanceMode = HazePerformanceMode.Adaptive
                            )
                    ) {
                        PilotBottomBarContent(icons, selectedIndex, { selectedIndex = it })
                    }
                } else {
                    CompositionLocalProvider(LocalVisualEffectMode provides mode) {
                        NeumorphicFrostedSurface(
                            modifier = Modifier.fillMaxSize(),
                            isDark = isAppDarkTheme(),
                            shape = RoundedCornerShape(topStart = 26.dp, topEnd = 26.dp),
                            hazeState = hazeState,
                            hazeStyle = rememberHazeBlurStyle(hazeParams),
                            glassRole = GlassSurfaceRole.BottomNavigation,
                            scene = GlassScene(contentLoad = 0.1f, readabilityDemand = 0.3f)
                        ) {
                            PilotBottomBarContent(icons, selectedIndex, { selectedIndex = it })
                        }
                    }
                }
            }

            PilotEngine.BACKDROP -> Box(
                Modifier
                    .fillMaxSize()
                    .pilotBackdropGlass(backdrop, mode, backdropParams)
            ) {
                PilotBottomBarContent(
                    icons = icons,
                    selectedIndex = selectedIndex,
                    onSelected = { selectedIndex = it },
                    backdrop = backdrop,
                    mode = mode,
                    params = backdropParams
                )
            }
        }
    }
}

@Composable
private fun PilotBottomBarContent(
    icons: List<androidx.compose.ui.graphics.vector.ImageVector>,
    selectedIndex: Int,
    onSelected: (Int) -> Unit,
    backdrop: Backdrop? = null,
    mode: VisualEffectMode = VisualEffectMode.GLASS,
    params: BackdropGlassParams? = null
) {
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val rowPadding = 8.dp
        val itemWidth = (maxWidth - rowPadding * 2) / icons.size
        val indicatorOffset by animateDpAsState(
            targetValue = rowPadding + itemWidth * selectedIndex,
            animationSpec = spring(
                dampingRatio = Spring.DampingRatioMediumBouncy,
                stiffness = Spring.StiffnessMediumLow
            ),
            label = "pilot_bottom_indicator_offset"
        )
        Box(
            modifier = Modifier
                .offset(x = indicatorOffset)
                .align(Alignment.CenterStart)
                .width(itemWidth)
                .height(46.dp)
                .padding(horizontal = 4.dp)
                .clip(RoundedCornerShape(24.dp))
                .background(
                    MaterialTheme.colorScheme.primary.copy(alpha = 0.12f),
                    RoundedCornerShape(24.dp)
                )
        )
        Row(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = rowPadding),
            verticalAlignment = Alignment.CenterVertically
        ) {
            icons.forEachIndexed { index, icon ->
                val interactionSource = remember { MutableInteractionSource() }
                val pressed by interactionSource.collectIsPressedAsState()
                val itemScale by animateFloatAsState(
                    targetValue = when {
                        pressed -> 0.985f
                        selectedIndex == index -> 1.04f
                        else -> 1f
                    },
                    animationSpec = spring(
                        dampingRatio = Spring.DampingRatioMediumBouncy,
                        stiffness = Spring.StiffnessMedium
                    ),
                    label = "pilot_bottom_item_scale_$index"
                )
                val itemParams = params?.copy(
                    shape = RoundedCornerShape(22.dp),
                    refractionHeight = params.refractionHeight * 0.7f,
                    refractionAmount = params.refractionAmount * 0.68f,
                    highlightWidth = 0.6.dp,
                    highlightAlpha = (params.highlightAlpha * 0.82f).coerceAtMost(1f),
                    shadowRadius = 0.dp,
                    shadowAlpha = 0f,
                    innerShadowRadius = 0.dp
                )
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxSize()
                        .scale(itemScale)
                        .then(
                            if (backdrop != null && itemParams != null) {
                                Modifier.pilotBackdropGlass(
                                    backdrop = backdrop,
                                    mode = mode,
                                    params = itemParams,
                                    pressed = pressed
                                )
                            } else {
                                Modifier
                            }
                        )
                        .clickable(
                            interactionSource = interactionSource,
                            indication = null,
                            onClick = { onSelected(index) }
                        ),
                    contentAlignment = Alignment.Center
                ) {
                    val tint = if (selectedIndex == index) {
                        MaterialTheme.colorScheme.primary
                    } else {
                        MaterialTheme.colorScheme.onSurface
                    }
                    Icon(
                        imageVector = icon,
                        contentDescription = null,
                        tint = tint,
                        modifier = Modifier.size(24.dp)
                    )
                }
            }
        }
    }
}

@Composable
private fun PilotSurfaceLabel(text: String) {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Text(
            text = text,
            style = MaterialTheme.typography.labelLarge,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.onSurface
        )
    }
}
