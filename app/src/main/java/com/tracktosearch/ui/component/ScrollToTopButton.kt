package com.tracktosearch.ui.component

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
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
import androidx.compose.ui.Modifier
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch

private const val DEEP_SCROLL_THRESHOLD = 5

@Composable
fun ScrollToTopButton(
    listState: LazyListState,
    modifier: Modifier = Modifier
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
        FilledTonalIconButton(
            onClick = { scope.launch { listState.animateScrollToItem(0) } },
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

@Composable
fun ScrollToTopButton(
    gridState: LazyGridState,
    modifier: Modifier = Modifier
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
        FilledTonalIconButton(
            onClick = { scope.launch { gridState.animateScrollToItem(0) } },
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
