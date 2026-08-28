package com.tracktosearch.ui.screen.statistics

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.DashPathEffect
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface
import android.net.Uri
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.res.stringResource
import androidx.core.content.FileProvider
import androidx.core.graphics.drawable.toBitmap
import com.tracktosearch.R
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.cos
import kotlin.math.sin
import kotlin.random.Random

/** 分享长图里的一个总览格子 */
@Immutable
data class ShareTile(
    val value: String,
    val unit: String?,
    val label: String,
    val secondary: String? = null,
)

/** 分享长图里的一条类型排行 */
@Immutable
data class ShareGenre(
    val name: String,
    val count: String,
    /** 相对第一名的长度比例，0f~1f */
    val fraction: Float,
)

/**
 * 分享长图所需的全部内容。
 *
 * 文案在 Composable 里就取好（stringResource / localizedGenreName 需要组合环境），
 * 渲染层只认字符串和数字，这样绘制可以整段放到后台线程，也便于单独测量高度。
 */
@Immutable
data class StatisticsShareData(
    val appName: String,
    val cardTitle: String,
    val dateText: String,
    val heroLabel: String,
    val heroValue: String,
    val heroUnit: String,
    val heroSentence: String,
    val overviewTitle: String,
    val tiles: List<ShareTile>,
    val heatmapTitle: String,
    val heatmapRange: String,
    /** 13 周 × 7 天的观看次数，未来日期填 -1（画成空位） */
    val heatmapWeeks: List<List<Int>>,
    val lessLabel: String,
    val moreLabel: String,
    val genresTitle: String,
    val genres: List<ShareGenre>,
    val wordsTitle: String,
    val words: List<WordCloudItem>,
    val tagline: String,
)

/**
 * 从 UI 状态凑出分享长图的内容。
 *
 * 必须在组合里调用：文案要过 stringResource，类型名要过 localizedGenreName。
 * 总览未就绪时返回 null，调用方据此禁用分享按钮——半份数据出图没有意义。
 */
@Composable
internal fun statisticsShareData(
    uiState: StatisticsUiState,
    locale: Locale,
    highlight: StatisticsHighlight,
): StatisticsShareData? {
    if (!uiState.overviewReady) return null
    val heroText = statisticsHeroText(highlight)
    val unitTitles = stringResource(R.string.statistics_unit_titles)
    val dateText = remember(locale) {
        val format = if (locale == Locale.CHINESE) {
            SimpleDateFormat("yyyy年M月d日", Locale.CHINESE)
        } else {
            SimpleDateFormat("yyyy/M/d", locale)
        }
        format.format(Date())
    }
    val tiles = listOf(
        ShareTile(
            value = uiState.totalMovieCount.toString(),
            unit = unitTitles,
            label = stringResource(R.string.statistics_movies)
        ),
        ShareTile(
            value = uiState.showsWatchedCount.toString(),
            unit = unitTitles,
            label = stringResource(R.string.statistics_shows),
            secondary = if (
                uiState.showsCompletedReady &&
                uiState.showsCompletedCount != uiState.showsWatchedCount
            ) {
                stringResource(R.string.statistics_shows_completed, uiState.showsCompletedCount)
            } else {
                null
            }
        ),
        ShareTile(
            value = uiState.totalEpisodeCount.toString(),
            unit = stringResource(R.string.statistics_unit_episodes),
            label = stringResource(R.string.statistics_episodes)
        ),
        ShareTile(
            value = uiState.totalRatings.toString(),
            unit = stringResource(R.string.statistics_unit_times),
            label = stringResource(R.string.statistics_overview_rated)
        ),
        ShareTile(
            value = uiState.thisMonthWatched.toString(),
            unit = unitTitles,
            label = stringResource(R.string.statistics_this_month)
        ),
        ShareTile(
            value = uiState.thisYearWatched.toString(),
            unit = unitTitles,
            label = stringResource(R.string.statistics_this_year)
        ),
    )
    // 分享图固定看最近 13 周，不跟随页面里的翻页位置：分享出去的应该是「现在」
    val grid = remember(uiState.heatmapData, uiState.heatmapReady, locale) {
        if (uiState.heatmapReady) buildHeatmapGrid(uiState.heatmapData, 0, locale) else null
    }
    val topGenres = remember(uiState.genreDistribution) {
        uiState.genreDistribution.entries
            .sortedByDescending { it.value }
            .take(3)
            .map { it.key to it.value }
    }
    val maxGenreCount = (topGenres.firstOrNull()?.second ?: 1).coerceAtLeast(1)
    val genres = topGenres.map { (key, count) ->
        ShareGenre(
            name = localizedGenreName(key),
            count = stringResource(R.string.statistics_share_genre_count, count),
            fraction = count.toFloat() / maxGenreCount
        )
    }
    return StatisticsShareData(
        appName = stringResource(R.string.app_name),
        cardTitle = stringResource(R.string.statistics_share_card_title),
        dateText = dateText,
        heroLabel = heroText.label,
        heroValue = heroText.value,
        heroUnit = heroText.unit,
        heroSentence = heroText.sentence,
        overviewTitle = stringResource(R.string.statistics_overview),
        tiles = tiles,
        heatmapTitle = stringResource(R.string.statistics_share_heatmap),
        heatmapRange = grid?.let { "${it.rangeStart} – ${it.rangeEnd}" }.orEmpty(),
        heatmapWeeks = grid?.weeks?.map { week ->
            week.map { if (it.isFuture) -1 else it.count }
        }.orEmpty(),
        lessLabel = stringResource(R.string.statistics_less),
        moreLabel = stringResource(R.string.statistics_more),
        genresTitle = stringResource(R.string.statistics_share_top_genres),
        genres = genres,
        wordsTitle = stringResource(R.string.statistics_share_wordcloud),
        words = uiState.wordCloud,
        tagline = stringResource(R.string.statistics_share_tagline),
    )
}

// 分享图固定暖纸色系，不跟随 App 深色模式，理由见 analyticsPaletteLight
private const val BG_TOP = 0xFFF2E3CE.toInt()
private const val BG_BOTTOM = 0xFFE6D0B4.toInt()
private const val PAPER = 0xFFFCF6EA.toInt()
private const val TILE = 0xFFF5E9D8.toInt()
private const val INK = 0xFF46301F.toInt()
private const val INK_SOFT = 0xFF8A6A50.toInt()
private const val ACCENT = 0xFF996345.toInt()

/** 输出宽度：1080 是主流社交平台单图不再二次压缩的宽度 */
private const val IMAGE_WIDTH = 1080

/** 纸卡到画布边缘的留白 */
private const val MARGIN = 44f

/** 纸卡内边距 */
private const val PAD = 56f

private const val CONTENT_WIDTH = IMAGE_WIDTH - (MARGIN + PAD) * 2

private const val HEATMAP_COLS = 13
private const val HEATMAP_ROWS = 7

/** 上下两条胶片齿孔带的高度 */
private const val FILM_BAND = 46f

/** 纸卡顶边：让出齿孔带 + 一点呼吸 */
private const val CARD_TOP = FILM_BAND + 26f

/** 内容起始 y */
private const val CONTENT_TOP = CARD_TOP + PAD

/** 内容底部之后还要留的高度（纸卡内边距 + 呼吸 + 齿孔带） */
private const val CONTENT_BOTTOM_EXTRA = PAD + 26f + FILM_BAND

/**
 * 渲染统计分享长图并写入 cacheDir/share，返回可分享的 content URI。
 *
 * 先用 1×1 的画布跑一遍布局拿到总高度（所有绘制都被裁掉，只有文字测量真正生效），
 * 再按这个高度建位图正式画一遍。比预估高度靠谱：文案长度随语言差别很大，
 * 预估偏小会截断，偏大则底部一大片空白。
 */
suspend fun renderStatisticsShareImage(
    context: Context,
    data: StatisticsShareData,
): Uri {
    val bitmap = withContext(Dispatchers.Default) {
        val icon = loadAppIcon(context)
        val probe = Bitmap.createBitmap(1, 1, Bitmap.Config.ARGB_8888)
        val height = try {
            drawContent(Canvas(probe), data, icon).toInt() + CONTENT_BOTTOM_EXTRA.toInt()
        } finally {
            probe.recycle()
        }
        Bitmap.createBitmap(IMAGE_WIDTH, height, Bitmap.Config.ARGB_8888).also { bmp ->
            val canvas = Canvas(bmp)
            drawBackdrop(canvas, height)
            drawContent(canvas, data, icon)
        }
    }
    return withContext(Dispatchers.IO) {
        val dir = File(context.cacheDir, "share").apply { if (!exists()) mkdirs() }
        val file = File(dir, "TraktoSearch-statistics.png")
        FileOutputStream(file).use { out ->
            bitmap.compress(Bitmap.CompressFormat.PNG, 100, out)
        }
        bitmap.recycle()
        FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
    }
}

/** 应用图标取不到时（自适应图标解析失败等）返回 null，头部退化成纯文字，不让分享整体失败。 */
private fun loadAppIcon(context: Context): Bitmap? = try {
    context.packageManager.getApplicationIcon(context.packageName).toBitmap(96, 96)
} catch (e: Exception) {
    null
}

private fun textPaint(
    size: Float,
    color: Int,
    bold: Boolean = false,
    serif: Boolean = false,
    spacing: Float = 0f,
): TextPaint {
    val base = when {
        serif && bold -> Typeface.create(Typeface.SERIF, Typeface.BOLD)
        serif -> Typeface.SERIF
        bold -> Typeface.DEFAULT_BOLD
        else -> Typeface.DEFAULT
    }
    return TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
        textSize = size
        this.color = color
        typeface = base
        letterSpacing = spacing
    }
}

private fun fillPaint(color: Int): Paint =
    Paint(Paint.ANTI_ALIAS_FLAG).apply { this.color = color }

private fun buildLayout(
    text: String,
    width: Int,
    paint: TextPaint,
    alignment: Layout.Alignment = Layout.Alignment.ALIGN_NORMAL,
    lineSpacingMultiplier: Float = 1.25f,
): StaticLayout = StaticLayout.Builder
    .obtain(text, 0, text.length, paint, width.coerceAtLeast(1))
    .setAlignment(alignment)
    .setLineSpacing(0f, lineSpacingMultiplier)
    .setIncludePad(false)
    .build()

private fun drawLayout(canvas: Canvas, layout: StaticLayout, x: Float, y: Float) {
    canvas.save()
    canvas.translate(x, y)
    layout.draw(canvas)
    canvas.restore()
}

/**
 * 画一段可换行文字，返回占用高度。
 *
 * 用 StaticLayout 而不是 Canvas.drawText：分享图里句子长度随语言差别很大，
 * drawText 不换行会直接画到画布外。
 */
private fun drawParagraph(
    canvas: Canvas,
    text: String,
    x: Float,
    y: Float,
    width: Int,
    paint: TextPaint,
    alignment: Layout.Alignment = Layout.Alignment.ALIGN_NORMAL,
    lineSpacingMultiplier: Float = 1.25f,
): Float {
    val layout = buildLayout(text, width, paint, alignment, lineSpacingMultiplier)
    drawLayout(canvas, layout, x, y)
    return layout.height.toFloat()
}

/**
 * 背景：暖色竖向渐变 + 上下胶片齿孔带 + 一张带柔影的纸卡。
 *
 * 齿孔带放在画布最外沿而不是纸卡里：分享图在别人聊天列表里通常只露出顶部一小条，
 * 齿孔在最外沿才能一眼认出这是「影视」内容。
 */
private fun drawBackdrop(canvas: Canvas, height: Int) {
    val w = IMAGE_WIDTH.toFloat()
    val h = height.toFloat()
    canvas.drawPaint(
        Paint().apply {
            shader = LinearGradient(0f, 0f, 0f, h, BG_TOP, BG_BOTTOM, Shader.TileMode.CLAMP)
        }
    )

    // 齿孔带：深色底 + 一排圆角孔
    val bandPaint = fillPaint(INK).apply { alpha = 38 }
    canvas.drawRect(0f, 0f, w, FILM_BAND, bandPaint)
    canvas.drawRect(0f, h - FILM_BAND, w, h, bandPaint)
    val holePaint = fillPaint(BG_TOP)
    val holeW = 34f
    val holeH = 20f
    val holeGap = 26f
    val stride = holeW + holeGap
    val count = ((w + holeGap) / stride).toInt()
    val startX = (w - (count * stride - holeGap)) / 2f
    for (i in 0 until count) {
        val left = startX + i * stride
        canvas.drawRoundRect(
            RectF(left, (FILM_BAND - holeH) / 2f, left + holeW, (FILM_BAND + holeH) / 2f),
            5f, 5f, holePaint
        )
        canvas.drawRoundRect(
            RectF(left, h - (FILM_BAND + holeH) / 2f, left + holeW, h - (FILM_BAND - holeH) / 2f),
            5f, 5f, holePaint
        )
    }

    // 纸卡：柔影用 shadowLayer，画在独立 Paint 上，避免影子叠到后面的内容
    val card = RectF(MARGIN, CARD_TOP, w - MARGIN, h - CARD_TOP)
    canvas.drawRoundRect(
        card, 40f, 40f,
        fillPaint(PAPER).apply { setShadowLayer(26f, 0f, 8f, 0x33000000) }
    )
}

/** 区块标题：主题色小竖条 + 标题，和统计页里的 StatsSectionTitle 同一套语言。 */
private fun drawSectionTitle(canvas: Canvas, title: String, x: Float, y: Float): Float {
    val barW = 7f
    val barH = 32f
    canvas.drawRoundRect(RectF(x, y + 4f, x + barW, y + 4f + barH), 3.5f, 3.5f, fillPaint(ACCENT))
    val paint = textPaint(34f, INK, bold = true)
    canvas.drawText(title, x + barW + 16f, y + 4f + barH - 6f, paint)
    return barH + 10f
}

/**
 * 逐段往下画，返回内容底部 y。
 *
 * 同一个函数既用于测量（画到 1×1 位图上，绘制全被裁掉）又用于正式绘制，
 * 保证两次的布局逻辑不可能走偏。
 */
private fun drawContent(canvas: Canvas, data: StatisticsShareData, icon: Bitmap?): Float {
    val left = MARGIN + PAD
    val width = CONTENT_WIDTH
    var y = CONTENT_TOP

    y = drawHeader(canvas, data, icon, left, y, width)
    y += 30f
    y = drawHero(canvas, data, left, y, width)
    y += 34f

    y += drawSectionTitle(canvas, data.overviewTitle, left, y)
    y += 14f
    y = drawTiles(canvas, data.tiles, left, y, width)

    if (data.heatmapWeeks.isNotEmpty()) {
        y += 34f
        y += drawSectionTitle(canvas, data.heatmapTitle, left, y)
        y += 6f
        canvas.drawText(data.heatmapRange, left, y + 22f, textPaint(24f, INK_SOFT))
        y += 40f
        y = drawHeatmap(canvas, data, left, y, width)
    }

    if (data.genres.isNotEmpty()) {
        y += 34f
        y += drawSectionTitle(canvas, data.genresTitle, left, y)
        y += 14f
        y = drawGenres(canvas, data.genres, left, y, width)
    }

    if (data.words.isNotEmpty()) {
        y += 34f
        y += drawSectionTitle(canvas, data.wordsTitle, left, y)
        y += 10f
        y = drawWords(canvas, data.words, left, y, width)
    }

    y += 40f
    y = drawFooter(canvas, data, left, y, width)
    return y
}

private fun drawHeader(
    canvas: Canvas,
    data: StatisticsShareData,
    icon: Bitmap?,
    left: Float,
    top: Float,
    width: Float,
): Float {
    val iconSize = 84f
    var textLeft = left
    if (icon != null) {
        val dst = RectF(left, top, left + iconSize, top + iconSize)
        // 圆角裁切：方形图标压在暖纸上显得生硬
        canvas.save()
        val clip = Path().apply { addRoundRect(dst, 22f, 22f, Path.Direction.CW) }
        canvas.clipPath(clip)
        canvas.drawBitmap(icon, null, dst, Paint(Paint.FILTER_BITMAP_FLAG))
        canvas.restore()
        textLeft = left + iconSize + 22f
    }
    canvas.drawText(data.cardTitle, textLeft, top + 38f, textPaint(38f, INK, bold = true, serif = true))
    canvas.drawText(data.dateText, textLeft, top + 74f, textPaint(24f, INK_SOFT))
    // 右上角落款：应用名，和底部 tagline 呼应
    val namePaint = textPaint(24f, ACCENT, spacing = 0.06f)
    val nameWidth = namePaint.measureText(data.appName)
    canvas.drawText(data.appName, left + width - nameWidth, top + 38f, namePaint)
    return top + iconSize
}

/**
 * Hero 块：小标签 + 一个大数字 + 一句话，和 App 里的 Hero 卡片同构。
 *
 * 先量句子高度再画底色：底色矩形要包住全部内容，句子在不同语言下 1~3 行都可能。
 */
private fun drawHero(
    canvas: Canvas,
    data: StatisticsShareData,
    left: Float,
    top: Float,
    width: Float,
): Float {
    val pad = 34f
    val innerLeft = left + pad
    val innerWidth = (width - pad * 2).toInt()

    val labelPaint = textPaint(23f, ACCENT, bold = true, spacing = 0.18f)
    val valuePaint = textPaint(118f, INK, bold = true)
    val unitPaint = textPaint(34f, INK_SOFT)
    val sentencePaint = textPaint(29f, INK_SOFT, serif = true)

    val valueMetrics = valuePaint.fontMetrics
    val valueHeight = valueMetrics.descent - valueMetrics.ascent
    val sentenceLayout = buildLayout(data.heroSentence, innerWidth, sentencePaint)
    val blockHeight = pad + 30f + 6f + valueHeight + 16f + sentenceLayout.height + pad

    canvas.drawRoundRect(
        RectF(left, top, left + width, top + blockHeight), 30f, 30f,
        fillPaint(ACCENT).apply { alpha = 22 }
    )

    var y = top + pad
    canvas.drawText(data.heroLabel, innerLeft, y + 23f, labelPaint)
    y += 30f + 6f
    val valueBaseline = y - valueMetrics.ascent
    canvas.drawText(data.heroValue, innerLeft, valueBaseline, valuePaint)
    val valueWidth = valuePaint.measureText(data.heroValue)
    canvas.drawText(data.heroUnit, innerLeft + valueWidth + 12f, valueBaseline - 12f, unitPaint)
    y += valueHeight + 16f
    drawLayout(canvas, sentenceLayout, innerLeft, y)
    return top + blockHeight
}

/** 总览格子：2 列铺开，行数由格子数决定；统一格高，副行只有剧集格会用到。 */
private fun drawTiles(
    canvas: Canvas,
    tiles: List<ShareTile>,
    left: Float,
    top: Float,
    width: Float,
): Float {
    if (tiles.isEmpty()) return top
    val gap = 20f
    val tileWidth = (width - gap) / 2f
    val tileHeight = 168f
    val valuePaint = textPaint(58f, INK, bold = true)
    val unitPaint = textPaint(23f, INK_SOFT)
    val labelPaint = textPaint(26f, INK_SOFT)
    val secondaryPaint = textPaint(22f, INK_SOFT).apply { alpha = 180 }
    val valueMetrics = valuePaint.fontMetrics
    val rows = (tiles.size + 1) / 2

    tiles.forEachIndexed { index, tile ->
        val col = index % 2
        val row = index / 2
        val x = left + col * (tileWidth + gap)
        val y = top + row * (tileHeight + gap)
        canvas.drawRoundRect(
            RectF(x, y, x + tileWidth, y + tileHeight), 24f, 24f, fillPaint(TILE)
        )
        val innerX = x + 28f
        val valueBaseline = y + 34f - valueMetrics.ascent
        canvas.drawText(tile.value, innerX, valueBaseline, valuePaint)
        if (tile.unit != null) {
            val w = valuePaint.measureText(tile.value)
            canvas.drawText(tile.unit, innerX + w + 8f, valueBaseline - 6f, unitPaint)
        }
        canvas.drawText(tile.label, innerX, valueBaseline + 42f, labelPaint)
        if (tile.secondary != null) {
            canvas.drawText(tile.secondary, innerX, valueBaseline + 76f, secondaryPaint)
        }
    }
    return top + rows * tileHeight + (rows - 1) * gap
}

/** 观看次数到色阶：与 App 内热力图同一套分级（0 / ≤2 / ≤5 / ≤9 / 更多）。 */
private fun heatmapShareColor(count: Int): Int = when {
    count <= 0 -> 0xFFEDE0CC.toInt()
    count <= 2 -> (ACCENT and 0x00FFFFFF) or (0x47 shl 24)
    count <= 5 -> (ACCENT and 0x00FFFFFF) or (0x80 shl 24)
    count <= 9 -> (ACCENT and 0x00FFFFFF) or (0xB8 shl 24)
    else -> ACCENT
}

private fun drawHeatmap(
    canvas: Canvas,
    data: StatisticsShareData,
    left: Float,
    top: Float,
    width: Float,
): Float {
    val gap = 8f
    val cell = (width - (HEATMAP_COLS - 1) * gap) / HEATMAP_COLS
    for ((col, week) in data.heatmapWeeks.withIndex()) {
        if (col >= HEATMAP_COLS) break
        for ((row, count) in week.withIndex()) {
            if (row >= HEATMAP_ROWS) break
            // 未来日期不画格子：画成空色阶会让人以为那天没看
            if (count < 0) continue
            val x = left + col * (cell + gap)
            val y = top + row * (cell + gap)
            canvas.drawRoundRect(
                RectF(x, y, x + cell, y + cell), 7f, 7f, fillPaint(heatmapShareColor(count))
            )
        }
    }
    var y = top + HEATMAP_ROWS * cell + (HEATMAP_ROWS - 1) * gap + 22f

    // 图例：少 → 多
    val legendPaint = textPaint(22f, INK_SOFT)
    val swatch = 26f
    val swatchGap = 6f
    val lessWidth = legendPaint.measureText(data.lessLabel)
    var x = left
    canvas.drawText(data.lessLabel, x, y + swatch - 6f, legendPaint)
    x += lessWidth + 12f
    for (level in intArrayOf(0, 1, 3, 7, 12)) {
        canvas.drawRoundRect(
            RectF(x, y, x + swatch, y + swatch), 6f, 6f, fillPaint(heatmapShareColor(level))
        )
        x += swatch + swatchGap
    }
    canvas.drawText(data.moreLabel, x + 6f, y + swatch - 6f, legendPaint)
    y += swatch
    return y
}

/** 类型排行：条形长度按相对第一名的比例，颜色取浅色分析色板，与 App 内饼图同一套。 */
private fun drawGenres(
    canvas: Canvas,
    genres: List<ShareGenre>,
    left: Float,
    top: Float,
    width: Float,
): Float {
    val palette = analyticsPaletteLight().map { it.toArgb() }
    val namePaint = textPaint(29f, INK, bold = true)
    val countPaint = textPaint(25f, INK_SOFT)
    val trackHeight = 16f
    val rowHeight = 84f
    genres.forEachIndexed { index, genre ->
        val y = top + index * rowHeight
        canvas.drawText(genre.name, left, y + 28f, namePaint)
        val countWidth = countPaint.measureText(genre.count)
        canvas.drawText(genre.count, left + width - countWidth, y + 28f, countPaint)
        val trackTop = y + 46f
        canvas.drawRoundRect(
            RectF(left, trackTop, left + width, trackTop + trackHeight),
            trackHeight / 2f, trackHeight / 2f,
            fillPaint(0xFFEDE0CC.toInt())
        )
        val filled = (width * genre.fraction.coerceIn(0.04f, 1f))
        canvas.drawRoundRect(
            RectF(left, trackTop, left + filled, trackTop + trackHeight),
            trackHeight / 2f, trackHeight / 2f,
            fillPaint(palette[index % palette.size])
        )
    }
    return top + genres.size * rowHeight - (rowHeight - trackHeight - 46f)
}

/**
 * 词云：与 App 内同样的黄金角螺旋摆放（固定种子），保证同一份词表每次出图一致。
 *
 * 这里不复用 Compose 版的实现：那份依赖 TextMeasurer 与 Density，只能在组合里跑，
 * 而分享图要在后台线程用 android.graphics 画。
 */
private fun drawWords(
    canvas: Canvas,
    words: List<WordCloudItem>,
    left: Float,
    top: Float,
    width: Float,
): Float {
    val palette = analyticsPaletteLight().map { it.toArgb() }
    val picked = words.sortedByDescending { it.weight }.take(24)
    if (picked.isEmpty()) return top
    val maxWeight = picked.first().weight.toFloat()
    val minWeight = picked.last().weight.toFloat()
    val span = (maxWeight - minWeight).coerceAtLeast(1f)
    val areaHeight = 470f
    val rand = Random(42)
    val placed = ArrayList<RectF>(picked.size)
    val laid = ArrayList<Triple<String, TextPaint, RectF>>(picked.size)

    for ((index, item) in picked.withIndex()) {
        val t = (item.weight - minWeight) / span
        val size = 28f + 44f * t
        val paint = textPaint(size, palette[index % palette.size], serif = true)
        val fm = paint.fontMetrics
        val boxWidth = paint.measureText(item.word) + size * 0.32f
        val boxHeight = (fm.descent - fm.ascent) + size * 0.18f
        val rect = spiralPlace(boxWidth, boxHeight, width / 2f, areaHeight / 2f, placed, rand, width, areaHeight)
            ?: continue
        placed.add(rect)
        laid.add(Triple(item.word, paint, rect))
    }
    if (laid.isEmpty()) return top

    // 整体包围盒居中：螺旋从中心往外长，直接画会偏在区域中间留下上下空白
    val minX = laid.minOf { it.third.left }
    val maxX = laid.maxOf { it.third.right }
    val minY = laid.minOf { it.third.top }
    val maxY = laid.maxOf { it.third.bottom }
    val offsetX = left + (width - (maxX - minX)) / 2f - minX
    val offsetY = top - minY
    for ((text, paint, rect) in laid) {
        val fm = paint.fontMetrics
        canvas.drawText(
            text,
            rect.left + offsetX + paint.textSize * 0.16f,
            rect.top + offsetY - fm.ascent + paint.textSize * 0.09f,
            paint
        )
    }
    return top + (maxY - minY)
}

/** 阿基米德螺旋 + 黄金角找不重叠位置；超出区域即放弃（返回 null，该词跳过）。 */
private fun spiralPlace(
    boxWidth: Float,
    boxHeight: Float,
    centerX: Float,
    centerY: Float,
    placed: List<RectF>,
    rand: Random,
    areaWidth: Float,
    areaHeight: Float,
): RectF? {
    val goldenAngle = 2.399963f
    val step = maxOf(boxWidth, boxHeight) * 0.25f + 2f
    var radius = 0f
    var angle = rand.nextFloat() * (Math.PI * 2f).toFloat()
    repeat(400) {
        val cx = centerX + radius * cos(angle)
        val cy = centerY + radius * sin(angle)
        val rect = RectF(
            cx - boxWidth / 2f, cy - boxHeight / 2f,
            cx + boxWidth / 2f, cy + boxHeight / 2f
        )
        if (rect.left < 0f || rect.top < 0f || rect.right > areaWidth || rect.bottom > areaHeight) {
            return null
        }
        if (placed.none { RectF.intersects(it, rect) }) return rect
        radius += step
        angle += goldenAngle
    }
    return null
}

private fun drawFooter(
    canvas: Canvas,
    data: StatisticsShareData,
    left: Float,
    top: Float,
    width: Float,
): Float {
    val dashPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = ACCENT
        alpha = 64
        strokeWidth = 3f
        pathEffect = DashPathEffect(floatArrayOf(14f, 12f), 0f)
    }
    canvas.drawLine(left, top, left + width, top, dashPaint)
    var y = top + 52f
    val namePaint = textPaint(31f, INK, bold = true, serif = true, spacing = 0.05f)
    val nameWidth = namePaint.measureText(data.appName)
    canvas.drawText(data.appName, left + (width - nameWidth) / 2f, y, namePaint)
    y += 14f
    y += drawParagraph(
        canvas = canvas,
        text = data.tagline,
        x = left,
        y = y,
        width = width.toInt(),
        paint = textPaint(25f, INK_SOFT),
        alignment = Layout.Alignment.ALIGN_CENTER
    )
    return y
}
