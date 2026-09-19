package com.tracktosearch.ui.screen.swiftie

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import com.tracktosearch.R
import com.tracktosearch.ui.screen.swiftie.bracelet.BRACELET_SETTLED_MS
import com.tracktosearch.ui.screen.swiftie.bracelet.SwiftieBracelet
import com.tracktosearch.ui.screen.swiftie.bracelet.braceletHeightFor

/**
 * 纪念页最下面那句话（无昵称时的整行回退）。
 *
 * 刻意**不本地化** —— 它是这张纪念品上印的字，不是界面文案；`swiftie_script`
 * （子集化的 Pacifico）也只有这一句和 `Taylor Swift` 的字母，换成中日韩会整行豆腐块。
 * 读屏用户拿不到它，所以另有一份本地化的 `R.string.swiftie_finale_a11y`。
 *
 * 有昵称时只把手写体的 `Me` 换成昵称（见 [SwiftieTagline]），「Taylor & 」与句尾
 * 用到的字形原句里都有，不用重跑子集脚本；昵称那一段走系统字体，不吃子集的限制。
 */
internal const val FINALE_TAGLINE = "Taylor & Me — forever & always."

/** 字号上限，**dp 当量**（见 [SwiftieTagline] 里为什么不是 sp）。 */
private val TAGLINE_MAX_SIZE = 22.dp

/** 两侧至少留出的空。 */
private val TAGLINE_SIDE_ROOM = 32.dp

/** 手链第一条链起步的时刻。整段入场嵌在签名段里 —— 笔还在写，珠子已经从两侧滚进来了。 */
private val BRACELET_ENTRY_START: Long =
    SwiftieTimeline.SIGNATURE_START + SwiftieTimeline.BRACELET_ENTRY_MS

/** 三条链全部停住的时刻。 */
private val BRACELET_ALL_SETTLED_AT: Long = BRACELET_ENTRY_START + BRACELET_SETTLED_MS

/** 手链停住之后文案才浮出来，不和还在滚的珠子抢注意力。 */
private const val TAGLINE_FADE_MS: Float = 800f

/** 签名与手链之间的间距。 */
private val SIGNATURE_GAP = 32.dp

/** 手链与文案之间的间距。最前那条垂到插槽底下只剩 10dp，所以这里不用给太多。 */
private val TAGLINE_GAP = 16.dp

/**
 * 终局：签名 + 手链 + 文案（Spec §5 的终局段、§7、§8）。
 *
 * 签名与手链**同场**：手链在签名写到 800ms 时就从两侧滚进来（见 `SwiftieTimeline`
 * 的 `BRACELET_ENTRY_MS`），所以这里不再有「签名段看完再看手链」那一段等待。
 *
 * 排在倒滑之前 —— 配乐末尾那句 Lover 要留给绽放（见 `SwiftieTimeline` 的类注释）。
 *
 * @param elapsedMs 序列全局已用毫秒。每一段自己减起点，段落之间不互相传时间
 * @param nickname 串在最前那条手链上的昵称，null 时只挂两条
 */
@Composable
fun SwiftieFinaleStage(
    elapsedMs: () -> Long,
    nickname: String?,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .windowInsetsPadding(WindowInsets.safeDrawing)
            // 整体偏上：给手链留出下垂的余地，也让签名落在视觉重心上（Spec §7）
            .padding(bottom = 48.dp)
            .then(finaleSemantics(nickname)),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        SwiftieSignature(
            elapsedInSignature = { elapsedMs() - SwiftieTimeline.SIGNATURE_START }
        )

        Spacer(modifier = Modifier.height(SIGNATURE_GAP))

        // 插槽高度按宽度算，见 braceletHeightFor —— 下垂量是宽度的比例
        BoxWithConstraints(modifier = Modifier.fillMaxWidth()) {
            SwiftieBracelet(
                elapsedInBracelet = { elapsedMs() - BRACELET_ENTRY_START },
                nickname = nickname,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(braceletHeightFor(maxWidth))
            )
        }

        Spacer(modifier = Modifier.height(TAGLINE_GAP))

        SwiftieTagline(
            nickname = nickname,
            modifier = Modifier
                .fillMaxWidth()
                .graphicsLayer {
                    alpha = ((elapsedMs() - BRACELET_ALL_SETTLED_AT) / TAGLINE_FADE_MS)
                        .coerceIn(0f, 1f)
                }
        )
    }
}

/**
 * 整幅纪念页读成一句话。
 *
 * 这一屏全是 `Spacer` + Canvas，没有一个可读的节点 —— 签名、手链、文案都得靠这条
 * `contentDescription` 才存在。昵称按用户输入的原样读，不用珠子上那份大写。
 */
@Composable
internal fun finaleSemantics(nickname: String?): Modifier {
    val label = if (nickname.isNullOrBlank()) {
        stringResource(R.string.swiftie_finale_a11y_plain)
    } else {
        stringResource(R.string.swiftie_finale_a11y, nickname)
    }
    return Modifier.semantics(mergeDescendants = true) { contentDescription = label }
}

/**
 * 那句手写体文案。
 *
 * 有昵称时把手写体的 `Me` 换成昵称：昵称是动态内容，子集字体里没有它的字形
 * （见 [SwiftieFonts.Script]），那一段换**系统字体加粗** —— 手链珠子上的昵称也是
 * 系统字体画的，两处一致；「Taylor & 」与句尾仍走 Pacifico。昵称取不到就整行
 * 回退 [FINALE_TAGLINE] 原句。
 *
 * 字号用 [rememberTextMeasurer] 按可用宽度反推（字号与排版宽成正比，量一次等比缩放），
 * 并且**换算成不随系统字号缩放的 sp**：这是一句装饰性的单行手写体，跟着字号放大只会
 * 被裁掉半句 —— 内容由 `contentDescription` 负责，读屏用户不吃这个亏。
 */
@Composable
internal fun SwiftieTagline(nickname: String?, modifier: Modifier = Modifier) {
    val density = LocalDensity.current
    val textMeasurer = rememberTextMeasurer()
    val tagline = taglineAnnotated(nickname)
    BoxWithConstraints(modifier = modifier) {
        // 上限 dp 换成 sp 当量（原文件约定，不随系统字号缩放的排版基准）
        val reference = with(density) { TAGLINE_MAX_SIZE.toSp() }
        val measuredPx = remember(tagline, reference) {
            textMeasurer.measure(tagline, TextStyle(fontSize = reference)).size.width
        }
        val availablePx = with(density) { (maxWidth - TAGLINE_SIDE_ROOM).toPx() }
        // 排版宽比可用宽窄时字号封在上限；放不下时按比例收到刚好放满
        val fontSize = if (measuredPx > 0) {
            val referencePx = with(density) { reference.toPx() }
            with(density) { (referencePx * (availablePx / measuredPx)).coerceAtMost(referencePx).toSp() }
        } else {
            reference
        }
        Text(
            text = tagline,
            style = TextStyle(
                fontFamily = SwiftieFonts.Script,
                fontSize = fontSize,
                color = SwiftiePalette.RoyalBlue,
                textAlign = TextAlign.Center
            ),
            maxLines = 1,
            modifier = Modifier.align(Alignment.Center)
        )
    }
}

/** 有昵称：「Taylor & 昵称 — forever & always.」；没有：整行原句。 */
private fun taglineAnnotated(nickname: String?): AnnotatedString =
    if (nickname.isNullOrBlank()) {
        AnnotatedString(FINALE_TAGLINE)
    } else {
        buildAnnotatedString {
            append("Taylor & ")
            withStyle(SpanStyle(fontFamily = FontFamily.SansSerif, fontWeight = FontWeight.Bold)) {
                append(nickname.trim())
            }
            append(" — forever & always.")
        }
    }
