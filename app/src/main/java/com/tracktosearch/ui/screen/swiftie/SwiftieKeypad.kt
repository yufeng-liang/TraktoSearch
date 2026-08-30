package com.tracktosearch.ui.screen.swiftie

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Backspace
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.ripple
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.tracktosearch.R

/**
 * 自绘数字键盘。半透明糖果玻璃圆角块，按下缩到 0.92 并出洋红涟漪。
 *
 * 与灯箱不同，键盘**跟随主题**（Spec §2.3）。
 *
 * @param canSubmit 提交键是否点亮。由 `SwiftieQuizState.canSubmit` 直接喂进来
 * @param enabled 答对后置 false，避免动画期间还能继续按
 * @param onSubmitCenter 提交键在 root 坐标系的中心点，供扩散层当原点用
 */
@Composable
fun SwiftieKeypad(
    canSubmit: Boolean,
    onDigit: (Char) -> Unit,
    onBackspace: () -> Unit,
    onSubmit: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    onSubmitCenter: (Offset) -> Unit = {}
) {
    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        listOf("123", "456", "789").forEach { rowDigits ->
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                rowDigits.forEach { digit ->
                    DigitKey(digit = digit, enabled = enabled, onClick = { onDigit(digit) })
                }
            }
        }
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            BackspaceKey(enabled = enabled, onClick = onBackspace)
            DigitKey(digit = '0', enabled = enabled, onClick = { onDigit('0') })
            SubmitKey(
                highlight = canSubmit,
                enabled = enabled && canSubmit,
                onClick = onSubmit,
                onCenter = onSubmitCenter
            )
        }
    }
}

@Composable
private fun RowScope.DigitKey(digit: Char, enabled: Boolean, onClick: () -> Unit) {
    KeyCell(enabled = enabled, highlight = false, onClick = onClick) {
        Text(
            text = digit.toString(),
            style = TextStyle(
                fontFamily = SwiftieFonts.Marker,
                fontSize = 30.sp,
                color = MaterialTheme.colorScheme.onSurface,
                textAlign = TextAlign.Center
            )
        )
    }
}

@Composable
private fun RowScope.BackspaceKey(enabled: Boolean, onClick: () -> Unit) {
    val label = stringResource(R.string.swiftie_quiz_backspace)
    KeyCell(enabled = enabled, highlight = false, onClick = onClick) {
        Icon(
            imageVector = Icons.Outlined.Backspace,
            contentDescription = label,
            tint = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.size(26.dp)
        )
    }
}

@Composable
private fun RowScope.SubmitKey(
    highlight: Boolean,
    enabled: Boolean,
    onClick: () -> Unit,
    onCenter: (Offset) -> Unit
) {
    KeyCell(
        enabled = enabled,
        highlight = highlight,
        onClick = onClick,
        // root 坐标系的中心点，直接喂给扩散层
        cellModifier = Modifier.onGloballyPositioned { coordinates ->
            val position = coordinates.positionInRoot()
            onCenter(
                Offset(
                    x = position.x + coordinates.size.width / 2f,
                    y = position.y + coordinates.size.height / 2f
                )
            )
        }
    ) {
        Text(
            text = stringResource(R.string.swiftie_quiz_submit),
            style = MaterialTheme.typography.titleMedium,
            // 点亮时底色是 Badge（对白字 5.04:1，合规）
            color = if (highlight) Color.White else MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

@Composable
private fun RowScope.KeyCell(
    enabled: Boolean,
    highlight: Boolean,
    onClick: () -> Unit,
    cellModifier: Modifier = Modifier,
    content: @Composable () -> Unit
) {
    val interactionSource = remember { MutableInteractionSource() }
    val isPressed by interactionSource.collectIsPressedAsState()
    val scale by animateFloatAsState(
        targetValue = if (isPressed) 0.92f else 1f,
        label = "swiftie_key_scale"
    )
    val isLight = MaterialTheme.colorScheme.background.luminance() > 0.5f
    val shape = RoundedCornerShape(18.dp)
    val base = when {
        highlight -> SwiftiePalette.Badge
        isLight -> MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.60f)
        else -> Color.White.copy(alpha = 0.10f)
    }
    Box(
        modifier = Modifier
            .weight(1f)
            .then(cellModifier)
            // 1.6：窄屏与 480dp 宽保持同一比例，不会在宽屏上摊成细长条
            .aspectRatio(1.6f)
            .scale(scale)
            // .alpha() 必须排在 .background() 之前：它只作用于链上位于其后的绘制，
            // 放在后面就只压暗内容、压不暗底板
            .alpha(if (enabled) 1f else 0.45f)
            .clip(shape)
            .background(base)
            // 糖果玻璃的上缘高光
            .background(
                Brush.verticalGradient(
                    listOf(Color.White.copy(alpha = 0.30f), Color.Transparent)
                )
            )
            .border(
                width = 1.dp,
                color = Color.White.copy(alpha = if (isLight) 0.55f else 0.16f),
                shape = shape
            )
            .clickable(
                interactionSource = interactionSource,
                indication = ripple(color = SwiftiePalette.Glitter),
                enabled = enabled,
                onClick = onClick
            ),
        contentAlignment = Alignment.Center
    ) {
        content()
    }
}


