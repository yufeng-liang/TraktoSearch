@file:OptIn(dev.chrisbanes.haze.ExperimentalHazeApi::class)

package com.tracktosearch.ui.component

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.unit.dp
import com.tracktosearch.R
import com.tracktosearch.ui.haptic.rememberAppHaptics
import dev.chrisbanes.haze.HazeInput
import dev.chrisbanes.haze.HazePerformanceMode
import dev.chrisbanes.haze.HazeSourceSelection
import dev.chrisbanes.haze.HazeState
import dev.chrisbanes.haze.blur.HazeBlurStyle
import dev.chrisbanes.haze.blur.materials.HazeMaterials
import kotlinx.coroutines.launch

private const val DEEP_SCROLL_THRESHOLD = 5

// 默认尺寸 48.dp，增大 20% 后为 57.6.dp，取 58.dp
private val HAZE_BUTTON_SIZE = 58.dp

@Composable
internal fun resolveScrollToTopHazeStyle(
    hazeStyle: HazeBlurStyle?,
    surface: Color
): HazeBlurStyle = hazeStyle ?: HazeMaterials.thin(surface)

@Composable
fun ScrollToTopButton(
    listState: LazyListState,
    modifier: Modifier = Modifier,
    hazeState: HazeState? = null,
    hazeStyle: HazeBlurStyle? = null,
    sourceSelection: HazeSourceSelection = HazeSourceSelection.Behind,
    scene: GlassScene = GlassScene()
) {
    val scope = rememberCoroutineScope()
    var showButton by remember { mutableStateOf(false) }
    var previousIndex by remember { mutableIntStateOf(0) }
    var previousOffset by remember { mutableIntStateOf(0) }

    LaunchedEffect(listState) {
        snapshotFlow {
            Pair(listState.firstVisibleItemIndex, listState.firstVisibleItemScrollOffset)
        }.collect { pair ->
            val index = pair.first
            val offset = pair.second
            val scrollingUp = index < previousIndex ||
                (index == previousIndex && offset < previousOffset)

            if (scrollingUp && index > DEEP_SCROLL_THRESHOLD) {
                showButton = true
            } else if (index <= DEEP_SCROLL_THRESHOLD) {
                showButton = false
            }

            previousIndex = index
            previousOffset = offset
        }
    }

    AnimatedVisibility(
        visible = showButton,
        enter = fadeIn(),
        exit = fadeOut(),
        modifier = modifier
    ) {
        ScrollToTopButtonContent(
            onClick = { scope.launch { listState.animateScrollToItem(0) } },
            hazeState = hazeState,
            hazeStyle = hazeStyle,
            sourceSelection = sourceSelection,
            scene = scene
        )
    }
}

@Composable
fun ScrollToTopButton(
    gridState: LazyGridState,
    modifier: Modifier = Modifier,
    hazeState: HazeState? = null,
    hazeStyle: HazeBlurStyle? = null,
    sourceSelection: HazeSourceSelection = HazeSourceSelection.Behind,
    scene: GlassScene = GlassScene()
) {
    val scope = rememberCoroutineScope()
    var showButton by remember { mutableStateOf(false) }
    var previousIndex by remember { mutableIntStateOf(0) }
    var previousOffset by remember { mutableIntStateOf(0) }

    LaunchedEffect(gridState) {
        snapshotFlow {
            Pair(gridState.firstVisibleItemIndex, gridState.firstVisibleItemScrollOffset)
        }.collect { pair ->
            val index = pair.first
            val offset = pair.second
            val scrollingUp = index < previousIndex ||
                (index == previousIndex && offset < previousOffset)

            if (scrollingUp && index > DEEP_SCROLL_THRESHOLD) {
                showButton = true
            } else if (index <= DEEP_SCROLL_THRESHOLD) {
                showButton = false
            }

            previousIndex = index
            previousOffset = offset
        }
    }

    AnimatedVisibility(
        visible = showButton,
        enter = fadeIn(),
        exit = fadeOut(),
        modifier = modifier
    ) {
        ScrollToTopButtonContent(
            onClick = { scope.launch { gridState.animateScrollToItem(0) } },
            hazeState = hazeState,
            hazeStyle = hazeStyle,
            sourceSelection = sourceSelection,
            scene = scene
        )
    }
}

@Composable
private fun ScrollToTopButtonContent(
    onClick: () -> Unit,
    hazeState: HazeState?,
    hazeStyle: HazeBlurStyle?,
    sourceSelection: HazeSourceSelection,
    scene: GlassScene
) {
    val arrowTint = MaterialTheme.colorScheme.primary
    // 整颗回顶按钮此前没有无障碍标签：图标是 Canvas 画的箭头，读屏只能读出「按钮」
    val backToTopDescription = stringResource(R.string.cd_back_to_top)
    val haptics = rememberAppHaptics()
    // 下面两条渲染分支互斥，共用这一份包装，一次点击只发一记
    val onClickWithHaptic = { haptics.lightTap(); onClick() }
    val interactionSource = remember { MutableInteractionSource() }
    if (hazeState != null) {
        val resolvedHazeStyle = resolveScrollToTopHazeStyle(
            hazeStyle = hazeStyle,
            surface = MaterialTheme.colorScheme.surface
        )
        // 毛玻璃样式（与首页悬浮导航一致），尺寸增大 20%
        Box(
            modifier = Modifier
                .size(HAZE_BUTTON_SIZE)
                .clip(CircleShape)
                .appVisualEffect(
                    input = HazeInput.Sources(hazeState, selection = sourceSelection),
                    hazeStyle = resolvedHazeStyle,
                    glassRole = GlassSurfaceRole.CircularControl,
                    glassShape = RoundedCornerShape(50),
                    glassTint = MaterialTheme.colorScheme.surface.copy(
                        alpha = if (isAppDarkTheme()) 0.18f else 0.72f
                    ),
                    scene = scene,
                    blurPerformanceMode = HazePerformanceMode.Default,
                    interactionSource = interactionSource
                )
                .border(
                    width = 1.dp,
                    color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.6f),
                    shape = CircleShape
                )
                .clickable(
                    interactionSource = interactionSource,
                    indication = null,
                    onClick = onClickWithHaptic
                )
                .semantics { contentDescription = backToTopDescription },
            contentAlignment = Alignment.Center
        ) {
            ScrollToTopArrow(tint = arrowTint)
        }
    } else {
        FilledTonalIconButton(
            onClick = onClickWithHaptic,
            shape = CircleShape,
            modifier = Modifier.semantics { contentDescription = backToTopDescription }
        ) {
            ScrollToTopArrow(tint = arrowTint)
        }
    }
}

@Composable
private fun ScrollToTopArrow(tint: Color) {
    val contrastHalo = if (tint.luminance() < 0.5f) {
        Color.White.copy(alpha = 0.68f)
    } else {
        Color.Black.copy(alpha = 0.62f)
    }
    Box(
        modifier = Modifier.size(95.dp),
        contentAlignment = Alignment.Center
    ) {
        Icon(
            imageVector = Icons.Filled.KeyboardArrowUp,
            contentDescription = null,
            tint = contrastHalo,
            modifier = Modifier
                .size(90.dp)
                .offset(y = 1.dp)
        )
        Icon(
            imageVector = Icons.Filled.KeyboardArrowUp,
            contentDescription = stringResource(R.string.scroll_to_top),
            tint = tint,
            modifier = Modifier.size(80.dp)
        )
    }
}
