package com.tracktosearch.ui.component

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonColors
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.window.DialogProperties
import com.tracktosearch.ui.haptic.rememberAppHaptics
import com.tracktosearch.ui.theme.DesignToken
import com.tracktosearch.ui.theme.floatingDialogColor

/*
 * 统一弹窗组件，三族共用一份按钮行与配色，按「对话框 → 浮动卡片 → 底部面板」组织，
 * 后续在同一个文件里往下追加：AppFloatingDialog、AppBottomSheet。
 *
 * 收在一起是因为浮层的圆角、内边距、按钮顺序、触感契约只该有一处定义：
 * 按钮行的契约写在这个文件底部，三族迁移时不必逐个调用点重审。
 */

/** 弹窗主操作按钮的语义色。 */
enum class DialogTone { Primary, Destructive }

/** 弹窗里的一个按钮。label 由调用点 stringResource 传入，组件不碰资源。 */
data class DialogAction(
    val label: String,
    val onClick: () -> Unit,
    val enabled: Boolean = true,
    val weight: Float = 1f,
    val tone: DialogTone = DialogTone.Primary,
)

/**
 * 填充按钮配色。抽成纯函数是因为「Destructive 到底吃 error 还是 primary」是可断言的
 * 契约，留在 @Composable 里就只能靠截图看，而色调有十来套。
 *
 * 直接构造 ButtonColors 而不用 `ButtonDefaults.buttonColors(...)`：material3 1.4.0 把后者
 * 标成了 @Composable（它要先读主题里的默认色再 copy），一旦调用它本函数就得变成
 * @Composable，上面那条可断言契约就没了。四个色全部显式给出，两种写法结果等价。
 */
internal fun dialogButtonColors(tone: DialogTone, scheme: ColorScheme): ButtonColors = when (tone) {
    DialogTone.Primary -> ButtonColors(
        containerColor = scheme.primary,
        contentColor = scheme.onPrimary,
        disabledContainerColor = scheme.onSurface.copy(alpha = 0.12f),
        disabledContentColor = scheme.onSurface.copy(alpha = 0.38f),
    )
    DialogTone.Destructive -> ButtonColors(
        containerColor = scheme.error,
        contentColor = scheme.onError,
        disabledContainerColor = scheme.onSurface.copy(alpha = 0.12f),
        disabledContentColor = scheme.onSurface.copy(alpha = 0.38f),
    )
}

/** 内容槽的滚动节点 tag，AppDialogTest 用它断言可滚动。 */
internal const val DialogContentTag = "app_dialog_content"

/**
 * 全 App 唯一的居中对话框。内部仍是 M3 AlertDialog，把底色、圆角、标题字号、
 * 内容限高、按钮行和触感全部收口，调用点只描述内容。
 *
 * 不放 dismissButton：M3 的按钮行是 FlowRow，两个按钮各占一槽会挤成右对齐小胶囊，
 * 撑满等宽必须合成一个 Row 交给 confirmButton（AlertDialogFlowRow 会把父约束
 * 原样传给子节点，故 Row.fillMaxWidth() 能铺满）。
 */
@Composable
fun AppAlertDialog(
    onDismissRequest: () -> Unit,
    title: String? = null,
    titleContent: (@Composable () -> Unit)? = null,
    message: String? = null,
    supportMessage: String? = null,
    confirm: DialogAction? = null,
    dismiss: DialogAction? = null,
    icon: (@Composable () -> Unit)? = null,
    modifier: Modifier = Modifier,
    properties: DialogProperties = DialogProperties(),
    content: (@Composable () -> Unit)? = null,
) {
    val hasTextSlot = message != null || supportMessage != null || content != null
    // titleContent 整槽接管标题：自定义结构（两行标题等）与 title 不同时生效，
    // 传了 titleContent 就把 title 丢掉，避免两处描述互相打架。
    val titleSlot: (@Composable () -> Unit)? = titleContent
        ?: title?.let { text -> @Composable { AppDialogTitle(text) } }
    // 槽位内容先单独建成 lambda、再在 if 里按引用三目：把 @Composable { } 直接写在
    // `if (cond) @Composable { } else null` 的分支上，Kotlin 会把它当「分支 lambda」，
    // 整个表达式推成 Unit? 编译不过。三字段全空时传 null 而不是空 lambda：
    // AlertDialog 的 text 槽只要非空就套一层带底部内边距的 Box，空槽会白占一段间距。
    val textSlotContent: @Composable () -> Unit = @Composable {
        Column(
            modifier = Modifier
                // testTag 必须在 heightIn 之前：挂在限高之后命中的是被裁剪的内层节点，
                // 滚动语义则可能落在别的层上，断言就查不到东西。
                .testTag(DialogContentTag)
                .heightIn(max = DesignToken.DialogContentMaxHeight)
                .verticalScroll(rememberScrollState()),
        ) {
            if (message != null) {
                Text(
                    text = message,
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
            if (message != null && supportMessage != null) {
                Spacer(modifier = Modifier.height(DesignToken.DialogActionGap))
            }
            if (supportMessage != null) {
                Text(
                    text = supportMessage,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (content != null) {
                if (message != null || supportMessage != null) {
                    Spacer(modifier = Modifier.height(DesignToken.DialogActionGap))
                }
                content()
            }
        }
    }
    val textSlot: (@Composable () -> Unit)? = if (hasTextSlot) textSlotContent else null
    AlertDialog(
        onDismissRequest = onDismissRequest,
        modifier = modifier,
        shape = DesignToken.Dialog,
        containerColor = floatingDialogColor(),
        icon = icon,
        title = titleSlot,
        text = textSlot,
        confirmButton = {
            if (confirm != null || dismiss != null) {
                AppDialogActionRow(primary = confirm, secondary = listOfNotNull(dismiss))
            }
        },
        properties = properties,
    )
}

/** 弹窗标题：统一 titleLarge + Bold，调用点不再手写。 */
@Composable
internal fun AppDialogTitle(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.titleLarge,
        fontWeight = FontWeight.Bold,
    )
}

/**
 * 三族浮层共用的按钮行：主按钮填充在右，次按钮纯文字在左，等宽撑满。
 *
 * 次按钮放前面是为了保持 M3 的左右顺序（取消在左、确认在右），视觉重量由填充承担。
 */
@Composable
fun AppDialogActionRow(
    primary: DialogAction?,
    secondary: List<DialogAction> = emptyList(),
    modifier: Modifier = Modifier,
) {
    // 按钮行自己就在弹窗槽位的 subcomposition 里，取一份 facade 即可
    val haptics = rememberAppHaptics()
    val scheme = MaterialTheme.colorScheme
    Row(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(DesignToken.DialogActionGap),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        secondary.forEach { action ->
            TextButton(
                onClick = { haptics.lightTap(); action.onClick() },
                enabled = action.enabled,
                modifier = Modifier.weight(action.weight),
            ) {
                Text(
                    text = action.label,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }
        if (primary != null) {
            Button(
                onClick = { haptics.tap(); primary.onClick() },
                enabled = primary.enabled,
                colors = dialogButtonColors(primary.tone, scheme),
                // 胶囊形。material3 1.4.0 的 Shapes 没有 full 一档（只有 extraSmall…extraLarge），
                // 库默认 ButtonDefaults.shape 虽是同款胶囊但是个 @Composable getter，
                // 显式写 CircleShape 免得上游哪天改默认值把弹窗按钮一起带走。
                shape = CircleShape,
                modifier = Modifier
                    .weight(primary.weight)
                    .heightIn(min = DesignToken.DialogActionMinHeight),
            ) {
                Text(
                    text = primary.label,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }
    }
}
