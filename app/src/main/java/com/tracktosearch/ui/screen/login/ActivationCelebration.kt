package com.tracktosearch.ui.screen.login

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.dp
import com.airbnb.lottie.compose.LottieAnimation
import com.airbnb.lottie.compose.LottieCompositionSpec
import com.airbnb.lottie.compose.animateLottieCompositionAsState
import com.airbnb.lottie.compose.rememberLottieComposition
import com.tracktosearch.R
import kotlinx.coroutines.delay

internal fun shouldShowActivationCelebration(
    previousActivated: Boolean,
    currentActivated: Boolean
): Boolean = !previousActivated && currentActivated

internal fun shouldFinishActivationCelebration(
    visible: Boolean,
    hasFinished: Boolean
): Boolean = visible && !hasFinished

@Composable
internal fun ActivationCelebration(
    visible: Boolean,
    onFinished: () -> Unit,
    modifier: Modifier = Modifier
) {
    AnimatedVisibility(
        visible = visible,
        enter = fadeIn(animationSpec = tween(180)),
        exit = fadeOut(animationSpec = tween(240)),
        modifier = modifier
    ) {
        val composition by rememberLottieComposition(
            LottieCompositionSpec.RawRes(R.raw.easter_firework)
        )
        val progress by animateLottieCompositionAsState(
            composition = composition,
            iterations = 1
        )
        val finishState = remember(visible) { mutableStateOf(false) }

        fun finishOnce() {
            if (shouldFinishActivationCelebration(visible, finishState.value)) {
                finishState.value = true
                onFinished()
            }
        }

        LaunchedEffect(visible, composition) {
            if (!visible || composition == null) return@LaunchedEffect
            delay(2_500)
            finishOnce()
        }
        LaunchedEffect(visible) {
            if (!visible) return@LaunchedEffect
            delay(3_000)
            finishOnce()
        }

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 8.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            LottieAnimation(
                composition = composition,
                progress = { progress },
                modifier = Modifier
                    .size(104.dp)
                    .rotate(40f)
            )
            LottieAnimation(
                composition = composition,
                progress = { progress },
                modifier = Modifier
                    .size(104.dp)
                    .graphicsLayer {
                        scaleX = -1f
                        rotationZ = -40f
                    }
            )
        }
    }
}
