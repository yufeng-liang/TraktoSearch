package com.tracktosearch.ui.component

import androidx.compose.foundation.Image
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import com.tracktosearch.R

/** 复用官网当前的 Trakt 紫色方标，不随莫奈主题切换。 */
@Composable
fun TraktLogo(
    contentDescription: String?,
    modifier: Modifier = Modifier
) {
    Image(
        painter = painterResource(R.drawable.ic_trakt_logo),
        contentDescription = contentDescription,
        contentScale = ContentScale.Fit,
        modifier = modifier
    )
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
        contentScale = ContentScale.Fit,
        modifier = modifier
    )
}
