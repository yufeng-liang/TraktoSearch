package com.tracktosearch.ui.screen.person

import androidx.compose.animation.ExperimentalSharedTransitionApi
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
import androidx.compose.material.icons.filled.Person
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil.compose.SubcomposeAsyncImage
import coil.request.ImageRequest
import com.tracktosearch.R
import com.tracktosearch.ui.component.LocalAnimatedVisibilityScope
import com.tracktosearch.ui.component.LocalSharedTransitionScope
import com.tracktosearch.ui.component.SocialMediaIcon

private data class AgeInfo(val age: Int, val isDeceased: Boolean)

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
    isLoadingTrakt: Boolean = true
) {
    val skeletonColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
    val context = LocalContext.current
    val sharedTransitionScope = LocalSharedTransitionScope.current
    val animatedVisibilityScope = LocalAnimatedVisibilityScope.current
    var showFullBio by remember { mutableStateOf(false) }

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

    Column(modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 48.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            // 头像
            Surface(
                shape = RoundedCornerShape(8.dp),
                color = MaterialTheme.colorScheme.surfaceVariant,
                modifier = Modifier
                    .width(120.dp)
                    .height(180.dp)
            ) {
                if (profileUrl != null) {
                    val imageModifier = if (sharedTransitionScope != null && animatedVisibilityScope != null) {
                        with(sharedTransitionScope) {
                            Modifier
                                .sharedElement(
                                    rememberSharedContentState(key = "person-avatar-$personId"),
                                    animatedVisibilityScope = animatedVisibilityScope
                                )
                                .fillMaxSize()
                        }
                    } else {
                        Modifier.fillMaxSize()
                    }
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
                                    Icons.Filled.Person,
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
                            Icons.Filled.Person,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(28.dp)
                        )
                    }
                }
            }

            // 右侧信息
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                if (isLoading && name.isBlank()) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth(0.7f)
                            .height(24.dp)
                            .clip(RoundedCornerShape(4.dp))
                            .background(skeletonColor)
                    )
                } else {
                    Text(
                        text = name,
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                }
                // 原名显示
                if (originalName == null && isLoading) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth(0.5f)
                            .height(14.dp)
                            .clip(RoundedCornerShape(4.dp))
                            .background(skeletonColor)
                    )
                } else if (!originalName.isNullOrEmpty() && originalName != name) {
                    Text(
                        text = originalName,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                // 社交媒体图标
                if (isLoadingTrakt && traktPerson == null) {
                    // Trakt 数据未加载时显示单行骨架屏，减少加载后高度跳变
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(12.dp)
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
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(12.dp)
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
                // 主页链接 + 维基百科链接
                if (isLoadingTrakt && traktPerson == null) {
                    // Trakt 数据未加载时显示骨架屏
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth(0.4f)
                                .height(14.dp)
                                .clip(RoundedCornerShape(4.dp))
                                .background(skeletonColor)
                        )
                        Box(
                            modifier = Modifier
                                .width(70.dp)
                                .height(14.dp)
                                .clip(RoundedCornerShape(4.dp))
                                .background(skeletonColor)
                        )
                    }
                } else if (!traktHomepage.isNullOrEmpty() || !wikipediaUrl.isNullOrEmpty()) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        if (!traktHomepage.isNullOrEmpty()) {
                            Text(
                                text = traktHomepage,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.primary,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier
                                    .weight(1f)
                                    .clickable(
                                        interactionSource = remember { MutableInteractionSource() },
                                        indication = null
                                    ) {
                                        try {
                                            val intent = android.content.Intent(android.content.Intent.ACTION_VIEW, android.net.Uri.parse(traktHomepage))
                                            context.startActivity(intent)
                                        } catch (_: Exception) { }
                                    }
                            )
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
                                    tint = Color(0xFF636466),
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
                if (isLoading && knownForDepartment.isBlank()) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth(0.5f)
                            .height(16.dp)
                            .clip(RoundedCornerShape(4.dp))
                            .background(skeletonColor)
                    )
                } else if (knownForDepartment.isNotEmpty()) {
                    Text(
                        text = knownForDepartment,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                // 性别显示
                if (genderText != null) {
                    Text(
                        text = genderText,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                if (isLoading && birthday.isNullOrEmpty()) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth(0.6f)
                            .height(16.dp)
                            .clip(RoundedCornerShape(4.dp))
                            .background(skeletonColor)
                    )
                } else if (birthday != null && birthday.isNotEmpty()) {
                    val dateText = if (birthday.length >= 10) {
                        "${birthday.substring(0, 4)}-${birthday.substring(5, 7)}-${birthday.substring(8, 10)}"
                    } else birthday
                    val lifeText = if (deathday != null && deathday.isNotEmpty()) {
                        val deathText = if (deathday.length >= 4) deathday.substring(0, 4) else deathday
                        val birthYear = if (birthday.length >= 4) birthday.substring(0, 4) else ""
                        "$birthYear - $deathText"
                    } else {
                        dateText
                    }
                    // 日期 + 年龄
                    val displayText = if (ageText != null) "$lifeText · $ageText" else lifeText
                    Text(
                        text = displayText,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                if (isLoading && placeOfBirth.isNullOrEmpty()) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth(0.5f)
                            .height(16.dp)
                            .clip(RoundedCornerShape(4.dp))
                            .background(skeletonColor)
                    )
                } else if (placeOfBirth != null && placeOfBirth.isNotEmpty()) {
                    Text(
                        text = placeOfBirth,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 3,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
        }

        // 简介
        Spacer(modifier = Modifier.height(12.dp))
        Text(
            text = stringResource(R.string.detail_overview_label),
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.onSurface
        )
        Spacer(modifier = Modifier.height(4.dp))
        if (displayBiography.isNotEmpty()) {
            Text(
                text = displayBiography,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = if (showFullBio) Int.MAX_VALUE else 4,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null
                    ) { showFullBio = !showFullBio }
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
                            .background(skeletonColor)
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
