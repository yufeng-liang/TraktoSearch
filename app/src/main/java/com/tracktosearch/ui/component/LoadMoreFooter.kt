package com.tracktosearch.ui.component

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalInspectionMode
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.tracktosearch.R
import com.tracktosearch.ui.haptic.rememberAppHaptics
import kotlinx.coroutines.delay

enum class LoadMoreFooterState { Loading, Error, Complete, Hidden }

/** 触底分页统一反馈区，固定高度避免列表加载时跳动。 */
@Composable
fun LoadMoreFooter(
    state: LoadMoreFooterState,
    onRetry: () -> Unit,
    modifier: Modifier = Modifier
) {
    val isInspectionMode = LocalInspectionMode.current
    val haptics = rememberAppHaptics()
    var visible by remember { mutableStateOf(true) }
    LaunchedEffect(state) {
        visible = state != LoadMoreFooterState.Hidden
        if (state == LoadMoreFooterState.Complete && !isInspectionMode) {
            delay(1200)
            visible = false
        }
    }
    Box(
        modifier = modifier
            .height(56.dp)
            .padding(horizontal = 16.dp),
        contentAlignment = Alignment.Center
    ) {
        AnimatedVisibility(
            visible = visible,
            enter = fadeIn(),
            exit = fadeOut()
        ) {
            when (state) {
                LoadMoreFooterState.Loading -> Row(
                    horizontalArrangement = Arrangement.Center,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
                    Spacer(modifier = Modifier.width(10.dp))
                    Text(stringResource(R.string.common_load_more_loading), color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                LoadMoreFooterState.Error -> TextButton(
                    onClick = {
                        haptics.tap()
                        onRetry()
                    }
                ) {
                    Text(
                        text = stringResource(R.string.common_load_more_failed),
                        color = MaterialTheme.colorScheme.error
                    )
                }
                LoadMoreFooterState.Complete -> Text(
                    text = stringResource(R.string.common_load_more_complete),
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                LoadMoreFooterState.Hidden -> Unit
            }
        }
    }
}
