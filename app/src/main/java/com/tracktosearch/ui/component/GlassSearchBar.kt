@file:OptIn(dev.chrisbanes.haze.ExperimentalHazeApi::class)

package com.tracktosearch.ui.component

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.tracktosearch.ui.theme.LocalGlassVariant
import com.tracktosearch.ui.theme.LocalVisualEffectMode
import com.tracktosearch.ui.theme.VisualEffectMode
import dev.chrisbanes.haze.HazeInput
import dev.chrisbanes.haze.HazePerformanceMode
import dev.chrisbanes.haze.HazeState
import dev.chrisbanes.haze.blur.materials.HazeMaterials

/**
 * 搜索栏底色决策：Blur 模式不叠玻璃 tint，必须保留底色保证可读性；
 * Glass 模式在 haze 生效时把底色交给玻璃 tint，避免双重底色。
 */
internal fun searchBarBaseColor(
    mode: VisualEffectMode,
    hazeActive: Boolean,
    fallbackColor: Color
): Color {
    return if (!hazeActive || mode == VisualEffectMode.BLUR) fallbackColor else Color.Transparent
}

