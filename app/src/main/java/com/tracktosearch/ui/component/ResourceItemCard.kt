package com.tracktosearch.ui.component

import androidx.annotation.DrawableRes
import androidx.annotation.StringRes
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
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
    @StringRes val shortNameResId: Int,
    val backgroundColor: Color,
    @StringRes val sealTextResId: Int,
    // 官方网盘 logo；没有 logo 的兜底类型（磁力/其他）继续用文字色章。
    @DrawableRes val logoResId: Int? = null
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

fun diskStyleOf(type: DiskType): DiskStyle = when (type) {
    DiskType.QUARK -> DiskStyle(
        R.string.disk_quark, QuarkBlue, R.string.resource_card_seal_quark, R.drawable.ic_disk_quark
    )
    DiskType.BAIDU -> DiskStyle(
        R.string.disk_baidu, BaiduBlue, R.string.resource_card_seal_baidu, R.drawable.ic_disk_baidu
    )
    DiskType.ALI -> DiskStyle(
        R.string.disk_ali, AliIndigo, R.string.resource_card_seal_ali, R.drawable.ic_disk_ali
    )
    DiskType.XUNLEI -> DiskStyle(
        R.string.disk_xunlei, XunleiBlue, R.string.resource_card_seal_xunlei, R.drawable.ic_disk_xunlei
    )
    DiskType.UC -> DiskStyle(
        R.string.disk_uc, UcOrange, R.string.resource_card_seal_uc, R.drawable.ic_disk_uc
    )
    DiskType.ONEONEFIVE -> DiskStyle(
        R.string.disk_115, Blue115, R.string.resource_card_seal_115, R.drawable.ic_disk_115
    )
    DiskType.MAGNET -> DiskStyle(R.string.disk_magnet, Color(0xFFD44000), R.string.resource_card_seal_magnet)
    DiskType.OTHER -> DiskStyle(R.string.disk_other, Color(0xFF8E8E93), R.string.resource_card_seal_other)
}

private val DATE_FORMATTER = DateTimeFormatter.ofPattern("yyyy-MM-dd")

private fun formatFileDate(raw: String): String {
    val trimmed = raw.trim()
    if (trimmed.isEmpty()) return ""
    // 覆盖 yunso 的纯日期（2026-11-08）、ISO 带时区、空格分隔日期时间三种格式，
    // 统一显示年月日，不再因为“没有时间部分”解析失败而把整串日期塞进窄位截断。
    if (trimmed.length >= 10 && trimmed[4] == '-' && trimmed[7] == '-') {
        return trimmed.take(10)
    }
    return try {
        val normalized = trimmed.take(19).replace(' ', 'T')
        LocalDateTime.parse(normalized).format(DATE_FORMATTER)
    } catch (_: Exception) {
        trimmed
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

/**
 * 列表层用：订阅已确认失效的链接集合，过滤掉对应卡片。
 * 卡片自己完成「淡出 → 标记失效」后，这里的状态变化会把卡片从列表移除。
 */
@Composable
fun rememberInvalidResourceUrls(): Set<String> {
    val checker = rememberResourceLinkChecker()
    return checker.invalidUrls.collectAsStateWithLifecycle().value
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

/** 网盘标识：优先用官方 logo，缺省时退回品牌色 + 首字色章。 */
@Composable
private fun DiskSeal(style: DiskStyle, size: Dp = 34.dp) {
    val diskName = stringResource(style.shortNameResId)
    val compact = size <= 20.dp
    val hasLogo = style.logoResId != null
    val shape = RoundedCornerShape(if (compact) 5.dp else 10.dp)
    Box(
        modifier = Modifier
            .size(size)
            .then(
                if (hasLogo) {
                    // 官方 logo 原样展示，不铺白底、不加内边距，避免出现一圈白边
                    Modifier
                } else {
                    Modifier
                        .clip(shape)
                        .background(style.backgroundColor)
                }
            )
            .clearAndSetSemantics { contentDescription = diskName },
        contentAlignment = Alignment.Center
    ) {
        if (hasLogo) {
            Image(
                painter = painterResource(style.logoResId!!),
                contentDescription = null,
                modifier = Modifier.fillMaxSize(),
                contentScale = ContentScale.Fit
            )
        } else {
            Text(
                text = stringResource(style.sealTextResId),
                style = MaterialTheme.typography.titleMedium.copy(
                    fontWeight = FontWeight.Medium,
                    fontSize = if (compact) 9.sp else 15.sp,
                    // 品牌色深浅不一，白字对比度不足时自动换成可读的深色
                    color = readableOn(Color.White, style.backgroundColor)
                ),
                maxLines = 1,
                softWrap = false
            )
        }
    }
}

@Composable
private fun SourceBadge(colors: SourceTagColors, text: String) {
    Surface(
        shape = RoundedCornerShape(6.dp),
        color = colors.background,
        modifier = Modifier.height(24.dp)
    ) {
        Row(
            modifier = Modifier
                .padding(horizontal = 7.dp)
                .fillMaxHeight(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            AdaptiveSingleLineText(
                text = text,
                style = MaterialTheme.typography.labelSmall.copy(
                    fontWeight = FontWeight.Bold,
                    fontSize = 10.5.sp,
                    color = colors.content
                ),
                maxFontSize = 10.5.sp,
                minFontSize = 8.5.sp,
                modifier = Modifier.widthIn(max = 72.dp)
            )
        }
    }
}

@Composable
private fun MetaText(text: String, modifier: Modifier = Modifier, color: Color? = null) {
    Text(
        text = text,
        style = MaterialTheme.typography.labelMedium.copy(
            fontSize = 12.sp,
            color = color ?: MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.8f)
        ),
        modifier = modifier,
        maxLines = 1,
        softWrap = false,
        overflow = TextOverflow.Ellipsis
    )
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
    animateEntry: Boolean = false,
    modifier: Modifier = Modifier
) {
    val style = diskStyleOf(item.diskType)
    val isDark = MaterialTheme.colorScheme.background.luminance() <= 0.5f
    val formattedDate = remember(item.fileDate) { formatFileDate(item.fileDate) }
    val interactionSource = remember { MutableInteractionSource() }
    val isPressed by interactionSource.collectIsPressedAsState()
    val scale by animateFloatAsState(
        targetValue = if (isPressed) 0.97f else 1f,
        label = "resource_card_scale"
    )

    // 多源结果陆续到达时，新卡片淡入 + 轻微上移；不改变列表整体布局，避免滚动锚定跳动
    var entered by remember(item.url) { mutableStateOf(!animateEntry) }
    val entryAlpha by animateFloatAsState(
        targetValue = if (entered) 1f else 0f,
        animationSpec = tween(durationMillis = 180),
        label = "resource_card_entry_alpha"
    )
    val entryOffset by animateDpAsState(
        targetValue = if (entered) 0.dp else 8.dp,
        animationSpec = tween(durationMillis = 180),
        label = "resource_card_entry_offset"
    )
    LaunchedEffect(item.url, animateEntry) {
        if (animateEntry && !entered) entered = true
    }

    val linkChecker = rememberResourceLinkChecker()
    val initialStatus = remember(item.url, item.status) {
        statusFromItem(item) ?: linkChecker.cachedStatus(item.url)
    }
    // 只有 PanSou 系（pansou/useai）能在线检测；其余源没有公开检测接口。
    var linkStatus by remember(item.url) { mutableStateOf(initialStatus) }
    // 检出失效后先淡出再通知列表移除，避免卡片瞬间消失、列表硬跳。
    var dismissing by remember(item.url) { mutableStateOf(false) }
    val targetAlpha = when {
        dismissing -> 0f
        isViewed -> 0.55f
        else -> 1f
    }
    val cardAlpha by animateFloatAsState(
        targetValue = targetAlpha,
        animationSpec = tween(durationMillis = 200),
        label = "resource_card_alpha"
    )

    LaunchedEffect(item.url, item.source, item.status) {
        val current = linkStatus
        if (current?.state == ResourceLinkChecker.State.INVALID) {
            dismissing = true
            delay(220)
            linkChecker.markInvalid(item.url)
            return@LaunchedEffect
        }
        if (current == null && linkChecker.supports(item)) {
            // 稍等一拍再检测：快速划过的卡片不产生无谓请求
            delay(450)
            val result = linkChecker.check(item)
            if (result.state == ResourceLinkChecker.State.INVALID) {
                dismissing = true
                delay(220)
                linkChecker.markInvalid(item.url)
            } else {
                // 检测失败（网络/站点不支持）不展示徽章，避免给所有卡片挂个「未知」
                linkStatus = result.takeIf {
                    it.state != ResourceLinkChecker.State.UNKNOWN &&
                        it.state != ResourceLinkChecker.State.UNSUPPORTED
                }
            }
        }
    }

    // 失效卡片直接过滤掉，只有「需提取码」值得在卡片上打断用户。
    val statusBadgeState = linkStatus?.state?.takeIf {
        it == ResourceLinkChecker.State.LOCKED
    }

    Card(
        modifier = modifier
            .fillMaxWidth()
            .alpha(cardAlpha * entryAlpha)
            .offset(y = entryOffset)
            .padding(horizontal = 12.dp, vertical = 3.dp)
            .scale(scale)
            .hapticCombinedClickable(
                interactionSource = interactionSource,
                indication = null,
                semantic = HapticSemantic.LIGHT_TAP,
                onClick = { if (!dismissing) onClick() },
                onLongClick = { if (!dismissing) onLongClick?.invoke() }
            ),
        shape = RoundedCornerShape(18.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surface
        ),
        border = BorderStroke(
            width = 1.dp,
            color = style.backgroundColor.copy(
                alpha = when {
                    isPressed -> 0.28f
                    isDark -> 0.24f
                    else -> 0.12f
                }
            )
        ),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 10.dp)
        ) {
            // 第一行：#序号 + 资源名 + 文件数/需提取码
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.Top
            ) {
                Text(
                    text = "#${index + 1}",
                    style = MaterialTheme.typography.labelSmall.copy(
                        fontSize = 10.5.sp,
                        fontWeight = FontWeight.Medium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.75f)
                    ),
                    modifier = Modifier
                        .widthIn(min = 22.dp)
                        .padding(top = 3.dp, end = 4.dp),
                    maxLines = 1,
                    softWrap = false
                )

                AdaptiveTwoLineTitle(
                    text = item.name,
                    style = MaterialTheme.typography.bodyLarge.copy(
                        fontWeight = FontWeight.SemiBold,
                        fontSize = 15.sp,
                        color = MaterialTheme.colorScheme.onSurface
                    ),
                    maxFontSize = 15.sp,
                    minFontSize = 13.sp,
                    modifier = Modifier.weight(1f)
                )

                if (item.fileCount > 1) {
                    Spacer(modifier = Modifier.width(6.dp))
                    MetaText(
                        text = stringResource(R.string.resource_file_count, item.fileCount),
                        modifier = Modifier.padding(top = 3.dp)
                    )
                }

                if (statusBadgeState != null) {
                    Spacer(modifier = Modifier.width(6.dp))
                    LinkStatusBadge(state = statusBadgeState)
                }
            }

            Spacer(modifier = Modifier.height(6.dp))

            // 第二行：网盘 logo + 网盘名称 · 来源名，日期贴右下角
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                DiskSeal(style = style, size = 16.dp)
                Spacer(modifier = Modifier.width(5.dp))
                Text(
                    text = stringResource(style.shortNameResId),
                    style = MaterialTheme.typography.labelMedium.copy(
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.62f)
                    ),
                    maxLines = 1,
                    softWrap = false
                )
                Text(
                    text = " · ",
                    style = MaterialTheme.typography.labelMedium.copy(
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.45f)
                    ),
                    maxLines = 1,
                    softWrap = false
                )
                Text(
                    text = sourceTagLabel(item.source, sourceName),
                    style = MaterialTheme.typography.labelMedium.copy(
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.58f)
                    ),
                    modifier = Modifier.weight(1f),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                if (formattedDate.isNotEmpty()) {
                    Spacer(modifier = Modifier.width(8.dp))
                    MetaText(
                        text = formattedDate,
                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.55f)
                    )
                }
            }
        }
    }
}
