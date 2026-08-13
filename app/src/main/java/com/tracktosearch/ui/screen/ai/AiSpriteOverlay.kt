package com.tracktosearch.ui.screen.ai

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import com.tracktosearch.data.ai.AiCharacter

/**
 * 兼容旧调用名的精灵动效入口。
 *
 * 自动触发只渲染角色本身，点击角色后由宿主打开现有精灵中心；功能按钮不再在自动
 * 动效中展开。这个入口保留在公共 AI 包内，详情页可以直接改用 [AiSpriteMotion]。
 */
@Composable
fun AiSpriteOverlay(
    visible: Boolean,
    character: AiCharacter?,
    anchor: AiSpriteAnchor,
    anchorBounds: Rect?,
    onOpenCenter: () -> Unit,
    onFinished: () -> Unit = {},
    modifier: Modifier = Modifier,
    interruptRequest: AiSpriteInterruptRequest? = null
) {
    if (character == null) return
    AiSpriteMotion(
        characterId = character.id,
        anchor = anchor,
        anchorBounds = anchorBounds,
        visible = visible && anchorBounds != null,
        onClick = onOpenCenter,
        onFinished = onFinished,
        modifier = modifier,
        interruptRequest = interruptRequest
    )
}
