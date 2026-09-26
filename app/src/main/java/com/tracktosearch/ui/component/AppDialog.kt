package com.tracktosearch.ui.component

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.BottomSheetDefaults
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonColors
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.Typography
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.tracktosearch.R
import com.tracktosearch.ui.haptic.rememberAppHaptics
import com.tracktosearch.ui.theme.DesignToken
import com.tracktosearch.ui.theme.floatingDialogColor
import com.tracktosearch.ui.theme.floatingSheetColor

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
    // 提交中把 label 换成转圈。没有这一档，带按钮内 loading 的调用点只能自绘整颗按钮，
    // 就绕开了本组件的几何与配色契约。
    val loading: Boolean = false,
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
 *
 * [titleAction] 是标题行的内联文字动作（眼下只有导入弹窗的「粘贴」在用）：它是「对输入框
 * 的一次操作」而不是「对这个弹窗的决定」，混进底部按钮行会跟取消/确认抢同一档视觉重量。
 * 它跟着 title 走 —— title 为空时这本就没有标题行，动作也一并消失。
 */
@Composable
fun AppAlertDialog(
    onDismissRequest: () -> Unit,
    title: String? = null,
    titleAction: DialogAction? = null,
    message: String? = null,
    supportMessage: String? = null,
    confirm: DialogAction? = null,
    dismiss: DialogAction? = null,
    icon: (@Composable () -> Unit)? = null,
    modifier: Modifier = Modifier,
    properties: DialogProperties = DialogProperties(),
    // 内容自带滚动容器（LazyColumn / LazyVerticalGrid 等）时必须置 false：
    // 本组件默认套的 verticalScroll 会把无限高约束传给子节点，lazy 列表收到直接抛
    // IllegalStateException（「点更新日志闪退」即此）。关掉后限高仍在，改由列表自己滚。
    contentScrollable: Boolean = true,
    content: (@Composable () -> Unit)? = null,
) {
    val hasTextSlot = message != null || supportMessage != null || content != null
    // 标题只有 title 一个入口，没有 titleContent 逃生舱：整槽接管等于把字号字重
    // 交回调用点手写，两行标题请改用 title + supportMessage。内联动作走 titleAction，
    // 由 AppDialogTitle 自己按可空处理，这里不再分叉槽位 lambda。
    val titleSlot: (@Composable () -> Unit)? =
        title?.let { text -> @Composable { AppDialogTitle(text, titleAction) } }
    // 槽位内容先单独建成 lambda、再在 if 里按引用三目：把 @Composable { } 直接写在
    // `if (cond) @Composable { } else null` 的分支上，Kotlin 会把它当「分支 lambda」，
    // 整个表达式推成 Unit? 编译不过。三字段全空时传 null 而不是空 lambda：
    // AlertDialog 的 text 槽只要非空就套一层带底部内边距的 Box，空槽会白占一段间距。
    val textSlotContent: @Composable () -> Unit = @Composable {
        // 自带滚动容器（LazyColumn 等）的调用点走 else 分支：限高照旧，滚动交给内容自己。
        val scrollModifier =
            if (contentScrollable) Modifier.verticalScroll(rememberScrollState()) else Modifier
        Column(
            modifier = Modifier
                // testTag 必须在 heightIn 之前：挂在限高之后命中的是被裁剪的内层节点，
                // 滚动语义则可能落在别的层上，断言就查不到东西。
                .testTag(DialogContentTag)
                .heightIn(max = DesignToken.DialogContentMaxHeight)
                .then(scrollModifier),
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

/**
 * 弹窗标题的两档字号字重，唯一出口。
 *
 * 抽成纯函数（同 [dialogButtonColors] 的理由）：Compose 语义树不暴露字号，留在
 * @Composable 里就只能靠截图看，而色调有十来套、自绘标题的调用点也有十来处。
 * 对话框与浮动卡片一档，底部面板一档：面板通栏贴底还带关闭键，18sp 在窄屏会挤到
 * 换行，而面板标题是 maxLines = 1，会直接截断。
 */
internal fun dialogTitleStyle(tp: Typography): TextStyle =
    tp.titleLarge.copy(fontWeight = FontWeight.Bold)

internal fun sheetTitleStyle(tp: Typography): TextStyle =
    tp.titleMedium.copy(fontWeight = FontWeight.Bold)

/**
 * 弹窗标题：统一走 [dialogTitleStyle]，调用点不再手写。
 *
 * [action] 非空时改成「标题 + 右侧内联文字动作」的一行（[AppAlertDialog] 的 titleAction）：
 * M3 的标题槽是个居左 Box，直接往里塞两个节点会上下堆叠，右侧动作得靠 Row 的
 * SpaceBetween 才落得下去。
 *
 * 触感与 [AppDialogActionRow] 同一契约，由本组件发（本函数的组合作用域已在弹窗内，
 * 拿到的是 `Dialog` 自己那个宿主 `View`）。若交给调用点在外面 remember 一份，句柄捕获的
 * 是页面那个 `View`，弹窗盖着它时触感会派发到被遮住的宿主上——见 [rememberAppHaptics] 的
 * KDoc 里「跨对话框必须重捕获」那条。
 * 代价是内联动作即使什么也没做（粘贴时剪贴板为空）也会震一下——与「粘贴地址」按钮同款，
 * 那点多出来的一记比整机无触感划算。
 */
@Composable
internal fun AppDialogTitle(text: String, action: DialogAction? = null) {
    if (action == null) {
        Text(
            text = text,
            style = dialogTitleStyle(MaterialTheme.typography),
        )
        return
    }
    val haptics = rememberAppHaptics()
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(
            text = text,
            style = dialogTitleStyle(MaterialTheme.typography),
            modifier = Modifier.weight(1f),
        )
        TextButton(
            onClick = { haptics.tap(); action.onClick() },
            enabled = action.enabled,
            contentPadding = PaddingValues(horizontal = 8.dp, vertical = 8.dp),
        ) {
            Text(text = action.label)
        }
    }
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
                // 三按钮等宽时每颗只有 90dp，material3 默认水平内边距（Button 24dp、
                // TextButton 12dp）会把四五字中文标签挤成两行。纵向沿用默认 8.dp，
                // 高度仍由下面的 min 兜住；文字居中，短标签观感不变。
                contentPadding = PaddingValues(horizontal = 8.dp, vertical = 8.dp),
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
                contentPadding = PaddingValues(horizontal = 8.dp, vertical = 8.dp),
                modifier = Modifier
                    .weight(primary.weight)
                    .heightIn(min = DesignToken.DialogActionMinHeight),
            ) {
                if (primary.loading) {
                    Box(modifier = Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(18.dp),
                            strokeWidth = 2.dp,
                            color = MaterialTheme.colorScheme.onPrimary,
                        )
                    }
                } else {
                    Text(
                        text = primary.label,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            }
        }
    }
}

/**
 * 自绘浮层的统一外壳，收编 DiscoverFilter / DoubanSpiderTest /
 * ResourceCopyright / UpdateDialog 四处裸 Dialog。
 *
 * usePlatformDefaultWidth = false 是刻意的：这四类里有带列表、带进度的宽内容，
 * 吃平台默认宽度会在平板上窄成一竖条。横向 24dp 缩进 + 28dp 圆角由本组件负责。
 *
 * tonalElevation 默认 0.dp（与遮罩同色平铺）；浮在内容上需要抬升感的调用点
 * （如版权确认弹窗）显式传 [DesignToken.ElevationFloating]，原样透传给内部 Surface。
 */
@Composable
fun AppFloatingDialog(
    onDismissRequest: () -> Unit,
    title: String? = null,
    confirm: DialogAction? = null,
    dismiss: DialogAction? = null,
    modifier: Modifier = Modifier,
    contentPadding: PaddingValues = PaddingValues(DesignToken.DialogPadding),
    tonalElevation: Dp = 0.dp,
    content: @Composable ColumnScope.() -> Unit,
) {
    Dialog(
        onDismissRequest = onDismissRequest,
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        Surface(
            modifier = modifier
                .fillMaxWidth()
                .padding(horizontal = DesignToken.DialogPadding),
            shape = DesignToken.Dialog,
            color = floatingDialogColor(),
            tonalElevation = tonalElevation,
        ) {
            Column(modifier = Modifier.padding(contentPadding)) {
                if (title != null) {
                    AppDialogTitle(title)
                    Spacer(modifier = Modifier.height(DesignToken.DialogActionGap))
                }
                content()
                if (confirm != null || dismiss != null) {
                    Spacer(modifier = Modifier.height(DesignToken.DialogPadding))
                    AppDialogActionRow(primary = confirm, secondary = listOfNotNull(dismiss))
                }
            }
        }
    }
}

/**
 * 全 App 唯一的底部弹层。原先 12 处散写 ModalBottomSheet 与经 DiscoverModalBottomSheet
 * 包装的 6 处调用已全部收口于此（迁移后全仓 raw ModalBottomSheet 仅剩本组件内部一处），
 * 标题栏统一由本组件的 AppSheetHeader 提供。
 *
 * skipPartiallyExpanded 默认 true：项目里的弹层几乎都是内容型，停在半展开
 * 要用户再拖一把；仅模板库、筛选等半展开场景由调用点显式传 false。
 *
 * drag 条跟着「有没有标题栏 + 是否半展开」推导，不再单开参数：只有停在半展开、
 * 又没有标题栏的面板才需要它——那时它是「还能往上拉」的唯一提示。带标题栏时标题栏
 * 已经占了顶部并给出关闭键，再压一条 drag 条就是重复信息；全展开同理。
 * 原先 4 处手写 dragHandle = null、其余默认显示，正是缺这条界线才飘的。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AppBottomSheet(
    onDismissRequest: () -> Unit,
    title: String? = null,
    skipPartiallyExpanded: Boolean = true,
    contentWindowInsets: @Composable () -> WindowInsets = { BottomSheetDefaults.windowInsets },
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit,
) {
    // 槽位 lambda 先建成变量、再在 if 里按引用三目：把 @Composable { } 直接写在
    // `if (cond) @Composable { } else null` 的分支上会被推成 ComposableFunction0<Unit>?，编不过。
    val dragHandleContent: @Composable () -> Unit = { BottomSheetDefaults.DragHandle() }
    val sheetDragHandle: (@Composable () -> Unit)? =
        if (title == null && !skipPartiallyExpanded) dragHandleContent else null
    ModalBottomSheet(
        onDismissRequest = onDismissRequest,
        modifier = modifier,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = skipPartiallyExpanded),
        containerColor = floatingSheetColor(),
        dragHandle = sheetDragHandle,
        contentWindowInsets = contentWindowInsets,
    ) {
        if (title != null) {
            AppSheetHeader(title = title, onDismiss = onDismissRequest)
        }
        content()
    }
}

/** 弹层标题栏：标题占满剩余宽度，关闭用 IconButton 而非文字，把宽度让给标题。 */
@Composable
private fun AppSheetHeader(title: String, onDismiss: () -> Unit) {
    val haptics = rememberAppHaptics()
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 8.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(
            text = title,
            style = sheetTitleStyle(MaterialTheme.typography),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier
                .weight(1f)
                .padding(start = 8.dp),
        )
        IconButton(onClick = { haptics.lightTap(); onDismiss() }) {
            Icon(
                imageVector = Icons.Default.Close,
                contentDescription = stringResource(R.string.common_close),
            )
        }
    }
}
