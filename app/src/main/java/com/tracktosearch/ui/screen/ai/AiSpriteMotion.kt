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
    RecommendationsTab,
    QuizResult,
    AiFeatureHeader
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

/**
 * 探头动效各阶段时长。
 *
 * 原来 PEEK/OBSERVE/REACT 只有 560/640/460ms，加上淡入淡出总共约 2 秒，
 * 而角色全程在动——用户基本不可能在这个窗口里点中它，"点精灵进中心" 形同没有。
 * 拉长 OBSERVE 这个静态观察段，把可点窗口做到约 3 秒。
 */
private const val PEEK_DURATION_MS = 700L
private const val OBSERVE_DURATION_MS = 1_500L
private const val REACT_DURATION_MS = 800L
private const val RETREAT_DURATION_MS = 240L

@Composable
fun AiSpriteMotion(
    characterId: String,
    anchor: AiSpriteAnchor,
    anchorBounds: Rect?,
    visible: Boolean,
    onClick: () -> Unit,
    onFinished: () -> Unit,
    modifier: Modifier = Modifier,
    sceneRes: Int? = null,
    interactive: Boolean = true,
    interruptRequest: AiSpriteInterruptRequest? = null
) {
    val art = automaticSpriteArt(characterId) ?: return
    val controller = remember(characterId, anchor) { AiSpriteMotionController() }
    var state by remember(characterId, anchor) { mutableStateOf(AiSpriteMotionState.HIDDEN) }
    // 换锚点时从当前 revision 开始，避免把上一个页面/锚点留下的中断请求误当成新事件。
    var handledInterruptRevision by remember(characterId, anchor) {
        mutableStateOf(interruptRequest?.revision ?: 0L)
    }

    LaunchedEffect(visible, characterId, anchor, sceneRes, interruptRequest?.revision) {
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
        delay(PEEK_DURATION_MS)
        controller.advance(AiSpriteMotionState.OBSERVE)
        state = controller.state
        delay(OBSERVE_DURATION_MS)
        controller.advance(AiSpriteMotionState.REACT)
        state = controller.state
        delay(REACT_DURATION_MS)
        controller.interrupt(AiSpriteInterruptReason.NAVIGATION)
        state = controller.state
        delay(RETREAT_DURATION_MS)
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
        sceneRes = sceneRes,
        interactive = interactive,
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
    sceneRes: Int?,
    interactive: Boolean,
    modifier: Modifier = Modifier
) {
    if (state == AiSpriteMotionState.HIDDEN) return

    val density = LocalDensity.current
    val spriteSize = when (anchor) {
        AiSpriteAnchor.Cloud -> 112.dp
        AiSpriteAnchor.SearchBox -> 118.dp
        AiSpriteAnchor.ResultCard -> 108.dp
        AiSpriteAnchor.BottomPanel -> 112.dp
        AiSpriteAnchor.DetailHeader -> 116.dp
        AiSpriteAnchor.DetailPanel -> 112.dp
        AiSpriteAnchor.RecommendationsTab -> 108.dp
        AiSpriteAnchor.QuizResult -> 132.dp
        AiSpriteAnchor.AiFeatureHeader -> 136.dp
    }
    val windowHeight = when (anchor) {
        AiSpriteAnchor.SearchBox -> 92.dp
        AiSpriteAnchor.Cloud -> 122.dp
        AiSpriteAnchor.ResultCard -> 128.dp
        AiSpriteAnchor.BottomPanel -> 118.dp
        AiSpriteAnchor.DetailHeader -> 124.dp
        AiSpriteAnchor.DetailPanel -> 118.dp
        AiSpriteAnchor.RecommendationsTab -> 122.dp
        AiSpriteAnchor.QuizResult -> 148.dp
        AiSpriteAnchor.AiFeatureHeader -> 152.dp
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
                AiSpriteAnchor.QuizResult -> centerX - spriteSize / 2f to top - 12.dp
                AiSpriteAnchor.AiFeatureHeader -> centerX - spriteSize / 2f to top + bounds.height.toDp() - 18.dp
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

    // 场景图之前被排除在可点范围外，等于搜索页精灵冒了个头但完全点不动。
    // 现在由调用方用 interactive 决定：搜索页可点进精灵中心，功能页内的场景图不可点。
    val isInteractive = interactive && (state == AiSpriteMotionState.PEEK ||
        state == AiSpriteMotionState.OBSERVE ||
        state == AiSpriteMotionState.REACT)
    val layeredArt = art.layerArt
    Box(
        modifier = modifier
            .offset { IntOffset(placement.first.roundToPx(), placement.second.roundToPx()) }
            .size(spriteSize, windowHeight)
            .clipToBounds()
            .then(if (isInteractive) Modifier.semantics { this.contentDescription = contentDescription } else Modifier)
            .then(if (isInteractive) Modifier.clickable(onClick = onClick) else Modifier),
        contentAlignment = Alignment.BottomCenter
    ) {
        if (sceneRes != null) {
            Image(
                painter = painterResource(sceneRes),
                contentDescription = null,
                contentScale = ContentScale.Fit,
                modifier = Modifier
                    .fillMaxSize()
                    .graphicsLayer {
                        this.alpha = alpha
                        translationY = offsetY.toPx()
                        val sceneScale = when (state) {
                            AiSpriteMotionState.PREPARE -> 0.88f
                            AiSpriteMotionState.PEEK -> 0.96f
                            AiSpriteMotionState.OBSERVE -> 1f
                            AiSpriteMotionState.REACT -> 1.025f
                            AiSpriteMotionState.HIDDEN,
                            AiSpriteMotionState.RETREAT -> 0.9f
                        }
                        scaleX = sceneScale
                        scaleY = sceneScale
                    }
            )
        } else if (layeredArt != null) {
            LayeredSpriteMotionVisual(
                art = layeredArt,
                state = state,
                alpha = alpha,
                offsetY = offsetY,
                scale = scale,
                rotation = rotation
            )
        } else {
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
        }
        if (sceneRes == null) {
            SpriteReactionEffect(
                reaction = art.reaction,
                active = state == AiSpriteMotionState.REACT,
                modifier = Modifier.align(Alignment.TopEnd)
            )
        }
    }
}

private data class LayerTransform(
    val translationX: Dp = 0.dp,
    val translationY: Dp = 0.dp,
    val scale: Float = 1f,
    val rotation: Float = 0f,
    val alpha: Float = 1f
)

@Composable
private fun LayeredSpriteMotionVisual(
    art: AiCharacterLayerArt,
    state: AiSpriteMotionState,
    alpha: Float,
    offsetY: Dp,
    scale: Float,
    rotation: Float
) {
    val frameState = when (state) {
        AiSpriteMotionState.PEEK -> AiSpriteMotionState.PEEK
        AiSpriteMotionState.REACT -> AiSpriteMotionState.REACT
        else -> AiSpriteMotionState.OBSERVE
    }
    AiSpriteLayer.entries
        .filter { it != AiSpriteLayer.EFFECTS }
        .forEach { layer ->
            val frameRes = art.drawableFor(layer, frameState) ?: return@forEach
            val transform = layerTransform(layer, state)
            val layerAlpha by animateFloatAsState(
                targetValue = alpha * transform.alpha,
                animationSpec = spring(stiffness = Spring.StiffnessMediumLow),
                label = "sprite_${layer.name.lowercase()}_alpha"
            )
            val layerOffsetX by animateDpAsState(
                targetValue = transform.translationX,
                animationSpec = spring(stiffness = Spring.StiffnessMediumLow),
                label = "sprite_${layer.name.lowercase()}_offset_x"
            )
            val layerOffsetY by animateDpAsState(
                targetValue = offsetY + transform.translationY,
                animationSpec = spring(stiffness = Spring.StiffnessMediumLow),
                label = "sprite_${layer.name.lowercase()}_offset_y"
            )
            val layerScale by animateFloatAsState(
                targetValue = scale * transform.scale,
                animationSpec = spring(stiffness = Spring.StiffnessLow),
                label = "sprite_${layer.name.lowercase()}_scale"
            )
            val layerRotation by animateFloatAsState(
                targetValue = rotation + transform.rotation,
                animationSpec = spring(stiffness = Spring.StiffnessMediumLow),
                label = "sprite_${layer.name.lowercase()}_rotation"
            )
            AnimatedContent(
                targetState = frameRes,
                transitionSpec = {
                    (fadeIn(animationSpec = tween(120)) + scaleIn(initialScale = 0.98f))
                        .togetherWith(fadeOut(animationSpec = tween(90)))
                        .using(SizeTransform(clip = false))
                },
                label = "sprite_${layer.name.lowercase()}_frame"
            ) { resource ->
                Image(
                    painter = painterResource(resource),
                    contentDescription = null,
                    contentScale = ContentScale.Fit,
                    modifier = Modifier
                        .fillMaxSize()
                        .graphicsLayer {
                            this.alpha = layerAlpha
                            translationX = layerOffsetX.toPx()
                            translationY = layerOffsetY.toPx()
                            scaleX = layerScale
                            scaleY = layerScale
                            rotationZ = layerRotation
                        }
                )
            }
        }
}

private fun layerTransform(layer: AiSpriteLayer, state: AiSpriteMotionState): LayerTransform {
    return when (layer) {
        AiSpriteLayer.BODY -> when (state) {
            AiSpriteMotionState.PREPARE -> LayerTransform(translationY = 5.dp, scale = 0.96f)
            AiSpriteMotionState.PEEK -> LayerTransform(translationY = 1.dp, scale = 0.99f)
            AiSpriteMotionState.OBSERVE -> LayerTransform(translationY = 0.dp, scale = 1f)
            AiSpriteMotionState.REACT -> LayerTransform(translationY = (-2).dp, scale = 1.015f)
            AiSpriteMotionState.RETREAT -> LayerTransform(translationY = 7.dp, scale = 0.96f, alpha = 0.8f)
            AiSpriteMotionState.HIDDEN -> LayerTransform(alpha = 0f)
        }
        AiSpriteLayer.HEAD -> when (state) {
            AiSpriteMotionState.PREPARE -> LayerTransform(translationY = 5.dp, scale = 0.96f)
            AiSpriteMotionState.PEEK -> LayerTransform(translationY = (-1).dp, rotation = -1f)
            AiSpriteMotionState.OBSERVE -> LayerTransform(translationX = 1.dp, rotation = 1.4f)
            AiSpriteMotionState.REACT -> LayerTransform(translationY = (-3).dp, scale = 1.025f, rotation = -2f)
            AiSpriteMotionState.RETREAT -> LayerTransform(translationY = 7.dp, rotation = -1f, alpha = 0.8f)
            AiSpriteMotionState.HIDDEN -> LayerTransform(alpha = 0f)
        }
        AiSpriteLayer.EARS -> when (state) {
            AiSpriteMotionState.PREPARE -> LayerTransform(translationY = 6.dp, scale = 0.94f)
            AiSpriteMotionState.PEEK -> LayerTransform(translationY = (-3).dp, rotation = -1.2f)
            AiSpriteMotionState.OBSERVE -> LayerTransform(translationX = (-1).dp, rotation = 1.8f)
            AiSpriteMotionState.REACT -> LayerTransform(translationY = (-7).dp, scale = 1.06f, rotation = -3.5f)
            AiSpriteMotionState.RETREAT -> LayerTransform(translationY = 9.dp, rotation = 1f, alpha = 0.8f)
            AiSpriteMotionState.HIDDEN -> LayerTransform(alpha = 0f)
        }
        AiSpriteLayer.FACE -> when (state) {
            AiSpriteMotionState.PREPARE -> LayerTransform(translationY = 4.dp, scale = 0.96f)
            AiSpriteMotionState.PEEK -> LayerTransform(translationX = 1.dp, translationY = (-1).dp)
            AiSpriteMotionState.OBSERVE -> LayerTransform(translationX = 2.dp, rotation = 0.6f)
            AiSpriteMotionState.REACT -> LayerTransform(translationY = (-2).dp, scale = 1.03f, rotation = -1.2f)
            AiSpriteMotionState.RETREAT -> LayerTransform(translationY = 7.dp, alpha = 0.8f)
            AiSpriteMotionState.HIDDEN -> LayerTransform(alpha = 0f)
        }
        AiSpriteLayer.ARMS -> when (state) {
            AiSpriteMotionState.PREPARE -> LayerTransform(translationY = 5.dp, scale = 0.96f)
            AiSpriteMotionState.PEEK -> LayerTransform(translationX = (-1).dp, rotation = -1.5f)
            AiSpriteMotionState.OBSERVE -> LayerTransform(translationX = 1.dp, rotation = 1.2f)
            AiSpriteMotionState.REACT -> LayerTransform(translationY = (-5).dp, scale = 1.04f, rotation = 4f)
            AiSpriteMotionState.RETREAT -> LayerTransform(translationY = 8.dp, rotation = 2f, alpha = 0.8f)
            AiSpriteMotionState.HIDDEN -> LayerTransform(alpha = 0f)
        }
        AiSpriteLayer.EFFECTS -> LayerTransform()
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
