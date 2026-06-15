package com.tracktosearch.ui.component

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.tracktosearch.data.repository.UpdateInfo
import com.tracktosearch.data.util.ApkDownloader
import com.tracktosearch.data.util.ApkInstaller
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import java.io.File

private sealed class DownloadState {
    object Idle : DownloadState()
    data class Downloading(val progress: Float) : DownloadState()
    data class Completed(val file: File) : DownloadState()
    data class Error(val message: String) : DownloadState()
}

@Composable
fun UpdateDialog(
    updateInfo: UpdateInfo,
    onDismiss: () -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var downloadState by remember { mutableStateOf<DownloadState>(DownloadState.Idle) }
    var downloadJob by remember { mutableStateOf<Job?>(null) }

    DisposableEffect(Unit) {
        onDispose { downloadJob?.cancel() }
    }

    val canDismiss = downloadState !is DownloadState.Downloading

    AlertDialog(
        onDismissRequest = { if (canDismiss) onDismiss() },
        title = { Text("发现新版本 v${updateInfo.latestVersion}") },
        text = {
            Column {
                Text(
                    text = updateInfo.changelog.ifBlank { "v${updateInfo.latestVersion} 版本更新" },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 8,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.verticalScroll(rememberScrollState())
                )

                val state = downloadState
                if (state !is DownloadState.Idle) {
                    Spacer(Modifier.height(12.dp))
                    when (state) {
                        is DownloadState.Downloading -> {
                            LinearProgressIndicator(
                                progress = { state.progress },
                                modifier = Modifier.fillMaxWidth()
                            )
                            Spacer(Modifier.height(4.dp))
                            Text(
                                text = "${(state.progress * 100).toInt()}%",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.primary
                            )
                        }
                        is DownloadState.Completed -> {
                            Text(
                                text = "下载完成",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.primary
                            )
                        }
                        is DownloadState.Error -> {
                            Text(
                                text = "下载失败: ${state.message}",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.error
                            )
                        }
                        else -> {}
                    }
                }
            }
        },
        confirmButton = {
            when (val state = downloadState) {
                is DownloadState.Idle -> {
                    Row {
                        OutlinedButton(onClick = onDismiss) {
                            Text("稍后提醒")
                        }
                        Spacer(Modifier.width(8.dp))
                        Button(onClick = {
                            downloadState = DownloadState.Downloading(0f)
                            downloadJob = scope.launch {
                                try {
                                    val file = ApkDownloader.downloadApk(
                                        context = context,
                                        url = updateInfo.downloadUrl,
                                        fileName = "TraktToSearch-v${updateInfo.latestVersion}.apk",
                                        onProgress = { progress ->
                                            downloadState = DownloadState.Downloading(progress)
                                        }
                                    )
                                    downloadState = DownloadState.Completed(file)
                                } catch (e: Exception) {
                                    downloadState = DownloadState.Error(e.message ?: "下载失败")
                                }
                            }
                        }) {
                            Text("内置下载")
                        }
                        Spacer(Modifier.width(8.dp))
                        OutlinedButton(onClick = {
                            val intent = Intent(Intent.ACTION_VIEW, Uri.parse(updateInfo.downloadUrl))
                            context.startActivity(intent)
                            onDismiss()
                        }) {
                            Text("浏览器下载")
                        }
                    }
                }
                is DownloadState.Downloading -> {
                    OutlinedButton(onClick = {
                        downloadJob?.cancel()
                        downloadState = DownloadState.Idle
                    }) {
                        Text("取消")
                    }
                }
                is DownloadState.Completed -> {
                    Row {
                        OutlinedButton(onClick = onDismiss) {
                            Text("稍后安装")
                        }
                        Spacer(Modifier.width(8.dp))
                        Button(onClick = {
                            ApkInstaller.installApk(context, state.file)
                            onDismiss()
                        }) {
                            Text("安装")
                        }
                    }
                }
                is DownloadState.Error -> {
                    Row {
                        OutlinedButton(onClick = onDismiss) {
                            Text("关闭")
                        }
                        Spacer(Modifier.width(8.dp))
                        Button(onClick = {
                            downloadState = DownloadState.Idle
                        }) {
                            Text("重试")
                        }
                        Spacer(Modifier.width(8.dp))
                        OutlinedButton(onClick = {
                            val intent = Intent(Intent.ACTION_VIEW, Uri.parse(updateInfo.downloadUrl))
                            context.startActivity(intent)
                            onDismiss()
                        }) {
                            Text("浏览器下载")
                        }
                    }
                }
            }
        }
    )
}