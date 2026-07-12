package com.tracktosearch.ui.component

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.chrisbanes.haze.HazeState
import dev.chrisbanes.haze.hazeEffect
import dev.chrisbanes.haze.materials.ExperimentalHazeMaterialsApi
import dev.chrisbanes.haze.materials.HazeMaterials

/**
 * 毛玻璃胶囊搜索栏
 *
 * @param placeholder 占位提示文字
 * @param typeLabel 搜索类型标签（如「电影」）
 * @param onClick 点击搜索栏回调
 * @param onTypeClick 点击类型标签回调
 * @param hazeState Haze 模糊状态
 */
@OptIn(ExperimentalHazeMaterialsApi::class)
@Composable
fun GlassSearchBar(
    placeholder: String,
    typeLabel: String,
    onClick: () -> Unit,
    onTypeClick: () -> Unit,
    hazeState: HazeState,
    modifier: Modifier = Modifier
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(28.dp))
            .hazeEffect(
                state = hazeState,
                style = HazeMaterials.thin(MaterialTheme.colorScheme.background)
            )
            .background(Color.White.copy(alpha = 0.08f))
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 12.dp)
    ) {
        Icon(
            imageVector = Icons.Rounded.Search,
            contentDescription = null,
            tint = Color.White.copy(alpha = 0.7f)
        )
        Text(
            text = placeholder,
            color = Color.White.copy(alpha = 0.5f),
            fontSize = 14.sp,
            modifier = Modifier
                .weight(1f)
                .padding(horizontal = 10.dp)
        )
        Text(
            text = "$typeLabel ▾",
            color = Color.White.copy(alpha = 0.4f),
            fontSize = 12.sp,
            modifier = Modifier
                .clip(RoundedCornerShape(12.dp))
                .background(Color.White.copy(alpha = 0.06f))
                .clickable(onClick = onTypeClick)
                .padding(horizontal = 8.dp, vertical = 4.dp)
        )
    }
}
