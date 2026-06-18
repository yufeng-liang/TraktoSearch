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
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import dev.chrisbanes.haze.HazeState
import dev.chrisbanes.haze.hazeEffect
import dev.chrisbanes.haze.materials.ExperimentalHazeMaterialsApi
import dev.chrisbanes.haze.materials.HazeMaterials
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch

private const val DEEP_SCROLL_THRESHOLD = 5

// 默认尺寸 48.dp，增大 20% 后为 57.6.dp，取 58.dp
private val HAZE_BUTTON_SIZE = 58.dp

@Composable
fun ScrollToTopButton(
    listState: LazyListState,
    modifier: Modifier = Modifier,
    hazeState: HazeState? = null
) {
    val scope = rememberCoroutineScope()
    var showButton by remember { mutableStateOf(false) }
    var previousIndex by remember { mutableStateOf(0) }
    var previousOffset by remember { mutableStateOf(0) }

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
            hazeState = hazeState
        )
    }
}

@Composable
fun ScrollToTopButton(
    gridState: LazyGridState,
    modifier: Modifier = Modifier,
    hazeState: HazeState? = null
) {
    val scope = rememberCoroutineScope()
    var showButton by remember { mutableStateOf(false) }
    var previousIndex by remember { mutableStateOf(0) }
    var previousOffset by remember { mutableStateOf(0) }

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
            hazeState = hazeState
        )
    }
}

@OptIn(ExperimentalHazeMaterialsApi::class)
@Composable
private fun ScrollToTopButtonContent(
    onClick: () -> Unit,
    hazeState: HazeState?
) {
    if (hazeState != null) {
        // 毛玻璃样式（与首页悬浮导航一致），尺寸增大 20%
        Box(
            modifier = Modifier
                .size(HAZE_BUTTON_SIZE)
                .clip(CircleShape)
                .hazeEffect(
                    state = hazeState,
                    style = HazeMaterials.thin()
                )
                .border(
                    width = 1.dp,
                    color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.6f),
                    shape = CircleShape
                )
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                    onClick = onClick
                ),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                imageVector = Icons.Filled.KeyboardArrowUp,
                contentDescription = "返回顶部",
                tint = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    } else {
        FilledTonalIconButton(
            onClick = onClick,
            shape = CircleShape
        ) {
            Icon(
                imageVector = Icons.Filled.KeyboardArrowUp,
                contentDescription = "返回顶部",
                tint = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}
