package com.tracktosearch.ui.animation

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.graphics.graphicsLayer
import kotlinx.coroutines.launch

fun Modifier.fadeSlideIn(index: Int = 0): Modifier = composed {
    val alpha = remember { Animatable(0f) }
    val offsetY = remember { Animatable(20f) }
    LaunchedEffect(Unit) {
        val delayMillis = index * 30
        launch { alpha.animateTo(1f, animationSpec = tween(300, delayMillis = delayMillis)) }
        launch { offsetY.animateTo(0f, animationSpec = tween(300, delayMillis = delayMillis)) }
    }
    graphicsLayer(
        alpha = alpha.value,
        translationY = offsetY.value
    )
}
