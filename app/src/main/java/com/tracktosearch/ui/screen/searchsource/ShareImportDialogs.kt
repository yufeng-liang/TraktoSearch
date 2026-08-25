package com.tracktosearch.ui.screen.searchsource

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.widget.Toast
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.tracktosearch.R
import com.tracktosearch.data.local.CustomSearchSource
import com.tracktosearch.data.local.ShareCodec

/** 分享配置弹层：编码文本 + 复制 + 系统分享面板 */
@Composable
fun ShareSourceDialog(
    source: CustomSearchSource,
    onDismiss: () -> Unit
) {
    val context = LocalContext.current
    val text = remember(source.id) { ShareCodec.encode(source) }
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = MaterialTheme.colorScheme.surfaceVariant,
        title = { Text(stringResource(R.string.share_source_title, source.name)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(stringResource(R.string.share_source_desc), style = MaterialTheme.typography.bodySmall)
                Text(
                    text = text,
                    style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 4,
                    overflow = TextOverflow.Ellipsis
                )
            }
        },
        confirmButton = {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                OutlinedButton(
                    onClick = {
                        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                        clipboard.setPrimaryClip(ClipData.newPlainText("source-config", text))
                        // 这里保留 Toast：复制后弹层不关，Snackbar 由下层 Scaffold 承载会被弹层窗口盖住看不见
                        Toast.makeText(context, R.string.share_copy_success, Toast.LENGTH_SHORT).show()
                    },
                    modifier = Modifier.weight(1f)
                ) { Text(stringResource(R.string.share_copy)) }
                Button(
                    onClick = {
                        val sendIntent = Intent(Intent.ACTION_SEND).apply {
                            type = "text/plain"
                            putExtra(Intent.EXTRA_TEXT, text)
                        }
                        context.startActivity(Intent.createChooser(sendIntent, null))
                    },
                    modifier = Modifier.weight(1.4f)
                ) { Text(stringResource(R.string.share_to_apps)) }
            }
        }
    )
}

/** 导入配置弹层：粘贴 → 校验 → 预览 → 确认 */
@Composable
fun ImportSourceDialog(
    onConfirm: (CustomSearchSource) -> Unit,
    onDismiss: () -> Unit
) {
    val context = LocalContext.current
    var pasteText by remember { mutableStateOf("") }
    var preview by remember { mutableStateOf<CustomSearchSource?>(null) }
    var invalid by remember { mutableStateOf(false) }
    // 预览区名称可编辑：预览源变化时重置
    var editableName by remember(preview?.id) { mutableStateOf(preview?.name.orEmpty()) }

    // 打开时自动读取剪贴板：符合分享格式则直接填充并预览，点击导入即可
    LaunchedEffect(Unit) {
        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        val text = clipboard.primaryClip?.takeIf { it.itemCount > 0 }?.getItemAt(0)?.text?.toString()
        if (!text.isNullOrBlank()) {
            val decoded = ShareCodec.decode(text.trim())
            if (decoded != null) {
                pasteText = text.trim()
                preview = decoded
            }
        }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = MaterialTheme.colorScheme.surfaceVariant,
        title = { Text(stringResource(R.string.editor_title_import)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedTextField(
                    value = pasteText,
                    onValueChange = { newText ->
                        pasteText = newText
                        invalid = false
                        preview = null
                    },
                    label = { Text(stringResource(R.string.import_paste_hint)) },
                    modifier = Modifier.fillMaxWidth(),
                    minLines = 2
                )
                TextButton(onClick = {
                    val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                    clipboard.primaryClip?.takeIf { it.itemCount > 0 }?.getItemAt(0)?.text?.toString()?.let { it ->
                        pasteText = it
                        val decoded = ShareCodec.decode(it.trim())
                        if (decoded == null) {
                            invalid = true
                            preview = null
                        } else {
                            invalid = false
                            preview = decoded
                        }
                    }
                }) { Text(stringResource(R.string.import_paste)) }
                if (invalid) {
                    Text(
                        stringResource(R.string.import_invalid),
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodySmall
                    )
                }
                preview?.let { source ->
                    Text(
                        stringResource(R.string.import_valid),
                        color = MaterialTheme.colorScheme.primary,
                        style = MaterialTheme.typography.bodySmall
                    )
                    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        OutlinedTextField(
                            value = editableName,
                            onValueChange = { editableName = it },
                            label = { Text(stringResource(R.string.import_preview_name)) },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth()
                        )
                        KeyValueRow(stringResource(R.string.import_preview_url), source.baseUrl)
                        KeyValueRow(stringResource(R.string.import_preview_mode), parseModeLabel(source.parseMode))
                    }
                }
            }
        },
        confirmButton = {
            TextButton(
                enabled = preview != null,
                onClick = {
                    preview?.let { source ->
                        val finalName = editableName.trim().ifBlank { source.name }
                        onConfirm(if (finalName != source.name) source.copy(name = finalName) else source)
                    }
                }
            ) { Text(stringResource(R.string.import_confirm)) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(android.R.string.cancel)) } }
    )
}

private fun parseModeLabel(mode: String): String = when (mode) {
    "pansou_template" -> "PanSou"
    "zreso_template" -> "Zreso"
    else -> "JSONPath"
}

/** 标签-值行（预览用） */
@Composable
private fun KeyValueRow(label: String, value: String) {
    Row(modifier = Modifier.fillMaxWidth()) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.weight(1f)
        )
        Text(
            text = value,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurface
        )
    }
}