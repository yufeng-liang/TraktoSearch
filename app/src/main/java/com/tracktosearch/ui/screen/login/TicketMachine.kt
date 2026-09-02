package com.tracktosearch.ui.screen.login

import android.graphics.Bitmap
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.Backspace
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.ripple
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.scale
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageShader
import androidx.compose.ui.graphics.ShaderBrush
import androidx.compose.ui.graphics.TileMode
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.testTag
import androidx.compose.ui.text.PlatformTextStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.LineHeightStyle
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.tracktosearch.R
import com.tracktosearch.ui.component.isAppDarkTheme
import com.tracktosearch.ui.theme.MachineBulbEmber
import com.tracktosearch.ui.theme.MachineBulbEmberGreen
import com.tracktosearch.ui.theme.MachineBulbEmberRed
import com.tracktosearch.ui.theme.MachineBulbLit
import com.tracktosearch.ui.theme.MachineBulbLitGreen
import com.tracktosearch.ui.theme.MachineBulbLitRed
import com.tracktosearch.ui.theme.MachineBulbUnlitDark
import com.tracktosearch.ui.theme.MachineBulbUnlitLight
import com.tracktosearch.ui.theme.MachineCodeInk
import com.tracktosearch.ui.theme.MachineDisplayInk
import com.tracktosearch.ui.theme.MachineDisplayInkError
import com.tracktosearch.ui.theme.MachineDisplayWellDark
import com.tracktosearch.ui.theme.MachineDisplayWellLight
import com.tracktosearch.ui.theme.MachineKeyInkDark
import com.tracktosearch.ui.theme.MachineKeyInkLight
import com.tracktosearch.ui.theme.MachineKeycapDark
import com.tracktosearch.ui.theme.MachineKeycapLight
import com.tracktosearch.ui.theme.MachinePlateDark
import com.tracktosearch.ui.theme.MachinePlateInk
import com.tracktosearch.ui.theme.MachinePlateLight
import com.tracktosearch.ui.theme.MachineShellDark
import com.tracktosearch.ui.theme.MachineShellLight
import com.tracktosearch.ui.theme.MachineShellShadeFraction
import com.tracktosearch.ui.theme.MachineSlotWall
import com.tracktosearch.ui.theme.LoginPaperLight
import com.tracktosearch.ui.theme.LoginTitleInk
import com.tracktosearch.ui.theme.PixelFontFamily
import com.tracktosearch.ui.theme.pixelFontSize
import kotlin.math.exp
import kotlin.math.floor
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.random.Random

/** 取票码位数。六格与读屏播报都按它算，改长度只改这里。 */
private const val CODE_LENGTH = 6

/**
 * 像素屏窗高。两行 —— 第一行短状态、第二行完整引导 —— 加上下 6dp 内边距和 3dp 行距。
 * 锁死而不是随内容伸缩：只有一行时也留着第二行的位置，否则整台机器会随状态跳动。
 */
private val DisplayHeight = 52.dp

/**
 * 键帽高度。固定值而非 `aspectRatio` —— 后者会让键在宽屏上跟着变高，
 * 而这一屏的全部意义是「一屏放得下」，键高必须与屏宽无关。52dp 仍高于 48dp 触达线。
 */
private val KeyHeight = 52.dp

/** 机壳圆角。上下同值：取票机是个方箱子，圆角只是倒边。 */
private val ShellCorner = 16.dp

/**
 * 取票码单格的高度。空着时这一格全是留白，40dp 会在屏和键盘之间留出一大块空洞，
 * 34dp 仍装得下 24dp 档的像素数字和 20dp 的光标。
 */
private val CodeCellHeight = 34.dp

/**
 * 机壳内边距。纵向这一档同时决定顶边跑马灯的位置，四角螺丝的纵向圆心由它推导
 * （见 [drawShellScrews]），所以改这里灯和螺丝会一起动，不会只动一边。
 */
private val ShellPaddingVertical = 12.dp

/**
 * 机壳四角螺丝的直径。
 *
 * 比灯泡小一半：两者现在同高一排，尺寸再接近的话灭灯之后整条顶边就是十个一样的暗圆点，
 * 分不出哪个是灯哪个是螺丝。
 */
private val ScrewSize = 6.dp

/** 螺丝到机壳左右边的距离。比横向内边距小一档，螺丝才落在「边框」那一圈里而不是压住内容。 */
private val ScrewInsetHorizontal = 7.dp

/** 已取票态键盘的残留透明度。键盘留在面板上撑住机器的样子，但不再是可用控件。 */
private const val GHOST_KEYPAD_ALPHA = 0.30f

/** 键盘整块的测试标记。已取票态整块从读屏树里摘掉，只留这个标记供测试确认它还在版式里。 */
internal const val MACHINE_KEYPAD_TAG = "machine_keypad"

// ---- 机壳砂面噪点 ----
// 一次生成一张小位图，之后交给 shader 平铺。不用每帧 drawPoints：
// 铺满整台机器是几千个点，而这层颗粒是静态的，没有哪一帧需要重算它。
/** 噪点位图边长。64 够碎，平铺接缝看不出来；再大只是白占内存。 */
private const val NOISE_TILE_PX = 64

/** 固定随机种子。噪点图案每次进页面都一样，不会让人觉得「换了一台机器」。 */
private const val NOISE_SEED = 0x5EED

/** 噪点透明度。0x16 是「凑近看有颗粒、正常距离只觉得不是塑料」的那一档。 */
private const val NOISE_ALPHA = 0x16

// ---- 顶边跑马灯 ----
/**
 * 灯泡颗数。
 *
 * 6 颗有两个理由。一是首尾两颗要离四角螺丝远一点（另见 [BulbRowInset]），
 * 排上塞得越少每颗的余地越大。二是尾巴长度：余温衰减常数是 [BULB_DECAY_BULBS] 颗，
 * 6 颗刚好让头灯走到最后一颗时整排都还亮着（末端也有约 13% 的余光），
 * 那一刻整排通亮就是一遍扫完的画面。颗数再多，头灯到底时前几颗已经黑了。
 */
private const val BULB_COUNT = 6

/** 灯泡玻璃直径。灯座环画在它外面，所以这一颗实际占位比这个数大一圈。 */
private val BulbSize = 12.dp

/**
 * 灯泡这一排相对机壳内边距再往里收的距离。
 *
 * 首尾两颗是钉在排两端的，不收的话它们的灯座环离四角螺丝只剩 3dp，
 * 看着像螺丝拧在灯泡上。收这一档之后留出 7dp 空当。
 */
private val BulbRowInset = 6.dp

/** 灯座环相对玻璃半径的加宽量。灯泡要看着是拧在机壳上的，不是画在机壳上的。 */
private const val BULB_SOCKET_RATIO = 1.28f

/**
 * 头灯走过一颗灯的时长。
 *
 * 按「每颗」而不是「跑一圈」定义：[BULB_COUNT] 改了也不用重算节奏，
 * 圈长由两者相乘得出。650ms 一颗是老式门头灯那种不着急的走法。
 */
private const val BULB_STEP_MILLIS = 650

/**
 * 验码期间头灯走过一颗灯的时长。
 *
 * 比进场那一遍的 [BULB_STEP_MILLIS] 快得多，因为这两处要说的事不一样：进场是氛围，
 * 慢才像门头灯；验码是「机器在忙」，而一次验码通常不到一秒 —— 按 650ms 一颗算，
 * 用户全程只看到头灯挪了一格，跟没动没有区别。240ms 一颗合一圈 1.44 秒，
 * 短请求也能看出这是在转。
 */
private const val BULB_VERIFY_STEP_MILLIS = 240

/**
 * 进场扫几遍。
 *
 * 一遍就停 —— 头灯走到最后一颗时整排都还亮着（见 [BULB_COUNT]），那一刻就是这台机器的
 * 开机画面，再扫一遍只是重复。之后整排按各自余温冷掉，见 [BULB_TAIL_BULBS]。
 */
private const val BULB_ENTRY_SWEEPS = 1

/**
 * 灯丝升温走完几颗的距离。头灯扫过来不是瞬间到最亮：白炽灯丝有热惯性，
 * 亮度要爬一小段。0.3 颗合约 98ms，正好是「灯泡不硬切」看得出来的那一档。
 */
internal const val BULB_RISE_BULBS = 0.3f

/**
 * 灯丝降温的时间常数，单位是「颗」。亮度按 exp(-t/τ) 衰减，不是线性拉到零 ——
 * 线性衰减会在尾巴末端突然断掉，指数衰减才是灯丝散热的样子。
 * 1.6 颗时衰到 53%、3 颗时 15%、5 颗时 4%，尾巴拖四五颗长。
 */
internal const val BULB_DECAY_BULBS = 1.6f

/**
 * 进场跑完后额外空转几颗的距离，让尾巴自己冷掉。
 *
 * 没有这一段就得在最后一圈末尾直接熄灯，那一瞬间还亮着的四五颗会同时消失 ——
 * 这是「暗下去要逐渐暗」最容易破功的地方，跑得再准也毁在最后一帧。
 */
private const val BULB_TAIL_BULBS = 6f

/**
 * 玻璃亮度的感知校正指数。
 *
 * 余温直接当透明度用，压在暗底上会比数值看着暗得多：装机截图上余温 0.53 的那颗
 * 只剩两三成观感，一条本该三四颗长的尾巴看着只有一颗半。取 0.6 次幂把中段抬起来 ——
 * 0.53 抬到 0.68、0.28 抬到 0.45、0.15 抬到 0.31，尾巴上三四颗都看得出在冷。
 *
 * 只校正透明度，不校正颜色：色温该跟着真实余温走，抬了颜色红档就提前出现。
 */
private const val BULB_ALPHA_GAMMA = 0.6f

/**
 * 电影院取票机。面板上按自绘数字键盘输入 6 位取票码，按通栏「取票」键，票从底部出票口打印出来。
 *
 * 自上而下：跑马灯灯泡、铭牌与喇叭网、像素屏、六格取票码、键盘、取票键、票卷窗与出票口。
 * 状态文案和错误判定全在调用方算好，这里只负责画机器、把按键抛回去。
 *
 * **机壳是自绘的，不是毛玻璃。** 原先整台机器是一层 haze，于是背景的爆米花能从机壳里
 * 透出来 —— 取票机是台设备，不是一块玻璃。所以这个组合体不接 [HazeState]，
 * 也不吃「玻璃/模糊」那项设置：材质就是它的身份，跟 SwiftiePalette 同理。
 * 配色全部来自 Color.kt 的 Machine* 常量，一个 `colorScheme` 槽位都不读。
 *
 * @param code 已输入的数字串，长度 0..6，调用方保证只含数字
 * @param codeDescription 六格整体的读屏文案，为 null 时按已输入位数自动生成。
 *   已取票态屏上那串是占位符不是真码，念它没有意义，由调用方给一句实话
 * @param statusText 像素屏第一行的短状态，已是最终文案
 * @param detailText 像素屏第二行的完整引导句，为 null 时第二行留空。
 *   这句以前印在机器外面，搬进屏里是因为屏本来就装得下 —— 一行约 25 字（412dp 屏）
 *   到 17 字（360dp 屏），而最长的那条引导是 22 字
 * @param keypadEnabled 已取票态整块键盘淡成残影并从读屏树里摘掉，但仍占着面板 ——
 *   机器不该只剩半截
 * @param phase 机器此刻处于哪一档，见 [MachinePhase]。屏上墨色、六格抖动、取票键上的进度圈、
 *   顶边那排灯全从它派生：原先是三个互不排斥的布尔各自判一遍，加一档状态很容易只改到其中一处
 * @param ticketSlot 出票口里的内容，票从这里长出来
 */
@Composable
internal fun TicketMachine(
    code: String,
    statusText: String,
    detailText: String?,
    phase: MachinePhase,
    keypadEnabled: Boolean,
    submitEnabled: Boolean,
    onDigit: (Char) -> Unit,
    onBackspace: () -> Unit,
    onPaste: () -> Unit,
    onSubmit: () -> Unit,
    modifier: Modifier = Modifier,
    codeDescription: String? = null,
    ticketSlot: @Composable () -> Unit = {},
) {
    val isDarkTheme = isAppDarkTheme()
    val shellShape = RoundedCornerShape(ShellCorner)
    val shell = if (isDarkTheme) MachineShellDark else MachineShellLight
    // 机壳最亮的一点就是 shell 本身，往下压暗、不往上提亮，见 MachineShellShadeFraction
    val shellBrush = remember(shell) {
        Brush.verticalGradient(
            listOf(shell, lerp(shell, Color.Black, MachineShellShadeFraction))
        )
    }
    val noiseBrush = rememberMachineNoiseBrush()
    val focusRequester = remember { FocusRequester() }
    // 节点还没挂上时 requestFocus 会抛；争取不到焦点只是少了外接键盘，触屏输入照旧
    LaunchedEffect(Unit) { runCatching { focusRequester.requestFocus() } }

    Column(
        modifier = modifier
            .fillMaxWidth()
            // 自绘键盘拿不到系统输入法白送的外接键盘支持，得自己接一遍。
            // onKeyEvent 放在 focusable 之前，焦点无论落在机器本身还是里面的按钮都能冒泡上来
            .focusRequester(focusRequester)
            .onKeyEvent { event ->
                if (event.type != KeyEventType.KeyDown) return@onKeyEvent false
                when {
                    !keypadEnabled -> false
                    event.key == Key.Backspace -> {
                        onBackspace()
                        true
                    }
                    event.key == Key.Enter || event.key == Key.NumPadEnter -> {
                        if (submitEnabled && phase != MachinePhase.Verifying) {
                            onSubmit()
                            true
                        } else {
                            false
                        }
                    }
                    else -> {
                        val digit = digitFromKey(event.key)
                        if (digit != null) {
                            onDigit(digit)
                            true
                        } else {
                            false
                        }
                    }
                }
            }
            .focusable()
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                // 落地阴影。机器要站在牛皮纸上，没有这层它是一张贴纸。
                // clip = false：阴影本来就该落在机壳外面
                .shadow(8.dp, shellShape, clip = false)
                .clip(shellShape)
                .background(shellBrush, shellShape)
                // 铸件砂面。一层平铺噪点，压在渐变之上、折边之下
                .background(noiseBrush, shellShape)
                .drawBehind {
                    drawShellBevel()
                    drawShellScrews()
                }
                .border(1.dp, Color.Black.copy(alpha = 0.42f), shellShape)
                .padding(horizontal = 16.dp, vertical = ShellPaddingVertical)
        ) {
            // 顶边跑马灯。影院门头上的那串灯，是这台机器唯一的「氛围」构件；
            // 取票有动静时它同时是状态灯：验码中一直流水，对了整排绿，错了整排红
            MarqueeBulbs(signal = bulbSignalFor(phase), isDarkTheme = isDarkTheme)

            // 铭牌是压进机壳的一块凹槽加蚀刻小字。不直接印在机壳上：机壳带竖向渐变，
            // 凹槽给这行字一个可控的底，MachinePlateInk 上标的对比度才算得准。
            // 右边配一块喇叭网 —— 取票机会叫号，有网罩才像有喇叭
            //
            // 顶距 4dp 而不是 8dp：灯泡放大到 12dp 后灯座环已经吃掉了原先那段空白，
            // 留 8dp 会把整台机器再推高一截，一屏的余量不该花在这儿
            val plateShape = RoundedCornerShape(3.dp)
            Row(
                modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box(
                    modifier = Modifier
                        .clip(plateShape)
                        .background(
                            if (isDarkTheme) MachinePlateDark else MachinePlateLight,
                            plateShape
                        )
                        .border(1.dp, Color.Black.copy(alpha = 0.32f), plateShape)
                        .padding(horizontal = 8.dp, vertical = 3.dp)
                ) {
                    Text(
                        text = stringResource(R.string.login_personal_cinema_access),
                        style = MaterialTheme.typography.labelSmall.copy(
                            fontFamily = FontFamily.Monospace,
                            fontSize = 11.sp,
                            letterSpacing = 1.43.sp
                        ),
                        color = MachinePlateInk,
                        fontWeight = FontWeight.Bold
                    )
                }
                Spacer(modifier = Modifier.width(10.dp))
                SpeakerGrille()
            }

            val displayShape = RoundedCornerShape(4.dp)
            val displayInk = if (phase == MachinePhase.Failed) {
                MachineDisplayInkError
            } else {
                MachineDisplayInk
            }
            // 两行合成一个语义节点整体播报：报错时两行本来就是一句完整的话
            // （「网络不通」+「网络连接失败，请检查网络后重试。」），分开念会变成两条互相重复的通知
            val displaySpeech = listOfNotNull(statusText, detailText).joinToString("，")
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 10.dp)
                    // 屏内文案长短不一，不锁死高度整台机器会随状态跳动
                    .height(DisplayHeight)
                    .clip(displayShape)
                    .background(
                        if (isDarkTheme) MachineDisplayWellDark else MachineDisplayWellLight,
                        displayShape
                    )
                    .border(1.dp, Color.Black.copy(alpha = 0.55f), displayShape)
                    .padding(horizontal = 10.dp, vertical = 6.dp)
                    .semantics(mergeDescendants = true) { contentDescription = displaySpeech },
                contentAlignment = Alignment.CenterStart
            ) {
                Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
                    // 第一行是主角。字号不能再往上加档：像素字体走 12px 网格，
                    // pixelFontSize 向下取整到网格倍数，再高一档中文会顶右边缘
                    Text(
                        text = statusText,
                        style = pixelDisplayStyle(pixelFontSize(16.dp)),
                        color = displayInk,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    // 第二行是注释，降一档到 12px 网格：窄屏上也能装约 23 字，
                    // 现有最长的引导文案是 22 字。maxLines 留 1 是兜底，以后加长文案会被截断而不是把屏撑破
                    if (detailText != null) {
                        Text(
                            text = detailText,
                            style = pixelDisplayStyle(pixelFontSize(12.dp)),
                            color = displayInk.copy(alpha = 0.78f),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }
            }

            // 错误时六格整体左右抖两下。key 里带 statusText 是为了同一类错误连续发生两次也能重抖
            val shake = remember { Animatable(0f) }
            LaunchedEffect(phase, statusText) {
                if (phase == MachinePhase.Failed) {
                    listOf(-6f, 6f, -4f, 4f, 0f).forEach { target ->
                        shake.animateTo(target, tween(durationMillis = 45))
                    }
                } else {
                    // 抖动途中错误被清掉会把偏移留在半路上，复位一次
                    shake.snapTo(0f)
                }
            }
            // 自绘键盘拿不到系统输入法白送的读屏播报，六格合成一个语义节点整体报。
            // 数字之间加空格是故意的：连读「492013」会被念成一个大数字
            val progressDescription = codeDescription ?: stringResource(
                R.string.machine_code_progress,
                code.map { it }.joinToString(" "),
                CODE_LENGTH - code.length,
            )
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 12.dp)
                    .offset { IntOffset(shake.value.roundToInt(), 0) }
                    .semantics(mergeDescendants = true) {
                        contentDescription = progressDescription
                    },
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                repeat(CODE_LENGTH) { index ->
                    CodeCell(
                        digit = code.getOrNull(index),
                        // 已取票态没有当前输入位，光标自然不出现，也就不闪
                        showCaret = index == code.length &&
                            code.length < CODE_LENGTH &&
                            keypadEnabled
                    )
                }
            }

            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 14.dp)
                    // 已取票后键盘不再是控件，只是机器的一部分：淡成残影，并整块从读屏树里摘掉。
                    // 摘掉是因为 12 个键此时全是装饰，念一遍「1 2 3 4…」纯属噪音；
                    // 留 testTag 是为了测试仍能确认它没被删掉 —— 机器不该只剩半截。
                    // 0.30 而不是更低：机壳和键帽都是不透明的暖棕，再淡键盘就整块消失，
                    // 那就等于把键盘删了
                    .then(
                        if (keypadEnabled) {
                            Modifier.testTag(MACHINE_KEYPAD_TAG)
                        } else {
                            Modifier
                                .alpha(GHOST_KEYPAD_ALPHA)
                                .clearAndSetSemantics { testTag = MACHINE_KEYPAD_TAG }
                        }
                    ),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                listOf("123", "456", "789").forEach { rowDigits ->
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        rowDigits.forEach { digit ->
                            DigitKey(digit = digit, enabled = keypadEnabled, onClick = { onDigit(digit) })
                        }
                    }
                }
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    PasteKey(enabled = keypadEnabled, onClick = onPaste)
                    DigitKey(digit = '0', enabled = keypadEnabled, onClick = { onDigit('0') })
                    BackspaceKey(enabled = keypadEnabled, onClick = onBackspace)
                }
            }
            Button(
                onClick = onSubmit,
                enabled = submitEnabled && phase != MachinePhase.Verifying,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 12.dp)
                    .height(48.dp),
                shape = RoundedCornerShape(10.dp),
                // 整台机器只有这一处上赭红：主动作值得一个强调色，
                // 铭牌、六格、键面全走中性墨色，accent 撒得到处都是就不再是强调
                colors = ButtonDefaults.buttonColors(
                    containerColor = LoginTitleInk,
                    contentColor = LoginPaperLight,
                    // 禁用态跟着机壳走，不能再用原先那对浅灰 —— 深色档机壳上会亮成一块白条
                    disabledContainerColor = lerp(shell, Color.Black, 0.22f),
                    disabledContentColor = MachineCodeInk.copy(alpha = 0.55f)
                )
            ) {
                if (phase == MachinePhase.Verifying) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(20.dp),
                        strokeWidth = 2.dp,
                        color = LoginPaperLight
                    )
                } else {
                    Text(
                        text = stringResource(R.string.machine_submit),
                        fontFamily = PixelFontFamily,
                        fontSize = pixelFontSize(18.dp)
                    )
                }
            }

            // 出票口那一行：左边一个票卷窗，右边是出票缝。
            // 缝没有按「靠右占 62%」缩窄 —— 票是全宽的，缝比票窄就不像票从这里出来
            val slotShape = RoundedCornerShape(3.dp)
            Row(
                modifier = Modifier.fillMaxWidth().padding(top = 14.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                TicketRollWindow()
                Spacer(modifier = Modifier.width(8.dp))
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .height(10.dp)
                        .clip(slotShape)
                        .background(MachineSlotWall, slotShape)
                        // 上齿边压暗、下齿边提亮，凹槽才有厚度；两条线各内移半个线宽避免被裁掉
                        .drawBehind {
                            val stroke = 1.dp.toPx()
                            drawLine(
                                color = Color.Black.copy(alpha = 0.35f),
                                start = Offset(0f, stroke / 2f),
                                end = Offset(size.width, stroke / 2f),
                                strokeWidth = stroke
                            )
                            drawLine(
                                color = Color.White.copy(alpha = 0.18f),
                                start = Offset(0f, size.height - stroke / 2f),
                                end = Offset(size.width, size.height - stroke / 2f),
                                strokeWidth = stroke
                            )
                        }
                )
            }
            // 票紧贴凹槽下沿，中间不留 padding，看上去是从槽里长出来的
            ticketSlot()
        }
    }
}

/**
 * 点阵屏那两行的排版。
 *
 * 点阵字体的 ascent/descent 留白很宽，再叠上 Compose 默认的 includeFontPadding，
 * 一行 48px 的字实际要占掉 27dp —— 两行加起来撑破 52dp 的屏窗，装机截图上
 * 第二行只露出上半截。这里把 font padding 关掉、行高按字号钉死、并让行高在上下均分。
 *
 * 行高由字号乘出来，跟 [pixelFontSize] 一样与系统字号倍率无关：
 * 屏窗高度是锁死的，行高跟着系统字号涨会重新把它撑破。
 */
@Composable
private fun pixelDisplayStyle(size: TextUnit): TextStyle = TextStyle(
    fontFamily = PixelFontFamily,
    fontSize = size,
    lineHeight = size * DISPLAY_LINE_HEIGHT_RATIO,
    platformStyle = PlatformTextStyle(includeFontPadding = false),
    lineHeightStyle = LineHeightStyle(
        alignment = LineHeightStyle.Alignment.Center,
        trim = LineHeightStyle.Trim.Both
    )
)

/** 点阵屏行高相对字号的倍率。1.15 留出一点行距，又不至于把两行顶出屏窗。 */
private const val DISPLAY_LINE_HEIGHT_RATIO = 1.15f

/**
 * 机壳顶边的一串跑马灯，兼取票状态灯。
 *
 * 平常不是一直在跑：进场扫 [BULB_ENTRY_SWEEPS] 遍就熄掉。这一屏的主任务是输入六位码，
 * 常驻动画会一直分走注意力。
 *
 * 三种情形接管这排灯，见 [BulbSignal]：验码期间一直流水（[BULB_VERIFY_STEP_MILLIS] 一颗，
 * 比进场快），取票码对了整排亮绿，错了整排亮红。后两档不扫动 ——
 * 结论不该看起来还在处理；反过来，还在处理的时候就该扫。
 *
 * 系统「动画时长」调成 0（开发者选项或省电模式）时跑马灯一颗不亮，
 * 但绿灯红灯照亮，只是不做升温冷却直接到位：那个开关的意思是「别给我动画」，
 * 不是「别告诉我取票成功了没有」。验码那一档在这种设置下也不亮 ——
 * 它是进度指示，而取票键上的那个进度圈已经把同样的事说了。
 *
 * 相位读在 `drawBehind` 里，不是拿来算 [BULB_COUNT] 个子组合体：这样每帧只重绘不重组。
 * 灯泡的明暗模型见 [filamentHeat]，画法见 [drawBulbLight]。
 */
@Composable
private fun MarqueeBulbs(signal: BulbSignal, isDarkTheme: Boolean) {
    val context = LocalContext.current
    val animationsOn = remember(context) { animatorDurationScale(context) > 0f }
    val unlit = if (isDarkTheme) MachineBulbUnlitDark else MachineBulbUnlitLight
    val phase = remember { Animatable(BULB_ALL_OFF) }
    // 头灯还允许点亮到哪一颗为止。相位跑过它之后不再有新灯被点亮，
    // 还亮着的那几颗按各自的余温继续冷 —— 这就是尾巴自然熄掉的实现
    var lightingCeiling by remember { mutableFloatStateOf(0f) }
    // 整排信号灯的亮度。绿灯红灯没有「头灯」，整排是同一个亮度一起升降
    val signalHeat = remember { Animatable(0f) }
    // 亮度降回 0 之前不能把颜色撤掉，否则整排是瞬间消失而不是慢慢冷掉
    var shownGlow by remember { mutableStateOf<BulbGlow?>(null) }
    LaunchedEffect(signal, animationsOn) {
        val glow = signal.glow
        if (glow != null) shownGlow = glow
        if (!animationsOn) {
            // 关掉系统动画时跑马灯一颗不亮，但绿灯红灯照亮：那是取票结果的反馈，不是装饰。
            // 只是不做升温和冷却，直接到位
            phase.snapTo(BULB_ALL_OFF)
            signalHeat.snapTo(if (glow == null) 0f else 1f)
            if (glow == null) shownGlow = null
            return@LaunchedEffect
        }
        if (glow != null) {
            // 信号灯期间不跑灯：整排是一个结论，头灯扫过去会把它读成「还在处理」
            phase.snapTo(BULB_ALL_OFF)
            signalHeat.animateTo(
                targetValue = 1f,
                animationSpec = tween(BULB_SIGNAL_RISE_MILLIS, easing = LinearEasing)
            )
            return@LaunchedEffect
        }
        // 只有真的还亮着才需要等它冷下去。Animatable 到目标值也照走完整个 tween，
        // 无条件 animateTo(0f) 会在本来就全灭的情况下白等 BULB_SIGNAL_FALL_MILLIS ——
        // 验码那一档等不起，一次验码常常还没这么久
        if (signalHeat.value > 0f) {
            signalHeat.animateTo(
                targetValue = 0f,
                animationSpec = tween(BULB_SIGNAL_FALL_MILLIS, easing = LinearEasing)
            )
        }
        shownGlow = null
        phase.snapTo(0f)
        if (signal == BulbSignal.Verifying) {
            // 一直流水到有结果为止。相位单调递增，不每遍回零：回零会把上一遍的尾巴
            // 一次抹掉，两遍的接缝看得出来
            lightingCeiling = Float.MAX_VALUE
            var sweep = 1
            while (true) {
                phase.animateTo(
                    targetValue = (BULB_COUNT * sweep).toFloat(),
                    animationSpec = tween(
                        durationMillis = BULB_VERIFY_STEP_MILLIS * BULB_COUNT,
                        easing = LinearEasing
                    )
                )
                sweep++
            }
        }
        val lit = (BULB_COUNT * BULB_ENTRY_SWEEPS).toFloat()
        lightingCeiling = lit
        phase.animateTo(
            targetValue = lit + BULB_TAIL_BULBS,
            animationSpec = tween(
                // 空转那一段和正常跑灯同速，尾巴才是按原速度一颗一颗冷下去
                durationMillis = ((lit + BULB_TAIL_BULBS) * BULB_STEP_MILLIS).toInt(),
                easing = LinearEasing
            )
        )
        phase.snapTo(BULB_ALL_OFF)
    }
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = BulbRowInset)
            .height(BulbSize)
            .drawBehind {
                drawMarqueeBulbs(
                    phase = phase.value,
                    lightingCeiling = lightingCeiling,
                    unlit = unlit,
                    signalHeat = signalHeat.value,
                    signalGlow = shownGlow
                )
            }
    )
}

/** 相位取这个值等于「全灭」：负数在 [drawMarqueeBulbs] 里直接走没有头灯的分支。 */
private const val BULB_ALL_OFF = -1f

/**
 * 一档灯色：暗时的余烬端、满亮端、灯丝色。
 *
 * 玻璃色按亮度在前两端之间插值。[filament] 是玻璃最里那一点白热灯丝，
 * 信号灯不给（传 null）—— 绿灯红灯是磨砂罩子里的一片色，露出一根白丝反而像坏了。
 */
internal data class BulbGlow(val ember: Color, val lit: Color, val filament: Color?)

/** 跑马灯的白炽档。头灯扫过去时用的就是这一档。 */
private val ChaseGlow = BulbGlow(MachineBulbEmber, MachineBulbLit, Color.White)

/**
 * 跑马灯当前该表达什么。
 *
 * 四态互斥。整排绿或整排红是一个结论，这种时候不该还有头灯在扫 ——
 * 扫动会把已经出来的结果读成「还在处理」；反过来，真的在处理的时候就该扫。
 */
internal enum class BulbSignal(internal val glow: BulbGlow?) {
    /** 平常。进场扫一遍就冷掉，见 [BULB_ENTRY_SWEEPS]。 */
    Sweep(null),

    /** 正在验取票码。黄灯一直流水，直到有结果为止。 */
    Verifying(null),

    /** 取票码对了，整排亮绿。 */
    Success(BulbGlow(MachineBulbEmberGreen, MachineBulbLitGreen, filament = null)),

    /** 取票码错了（或剪贴板里没有码），整排亮红。 */
    Failure(BulbGlow(MachineBulbEmberRed, MachineBulbLitRed, filament = null)),
}

/**
 * 取票机此刻处于哪一档。
 *
 * 存在的理由是这一屏有四个消费者要说同一件事：像素屏第一行、第二行、屏上墨色（连带六格抖动）、
 * 顶边那排灯。四个入参（验码中、有错、正在打印、已取票）互不排斥，四档互斥，
 * 所以谁盖过谁本身就是设计的一部分 —— 而它们原先各写一遍 `when`，
 * 加一档状态只改其中一处，就会出现「屏上说正在核对、灯却红着」这种自相矛盾的画面。
 */
internal enum class MachinePhase { Verifying, Failed, Printing, Collected, Ready }

/**
 * [isLoading] 排在最前，因为请求回来的那一刻它转 false，同时
 * [hasError] / [isPrinting] 里恰好有一个转 true —— 验码和结果的交接没有空档。
 *
 * 反过来把结果排在前面，上一次的结论会盖住这一次的验码：粘贴失败那个标志不随新请求
 * 清掉（`AuthViewModel.activate()` 只清 `error`），用户粘贴失败后手输六位再按取票，
 * 屏上会一直红着「剪贴板没码」，看不出机器已经在验。
 */
internal fun machinePhaseOf(
    isLoading: Boolean,
    hasError: Boolean,
    isPrinting: Boolean,
    isCollected: Boolean,
): MachinePhase = when {
    isLoading -> MachinePhase.Verifying
    hasError -> MachinePhase.Failed
    isPrinting -> MachinePhase.Printing
    isCollected -> MachinePhase.Collected
    else -> MachinePhase.Ready
}

/**
 * 每一档配哪种灯。
 *
 * 已取票和平常同归进场那一遍：票已经静态停在出票口了，灯再喊一次是重复
 * —— 而这一屏的主任务是输入六位码，常驻动画一直分走注意力。
 */
internal fun bulbSignalFor(phase: MachinePhase): BulbSignal = when (phase) {
    MachinePhase.Verifying -> BulbSignal.Verifying
    MachinePhase.Failed -> BulbSignal.Failure
    MachinePhase.Printing -> BulbSignal.Success
    MachinePhase.Collected, MachinePhase.Ready -> BulbSignal.Sweep
}

/**
 * 整排信号灯升温的时长。
 *
 * 比单颗灯丝的 [BULB_RISE_BULBS]（约 98ms）慢一档：整排一起亮，太快就是一次硬切，
 * 看不出「灯亮起来了」这个动作。
 */
private const val BULB_SIGNAL_RISE_MILLIS = 190

/** 整排信号灯冷却的时长。远长于升温，跟单颗灯丝一样是快亮慢灭。 */
private const val BULB_SIGNAL_FALL_MILLIS = 760

/**
 * 画一排白炽灯泡。
 *
 * 每颗四层，从外到内：灯座环（暗，恒在）、玻璃壳、亮起来的光、灯丝。
 * 灭着的灯泡也不是一个纯色圆点 —— 左上角留一点玻璃反光，不然那是个洞不是个灯泡。
 *
 * @param phase 头灯当前扫到的位置，单位是「颗」，单调递增；负数表示全灭
 * @param lightingCeiling 头灯最多点亮到哪个相位。相位超过它之后不再点新灯，
 *   已亮的按余温继续衰减，于是尾巴一颗一颗冷掉而不是集体断电
 * @param signalHeat 整排信号灯的亮度 0..1。大于 0 时整排同亮，不再有头灯
 * @param signalGlow 信号灯的那档颜色。亮度降回 0 之前不撤，否则整排是瞬间消失
 */
private fun DrawScope.drawMarqueeBulbs(
    phase: Float,
    lightingCeiling: Float,
    unlit: Color,
    signalHeat: Float,
    signalGlow: BulbGlow?,
) {
    val radius = BulbSize.toPx() / 2f
    val step = (size.width - 2 * radius) / (BULB_COUNT - 1)
    val centerY = size.height / 2f
    // 头灯只能停在 ceiling 上，但 phase 继续往前走 —— 两者的差就是尾巴额外冷掉的时长
    val head = if (phase < 0f) null else minOf(phase, lightingCeiling)
    val signalOn = signalGlow != null && signalHeat > 0.01f
    for (index in 0 until BULB_COUNT) {
        val center = Offset(radius + step * index, centerY)
        drawBulbSocket(center, radius)
        drawCircle(unlit, radius = radius, center = center)
        drawBulbGlassSheen(center, radius)
        if (signalOn) {
            drawBulbLight(center, radius, signalHeat, signalGlow!!)
            continue
        }
        if (head == null) continue
        // 头灯最近一次经过这颗灯是在多少「颗」之前。用绝对相位减去最近一次经过的相位，
        // 不取模：取模会在圈与圈的接缝上把尾巴截断
        val lastPass = index + floor((head - index) / BULB_COUNT) * BULB_COUNT
        if (lastPass < 0f) continue // 第一圈里头灯还没走到的灯泡，本来就没亮过
        val heat = filamentHeat(phase - lastPass)
        if (heat > 0.01f) {
            drawBulbLight(center, radius, heat, ChaseGlow)
        }
    }
}

/**
 * 灯丝在头灯经过 [elapsedBulbs] 颗之后的余温，0..1。
 *
 * 先线性升温到满，再指数降温。升温段远短于降温段，这个不对称就是白炽灯和 LED
 * 在观感上的全部区别：LED 亮灭都是方波，灯丝是快亮慢灭。
 */
internal fun filamentHeat(elapsedBulbs: Float): Float = when {
    elapsedBulbs < 0f -> 0f
    elapsedBulbs < BULB_RISE_BULBS -> elapsedBulbs / BULB_RISE_BULBS
    else -> exp(-(elapsedBulbs - BULB_RISE_BULBS) / BULB_DECAY_BULBS)
}

/** 灯座：玻璃外面一圈暗环加一道下缘高光，让灯泡看着是拧进机壳的。 */
private fun DrawScope.drawBulbSocket(center: Offset, radius: Float) {
    val socket = radius * BULB_SOCKET_RATIO
    drawCircle(Color.Black.copy(alpha = 0.45f), radius = socket, center = center)
    drawCircle(
        color = Color.White.copy(alpha = 0.14f),
        radius = socket,
        center = center,
        style = Stroke(width = 1.dp.toPx())
    )
}

/** 玻璃反光。左上一小点白 —— 玻璃壳灭着的时候也是亮面材质。 */
private fun DrawScope.drawBulbGlassSheen(center: Offset, radius: Float) {
    drawCircle(
        color = Color.White.copy(alpha = 0.20f),
        radius = radius * 0.22f,
        center = Offset(center.x - radius * 0.34f, center.y - radius * 0.34f)
    )
}

/**
 * 亮起来的那几层：外面三圈溢到机壳上的光晕，中间是随亮度变色的玻璃，最里是灯丝。
 *
 * 光晕用三个同心实心圆而不是 radialGradient：渐变 Brush 每颗每帧都要新建对象，
 * 三圈叠出来的过渡在这个尺寸的灯泡上已经看不出台阶。
 *
 * 透明度走 [BULB_ALPHA_GAMMA] 校正过的 glow，颜色仍按原始亮度在 [BulbGlow.ember]
 * 和 [BulbGlow.lit] 之间插值 —— 白炽档冷下去的灯丝先转橙再转红，只调透明度会像
 * 有人在拉调光旋钮；信号灯档同理，整排绿灯升起来是先深绿后亮绿。
 */
private fun DrawScope.drawBulbLight(center: Offset, radius: Float, heat: Float, glow: BulbGlow) {
    val alpha = heat.pow(BULB_ALPHA_GAMMA)
    drawCircle(glow.lit.copy(alpha = 0.08f * alpha), radius = radius * 2.4f, center = center)
    drawCircle(glow.lit.copy(alpha = 0.13f * alpha), radius = radius * 1.7f, center = center)
    drawCircle(glow.lit.copy(alpha = 0.20f * alpha), radius = radius * 1.25f, center = center)
    drawCircle(
        color = lerp(glow.ember, glow.lit, heat).copy(alpha = alpha),
        radius = radius,
        center = center
    )
    // 灯丝只在够热的时候看得见，所以用原始亮度的平方：尾巴上剩的是一团红光，不是一根丝
    glow.filament?.let { filament ->
        drawCircle(
            color = filament.copy(alpha = heat * heat * 0.85f),
            radius = radius * 0.34f,
            center = center
        )
    }
}

/** 喇叭网。几条横缝 —— 取票机会叫号，有网罩才像有喇叭。 */
@Composable
private fun SpeakerGrille() {
    Box(
        modifier = Modifier
            .size(width = 28.dp, height = 13.dp)
            .drawBehind {
                val slit = 1.5.dp.toPx()
                val pitch = slit + 2.dp.toPx()
                var y = slit / 2f
                while (y < size.height) {
                    drawLine(
                        color = Color.Black.copy(alpha = 0.34f),
                        start = Offset(0f, y),
                        end = Offset(size.width, y),
                        strokeWidth = slit
                    )
                    y += pitch
                }
            }
    )
}

/** 票卷窗。透过机壳上的小圆窗看见里面那卷还没打印的票纸。 */
@Composable
private fun TicketRollWindow() {
    Box(
        modifier = Modifier
            .size(18.dp)
            .drawBehind {
                val radius = size.minDimension / 2f
                drawCircle(MachineSlotWall)
                drawCircle(MachinePlateInk.copy(alpha = 0.72f), radius = radius * 0.62f)
                drawCircle(MachineSlotWall, radius = radius * 0.22f)
                drawCircle(
                    color = Color.Black.copy(alpha = 0.55f),
                    radius = radius - 0.5.dp.toPx(),
                    style = Stroke(width = 1.dp.toPx())
                )
            }
    )
}

/**
 * 机壳的砂面颗粒，见 [NOISE_TILE_PX] 那一组常量的说明。
 *
 * 位图只生成一次，之后由 shader 平铺；不是每帧铺几千个点。
 */
@Composable
private fun rememberMachineNoiseBrush(): ShaderBrush = remember {
    val random = Random(NOISE_SEED)
    val pixels = IntArray(NOISE_TILE_PX * NOISE_TILE_PX) {
        val level = random.nextInt(GRAY_LEVELS)
        (NOISE_ALPHA shl 24) or (level shl 16) or (level shl 8) or level
    }
    val bitmap = Bitmap.createBitmap(
        pixels,
        NOISE_TILE_PX,
        NOISE_TILE_PX,
        Bitmap.Config.ARGB_8888
    )
    ShaderBrush(ImageShader(bitmap.asImageBitmap(), TileMode.Repeated, TileMode.Repeated))
}

/** 8 位通道的取值个数。噪点在整个灰阶上取样，只靠 [NOISE_ALPHA] 压住强度。 */
private const val GRAY_LEVELS = 256

/**
 * 机壳折边。上缘一道高光、下缘一道暗边，金属才有厚度。
 *
 * 两条线各内移半个线宽：线宽居中在 y = 0 会被 clip 掉一半，1.dp 只剩 0.5dp。
 * 左右各内缩一个圆角的量，画满整宽会在圆角处变成一条生硬的直边。
 */
private fun DrawScope.drawShellBevel() {
    val stroke = 1.dp.toPx()
    val inset = ShellCorner.toPx()
    drawLine(
        color = Color.White.copy(alpha = 0.26f),
        start = Offset(inset, stroke / 2f),
        end = Offset(size.width - inset, stroke / 2f),
        strokeWidth = stroke
    )
    drawLine(
        color = Color.Black.copy(alpha = 0.34f),
        start = Offset(inset, size.height - stroke / 2f),
        end = Offset(size.width - inset, size.height - stroke / 2f),
        strokeWidth = stroke
    )
}

/**
 * 四角螺丝：一个暗坑、一圈高光、一个十字槽。「这台机器是拧起来的」最省事的一笔。
 *
 * 纵向圆心跟顶边跑马灯的灯泡圆心对齐：螺丝和灯泡都是钉在机壳边框那一圈上的圆点，
 * 两排错开几 dp 就像装歪了。所以这里不写死一个纵向 inset，而是拿内边距加半个灯泡直径算出来。
 */
private fun DrawScope.drawShellScrews() {
    val radius = ScrewSize.toPx() / 2f
    val insetX = ScrewInsetHorizontal.toPx() + radius
    val insetY = ShellPaddingVertical.toPx() + BulbSize.toPx() / 2f
    val slot = radius * 0.55f
    val stroke = 1.dp.toPx()
    val centers = listOf(
        Offset(insetX, insetY),
        Offset(size.width - insetX, insetY),
        Offset(insetX, size.height - insetY),
        Offset(size.width - insetX, size.height - insetY),
    )
    for (center in centers) {
        drawCircle(Color.Black.copy(alpha = 0.40f), radius = radius, center = center)
        drawCircle(
            color = Color.White.copy(alpha = 0.20f),
            radius = radius,
            center = center,
            style = Stroke(width = stroke)
        )
        drawLine(
            color = Color.Black.copy(alpha = 0.55f),
            start = Offset(center.x - slot, center.y),
            end = Offset(center.x + slot, center.y),
            strokeWidth = stroke
        )
        drawLine(
            color = Color.Black.copy(alpha = 0.55f),
            start = Offset(center.x, center.y - slot),
            end = Offset(center.x, center.y + slot),
            strokeWidth = stroke
        )
    }
}

/**
 * 取票码单格。只在底部压一道横线：画整框会变成六个输入框，取票机面板上是压印的横线。
 *
 * 横线用白而不是赭红：赭红压在机壳上只有 1.28:1，画上去等于没画。
 *
 * @param digit 该位已输入的数字，未输入为 null
 * @param showCaret 是否是当前输入位，闪烁光标只在这一格出现
 */
@Composable
private fun RowScope.CodeCell(digit: Char?, showCaret: Boolean) {
    val underline = Color.White.copy(alpha = if (digit != null) 0.85f else 0.42f)
    Box(
        modifier = Modifier
            .weight(1f)
            .height(CodeCellHeight)
            .drawBehind {
                val stroke = 2.dp.toPx()
                drawLine(
                    color = underline,
                    start = Offset(0f, size.height - stroke / 2f),
                    end = Offset(size.width, size.height - stroke / 2f),
                    strokeWidth = stroke
                )
            },
        contentAlignment = Alignment.Center
    ) {
        when {
            digit != null -> Text(
                text = digit.toString(),
                fontFamily = PixelFontFamily,
                fontSize = pixelFontSize(24.dp),
                color = MachineCodeInk
            )
            showCaret -> BlinkingCaret(color = MachineCodeInk)
        }
    }
}

/** 当前输入位的闪烁竖条。光标换位时整个节点重建，闪烁从亮开始，刚按完键光标一定看得见。 */
@Composable
private fun BlinkingCaret(color: Color) {
    val transition = rememberInfiniteTransition(label = "machine_caret")
    val caretAlpha by transition.animateFloat(
        initialValue = 1f,
        targetValue = 0f,
        animationSpec = infiniteRepeatable(tween(durationMillis = 500), RepeatMode.Reverse),
        label = "machine_caret_alpha"
    )
    Box(
        modifier = Modifier
            .width(2.dp)
            .height(20.dp)
            // 闪烁只改 alpha，走 graphicsLayer 留在绘制阶段，不每帧触发重组
            .graphicsLayer { alpha = caretAlpha }
            .background(color)
    )
}

@Composable
private fun RowScope.DigitKey(digit: Char, enabled: Boolean, onClick: () -> Unit) {
    // 数字键不给 contentDescription：数字文本本身就是读屏内容
    MachineKey(enabled = enabled, onClick = onClick) {
        Text(
            text = digit.toString(),
            fontFamily = PixelFontFamily,
            fontSize = pixelFontSize(28.dp),
            color = machineKeyInk()
        )
    }
}

@Composable
private fun RowScope.PasteKey(enabled: Boolean, onClick: () -> Unit) {
    MachineKey(enabled = enabled, onClick = onClick) {
        // 中文比数字宽，字号得比数字键降一档才不顶键帽边缘
        Text(
            text = stringResource(R.string.machine_key_paste),
            fontFamily = PixelFontFamily,
            fontSize = pixelFontSize(16.dp),
            color = machineKeyInk()
        )
    }
}

@Composable
private fun RowScope.BackspaceKey(enabled: Boolean, onClick: () -> Unit) {
    MachineKey(enabled = enabled, onClick = onClick) {
        Icon(
            imageVector = Icons.AutoMirrored.Outlined.Backspace,
            contentDescription = stringResource(R.string.machine_key_backspace_desc),
            tint = machineKeyInk(),
            modifier = Modifier.size(22.dp)
        )
    }
}

/** 键面墨色。原先读 `colorScheme.onSurface`，于是键面颜色跟着主题走、跟机器不是一套。 */
@Composable
private fun machineKeyInk(): Color =
    if (isAppDarkTheme()) MachineKeyInkDark else MachineKeyInkLight

/** 机器按键键帽。方角、按下缩到 0.92、上缘一道高光，摸上去像塑料按键而不是玻璃卡片。 */
@Composable
private fun RowScope.MachineKey(
    enabled: Boolean,
    onClick: () -> Unit,
    content: @Composable () -> Unit
) {
    val interactionSource = remember { MutableInteractionSource() }
    val isPressed by interactionSource.collectIsPressedAsState()
    val scale by animateFloatAsState(
        targetValue = if (isPressed) 0.92f else 1f,
        label = "machine_key_scale"
    )
    val isDarkTheme = isAppDarkTheme()
    val keycap = if (isDarkTheme) MachineKeycapDark else MachineKeycapLight
    val shape = RoundedCornerShape(8.dp)
    Box(
        modifier = Modifier
            .weight(1f)
            .height(KeyHeight)
            .scale(scale)
            // 键帽自己不再按 enabled 压暗：整块键盘的淡出由外层那一处 alpha 统一做，
            // 两处相乘会把键盘压到几乎看不见
            .clip(shape)
            // 键帽是不透明塑料，不再是压在机壳上的一层白雾：
            // 半透明键帽会把机壳的噪点透上来，键面数字读起来发脏
            .background(keycap, shape)
            .background(
                Brush.verticalGradient(
                    listOf(Color.White.copy(alpha = 0.20f), Color.Transparent)
                )
            )
            // 下缘压一道暗边，键帽才有厚度
            .drawBehind {
                val stroke = 1.5.dp.toPx()
                drawLine(
                    color = Color.Black.copy(alpha = 0.28f),
                    start = Offset(0f, size.height - stroke / 2f),
                    end = Offset(size.width, size.height - stroke / 2f),
                    strokeWidth = stroke
                )
            }
            .border(
                width = 1.dp,
                color = Color.Black.copy(alpha = if (isDarkTheme) 0.30f else 0.18f),
                shape = shape
            )
            .clickable(
                interactionSource = interactionSource,
                indication = ripple(color = LoginTitleInk),
                enabled = enabled,
                onClick = onClick
            ),
        contentAlignment = Alignment.Center
    ) {
        content()
    }
}

/** 外接键盘按键到数字的映射。主键区和小键盘都收，两者都可能被用来输码。 */
private fun digitFromKey(key: Key): Char? = when (key) {
    Key.Zero, Key.NumPad0 -> '0'
    Key.One, Key.NumPad1 -> '1'
    Key.Two, Key.NumPad2 -> '2'
    Key.Three, Key.NumPad3 -> '3'
    Key.Four, Key.NumPad4 -> '4'
    Key.Five, Key.NumPad5 -> '5'
    Key.Six, Key.NumPad6 -> '6'
    Key.Seven, Key.NumPad7 -> '7'
    Key.Eight, Key.NumPad8 -> '8'
    Key.Nine, Key.NumPad9 -> '9'
    else -> null
}
