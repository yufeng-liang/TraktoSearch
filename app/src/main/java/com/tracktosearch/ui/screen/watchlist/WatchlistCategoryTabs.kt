package com.tracktosearch.ui.screen.watchlist

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.tracktosearch.ui.component.GlassTabIndicator
import com.tracktosearch.ui.component.NeumorphicFrostedSurface
import com.tracktosearch.ui.component.isAppDarkTheme
import com.tracktosearch.ui.theme.GlassFillDark
import com.tracktosearch.ui.theme.LocalVisualEffectMode
import com.tracktosearch.ui.theme.VisualEffectMode

internal data class WatchlistCategoryTab(
    val label: String,
    val count: Int
)

@Composable
internal fun WatchlistCategoryTabs(
    tabs: List<WatchlistCategoryTab>,
    selectedIndex: Int,
    onTabSelected: (Int) -> Unit,
    modifier: Modifier = Modifier
) {
    if (tabs.isEmpty()) return

    val isDark = isAppDarkTheme()
    val primary = MaterialTheme.colorScheme.primary
    val safeSelectedIndex = selectedIndex.coerceIn(0, tabs.lastIndex)
    val selectedShape = RoundedCornerShape(18.dp)

    Row(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        tabs.forEachIndexed { index, tab ->
            val selected = index == safeSelectedIndex
            val scale by animateFloatAsState(
                targetValue = if (selected) 1.05f else 1f,
                animationSpec = tween(durationMillis = 240),
                label = "watchlist_category_scale_$index"
            )

            Box(
                modifier = Modifier
                    .weight(1f)
                    .height(44.dp)
                    .clip(selectedShape)
                    .testTag("watchlist_category_tab_$index")
                    .selectable(
                        selected = selected,
                        role = Role.Tab,
                        onClick = { onTabSelected(index) }
                    )
                    .semantics(mergeDescendants = true) { this.selected = selected },
                contentAlignment = Alignment.Center
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .graphicsLayer {
                            scaleX = scale
                            scaleY = scale
                        },
                    contentAlignment = Alignment.Center
                ) {
                    if (selected && LocalVisualEffectMode.current == VisualEffectMode.BLUR) {
                        NeumorphicFrostedSurface(
                            modifier = Modifier.fillMaxSize(),
                            isDark = isDark,
                            shape = selectedShape,
                            elevation = 3.dp,
                            blurRadius = 10.dp,
                            backgroundColor = if (isDark) {
                                GlassFillDark
                            } else {
                                Color.White.copy(alpha = 0.60f)
                            },
                            borderColor = primary.copy(alpha = if (isDark) 0.35f else 0.28f)
                        ) {
                            Box(
                                modifier = Modifier.fillMaxSize(),
                                contentAlignment = Alignment.Center
                            ) {
                                CategoryTabContent(tab = tab, selected = true)
                            }
                        }
                    } else if (selected) {
                        GlassTabIndicator(
                            modifier = Modifier.fillMaxSize(),
                            isDark = isDark,
                            shape = selectedShape
                        )
                        CategoryTabContent(tab = tab, selected = true)
                    } else {
                        CategoryTabContent(tab = tab, selected = false)
                    }
                }
            }
        }
    }
}

@Composable
private fun CategoryTabContent(
    tab: WatchlistCategoryTab,
    selected: Boolean
) {
    Row(
        modifier = Modifier.padding(horizontal = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = tab.label,
            fontSize = 15.sp,
            fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium,
            color = if (selected) {
                MaterialTheme.colorScheme.primary
            } else {
                MaterialTheme.colorScheme.onSurfaceVariant
            }
        )
        Surface(
            shape = RoundedCornerShape(50),
            color = if (selected) {
                MaterialTheme.colorScheme.primary.copy(alpha = 0.14f)
            } else {
                MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.10f)
            }
        ) {
            Text(
                text = tab.count.toString(),
                modifier = Modifier.padding(horizontal = 7.dp, vertical = 2.dp),
                fontSize = 13.sp,
                fontWeight = FontWeight.Bold,
                color = if (selected) {
                    MaterialTheme.colorScheme.primary
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant
                }
            )
        }
    }
}
