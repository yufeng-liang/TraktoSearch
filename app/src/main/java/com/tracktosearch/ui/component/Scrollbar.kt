package com.tracktosearch.ui.component

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.unit.dp

/**
 * 在可滚动列表右侧显示一个极简的滚动条指示器
 */
@Composable
fun LazyColumnScrollbar(
    state: LazyListState,
    modifier: Modifier = Modifier
) {
    val scrollbarAlpha by animateFloatAsState(
        targetValue = if (state.isScrollInProgress) 0.8f else 0.3f,
        animationSpec = tween(durationMillis = 300),
        label = "scrollbarAlpha"
    )

    val layoutInfo by remember(state) {
        derivedStateOf {
            val visibleItems = state.layoutInfo.visibleItemsInfo
            val totalItems = state.layoutInfo.totalItemsCount
            if (visibleItems.isEmpty() || totalItems == 0) {
                null
            } else {
                val firstVisible = visibleItems.first().index
                val lastVisible = visibleItems.last().index
                val visibleCount = lastVisible - firstVisible + 1
                val progress = firstVisible.toFloat() / (totalItems - visibleCount).coerceAtLeast(1)
                val thumbHeight = (visibleCount.toFloat() / totalItems).coerceIn(0.1f, 0.5f)
                progress to thumbHeight
            }
        }
    }

    Box(
        modifier = modifier
            .fillMaxHeight()
            .width(4.dp)
            .padding(vertical = 4.dp),
        contentAlignment = Alignment.TopCenter
    ) {
        // Track
        Box(
            modifier = Modifier
                .fillMaxHeight()
                .width(2.dp)
                .background(
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.15f),
                    shape = RoundedCornerShape(1.dp)
                )
        )

        // Thumb
        layoutInfo?.let { (progress, thumbHeight) ->
            Box(
                modifier = Modifier
                    .fillMaxHeight(thumbHeight)
                    .width(3.dp)
                    .alpha(scrollbarAlpha)
                    .background(
                        color = MaterialTheme.colorScheme.primary.copy(alpha = 0.6f),
                        shape = RoundedCornerShape(1.5.dp)
                    )
                    .align(Alignment.TopCenter)
                    .padding(top = androidx.compose.ui.unit.max(
                        0.dp,
                        (progress * 100).dp
                    ))
            )
        }
    }
}

/**
 * 在 LazyVerticalGrid 右侧显示一个极简的滚动条指示器
 */
@Composable
fun LazyGridScrollbar(
    state: LazyGridState,
    modifier: Modifier = Modifier
) {
    val scrollbarAlpha by animateFloatAsState(
        targetValue = if (state.isScrollInProgress) 0.8f else 0.3f,
        animationSpec = tween(durationMillis = 300),
        label = "gridScrollbarAlpha"
    )

    val layoutInfo by remember(state) {
        derivedStateOf {
            val visibleItems = state.layoutInfo.visibleItemsInfo
            val totalItems = state.layoutInfo.totalItemsCount
            if (visibleItems.isEmpty() || totalItems == 0) {
                null
            } else {
                val firstVisible = visibleItems.first().index
                val lastVisible = visibleItems.last().index
                val visibleCount = lastVisible - firstVisible + 1
                val progress = firstVisible.toFloat() / (totalItems - visibleCount).coerceAtLeast(1)
                val thumbHeight = (visibleCount.toFloat() / totalItems).coerceIn(0.1f, 0.5f)
                progress to thumbHeight
            }
        }
    }

    Box(
        modifier = modifier
            .fillMaxHeight()
            .width(4.dp)
            .padding(vertical = 4.dp),
        contentAlignment = Alignment.TopCenter
    ) {
        Box(
            modifier = Modifier
                .fillMaxHeight()
                .width(2.dp)
                .background(
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.15f),
                    shape = RoundedCornerShape(1.dp)
                )
        )

        layoutInfo?.let { (progress, thumbHeight) ->
            Box(
                modifier = Modifier
                    .fillMaxHeight(thumbHeight)
                    .width(3.dp)
                    .alpha(scrollbarAlpha)
                    .background(
                        color = MaterialTheme.colorScheme.primary.copy(alpha = 0.6f),
                        shape = RoundedCornerShape(1.5.dp)
                    )
                    .align(Alignment.TopCenter)
            )
        }
    }
}
