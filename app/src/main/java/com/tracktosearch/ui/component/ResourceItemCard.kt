package com.tracktosearch.ui.component

import androidx.annotation.DrawableRes
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.tracktosearch.data.remote.dto.DiskType
import com.tracktosearch.data.remote.dto.ResourceItem
import com.tracktosearch.data.repository.ResourceLinkChecker
import com.tracktosearch.R
import com.tracktosearch.ui.haptic.HapticSemantic
import com.tracktosearch.ui.haptic.hapticCombinedClickable
import com.tracktosearch.ui.theme.*
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.delay
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

data class DiskStyle(
    val shortNameResId: Int,
    val backgroundColor: Color,
    @DrawableRes val iconRes: Int = 0
)

internal data class SourceTagColors(
    val background: Color,
    val content: Color
)

// ====== 来源标签色板 ======
// **不跟随强调色**：标签的作用是让同一条结果列表里不同来源一眼分得开，跟随强调色的话
// 所有标签会变成同一色系。和 StatisticsPalette / DiscoverPalette 同一个取舍。
//
// 每个色都是「底色 + 配套文字色」成对给的，明暗两档各一套。改色时两个一起改并确认
// 配对对比度 ≥ 4.5:1 —— 标签文字很小，现有 14 对最差的一对是 5.09:1，别把余量吃掉。
// 三个已知来源各自固定一对，其余来源按名字 hash 落到 4 组通用配色上。

private val lightCustomSourceTagColors = listOf(
    SourceTagColors(Color(0xFFD8C7A3), Color(0xFF5A4B2E)),
    SourceTagColors(Color(0xFFD2C4D0), Color(0xFF534352)),
    SourceTagColors(Color(0xFFB9D0CF), Color(0xFF2F4D4C)),
    SourceTagColors(Color(0xFFD8BFC5), Color(0xFF5A3942))
)

private val darkCustomSourceTagColors = listOf(
    SourceTagColors(Color(0xFF695A3D), Color(0xFFF0E2C1)),
    SourceTagColors(Color(0xFF604B5F), Color(0xFFEBDCE9)),
    SourceTagColors(Color(0xFF3F605F), Color(0xFFD9ECEB)),
    SourceTagColors(Color(0xFF6E4650), Color(0xFFF1D9DF))
)

internal fun sourceTagColors(source: String, isDark: Boolean): SourceTagColors {
    return when (source) {
        "pansou" -> SourceTagColors(
            background = if (isDark) Color(0xFF6F453A) else Color(0xFFE8C8B8),
            content = if (isDark) Color(0xFFFFE5D9) else Color(0xFF5D4036)
        )
        "panhub" -> SourceTagColors(
            background = if (isDark) Color(0xFF405747) else Color(0xFFC9D4C5),
            content = if (isDark) Color(0xFFDEEDDF) else Color(0xFF314738)
        )
        "zreso" -> SourceTagColors(
            background = if (isDark) Color(0xFF43566B) else Color(0xFFC7D2DF),
            content = if (isDark) Color(0xFFDDE8F4) else Color(0xFF33465A)
        )
        "useai" -> SourceTagColors(
            background = if (isDark) Color(0xFF5F5334) else Color(0xFFE4D8B8),
            content = if (isDark) Color(0xFFEDE1C2) else Color(0xFF4F4324)
        )
        "yunso" -> SourceTagColors(
            background = if (isDark) Color(0xFF36544E) else Color(0xFFBFD9D3),
            content = if (isDark) Color(0xFFD5EDE7) else Color(0xFF2C4A45)
        )
        "ataw" -> SourceTagColors(
            background = if (isDark) Color(0xFF4C3B5C) else Color(0xFFD9CBE4),
            content = if (isDark) Color(0xFFE5D8F2) else Color(0xFF463256)
        )
        "melost" -> SourceTagColors(
            background = if (isDark) Color(0xFF33565C) else Color(0xFFC5DEE2),
            content = if (isDark) Color(0xFFD2ECEF) else Color(0xFF2E474C)
        )
        "panxiaozi" -> SourceTagColors(
            background = if (isDark) Color(0xFF5C4A33) else Color(0xFFE8D8B8),
            content = if (isDark) Color(0xFFEFE1C2) else Color(0xFF4F3F1E)
        )
        "baoys" -> SourceTagColors(
            background = if (isDark) Color(0xFF5C3344) else Color(0xFFEBC9D6),
            content = if (isDark) Color(0xFFF2D8E2) else Color(0xFF4E2635)
        )
        else -> {
            val paletteIndex = Math.floorMod(source.hashCode(), lightCustomSourceTagColors.size)
            if (isDark) darkCustomSourceTagColors[paletteIndex] else lightCustomSourceTagColors[paletteIndex]
        }
    }
}

internal fun sourceTagLabel(source: String, customName: String?): String {
    return when (source) {
        "pansou" -> "PanSou"
        "panhub" -> "PanHub"
        "zreso" -> "Zreso"
        "useai" -> "盘搜"
        "yunso" -> "云搜"
        "ataw" -> "阿涛搜"
        "melost" -> "影盘"
        "panxiaozi" -> "盘小子"
        "baoys" -> "宝藏郎"
        else -> customName ?: source
    }
}

internal fun resourceCardGradientColors(
    diskColor: Color,
    sourceColors: SourceTagColors,
    isDark: Boolean
): List<Color> {
    return listOf(
        diskColor.copy(alpha = if (isDark) 0.20f else 0.12f),
        diskColor.copy(alpha = if (isDark) 0.06f else 0.04f),
        sourceColors.background.copy(alpha = if (isDark) 0.24f else 0.16f)
    )
}

fun diskStyleOf(type: DiskType): DiskStyle = when (type) {
    DiskType.QUARK -> DiskStyle(R.string.disk_quark, QuarkBlue, R.drawable.ic_disk_quark)
    DiskType.BAIDU -> DiskStyle(R.string.disk_baidu, BaiduBlue, R.drawable.ic_disk_baidu)
    DiskType.ALI -> DiskStyle(R.string.disk_ali, AliIndigo, R.drawable.ic_disk_ali)
    DiskType.XUNLEI -> DiskStyle(R.string.disk_xunlei, XunleiBlue, R.drawable.ic_disk_xunlei)
    DiskType.UC -> DiskStyle(R.string.disk_uc, UcOrange, R.drawable.ic_disk_uc)
    DiskType.ONEONEFIVE -> DiskStyle(R.string.disk_115, Blue115, R.drawable.ic_disk_115)
    DiskType.MAGNET -> DiskStyle(R.string.disk_magnet, Color(0xFFD44000))
    DiskType.OTHER -> DiskStyle(R.string.disk_other, Color(0xFF8E8E93))
}

private val DATE_FORMATTER = DateTimeFormatter.ofPattern("yyyy-M-d HH:mm")

private fun formatFileDate(raw: String): String {
    return try {
        // 尝试解析 ISO 格式 (如 2026-06-13T15:15:09+08:00)
        LocalDateTime.parse(raw.take(19)).format(DATE_FORMATTER)
    } catch (_: Exception) {
        raw
    }
}

// ====== 链接有效性徽章 ======

@EntryPoint
@InstallIn(SingletonComponent::class)
interface ResourceLinkCheckerProvider {
    fun resourceLinkChecker(): ResourceLinkChecker
}

@Composable
private fun rememberResourceLinkChecker(): ResourceLinkChecker {
    val context = LocalContext.current
    return remember(context) {
        EntryPointAccessors.fromApplication(
            context.applicationContext,
            ResourceLinkCheckerProvider::class.java
        ).resourceLinkChecker()
    }
}

/** zreso/ataw 等源在搜索结果里自带的失效标记，直接转成统一的展示状态。 */
private fun statusFromItem(item: ResourceItem): ResourceLinkChecker.LinkStatus? {
    return when (item.status.lowercase()) {
        "ok", "valid" -> ResourceLinkChecker.LinkStatus(ResourceLinkChecker.State.VALID)
        "bad", "dead", "invalid", "expired", "fail", "deleted" ->
            ResourceLinkChecker.LinkStatus(ResourceLinkChecker.State.INVALID)
        "locked" -> ResourceLinkChecker.LinkStatus(ResourceLinkChecker.State.LOCKED)
        "uncertain" -> ResourceLinkChecker.LinkStatus(ResourceLinkChecker.State.UNCERTAIN)
        else -> null
    }
}

private data class LinkBadgeColors(val background: Color, val content: Color)

@Composable
private fun linkBadgeColors(state: ResourceLinkChecker.State): LinkBadgeColors {
    val isDark = MaterialTheme.colorScheme.background.luminance() <= 0.5f
    return when (state) {
        ResourceLinkChecker.State.VALID -> if (isDark) {
            LinkBadgeColors(Color(0xFF2C4A32), Color(0xFFBFE3C2))
        } else {
            LinkBadgeColors(Color(0xFFDCEEDC), Color(0xFF2F6338))
        }
        ResourceLinkChecker.State.INVALID -> if (isDark) {
            LinkBadgeColors(Color(0xFF4A2F2F), Color(0xFFF0C9C9))
        } else {
            LinkBadgeColors(Color(0xFFF0DADA), Color(0xFF8A3030))
        }
        ResourceLinkChecker.State.LOCKED -> if (isDark) {
            LinkBadgeColors(Color(0xFF4A3F26), Color(0xFFF0E2B8))
        } else {
            LinkBadgeColors(Color(0xFFF4E8C8), Color(0xFF6B5520))
        }
        ResourceLinkChecker.State.UNCERTAIN, ResourceLinkChecker.State.CHECKING -> if (isDark) {
            LinkBadgeColors(Color(0xFF3A3733), Color(0xFFD9D3CA))
        } else {
            LinkBadgeColors(Color(0xFFE9E5DF), Color(0xFF5F5A52))
        }
        ResourceLinkChecker.State.UNSUPPORTED, ResourceLinkChecker.State.UNKNOWN -> if (isDark) {
            LinkBadgeColors(Color(0xFF3A3733), Color(0xFFBDB7AE))
        } else {
            LinkBadgeColors(Color(0xFFEDEAE5), Color(0xFF7A746B))
        }
    }
}

@Composable
private fun linkStatusLabel(state: ResourceLinkChecker.State): String = when (state) {
    ResourceLinkChecker.State.CHECKING -> stringResource(R.string.resource_link_checking)
    ResourceLinkChecker.State.VALID -> stringResource(R.string.resource_link_valid)
    ResourceLinkChecker.State.INVALID -> stringResource(R.string.resource_link_invalid)
    ResourceLinkChecker.State.LOCKED -> stringResource(R.string.resource_link_locked)
    ResourceLinkChecker.State.UNCERTAIN -> stringResource(R.string.resource_link_uncertain)
    ResourceLinkChecker.State.UNSUPPORTED -> stringResource(R.string.resource_link_unsupported)
    ResourceLinkChecker.State.UNKNOWN -> stringResource(R.string.resource_link_unknown)
}

@Composable
private fun LinkStatusBadge(state: ResourceLinkChecker.State) {
    val colors = linkBadgeColors(state)
    Surface(
        shape = RoundedCornerShape(6.dp),
        color = colors.background,
        modifier = Modifier.height(24.dp)
    ) {
        Row(
            modifier = Modifier
                .padding(horizontal = 7.dp)
                .fillMaxHeight(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            Box(
                modifier = Modifier
                    .size(6.dp)
                    .clip(CircleShape)
                    .background(colors.content)
            )
            Text(
                text = linkStatusLabel(state),
                style = MaterialTheme.typography.labelSmall.copy(
                    fontWeight = FontWeight.Medium,
                    fontSize = 10.5.sp,
                    color = colors.content
                ),
                maxLines = 1,
                softWrap = false
            )
        }
    }
}

// ====== 卡片基础组件 ======

@Composable
private fun IndexBadge(index: Int) {
    Surface(
        shape = RoundedCornerShape(6.dp),
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.7f)
    ) {
        Box(
            modifier = Modifier
                .height(24.dp)
                .widthIn(min = 24.dp)
                .padding(horizontal = 5.dp),
            contentAlignment = Alignment.Center
        ) {
            Text(
                text = "${index + 1}",
                style = MaterialTheme.typography.labelSmall.copy(
                    fontWeight = FontWeight.Medium,
                    fontSize = 10.5.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.75f)
                ),
                maxLines = 1,
                softWrap = false
            )
        }
    }
}

@Composable
private fun MetaBadge(
    background: Color,
    contentColor: Color,
    text: String,
    @DrawableRes iconRes: Int = 0,
    maxTextWidth: Dp = 72.dp
) {
    Surface(
        shape = RoundedCornerShape(6.dp),
        color = background,
        modifier = Modifier.height(24.dp)
    ) {
        Row(
            modifier = Modifier
                .padding(horizontal = 7.dp)
                .fillMaxHeight(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            if (iconRes != 0) {
                Image(
                    painter = painterResource(id = iconRes),
                    contentDescription = null,
                    contentScale = ContentScale.Fit,
                    modifier = Modifier.size(12.dp)
                )
            }
            AdaptiveSingleLineText(
                text = text,
                style = MaterialTheme.typography.labelSmall.copy(
                    fontWeight = FontWeight.Bold,
                    fontSize = 10.5.sp,
                    color = contentColor
                ),
                maxFontSize = 10.5.sp,
                minFontSize = 8.5.sp,
                modifier = Modifier.widthIn(max = maxTextWidth)
            )
        }
    }
}

@Composable
private fun ViewedBadge() {
    Surface(
        shape = RoundedCornerShape(6.dp),
        color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.45f)
    ) {
        Text(
            text = stringResource(R.string.resource_viewed),
            style = MaterialTheme.typography.labelSmall.copy(
                fontSize = 10.sp,
                color = MaterialTheme.colorScheme.outline
            ),
            modifier = Modifier.padding(horizontal = 7.dp, vertical = 3.dp),
            maxLines = 1,
            softWrap = false
        )
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun ResourceItemCard(
    item: ResourceItem,
    sourceName: String? = null,
    isViewed: Boolean = false,
    onClick: () -> Unit,
    onLongClick: (() -> Unit)? = null,
    index: Int = 0,
    modifier: Modifier = Modifier
) {
    val style = diskStyleOf(item.diskType)
    val contentAlpha = if (isViewed) 0.5f else 1f
    val isLight = MaterialTheme.colorScheme.background.luminance() > 0.5f
    val isDark = !isLight
    val sourceColors = sourceTagColors(item.source, isDark = isDark)
    val gradientColors = resourceCardGradientColors(style.backgroundColor, sourceColors, isDark = isDark)
    val interactionSource = remember { MutableInteractionSource() }
    val isPressed by interactionSource.collectIsPressedAsState()
    val scale by animateFloatAsState(
        targetValue = if (isPressed) 0.96f else 1f,
        label = "resource_card_scale"
    )

    val linkChecker = rememberResourceLinkChecker()
    val initialStatus = remember(item.url, item.status) { statusFromItem(item) }
    // 只有 PanSou 系（pansou/useai）能在线检测；其余源没有公开检测接口，保持无徽章。
    // 复用已检测结果：列表滚动回来时直接显示缓存徽章，不再闪一次空态。
    var linkStatus by remember(item.url) {
        mutableStateOf(initialStatus ?: linkChecker.cachedStatus(item.url))
    }
    LaunchedEffect(item.url, item.source, item.status) {
        if (linkStatus == null && linkChecker.supports(item)) {
            // 稍等一拍再检测：快速划过的卡片不产生无谓请求
            delay(450)
            linkStatus = ResourceLinkChecker.LinkStatus(ResourceLinkChecker.State.CHECKING)
            val result = linkChecker.check(item)
            // 检测失败（网络/站点不支持）不展示徽章，避免给所有卡片挂个「未知」
            linkStatus = result.takeIf {
                it.state != ResourceLinkChecker.State.UNKNOWN &&
                    it.state != ResourceLinkChecker.State.UNSUPPORTED
            }
        }
    }

    Card(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 5.dp)
            .scale(scale)
            .hapticCombinedClickable(
                interactionSource = interactionSource,
                indication = null,
                semantic = HapticSemantic.LIGHT_TAP,
                onClick = onClick,
                onLongClick = onLongClick
            ),
        shape = RoundedCornerShape(18.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surface
        ),
        border = BorderStroke(
            width = 1.dp,
            color = style.backgroundColor.copy(alpha = if (isDark) 0.28f else 0.14f)
        ),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp)
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .background(
                    Brush.horizontalGradient(colors = gradientColors)
                )
                .padding(start = 14.dp, end = 14.dp, top = 11.dp, bottom = 10.dp)
                .alpha(contentAlpha)
        ) {
            // 左侧品牌色强调条
            Box(
                modifier = Modifier
                    .align(Alignment.CenterStart)
                    .width(3.dp)
                    .height(44.dp)
                    .clip(RoundedCornerShape(2.dp))
                    .background(style.backgroundColor.copy(alpha = 0.9f))
            )

            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = 10.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    IndexBadge(index = index)

                    // 网盘类型徽章：与来源徽章统一 24dp 高、6dp 圆角、7dp 横向内边距
                    MetaBadge(
                        background = style.backgroundColor,
                        contentColor = Color.White,
                        text = stringResource(style.shortNameResId),
                        iconRes = style.iconRes,
                        maxTextWidth = 64.dp
                    )

                    // 来源徽章
                    MetaBadge(
                        background = sourceColors.background,
                        contentColor = sourceColors.content,
                        text = sourceTagLabel(item.source, sourceName),
                        maxTextWidth = 72.dp
                    )

                    if (linkStatus != null) {
                        LinkStatusBadge(state = linkStatus!!.state)
                    }
                }

                // 资源名称
                AdaptiveTwoLineTitle(
                    text = item.name,
                    style = MaterialTheme.typography.bodyLarge.copy(
                        fontWeight = FontWeight.SemiBold,
                        fontSize = 14.sp,
                        color = MaterialTheme.colorScheme.onSurface
                    ),
                    maxFontSize = 14.sp,
                    minFontSize = 12.sp,
                    modifier = Modifier.padding(top = 8.dp)
                )

                val showFileCount = item.fileCount > 1
                val showDate = item.fileDate.isNotEmpty()
                if (isViewed || showFileCount || showDate) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        if (isViewed) {
                            ViewedBadge()
                        }
                        if (showFileCount) {
                            Surface(
                                shape = RoundedCornerShape(6.dp),
                                color = style.backgroundColor.copy(alpha = 0.12f)
                            ) {
                                Text(
                                    text = stringResource(R.string.resource_file_count, item.fileCount),
                                    style = MaterialTheme.typography.labelSmall.copy(
                                        fontWeight = FontWeight.Medium,
                                        fontSize = 10.sp
                                    ),
                                    color = style.backgroundColor.copy(alpha = 0.9f),
                                    modifier = Modifier.padding(horizontal = 7.dp, vertical = 3.dp),
                                    maxLines = 1,
                                    softWrap = false
                                )
                            }
                        }
                        Spacer(modifier = Modifier.weight(1f))
                        if (showDate) {
                            val formattedDate = remember(item.fileDate) { formatFileDate(item.fileDate) }
                            Text(
                                text = formattedDate,
                                style = MaterialTheme.typography.labelSmall.copy(fontSize = 10.sp),
                                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
                                maxLines = 1,
                                softWrap = false
                            )
                        }
                    }
                }
            }
        }
    }
}
