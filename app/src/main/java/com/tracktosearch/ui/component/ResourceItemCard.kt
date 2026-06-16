package com.tracktosearch.ui.component

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
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
    val backgroundColor: Color
)

fun diskStyleOf(type: DiskType): DiskStyle = when (type) {
    DiskType.QUARK -> DiskStyle(R.string.disk_quark, QuarkBlue)
    DiskType.BAIDU -> DiskStyle(R.string.disk_baidu, BaiduBlue)
    DiskType.ALI -> DiskStyle(R.string.disk_ali, AliIndigo)
    DiskType.XUNLEI -> DiskStyle(R.string.disk_xunlei, XunleiBlue)
    DiskType.UC -> DiskStyle(R.string.disk_uc, UcOrange)
    DiskType.ONEONEFIVE -> DiskStyle(R.string.disk_115, Blue115)
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

@Composable
fun ResourceItemCard(
    item: ResourceItem,
    isViewed: Boolean = false,
    onClick: () -> Unit,
    index: Int = 0,
    modifier: Modifier = Modifier
) {
    val style = diskStyleOf(item.diskType)
    val contentAlpha = if (isViewed) 0.5f else 1f

    Card(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 4.dp, vertical = 2.dp)
            .clickable(onClick = onClick),
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerLow
        ),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 12.dp, end = 12.dp, top = 12.dp, bottom = 10.dp)
                .alpha(contentAlpha)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                // 序号ID
                Text(
                    text = "#${index + 1}",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
                    modifier = Modifier.width(28.dp)
                )

                // 网盘类型标签（品牌色背景 + 白色文字，居中）
                Surface(
                    shape = RoundedCornerShape(6.dp),
                    color = style.backgroundColor,
                    modifier = Modifier.height(26.dp)
                ) {
                    Box(
                        modifier = Modifier
                            .padding(horizontal = 8.dp)
                            .fillMaxHeight(),
                        contentAlignment = Alignment.Center
                    ) {
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

                Spacer(modifier = Modifier.width(6.dp))

                // 来源标签
                Surface(
                    shape = RoundedCornerShape(4.dp),
                    color = if (item.source == "pansou") {
                        MaterialTheme.colorScheme.primaryContainer
                    } else {
                        MaterialTheme.colorScheme.secondaryContainer
                    }
                ) {
                    Text(
                        text = if (item.source == "pansou") "PanSou" else "Zreso",
                        style = MaterialTheme.typography.labelSmall,
                        color = if (item.source == "pansou") {
                            MaterialTheme.colorScheme.onPrimaryContainer
                        } else {
                            MaterialTheme.colorScheme.onSecondaryContainer
                        },
                        modifier = Modifier.padding(horizontal = 5.dp, vertical = 2.dp)
                    )
                }

                // 已查看标签
                if (isViewed) {
                    Spacer(modifier = Modifier.width(4.dp))
                    Surface(
                        shape = RoundedCornerShape(4.dp),
                        color = MaterialTheme.colorScheme.outlineVariant
                    ) {
                        Text(
                            text = stringResource(R.string.resource_viewed),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.outline,
                            modifier = Modifier.padding(horizontal = 5.dp, vertical = 1.dp)
                        )
                    }
                }

                Spacer(modifier = Modifier.weight(1f))

                // 日期（右对齐）
                if (item.fileDate.isNotEmpty()) {
                    Text(
                        text = formatFileDate(item.fileDate),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            // 第二行：文件数 + 资源名称
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 4.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                // 文件数
                if (item.fileCount > 1) {
                    Surface(
                        shape = RoundedCornerShape(4.dp),
                        color = MaterialTheme.colorScheme.tertiaryContainer
                    ) {
                        Text(
                            text = "${item.fileCount}文件",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onTertiaryContainer,
                            modifier = Modifier.padding(horizontal = 4.dp, vertical = 1.dp)
                        )
                    }
                }

                Spacer(modifier = Modifier.weight(1f))
            }

            // 资源名称
            Text(
                text = item.name,
                style = MaterialTheme.typography.bodyMedium,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.padding(top = 2.dp)
            )
        }
    }
}
