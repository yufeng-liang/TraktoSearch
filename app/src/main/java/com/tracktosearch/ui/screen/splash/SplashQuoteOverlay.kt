package com.tracktosearch.ui.screen.splash

import android.graphics.Bitmap
import android.provider.Settings
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.ImageShader
import androidx.compose.ui.graphics.ShaderBrush
import androidx.compose.ui.graphics.TileMode
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import com.tracktosearch.R
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Locale
import java.util.Random

/**
 * 开屏「每日一句」台词层。
 *
 * 接在系统 SplashScreen 的场记板之后：场记板合板的瞬间这一层已经铺着同一张暖纸色，
 * 所以视觉上是同一块画面继续往下演，而不是两段动画拼接。
 *
 * 节奏见 [SplashQuoteTiming]：光晕扩散 → 海报浮起 → 台词逐行升起 → 出处淡入 →
 * 停留 5 秒 → 光晕散开同时整层淡出，露出下面已经组合好的主界面。
 * 任意时刻轻触屏幕直接跳到散开阶段。
 *
 * 逐步浮现只演给当天第一次看的人。同一天再进 App 时整页一次摊开：海报、台词、出处、
 * 印章从第一帧就都在，停留 3 秒后退场。逐行升起那一遍是给人认画面、从头念一遍用的，
 * 已经看过之后它只是在挡路。系统关掉动效时走的也是这一条路——无障碍设置的意思是
 * 不要动效，不是不要内容，所以那一档仍按完整可读时长停留。
 *
 * [continuesSystemSplash] 区分这一层是「接着场记板演」还是「后来才盖上来」：冷启动直接进主页时
 * 它紧贴着系统 splash，硬切才没有接缝；先落在登录页、之后才进主页的情况下屏幕上已经有别的画面，
 * 硬切会像闪了一下，所以整层淡入。淡入这段时间里手指可能还按在屏幕上，
 * 跳过因此要等到 [SplashQuoteTiming.SKIP_AT_MS] 才收点击，免得刚亮起来就被误触掀掉。
 *
 * [onSplashQuoteShown] 在这一层真的开演时回调一次，用来记当天的日签：
 * 记的时机必须是「这条台词确实亮在屏幕上过」，在启动流程里提前记会把用户没看见的台词写进日历。
 */
@Composable
fun SplashQuoteOverlay(
    quote: SplashQuoteUi,
    continuesSystemSplash: Boolean = true,
    onSplashQuoteShown: () -> Unit = {},
    onFinished: () -> Unit,
) {
    val palette = if (MaterialTheme.colorScheme.surface.luminance() < 0.5f) {
        SplashPalette.Dark
    } else {
        SplashPalette.Light
    }
    val context = LocalContext.current
    // 系统开启「移除动画」时不播过渡，静态呈现但保留完整可读时长——
    // 无障碍设置的意思是不要动效，不是不要内容。
    val reduceMotion = remember {
        Settings.Global.getFloat(
            context.contentResolver,
            Settings.Global.ANIMATOR_DURATION_SCALE,
            1f
        ) == 0f
    }
    val lineCount = quote.lines.size
    // 台词浮现完 + 停留读完，也就是这一层「有话要说」的整段时间（不含退场）。
    // 正常播和关掉动效两条路径都按它计时：一条在演，一条静止，但给的阅读时间一样长。
    val readSpanMs = SplashQuoteTiming.stayStart(lineCount) +
        SplashQuoteTiming.stay(quote.isFirstToday)
    // 整页一次摊开、不逐步浮现的两种情形：系统关掉了动效，以及今天已经看过这条台词。
    // 逐行升起那一遍是给当天第一次看的人认画面、从头念一遍用的；同一天再进 App 时
    // 那一秒多只是在挡路——内容都是同一份，早一点看全没有损失。
    val instant = reduceMotion || !quote.isFirstToday
    // 静止那条路留多久。关掉动效仍按完整可读时长算：无障碍设置的意思是不要动效，
    // 不是不要内容。今天已经看过那一档只留停留时间——浮现本来要花的那一秒多现在不花了，
    // 整层于是从 4.7 秒收到 3.4 秒。
    val staticHoldMs = if (quote.isFirstToday) readSpanMs else SplashQuoteTiming.stay(false)

    var bloom by remember { mutableStateOf(instant) }
    var posterVisible by remember { mutableStateOf(instant) }
    var visibleLines by remember { mutableIntStateOf(if (instant) lineCount else 0) }
    var sourceVisible by remember { mutableStateOf(instant) }
    var sealVisible by remember { mutableStateOf(instant) }
    var skipVisible by remember { mutableStateOf(instant) }
    var exiting by remember { mutableStateOf(false) }
    // 紧接系统 splash 的场合起手就是不透明（硬切），后来才盖上的场合从 0 淡进来。
    // 整页一次摊开时同样不淡入：淡入本身就是逐步显示。
    var entered by remember { mutableStateOf(continuesSystemSplash || instant) }

    LaunchedEffect(Unit) {
        onSplashQuoteShown()
        entered = true
        if (instant) {
            delay(staticHoldMs)
            exiting = true
            return@LaunchedEffect
        }
        bloom = true
        launch {
            delay(SplashQuoteTiming.POSTER_AT_MS)
            posterVisible = true
        }
        launch {
            delay(SplashQuoteTiming.BLOOM_MS)
            repeat(lineCount) { index ->
                if (index > 0) delay(SplashQuoteTiming.LINE_STEP_MS)
                visibleLines = index + 1
            }
        }
        launch {
            delay(SplashQuoteTiming.SOURCE_AT_MS)
            sourceVisible = true
        }
        launch {
            delay(SplashQuoteTiming.SEAL_AT_MS)
            sealVisible = true
        }
        launch {
            delay(SplashQuoteTiming.SKIP_AT_MS)
            skipVisible = true
        }
        delay(readSpanMs)
        exiting = true
    }

    // 跳过和自然结束汇到同一条退场路径：都只是把 exiting 置为 true
    LaunchedEffect(exiting) {
        if (!exiting) return@LaunchedEffect
        delay(SplashQuoteTiming.EXIT_MS)
        onFinished()
    }

    val exitMs = SplashQuoteTiming.EXIT_MS.toInt()
    // 淡入和退场淡出共用这一个 alpha：另起一层 alpha 会在「刚淡入就被点掉」时两个动画抢同一个值
    val layerAlpha by animateFloatAsState(
        targetValue = if (exiting || !entered) 0f else 1f,
        animationSpec = tween(durationMillis = if (exiting) exitMs else ENTER_FADE_MS),
        label = "splashLayerAlpha"
    )
    val glowScale by animateFloatAsState(
        targetValue = when {
            exiting -> 1.55f
            bloom -> 1f
            else -> 0.55f
        },
        animationSpec = tween(
            durationMillis = if (exiting) 620 else 900,
            easing = GlowEasing
        ),
        label = "splashGlowScale"
    )
    val glowAlpha by animateFloatAsState(
        targetValue = if (exiting || !bloom) 0f else palette.glowAlpha,
        animationSpec = tween(durationMillis = if (exiting) 620 else 400),
        label = "splashGlowAlpha"
    )
    val washAlpha by animateFloatAsState(
        targetValue = if (exiting || !bloom) 0f else WASH_ALPHA,
        animationSpec = tween(durationMillis = 460),
        label = "splashWashAlpha"
    )
    val grainAlpha by animateFloatAsState(
        targetValue = if (bloom) palette.grainAlpha else 0f,
        animationSpec = tween(durationMillis = 360),
        label = "splashGrainAlpha"
    )
    val vignetteAlpha by animateFloatAsState(
        targetValue = if (bloom) 1f else 0f,
        animationSpec = tween(durationMillis = 480),
        label = "splashVignetteAlpha"
    )
    val chromeAlpha by animateFloatAsState(
        targetValue = if (bloom && !exiting) 1f else 0f,
        animationSpec = tween(durationMillis = 520),
        label = "splashChromeAlpha"
    )
    val skipAlpha by animateFloatAsState(
        targetValue = if (skipVisible && !exiting) 1f else 0f,
        animationSpec = tween(durationMillis = 600),
        label = "splashSkipAlpha"
    )
    // 起手一定是系统 splash 的那张暖纸色（#F7EFE2，见 values-v31/themes.xml），
    // 暗色主题下再随光晕扩散把底色压到深棕。这样接缝处一点色差都没有，
    // 而暗色用户也不用被一整屏亮纸色晃 4 秒——像影院里灯慢慢暗下去。
    val backgroundColor by animateColorAsState(
        targetValue = if (bloom) palette.paper else SplashPalette.Light.paper,
        animationSpec = tween(durationMillis = SplashQuoteTiming.BLOOM_MS.toInt()),
        label = "splashBackground"
    )

    val today = remember {
        // 固定 Locale.US：日期只是「2026.08.28」这样的装饰性刻度，
        // 跟随系统 Locale 会在部分语言下渲染成非阿拉伯数字，破坏等宽刻度感。
        LocalDate.now().format(DateTimeFormatter.ofPattern("yyyy.MM.dd", Locale.US))
    }
    val interactionSource = remember { MutableInteractionSource() }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .alpha(layerAlpha)
            .background(backgroundColor)
            .clickable(
                interactionSource = interactionSource,
                indication = null,
                // 淡入进来的那一路要等跳过提示浮起来才收点击：这一层是突然盖上去的，
                // 手指可能正落在原来那个界面的按钮上，立刻收点击等于替用户按了跳过
                onClick = { if (!exiting && (continuesSystemSplash || skipVisible)) exiting = true }
            )
    ) {
        SplashBackdrop(
            palette = palette,
            glowScale = glowScale,
            glowAlpha = glowAlpha,
            washAlpha = washAlpha,
            grainAlpha = grainAlpha,
            vignetteAlpha = vignetteAlpha
        )

        Text(
            text = today,
            modifier = Modifier
                .align(Alignment.TopCenter)
                .padding(top = 82.dp)
                .alpha(chromeAlpha),
            color = dateInk(palette),
            fontSize = 11.sp,
            fontFamily = FontFamily.Monospace,
            letterSpacing = 0.42.em,
            textAlign = TextAlign.Center
        )

        // 海报、台词、出处、印章同在这一列里从上往下排，中间垫着 spacer，
        // 所以文字永远不会压在海报上——海报放大到 176×264 也不改变这一点，
        // 不需要再给文字单独垫一块局部衬底。而且海报是在日期之后画的、本身不透明，
        // 万一被极端字号顶到日期那一带，结果是日期被海报盖住，不会出现「深褐字压在海报亮部上」。
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 34.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            QuotePoster(poster = quote.poster, palette = palette, visible = posterVisible)
            Spacer(Modifier.height(24.dp))
            quote.lines.forEachIndexed { index, line ->
                QuoteLine(
                    text = line,
                    visible = index < visibleLines,
                    palette = palette,
                    isEnglish = quote.isEnglish,
                    lineCount = lineCount
                )
            }
            Spacer(Modifier.height(22.dp))
            QuoteSource(quote = quote, palette = palette, visible = sourceVisible)
            Spacer(Modifier.height(26.dp))
            StampedSeal(quote = quote, palette = palette, visible = sealVisible)
        }

        Text(
            text = stringResource(R.string.splash_quote_skip),
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(bottom = 56.dp)
                .alpha(skipAlpha),
            // 这行不是装饰而是操作提示：找不到它的用户只能干等着，所以按正文的对比度要求给色，
            // 见 hintInk——画线用的 inkFaint（1.6:1 / 2.2:1）在这里是不合格的
            color = hintInk(palette),
            fontSize = 11.sp,
            letterSpacing = 0.3.em,
            textAlign = TextAlign.Center
        )
    }
}

/**
 * 单行台词。
 *
 * 逐行升起而不是整段淡入：台词的换行位置是排版的一部分，一行一行出来才有念白的停顿感。
 * 字号随行数递减，保证 4 行也不会触发自动折行——自动折行会把断句断在错误的地方。
 */
@Composable
private fun QuoteLine(
    text: String,
    visible: Boolean,
    palette: SplashPalette,
    isEnglish: Boolean,
    lineCount: Int,
) {
    val alpha by animateFloatAsState(
        targetValue = if (visible) 1f else 0f,
        animationSpec = tween(durationMillis = SplashQuoteTiming.LINE_DUR_MS.toInt()),
        label = "quoteLineAlpha"
    )
    val riseDp by animateFloatAsState(
        targetValue = if (visible) 0f else 14f,
        animationSpec = tween(
            durationMillis = SplashQuoteTiming.LINE_DUR_MS.toInt(),
            easing = RiseEasing
        ),
        label = "quoteLineRise"
    )
    val fontSize = when {
        isEnglish && lineCount >= 4 -> 17.5f
        isEnglish && lineCount == 3 -> 19f
        isEnglish -> 20f
        lineCount >= 4 -> 19f
        lineCount == 3 -> 21f
        else -> 23f
    }
    val lineHeightFactor = if (isEnglish) 1.58f else if (lineCount >= 4) 1.60f else 1.64f
    Text(
        text = text,
        modifier = Modifier
            .fillMaxWidth()
            .graphicsLayer {
                this.alpha = alpha
                translationY = riseDp.dp.toPx()
            },
        color = palette.ink,
        fontSize = fontSize.sp,
        lineHeight = (fontSize * lineHeightFactor).sp,
        fontFamily = FontFamily.Serif,
        // 600 而不是 500：衬线族在多数机器上只装了 400 和 700 两个字重，中间值按最近的一档取，
        // 500 会被取回 400——写着 Medium，画出来是常规体，也就是「太细」的由来。600 落到 700
        // 那一侧，装了可变字体的机器上还能拿到真正的 600。台词是这一层的主体，该比正文重一档。
        fontWeight = FontWeight.SemiBold,
        fontStyle = if (isEnglish) FontStyle.Italic else FontStyle.Normal,
        letterSpacing = if (isEnglish) 0.006.em else 0.012.em,
        textAlign = TextAlign.Center
    )
}

/** 出处行：破折号 + 书名号包起的片名 + 小一号的年份 */
@Composable
private fun QuoteSource(
    quote: SplashQuoteUi,
    palette: SplashPalette,
    visible: Boolean,
) {
    val alpha by animateFloatAsState(
        targetValue = if (visible) 1f else 0f,
        animationSpec = tween(durationMillis = 400),
        label = "quoteSourceAlpha"
    )
    val riseDp by animateFloatAsState(
        targetValue = if (visible) 0f else 8f,
        animationSpec = tween(durationMillis = 400, easing = RiseEasing),
        label = "quoteSourceRise"
    )
    val text = buildAnnotatedString {
        append(EM_DASH)
        append(' ')
        append(quote.titleWrap.first)
        append(quote.title)
        append(quote.titleWrap.second)
        append(' ')
        // 年份靠字号退一档，不靠透明度：它是注解不是标题，同号同重会和片名抢注意力。
        // 原先是把 inkSoft 再乘 0.7，等于 0.43 倍墨，明色 1.75:1、暗色 3.42:1，
        // 注解归注解，看不见就不叫注解了；现在它跟着出处行拿同一个 inkSoft。
        withStyle(SpanStyle(fontSize = 11.sp)) {
            append("(${quote.year})")
        }
    }
    Text(
        text = text,
        modifier = Modifier
            .fillMaxWidth()
            .graphicsLayer {
                this.alpha = alpha
                translationY = riseDp.dp.toPx()
            },
        color = palette.inkSoft,
        fontSize = 13.sp,
        letterSpacing = 0.1.em,
        textAlign = TextAlign.Center
    )
}

/**
 * 落下的印章：当天日签的关键词。
 *
 * 动作是「压」而不是「浮」：从 1.28 倍缩到 1 倍、260ms 收住，比其它元素都快。
 * 台词是慢慢浮起来的，印章要是也慢慢浮，就成了第四行字；快速压下去才是盖章。
 */
@Composable
private fun StampedSeal(
    quote: SplashQuoteUi,
    palette: SplashPalette,
    visible: Boolean,
) {
    if (quote.keyword.isBlank()) return
    val alpha by animateFloatAsState(
        targetValue = if (visible) 1f else 0f,
        animationSpec = tween(durationMillis = 200),
        label = "quoteSealAlpha"
    )
    val scale by animateFloatAsState(
        targetValue = if (visible) 1f else 1.28f,
        animationSpec = tween(durationMillis = 260, easing = SealEasing),
        label = "quoteSealScale"
    )
    QuoteSeal(
        keyword = quote.sealKeyword,
        latin = quote.keywordLatin,
        sealLang = quote.sealLang,
        palette = palette,
        modifier = Modifier.graphicsLayer {
            this.alpha = alpha
            scaleX = scale
            scaleY = scale
        },
    )
}

/**
 * 海报，做成相纸装裱的样子：外层一圈奶油色卡纸 + 投影，内层图片压一层纸色。
 *
 * 压色是必要的：未处理的彩色海报直接贴在暖纸背景上，看起来像硬插进来的一块图，
 * 压掉一点饱和度之后它才像原本就印在这张纸上。
 *
 * 尺寸看屏幕高度而不是宽度：这一层是一整列竖排——日期、海报、台词、出处、印章挨着往下摆，
 * 挤的从来是纵向。窄屏横向本就留着 34dp 的页边距，真会把印章顶出屏幕、把日期顶到海报底下的是矮屏。
 * 超大字号一并按矮屏算：字号翻上去等于把四行台词的行高整段拉长，占的还是纵向那点余量。
 *
 * [poster] 为 null 时整块不渲染，而不是画一个占位框——开屏宁可少一样东西，
 * 也不要出现「这里本该有张图」的破洞感。
 */
@Composable
private fun QuotePoster(
    poster: ImageBitmap?,
    palette: SplashPalette,
    visible: Boolean,
) {
    if (poster == null) return
    val alpha by animateFloatAsState(
        targetValue = if (visible) 1f else 0f,
        animationSpec = tween(durationMillis = 460),
        label = "quotePosterAlpha"
    )
    val scale by animateFloatAsState(
        targetValue = if (visible) 1f else 0.955f,
        animationSpec = tween(durationMillis = 520, easing = PosterEasing),
        label = "quotePosterScale"
    )
    val riseDp by animateFloatAsState(
        targetValue = if (visible) 0f else 12f,
        animationSpec = tween(durationMillis = 520, easing = PosterEasing),
        label = "quotePosterRise"
    )
    val compact = LocalConfiguration.current.screenHeightDp < POSTER_COMPACT_HEIGHT_DP ||
        LocalDensity.current.fontScale > POSTER_COMPACT_FONT_SCALE
    val posterWidth = if (compact) 148.dp else 176.dp
    val posterHeight = if (compact) 222.dp else 264.dp
    val tintColor = if (palette.isDark) Color(0xFF16100B) else palette.paper
    val tintAlpha = if (palette.isDark) 0.22f else 0.14f
    Box(
        modifier = Modifier
            .size(width = posterWidth, height = posterHeight)
            .graphicsLayer {
                this.alpha = alpha
                scaleX = scale
                scaleY = scale
                translationY = riseDp.dp.toPx()
            }
            .shadow(elevation = 18.dp, shape = RoundedCornerShape(9.dp), clip = false)
            .clip(RoundedCornerShape(9.dp))
            .background(palette.cream)
            // 卡纸边距：海报放大之后 5dp 看着像图印歪了没留边，7dp 才是装裱的一圈白
            .padding(7.dp)
    ) {
        Image(
            bitmap = poster,
            contentDescription = null,
            contentScale = ContentScale.Crop,
            modifier = Modifier
                .fillMaxSize()
                // 内层圆角比外层小一档：卡纸的角总是比压在里面那张相纸圆一点
                .clip(RoundedCornerShape(5.dp))
                .drawWithContent {
                    drawContent()
                    drawRect(color = tintColor, alpha = tintAlpha, blendMode = BlendMode.Multiply)
                }
        )
    }
}

/**
 * 背景：暖色漏光 + 中央可读性蒙层 + 四角压暗。
 *
 * 光晕本来分两条路：API 33+ 走 mirage 的 AGSL 着色器，低版本回退径向渐变。现在两条都不留，
 * 统一画四团手摆位置的椭圆光斑。着色器算出来的弥散太匀，而匀恰恰是「渲染出来的光」的味道；
 * 相纸上的漏光从来是几处深几处浅，位置也不讲道理。少一条按系统版本分叉的实现还有个好处：
 * 开屏只有一套观感，不必再判断两台设备看起来不一样是设备差异还是分支差异。
 */
@Composable
private fun SplashBackdrop(
    palette: SplashPalette,
    glowScale: Float,
    glowAlpha: Float,
    washAlpha: Float,
    grainAlpha: Float,
    vignetteAlpha: Float,
) {
    Box(modifier = Modifier.fillMaxSize()) {
        LeakGlow(palette, glowScale, glowAlpha, grainAlpha)
        ReadabilityOverlay(palette, washAlpha, vignetteAlpha)
    }
}

/**
 * 四团漏光 + 平铺颗粒。
 *
 * 光斑取椭圆而不是正圆：正圆一眼就看得出是个圆，各自长宽比不同的椭圆才像光顺着纸边渗进来的一片。
 * 颗粒画在同一层里：原先只有低版本分支铺噪点，新系统靠着色器自己出噪声，
 * 着色器一撤，这层纸的纹理就得由这里补上，否则新系统上纸面是干净的塑料感。
 */
@Composable
private fun LeakGlow(
    palette: SplashPalette,
    glowScale: Float,
    glowAlpha: Float,
    grainAlpha: Float,
) {
    val grain = remember { grainBrush() }
    Canvas(modifier = Modifier.fillMaxSize()) {
        val w = size.width
        val h = size.height
        if (w <= 0f || h <= 0f) return@Canvas
        // 明色主题用 Multiply 让暖光像颜料渗进纸里，暗色主题改 Screen，
        // 否则几团深棕叠在深底上会糊成一片黑
        val glowBlend = if (palette.isDark) BlendMode.Screen else BlendMode.Multiply
        if (glowAlpha > EPSILON) {
            GLOW_SPOTS.forEach { spot ->
                drawLeakSpot(spot, palette, glowScale, glowAlpha, glowBlend)
            }
        }
        if (grainAlpha > EPSILON) {
            drawRect(brush = grain, alpha = grainAlpha, blendMode = BlendMode.Multiply)
        }
    }
}

/**
 * 中央纸色蒙层与四角压暗，两条都只为台词的对比度服务。
 *
 * 蒙层是这一层唯一的可读性衬底：光斑压上来之后纸色会往暖里偏，它把台词那一片拉回纯纸色。
 * 所以调低 [WASH_ALPHA] 一定要连着光斑的基准 alpha 一起调，单降一边就是拿对比度换气质。
 */
@Composable
private fun ReadabilityOverlay(
    palette: SplashPalette,
    washAlpha: Float,
    vignetteAlpha: Float,
) {
    Canvas(modifier = Modifier.fillMaxSize()) {
        val w = size.width
        val h = size.height
        if (w <= 0f || h <= 0f) return@Canvas
        if (washAlpha > EPSILON) {
            // 光晕铺满之后台词区域对比度会掉，这层把中央拉回纯纸色
            val center = Offset(0.5f * w, 0.48f * h)
            val radius = 0.80f * w
            drawCircle(
                brush = Brush.radialGradient(
                    0f to palette.paper,
                    0.74f to Color.Transparent,
                    center = center,
                    radius = radius
                ),
                radius = radius,
                center = center,
                alpha = washAlpha
            )
        }
        if (vignetteAlpha > EPSILON) {
            val center = Offset(0.5f * w, 0.44f * h)
            val radius = 0.78f * maxOf(w, h)
            drawCircle(
                brush = Brush.radialGradient(
                    0.44f to Color.Transparent,
                    1f to VIGNETTE_COLOR,
                    center = center,
                    radius = radius
                ),
                radius = radius,
                center = center,
                alpha = vignetteAlpha
            )
        }
    }
}

/**
 * 顶部日期那一行的淡墨。
 *
 * 日期是装饰性刻度，按 WCAG 只需要 3:1，可以比正文淡；但不能淡到看不见——
 * [SplashPalette.inkFaint]（0.32 倍墨）在纸上只有 1.60:1（暗色 2.23:1），等于没印上去。
 * 这里按正文墨另兑一档：明色 0.76 得 3.53:1，暗色 0.44 得 3.64:1，两套的淡法看上去一致。
 * 留的余量是给光斑的：日期落在中央蒙层的圆外面，底下那点暖光没人帮它拉回纸色，
 * 实际比值会掉到 3.30:1（暗色 3.51:1），压着 3:1 取值就等于不留余量。
 *
 * 不去改 inkFaint 本身，是因为日签页拿它画撕口虚线和日历格线：
 * 为了这一行日期把它压深，那一屏的细线会立刻变成描边。
 */
private fun dateInk(palette: SplashPalette): Color =
    palette.ink.copy(alpha = if (palette.isDark) 0.44f else 0.76f)

/**
 * 底部「轻触跳过」那一行的墨色。
 *
 * 这行是操作提示不是装饰，得按正文的 4.5:1 要求给色；而它和日期一样落在中央蒙层的圆外，
 * 底下叠着暖光斑和四角压暗，实际比值比纸上算出来的要低半档。明色索引到实心墨
 * （纸上 5.87:1，把光斑和压暗算进去 4.98:1）；暗色的浅墨本来就富裕，0.60 已经是 5.46:1，
 * 再往上加只会让一行小字比台词还抢眼。
 */
private fun hintInk(palette: SplashPalette): Color =
    palette.ink.copy(alpha = if (palette.isDark) 0.60f else 1f)

/**
 * 画一团漏光。
 *
 * 做法是先把画布按 ry/rx 纵向压扁，再画一个半径 rx 的正圆径向渐变：出来是软边的椭圆。
 * 直接 drawOval 配径向画笔不行——径向渐变本身是圆的，被椭圆一裁，边上会留一道硬边。
 */
private fun DrawScope.drawLeakSpot(
    spot: GlowSpot,
    palette: SplashPalette,
    glowScale: Float,
    glowAlpha: Float,
    blendMode: BlendMode,
) {
    val rx = spot.rx * size.width * glowScale
    val ry = spot.ry * size.height * glowScale
    if (rx <= 0f || ry <= 0f) return
    val center = Offset(spot.cx * size.width, spot.cy * size.height)
    val color = spot.tint(palette)
    scale(scaleX = 1f, scaleY = ry / rx, pivot = center) {
        drawCircle(
            brush = Brush.radialGradient(
                0f to color,
                0.68f to color.copy(alpha = 0f),
                center = center,
                radius = rx
            ),
            radius = rx,
            center = center,
            alpha = (glowAlpha * spot.jitter).coerceIn(0f, 1f),
            blendMode = blendMode
        )
    }
}

/**
 * 一团漏光的摆位，全部按屏幕比例存：中心与半径横向比宽、纵向比高，换屏幕时四团的相对关系不变。
 * 横竖各自取比例的结果是竖屏手机上每团都略微竖长，四团的长宽比还各不相同，没有一团是正圆。
 *
 * [jitter] 是这一团独有的亮度系数。四团共用一个 alpha 就又回到「算出来的光」，
 * 差一成才有先后深浅。写成常量而不是启动时随机：随机的话每次重绘都换一个数，看上去是在闪。
 */
private class GlowSpot(
    val cx: Float,
    val cy: Float,
    val rx: Float,
    val ry: Float,
    val jitter: Float,
    val tint: (SplashPalette) -> Color,
)

/**
 * 四团漏光的位置，手摆的。
 *
 * 四个中心任取三个都不共线，也没有一对是关于屏幕中轴或中心镜像的——一旦对称，
 * 人眼立刻读出「这是按公式摆的」。尺寸和长宽比也四团各不相同：最大的一团压在左下，
 * 最小的一团缩在右下，亮区于是从左上斜着往右下走，顺着海报、台词、印章往下排的方向。
 * 中心大多贴在边上或干脆出屏（0.94、0.86），只让光的一角照进画面，光源本身留在纸外。
 *
 * 四团都只擦到中央那片台词区的边，不盖到它头上：径向渐变到 0.68 倍半径就透明了，
 * 按这四组数算下来，屏幕正中 x 0.48-0.63、y 0.35-0.56 那一块几乎不落光。
 * 这是有意留的——台词是要读的，读的地方就该是干净的纸。
 *
 * 抖动取 1.09 / 0.92 / 1.06 / 0.90：两明两暗交错，平均 0.99——整屏亮度和不抖动时基本一样，
 * 只是把光挪得深浅不匀。
 */
private val GLOW_SPOTS = listOf(
    GlowSpot(cx = 0.13f, cy = 0.15f, rx = 0.52f, ry = 0.30f, jitter = 1.09f) { it.caramel },
    GlowSpot(cx = 0.94f, cy = 0.37f, rx = 0.46f, ry = 0.34f, jitter = 0.92f) { it.ochre },
    GlowSpot(cx = 0.22f, cy = 0.86f, rx = 0.60f, ry = 0.28f, jitter = 1.06f) { it.cream },
    GlowSpot(cx = 0.79f, cy = 0.70f, rx = 0.36f, ry = 0.20f, jitter = 0.90f) { it.caramel },
)

/**
 * 生成一小块噪点并平铺成胶片颗粒，明暗两套主题、所有系统版本共用这一份。
 *
 * 128×128 一张（64KB）平铺整屏，比放一张全屏噪点图省得多，也不用往 APK 里塞资源。
 * 固定随机种子，保证每次启动的颗粒分布一致——颗粒每次都变会看出「在闪」。
 *
 * 日签页也用这一块噪点：两屏的纸面纹理必须是同一种，否则从开屏走到日签会看出换了张纸。
 */
internal fun grainBrush(): ShaderBrush {
    val size = GRAIN_TILE_PX
    val random = Random(GRAIN_SEED)
    val pixels = IntArray(size * size) {
        val v = 120 + random.nextInt(72)
        (0xFF shl 24) or (v shl 16) or (v shl 8) or v
    }
    val bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
    bitmap.setPixels(pixels, 0, size, 0, 0, size, size)
    return ShaderBrush(
        ImageShader(bitmap.asImageBitmap(), TileMode.Repeated, TileMode.Repeated)
    )
}

private const val EM_DASH = "—"
/**
 * 中央纸色蒙层的峰值透明度。
 *
 * 原先是 0.66，配的是几乎盖满整屏的着色器光晕；现在光斑只从四边漏进来、基准 alpha 也压低了，
 * 蒙层跟着退到 0.58——再厚下去中央就成了一块没有光的奶白饼，纸和光的关系反而看不出来。
 */
private const val WASH_ALPHA = 0.58f
private const val EPSILON = 0.001f
private const val GRAIN_TILE_PX = 128
private const val GRAIN_SEED = 20260828L

/**
 * 海报回落小尺寸的屏高阈值，单位 dp。
 *
 * 760dp 之下的矮屏（多是老设备或分屏）容不下 176×264 的海报：这一列从日期排到印章，
 * 海报一大，下面的印章就被顶到屏幕外，或者台词行距被压得念不成句子。
 */
private const val POSTER_COMPACT_HEIGHT_DP = 760

/**
 * 超过这个字体缩放倍数也按矮屏算。
 *
 * 1.15 之上四行台词就要多吃掉三四十 dp，够把海报顶进顶部日期那一带。
 * 系统的字体放大是用户明确要求的，压的应该是海报，不是字。
 */
private const val POSTER_COMPACT_FONT_SCALE = 1.15f

/**
 * 后来才盖上来时整层的淡入时长。
 *
 * 320ms 是「看得出是淡进来的、又不用等」的那一档：短于 250ms 观感上就是硬切，
 * 长过 400ms 会让人觉得启动卡了一下。紧接系统 splash 的场合不走这段，见 SplashQuoteOverlay。
 */
private const val ENTER_FADE_MS = 320

private val VIGNETTE_COLOR = Color(0x383C2212)
private val GlowEasing = CubicBezierEasing(0.22f, 0.7f, 0.25f, 1f)
private val RiseEasing = CubicBezierEasing(0.2f, 0.75f, 0.28f, 1f)
private val PosterEasing = CubicBezierEasing(0.2f, 0.75f, 0.28f, 1f)

/** 印章专用：起手就快、末尾硬收，模拟压下去到底的手感 */
private val SealEasing = CubicBezierEasing(0.16f, 0.9f, 0.2f, 1f)
