package com.tracktosearch.ui.component

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Gavel
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.tracktosearch.R
import com.tracktosearch.ui.haptic.HapticSemantic
import com.tracktosearch.ui.haptic.hapticClickable
import com.tracktosearch.ui.theme.DesignToken

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
    // 头部的图标+标题留在正文里自绘（组件不为单个调用点开 icon 槽）；
    // 确认文案按动作分支，触感由 AppDialogActionRow 统一承担。
    AppFloatingDialog(
        onDismissRequest = onDismiss,
        confirm = DialogAction(
            label = stringResource(
                when (action) {
                    ResourceCopyrightAction.OPEN -> R.string.resource_copyright_continue_open
                    ResourceCopyrightAction.COPY -> R.string.resource_copyright_continue_copy
                }
            ),
            onClick = { onConfirm(dontShowAgain) }
        ),
        dismiss = DialogAction(
            label = stringResource(R.string.common_cancel),
            onClick = onDismiss
        ),
        // 浮在内容之上需要抬升感，这里保留原有的 tonal 抬升
        tonalElevation = DesignToken.ElevationFloating
    ) {
        // 小屏或大字体档下内容可能高于弹窗可用高度：允许纵向滚动，正常档位无感。
        // 24dp 内边距由 AppFloatingDialog 的 contentPadding 承担，这里不再重复。
        Column(
            modifier = Modifier
                .verticalScroll(rememberScrollState())
        ) {
            DialogHeader()
            Spacer(modifier = Modifier.height(18.dp))
            CopyrightPoint(text = stringResource(R.string.resource_copyright_body_1))
            Spacer(modifier = Modifier.height(10.dp))
            CopyrightPoint(text = stringResource(R.string.resource_copyright_body_2))
            Spacer(modifier = Modifier.height(10.dp))
            CopyrightPoint(text = stringResource(R.string.resource_copyright_body_3))
            Spacer(modifier = Modifier.height(18.dp))
            DontShowAgainRow(
                checked = dontShowAgain,
                onCheckedChange = { dontShowAgain = it }
            )
        }
    }
}

/** 头部：主题色图标容器 + 标题/副标题两级信息。 */
@Composable
private fun DialogHeader() {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(
            modifier = Modifier
                .size(48.dp)
                .clip(RoundedCornerShape(14.dp))
                .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.12f)),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                imageVector = Icons.Rounded.Gavel,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(24.dp)
            )
        }
        Spacer(modifier = Modifier.width(14.dp))
        Text(
            text = stringResource(R.string.resource_copyright_title),
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.onSurface
        )
    }
}

/**
 * 单条边界说明：圆点 + 正文。
 *
 * 圆点外层固定 22dp 且水平居中，这样勾选行只要用同样的 22dp 方块、同样的左侧起点，
 * 两个圆心的 x 坐标就自然对齐，不靠魔法 padding 凑。
 */
@Composable
private fun CopyrightPoint(text: String) {
    Row(verticalAlignment = Alignment.Top) {
        Box(
            modifier = Modifier
                .size(22.dp)
                .padding(top = 1.dp),
            contentAlignment = Alignment.Center
        ) {
            Box(
                modifier = Modifier
                    .size(8.dp)
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.primary)
            )
        }
        Spacer(modifier = Modifier.width(10.dp))
        Text(
            text = text,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurface,
            lineHeight = MaterialTheme.typography.bodyMedium.lineHeight * 1.35f
        )
    }
}

/**
 * 整块可点的勾选条，无描边。
 *
 * 勾选圆框同样是 22dp 方块且与正文同心，起点与 [CopyrightPoint] 的圆点一致；
 * 选中态只用浅色底提示，不再画边框。
 */
@Composable
private fun DontShowAgainRow(
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(
                if (checked) MaterialTheme.colorScheme.primary.copy(alpha = 0.10f)
                else Color.Transparent
            )
            .hapticClickable(
                semantic = if (checked) HapticSemantic.TOGGLE_OFF else HapticSemantic.TOGGLE_ON
            ) { onCheckedChange(!checked) }
            .padding(vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            modifier = Modifier
                .size(22.dp),
            contentAlignment = Alignment.Center
        ) {
            Box(
                modifier = Modifier
                    .size(20.dp)
                    .clip(CircleShape)
                    .background(
                        if (checked) MaterialTheme.colorScheme.primary
                        else Color.Transparent
                    )
                    .border(
                        width = 1.5.dp,
                        color = if (checked) MaterialTheme.colorScheme.primary
                        else MaterialTheme.colorScheme.outline,
                        shape = CircleShape
                    ),
                contentAlignment = Alignment.Center
            ) {
                if (checked) {
                    Icon(
                        imageVector = Icons.Rounded.Check,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.surface,
                        modifier = Modifier.size(14.dp)
                    )
                }
            }
        }
        Spacer(modifier = Modifier.width(10.dp))
        Text(
            text = stringResource(R.string.resource_copyright_dont_show),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurface
        )
    }
}
