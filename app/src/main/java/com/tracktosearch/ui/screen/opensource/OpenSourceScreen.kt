package com.tracktosearch.ui.screen.opensource

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.browser.customtabs.CustomTabsIntent
import androidx.core.net.toUri
import com.tracktosearch.R

/**
 * 开源相关页：按分组列出本 App 使用的第三方开源库（名称版本/许可/开发者），
 * 点击条目弹窗展示在本 App 中的用途与仓库链接。页面结构照帮助页模板。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun OpenSourceScreen(
    onBack: () -> Unit
) {
    var selected by remember { mutableStateOf<OssLibrary?>(null) }

    Scaffold(
        contentWindowInsets = WindowInsets(0, 0, 0, 0)
    ) { padding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
        ) {
            val statusBarHeight = WindowInsets.statusBars.asPaddingValues().calculateTopPadding()
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(
                    top = 64.dp + statusBarHeight,
                    bottom = 80.dp
                )
            ) {
                item(key = "thanks_card") {
                    OssThanksCard()
                }
                OpenSourceData.groups.forEach { group ->
                    item(key = "group_${group.titleRes}") {
                        Text(
                            text = stringResource(group.titleRes),
                            style = MaterialTheme.typography.labelLarge,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.padding(start = 20.dp, top = 16.dp, bottom = 6.dp)
                        )
                    }
                    items(group.libraries, key = { "${group.titleRes}_${it.name}" }) { lib ->
                        OssLibraryCard(lib) { selected = lib }
                    }
                }
            }

            // TopAppBar（纯色背景，与帮助页一致）
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(MaterialTheme.colorScheme.surface)
                    .clickable(enabled = false, onClick = {})
            ) {
                Spacer(modifier = Modifier.statusBarsPadding())
                TopAppBar(
                    title = {
                        Text(
                            text = stringResource(R.string.opensource_title),
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                    },
                    navigationIcon = {
                        IconButton(onClick = onBack) {
                            Icon(
                                imageVector = Icons.AutoMirrored.Rounded.ArrowBack,
                                contentDescription = "Back",
                                tint = MaterialTheme.colorScheme.primary
                            )
                        }
                    },
                    colors = TopAppBarDefaults.topAppBarColors(
                        containerColor = MaterialTheme.colorScheme.surface
                    )
                )
            }
        }
    }

    selected?.let { lib ->
        OssLibraryDialog(lib) { selected = null }
    }
}

/**
 * 顶部感谢卡片：左右麦穗弧 + 致谢文案。
 * 麦穗素材为 OpenClipart 公有领域金色麦穗花环（Public Domain / CC0），
 * 整幅图按 150dp 高缩放，左右各裁出 58dp 宽的弧段，与视觉伴侣定稿一致。
 */
@Composable
private fun OssThanksCard() {
    Surface(
        shape = RoundedCornerShape(14.dp),
        color = MaterialTheme.colorScheme.surfaceVariant,
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 4.dp)
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 150.dp),
            contentAlignment = Alignment.Center
        ) {
            val wreath = painterResource(R.drawable.wheat_wreath)
            val arcHeight = 150.dp
            val arcWidth = 58.dp
            // 源图 549x600，等比缩放后宽约 137dp；裁窗只露左右弧段
            val imageWidth = arcHeight * (549f / 600f)
            val cropOffset = (imageWidth - arcWidth) / 2
            Box(
                modifier = Modifier
                    .align(Alignment.CenterStart)
                    .size(arcWidth, arcHeight)
                    .clipToBounds()
            ) {
                Image(
                    painter = wreath,
                    contentDescription = null,
                    modifier = Modifier
                        .size(imageWidth, arcHeight)
                        .offset(x = -cropOffset)
                )
            }
            Box(
                modifier = Modifier
                    .align(Alignment.CenterEnd)
                    .size(arcWidth, arcHeight)
                    .clipToBounds()
            ) {
                Image(
                    painter = wreath,
                    contentDescription = null,
                    modifier = Modifier
                        .size(imageWidth, arcHeight)
                        .offset(x = cropOffset)
                )
            }
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                modifier = Modifier.padding(horizontal = 64.dp, vertical = 22.dp)
            ) {
                Text(
                    text = stringResource(R.string.opensource_thanks_line1),
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurface
                )
                Text(
                    text = stringResource(R.string.opensource_thanks_line2),
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurface
                )
                Spacer(modifier = Modifier.height(7.dp))
                Text(
                    text = stringResource(R.string.opensource_thanks_line3),
                    style = MaterialTheme.typography.bodySmall,
                    fontWeight = FontWeight.Medium,
                    color = MaterialTheme.colorScheme.primary
                )
            }
        }
    }
}

/** 单个库条目卡片：名称+版本 / 许可 / 版权·开发者 三行，点击弹详情 */
@Composable
private fun OssLibraryCard(lib: OssLibrary, onClick: () -> Unit) {
    Surface(
        shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.surfaceVariant,
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 4.dp)
            .clickable(onClick = onClick)
    ) {
        Column(modifier = Modifier.padding(horizontal = 14.dp, vertical = 12.dp)) {
            Text(
                text = "${lib.name} ${lib.version}",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSurface
            )
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = stringResource(R.string.opensource_license_label, lib.license),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Text(
                text = stringResource(R.string.opensource_developer_label, lib.developer),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

/** 库详情弹窗：版本/许可/开发者 + 在本 App 中的用途 + 仓库链接 */
@Composable
private fun OssLibraryDialog(lib: OssLibrary, onDismiss: () -> Unit) {
    val context = LocalContext.current
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = MaterialTheme.colorScheme.surfaceVariant,
        title = {
            Text(
                text = lib.name,
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold
            )
        },
        text = {
            Column(
                modifier = Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                Text(
                    text = stringResource(R.string.opensource_version_label, lib.version),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Text(
                    text = stringResource(R.string.opensource_license_label, lib.license),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Text(
                    text = stringResource(R.string.opensource_developer_label, lib.developer),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))
                Text(
                    text = stringResource(R.string.opensource_usage_label),
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.primary
                )
                Text(
                    text = stringResource(lib.usageRes),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurface
                )
            }
        },
        confirmButton = {
            TextButton(onClick = {
                CustomTabsIntent.Builder().build()
                    .launchUrl(context, lib.repoUrl.toUri())
            }) {
                Text(stringResource(R.string.opensource_view_repo))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.common_cancel))
            }
        }
    )
}
