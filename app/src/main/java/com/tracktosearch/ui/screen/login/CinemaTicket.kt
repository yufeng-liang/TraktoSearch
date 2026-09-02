package com.tracktosearch.ui.screen.login

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathOperation
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.layout.layout
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInParent
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.tracktosearch.R
import com.tracktosearch.data.local.TicketStub
import com.tracktosearch.ui.component.DoubanLogo
import com.tracktosearch.ui.component.TraktLogo
import java.time.LocalDate
import java.util.Locale
import kotlin.math.abs
import kotlin.math.roundToInt

// 票是纸，不是玻璃：这里是对全站毛玻璃体系的一次有意偏离，不用 haze / appVisualEffect。
// 糊上毛玻璃之后票会退化成一张普通卡片，失去实物感；票面那些 11.sp 的小字透出背景后
// 可读性也会掉一档。所以纸面用不透明颜色，立体感靠下面那层自绘投影给。

// 名字带 Printed 前缀是为了跟 ui.theme 里的 TicketPaperLight / TicketPaperDark 区分：
// 那两个是复古票根**主题**的 surface 阶梯（一浅一深），这三个是这张**打印出来的票**的纸和墨
// （两档都是浅色）。同名不同义最容易看串，尤其 TicketPaperDark —— 主题那个是暖黑，这个是浅纸。

/** 浅色主题下的票纸色，比页面底色 #F7EFE2 略亮一档。 */
private val PrintedTicketPaperOnLight = Color(0xFFFBF6EC)

/** 深色主题下的票纸色。本项目深色主题的登录页底色是 #D9CFC2，票得更亮才像纸。 */
private val PrintedTicketPaperOnDark = Color(0xFFF3ECE0)

/** 票面文字色。两档下纸面都是亮的，所以只需要这一个深棕。 */
private val PrintedTicketInk = Color(0xFF3A2E24)

/** 纸张投影：不加模糊，纸搭在机器上投出来的本来就是硬边影子。 */
private val TicketShadowColor = Color.Black.copy(alpha = 0.18f)
private val TicketShadowOffset = 2.dp

private val TicketCornerRadius = 6.dp

/** 票根撕口：半圆半径，圆心落在垂直中线偏上（距顶 38%），上面那截就是票根。 */
private val TicketNotchRadius = 7.dp
private const val TICKET_NOTCH_CENTER_FRACTION = 0.38f

/** 底边锯齿：半径 3.dp 的圆按 8.dp 间距咬在底边线上，咬出撕纸口。 */
private val TicketToothRadius = 3.dp
private val TicketToothSpacing = 8.dp

/** 入口行高。44.dp 低于 48.dp 的常规最小触达，是票面版面的取舍。 */
private val TicketEntryRowHeight = 44.dp

/** 按下时整行压深一档，模拟纸被按住；不用 ripple，涟漪是玻璃和塑料的语言。 */
private val TicketPressedOverlay = Color.Black.copy(alpha = 0.07f)

/** 入口行行首的箭头。 */
private const val TICKET_ENTRY_CHEVRON = "›"

/**
 * 条码：高 30.dp，横向铺满票宽。
 *
 * 铺满而不是按 dp 排死：真票的条码是横跨票根的一整条，早先按 1..3dp 排出来只有约 53dp 宽，
 * 缩在票根左下角像一枚印章而不是条码。宽度由 [ticketBarcodeWidths] 的相对比例摊到可用宽度上，
 * 所以同一张票在任何屏宽下都是同一段条码、同样的疏密。
 */
private val TicketBarcodeHeight = 30.dp
private val TicketBarcodeGap = 1.5.dp

/** 条码下面那行号码的字距。等宽体再拉开一点才像印在条码下面的那串数字。 */
private val TicketSerialLetterSpacing = 3.sp

/** `ADMIT ONE` 反白牌的圆角与内边距。真票这几个字通常是压在一块实底上反白印的。 */
private val TicketBadgeCorner = 2.dp
private val TicketBadgePaddingHorizontal = 6.dp
private val TicketBadgePaddingVertical = 2.dp

/** 撕口线上的打孔点：半径与间距。真票那条线是一排孔，不是一条虚线。 */
private val TicketPerforationDotRadius = 1.dp
private val TicketPerforationDotSpacing = 6.dp

/** 撕口线还没量到时的兜底位置，只在首帧用一次。 */
private const val TEAR_LINE_UNKNOWN = -1f

/**
 * 只有「未激活 → 已激活」那一次跳变才播打印动画。
 *
 * 冷启动进入已激活态时 previousActivated 初值就是 true，不会误触发；
 * 回访时票静态停在出票口，不重播。
 */
internal fun shouldPlayTicketPrint(
    previousActivated: Boolean,
    currentActivated: Boolean,
): Boolean = !previousActivated && currentActivated

/**
 * 条码宽度序列，由票根的厅/排/座派生。
 *
 * 取票码本身不落盘，所以条码不能直接从码算；用座位三元组拼出一个 6 位数字串再展开，
 * 同一张票每次渲染得到同一段条码，不同票之间又互不相同。
 */
internal fun ticketBarcodeWidths(stub: TicketStub): List<Int> =
    ticketSerial(stub).flatMap { char ->
        val digit = char.digitToIntOrNull() ?: 0
        (0..2).map { k -> (digit + k) % 3 + 1 }
    }

/**
 * 票号：厅/排/座各补两位拼成的 6 位数字串，同时是条码下面印的那串数字。
 *
 * format 显式给 [Locale.ROOT]：默认 locale 在阿拉伯语等环境下会把 %02d 输出成
 * 另一套数字字形（٠٢），后面按字符取数就不再是这里想要的那三段，印在票上也不再是
 * 条码对应的号码。理由同 [formatIssuedDate] 不走 DateTimeFormatter。
 */
internal fun ticketSerial(stub: TicketStub): String =
    "%02d%02d%02d".format(Locale.ROOT, stub.hall, stub.row, stub.seat)

/**
 * 取票日期，ISO 的 yyyy-MM-dd。
 *
 * minSdk 是 26，java.time 已经在系统里（工程也没开 isCoreLibraryDesugaringEnabled，
 * 不需要开），所以直接用 LocalDate。LocalDate.toString() 本身就是 ISO 格式，
 * 不必再建 DateTimeFormatter，也不会像本地化格式那样在某些语言环境下换成另一套数字。
 *
 * epochDay 先夹回合法区间：票根是本地存的，一个越界的脏值只该让票面日期难看，
 * 不该让整个取票页抛 DateTimeException。
 */
private fun formatIssuedDate(epochDay: Long): String {
    val safeDay = epochDay.coerceIn(LocalDate.MIN.toEpochDay(), LocalDate.MAX.toEpochDay())
    return LocalDate.ofEpochDay(safeDay).toString()
}

/**
 * 从出票口打印出来的电影票。
 *
 * 露出方式是「容器长高 + 裁掉溢出」：票面内容始终按整票测量、在容器里顶部对齐，
 * 容器高度按 [PrintPhase.revealFraction] 增长，所以先露出来的是票的上半截，
 * 方向和热敏打印机走纸一致。[PrintPhase.revealFraction] 为 0f 时容器高度为 0，不占版面。
 *
 * [PrintPhase.overshootDp] 只施加在票面内容上。它是纸张过冲后回弹的位移，
 * 加到容器上会连带改变裁切高度 —— 那就不是纸在抖，而是出票口自己在抖。
 */
@Composable
internal fun CinemaTicket(
    stub: TicketStub,
    phase: PrintPhase,
    loginState: LoginState,
    traktEnabled: Boolean,
    doubanEnabled: Boolean,
    guestEnabled: Boolean,
    onTraktLogin: () -> Unit,
    onCancelAuth: () -> Unit,
    onDoubanLogin: () -> Unit,
    onGuestMode: () -> Unit,
    modifier: Modifier = Modifier,
) {
    // 主题判断方式与 ActivationLoginScreen 里的取色保持一致
    val isDarkTheme = MaterialTheme.colorScheme.background.luminance() < 0.5f
    val paperColor = if (isDarkTheme) PrintedTicketPaperOnDark else PrintedTicketPaperOnLight
    val seatText = stringResource(R.string.ticket_seat, stub.row, stub.seat)
    // 浏览器授权中和换 token 中都不能再点第二次登录，两个平台入口共用这一个判断
    val authBusy = loginState == LoginState.AUTHORIZING || loginState == LoginState.CONNECTING
    // 撕口线的实际位置：左右两个半圆缺口要正好落在这条打孔线上，否则「沿孔撕开」这件事
    // 在画面上是两回事 —— 孔在一处，缺口在另一处。写死一个百分比看着也能对上，
    // 但昵称为空、换语言、以后加一行，内容一变就错开，而错开几 dp 说不清哪里怪
    var tearLineY by remember { mutableFloatStateOf(TEAR_LINE_UNKNOWN) }

    Box(
        modifier = modifier
            // clipToBounds 必须排在 layout 之前：裁切范围取自 clip 节点自身的测量尺寸，
            // 而它的尺寸来自链上更内层的节点。反过来写成 .layout {}.clipToBounds()，
            // clip 节点量到的是整票高度，一点都裁不掉，票会一次性整张显示、动画看不见。
            .clipToBounds()
            .layout { measurable, constraints ->
                val placeable = measurable.measure(constraints)
                val revealed = (placeable.height * phase.revealFraction)
                    .roundToInt()
                    .coerceIn(0, placeable.height)
                layout(placeable.width, revealed) {
                    placeable.place(0, 0)
                }
            }
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .offset(y = phase.overshootDp.dp)
                // 给自绘投影留一条落地空间：裁切正好停在票的下沿，不留这 2.dp
                // 投影会被整条切掉，票看起来就是直接印在机壳上而不是搭在机壳上
                .padding(bottom = TicketShadowOffset)
                .drawBehind { drawTicketPaper(paperColor = paperColor, tearLineY = tearLineY) }
                .padding(horizontal = 16.dp, vertical = 14.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                AdmitOneBadge(paperColor = paperColor)
                TicketText(
                    text = stringResource(R.string.ticket_hall, stub.hall),
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Bold,
                    letterSpacing = 1.2.sp,
                    alpha = 0.75f
                )
            }
            Spacer(modifier = Modifier.height(12.dp))
            // 昵称空白时整行省掉：静默恢复路径拿不到昵称，那种情况下票上不该留一行空白，
            // 也不该印一个「未命名」之类的占位词冒充用户名
            if (stub.nickname.isNotBlank()) {
                TicketText(
                    text = stub.nickname,
                    fontSize = 22.sp,
                    fontWeight = FontWeight.Bold,
                    maxLines = 1
                )
            }
            TicketText(text = formatIssuedDate(stub.issuedEpochDay), fontSize = 13.sp)
            Spacer(modifier = Modifier.height(10.dp))
            // 排和座改成「小字标签 + 大字数字」两格并排，跟真票一样：早先是一行
            // 「4 排 4 座」，信息一样但读起来是一句话，不是票面上那种一眼扫到的字段
            Row(horizontalArrangement = Arrangement.spacedBy(28.dp)) {
                TicketStat(
                    label = stringResource(R.string.ticket_label_row),
                    value = stub.row.toString()
                )
                TicketStat(
                    label = stringResource(R.string.ticket_label_seat),
                    value = stub.seat.toString()
                )
            }
            Spacer(modifier = Modifier.height(12.dp))
            // 条码在撕口线以上，也就是留在票根那一半 —— 真票撕开后带走的正是这一截
            TicketBarcode(stub = stub, seatDescription = seatText)
            TicketPerforation(
                modifier = Modifier.onGloballyPositioned { coords ->
                    val centerY = coords.positionInParent().y + coords.size.height / 2f
                    // 只在真的挪了位置时回写：这个回调每次布局都跑一遍，
                    // 无条件赋值等于每帧碰一次 state
                    if (abs(centerY - tearLineY) > 0.5f) tearLineY = centerY
                }
            )

            TicketEntryRow(
                text = if (loginState == LoginState.ERROR) {
                    stringResource(R.string.login_retry)
                } else {
                    stringResource(R.string.ticket_entry_trakt)
                },
                enabled = traktEnabled && !authBusy,
                visible = phase.rowsVisible > 0,
                onClick = onTraktLogin,
                leading = {
                    TraktLogo(
                        contentDescription = null,
                        modifier = Modifier.size(16.dp)
                    )
                },
                trailing = { TraktRowStatus(loginState = loginState, onCancelAuth = onCancelAuth) }
            )
            TicketEntryRow(
                text = stringResource(R.string.ticket_entry_douban),
                enabled = doubanEnabled && !authBusy,
                visible = phase.rowsVisible > 1,
                onClick = onDoubanLogin,
                leading = {
                    DoubanLogo(
                        contentDescription = null,
                        modifier = Modifier.size(16.dp)
                    )
                }
            )
            TicketEntryRow(
                text = stringResource(R.string.ticket_entry_guest),
                enabled = guestEnabled,
                visible = phase.rowsVisible > 2,
                onClick = onGuestMode
            )
        }
    }
}

/**
 * Trakt 那行行尾的进行态。
 *
 * 都压在同一行里，不再往票面下面追加一段文字和一个 TextButton：那两样会把票撑高一截，
 * 而且「等待授权」明明是这一行的状态，印在别处就得先让用户自己对应回来。
 *
 * - `CONNECTING`（正在换 token）：一个转圈。行文案留着，用户刚点下去，得知道点的是哪一行
 * - `AUTHORIZING`（浏览器里授权中）：短提示加一个取消。CustomTabs 里按返回取消不产生回调，
 *   没有这个取消入口，Trakt 行会被这个状态永久锁在禁用态
 */
@Composable
private fun TraktRowStatus(
    loginState: LoginState,
    onCancelAuth: () -> Unit,
    modifier: Modifier = Modifier,
) {
    when (loginState) {
        LoginState.CONNECTING -> CircularProgressIndicator(
            modifier = modifier.size(16.dp),
            strokeWidth = 2.dp,
            color = PrintedTicketInk
        )
        LoginState.AUTHORIZING -> Row(
            modifier = modifier,
            verticalAlignment = Alignment.CenterVertically
        ) {
            TicketText(
                text = stringResource(R.string.ticket_entry_authorizing),
                fontSize = 10.sp,
                alpha = 0.6f,
                maxLines = 1
            )
            // 取消不做成按钮：票上贴按钮毁纸质感。靠字重跟旁边的提示分开，
            // 触达面积由这一圈 padding 撑到与行等高
            TicketText(
                text = stringResource(R.string.common_cancel),
                modifier = Modifier
                    .clickable(onClick = onCancelAuth)
                    .padding(horizontal = 8.dp, vertical = 12.dp),
                fontSize = 12.sp,
                fontWeight = FontWeight.Bold,
                maxLines = 1
            )
        }
        else -> Unit
    }
}

/** 票面字段：小字标签压在大字数字上面，真票上的排/座就是这么排的。 */
@Composable
private fun TicketStat(label: String, value: String, modifier: Modifier = Modifier) {
    Column(modifier = modifier) {
        TicketText(text = label, fontSize = 9.sp, letterSpacing = 1.4.sp, alpha = 0.55f)
        TicketText(text = value, fontSize = 18.sp, fontWeight = FontWeight.Bold, maxLines = 1)
    }
}

/** `ADMIT ONE` 反白牌：实底上印纸色的字，真票这几个字通常就是这么压出来的。 */
@Composable
private fun AdmitOneBadge(paperColor: Color, modifier: Modifier = Modifier) {
    Text(
        text = stringResource(R.string.ticket_admit_one),
        modifier = modifier
            .background(PrintedTicketInk, RoundedCornerShape(TicketBadgeCorner))
            .padding(
                horizontal = TicketBadgePaddingHorizontal,
                vertical = TicketBadgePaddingVertical
            ),
        color = paperColor,
        fontSize = 10.sp,
        fontWeight = FontWeight.Bold,
        fontFamily = FontFamily.Monospace,
        letterSpacing = 1.4.sp,
        maxLines = 1
    )
}

/**
 * 票面上的一行登录入口。
 *
 * 不做成 Material 按钮：票上贴按钮会毁掉纸质感，一行行印在票面上的字才像票。
 *
 * [visible] 用 alpha 控制而不是条件渲染 —— 三行必须始终占位，否则逐行淡入的过程中
 * 票高会变，露出比例跟着乱跳。不可见时同时断掉点击，避免打印动画途中误触。
 */
@Composable
private fun TicketEntryRow(
    text: String,
    enabled: Boolean,
    visible: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    leading: (@Composable () -> Unit)? = null,
    trailing: (@Composable () -> Unit)? = null,
) {
    val interactionSource = remember { MutableInteractionSource() }
    val pressed by interactionSource.collectIsPressedAsState()
    val rowAlpha = when {
        !visible -> 0f
        enabled -> 1f
        else -> 0.38f
    }
    Row(
        modifier = modifier
            .fillMaxWidth()
            .height(TicketEntryRowHeight)
            .alpha(rowAlpha)
            .clickable(
                interactionSource = interactionSource,
                indication = null,
                enabled = enabled && visible,
                onClick = onClick
            )
            .background(if (pressed) TicketPressedOverlay else Color.Transparent),
        verticalAlignment = Alignment.CenterVertically
    ) {
        TicketText(text = TICKET_ENTRY_CHEVRON, fontSize = 14.sp, alpha = 0.6f)
        Spacer(modifier = Modifier.width(10.dp))
        if (leading != null) {
            leading()
            Spacer(modifier = Modifier.width(8.dp))
        }
        TicketText(text = text, fontSize = 14.sp, maxLines = 1)
        if (trailing != null) {
            Spacer(modifier = Modifier.weight(1f))
            trailing()
        }
    }
}

/**
 * 撕口线：一排打孔点，不是一条虚线。
 *
 * 真票的这条线是冲出来的孔，两端接到左右那对半圆缺口上。虚线画法看着相近，
 * 但孔是圆的、有间距、每个孔中心都在同一条线上 —— 放到 16.dp 的行高里差别很明显。
 *
 * 首尾各留半个间距，孔不会正好压在票的左右边线上（那两处是纸边，冲不出整孔）。
 */
@Composable
private fun TicketPerforation(modifier: Modifier = Modifier) {
    Box(
        modifier = modifier
            .fillMaxWidth()
            .padding(vertical = 10.dp)
            .height(TicketPerforationDotRadius * 2)
            .drawBehind {
                val radius = TicketPerforationDotRadius.toPx()
                val spacing = TicketPerforationDotSpacing.toPx()
                val centerY = size.height / 2f
                var x = spacing / 2f
                while (x <= size.width) {
                    drawCircle(
                        color = PrintedTicketInk.copy(alpha = 0.42f),
                        radius = radius,
                        center = Offset(x, centerY)
                    )
                    x += spacing
                }
            }
    )
}

/**
 * 票根条码：铺满票宽的一整条竖条，下面印同一串票号。
 *
 * 整块只给一个语义节点，读屏播的是座位：条码本身不携带用户可用的信息，
 * 念「一段条码」不如念「几排几座」，所以复用座位那句现成文案，不为此新增字符串键。
 * 号码同样不单独播报 —— 它是条码的可读版本，念两遍等于同一件事说两次。
 *
 * 竖条位置取整到整像素：热敏打印压出来的是硬边，落在半像素上会被抗锯齿糊成灰边，
 * 一整条 18 根糊过去就不像印的了。
 */
@Composable
private fun TicketBarcode(
    stub: TicketStub,
    seatDescription: String,
    modifier: Modifier = Modifier,
) {
    val widths = remember(stub.hall, stub.row, stub.seat) { ticketBarcodeWidths(stub) }
    val serial = remember(stub.hall, stub.row, stub.seat) { ticketSerial(stub) }
    Column(modifier = modifier.semantics { contentDescription = seatDescription }) {
        Canvas(
            modifier = Modifier
                .fillMaxWidth()
                .height(TicketBarcodeHeight)
        ) {
            val gap = TicketBarcodeGap.toPx()
            val unit = (size.width - gap * (widths.size - 1)) / widths.sum()
            var x = 0f
            widths.forEach { level ->
                val barWidth = unit * level
                val left = x.roundToInt().toFloat()
                val right = (x + barWidth).roundToInt().toFloat()
                drawRect(
                    color = PrintedTicketInk,
                    topLeft = Offset(left, 0f),
                    size = Size(right - left, size.height)
                )
                x += barWidth + gap
            }
        }
        Spacer(modifier = Modifier.height(3.dp))
        TicketText(
            text = serial,
            fontSize = 9.sp,
            letterSpacing = TicketSerialLetterSpacing,
            alpha = 0.7f,
            maxLines = 1
        )
    }
}

/**
 * 票面文字。
 *
 * 全票统一 FontFamily.Monospace，不用站内的像素字体：像素字体是取票机和取票码的语言，
 * 票用等宽体更像热敏打印机压出来的字。两种字体分开，机器和票在视觉上才是两个物件，
 * 而不是同一块 UI 的上下两半。
 */
@Composable
private fun TicketText(
    text: String,
    fontSize: TextUnit,
    modifier: Modifier = Modifier,
    fontWeight: FontWeight? = null,
    letterSpacing: TextUnit = TextUnit.Unspecified,
    alpha: Float = 1f,
    maxLines: Int = Int.MAX_VALUE,
) {
    Text(
        text = text,
        modifier = modifier,
        color = PrintedTicketInk.copy(alpha = alpha),
        fontSize = fontSize,
        fontWeight = fontWeight,
        fontFamily = FontFamily.Monospace,
        letterSpacing = letterSpacing,
        maxLines = maxLines,
        overflow = TextOverflow.Ellipsis
    )
}

/**
 * 画票：投影在下，纸面在上，两者共用同一条票形 Path。
 *
 * 票形是「圆角矩形减掉一串圆」—— 左右各一个撕口半圆，底边一排锯齿圆，
 * 用 [PathOperation.Difference] 真挖掉，而不是拿背景色的实心圆盖在纸面上。
 * 盖圆那种做法更短，但它要求票背后正好是页面底色；票实际是画在取票机机壳
 * （深金属 + 毛玻璃）上的，页面底色跟纸色只差两个色阶，缺口会整个看不见。
 * 挖洞跟背后是什么无关，机壳、页面底色、以后换别的容器都能照样透出来。
 *
 * 投影用的是同一条挖过洞的 Path，所以缺口处的影子也跟着缺，撕口才像真撕出来的。
 *
 * @param tearLineY 撕口半圆的圆心高度，由打孔线实测得来；[TEAR_LINE_UNKNOWN] 时退回按比例取
 */
private fun DrawScope.drawTicketPaper(paperColor: Color, tearLineY: Float) {
    val ticket = buildTicketPath(tearLineY)
    translate(top = TicketShadowOffset.toPx()) {
        drawPath(path = ticket, color = TicketShadowColor)
    }
    drawPath(path = ticket, color = paperColor)
}

private fun DrawScope.buildTicketPath(tearLineY: Float): Path {
    val width = size.width
    val height = size.height
    val corner = TicketCornerRadius.toPx()
    val body = Path().apply {
        addRoundRect(
            RoundRect(
                left = 0f,
                top = 0f,
                right = width,
                bottom = height,
                cornerRadius = CornerRadius(corner, corner)
            )
        )
    }
    val notchCenterY = if (tearLineY > 0f) {
        tearLineY.coerceIn(0f, height)
    } else {
        height * TICKET_NOTCH_CENTER_FRACTION
    }
    val notchRadius = TicketNotchRadius.toPx()
    val toothRadius = TicketToothRadius.toPx()
    val toothStep = TicketToothSpacing.toPx()
    val cutouts = Path().apply {
        // 撕口整圆落在票的左右边线上，挖完各剩半个
        addOval(Rect(center = Offset(0f, notchCenterY), radius = notchRadius))
        addOval(Rect(center = Offset(width, notchCenterY), radius = notchRadius))
        var toothX = 0f
        while (toothX <= width) {
            addOval(Rect(center = Offset(toothX, height), radius = toothRadius))
            toothX += toothStep
        }
    }
    return Path().apply { op(body, cutouts, PathOperation.Difference) }
}
