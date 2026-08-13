package com.tracktosearch.ui.component

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.chrisbanes.haze.HazeState
import com.tracktosearch.ui.theme.LocalVisualEffectMode

/**
 * 详情页操作按钮组（想看 / 已看 / 评分）
 *
 * @param actions 操作项列表
 * @param hazeState Haze 模糊状态
 */
@Composable
fun ActionButtonRow(
    actions: List<ActionItem>,
    hazeState: HazeState,
    modifier: Modifier = Modifier,
    verticalPadding: Dp = 5.dp
) {
    Row(
        modifier = modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically
    ) {
        actions.forEach { action ->
            val interactionSource = remember { MutableInteractionSource() }
            val isPressed by interactionSource.collectIsPressedAsState()
            val scale by animateFloatAsState(
                targetValue = if (isPressed) 0.96f else 1f,
                label = "action_button_scale"
            )
            val isLight = MaterialTheme.colorScheme.background.luminance() > 0.5f
            val bg = if (action.isDestructive) {
                MaterialTheme.colorScheme.errorContainer
            } else if (action.selected) {
                MaterialTheme.colorScheme.primary
            } else if (isLight) {
                MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f)
            } else {
                Color.White.copy(alpha = 0.08f)
            }
            val contentColor = if (action.isDestructive) {
                MaterialTheme.colorScheme.error
            } else if (action.selected) {
                MaterialTheme.colorScheme.onPrimary
            } else {
                MaterialTheme.colorScheme.onSurface
            }
            val contentAlpha = if (action.enabled && !action.isLoading) 1f else 0.5f
            val useGlassSurface = surfaceTreatmentFor(LocalVisualEffectMode.current) == SurfaceTreatment.GLASS

            val buttonModifier = Modifier
                    .weight(1f)
                    .padding(horizontal = 4.dp)
                    .scale(scale)
                    .clickable(
                        interactionSource = interactionSource,
                        indication = null,
                        enabled = action.enabled && !action.isLoading,
                        onClick = action.onClick
                    )
                    .alpha(contentAlpha)
                    .padding(vertical = verticalPadding)
            if (useGlassSurface) {
                AppVisualSurface(
                    kind = VisualSurfaceKind.Glass,
                    modifier = buttonModifier,
                    shape = RoundedCornerShape(14.dp),
                    hazeState = hazeState,
                    role = GlassSurfaceRole.DetailAction,
                    interactionSource = interactionSource,
                    backgroundColor = bg,
                    borderColor = MaterialTheme.colorScheme.outline.copy(alpha = 0.22f)
                ) {
                    ActionButtonContent(action, contentColor)
                }
            } else {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    modifier = buttonModifier
                        .neumorphicShadow(
                            shape = RoundedCornerShape(14.dp),
                            isDark = !isLight,
                            elevation = 4.dp,
                            darkAlpha = if (isLight) 0.18f else 0.35f,
                            lightAlpha = if (isLight) 0.55f else 0.10f,
                            blurRadius = 10.dp,
                            shadowOffset = 4.dp
                        )
                        .clip(RoundedCornerShape(14.dp))
                        .background(bg)
                ) {
                    ActionButtonContent(action, contentColor)
                }
            }
        }
    }
}

@Composable
private fun ActionButtonContent(
    action: ActionItem,
    contentColor: Color
) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                if (action.isLoading) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(24.dp),
                        color = contentColor,
                        strokeWidth = 2.dp
                    )
                } else {
                    Icon(
                        imageVector = action.icon,
                        contentDescription = action.label,
                        tint = contentColor
                    )
                }
                Text(
                    text = action.label,
                    color = contentColor,
                    fontSize = 12.sp,
                    lineHeight = 12.sp,
                    fontWeight = FontWeight.Medium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
    }
}

/**
 * 操作按钮项数据类
 */
data class ActionItem(
    val icon: ImageVector,
    val label: String,
    val selected: Boolean = false,
    val enabled: Boolean = true,
    val isLoading: Boolean = false,
    val isDestructive: Boolean = false,
    val onClick: () -> Unit
)
