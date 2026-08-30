package com.tracktosearch.ui.component

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowForwardIos
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.tracktosearch.R

/**
 * 统一「标题 + 查看全部 ›」组件
 *
 * @param title 标题文字
 * @param actionText 右侧动作文字，null 时不显示动作
 * @param onActionClick 动作点击回调，null 时不显示动作
 * @param titleFontSize 标题字号。详情页栏目多、密度高，压到 15sp 才不抢戏
 * @param titleColor 标题颜色，null 走 onBackground。详情页要随沉浸背景自适应，故开放覆盖
 * @param bottomPadding 标题下留白
 */
@Composable
fun SectionHeader(
    title: String,
    actionText: String? = null,
    onActionClick: (() -> Unit)? = null,
    modifier: Modifier = Modifier,
    titleFontSize: TextUnit = 18.sp,
    titleColor: Color? = null,
    bottomPadding: Dp = 10.dp
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(bottom = bottomPadding),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = title,
            fontSize = titleFontSize,
            fontWeight = FontWeight.Bold,
            color = titleColor ?: MaterialTheme.colorScheme.onBackground
        )
        if (actionText != null && onActionClick != null) {
            val actionColor = MaterialTheme.colorScheme.primary
            Row(
                modifier = Modifier
                    .clickable(onClick = onActionClick)
                    .padding(horizontal = 4.dp, vertical = 2.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(2.dp)
            ) {
                Text(
                    text = actionText,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Medium,
                    color = actionColor
                )
                Icon(
                    imageVector = Icons.AutoMirrored.Rounded.ArrowForwardIos,
                    contentDescription = stringResource(R.string.content_desc_view_all),
                    modifier = Modifier.size(12.dp),
                    tint = actionColor
                )
            }
        }
    }
}
