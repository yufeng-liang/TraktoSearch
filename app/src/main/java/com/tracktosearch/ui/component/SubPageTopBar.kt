package com.tracktosearch.ui.component

import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarColors
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import com.tracktosearch.R

/**
 * 二级子页面统一标题栏。
 *
 * 统一三项：标题字号（titleLarge）、字重（ExtraBold）、返回箭头 tint（primary）。
 * 只封装 M3 [TopAppBar] 这一流派；带搜索槽/毛玻璃吸顶/共享元素的自定义 Row 标题栏结构差异过大，不走此组件。
 *
 * 毛玻璃吸顶页外层已用 Spacer(statusBarsPadding) 让出状态栏，故 [windowInsets] 默认清零、
 * [colors] 默认透明容器（底色交给外层 haze）。非毛玻璃页（如 Scaffold topBar、覆盖式顶栏）
 * 按需覆盖 [colors]/[windowInsets] 保留原有容器色与状态栏避让。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SubPageTopBar(
    title: String,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    backEnabled: Boolean = true,
    backContentDescription: String = stringResource(R.string.content_desc_back),
    colors: TopAppBarColors = TopAppBarDefaults.topAppBarColors(containerColor = Color.Transparent),
    windowInsets: WindowInsets = WindowInsets(0, 0, 0, 0),
    actions: @Composable RowScope.() -> Unit = {},
) {
    TopAppBar(
        modifier = modifier,
        title = {
            Text(
                text = title,
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.ExtraBold,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        },
        navigationIcon = {
            IconButton(onClick = onBack, enabled = backEnabled) {
                Icon(
                    imageVector = Icons.AutoMirrored.Rounded.ArrowBack,
                    contentDescription = backContentDescription,
                    tint = MaterialTheme.colorScheme.primary,
                )
            }
        },
        actions = actions,
        colors = colors,
        windowInsets = windowInsets,
    )
}
