package com.tracktosearch.ui.screen.person

import androidx.compose.animation.ExperimentalSharedTransitionApi
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.OpenInNew
import androidx.compose.material.icons.rounded.Person
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil.compose.SubcomposeAsyncImage
import coil.request.ImageRequest
import com.tracktosearch.R
import com.tracktosearch.ui.component.LocalAnimatedVisibilityScope
import com.tracktosearch.ui.component.SocialMediaIcon
import com.tracktosearch.ui.screen.detail.ExpandableText
import com.tracktosearch.ui.component.appSharedBounds
import com.tracktosearch.ui.component.personAvatarSharedKey

private data class AgeInfo(val age: Int, val isDeceased: Boolean)

/**
 * 人物详情页沉浸背景的头像主色叠加透明度。
 * PersonScreen 用它绘制顶部渐变，PersonHeaderContent 用它反推真实底色以决定前景色，
 * 两处必须共用同一个值，否则文字对比度判断会和实际背景脱节。
 */
internal const val PersonImmersiveTintAlpha = 0.25f

@OptIn(ExperimentalSharedTransitionApi::class)
@Composable
internal fun PersonHeaderContent(
    personId: Int,
    name: String,
    profileUrl: String?,
    birthday: String?,
    deathday: String?,
    placeOfBirth: String?,
    biography: String,
    knownForDepartment: String,
    gender: Int? = null,
    traktGender: String = "",
    traktBiography: String = "",
    traktHomepage: String? = null,
    isLoading: Boolean = false,
    facebookId: String? = null,
    instagramId: String? = null,
    twitterId: String? = null,
    wikipediaUrl: String? = null,
    originalName: String? = null,
    traktPerson: Any? = null,
    isLoadingTrakt: Boolean = true,
    avatarDominantColor: androidx.compose.ui.graphics.Color? = null
) {
    val skeletonColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
    val context = LocalContext.current
    val animatedVisibilityScope = LocalAnimatedVisibilityScope.current

    // 沉浸背景实际是「头像主色按 PersonImmersiveTintAlpha 叠在页面底色上」再向 background 过渡。
    // 直接用主色亮度判断前景色会误判：浅色主题下 25% 的暗主色叠出来的底仍然很亮，却选了白字，
    // 深色主题下亮主色同理会选出黑字。这里先合成出真实底色再判断。
    val immersiveBase = MaterialTheme.colorScheme.background
    val immersiveBackdrop = avatarDominantColor
        ?.copy(alpha = PersonImmersiveTintAlpha)
        ?.compositeOver(immersiveBase)
        ?: immersiveBase
    val backdropIsLight = immersiveBackdrop.luminance() > 0.5f
    val themeIsLight = immersiveBase.luminance() > 0.5f
    // 合成底色与主题明暗一致时沿用主题前景色（配色更统一）；只有主色浓到翻转明暗才降级为黑/白
    val onImmersiveColor = when {
        backdropIsLight == themeIsLight -> MaterialTheme.colorScheme.onSurface
        backdropIsLight -> Color.Black.copy(alpha = 0.92f)
        else -> Color.White
    }
    val onImmersiveVariantColor = when {
        backdropIsLight == themeIsLight -> MaterialTheme.colorScheme.onSurfaceVariant
        backdropIsLight -> Color.Black.copy(alpha = 0.65f)
        else -> Color.White.copy(alpha = 0.72f)
    }

    // 计算年龄数值
    val ageInfo = remember(birthday, deathday) {
        if (birthday != null && birthday.length >= 4) {
            try {
                val birthYear = birthday.substring(0, 4).toInt()
                val birthMonth = if (birthday.length >= 7) birthday.substring(5, 7).toInt() else 1
                val birthDay = if (birthday.length >= 10) birthday.substring(8, 10).toInt() else 1

                if (deathday != null && deathday.length >= 4) {
                    val deathYear = deathday.substring(0, 4).toInt()
                    val deathMonth = if (deathday.length >= 7) deathday.substring(5, 7).toInt() else 12
                    val deathDay = if (deathday.length >= 10) deathday.substring(8, 10).toInt() else 31
                    val age = if (deathMonth > birthMonth || (deathMonth == birthMonth && deathDay >= birthDay)) {
                        deathYear - birthYear
                    } else {
                        deathYear - birthYear - 1
                    }
                    AgeInfo(age, isDeceased = true)
                } else {
                    val now = java.util.Calendar.getInstance()
                    val age = if (now.get(java.util.Calendar.MONTH) + 1 > birthMonth ||
                        (now.get(java.util.Calendar.MONTH) + 1 == birthMonth && now.get(java.util.Calendar.DAY_OF_MONTH) >= birthDay)) {
                        now.get(java.util.Calendar.YEAR) - birthYear
                    } else {
                        now.get(java.util.Calendar.YEAR) - birthYear - 1
                    }
                    AgeInfo(age, isDeceased = false)
                }
            } catch (_: Exception) { null }
        } else null
    }

    // 年龄文本
    val ageText = if (ageInfo != null) {
        if (ageInfo.isDeceased) stringResource(R.string.person_age_deceased, ageInfo.age)
        else "${ageInfo.age}${stringResource(R.string.person_age)}"
    } else null

    // 性别文本：优先用 Trakt（更详细），降级用 TMDB
    val genderText = when {
        traktGender.equals("male", ignoreCase = true) -> stringResource(R.string.person_gender_male)
        traktGender.equals("female", ignoreCase = true) -> stringResource(R.string.person_gender_female)
        traktGender.equals("non_binary", ignoreCase = true) -> stringResource(R.string.person_gender_non_binary)
        gender == 2 -> stringResource(R.string.person_gender_male)
        gender == 1 -> stringResource(R.string.person_gender_female)
        else -> null
    }

    // biography 优先用 TMDB（跟随语言），如果为空则用 Trakt 的
    val displayBiography = if (biography.isNotEmpty()) biography else traktBiography

    // 生卒日期文本
    val lifeText = remember(birthday, deathday) {
        if (birthday != null && birthday.isNotEmpty()) {
            val dateText = if (birthday.length >= 10) {
                "${birthday.substring(0, 4)}-${birthday.substring(5, 7)}-${birthday.substring(8, 10)}"
            } else birthday
            if (deathday != null && deathday.isNotEmpty()) {
                val deathText = if (deathday.length >= 4) deathday.substring(0, 4) else deathday
                val birthYear = if (birthday.length >= 4) birthday.substring(0, 4) else ""
                "$birthYear - $deathText"
            } else {
                dateText
            }
        } else null
    }

    // 元信息聚合
    val metaItems = remember(knownForDepartment, genderText, ageText, lifeText, placeOfBirth) {
        buildList {
            if (knownForDepartment.isNotEmpty()) add(knownForDepartment)
            genderText?.let { add(it) }
            ageText?.let { add(it) }
            lifeText?.let { add(it) }
            if (placeOfBirth != null && placeOfBirth.isNotEmpty()) add(placeOfBirth)
        }
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 16.dp, end = 16.dp, top = 32.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        // 头像
        Surface(
            shape = RoundedCornerShape(14.dp),
            color = MaterialTheme.colorScheme.surfaceVariant,
            modifier = Modifier
                .width(140.dp)
                .height(210.dp)
        ) {
            if (profileUrl != null) {
                val imageModifier = Modifier
                    .appSharedBounds(
                        key = personAvatarSharedKey(personId),
                        animatedVisibilityScope = animatedVisibilityScope,
                    )
                    .fillMaxSize()
                SubcomposeAsyncImage(
                    model = remember(profileUrl) {
                        ImageRequest.Builder(context)
                            .data(profileUrl)
                            .size(200)
                            .crossfade(false)
                            .build()
                    },
                    contentDescription = name,
                    contentScale = ContentScale.Crop,
                    modifier = imageModifier,
                    loading = {
                        Box(contentAlignment = Alignment.Center, modifier = Modifier.fillMaxSize()) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(20.dp),
                                strokeWidth = 2.dp
                            )
                        }
                    },
                    error = {
                        Box(contentAlignment = Alignment.Center, modifier = Modifier.fillMaxSize()) {
                            Icon(
                                Icons.Rounded.Person,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.size(28.dp)
                            )
                        }
                    }
                )
            } else {
                Box(contentAlignment = Alignment.Center, modifier = Modifier.fillMaxSize()) {
                    Icon(
                        Icons.Rounded.Person,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(28.dp)
                    )
                }
            }
        }

        Spacer(modifier = Modifier.height(16.dp))

        // 姓名
        if (isLoading && name.isBlank()) {
            Box(
                modifier = Modifier
                    .fillMaxWidth(0.55f)
                    .height(32.dp)
                    .clip(RoundedCornerShape(4.dp))
                    .background(skeletonColor)
            )
        } else {
            Text(
                text = name,
                style = MaterialTheme.typography.headlineMedium,
                fontWeight = FontWeight.Bold,
                color = onImmersiveColor,
                textAlign = TextAlign.Center,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.fillMaxWidth()
            )
        }

        // 原名
        if (originalName == null && isLoading) {
            Box(
                modifier = Modifier
                    .padding(top = 6.dp)
                    .fillMaxWidth(0.4f)
                    .height(16.dp)
                    .clip(RoundedCornerShape(4.dp))
                    .background(skeletonColor)
            )
        } else if (!originalName.isNullOrEmpty() && originalName != name) {
            Text(
                text = originalName,
                style = MaterialTheme.typography.bodySmall,
                color = onImmersiveVariantColor,
                textAlign = TextAlign.Center,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier
                    .padding(top = 4.dp)
                    .fillMaxWidth()
            )
        }

        Spacer(modifier = Modifier.height(8.dp))

        // 元信息
        if (isLoading && metaItems.isEmpty()) {
            Column(
                verticalArrangement = Arrangement.spacedBy(6.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth(0.7f)
                        .height(14.dp)
                        .clip(RoundedCornerShape(4.dp))
                        .background(skeletonColor)
                )
                Box(
                    modifier = Modifier
                        .fillMaxWidth(0.5f)
                        .height(14.dp)
                        .clip(RoundedCornerShape(4.dp))
                        .background(skeletonColor)
                )
            }
        } else if (metaItems.isNotEmpty()) {
            Text(
                text = metaItems.joinToString(" · "),
                style = MaterialTheme.typography.bodySmall,
                color = onImmersiveVariantColor,
                textAlign = TextAlign.Center,
                maxLines = 3,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.fillMaxWidth()
            )
        }

        // 主页链接 + 维基百科链接
        if (isLoadingTrakt && traktPerson == null) {
            Box(
                modifier = Modifier
                    .padding(top = 8.dp)
                    .fillMaxWidth(0.5f)
                    .height(14.dp)
                    .clip(RoundedCornerShape(4.dp))
                    .background(skeletonColor)
            )
        } else if (!traktHomepage.isNullOrEmpty() || !wikipediaUrl.isNullOrEmpty()) {
            Row(
                modifier = Modifier
                    .padding(top = 8.dp)
                    .fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(20.dp, Alignment.CenterHorizontally),
                verticalAlignment = Alignment.CenterVertically
            ) {
                if (!traktHomepage.isNullOrEmpty()) {
                    val homepageLabel = stringResource(R.string.person_homepage)
                    Row(
                        modifier = Modifier
                            .clickable(
                                interactionSource = remember { MutableInteractionSource() },
                                indication = null
                            ) {
                                try {
                                    val intent = android.content.Intent(android.content.Intent.ACTION_VIEW, android.net.Uri.parse(traktHomepage))
                                    context.startActivity(intent)
                                } catch (_: Exception) { }
                            },
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Rounded.OpenInNew,
                            contentDescription = homepageLabel,
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(16.dp)
                        )
                        Text(
                            text = homepageLabel,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.primary
                        )
                    }
                }
                if (!wikipediaUrl.isNullOrEmpty()) {
                    Row(
                        modifier = Modifier
                            .clickable(
                                interactionSource = remember { MutableInteractionSource() },
                                indication = null
                            ) {
                                try {
                                    val url = if (wikipediaUrl.startsWith("http")) {
                                        wikipediaUrl
                                    } else {
                                        "https://en.wikipedia.org/wiki/${wikipediaUrl.replace(" ", "_")}"
                                    }
                                    val intent = android.content.Intent(android.content.Intent.ACTION_VIEW, android.net.Uri.parse(url))
                                    context.startActivity(intent)
                                } catch (_: Exception) { }
                            },
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        Icon(
                            painter = painterResource(id = R.drawable.ic_wikipedia),
                            contentDescription = "Wikipedia",
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(16.dp)
                        )
                        Text(
                            text = "Wikipedia",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.primary
                        )
                    }
                }
            }
        }

        // 社交媒体图标
        if (isLoadingTrakt && traktPerson == null) {
            Row(
                modifier = Modifier.padding(top = 10.dp),
                horizontalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                Box(
                    modifier = Modifier
                        .width(80.dp)
                        .height(16.dp)
                        .clip(RoundedCornerShape(4.dp))
                        .background(skeletonColor)
                )
                Box(
                    modifier = Modifier
                        .width(90.dp)
                        .height(16.dp)
                        .clip(RoundedCornerShape(4.dp))
                        .background(skeletonColor)
                )
            }
        } else if (!facebookId.isNullOrEmpty() || !instagramId.isNullOrEmpty() || !twitterId.isNullOrEmpty()) {
            Row(
                modifier = Modifier.padding(top = 10.dp),
                horizontalArrangement = Arrangement.spacedBy(16.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                if (!facebookId.isNullOrEmpty()) {
                    SocialMediaIcon(
                        iconRes = R.drawable.ic_facebook,
                        label = facebookId,
                        tint = Color(0xFF1877F2),
                        labelColor = Color(0xFF1877F2),
                        modifier = Modifier.weight(1f, fill = false),
                        onClick = {
                            try {
                                val intent = android.content.Intent(android.content.Intent.ACTION_VIEW, android.net.Uri.parse("https://facebook.com/$facebookId"))
                                context.startActivity(intent)
                            } catch (_: Exception) { }
                        }
                    )
                }
                if (!instagramId.isNullOrEmpty()) {
                    SocialMediaIcon(
                        iconRes = R.drawable.ic_instagram,
                        label = instagramId,
                        tint = Color(0xFFE4405F),
                        labelColor = Color(0xFFE4405F),
                        modifier = Modifier.weight(1f, fill = false),
                        onClick = {
                            try {
                                val intent = android.content.Intent(android.content.Intent.ACTION_VIEW, android.net.Uri.parse("https://instagram.com/$instagramId"))
                                context.startActivity(intent)
                            } catch (_: Exception) { }
                        }
                    )
                }
                if (!twitterId.isNullOrEmpty()) {
                    SocialMediaIcon(
                        iconRes = R.drawable.ic_x_twitter,
                        label = twitterId,
                        tint = MaterialTheme.colorScheme.onSurface,
                        labelColor = MaterialTheme.colorScheme.onSurface,
                        modifier = Modifier.weight(1f, fill = false),
                        onClick = {
                            try {
                                val intent = android.content.Intent(android.content.Intent.ACTION_VIEW, android.net.Uri.parse("https://x.com/$twitterId"))
                                context.startActivity(intent)
                            } catch (_: Exception) { }
                        }
                    )
                }
            }
        }

        Spacer(modifier = Modifier.height(14.dp))

        // 简介卡片
        // 填充用不透明 surfaceVariant：半透明填充叠在沉浸渐变上会被底色吃掉，卡片轮廓看不出来。
        // 再补一条 outlineVariant 描边，保证浅色主题下卡片与页面底色差异很小时仍有明确边界。
        // 卡片内骨架条：卡片已是不透明 surfaceVariant，沿用外层 skeletonColor 会与卡片同色而看不见
        val biographySkeletonColor = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.15f)
        Surface(
            shape = RoundedCornerShape(20.dp),
            color = MaterialTheme.colorScheme.surfaceVariant,
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                Text(
                    text = stringResource(R.string.detail_overview_label),
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurface,
                    // 标题水平居中，正文仍左对齐
                    modifier = Modifier.align(Alignment.CenterHorizontally)
                )
                Spacer(modifier = Modifier.height(8.dp))
                if (displayBiography.isNotEmpty()) {
                    // 简介正文：折叠为 4 行，溢出时在下方独立一行显示右对齐的「展开/收起」
                    ExpandableText(
                        text = displayBiography,
                        maxLines = 4
                    )
                } else if (isLoadingTrakt) {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        repeat(4) { index ->
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth(
                                        when (index) {
                                            0 -> 1f
                                            1 -> 0.95f
                                            2 -> 0.9f
                                            else -> 0.7f
                                        }
                                    )
                                    .height(16.dp)
                                    .clip(RoundedCornerShape(4.dp))
                                    .background(biographySkeletonColor)
                            )
                        }
                    }
                } else {
                    Text(
                        text = stringResource(R.string.person_no_biography),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
    }
}
