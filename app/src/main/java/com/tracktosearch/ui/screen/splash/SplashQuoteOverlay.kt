package com.tracktosearch.ui.screen.splash

import android.graphics.Bitmap
import android.provider.Settings
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.ImageShader
import androidx.compose.ui.graphics.ShaderBrush
import androidx.compose.ui.graphics.TileMode
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import com.tracktosearch.R
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.time.LocalDate
import java.util.Random
import kotlin.math.PI
import kotlin.math.atan2
import kotlin.math.floor
import kotlin.math.hypot
import kotlin.math.min

/**
 * 开屏「每日一句」台词层。
 *
 * 接在系统 SplashScreen 的场记板之后：场记板合板的瞬间这一层已经铺着同一张暖纸色，
 * 所以视觉上是同一块画面继续往下演，而不是两段动画拼接。
 *
 * 节奏见 [SplashQuoteTiming]：光晕扩散 → 海报浮起 → 台词逐行升起 → 出处淡入 →
 * 印章压下、日期跟着落下 → 停留（当天首看 8 秒 / 再看 3 秒）→ 光晕散开同时整层淡出，
 * 露出下面已经组合好的主界面。App 已就绪之后任意轻触直接跳到散开阶段。
 *
 * 逐步浮现只演给当天第一次看的人。同一天再进 App 时整页一次摊开：海报、台词、出处、
 * 印章从第一帧就都在，停留 3 秒后退场。逐行升起那一遍是给人认画面、从头念一遍用的，
 * 已经看过之后它只是在挡路。系统关掉动效时走的也是这一条路——无障碍设置的意思是
 * 不要动效，不是不要内容，所以那一档仍按完整可读时长停留。
 *
 * 这一层不再等「导航落到主页」才上场：它以日签数据就绪为准，紧接着系统场记板压上去，
 * 主界面在它背后继续加载。用户等 App 启动的那段时间因此花在有内容的这一页纸上，
 * 而不是一块只有品牌名的暖纸底。冷启动直进主页、先过登录页两条路现在都走这一条。
 *
 * [contentReady] 是「App 已就绪」：导航已组合、日签收掉之后立刻能交互。它管三件事——
 * 跳过提示浮出的时机、整层收点击的时机、以及演完之后肯不肯散场。
 * 就绪之前轻触一律不响应：收掉日签之后下面还没有东西可看，那一下此刻没有意义。
 * 跳过提示浮出后再给 [SplashQuoteTiming.SKIP_READ_MS] 才允许自然退场，
 * 否则慢启动的机器上提示刚亮就收场，用户根本来不及读它。
 *
 * [onSplashQuoteShown] 在这一层真的开演时回调一次，用来记当天的日签：
 * 记的时机必须是「这条台词确实亮在屏幕上过」，在启动流程里提前记会把用户没看见的台词写进日历。
 */
@Composable
fun SplashQuoteOverlay(
    quote: SplashQuoteUi,
    contentReady: Boolean,
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
    // 起手就是不透明，只有退场时这一层才动 alpha。
    // 「整层淡入」那一档是「先过登录页、后来才盖到主页上」时代的产物——那条路已经不存在了，
    // 这一层现在总是紧接着系统场记板上场（日签数据一就绪就压上去、系统 splash 随之散场）。
    var exiting by remember { mutableStateOf(false) }
    // 「跳过」给足可读时间之后才允许自然退场。慢启动的机器上用户刚看到提示它就收场，
    // 那行字等于白印；这一个布尔就是那道门。
    var skipReadable by remember { mutableStateOf(false) }
    // 这一层「有话要说 + 给得起一次轻触」的时长。两档都至少按当天首看的完整可读时间算：
    // 逐步浮现那一档本来就演这么久，整页一次摊开的那一档三秒太短——用户还没抬手就收场了。
    val holdMs = maxOf(readSpanMs, staticHoldMs)

    LaunchedEffect(Unit) {
        onSplashQuoteShown()
        if (instant) return@LaunchedEffect
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
    }

    // 跳过提示在 App 就绪那一刻浮出（状态初值），再过 SKIP_READ_MS 才认它可以被读完
    LaunchedEffect(contentReady) {
        if (!contentReady) return@LaunchedEffect
        delay(SplashQuoteTiming.SKIP_READ_MS)
        skipReadable = true
    }

    // 退场条件：这一层已经亮过、App 已就绪、停留时长够了、跳过提示也给足了可读时间。
    // App 迟迟不就绪时这一层就停在最后定格画面上等——比露出一个半加载的界面强，
    // 那段等待期正好拿来跑「必要加载工作」。
    LaunchedEffect(contentReady, skipReadable) {
        if (!contentReady || !skipReadable) return@LaunchedEffect
        delay(holdMs)
        exiting = true
    }

    // 等待的硬上限：从这一层真正出现在屏幕上那一刻起算。越过它宁可露出底下正在加载的界面，
    // 也不能把用户永久困在一页日签上——启动链里任何一环挂死都不该变成一堵墙。
    LaunchedEffect(Unit) {
        delay(SplashQuoteTiming.HARD_WAIT_MS)
        exiting = true
    }

    // 跳过、停留结束、硬上限三条路汇到同一条退场路径：都只是把 exiting 置为 true
    LaunchedEffect(exiting) {
        if (!exiting) return@LaunchedEffect
        delay(SplashQuoteTiming.EXIT_MS)
        onFinished()
    }

    val exitMs = SplashQuoteTiming.EXIT_MS.toInt()
    // 这一层只在退场时动 alpha：起手就是不透明（接着系统场记板，中间没有别的画面），
    // 「刚才还是 0 现在要淡到 1」这种情形不存在，所以这里不会和退场抢同一个值。
    val layerAlpha by animateFloatAsState(
        targetValue = if (exiting) 0f else 1f,
        animationSpec = tween(durationMillis = if (exiting) exitMs else ENTER_FADE_MS),
        label = "splashLayerAlpha"
    )
    val beamScale by animateFloatAsState(
        targetValue = when {
            exiting -> 1.55f
            bloom -> 1f
            else -> 0.55f
        },
        animationSpec = tween(
            durationMillis = if (exiting) 620 else 900,
            easing = BeamEasing
        ),
        label = "splashBeamScale"
    )
    val beamAlpha by animateFloatAsState(
        targetValue = if (exiting || !bloom) 0f else palette.beamAlpha,
        animationSpec = tween(durationMillis = if (exiting) 620 else 400),
        label = "splashBeamAlpha"
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
    // 「跳过」这一行只在 App 就绪之后亮：它亮了就代表点了有用。
    // 还没就绪的那些帧里轻触不响应（见下面的 onClick），提示自然也不该在。
    val skipVisible = contentReady
    // 亮出来之后还收 600ms 才收点击：那 600ms 是它自己的浮现时间，
    // 在它还没看清时就把「点哪都能跳过」打开，等于让用户点一个他还没看见的东西。
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

    val today = remember { LocalDate.now() }
    val interactionSource = remember { MutableInteractionSource() }
    // 日期和印章同一拍落下来（印章 260ms 压下去，日期 300ms 跟着落），所以这一对值留在这里，
    // 和别的动画值并列。日期块本身在 StampPage 里钉底，这里只把它那一档浮现传下去。
    val dateAlpha by animateFloatAsState(
        targetValue = if (sealVisible) 1f else 0f,
        animationSpec = tween(durationMillis = 300),
        label = "quoteDateAlpha"
    )
    val dateRiseDp by animateFloatAsState(
        targetValue = if (sealVisible) 0f else 6f,
        animationSpec = tween(durationMillis = 300, easing = RiseEasing),
        label = "quoteDateRise"
    )

    StampPage(
        palette = palette,
        date = today,
        modifier = Modifier
            .fillMaxSize()
            .alpha(layerAlpha)
            .clickable(
                interactionSource = interactionSource,
                indication = null,
                // App 就绪之前整层不收点击：收掉日签之后下面还没有东西可看，那一下此刻没有意义，
                // 而且手指可能正落在启动过程中刚出现的东西上，放行等于替用户按下去。
                onClick = { if (contentReady && !exiting) exiting = true }
            ),
        // 纸色画在页内而不是这一层的背景上：日签卡要录下同一页给导出用，
        // 底色落在页外录进去就是透明底（见 StampPage 的 base）
        base = backgroundColor,
        // 状态栏就压在这一层上面，少了这道边，矮屏上海报的上沿会钻到时钟底下去。
        // 只给内容列，不给背景层：光锥和齿孔轨要铺满整屏，那才是这张纸的边界。
        columnModifier = Modifier.statusBarsPadding(),
        tearAlpha = vignetteAlpha,
        dateReveal = StampReveal(dateAlpha, dateRiseDp.dp),
        backdrop = {
            SplashBackdrop(
                palette = palette,
                beamScale = beamScale,
                beamAlpha = beamAlpha,
                grainAlpha = grainAlpha,
                vignetteAlpha = vignetteAlpha,
                // 关掉动效时尘埃不飘。这一条和别处不同：别处静止的是「浮现」，
                // 这里静止的是环境里一直在动的东西，飘不飘都不影响内容
                drift = !reduceMotion
            )
        },
        poster = {
            QuotePoster(
                poster = quote.poster,
                palette = palette,
                visible = posterVisible,
                lineCount = lineCount,
            )
        },
        quoteBlock = {
            quote.lines.forEachIndexed { index, line ->
                QuoteLine(
                    text = line,
                    visible = index < visibleLines,
                    palette = palette,
                    isEnglish = quote.isEnglish,
                    lineCount = lineCount
                )
            }
            Spacer(Modifier.height(StampLinesToSource))
            QuoteSource(quote = quote, palette = palette, visible = sourceVisible)
        },
        seal = { StampedSeal(quote = quote, palette = palette, visible = sealVisible) },
        strip = {
            Text(
                text = stringResource(R.string.splash_quote_skip),
                modifier = Modifier.alpha(skipAlpha),
                // 这行不是装饰而是操作提示：找不到它的用户只能干等着，所以按正文的对比度要求给色，
                // 见 stampHintInk——画线用的 inkFaint（1.6:1 / 2.2:1）在这里是不合格的
                color = stampHintInk(palette),
                fontSize = 11.sp,
                letterSpacing = 0.3.em,
                textAlign = TextAlign.Center
            )
        },
    )
}

/**
 * 单行台词：这一层只加浮现，版面和字号在 [StampQuoteLine]。
 *
 * 逐行升起而不是整段淡入：台词的换行位置是排版的一部分，一行一行出来才有念白的停顿感。
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
    StampQuoteLine(
        text = text,
        palette = palette,
        isEnglish = isEnglish,
        lineCount = lineCount,
        modifier = Modifier.stampReveal(StampReveal(alpha = alpha, rise = riseDp.dp)),
    )
}

/** 出处行：这一层只加浮现，版面和字号在 [StampSourceLine] */
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
    StampSourceLine(
        title = quote.title,
        titleWrap = quote.titleWrap,
        year = quote.year,
        palette = palette,
        modifier = Modifier.stampReveal(StampReveal(alpha = alpha, rise = riseDp.dp)),
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
        modifier = Modifier.stampReveal(StampReveal(alpha = alpha, scale = scale)),
    )
}

/**
 * 海报：这一层只加浮现，装裱、投影、压色在 [StampPosterFrame]，尺寸在 [stampPosterSize]。
 *
 * [poster] 为 null 时整块不渲染，而不是画一个占位框——开屏宁可少一样东西，
 * 也不要出现「这里本该有张图」的破洞感。
 */
@Composable
private fun QuotePoster(
    poster: ImageBitmap?,
    palette: SplashPalette,
    visible: Boolean,
    lineCount: Int,
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
    val compact = LocalConfiguration.current.screenHeightDp < PosterCompactHeightDp ||
        LocalDensity.current.fontScale > PosterCompactFontScale
    Box(
        modifier = Modifier.stampReveal(
            StampReveal(alpha = alpha, rise = riseDp.dp, scale = scale)
        )
    ) {
        StampPosterFrame(palette = palette, size = stampPosterSize(lineCount, compact)) {
            Image(
                bitmap = poster,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
            )
        }
    }
}

/**
 * 背景：一束放映机光锥 + 左右两条胶片齿孔轨 + 颗粒 + 四角压暗。
 *
 * 上一版是四团手摆的暖色椭圆漏光：明色下用 Multiply 压在暖纸上，再拿一层中央纸色蒙层
 * 把台词那一片擦回纯纸色。那条路的方向是反的——把暖色 Multiply 到暖纸上是把纸染脏，
 * 不是打光，所以只好再擦一遍。先弄脏再擦干净，剩下的就是一圈说不清来路的褐晕，
 * 而整屏最平的那块恰好是眼睛要落的地方（台词）。
 *
 * 现在只有一个光源：一束斜切下来的光锥。中央蒙层因此整层删掉，光锥自己就是那个亮的中心。
 * 光锥里飘着尘埃；光锥之外，左右两条边上钉着胶片齿孔。一柔一硬：一团光配一排硬边小孔，
 * 画面才有结构可看，而齿孔又和日签卡片撕口上的齿孔是同一套语言。
 *
 * 明暗两套的画法是反的，见 [SplashPalette.beamAlpha]：暗色主题把光加上去（Screen），
 * 明色主题把光锥之外压暗一档。亮纸上加不出光——纸已经快到白了，Screen 再怎么加也只剩
 * 三四个色阶的余量，那正是上一版明色下什么都看不出来的原因。
 *
 * 日签卡也铺这一层：一页日签的背景就是它（见 StampPage 的 [StampBackdrop]），
 * 只是开屏在演、卡片是静止的末态。
 */
@Composable
internal fun SplashBackdrop(
    palette: SplashPalette,
    beamScale: Float,
    beamAlpha: Float,
    grainAlpha: Float,
    vignetteAlpha: Float,
    drift: Boolean,
) {
    Box(modifier = Modifier.fillMaxSize()) {
        ProjectorBeam(
            palette = palette,
            beamScale = beamScale,
            beamAlpha = beamAlpha,
            grainAlpha = grainAlpha,
            drift = drift,
        )
        // 齿孔轨和四角压暗同一个淡入：两者都是「这张纸的边界」，一起浮起来才像同一张纸
        FilmRails(palette = palette, alpha = vignetteAlpha)
        Vignette(palette = palette, alpha = vignetteAlpha)
    }
}

/**
 * 一束光锥 + 光锥里的尘埃 + 平铺颗粒。
 *
 * 光锥是一段角度，不是一个画上去的形状：两条边的延长线交在画布外的一个顶点上（见
 * [beamGeometry]），亮度只由「偏离轴多少度」决定，用一条以那个顶点为心的 sweep 渐变画出来。
 * 于是光锥的边缘处处是软的，而软的那一段跟着光锥一起张开——离顶点越远，锥越宽，
 * 过渡带也越宽，正如一束真的光。轴向 [BEAM_FROM] 到 [BEAM_TO] 斜切而下，半宽从
 * [BEAM_NEAR_HALF] 张到 [BEAM_FAR_HALF]，都按屏幕短边算，换设备时光锥的粗细一致；
 * 再乘 [beamScale]，开场那一下就是「光从顶点推开」，退场是「光散掉」。
 *
 * 上一版是画一个梯形再 clip：往下走梯形比渐变宽，边是软的；往上走梯形比渐变窄，
 * 于是左上那一段露出一道两三成 alpha 的直边——整屏最扎眼的一样东西是它。
 * 角度渐变没有这个问题，因为再没有一个形状要被裁。
 *
 * 明色主题的光是「压暗光锥之外」，暗色主题的光是琥珀色 Screen 加上去，见
 * [SplashPalette.beamAlpha] 与 [BeamGeometry.sweep]。
 */
@Composable
private fun ProjectorBeam(
    palette: SplashPalette,
    beamScale: Float,
    beamAlpha: Float,
    grainAlpha: Float,
    drift: Boolean,
) {
    val grain = remember { grainBrush() }
    val motes = remember { dustMotes() }
    val driftT = dustDrift(drift)
    Canvas(modifier = Modifier.fillMaxSize()) {
        drawProjectorBeam(
            palette = palette,
            motes = motes,
            grain = grain,
            beamScale = beamScale,
            beamAlpha = beamAlpha,
            grainAlpha = grainAlpha,
            driftT = driftT,
        )
    }
}

/**
 * 光锥、尘埃、颗粒三层的实际绘制。
 *
 * 从 [ProjectorBeam] 里拆出来只为一件事：它是纯绘制，不碰组合，于是临时把可见性放开、
 * 接到一张软件位图上就能单独放一遍——改这一屏的观感时不必装到手机上才看得见结果。
 * [drawFilmRails]、[drawVignette] 同理。
 */
private fun DrawScope.drawProjectorBeam(
    palette: SplashPalette,
    motes: List<DustMote>,
    grain: Brush,
    beamScale: Float,
    beamAlpha: Float,
    grainAlpha: Float,
    driftT: Float,
) {
    val w = size.width
    val h = size.height
    if (w <= 0f || h <= 0f) return
    if (beamAlpha > EPSILON) {
        val beam = beamGeometry(w, h, beamScale)
        drawRect(
            brush = beam.sweep(
                color = if (palette.isDark) palette.caramel else BEAM_SHADE_LIGHT,
                lit = palette.isDark,
            ),
            alpha = beamAlpha,
            // 暗色是加光，明色是压暗光锥之外，见 SplashBackdrop 的 KDoc
            blendMode = if (palette.isDark) BlendMode.Screen else BlendMode.SrcOver,
        )
        drawDust(motes, beam, palette, beamAlpha, driftT)
    }
    if (grainAlpha > EPSILON) {
        drawRect(brush = grain, alpha = grainAlpha, blendMode = BlendMode.Multiply)
    }
}

/**
 * 尘埃缓慢下飘的进度，0 到 1 循环一遍要 [DUST_DRIFT_MS]。
 *
 * 关掉动效时直接返回 0，连动画都不起：这一层只活几秒，起一个每帧都要重画的循环动画
 * 却没人看得到位移，纯是白烧。[enabled] 在这一层的整个生命里不会变（它由 reduceMotion 算出来），
 * 所以这里提前 return 不会让组合结构在两次重组之间跳来跳去。
 */
@Composable
private fun dustDrift(enabled: Boolean): Float {
    if (!enabled) return 0f
    val transition = rememberInfiniteTransition(label = "splashDust")
    val drift by transition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = DUST_DRIFT_MS, easing = LinearEasing),
        ),
        label = "splashDustDrift",
    )
    return drift
}

/**
 * 左右两条胶片齿孔轨，各一列圆角小孔加一道片边线。
 *
 * 这是整屏唯一的硬边元素。背景其余部分全是柔的（一束光、一层颗粒、四角压暗），
 * 柔的东西堆再多也只是雾；要有一样东西边缘是清楚的、有节奏的、重复的，画面才立得住。
 * 取齿孔而不是别的图形，是因为日签卡片撕口两端就是齿孔——同一套语言，两屏才像一件东西。
 *
 * 墨色明色 15%、暗色 11%（见 [RAIL_INK_LIGHT]）：它是纸上压出来的孔，不是画上去的图案。
 * 孔列按屏高排满并整列居中，上下各留半个间距，任何屏幕上都不会出现顶头半个孔的样子。
 *
 * 不会压到内容：台词那一列有 34dp 横向内边距，齿孔连边线一起只占到边上 [RAIL_INSET] 加
 * 孔宽那一小段；跳过提示是居中的。
 */
@Composable
private fun FilmRails(palette: SplashPalette, alpha: Float) {
    Canvas(modifier = Modifier.fillMaxSize()) { drawFilmRails(palette, alpha) }
}

/** 齿孔轨的实际绘制，拆出来的理由同 [drawProjectorBeam] */
private fun DrawScope.drawFilmRails(palette: SplashPalette, alpha: Float) {
    if (alpha <= EPSILON || size.width <= 0f || size.height <= 0f) return
    val holeW = RAIL_HOLE_W.toPx()
    val holeH = RAIL_HOLE_H.toPx()
    val pitch = holeH + RAIL_HOLE_GAP.toPx()
    val inset = RAIL_INSET.toPx()
    if (pitch <= 0f) return
    val count = floor(size.height / pitch).toInt()
    if (count <= 0) return
    val holeAlpha = (if (palette.isDark) RAIL_INK_DARK else RAIL_INK_LIGHT) * alpha
    // 整列居中：剩下的空处上下对半分，首孔再往下让半个间距
    val top = (size.height - count * pitch + (pitch - holeH)) / 2f
    val holeX = listOf(inset, size.width - inset - holeW)
    repeat(count) { index ->
        val y = top + index * pitch
        holeX.forEach { x ->
            drawRoundRect(
                color = palette.ink,
                topLeft = Offset(x, y),
                size = Size(holeW, holeH),
                cornerRadius = CornerRadius(holeW / 2f),
                alpha = holeAlpha,
            )
        }
    }
    val lineAlpha = (if (palette.isDark) RAIL_LINE_INK_DARK else RAIL_LINE_INK_LIGHT) * alpha
    val lineGap = RAIL_LINE_GAP.toPx()
    listOf(inset + holeW + lineGap, size.width - inset - holeW - lineGap).forEach { x ->
        drawLine(
            color = palette.ink,
            start = Offset(x, 0f),
            end = Offset(x, size.height),
            strokeWidth = RAIL_LINE_W.toPx(),
            alpha = lineAlpha,
        )
    }
}

/**
 * 四角压暗。
 *
 * 光锥只管亮的那一条，暗的这一头交给它：有明才有暗，两头都得有人管，否则纸是一整片匀的。
 *
 * 深浅分明暗两档（[VIGNETTE_INK_LIGHT] / [VIGNETTE_INK_DARK]）。明色这一档只有暗色的四分之一：
 * 明色下光锥本身已经是「把锥外压暗一档」，而锥外正是右上和左下那两个角，四角压暗压的也是它们。
 * 同一处叠两遍，纸会沉成土色。
 *
 * 起手 0.44 倍半径才开始压：那一圈之内是海报和台词，压到它们头上等于拿内容的对比度换气质。
 */
@Composable
private fun Vignette(palette: SplashPalette, alpha: Float) {
    Canvas(modifier = Modifier.fillMaxSize()) { drawVignette(palette, alpha) }
}

/** 四角压暗的实际绘制，拆出来的理由同 [drawProjectorBeam] */
private fun DrawScope.drawVignette(palette: SplashPalette, alpha: Float) {
    if (alpha <= EPSILON || size.width <= 0f || size.height <= 0f) return
    val center = Offset(0.5f * size.width, 0.44f * size.height)
    val radius = 0.78f * maxOf(size.width, size.height)
    val ink = VIGNETTE_INK.copy(
        alpha = if (palette.isDark) VIGNETTE_INK_DARK else VIGNETTE_INK_LIGHT,
    )
    drawCircle(
        brush = Brush.radialGradient(
            // 透明那一头取同色零透明，不用 Color.Transparent：渐变两端色相一致才不会在中途泛灰
            0.44f to ink.copy(alpha = 0f),
            1f to ink,
            center = center,
            radius = radius,
        ),
        radius = radius,
        center = center,
        alpha = alpha,
    )
}

/**
 * 日期那一行的淡墨与底部那一行提示的墨色都搬去了 StampPage（[stampDateInk] / [stampHintInk]）：
 * 开屏、日签卡、导出图共用同一页，墨色也只剩一份。
 */

/**
 * 光锥的几何，全在像素空间里算。
 *
 * 不在归一化坐标里算是因为那套坐标不等比：x 比宽、y 比高，同一个「垂直于轴」的方向在竖屏上
 * 会被拉斜，光锥的两条边就不再平行于它自己的轴。先换成像素再算，角度和宽度才是几何上的。
 *
 * 角度存的是「整圈的几分之几」而不是弧度：sweep 渐变的色标就是这个单位（0 在三点钟方向，
 * 顺着屏幕坐标转一圈是 1），存成它省得每次画都换算一遍。
 */
private class BeamGeometry(
    /** 两条边的延长线交汇处，落在画布外，见 [beamGeometry] */
    val apex: Offset,
    /** 轴的方向角，单位是整圈的比例 */
    private val axisTurn: Float,
    /** 半张角，单位同 [axisTurn] */
    private val halfTurn: Float,
    /** 轴起点，[BEAM_FROM] 换算成像素 */
    val from: Offset,
    /** 单位轴向 */
    val axis: Offset,
    /** 单位法向，指向轴的右手边 */
    val normal: Offset,
    val length: Float,
    private val nearHalf: Float,
    private val farHalf: Float,
) {
    /** 轴上 [t]（0 是 [from]，1 是轴终点）处的半宽，尘埃靠它待在光里 */
    fun halfWidthAt(t: Float): Float = nearHalf + (farHalf - nearHalf) * t

    /**
     * 把光锥调成一把以 [apex] 为心的角度渐变。
     *
     * [lit] 为真时 [color] 是光：轴上最浓，到锥边收成零透明，锥外什么都不加（拿去 Screen）。
     * 为假时反过来，[color] 是阴影：轴上零透明，锥外满上（拿去 SrcOver 压暗）。两档共用
     * 同一条横截面曲线 [BEAM_PROFILE]，边缘的软硬因此一致。
     *
     * 压暗那一档的张角要乘 [BEAM_SHADE_SPREAD]。加光时锥外不加东西，锥窄一点只是光细一束；
     * 压暗时锥外是要被压的，锥窄就等于「整屏都暗了一档，只有一条不暗」——纸会整片沉下去，
     * 每一行字的对比度跟着掉。张开到 1.7 倍之后，日期、海报、台词、印章、跳过提示全落在
     * 没被压过的那片纸上，暗的只剩右上和左下两个角：亮的是整整一条斜过去的纸，
     * 而不是一条打在暗纸上的细光。
     *
     * 色标的透明档全用 `color.copy(alpha = 0f)` 而不是 [Color.Transparent]：一头是暖褐、
     * 一头是透明黑的话，渐变中段会掉进灰里去。
     *
     * 一圈里剩下的角度补上两个端点色标压住。[BEAM_FROM] 到 [BEAM_TO] 是往右下走的，
     * 轴角必落在 0 到四分之一圈之间，窗口不会跨过 0 那道缝，所以这里不必处理绕圈。
     */
    fun sweep(color: Color, lit: Boolean): Brush {
        val ends = color.copy(alpha = if (lit) 0f else 1f)
        val half = if (lit) halfTurn else halfTurn * BEAM_SHADE_SPREAD
        val stops = ArrayList<Pair<Float, Color>>(BEAM_PROFILE.size * 2 + 1)
        val windowStart = axisTurn - half
        val windowEnd = axisTurn + half
        if (windowStart > 0f) stops += 0f to ends
        BEAM_PROFILE.forEach { (offset, lightness) ->
            stops += crossStop(axisTurn - half * offset, color, lightness, lit)
        }
        BEAM_PROFILE.asReversed().forEach { (offset, lightness) ->
            if (offset > 0f) {
                stops += crossStop(axisTurn + half * offset, color, lightness, lit)
            }
        }
        if (windowEnd < 1f) stops += 1f to ends
        return Brush.sweepGradient(*stops.toTypedArray(), center = apex)
    }

    private fun crossStop(
        turn: Float,
        color: Color,
        lightness: Float,
        lit: Boolean,
    ): Pair<Float, Color> =
        turn.coerceIn(0f, 1f) to color.copy(alpha = if (lit) lightness else 1f - lightness)
}

/**
 * 由轴的两端和两处半宽推出光锥的顶点与张角。
 *
 * 半宽在 [BEAM_FROM] 处是 [BEAM_NEAR_HALF]、在 [BEAM_TO] 处是 [BEAM_FAR_HALF]，两条边于是
 * 交在轴起点之后 `轴长 / (远近半宽之比 − 1)` 那个点上。这个距离只跟两个常量的比值有关，
 * [scale] 在里面约掉了——光锥张开缩回时顶点是钉住的，动起来才是「从一处推开」，
 * 而不是整束光平移。
 */
private fun beamGeometry(width: Float, height: Float, scale: Float): BeamGeometry {
    val from = Offset(BEAM_FROM.x * width, BEAM_FROM.y * height)
    val to = Offset(BEAM_TO.x * width, BEAM_TO.y * height)
    val span = to - from
    val length = hypot(span.x, span.y)
    val axis = Offset(span.x / length, span.y / length)
    val normal = Offset(-axis.y, axis.x)
    val shortSide = min(width, height)
    val nearHalf = BEAM_NEAR_HALF * shortSide * scale
    val farHalf = BEAM_FAR_HALF * shortSide * scale
    val apexDistance = length / (BEAM_FAR_HALF / BEAM_NEAR_HALF - 1f)
    return BeamGeometry(
        apex = from - axis * apexDistance,
        axisTurn = atan2(axis.y, axis.x) / TWO_PI,
        halfTurn = atan2(farHalf, apexDistance + length) / TWO_PI,
        from = from,
        axis = axis,
        normal = normal,
        length = length,
        nearHalf = nearHalf,
        farHalf = farHalf,
    )
}

/**
 * 光柱里的一粒尘埃。位置不存屏幕坐标，存「在轴上多远（[t]）、离轴多偏（[across]，±1 是光锥边）」。
 *
 * 这样存的好处是尘埃永远在光里：换屏幕、光锥宽窄变化，它们跟着光走，不会飘到暗处去。
 */
private class DustMote(
    val t: Float,
    val across: Float,
    val radius: Dp,
    val alpha: Float,
)

/**
 * 十几粒尘埃，固定种子摆位。
 *
 * 和颗粒同一个道理：随机的话每帧都换一处，看上去是在闪。[DUST_ACROSS] 只到 0.60——
 * 再往边上放就落在光锥的暗尾里，那时候它读起来不是尘埃，是屏幕上的一个脏点。
 */
private fun dustMotes(): List<DustMote> {
    val random = Random(DUST_SEED)
    return List(DUST_COUNT) {
        DustMote(
            t = DUST_T_FROM + random.nextFloat() * (DUST_T_TO - DUST_T_FROM),
            across = (random.nextFloat() * 2f - 1f) * DUST_ACROSS,
            radius = (DUST_RADIUS_MIN + random.nextFloat() * DUST_RADIUS_SPAN).dp,
            alpha = DUST_ALPHA_MIN + random.nextFloat() * DUST_ALPHA_SPAN,
        )
    }
}

/**
 * 画尘埃。
 *
 * 明暗两套的方向是相反的：暗色主题下背景是深棕，尘埃是被光照亮的一粒，用奶色 Screen 加上去；
 * 明色主题下纸本来就快到白了，加光加不出东西来，逆光看到的尘埃反而是比纸暗的一点，
 * 所以改成赭色画上去，透明度还要再收一档——同样的深浅在亮纸上比在暗底上显眼得多。
 *
 * 整片尘埃跟着 [beamAlpha] 一起亮起来、一起散掉：它们是光的一部分，不是钉在纸上的点。
 */
private fun DrawScope.drawDust(
    motes: List<DustMote>,
    beam: BeamGeometry,
    palette: SplashPalette,
    beamAlpha: Float,
    driftT: Float,
) {
    val color = if (palette.isDark) palette.cream else palette.ochre
    val blend = if (palette.isDark) BlendMode.Screen else BlendMode.SrcOver
    val scale = if (palette.isDark) 1f else DUST_ALPHA_LIGHT_SCALE
    val slide = DUST_DRIFT.toPx() * driftT
    motes.forEach { mote ->
        val along = beam.from + beam.axis * (beam.length * mote.t)
        val center = along +
            beam.normal * (beam.halfWidthAt(mote.t) * mote.across) +
            Offset(slide * DUST_DRIFT_X_RATIO, slide)
        drawCircle(
            color = color,
            radius = mote.radius.toPx(),
            center = center,
            alpha = (mote.alpha * beamAlpha * scale).coerceIn(0f, 1f),
            blendMode = blend,
        )
    }
}

/**
 * 生成一小块噪点并平铺成胶片颗粒，明暗两套主题、所有系统版本共用这一份。
 *
 * 128×128 一张（64KB）平铺整屏，比放一张全屏噪点图省得多，也不用往 APK 里塞资源。
 * 固定随机种子，保证每次启动的颗粒分布一致——颗粒每次都变会看出「在闪」。
 *
 * 日签页也用这一块噪点：两屏的纸面纹理必须是同一种，否则从开屏走到日签会看出换了张纸。
 */
internal fun grainBrush(): ShaderBrush =
    ShaderBrush(ImageShader(grainTile().asImageBitmap(), TileMode.Repeated, TileMode.Repeated))

/**
 * 上面那块噪点瓦片本身。
 *
 * 全进程一份：内容固定，重复生成只是白烧 CPU。
 */
internal fun grainTile(): Bitmap = GRAIN_TILE

private val GRAIN_TILE: Bitmap by lazy {
    val size = GRAIN_TILE_PX
    val random = Random(GRAIN_SEED)
    val pixels = IntArray(size * size) {
        val v = 120 + random.nextInt(72)
        (0xFF shl 24) or (v shl 16) or (v shl 8) or v
    }
    Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888).apply {
        setPixels(pixels, 0, size, 0, 0, size, size)
    }
}

private const val EPSILON = 0.001f
private const val GRAIN_TILE_PX = 128
private const val GRAIN_SEED = 20260828L

/**
 * 四角压暗和明色光锥共用的那个褐，见 [drawVignette] 与 [BEAM_SHADE_LIGHT]。
 *
 * 声明在光锥那几个常量之前是必须的：文件里的顶层属性按书写顺序初始化，
 * [BEAM_SHADE_LIGHT] 引用它，写在它后面就会拿到 0（Color 是内联的 ULong，
 * 没初始化不报错，只是悄悄变成全透明）。
 */
private val VIGNETTE_INK = Color(0xFF3C2212)

/**
 * 四角压暗的浓度，明暗各一档。
 *
 * 明色只有暗色的四分之一：那一档的光锥本身就是在压暗（见 [BEAM_SHADE_LIGHT]），
 * 而它压的地方——右上、左下两个角——正是四角压暗压得最狠的地方，两层叠在一处。
 * 照暗色那个力度再压一遍，角上就是 0.30 往上，纸沉成土色，底部那行提示也保不住 4.5:1。
 */
private const val VIGNETTE_INK_LIGHT = 0.06f
private const val VIGNETTE_INK_DARK = 0.24f

/**
 * 光锥轴的两端，归一化坐标（x 比宽、y 比高）。
 *
 * 起点在左上偏内、终点在右下偏内。真正的进光口在画布外——两条边的延长线交汇的那个顶点在
 * 起点之后（见 [beamGeometry]），所以屏幕上看不到光锥收口那一下。这条轴在 t≈0.55 处经过
 * 屏幕中央偏上那一片——正是海报和台词落的位置。光该照在要读的东西上，不是照在旁边的空处。
 */
private val BEAM_FROM = Offset(0.12f, 0.10f)
private val BEAM_TO = Offset(0.80f, 0.86f)

/**
 * 光锥的半宽，从近端（[BEAM_FROM]）到远端（[BEAM_TO]），按屏幕短边的比例。
 *
 * 0.22 到 0.40 是「张开得看得出来，又还留着暗角」的一档：斜着穿过竖屏之后，光锥在画面中段
 * 的横向覆盖大约是屏宽的三分之二，右上和左下两个角留在光外。再宽一点两角就保不住，
 * 整屏又回到一片匀的亮；再窄一点它就不像一束光，像一条带子。
 *
 * 这两个数还定下了顶点的远近：比值 1.8 意味着顶点落在起点之前 1.2 倍轴长处，
 * 也就是屏幕左上角外一屏多的地方。比值再大顶点就压进画面里，光锥收成一个尖，像手电筒；
 * 再小则两条边几乎平行，锥就成了带子。
 */
private const val BEAM_NEAR_HALF = 0.22f
private const val BEAM_FAR_HALF = 0.40f

/**
 * 光锥的横截面：偏离轴多远（1 是锥边）对应多浓。
 *
 * 中间满、到边收零，中段那两档让曲线鼓一点——线性过渡看着像一块半透明的板子，
 * 鼓起来才像空气里的一束光。明色主题下这条曲线整个反过来用，见 [BeamGeometry.sweep]。
 */
private val BEAM_PROFILE = listOf(
    1f to 0f,
    0.72f to 0.30f,
    0.40f to 0.74f,
    0f to 1f,
)

/**
 * 明色主题下压在光锥之外的那一层暖褐。
 *
 * 亮纸上没法加光：纸已经是 #F7EFE2，Screen 上去只剩三四个色阶的余量，怎么调都是「什么都
 * 没发生」。改成压暗锥外一档，光锥自己就是那块没被压过的纸——画上光的办法从来是先画暗。
 * 取和四角压暗同一个褐（[VIGNETTE_INK]），两层叠在角上才是同一种暗，不会显出两个色相。
 */
private val BEAM_SHADE_LIGHT = VIGNETTE_INK

/** 压暗那一档的张角倍数，理由见 [BeamGeometry.sweep] */
private const val BEAM_SHADE_SPREAD = 1.7f

private const val TWO_PI = (2 * PI).toFloat()

private const val DUST_COUNT = 14
private const val DUST_SEED = 20260831L
private const val DUST_T_FROM = 0.16f
private const val DUST_T_TO = 0.92f
private const val DUST_ACROSS = 0.60f
private const val DUST_RADIUS_MIN = 0.8f
private const val DUST_RADIUS_SPAN = 1.4f
private const val DUST_ALPHA_MIN = 0.10f
private const val DUST_ALPHA_SPAN = 0.20f
/** 明色主题下尘埃再收一档：同样的深浅压在亮纸上比压在暗底上显眼得多 */
private const val DUST_ALPHA_LIGHT_SCALE = 0.62f
/** 一个循环里尘埃往下飘多远，以及横向跟着挪的比例——竖直落下太规整，略微斜着才像飘 */
private val DUST_DRIFT: Dp = 10.dp
private const val DUST_DRIFT_X_RATIO = 0.25f
/**
 * 飘一个来回的时长。
 *
 * 6.2 秒略长于这一层最长的寿命（当天首看约 6.5 秒），所以整场看下来尘埃只是一直在缓缓下沉，
 * 不会走到循环的接缝、跳回原处。
 */
private const val DUST_DRIFT_MS = 6200

private val RAIL_INSET: Dp = 7.dp
private val RAIL_HOLE_W: Dp = 9.dp
private val RAIL_HOLE_H: Dp = 13.dp
private val RAIL_HOLE_GAP: Dp = 11.dp
/** 片边线离齿孔多远、多粗 */
private val RAIL_LINE_GAP: Dp = 5.dp
private val RAIL_LINE_W: Dp = 0.8.dp
/**
 * 齿孔和片边线的墨，明暗各一档。
 *
 * 明色这两档是从 0.05 / 0.04 提上来的。那两个值是照着暗色定的：暗底上 0.07 的奶色墨
 * 已经能看出一排孔，而亮纸上 0.05 的褐墨在 #F7EFE2 上只有 1.08:1——不是「淡」，是没画。
 * 齿孔是整屏唯一的硬边元素，看不见就等于背景只剩一团光和一层颗粒，画面立不住。
 *
 * 0.15 / 0.10 是「看得出是压在纸上的孔，又不至于成为一排图案」的一档：孔在纸上约 1.3:1,
 * 边线更淡一档，两者仍在正文可读线之下——它们是纸的边界，不是要读的东西。
 */
private const val RAIL_INK_LIGHT = 0.15f
private const val RAIL_INK_DARK = 0.11f
private const val RAIL_LINE_INK_LIGHT = 0.10f
private const val RAIL_LINE_INK_DARK = 0.07f

/**
 * 撕口虚线、底部那一条、日期块、海报尺寸与回落阈值都搬去了 StampPage：
 * 开屏、日签卡、导出图现在是同一页的三种呈现，那些数只能有一份。
 */

/**
 * 这一层 alpha 动画的时长档位。非退场那一档现在只是个占位——起手就目标 1，
 * 动画不跑；留着是为了退场那条路有一致的写法。
 */
private const val ENTER_FADE_MS = 320

private val BeamEasing = CubicBezierEasing(0.22f, 0.7f, 0.25f, 1f)
private val RiseEasing = CubicBezierEasing(0.2f, 0.75f, 0.28f, 1f)
private val PosterEasing = CubicBezierEasing(0.2f, 0.75f, 0.28f, 1f)

/** 印章专用：起手就快、末尾硬收，模拟压下去到底的手感 */
private val SealEasing = CubicBezierEasing(0.16f, 0.9f, 0.2f, 1f)
