package com.tracktosearch.ui.component

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.tracktosearch.R
import com.tracktosearch.ui.haptic.HapticSemantic
import com.tracktosearch.ui.haptic.rememberAppHaptics
import com.tracktosearch.ui.theme.floatingDialogColor

/** 资源卡片的动作类型，决定确认按钮文案。 */
enum class ResourceCopyrightAction {
    OPEN,
    COPY
}

/**
 * 首次访问第三方影视资源前的版权边界确认。
 *
 * 取消、返回键与遮罩关闭均不执行原动作，也不保存勾选；只有确认时才把
 * [dontShowAgain] 交给调用方持久化并执行动作。
 */
@Composable
fun ResourceCopyrightDialog(
    action: ResourceCopyrightAction,
    onConfirm: (dontShowAgain: Boolean) -> Unit,
    onDismiss: () -> Unit
) {
    var dontShowAgain by remember { mutableStateOf(false) }
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = floatingDialogColor(),
        title = { Text(stringResource(R.string.resource_copyright_title)) },
        text = {
            Column {
                Text(
                    text = stringResource(R.string.resource_copyright_body_1),
                    style = androidx.compose.material3.MaterialTheme.typography.bodyMedium,
                    color = androidx.compose.material3.MaterialTheme.colorScheme.onSurface,
                    lineHeight = androidx.compose.material3.MaterialTheme.typography.bodyMedium.lineHeight * 1.35f
                )
                Spacer(modifier = Modifier.height(10.dp))
                Text(
                    text = stringResource(R.string.resource_copyright_body_2),
                    style = androidx.compose.material3.MaterialTheme.typography.bodyMedium,
                    color = androidx.compose.material3.MaterialTheme.colorScheme.onSurface,
                    lineHeight = androidx.compose.material3.MaterialTheme.typography.bodyMedium.lineHeight * 1.35f
                )
                Spacer(modifier = Modifier.height(10.dp))
                Text(
                    text = stringResource(R.string.resource_copyright_body_3),
                    style = androidx.compose.material3.MaterialTheme.typography.bodyMedium,
                    color = androidx.compose.material3.MaterialTheme.colorScheme.onSurface,
                    lineHeight = androidx.compose.material3.MaterialTheme.typography.bodyMedium.lineHeight * 1.35f
                )
                Spacer(modifier = Modifier.height(4.dp))
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable(
                            interactionSource = null,
                            indication = null
                        ) { dontShowAgain = !dontShowAgain },
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.Start
                ) {
                    Checkbox(
                        checked = dontShowAgain,
                        onCheckedChange = { dontShowAgain = it }
                    )
                    Text(
                        text = stringResource(R.string.resource_copyright_dont_show),
                        style = androidx.compose.material3.MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.padding(start = 4.dp)
                    )
                }
            }
        },
        confirmButton = {
            val confirmHaptics = rememberAppHaptics()
            TextButton(
                onClick = {
                    confirmHaptics.tap()
                    onConfirm(dontShowAgain)
                }
            ) {
                Text(
                    stringResource(
                        when (action) {
                            ResourceCopyrightAction.OPEN -> R.string.resource_copyright_continue_open
                            ResourceCopyrightAction.COPY -> R.string.resource_copyright_continue_copy
                        }
                    )
                )
            }
        },
        dismissButton = {
            val cancelHaptics = rememberAppHaptics()
            TextButton(
                onClick = {
                    cancelHaptics.perform(HapticSemantic.LIGHT_TAP)
                    onDismiss()
                }
            ) {
                Text(stringResource(R.string.common_cancel))
            }
        }
    )
}
