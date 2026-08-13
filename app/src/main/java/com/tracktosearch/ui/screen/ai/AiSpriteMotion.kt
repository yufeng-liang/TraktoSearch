package com.tracktosearch.ui.screen.ai

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.SizeTransform
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.togetherWith
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.geometry.Rect
import kotlinx.coroutines.delay
import com.tracktosearch.R
import kotlin.math.roundToInt

enum class AiSpriteMotionState {
    HIDDEN,
    PREPARE,
    PEEK,
    OBSERVE,
    REACT,
    RETREAT
}

enum class AiSpriteAnchor {
    Cloud,
    SearchBox,
    ResultCard,
    BottomPanel,
    DetailHeader,
    DetailPanel,
    RecommendationsTab
}

enum class AiSpriteInterruptReason {
    USER_INPUT,
    FOCUS,
    SCROLL,
    NAVIGATION,
    BLOCKED
}

/** 页面间共享的可中断事件。revision 由宿主单调递增，避免重复事件被忽略。 */
data class AiSpriteInterruptRequest(
    val revision: Long,
    val reason: AiSpriteInterruptReason
)

/** 纯状态控制器，保证任意中断都经过 RETREAT，而不是突然移除角色。 */
class AiSpriteMotionController {
    var state: AiSpriteMotionState = AiSpriteMotionState.HIDDEN
        private set

    fun start(next: AiSpriteMotionState = AiSpriteMotionState.PREPARE) {
        if (state == AiSpriteMotionState.RETREAT) state = AiSpriteMotionState.HIDDEN
        state = next
    }

    fun advance(next: AiSpriteMotionState) {
        if (state != AiSpriteMotionState.RETREAT) state = next
    }

    fun interrupt(@Suppress("UNUSED_PARAMETER") reason: AiSpriteInterruptReason) {
        if (state != AiSpriteMotionState.HIDDEN) state = AiSpriteMotionState.RETREAT
    }

    fun interrupt(request: AiSpriteInterruptRequest) {
        interrupt(request.reason)
    }

    fun finishRetreat() {
        state = AiSpriteMotionState.HIDDEN
    }
}

@Composable
fun AiSpriteMotion(
    characterId: String,
    anchor: AiSpriteAnchor,
    anchorBounds: Rect?,
    visible: Boolean,
    onClick: () -> Unit,
    onFinished: () -> Unit,
    modifier: Modifier = Modifier,
    interruptRequest: AiSpriteInterruptRequest? = null
) {
    val art = automaticSpriteArt(characterId) ?: return
    val controller = remember(characterId, anchor) { AiSpriteMotionController() }
    var state by remember(characterId, anchor) { mutableStateOf(AiSpriteMotionState.HIDDEN) }
    var handledInterruptRevision by remember(characterId, anchor) { mutableStateOf(0L) }

    LaunchedEffect(visible, characterId, anchor, interruptRequest?.revision) {
        val request = interruptRequest
        if (request != null && request.revision > handledInterruptRevision) {
            handledInterruptRevision = request.revision
            controller.interrupt(request)
            state = controller.state
            if (state == AiSpriteMotionState.RETREAT) {
                delay(240L)
                controller.finishRetreat()
                state = controller.state
            }
            return@LaunchedEffect
        }

        if (!visible) {
            if (state != AiSpriteMotionState.HIDDEN) {
                controller.interrupt(AiSpriteInterruptReason.NAVIGATION)
                state = controller.state
                delay(240L)
                controller.finishRetreat()
                state = controller.state
            }
            return@LaunchedEffect
        }

        controller.finishRetreat()
        controller.start()
        state = controller.state
        delay(120L)
        controller.advance(AiSpriteMotionState.PEEK)
        state = controller.state
        delay(560L)
        controller.advance(AiSpriteMotionState.OBSERVE)
        state = controller.state
        delay(640L)
        controller.advance(AiSpriteMotionState.REACT)
        state = controller.state
        delay(460L)
        controller.interrupt(AiSpriteInterruptReason.NAVIGATION)
        state = controller.state
        delay(240L)
        controller.finishRetreat()
        state = controller.state
        onFinished()
    }

    SpriteMotionVisual(
        art = art,
        anchor = anchor,
        anchorBounds = anchorBounds,
        state = state,
        onClick = onClick,
        modifier = modifier
    )
}

@Composable
private fun SpriteMotionVisual(
    art: AiCharacterArtSpec,
    anchor: AiSpriteAnchor,
    anchorBounds: Rect?,
    state: AiSpriteMotionState,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val density = LocalDensity.current
    val spriteSize = when (anchor) {
        AiSpriteAnchor.Cloud -> 112.dp
        AiSpriteAnchor.SearchBox -> 118.dp
        AiSpriteAnchor.ResultCard -> 108.dp
        AiSpriteAnchor.BottomPanel -> 112.dp
        AiSpriteAnchor.DetailHeader -> 116.dp
        AiSpriteAnchor.DetailPanel -> 112.dp
        AiSpriteAnchor.RecommendationsTab -> 108.dp
    }
    val windowHeight = when (anchor) {
        AiSpriteAnchor.SearchBox -> 92.dp
        AiSpriteAnchor.Cloud -> 122.dp
        AiSpriteAnchor.ResultCard -> 128.dp
        AiSpriteAnchor.BottomPanel -> 118.dp
        AiSpriteAnchor.DetailHeader -> 124.dp
        AiSpriteAnchor.DetailPanel -> 118.dp
        AiSpriteAnchor.RecommendationsTab -> 122.dp
    }
    val placement = with(density) {
        anchorBounds?.let { bounds ->
            val centerX = bounds.center.x.toDp()
            val top = bounds.top.toDp()
            when (anchor) {
                AiSpriteAnchor.Cloud -> centerX - spriteSize / 2f to top - 8.dp
                AiSpriteAnchor.SearchBox -> bounds.right.toDp() - spriteSize + 18.dp to top - windowHeight + 3.dp
                AiSpriteAnchor.ResultCard -> bounds.right.toDp() - spriteSize + 12.dp to top - 46.dp
                AiSpriteAnchor.BottomPanel -> bounds.right.toDp() - spriteSize + 18.dp to top - windowHeight + 4.dp
                AiSpriteAnchor.DetailHeader -> centerX - spriteSize / 2f to top + bounds.height.toDp() - 14.dp
                AiSpriteAnchor.DetailPanel -> bounds.right.toDp() - spriteSize + 14.dp to top - windowHeight + 4.dp
                AiSpriteAnchor.RecommendationsTab -> bounds.right.toDp() - spriteSize + 10.dp to top - 42.dp
            }
        } ?: (0.dp to 132.dp)
    }
    val offsetY by animateDpAsState(
        targetValue = when (state) {
            AiSpriteMotionState.HIDDEN -> 28.dp
            AiSpriteMotionState.PREPARE -> 24.dp
            AiSpriteMotionState.PEEK -> 0.dp
            AiSpriteMotionState.OBSERVE -> (-3).dp
            AiSpriteMotionState.REACT -> (-8).dp
            AiSpriteMotionState.RETREAT -> 24.dp
        },
        animationSpec = spring(stiffness = Spring.StiffnessMediumLow),
        label = "sprite_offset_y"
    )
    val alpha by animateFloatAsState(
        targetValue = if (state == AiSpriteMotionState.HIDDEN || state == AiSpriteMotionState.PREPARE || state == AiSpriteMotionState.RETREAT) 0f else 1f,
        animationSpec = spring(stiffness = Spring.StiffnessMediumLow),
        label = "sprite_alpha"
    )
    val scale by animateFloatAsState(
        targetValue = when (state) {
            AiSpriteMotionState.PREPARE -> 0.86f
            AiSpriteMotionState.PEEK -> 0.98f * art.peekScale
            AiSpriteMotionState.OBSERVE -> 1f * art.peekScale
            AiSpriteMotionState.REACT -> 1.04f * art.peekScale
            AiSpriteMotionState.HIDDEN,
            AiSpriteMotionState.RETREAT -> 0.88f
        },
        animationSpec = spring(stiffness = Spring.StiffnessLow),
        label = "sprite_scale"
    )
    val rotation by animateFloatAsState(
        targetValue = if (state == AiSpriteMotionState.REACT) art.reactionRotation else 0f,
        animationSpec = spring(stiffness = Spring.StiffnessMediumLow),
        label = "sprite_rotation"
    )
    val contentDescription = stringResource(R.string.ai_sprite_open_center)

    Box(
        modifier = modifier
            .offset { IntOffset(placement.first.roundToPx(), placement.second.roundToPx()) }
            .size(spriteSize, windowHeight)
            .clipToBounds()
            .semantics { this.contentDescription = contentDescription }
            .clickable(onClick = onClick),
        contentAlignment = Alignment.BottomCenter
    ) {
        AnimatedContent(
            targetState = art.drawableFor(state, anchor),
            transitionSpec = {
                (fadeIn(animationSpec = tween(120)) + scaleIn(initialScale = 0.96f))
                    .togetherWith(fadeOut(animationSpec = tween(90)))
                    .using(SizeTransform(clip = false))
            },
            label = "sprite_frame"
        ) { frameRes ->
            Image(
                painter = painterResource(frameRes),
                contentDescription = null,
                contentScale = ContentScale.Fit,
                modifier = Modifier
                    .fillMaxSize()
                    .graphicsLayer {
                        this.alpha = alpha
                        translationY = offsetY.toPx()
                        scaleX = scale
                        scaleY = scale
                        rotationZ = rotation
                    }
            )
        }
        SpriteReactionEffect(
            reaction = art.reaction,
            active = state == AiSpriteMotionState.REACT,
            modifier = Modifier.align(Alignment.TopEnd)
        )
    }
}

@Composable
private fun SpriteReactionEffect(
    reaction: SpriteReaction,
    active: Boolean,
    modifier: Modifier = Modifier
) {
    val effectColor = when (reaction) {
        SpriteReaction.GENTLE_LOOK -> Color(0xFFFFA7C2)
        SpriteReaction.NOD -> Color(0xFF8AC7E8)
        SpriteReaction.BOUNCE -> Color(0xFFFFD56A)
    }
    Box(
        modifier = modifier
            .size(if (active) 18.dp else 10.dp)
            .clip(CircleShape)
            .background(effectColor.copy(alpha = if (active) 0.9f else 0f))
    )
}
