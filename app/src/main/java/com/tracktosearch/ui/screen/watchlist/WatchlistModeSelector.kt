package com.tracktosearch.ui.screen.watchlist

import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import com.tracktosearch.ui.component.NeumorphicFrostedSurface
import com.tracktosearch.ui.component.isAppDarkTheme

/**
 * Watchlist 专用的想看/已看切换条。
 *
 * 每个选项的宽度由对应字号的文字测量结果决定，选中项同时放大字号和占用宽度，
 * 避免两个状态被固定成等宽而削弱当前模式的视觉层级。
 */
@Composable
internal fun WatchlistModeSelector(
    tabs: List<String>,
    selectedIndex: Int,
    onTabSelected: (Int) -> Unit,
    modifier: Modifier = Modifier
) {
    if (tabs.isEmpty()) return

    val isDark = isAppDarkTheme()
    val safeSelectedIndex = selectedIndex.coerceIn(0, tabs.lastIndex)
    val textMeasurer = rememberTextMeasurer()
    val density = LocalDensity.current
    val capsuleShape = RoundedCornerShape(50)
    val selectedColor = MaterialTheme.colorScheme.primary
    val unselectedColor = MaterialTheme.colorScheme.onSurfaceVariant
    val durationMillis = 240

    val measuredWidths = remember(tabs) {
        tabs.map { label ->
            val selectedText = textMeasurer.measure(
                text = label,
                style = TextStyle(fontSize = 16.sp, fontWeight = FontWeight.Bold)
            ).size.width
            val unselectedText = textMeasurer.measure(
                text = label,
                style = TextStyle(fontSize = 14.sp, fontWeight = FontWeight.Medium)
            ).size.width
            with(density) {
                ModeTextWidths(
                    selected = selectedText.toDp(),
                    unselected = unselectedText.toDp()
                )
            }
        }
    }

    val baseWidths = measuredWidths.map { it.widthFor(isSelected = false) }
    val selectedMinimumWidth = baseWidths
        .filterIndexed { index, _ -> index != safeSelectedIndex }
        .fold(0.dp, androidx.compose.ui.unit.Dp::plus)
        .times(1.18f)
    val targetWidths = measuredWidths.mapIndexed { index, widths ->
        if (index == safeSelectedIndex) {
            maxOf(widths.widthFor(isSelected = true), selectedMinimumWidth)
        } else {
            widths.widthFor(isSelected = false)
        }
    }

    Row(
        modifier = modifier
            .clip(capsuleShape)
            .height(42.dp)
            .background(
                if (isDark) Color.White.copy(alpha = 0.10f)
                else Color.White.copy(alpha = 0.35f)
            )
            .padding(horizontal = 4.dp, vertical = 3.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        tabs.forEachIndexed { index, label ->
            val selected = index == safeSelectedIndex
            val targetWidth = targetWidths[index]
            val itemWidth by animateDpAsState(
                targetValue = targetWidth,
                animationSpec = tween(durationMillis),
                label = "watchlist_mode_width_$index"
            )
            val fontSize by animateFloatAsState(
                targetValue = if (selected) 16f else 14f,
                animationSpec = tween(durationMillis),
                label = "watchlist_mode_font_size_$index"
            )
            val itemModifier = Modifier
                .width(itemWidth)
                .widthIn(min = 44.dp)
                .fillMaxHeight()
                .clip(RoundedCornerShape(50))
                .testTag("watchlist_mode_tab_$index")
                .selectable(
                    selected = selected,
                    role = Role.Tab,
                    onClick = { onTabSelected(index) }
                )
                .semantics(mergeDescendants = true) { this.selected = selected }

            Box(
                modifier = itemModifier,
                contentAlignment = Alignment.Center
            ) {
                if (selected) {
                    NeumorphicFrostedSurface(
                        modifier = Modifier.fillMaxSize(),
                        isDark = isDark,
                        shape = capsuleShape,
                        elevation = 2.dp,
                        blurRadius = 10.dp,
                        backgroundColor = if (isDark) {
                            selectedColor.copy(alpha = 0.30f)
                        } else {
                            selectedColor.copy(alpha = 0.18f)
                        },
                        borderColor = selectedColor.copy(alpha = if (isDark) 0.42f else 0.28f)
                    ) {
                        Box(
                            modifier = Modifier.fillMaxSize(),
                            contentAlignment = Alignment.Center
                        ) {
                            ModeLabel(
                                text = label,
                                fontSize = fontSize,
                                color = selectedColor,
                                selected = true
                            )
                        }
                    }
                } else {
                    ModeLabel(
                        text = label,
                        fontSize = fontSize,
                        color = unselectedColor,
                        selected = false
                    )
                }
            }
        }
    }
}

private data class ModeTextWidths(
    val selected: androidx.compose.ui.unit.Dp,
    val unselected: androidx.compose.ui.unit.Dp
) {
    fun widthFor(isSelected: Boolean): androidx.compose.ui.unit.Dp {
        val horizontalPadding = 18.dp
        return (if (isSelected) selected else unselected) + horizontalPadding
    }
}

@Composable
private fun ModeLabel(
    text: String,
    fontSize: Float,
    color: Color,
    selected: Boolean
) {
    Text(
        text = text,
        color = color,
        fontSize = fontSize.sp,
        fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium,
        maxLines = 1
    )
}
