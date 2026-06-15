package com.tracktosearch.ui.component

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.tracktosearch.data.repository.UpdateInfo
import kotlinx.coroutines.launch

@Composable
fun UpdateDialog(
    updateInfo: UpdateInfo,
    onDismiss: () -> Unit,
    onOpenInBrowser: (String) -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var isDownloading by remember { mutableStateOf(false) }
    var downloadProgress by remember { mutableFloatStateOf(0f) }
    var downloadFailed by remember { mutableStateOf(false) }
    var downloadComplete by remember { mutableStateOf(false) }

    AlertDialog(
        onDismissRequest = { if (!isDownloading) onDismiss() },
        title = { Text("发现新版本 v${updateInfo.latestVersion}") },
        text = {
            Column {
                // 更新日志
                Text(
                    text = updateInfo.changelog.ifBlank { "暂无更新说明" },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 8,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.verticalScroll(rememberScrollState())
                )

                Spacer(modifier = Modifier.height(16.dp))

                when {
                    downloadComplete -> {
                        Text(
                            text = "下载完成，正在安装…",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.primary
                        )
                    }
                    isDownloading -> {
                        LinearProgressIndicator(
                            progress = { downloadProgress },
                            modifier = Modifier.fillMaxWidth(),
                        )
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            text = "${(downloadProgress * 100).toInt()}%",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    downloadFailed -> {
                        Text(
                            text = "下载失败，请尝试浏览器下载",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error
                        )
                    }
                }
            }
        },
        confirmButton = {
            when {
                downloadComplete -> {
                    // 什么都不做，安装器已启动
                }
                isDownloading -> {
                    CircularProgressIndicator(modifier = Modifier.size(24.dp), strokeWidth = 2.dp)
                }
                downloadFailed -> {
                    Row {
                        OutlinedButton(onClick = onDismiss) {
                            Text("关闭")
                        }
                        Spacer(modifier = Modifier.width(8.dp))
                        Button(onClick = {
                            val url = updateInfo.giteeDownloadUrl.ifBlank { updateInfo.downloadUrl }
                            onOpenInBrowser(url)
                            onDismiss()
                        }) {
                            Text("浏览器下载")
                        }
                    }
                }
                else -> {
                    Row {
                        OutlinedButton(onClick = onDismiss) {
                            Text("稍后提醒")
                        }
                        Spacer(modifier = Modifier.width(8.dp))
                        Button(onClick = {
                            isDownloading = true
                            downloadFailed = false
                            scope.launch {
                                try {
                                    val apkFile = com.tracktosearch.data.util.ApkDownloader.downloadApk(
                                        context = context,
                                        url = updateInfo.downloadUrl,
                                        fileName = "TraktToSearch-v${updateInfo.latestVersion}.apk",
                                        onProgress = { progress -> downloadProgress = progress }
                                    )
                                    downloadComplete = true
                                    com.tracktosearch.data.util.ApkInstaller.installApk(context, apkFile)
                                } catch (e: Exception) {
                                    downloadFailed = true
                                    isDownloading = false
                                }
                            }
                        }) {
                            Text("立即更新")
                        }
                    }
                }
            }
        }
    )
}
