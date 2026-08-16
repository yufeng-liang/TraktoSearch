@file:OptIn(dev.chrisbanes.haze.ExperimentalHazeApi::class)

package com.tracktosearch.ui.screen.pilot

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Favorite
import androidx.compose.material.icons.rounded.Home
import androidx.compose.material.icons.rounded.Person
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.unit.dp
import com.kyant.backdrop.backdrops.LayerBackdrop
import com.kyant.backdrop.drawBackdrop
import com.kyant.backdrop.effects.blur
import com.kyant.backdrop.effects.colorControls
import com.kyant.backdrop.effects.lens
import com.kyant.backdrop.effects.vibrancy
import com.kyant.backdrop.highlight.Highlight
import com.kyant.backdrop.highlight.HighlightStyle
import com.kyant.backdrop.backdrops.layerBackdrop
import com.kyant.backdrop.backdrops.rememberLayerBackdrop
import com.kyant.backdrop.shadow.InnerShadow
import com.kyant.backdrop.shadow.Shadow
import com.tracktosearch.ui.component.GlassScene
import com.tracktosearch.ui.component.GlassSurfaceRole
import com.tracktosearch.ui.component.NeumorphicFrostedSurface
import com.tracktosearch.ui.component.NeumorphicIconButton
import com.tracktosearch.ui.component.isAppDarkTheme
import com.tracktosearch.ui.theme.LocalVisualEffectMode
import com.tracktosearch.ui.theme.VisualEffectMode
import dev.chrisbanes.haze.HazeState
import dev.chrisbanes.haze.hazeSource

/**
 * 玻璃引擎试点页(仅 DEBUG 注册)。
 *
 * 目的：在同一屏上对比两套玻璃引擎的「模糊 / 玻璃」两种模式：
 * - Haze（现引擎）：复用 NeumorphicFrostedSurface / NeumorphicIconButton，
 *   由 LocalVisualEffectMode 分发给 hazeBlur（模糊）或 hazeGlass（玻璃）。
 * - Backdrop（新引擎，com.kyant.backdrop）：drawBackdrop + blur / lens 效果，
 *   自带镜面高光(Highlight)与投影(Shadow)，按场景深度调参。
 *
 * 结论口径：玻璃模式目标是彻底脱离 haze-glass，以 backdrop 的液态玻璃为主；
 * 模糊模式保留成熟 haze 作为参照，方便 A/B 对比。
 */
@Composable
fun GlassEnginePilotScreen(onBack: () -> Unit) {
    var engine by rememberSaveable { mutableStateOf(PilotEngine.BACKDROP) }
    var mode by rememberSaveable { mutableStateOf(VisualEffectMode.GLASS) }
    val hazeState = remember { HazeState() }
    val backgroundColor = MaterialTheme.colorScheme.background
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

    Box(
        Modifier
            .fillMaxSize()
            .background(backgroundColor)
    ) {
        // 背景内容：同时挂 hazeSource（供 haze 采样）与 layerBackdrop（供 backdrop 采样）
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
            onBack = onBack
        )

        // 玻璃卡片 demo
        PilotGlassCard(
            engine = engine,
            mode = mode,
            hazeState = hazeState,
            backdrop = backdrop,
            modifier = Modifier
                .align(Alignment.TopCenter)
                .padding(top = 176.dp)
        )

        // 圆形玻璃按钮行
        Row(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(bottom = 92.dp),
            horizontalArrangement = Arrangement.spacedBy(22.dp)
        ) {
            PilotGlassButton(engine, mode, hazeState, backdrop, onClick = {})
            PilotGlassButton(engine, mode, hazeState, backdrop, onClick = {})
            PilotGlassButton(engine, mode, hazeState, backdrop, onClick = {})
        }

        // 底部玻璃栏 demo
        PilotGlassBottomBar(
            engine = engine,
            mode = mode,
            hazeState = hazeState,
            backdrop = backdrop,
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

/**
 * 场景参数：
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
    backdrop: LayerBackdrop,
    mode: VisualEffectMode,
    params: BackdropGlassParams
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
                    refractionAmount = params.refractionAmount.toPx(),
                    depthEffect = params.depthEffect,
                    chromaticAberration = params.chromaticAberration
                )
            }
        },
        highlight = {
            Highlight(
                width = params.highlightWidth,
                alpha = params.highlightAlpha,
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

/** 顶部控制条：返回 + 引擎切换 + 模式切换。 */
@Composable
private fun PilotControlBar(
    engine: PilotEngine,
    onEngineChange: (PilotEngine) -> Unit,
    mode: VisualEffectMode,
    onModeChange: (VisualEffectMode) -> Unit,
    onBack: () -> Unit
) {
    Column(
        Modifier
            .fillMaxWidth()
            .statusBarsPadding()
            .background(MaterialTheme.colorScheme.surface)
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
    }
}

@Composable
private fun PilotChip(label: String, selected: Boolean, onClick: () -> Unit) {
    FilterChip(
        selected = selected,
        onClick = onClick,
        label = { Text(label, fontSize = MaterialTheme.typography.bodySmall.fontSize) }
    )
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
    backdrop: LayerBackdrop,
    modifier: Modifier = Modifier
) {
    Box(modifier) {
        when (engine) {
            PilotEngine.HAZE -> CompositionLocalProvider(LocalVisualEffectMode provides mode) {
                NeumorphicFrostedSurface(
                    modifier = Modifier.size(width = 230.dp, height = 140.dp),
                    isDark = isAppDarkTheme(),
                    shape = RoundedCornerShape(24.dp),
                    hazeState = hazeState,
                    glassRole = GlassSurfaceRole.SearchField,
                    scene = GlassScene(contentLoad = 0.2f, readabilityDemand = 0.4f)
                ) {
                    PilotSurfaceLabel("Haze·" + mode.name)
                }
            }

            PilotEngine.BACKDROP -> Box(
                Modifier
                    .size(width = 230.dp, height = 140.dp)
                    .pilotBackdropGlass(backdrop, mode, pilotParams("card", isAppDarkTheme()))
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
    backdrop: LayerBackdrop,
    onClick: () -> Unit
) {
    when (engine) {
        PilotEngine.HAZE -> CompositionLocalProvider(LocalVisualEffectMode provides mode) {
            NeumorphicIconButton(
                onClick = onClick,
                isDark = isAppDarkTheme(),
                hazeState = hazeState,
                size = 52.dp
            ) {
                Icon(
                    imageVector = Icons.Rounded.Favorite,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurface
                )
            }
        }

        PilotEngine.BACKDROP -> Box(
            Modifier
                .size(52.dp)
                .pilotBackdropGlass(backdrop, mode, pilotParams("button", isAppDarkTheme()))
                .clickable(onClick = onClick),
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
    backdrop: LayerBackdrop,
    modifier: Modifier = Modifier
) {
    val icons = listOf(Icons.Rounded.Home, Icons.Rounded.Search, Icons.Rounded.Person)
    Box(
        modifier
            .fillMaxWidth()
            .navigationBarsPadding()
            .padding(horizontal = 12.dp)
            .height(76.dp)
    ) {
        when (engine) {
            PilotEngine.HAZE -> CompositionLocalProvider(LocalVisualEffectMode provides mode) {
                NeumorphicFrostedSurface(
                    modifier = Modifier.fillMaxSize(),
                    isDark = isAppDarkTheme(),
                    shape = RoundedCornerShape(topStart = 26.dp, topEnd = 26.dp),
                    hazeState = hazeState,
                    glassRole = GlassSurfaceRole.BottomNavigation,
                    scene = GlassScene(contentLoad = 0.1f, readabilityDemand = 0.3f)
                ) {
                    PilotBottomBarContent(icons)
                }
            }

            PilotEngine.BACKDROP -> Box(
                Modifier
                    .fillMaxSize()
                    .pilotBackdropGlass(backdrop, mode, pilotParams("bottomBar", isAppDarkTheme()))
            ) {
                PilotBottomBarContent(icons)
            }
        }
    }
}

@Composable
private fun PilotBottomBarContent(icons: List<androidx.compose.ui.graphics.vector.ImageVector>) {
    Row(
        modifier = Modifier.fillMaxSize(),
        horizontalArrangement = Arrangement.SpaceEvenly,
        verticalAlignment = Alignment.CenterVertically
    ) {
        icons.forEachIndexed { index, icon ->
            val tint = if (index == 0) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = tint,
                modifier = Modifier.size(24.dp)
            )
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
