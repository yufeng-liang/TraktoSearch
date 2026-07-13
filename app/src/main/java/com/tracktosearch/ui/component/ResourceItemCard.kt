package com.tracktosearch.ui.component

import androidx.annotation.DrawableRes
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.tracktosearch.data.remote.dto.DiskType
import com.tracktosearch.data.remote.dto.ResourceItem
import com.tracktosearch.R
import com.tracktosearch.ui.theme.*
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

data class DiskStyle(
    val shortNameResId: Int,
    val backgroundColor: Color,
    @DrawableRes val iconRes: Int = 0
)

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

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun ResourceItemCard(
    item: ResourceItem,
    isViewed: Boolean = false,
    onClick: () -> Unit,
    onLongClick: (() -> Unit)? = null,
    index: Int = 0,
    modifier: Modifier = Modifier
) {
    val style = diskStyleOf(item.diskType)
    val contentAlpha = if (isViewed) 0.5f else 1f
    val isLight = MaterialTheme.colorScheme.background.luminance() > 0.5f

    Card(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 6.dp)
            .combinedClickable(
                onClick = onClick,
                onLongClick = onLongClick
            ),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surface
        ),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .background(
                    Brush.horizontalGradient(
                        colors = listOf(
                            style.backgroundColor.copy(alpha = if (isLight) 0.14f else 0.22f),
                            style.backgroundColor.copy(alpha = if (isLight) 0.05f else 0.08f),
                            MaterialTheme.colorScheme.surface
                        )
                    )
                )
                .padding(start = 14.dp, end = 14.dp, top = 14.dp, bottom = 12.dp)
                .alpha(contentAlpha)
        ) {
            // 左侧品牌色强调条
            Box(
                modifier = Modifier
                    .align(Alignment.CenterStart)
                    .width(3.dp)
                    .height(40.dp)
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
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    // 序号ID
                    Text(
                        text = "#${index + 1}",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f),
                        modifier = Modifier.width(24.dp)
                    )

                    // 网盘类型标签（品牌色背景 + 图标 + 文字，胶囊形）
                    Surface(
                        shape = RoundedCornerShape(10.dp),
                        color = style.backgroundColor,
                        modifier = Modifier.height(28.dp)
                    ) {
                        Row(
                            modifier = Modifier
                                .padding(horizontal = 8.dp)
                                .fillMaxHeight(),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(4.dp)
                        ) {
                            if (style.iconRes != 0) {
                                Image(
                                    painter = painterResource(id = style.iconRes),
                                    contentDescription = null,
                                    contentScale = ContentScale.Fit,
                                    modifier = Modifier.size(14.dp)
                                )
                            }
                            Text(
                                text = stringResource(style.shortNameResId),
                                style = MaterialTheme.typography.labelSmall.copy(
                                    fontWeight = FontWeight.Bold,
                                    fontSize = 11.sp
                                ),
                                color = Color.White
                            )
                        }
                    }

                    Spacer(modifier = Modifier.width(8.dp))

                    // 来源标签
                    Surface(
                        shape = RoundedCornerShape(8.dp),
                        color = when (item.source) {
                            "pansou" -> MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.8f)
                            "panhub" -> MaterialTheme.colorScheme.tertiaryContainer.copy(alpha = 0.8f)
                            else -> MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.8f)
                        }
                    ) {
                        Text(
                            text = when (item.source) {
                                "pansou" -> "PanSou"
                                "panhub" -> "PanHub"
                                else -> "Zreso"
                            },
                            style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Medium),
                            color = when (item.source) {
                                "pansou" -> MaterialTheme.colorScheme.onPrimaryContainer
                                "panhub" -> MaterialTheme.colorScheme.onTertiaryContainer
                                else -> MaterialTheme.colorScheme.onSecondaryContainer
                            },
                            modifier = Modifier.padding(horizontal = 6.dp, vertical = 3.dp)
                        )
                    }

                    // 已查看标签
                    if (isViewed) {
                        Spacer(modifier = Modifier.width(6.dp))
                        Surface(
                            shape = RoundedCornerShape(8.dp),
                            color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f)
                        ) {
                            Text(
                                text = stringResource(R.string.resource_viewed),
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.outline,
                                modifier = Modifier.padding(horizontal = 6.dp, vertical = 3.dp)
                            )
                        }
                    }

                    Spacer(modifier = Modifier.weight(1f))

                    // 日期（右对齐）
                    if (item.fileDate.isNotEmpty()) {
                        val formattedDate = remember(item.fileDate) { formatFileDate(item.fileDate) }
                        Text(
                            text = formattedDate,
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.8f)
                        )
                    }
                }

                // 资源名称
                Text(
                    text = item.name,
                    style = MaterialTheme.typography.bodyLarge.copy(
                        fontWeight = FontWeight.SemiBold,
                        fontSize = 15.sp
                    ),
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    color = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.padding(top = 8.dp)
                )

                // 文件数
                if (item.fileCount > 1) {
                    Spacer(modifier = Modifier.height(6.dp))
                    Surface(
                        shape = RoundedCornerShape(6.dp),
                        color = style.backgroundColor.copy(alpha = 0.12f)
                    ) {
                        Text(
                            text = stringResource(R.string.resource_file_count, item.fileCount),
                            style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Medium),
                            color = style.backgroundColor.copy(alpha = 0.9f),
                            modifier = Modifier.padding(horizontal = 6.dp, vertical = 3.dp)
                        )
                    }
                }
            }
        }
    }
}
