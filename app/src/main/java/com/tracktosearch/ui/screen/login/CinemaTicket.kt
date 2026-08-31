package com.tracktosearch.ui.screen.login

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
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.PathOperation
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.layout.layout
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
import java.time.LocalDate
import java.util.Locale
import kotlin.math.roundToInt

// 票是纸，不是玻璃：这里是对全站毛玻璃体系的一次有意偏离，不用 haze / appVisualEffect。
// 糊上毛玻璃之后票会退化成一张普通卡片，失去实物感；票面那些 11.sp 的小字透出背景后
// 可读性也会掉一档。所以纸面用不透明颜色，立体感靠下面那层自绘投影给。

/** 浅色主题纸色，比页面底色 #F7EFE2 略亮一档。 */
private val TicketPaperLight = Color(0xFFFBF6EC)

/** 深色主题纸色。本项目深色主题的页面底色是 #D9CFC2，票得更亮才像纸。 */
private val TicketPaperDark = Color(0xFFF3ECE0)

/** 票面文字色。深色主题下纸面依旧是亮的，所以两套主题共用这一个深棕。 */
private val TicketInkColor = Color(0xFF3A2E24)

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

/** 条码竖条：高 26.dp，固定 1.dp 间隔，宽度由票根派生。 */
private val TicketBarcodeHeight = 26.dp
private val TicketBarcodeGap = 1.dp

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
 *
 * format 显式给 [Locale.ROOT]：默认 locale 在阿拉伯语等环境下会把 %02d 输出成
 * 另一套数字字形（٠٢），后面按字符取数就不再是这里想要的那三段。理由同
 * [formatIssuedDate] 不走 DateTimeFormatter。
 */
internal fun ticketBarcodeWidths(stub: TicketStub): List<Int> {
    val digits = "%02d%02d%02d".format(Locale.ROOT, stub.hall, stub.row, stub.seat)
    return digits.flatMap { char ->
        val digit = char.digitToIntOrNull() ?: 0
        (0..2).map { k -> (digit + k) % 3 + 1 }
    }
}

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
    val paperColor = if (isDarkTheme) TicketPaperDark else TicketPaperLight
    val seatText = stringResource(R.string.ticket_seat, stub.row, stub.seat)
    // 浏览器授权中和换 token 中都不能再点第二次登录，两个平台入口共用这一个判断
    val authBusy = loginState == LoginState.AUTHORIZING || loginState == LoginState.CONNECTING

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
                .drawBehind { drawTicketPaper(paperColor = paperColor) }
                .padding(horizontal = 16.dp, vertical = 14.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                TicketText(
                    text = stringResource(R.string.ticket_admit_one),
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Bold,
                    letterSpacing = 1.2.sp,
                    alpha = 0.75f
                )
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
            TicketText(text = seatText, fontSize = 13.sp)

            TicketDashedDivider()

            // 连接中把转圈放在行尾而不是替换文案：用户刚点下去，行还得留着告诉他点的是哪一行
            val traktTrailing: (@Composable () -> Unit)? =
                if (loginState == LoginState.CONNECTING) {
                    { CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp) }
                } else {
                    null
                }
            TicketEntryRow(
                text = if (loginState == LoginState.ERROR) {
                    stringResource(R.string.login_retry)
                } else {
                    stringResource(R.string.ticket_entry_trakt)
                },
                enabled = traktEnabled && !authBusy,
                visible = phase.rowsVisible > 0,
                onClick = onTraktLogin,
                trailing = traktTrailing
            )
            if (loginState == LoginState.AUTHORIZING) {
                // CustomTabs 里按返回取消授权不产生回调：票面补一句等待提示和一个取消入口，
                // 否则 Trakt 行会被 AUTHORIZING 永久锁在禁用态
                TicketText(
                    text = stringResource(R.string.douban_login_waiting_auth),
                    fontSize = 11.sp,
                    alpha = 0.6f
                )
                TextButton(onClick = onCancelAuth) {
                    TicketText(text = stringResource(R.string.common_cancel), fontSize = 13.sp)
                }
            }
            TicketEntryRow(
                text = stringResource(R.string.ticket_entry_douban),
                enabled = doubanEnabled && !authBusy,
                visible = phase.rowsVisible > 1,
                onClick = onDoubanLogin,
                leading = {
                    DoubanLogo(
                        contentDescription = stringResource(R.string.settings_account_douban),
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

            TicketDashedDivider()

            TicketBarcode(
                stub = stub,
                visible = phase.barcodeVisible,
                seatDescription = seatText
            )
        }
    }
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

/** 票面虚线：把票根信息、登录入口、条码切成三段。 */
@Composable
private fun TicketDashedDivider(modifier: Modifier = Modifier) {
    Box(
        modifier = modifier
            .fillMaxWidth()
            .padding(vertical = 10.dp)
            .height(1.dp)
            .drawBehind {
                val centerY = size.height / 2f
                drawLine(
                    color = TicketInkColor.copy(alpha = 0.35f),
                    start = Offset(0f, centerY),
                    end = Offset(size.width, centerY),
                    strokeWidth = 1.dp.toPx(),
                    pathEffect = PathEffect.dashPathEffect(
                        floatArrayOf(3.dp.toPx(), 3.dp.toPx())
                    )
                )
            }
    )
}

/**
 * 票面条码。
 *
 * 整块只给一个语义节点，读屏播的是座位：条码本身不携带用户可用的信息，
 * 念「一段条码」不如念「几排几座」，所以复用座位那句现成文案，不为此新增字符串键。
 */
@Composable
private fun TicketBarcode(
    stub: TicketStub,
    visible: Boolean,
    seatDescription: String,
    modifier: Modifier = Modifier,
) {
    val widths = remember(stub.hall, stub.row, stub.seat) { ticketBarcodeWidths(stub) }
    Row(
        modifier = modifier
            .alpha(if (visible) 1f else 0f)
            .semantics { contentDescription = seatDescription },
        horizontalArrangement = Arrangement.spacedBy(TicketBarcodeGap)
    ) {
        widths.forEach { barWidth ->
            Box(
                modifier = Modifier
                    .size(width = barWidth.dp, height = TicketBarcodeHeight)
                    .background(TicketInkColor)
            )
        }
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
        color = TicketInkColor.copy(alpha = alpha),
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
 */
private fun DrawScope.drawTicketPaper(paperColor: Color) {
    val ticket = buildTicketPath()
    translate(top = TicketShadowOffset.toPx()) {
        drawPath(path = ticket, color = TicketShadowColor)
    }
    drawPath(path = ticket, color = paperColor)
}

private fun DrawScope.buildTicketPath(): Path {
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
    val notchCenterY = height * TICKET_NOTCH_CENTER_FRACTION
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
