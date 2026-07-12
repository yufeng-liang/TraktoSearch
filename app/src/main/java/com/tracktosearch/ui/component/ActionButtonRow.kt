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
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.chrisbanes.haze.HazeState
import dev.chrisbanes.haze.hazeEffect
import dev.chrisbanes.haze.materials.ExperimentalHazeMaterialsApi
import dev.chrisbanes.haze.materials.HazeMaterials

/**
 * 详情页操作按钮组（想看 / 已看 / 评分）
 *
 * @param actions 操作项列表
 * @param hazeState Haze 模糊状态
 */
@OptIn(ExperimentalHazeMaterialsApi::class)
@Composable
fun ActionButtonRow(
    actions: List<ActionItem>,
    hazeState: HazeState,
    modifier: Modifier = Modifier
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
            val bg = if (action.selected) {
                MaterialTheme.colorScheme.primary
            } else {
                Color.White.copy(alpha = 0.08f)
            }
            val shadow = if (action.selected) 8.dp else 0.dp
            val hazeStyle = if (action.selected) {
                null
            } else {
                HazeMaterials.thin(MaterialTheme.colorScheme.background)
            }
            val contentAlpha = if (action.enabled && !action.isLoading) 1f else 0.5f

            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                modifier = Modifier
                    .weight(1f)
                    .padding(horizontal = 4.dp)
                    .scale(scale)
                    .shadow(shadow, RoundedCornerShape(14.dp))
                    .clip(RoundedCornerShape(14.dp))
                    .then(
                        if (hazeStyle != null) {
                            Modifier.hazeEffect(state = hazeState, style = hazeStyle)
                        } else {
                            Modifier
                        }
                    )
                    .background(bg)
                    .clickable(
                        interactionSource = interactionSource,
                        indication = null,
                        enabled = action.enabled && !action.isLoading,
                        onClick = action.onClick
                    )
                    .alpha(contentAlpha)
                    .padding(vertical = 10.dp)
            ) {
                if (action.isLoading) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(24.dp),
                        color = Color.White,
                        strokeWidth = 2.dp
                    )
                } else {
                    Icon(
                        imageVector = action.icon,
                        contentDescription = action.label,
                        tint = Color.White
                    )
                }
                Text(
                    text = action.label,
                    color = Color.White,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Medium
                )
            }
        }
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
    val onClick: () -> Unit
)
