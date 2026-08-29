package com.tracktosearch.ui.screen.splash

import android.graphics.Bitmap
import android.os.Build
import android.provider.Settings
import androidx.annotation.RequiresApi
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
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
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
import io.androidpoet.mirage.GrainGradient
import io.androidpoet.mirage.GrainGradientShape
import io.androidpoet.mirage.core.ShaderFit
import io.androidpoet.mirage.core.SizingParams
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
 * 停留 3 秒 → 光晕散开同时整层淡出，露出下面已经组合好的主界面。
 * 任意时刻轻触屏幕直接跳到散开阶段。
 */
@Composable
fun SplashQuoteOverlay(
    quote: SplashQuoteUi,
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

    var bloom by remember { mutableStateOf(reduceMotion) }
    var posterVisible by remember { mutableStateOf(reduceMotion) }
    var visibleLines by remember { mutableIntStateOf(if (reduceMotion) lineCount else 0) }
    var sourceVisible by remember { mutableStateOf(reduceMotion) }
    var skipVisible by remember { mutableStateOf(reduceMotion) }
    var exiting by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        if (reduceMotion) {
            delay(SplashQuoteTiming.STAY_MS)
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
            delay(SplashQuoteTiming.SKIP_AT_MS)
            skipVisible = true
        }
        delay(SplashQuoteTiming.stayStart(lineCount) + SplashQuoteTiming.STAY_MS)
        exiting = true
    }

    // 跳过和自然结束汇到同一条退场路径：都只是把 exiting 置为 true
    LaunchedEffect(exiting) {
        if (!exiting) return@LaunchedEffect
        delay(SplashQuoteTiming.EXIT_MS)
        onFinished()
    }

    val exitMs = SplashQuoteTiming.EXIT_MS.toInt()
    val layerAlpha by animateFloatAsState(
        targetValue = if (exiting) 0f else 1f,
        animationSpec = tween(durationMillis = exitMs),
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
                onClick = { if (!exiting) exiting = true }
            )
    ) {
        SplashBackdrop(
            palette = palette,
            backgroundColor = backgroundColor,
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
            color = palette.inkFaint,
            fontSize = 11.sp,
            fontFamily = FontFamily.Monospace,
            letterSpacing = 0.42.em,
            textAlign = TextAlign.Center
        )

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
        }

        Text(
            text = stringResource(R.string.splash_quote_skip),
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(bottom = 56.dp)
                .alpha(skipAlpha),
            color = palette.inkFaint,
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
        fontWeight = FontWeight.Medium,
        fontStyle = if (isEnglish) FontStyle.Italic else FontStyle.Normal,
        letterSpacing = if (isEnglish) 0.006.em else 0.012.em,
        textAlign = TextAlign.Center
    )
}

/** 出处行：破折号 + 书名号包起的片名 + 弱化的年份 */
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
        // 年份压到 70% 透明度：它是注解不是标题，同色同重会和片名抢注意力
        withStyle(SpanStyle(color = palette.inkSoft.copy(alpha = palette.inkSoft.alpha * 0.7f))) {
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
 * 海报，做成相纸装裱的样子：外层一圈奶油色卡纸 + 投影，内层图片压一层纸色。
 *
 * 压色是必要的：未处理的彩色海报直接贴在暖纸背景上，看起来像硬插进来的一块图，
 * 压掉一点饱和度之后它才像原本就印在这张纸上。
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
    val tintColor = if (palette.isDark) Color(0xFF16100B) else palette.paper
    val tintAlpha = if (palette.isDark) 0.22f else 0.14f
    Box(
        modifier = Modifier
            .size(width = 130.dp, height = 195.dp)
            .graphicsLayer {
                this.alpha = alpha
                scaleX = scale
                scaleY = scale
                translationY = riseDp.dp.toPx()
            }
            .shadow(elevation = 14.dp, shape = RoundedCornerShape(7.dp), clip = false)
            .clip(RoundedCornerShape(7.dp))
            .background(palette.cream)
            .padding(5.dp)
    ) {
        Image(
            bitmap = poster,
            contentDescription = null,
            contentScale = ContentScale.Crop,
            modifier = Modifier
                .fillMaxSize()
                .clip(RoundedCornerShape(4.dp))
                .drawWithContent {
                    drawContent()
                    drawRect(color = tintColor, alpha = tintAlpha, blendMode = BlendMode.Multiply)
                }
        )
    }
}

/**
 * 背景：暖色光晕 + 中央可读性蒙层 + 四角压暗。
 *
 * 光晕在 API 33+ 走 mirage 的 AGSL GrainGradient（Blob 形状），和主页面背景光晕
 * 用的是同一套着色器。相比手搓径向渐变，AGSL 这边是真的在片元着色器里算弥散：
 * 边缘没有可数的同心圆过渡，颗粒噪声由 shader 自己生成、顺带压掉大面积暖色渐变的色带。
 * API 26-32 没有 RuntimeShader，回退到三团径向渐变 + 平铺噪点，形近而已。
 */
@Composable
private fun SplashBackdrop(
    palette: SplashPalette,
    backgroundColor: Color,
    glowScale: Float,
    glowAlpha: Float,
    washAlpha: Float,
    grainAlpha: Float,
    vignetteAlpha: Float,
) {
    Box(modifier = Modifier.fillMaxSize()) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            ShaderGlow(palette, backgroundColor, glowScale, glowAlpha)
        } else {
            GradientGlow(palette, glowScale, glowAlpha, grainAlpha)
        }
        ReadabilityOverlay(palette, washAlpha, vignetteAlpha)
    }
}

/**
 * AGSL 光晕层。
 *
 * 缩放走 Compose 的 graphicsLayer 而不是 SizingParams.scale：前者语义确定
 * （>1 就是变大），后者是着色器世界坐标的缩放，方向反过来就得改代码。
 * 底图先放大到 [GLOW_BASE_SCALE]，保证收缩到 0.55 倍时边缘也不会露出底色。
 *
 * speed 压到 0.10：整层只活 4 秒多，这个速度刚好让暖光有一点呼吸感，
 * 又不至于为了背景动画在启动阶段满帧重绘。
 */
@RequiresApi(Build.VERSION_CODES.TIRAMISU)
@Composable
private fun ShaderGlow(
    palette: SplashPalette,
    backgroundColor: Color,
    glowScale: Float,
    glowAlpha: Float,
) {
    val colors = remember(palette) { listOf(palette.caramel, palette.ochre, palette.cream) }
    val sizing = remember { SizingParams(fit = ShaderFit.Cover) }
    GrainGradient(
        modifier = Modifier
            .fillMaxSize()
            .graphicsLayer {
                alpha = glowAlpha
                scaleX = glowScale * GLOW_BASE_SCALE
                scaleY = glowScale * GLOW_BASE_SCALE
            },
        colorBack = backgroundColor,
        colors = colors,
        shape = GrainGradientShape.Blob,
        softness = 0.92f,
        intensity = 0.46f,
        noise = palette.grainAlpha,
        speed = 0.10f,
        sizing = sizing,
    )
}

/**
 * API 26-32 回退光晕：三团径向渐变 + 平铺噪点。
 *
 * 明色主题用 Multiply 让暖光像颜料渗进纸里，暗色主题改 Screen，
 * 否则三团深棕叠在深底上会糊成一片黑。
 */
@Composable
private fun GradientGlow(
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
        val glowBlend = if (palette.isDark) BlendMode.Screen else BlendMode.Multiply
        if (glowAlpha > EPSILON) {
            drawGlow(Offset(0.16f * w, 0.17f * h), 0.42f * w * glowScale, palette.caramel, glowAlpha, glowBlend)
            drawGlow(Offset(0.91f * w, 0.49f * h), 0.49f * w * glowScale, palette.ochre, glowAlpha, glowBlend)
            drawGlow(Offset(0.21f * w, 0.84f * h), 0.56f * w * glowScale, palette.cream, glowAlpha, glowBlend)
        }
        if (grainAlpha > EPSILON) {
            drawRect(brush = grain, alpha = grainAlpha, blendMode = BlendMode.Multiply)
        }
    }
}

/** 中央纸色蒙层与四角压暗：两条都是为了台词的对比度，与光晕实现无关，两个分支共用 */
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

private fun DrawScope.drawGlow(
    center: Offset,
    radius: Float,
    color: Color,
    alpha: Float,
    blendMode: BlendMode,
) {
    if (radius <= 0f) return
    drawCircle(
        brush = Brush.radialGradient(
            0f to color,
            0.68f to color.copy(alpha = 0f),
            center = center,
            radius = radius
        ),
        radius = radius,
        center = center,
        alpha = alpha,
        blendMode = blendMode
    )
}

/**
 * 生成一小块噪点并平铺成胶片颗粒。API 26-32 专用，AGSL 分支的噪点由着色器自己出。
 *
 * 128×128 一张（64KB）平铺整屏，比放一张全屏噪点图省得多，也不用往 APK 里塞资源。
 * 固定随机种子，保证每次启动的颗粒分布一致——颗粒每次都变会看出「在闪」。
 */
private fun grainBrush(): ShaderBrush {
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
private const val WASH_ALPHA = 0.66f
private const val EPSILON = 0.001f
private const val GRAIN_TILE_PX = 128
private const val GRAIN_SEED = 20260828L

/**
 * AGSL 光晕层的基准放大倍数。
 *
 * 对应原型里 `.glow { inset: -18% }` 的做法：着色器画布本身要比屏幕大一圈，
 * 这样光斑收缩到 0.55 倍（弥散动画起点）时，四边也不会露出没有光晕的底色。
 */
private const val GLOW_BASE_SCALE = 1.45f
private val VIGNETTE_COLOR = Color(0x383C2212)
private val GlowEasing = CubicBezierEasing(0.22f, 0.7f, 0.25f, 1f)
private val RiseEasing = CubicBezierEasing(0.2f, 0.75f, 0.28f, 1f)
private val PosterEasing = CubicBezierEasing(0.2f, 0.75f, 0.28f, 1f)
