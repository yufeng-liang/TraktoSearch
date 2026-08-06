package com.tracktosearch.ui.component

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.tracktosearch.R
import com.tracktosearch.ui.theme.Red500

/** Trakt 品牌标记，固定使用 Trakt 品牌红，不随莫奈主题切换。 */
@Composable
fun TraktLogo(
    contentDescription: String?,
    modifier: Modifier = Modifier
) {
    val semanticModifier = if (contentDescription == null) {
        modifier
    } else {
        modifier.semantics { this.contentDescription = contentDescription }
    }
    Box(
        modifier = semanticModifier
            .clip(RoundedCornerShape(6.dp))
            .background(Red500),
        contentAlignment = Alignment.Center
    ) {
        Image(
            painter = painterResource(R.drawable.ic_trakt_logo),
            contentDescription = null,
            colorFilter = ColorFilter.tint(Color.White),
            modifier = Modifier.size(18.dp)
        )
    }
}

/** 豆瓣品牌标记，复用详情页评分使用的资源。 */
@Composable
fun DoubanLogo(
    contentDescription: String?,
    modifier: Modifier = Modifier
) {
    Image(
        painter = painterResource(R.drawable.ic_douban_logo),
        contentDescription = contentDescription,
        modifier = modifier
    )
}
