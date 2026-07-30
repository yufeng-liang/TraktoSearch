package com.tracktosearch.ui.component

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.KeyboardArrowUp
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
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.tracktosearch.R
import com.tracktosearch.ui.util.HapticType
import com.tracktosearch.ui.util.performHaptic
import dev.chrisbanes.haze.HazeInputScale
import dev.chrisbanes.haze.HazeState
import dev.chrisbanes.haze.blur.HazeBlurStyle
import dev.chrisbanes.haze.blur.blurEffect
import dev.chrisbanes.haze.hazeEffect
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
    hazeStyle: HazeBlurStyle? = null
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
            hazeStyle = hazeStyle
        )
    }
}

@Composable
fun ScrollToTopButton(
    gridState: LazyGridState,
    modifier: Modifier = Modifier,
    hazeState: HazeState? = null,
    hazeStyle: HazeBlurStyle? = null
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
            hazeStyle = hazeStyle
        )
    }
}

@Composable
private fun ScrollToTopButtonContent(
    onClick: () -> Unit,
    hazeState: HazeState?,
    hazeStyle: HazeBlurStyle?
) {
    val view = LocalView.current
    val arrowTint = MaterialTheme.colorScheme.primary
    val onClickWithHaptic = { view.performHaptic(HapticType.TICK); onClick() }
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
                .hazeEffect(state = hazeState) {
                    inputScale = HazeInputScale.Auto
                    blurEffect { style = resolvedHazeStyle }
                }
                .border(
                    width = 1.dp,
                    color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.6f),
                    shape = CircleShape
                )
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                    onClick = onClickWithHaptic
                ),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                imageVector = Icons.Rounded.KeyboardArrowUp,
                contentDescription = stringResource(R.string.scroll_to_top),
                tint = arrowTint,
                modifier = Modifier.size(32.dp)
            )
        }
    } else {
        FilledTonalIconButton(
            onClick = onClickWithHaptic,
            shape = CircleShape
        ) {
            Icon(
                imageVector = Icons.Rounded.KeyboardArrowUp,
                contentDescription = stringResource(R.string.scroll_to_top),
                tint = arrowTint,
                modifier = Modifier.size(32.dp)
            )
        }
    }
}
