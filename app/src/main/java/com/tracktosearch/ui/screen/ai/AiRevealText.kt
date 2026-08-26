package com.tracktosearch.ui.screen.ai

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay

/** AI 结果正文的呈现方式：无动画 / 打字机 / 渐入。 */
enum class AiTextReveal {
    NONE,
    TYPEWRITER,
    FADE_IN
}

/**
 * 打字机效果文本：约 30ms/字符逐字显示，播完常驻全文。
 *
 * 播放进度用 rememberSaveable 记住：返回本页或重组时从断点继续，已播完则直接整段渲染，
 * 不重播闪烁；文本变化时从头重播。空/空白文本不播动画直接渲染。
 * 动画只由 LaunchedEffect 驱动，不阻塞用户交互和滚动。
 */
@Composable
fun TypewriterText(
    text: String,
    modifier: Modifier = Modifier,
    style: TextStyle = MaterialTheme.typography.bodyLarge,
    color: Color = Color.Unspecified,
    fontWeight: FontWeight? = null,
    charDelayMillis: Long = 30L
) {
    if (text.isBlank()) {
        Text(text = text, modifier = modifier, style = style, color = color, fontWeight = fontWeight)
        return
    }
    var revealed by rememberSaveable(text) { mutableStateOf(0) }
    LaunchedEffect(text) {
        while (revealed < text.length) {
            delay(charDelayMillis)
            revealed += 1
        }
    }
    Text(
        text = text.take(revealed),
        modifier = modifier,
        style = style,
        color = color,
        fontWeight = fontWeight
    )
}

/**
 * 渐入文本：淡入并轻微上移，供次要文案错峰出场（[delayMillis] 控制错开延迟）。
 *
 * 同文本已显示过后不再重播，返回本页保持显示；文本变化时重新渐入。
 * 空/空白文本不播动画直接渲染。
 */
@Composable
fun FadeInText(
    text: String,
    modifier: Modifier = Modifier,
    style: TextStyle = MaterialTheme.typography.bodyLarge,
    color: Color = Color.Unspecified,
    fontWeight: FontWeight? = null,
    delayMillis: Long = 0L
) {
    if (text.isBlank()) {
        Text(text = text, modifier = modifier, style = style, color = color, fontWeight = fontWeight)
        return
    }
    var shown by rememberSaveable(text) { mutableStateOf(false) }
    val progress by animateFloatAsState(
        targetValue = if (shown) 1f else 0f,
        animationSpec = tween(durationMillis = 350)
    )
    LaunchedEffect(text) {
        if (delayMillis > 0) delay(delayMillis)
        shown = true
    }
    Text(
        text = text,
        modifier = modifier.graphicsLayer {
            alpha = progress
            translationY = (1f - progress) * 6.dp.toPx()
        },
        style = style,
        color = color,
        fontWeight = fontWeight
    )
}
