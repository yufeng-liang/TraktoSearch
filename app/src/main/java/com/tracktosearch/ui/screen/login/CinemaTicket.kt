package com.tracktosearch.ui.screen.login

import android.graphics.Bitmap
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
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
import androidx.compose.ui.graphics.ImageShader
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathOperation
import androidx.compose.ui.graphics.ShaderBrush
import androidx.compose.ui.graphics.TileMode
import androidx.compose.ui.graphics.asImageBitmap
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
import com.tracktosearch.ui.haptic.HapticSemantic
import com.tracktosearch.ui.haptic.hapticClickable
import java.time.LocalDate
import java.util.Locale
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlin.random.Random

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

/**
 * 票高。定高而不是随内容伸缩：票分左右两半，两半的内容互不相干，
 * 谁高谁矮取决于昵称长短和语言，跟着内容走会让同一台机器出的票时高时矮。
 * 176dp 是两半各自的净需求取大的那个 —— 左票根四行文字 80 加二维码块 67 加上下内边距 20，
 * 右半三行入口 120 加两道细线加上下内边距 20，都落在 176 以内。
 */
private val TicketHeight = 176.dp

/**
 * 左右两半的宽度权重。
 *
 * 左票根拿 52%：右半只有三行入口，最长那行连 logo 带箭头也就 106dp，再宽出去就是一片
 * 空白 —— 与其在右边想办法填，不如把线往右挪，让票根多印东西（座位那行就是这么从右半
 * 搬过来的，二维码也是靠这点宽度才排得下）。真影票的票根也不窄，撕开后带走的那截本来
 * 就印着完整的场次信息。
 */
private const val TICKET_STUB_WEIGHT = 0.52f
private const val TICKET_BODY_WEIGHT = 0.48f

/** 两半之间的内边距。票面上的字不能贴着撕口线，也不能贴着纸边。 */
private val TicketHalfPadding = 12.dp

/**
 * 撕口：一条竖打孔线把票分成左票根和右主票，线的上下两端各挖掉一个半圆。
 *
 * [TICKET_TEAR_X_FRACTION] 只是打孔线还没量到时的兜底位置，量到之后一律用实测值。
 */
private val TicketNotchRadius = 7.dp
private const val TICKET_TEAR_X_FRACTION = 0.52f

/** 入口行的最小高度。行高实际由剩余高度均分而来，这个数只是分不到时的下限。 */
private val TicketEntryRowHeight = 40.dp

/** 行与行之间那条细线。真票的入场须知就是印成一栏栏的表格，线比空白更像印刷品。 */
private val TicketHairlineAlpha = 0.16f

/** 按下时整行压深一档，模拟纸被按住；不用 ripple，涟漪是玻璃和塑料的语言。 */
private val TicketPressedOverlay = Color.Black.copy(alpha = 0.07f)

/** 入口行行尾的箭头。横排之后行宽紧张，箭头从行首挪到行尾，省下行首那 24dp。 */
private const val TICKET_ENTRY_CHEVRON = "›"

/** 访客那行的人形剪影墨色。比正文淡一点点，图形的视觉重量本来就比同高的字重。 */
private const val TICKET_GUEST_GLYPH_ALPHA = 0.88f

/**
 * 票面文字的行高倍数。
 *
 * 印在纸上的字行距是紧的，而主题的 bodyLarge 给的是 24.sp 固定行高 —— 那是给正文
 * 段落用的，套到票上 8sp 的抬头身上就是一行 24dp 的空白盒子。1.35 倍留得下中日韩
 * 字形的上下伸出部分，再紧一档汉字的下缘会被行高裁掉。
 */
private const val TICKET_TEXT_LINE_HEIGHT_RATIO = 1.35f

/**
 * 票根二维码：印在左票根下端的方码。
 *
 * 边长 52dp、21 格 —— 21×21 是 QR 版本 1 的真实规格，格子换成任何别的数目，
 * 三个定位角的比例就不再是真二维码的样子。模块边取整到整像素，热敏打印压出来的是
 * 硬边；落在半像素上会被抗锯齿糊成一片灰，凑近看不像印的。
 */
private val TicketQrSize = 52.dp

/** 二维码墨色。整块用同一个值：二维码是要给机器读的，深浅不匀看着像脏了不像油墨不匀。 */
private const val TICKET_QR_INK_ALPHA = 0.9f

/** 票号印在二维码正下方，这是那行字的字距。 */
private val TicketSerialLetterSpacing = 1.sp

/** 撕口线上的打孔点：半径、间距与那一栏的宽度。真票那条线是一排孔，不是一条虚线。 */
private val TicketPerforationDotRadius = 1.dp
private val TicketPerforationDotSpacing = 6.dp
private val TicketPerforationWidth = 12.dp

/** 撕口线还没量到时的兜底位置，只在首帧用一次。 */
private const val TEAR_LINE_UNKNOWN = -1f

// ---- 纸纹 ----
// 画法与机壳砂面同源（见 TicketMachine.kt 的 rememberMachineNoiseBrush）：一次生成
// 一张小位图交给 shader 平铺，不每帧 drawPoints。纸纹比金属砂面弱得多，只提供
// 「这是纸不是塑料」那一点点不均匀。
/** 纸纹位图边长。 */
private const val PAPER_NOISE_TILE_PX = 64

/** 固定随机种子。每次进页面纸纹一样，不会让人觉得换了一张纸。 */
private const val PAPER_NOISE_SEED = 0x7A9E

/** 纸纹透明度。0x0B 大约是机壳砂面的一半：纸面上的小字不能被颗粒吃掉。 */
private const val PAPER_NOISE_ALPHA = 0x0B

/** 8 位通道的取值个数。 */
private const val PAPER_GRAY_LEVELS = 256

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

/** 二维码边长（模块数）。21 是 QR 版本 1 的规格。 */
internal const val TICKET_QR_MODULE_COUNT = 21

/** 定位角边长（模块数）。三个角各 7×7，外加一圈 1 模块宽的分隔白边。 */
private const val TICKET_QR_FINDER_SIZE = 7

/** FNV-1a 的两个常数。取一个成熟的字符串散列，图案才不会随口编出规律来。 */
private const val FNV_OFFSET_BASIS = -0x7EE3623B
private const val FNV_PRIME = 0x01000193

/**
 * 二维码模块阵列，由票号派生。行优先，长度恒为 21×21。
 *
 * **这不是一张能扫的二维码**，是一张长得像二维码的印刷图案：三个定位角、两条时序线
 * 按真规格摆放，其余模块由票号散列出来。票根上真正能被读的信息是下面印的票号 ——
 * 取票码本身不落盘，App 也没有扫码入场这个功能，编一段能扫出内容的码反而是承诺了
 * 一个不存在的动作。
 *
 * 同一张票每次渲染得到同一张图（票号一样，散列一样），不同票之间互不相同。
 * 票号是 [ticketSerial] 出来的 ASCII 数字串，所以这里不受设备语言影响。
 */
internal fun ticketQrModules(serial: String): List<Boolean> {
    val n = TICKET_QR_MODULE_COUNT
    val modules = MutableList(n * n) { false }
    val reserved = MutableList(n * n) { false }

    fun put(x: Int, y: Int, on: Boolean) {
        modules[y * n + x] = on
        reserved[y * n + x] = true
    }

    // 三个定位角：外环实、第二环空、中心 3×3 实。第四个角空着 —— 真二维码就只有三个
    val finderOrigins = listOf(
        0 to 0,
        n - TICKET_QR_FINDER_SIZE to 0,
        0 to n - TICKET_QR_FINDER_SIZE
    )
    for ((originX, originY) in finderOrigins) {
        // 连同外面那圈分隔白边一起占位，散列出来的模块不会贴到定位角上
        for (dy in -1..TICKET_QR_FINDER_SIZE) {
            for (dx in -1..TICKET_QR_FINDER_SIZE) {
                val x = originX + dx
                val y = originY + dy
                if (x !in 0 until n || y !in 0 until n) continue
                val ring = maxOf(abs(dx - 3), abs(dy - 3))
                put(x, y, ring <= 1 || ring == 3)
            }
        }
    }

    // 时序线：第 6 行与第 6 列，奇偶交替。它是解码器找模块网格的标尺
    for (i in TICKET_QR_FINDER_SIZE + 1 until n - TICKET_QR_FINDER_SIZE - 1) {
        put(i, 6, i % 2 == 0)
        put(6, i, i % 2 == 0)
    }

    // 其余模块按票号散列填充。FNV-1a 起手，之后每格再滚一次，取一位
    var state = serial.fold(FNV_OFFSET_BASIS) { acc, char -> (acc xor char.code) * FNV_PRIME }
    for (index in modules.indices) {
        if (reserved[index]) continue
        state = (state xor (state shl 13)) * FNV_PRIME
        modules[index] = (state ushr 17) and 1 == 1
    }
    return modules
}

/**
 * 票号：厅/排/座各补两位拼成的 6 位数字串，同时是二维码下面印的那串数字。
 *
 * format 显式给 [Locale.ROOT]：默认 locale 在阿拉伯语等环境下会把 %02d 输出成
 * 另一套数字字形（٠٢），后面按字符取数就不再是这里想要的那三段，印在票上也不再是
 * 二维码对应的号码。理由同 [formatIssuedDate] 不走 DateTimeFormatter。
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
    doubanBusy: Boolean,
    traktEnabled: Boolean,
    doubanEnabled: Boolean,
    guestEnabled: Boolean,
    onTraktLogin: () -> Unit,
    onDoubanLogin: () -> Unit,
    onGuestMode: () -> Unit,
    modifier: Modifier = Modifier,
) {
    // 主题判断方式与 ActivationLoginScreen 里的取色保持一致
    val isDarkTheme = MaterialTheme.colorScheme.background.luminance() < 0.5f
    val paperColor = if (isDarkTheme) PrintedTicketPaperOnDark else PrintedTicketPaperOnLight
    val noiseBrush = rememberPaperNoiseBrush()
    // 座位信息印在右半主票上：真票撕开后凭主票入场找座，票根上只留身份与条码
    val seatLine = stringResource(R.string.ticket_hall, stub.hall) +
        " · " + stringResource(R.string.ticket_seat, stub.row, stub.seat)
    // 浏览器授权中和换 token 中都不能再点第二次登录，两个平台入口共用这一个判断
    val authBusy = loginState == LoginState.AUTHORIZING || loginState == LoginState.CONNECTING
    // 撕口线的实际位置：上下两个半圆缺口要正好落在这条竖打孔线上，否则「沿孔撕开」这件事
    // 在画面上是两回事 —— 孔在一处，缺口在另一处。写死一个百分比看着也能对上，
    // 但左右两半的权重一改、以后换字号，内容一变就错开，而错开几 dp 说不清哪里怪
    var tearLineX by remember { mutableFloatStateOf(TEAR_LINE_UNKNOWN) }

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
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(TicketHeight + TicketShadowOffset)
                .offset(y = phase.overshootDp.dp)
                // 给自绘投影留一条落地空间：裁切正好停在票的下沿，不留这 2.dp
                // 投影会被整条切掉，票看起来就是直接印在机壳上而不是搭在机壳上
                .padding(bottom = TicketShadowOffset)
                .drawBehind {
                    drawTicketPaper(
                        paperColor = paperColor,
                        noiseBrush = noiseBrush,
                        tearLineX = tearLineX
                    )
                }
        ) {
            // 内边距挂在两半自己身上，不挂在画纸那个节点上：打孔栏要量出自己在纸面
            // 坐标系里的横向位置，而 positionInParent 量的是父节点内容框 —— 纸面节点
            // 一带内边距，量出来就比实际位置偏左一整个内边距，缺口于是浮在孔线旁边
            TicketStubHalf(
                stub = stub,
                seatLine = seatLine,
                modifier = Modifier.weight(TICKET_STUB_WEIGHT)
            )
            TicketPerforation(
                modifier = Modifier.onGloballyPositioned { coords ->
                    val centerX = coords.positionInParent().x + coords.size.width / 2f
                    // 只在真的挪了位置时回写：这个回调每次布局都跑一遍，
                    // 无条件赋值等于每帧碰一次 state
                    if (abs(centerX - tearLineX) > 0.5f) tearLineX = centerX
                }
            )
            Column(
                modifier = Modifier
                    .weight(TICKET_BODY_WEIGHT)
                    .fillMaxHeight()
                    .padding(end = TicketHalfPadding, top = 10.dp, bottom = 10.dp)
            ) {
                // 三行入口把整半张票的高度均分，中间两道细线。座位那行搬到票根去了，
                // 右下角那行英文条例也撤了：这半张票只回答「怎么进场」，一行不多印
                TicketEntryRow(
                    text = when {
                        authBusy -> stringResource(R.string.ticket_entry_authorizing)
                        loginState == LoginState.ERROR -> stringResource(R.string.login_retry)
                        else -> stringResource(R.string.ticket_entry_trakt)
                    },
                    enabled = traktEnabled && !authBusy && !doubanBusy,
                    visible = phase.rowsVisible > 0,
                    busy = authBusy,
                    onClick = onTraktLogin,
                    modifier = Modifier.weight(1f),
                    leading = {
                        TraktLogo(
                            contentDescription = null,
                            modifier = Modifier.size(16.dp)
                        )
                    }
                )
                TicketRowHairline()
                TicketEntryRow(
                    text = if (doubanBusy) {
                        stringResource(R.string.ticket_entry_authorizing)
                    } else {
                        stringResource(R.string.ticket_entry_douban)
                    },
                    enabled = doubanEnabled && !authBusy && !doubanBusy,
                    visible = phase.rowsVisible > 1,
                    busy = doubanBusy,
                    onClick = onDoubanLogin,
                    modifier = Modifier.weight(1f),
                    leading = {
                        DoubanLogo(
                            contentDescription = null,
                            modifier = Modifier.size(16.dp)
                        )
                    }
                )
                TicketRowHairline()
                TicketEntryRow(
                    text = stringResource(R.string.ticket_entry_guest),
                    enabled = guestEnabled && !authBusy && !doubanBusy,
                    visible = phase.rowsVisible > 2,
                    busy = false,
                    onClick = onGuestMode,
                    modifier = Modifier.weight(1f),
                    leading = { TicketGuestGlyph(modifier = Modifier.size(16.dp)) }
                )
            }
        }
    }
}

/**
 * 左票根：身份那一半，撕开之后带走的就是这截。
 *
 * 自上而下是签发方、昵称、日期、座位，最下面贴着二维码和票号，中间的空当由
 * [Arrangement.SpaceBetween] 撑开 —— 真票的码就是压在票根最下端的。
 *
 * 整块合成一个语义节点：这半张票是印刷内容不是控件，逐个念「小明」「2026-08-31」
 * 「2 号厅 · 7 排 12 座」「NO. 020712」会变成四条互不相干的播报。
 */
@Composable
private fun TicketStubHalf(
    stub: TicketStub,
    seatLine: String,
    modifier: Modifier = Modifier,
) {
    val issuedDate = formatIssuedDate(stub.issuedEpochDay)
    val serial = remember(stub.hall, stub.row, stub.seat) { ticketSerial(stub) }
    val stubSpeech = listOf(
        stub.nickname.takeIf { it.isNotBlank() },
        issuedDate,
        seatLine,
        serial
    ).filterNotNull().joinToString(" · ")
    Column(
        modifier = modifier
            .fillMaxHeight()
            .padding(start = TicketHalfPadding, top = 10.dp, bottom = 10.dp)
            .semantics(mergeDescendants = true) { contentDescription = stubSpeech },
        verticalArrangement = Arrangement.SpaceBetween
    ) {
        Column {
            // 签发方。`ADMIT ONE` 撤掉之后这里空着，而票根上方本来就该印是谁出的票；
            // 这不是编造的影院名，就是发这张票的 App 自己
            TicketText(
                text = stringResource(R.string.app_name).uppercase(Locale.ROOT),
                fontSize = 8.sp,
                fontWeight = FontWeight.Bold,
                letterSpacing = 1.2.sp,
                alpha = 0.55f,
                maxLines = 1
            )
            Spacer(modifier = Modifier.height(10.dp))
            // 昵称空白时整行省掉：静默恢复路径拿不到昵称，那种情况下票上不该留一行空白，
            // 也不该印一个「未命名」之类的占位词冒充用户名
            if (stub.nickname.isNotBlank()) {
                TicketText(
                    text = stub.nickname,
                    fontSize = 18.sp,
                    fontWeight = FontWeight.Bold,
                    maxLines = 1
                )
            }
            TicketText(text = issuedDate, fontSize = 11.sp, alpha = 0.8f, maxLines = 1)
            Spacer(modifier = Modifier.height(2.dp))
            TicketText(text = seatLine, fontSize = 11.sp, alpha = 0.8f, maxLines = 1)
        }
        TicketQrCode(serial = serial)
    }
}

/** 行与行之间那条细线。真票的入场须知就是印成一栏栏的表格，线比空白更像印刷品。 */
@Composable
private fun TicketRowHairline() {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(1.dp)
            .drawBehind {
                drawRect(color = PrintedTicketInk.copy(alpha = TicketHairlineAlpha))
            }
    )
}







/**
 * 票面上的一行登录入口。
 *
 * 不做成 Material 按钮：票上贴按钮会毁掉纸质感，一行行印在票面上的字才像票。
 *
 * [visible] 用 alpha 控制而不是条件渲染 —— 三行必须始终占位，否则逐行淡入的过程中
 * 票高会变，露出比例跟着乱跳。不可见时同时断掉点击，避免打印动画途中误触。
 *
 * [busy] 时行尾那个 `›` 换成一个转圈。行文案此时已经由调用方换成「授权中…」，
 * 但静态文字看不出还在动 —— 转圈是「在等浏览器」和「卡住了」之间唯一的区别。
 */
@Composable
private fun TicketEntryRow(
    text: String,
    enabled: Boolean,
    visible: Boolean,
    busy: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    leading: (@Composable () -> Unit)? = null,
) {
    val interactionSource = remember { MutableInteractionSource() }
    val pressed by interactionSource.collectIsPressedAsState()
    val rowAlpha = when {
        !visible -> 0f
        enabled || busy -> 1f
        else -> 0.38f
    }
    Row(
        modifier = modifier
            .fillMaxWidth()
            // 高度由外面那个 Column 的权重给，这里只留一个下限：拿不到权重时行还得能按
            .heightIn(min = TicketEntryRowHeight)
            .alpha(rowAlpha)
            // 触感收在这里：三行入口都从这条路走，调用方不要再各自挂一层。
            // 三行长得一模一样（同款细线分隔、同款行尾箭头），手感分档会被当成 bug ——
            // 游客那行虽然是次级选择，也跟着走同一记
            .hapticClickable(
                interactionSource = interactionSource,
                indication = null,
                semantic = HapticSemantic.TAP,
                enabled = enabled && visible,
                onClick = onClick
            )
            .background(if (pressed) TicketPressedOverlay else Color.Transparent),
        verticalAlignment = Alignment.CenterVertically
    ) {
        if (leading != null) {
            leading()
            Spacer(modifier = Modifier.width(8.dp))
        }
        TicketText(text = text, fontSize = 13.sp, maxLines = 1)
        Spacer(modifier = Modifier.weight(1f))
        // 行尾：进行态是转圈，其余时候是箭头。箭头在行尾而不是行首 ——
        // 横排票右半只有 200dp 出头，行首那个箭头连带间距白占 24dp
        if (busy) {
            CircularProgressIndicator(
                modifier = Modifier.size(12.dp),
                strokeWidth = 1.5.dp,
                color = PrintedTicketInk.copy(alpha = 0.7f)
            )
        } else {
            TicketText(text = TICKET_ENTRY_CHEVRON, fontSize = 14.sp, alpha = 0.5f)
        }
    }
}

/**
 * 撕口线：一列打孔点，不是一条虚线。
 *
 * 真票的这条线是冲出来的孔，两端接到上下那对半圆缺口上。虚线画法看着相近，
 * 但孔是圆的、有间距、每个孔中心都在同一条线上 —— 凑近看差别很明显。
 *
 * 首尾各留半个间距，孔不会正好压在票的上下边线上（那两处是纸边，冲不出整孔）。
 */
@Composable
private fun TicketPerforation(modifier: Modifier = Modifier) {
    Box(
        modifier = modifier
            .fillMaxHeight()
            .width(TicketPerforationWidth)
            .drawBehind {
                val radius = TicketPerforationDotRadius.toPx()
                val spacing = TicketPerforationDotSpacing.toPx()
                val centerX = size.width / 2f
                var y = spacing / 2f
                while (y <= size.height) {
                    drawCircle(
                        color = PrintedTicketInk.copy(alpha = 0.42f),
                        radius = radius,
                        center = Offset(centerX, y)
                    )
                    y += spacing
                }
            }
    )
}

/**
 * 票根二维码：一张 21×21 的方码，正下方印同一串票号。
 *
 * 模块边取整到整像素：热敏打印压出来的是硬边，落在半像素上会被抗锯齿糊成灰边，
 * 21 格糊过去整块就是一团灰。整块同一个墨色 —— 二维码是给机器读的东西，
 * 深浅不匀看着像纸脏了，不像油墨不匀（横条码那时的按位调墨在这里反而失真）。
 *
 * 票号横着印在码下面，不再竖排：撕口线右移之后票根宽了，竖排票号那套
 * `requiredWidth` + `rotate(-90f)` 的绕法没有必要了，横排也更像真票。
 */
@Composable
private fun TicketQrCode(
    serial: String,
    modifier: Modifier = Modifier,
) {
    val modules = remember(serial) { ticketQrModules(serial) }
    Column(modifier = modifier) {
        Canvas(modifier = Modifier.size(TicketQrSize)) {
            val ink = PrintedTicketInk.copy(alpha = TICKET_QR_INK_ALPHA)
            val step = size.width / TICKET_QR_MODULE_COUNT
            modules.forEachIndexed { index, on ->
                if (!on) return@forEachIndexed
                val column = index % TICKET_QR_MODULE_COUNT
                val row = index / TICKET_QR_MODULE_COUNT
                val left = (column * step).roundToInt().toFloat()
                val top = (row * step).roundToInt().toFloat()
                val right = ((column + 1) * step).roundToInt().toFloat()
                val bottom = ((row + 1) * step).roundToInt().toFloat()
                drawRect(
                    color = ink,
                    topLeft = Offset(left, top),
                    size = Size(right - left, bottom - top)
                )
            }
        }
        Spacer(modifier = Modifier.height(3.dp))
        TicketText(
            text = stringResource(R.string.ticket_serial_no, serial),
            fontSize = 8.sp,
            letterSpacing = TicketSerialLetterSpacing,
            alpha = 0.7f,
            maxLines = 1
        )
    }
}

/**
 * 访客那行行首的人形剪影。
 *
 * 不用 Material 的 Person 图标：同一栏另外两行摆的是平台 logo，中间夹一个线框图标
 * 会像贴上去的 UI 元件。这里用票面墨色实心画，头是一个圆，肩是半个椭圆 ——
 * 和旁边的字同一支笔印出来的。
 */
@Composable
private fun TicketGuestGlyph(modifier: Modifier = Modifier) {
    Canvas(modifier = modifier) {
        val ink = PrintedTicketInk.copy(alpha = TICKET_GUEST_GLYPH_ALPHA)
        drawCircle(
            color = ink,
            radius = size.minDimension * 0.19f,
            center = Offset(size.width / 2f, size.height * 0.30f)
        )
        // 肩：椭圆的上半段。sweep 180° 且不连圆心，填出来正好是一个圆顶，
        // 椭圆下缘落在图标底边之外，可见的那半截恰好铺到底
        drawArc(
            color = ink,
            startAngle = 180f,
            sweepAngle = 180f,
            useCenter = false,
            topLeft = Offset(size.width * 0.14f, size.height * 0.56f),
            size = Size(size.width * 0.72f, size.height * 0.88f)
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
        // 行高必须跟着字号算：主题的 bodyLarge 带着 24.sp 行高，而 Text 只被换掉了字号，
        // 于是票上 8sp 的抬头和 11sp 的日期各占 24dp 高 —— 左票根四行文字加二维码
        // 因此超出票高 34dp，票号被挤到纸外面裁掉。装机截图上只看得出「票号没了」
        lineHeight = fontSize * TICKET_TEXT_LINE_HEIGHT_RATIO,
        maxLines = maxLines,
        overflow = TextOverflow.Ellipsis
    )
}

/**
 * 画票：投影在下，纸面在上，纸纹压在纸面上，三层共用同一条票形 Path。
 *
 * 票形是「圆角矩形减掉一串圆」—— 竖撕口线的上下两端各一个半圆，
 * 用 [PathOperation.Difference] 真挖掉，而不是拿背景色的实心圆盖在纸面上。
 * 盖圆那种做法更短，但它要求票背后正好是页面底色；票实际是画在取票机机壳上的，
 * 页面底色跟纸色只差两个色阶，缺口会整个看不见。挖洞跟背后是什么无关。
 *
 * 投影用的是同一条挖过洞的 Path，所以缺口处的影子也跟着缺，撕口才像真撕出来的。
 * 纸纹也走同一条 Path，颗粒不会溢到缺口外面去。
 *
 * @param tearLineX 撕口半圆的圆心横坐标，由打孔栏实测得来；[TEAR_LINE_UNKNOWN] 时退回按比例取
 */
private fun DrawScope.drawTicketPaper(
    paperColor: Color,
    noiseBrush: ShaderBrush,
    tearLineX: Float,
) {
    val ticket = buildTicketPath(tearLineX)
    translate(top = TicketShadowOffset.toPx()) {
        drawPath(path = ticket, color = TicketShadowColor)
    }
    drawPath(path = ticket, color = paperColor)
    drawPath(path = ticket, brush = noiseBrush)
}

private fun DrawScope.buildTicketPath(tearLineX: Float): Path {
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
    val notchCenterX = if (tearLineX > 0f) {
        tearLineX.coerceIn(0f, width)
    } else {
        width * TICKET_TEAR_X_FRACTION
    }
    val notchRadius = TicketNotchRadius.toPx()
    val cutouts = Path().apply {
        // 撕口整圆落在票的上下边线上，挖完各剩半个
        addOval(Rect(center = Offset(notchCenterX, 0f), radius = notchRadius))
        addOval(Rect(center = Offset(notchCenterX, height), radius = notchRadius))
    }
    return Path().apply { op(body, cutouts, PathOperation.Difference) }
}

/**
 * 纸纹：一张 64×64 的噪点位图交给 shader 平铺。
 *
 * 与机壳砂面同一套画法（[rememberMachineNoiseBrush]），只是透明度低一半 ——
 * 纸面上还压着 8sp 的条例小字和票号，颗粒再重一档就开始吃字。
 */
@Composable
private fun rememberPaperNoiseBrush(): ShaderBrush = remember {
    val random = Random(PAPER_NOISE_SEED)
    val pixels = IntArray(PAPER_NOISE_TILE_PX * PAPER_NOISE_TILE_PX) {
        val level = random.nextInt(PAPER_GRAY_LEVELS)
        (PAPER_NOISE_ALPHA shl 24) or (level shl 16) or (level shl 8) or level
    }
    val bitmap = Bitmap.createBitmap(
        pixels,
        PAPER_NOISE_TILE_PX,
        PAPER_NOISE_TILE_PX,
        Bitmap.Config.ARGB_8888
    )
    ShaderBrush(ImageShader(bitmap.asImageBitmap(), TileMode.Repeated, TileMode.Repeated))
}
