package com.tracktosearch.ui.screen.dailystamp

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapShader
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface
import android.net.Uri
import android.os.Environment
import android.text.TextPaint
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.FileProvider
import androidx.core.graphics.drawable.toBitmap
import com.tracktosearch.R
import com.tracktosearch.ui.component.SaveToAlbumResult
import com.tracktosearch.ui.component.queryExistingFile
import com.tracktosearch.ui.component.saveBitmapToAlbum
import com.tracktosearch.ui.screen.splash.SplashPalette
import com.tracktosearch.ui.screen.splash.grainTile
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.math.ceil
import kotlin.math.roundToInt

/**
 * 日签卡片的导出。
 *
 * 位图是把卡面那次绘制录下来的 Picture 重放一遍（见 DailyStampCardOverlay 的
 * captureCardPicture），也就是屏幕上那张卡的原样——不像观看统计那样另写一套 Canvas
 * 绘制。卡片是一块固定尺寸、不滚动的内容，录下来即所得，再手绘一遍只会出现
 * 「存下来的和看到的不一样」。
 *
 * 只有一处例外：卡面上方多接一条落款带（见 [StampBrand]），屏幕上没有它。
 *
 * 存 PNG 而不是 JPEG：卡面是大面积纯色加细字，JPEG 会在字缘留下彩边。
 */

/** 相册里单独开一个子目录，和海报保存的图分开 */
private const val ALBUM_SUB_DIR = "TrackToSearch/DailyStamp"

/** cacheDir 下的分享目录，名字要和 res/xml/file_paths.xml 里声明的 cache-path 对上 */
private const val SHARE_CACHE_DIR = "share"

private fun stamp(date: LocalDate): String =
    date.format(DateTimeFormatter.ofPattern("yyyyMMdd", Locale.US))

private fun fileName(date: LocalDate): String = "TrackToSearch_DailyStamp_${stamp(date)}.png"

/**
 * 导出图顶上那条落款带：应用图标一行，应用名一行，都水平居中。
 *
 * 只画在存下来／分享出去的那张图上，屏幕上的卡片没有它——卡片本来就在应用里，落款是废话；
 * 发出去之后这两行是唯一说明这张票根出自哪儿的东西。
 *
 * 接在卡面上方新加的一条带子里，而不是盖在票头那一行上：那一行左右已经有日期和编号，
 * 再塞进去就是三样东西挤一行。带子和卡面同底色、同一张噪点瓦片、同一个颗粒强度，
 * 接起来是一整张纸；差一点纹理，导出图上就是一道横线。
 *
 * 尺寸全按 [density] 换算，字号还跟系统字体缩放：卡面本身是按当前缩放渲染后录下来的，
 * 落款不跟着走，字体调大的机器上这两行会显得比卡片小一号。
 */
internal class StampBrand(context: Context, palette: SplashPalette, density: Density) {

    private val name: String = context.getString(R.string.app_name)
    private val iconPx: Float = with(density) { BRAND_ICON.toPx() }
    private val topPx: Float = with(density) { BRAND_TOP.toPx() }
    private val gapPx: Float = with(density) { BRAND_GAP.toPx() }
    private val bottomPx: Float = with(density) { BRAND_BOTTOM.toPx() }
    private val cornerPx: Float = with(density) { BRAND_CORNER.toPx() }

    /**
     * 图标解析不出来时（自适应图标异常等）整带退化成一行应用名，不让导出整体失败。
     *
     * 拖到第一次导出才解码：这个类在卡片打开那一下就建好了，而多数人打开卡片是看一眼就
     * 关掉。自适应图标要解析一遍 XML，塞进那一帧只是白掉帧。
     */
    private val icon: Bitmap? by lazy {
        try {
            val side = iconPx.roundToInt().coerceAtLeast(1)
            context.packageManager.getApplicationIcon(context.packageName).toBitmap(side, side)
        } catch (_: Exception) {
            null
        }
    }

    /** 带子的底：卡面底色，颗粒已经乘进去了，见 [grainedPaper] */
    private val paperPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        shader = BitmapShader(
            grainedPaper(palette.sheet.toArgb()),
            Shader.TileMode.REPEAT,
            Shader.TileMode.REPEAT,
        )
    }

    private val iconPaint = Paint(Paint.FILTER_BITMAP_FLAG)

    /** 落款走次级墨色：它是注解，不该和台词抢。字重压到粗体是因为这一行只有 12sp */
    private val namePaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
        textSize = with(density) { BRAND_NAME.toPx() }
        color = palette.inkSoft.toArgb()
        typeface = Typeface.create(Typeface.SERIF, Typeface.BOLD)
        letterSpacing = BRAND_NAME_SPACING
    }

    /** 这条带子占多少像素高，导出位图按它加高，卡面按它下移。读它会连带解码图标 */
    val bandHeightPx: Int by lazy {
        ceil(
            topPx +
                (if (icon != null) iconPx + gapPx else 0f) +
                (namePaint.descent() - namePaint.ascent()) +
                bottomPx
        ).toInt()
    }

    /**
     * 把带子画在 [canvas] 顶上，[widthPx] 是卡面宽度。调用方随后把卡面下移 [bandHeightPx] 回放。
     *
     * 带子往下多铺一个圆角的量：卡面自己 clip 过圆角，它顶上那两个角是透明的，不垫在下面
     * 导出图上就缺两块。垫上之后整张图是「顶上圆、腰是直的、底下还是卡面那两个圆角」，
     * 也就是把票根往上接长了一截，而不是另贴了一块牌子。
     */
    fun draw(canvas: Canvas, widthPx: Float) {
        val band = Path().apply {
            addRoundRect(
                RectF(0f, 0f, widthPx, bandHeightPx + cornerPx),
                floatArrayOf(cornerPx, cornerPx, cornerPx, cornerPx, 0f, 0f, 0f, 0f),
                Path.Direction.CW,
            )
        }
        canvas.drawPath(band, paperPaint)

        var y = topPx
        icon?.let { bitmap ->
            val left = (widthPx - iconPx) / 2f
            val dst = RectF(left, y, left + iconPx, y + iconPx)
            // 圆角裁切：方形图标压在暖纸上显得生硬，和统计分享图同一处理
            val radius = iconPx * BRAND_ICON_CORNER
            canvas.save()
            canvas.clipPath(Path().apply { addRoundRect(dst, radius, radius, Path.Direction.CW) })
            canvas.drawBitmap(bitmap, null, dst, iconPaint)
            canvas.restore()
            y += iconPx + gapPx
        }
        // ascent 是负值，减掉它才是从行顶量到基线
        canvas.drawText(
            name,
            (widthPx - namePaint.measureText(name)) / 2f,
            y - namePaint.ascent(),
            namePaint,
        )
    }
}

@Composable
internal fun rememberStampBrand(palette: SplashPalette): StampBrand {
    val context = LocalContext.current
    val density = LocalDensity.current
    return remember(context, palette, density) { StampBrand(context, palette, density) }
}

/**
 * 把噪点瓦片乘进卡面底色，得到一张「已经有颗粒的纸」，平铺就能用。
 *
 * 不在画布上另叠一层 Multiply：android.graphics 里 PorterDuff.Mode.MULTIPLY 是 Modulate，
 * 连 alpha 一起乘，8% 的一层盖下来会把整条带子的不透明度也压到 8%；而卡面那层颗粒走的是
 * Compose 的 BlendMode.Multiply，那是不动 alpha 的另一个算子。与其按版本分叉去要
 * android.graphics.BlendMode（API 29 才有），不如把这点乘法直接算在瓦片上。
 *
 * 算式和 Compose 那边逐像素相同：src 预乘后是 [BRAND_GRAIN_ALPHA]×灰度，
 * kMultiply 出来就是 底色 ×(1 − a + a×灰度)。128×128 一张，只在建这个类时算一遍。
 */
private fun grainedPaper(paperArgb: Int): Bitmap {
    val tile = grainTile()
    val size = tile.width
    val grain = IntArray(size * size)
    tile.getPixels(grain, 0, size, 0, 0, size, size)
    val red = (paperArgb shr 16) and 0xFF
    val green = (paperArgb shr 8) and 0xFF
    val blue = paperArgb and 0xFF
    val pixels = IntArray(grain.size) { index ->
        // 瓦片是灰的，取一个通道就够
        val grey = (grain[index] and 0xFF) / 255f
        val factor = 1f - BRAND_GRAIN_ALPHA + BRAND_GRAIN_ALPHA * grey
        (0xFF shl 24) or
            ((red * factor).roundToInt().coerceIn(0, 255) shl 16) or
            ((green * factor).roundToInt().coerceIn(0, 255) shl 8) or
            (blue * factor).roundToInt().coerceIn(0, 255)
    }
    return Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888).apply {
        setPixels(pixels, 0, size, 0, 0, size, size)
    }
}

private val BRAND_TOP: Dp = 20.dp
private val BRAND_ICON: Dp = 36.dp
private val BRAND_GAP: Dp = 9.dp
private val BRAND_BOTTOM: Dp = 8.dp
/** 与卡面圆角一致（DailyStampCardOverlay 里 clip 的 11dp），接缝处轮廓才连得上 */
private val BRAND_CORNER: Dp = 11.dp
private val BRAND_NAME: TextUnit = 12.sp
/** 图标圆角占边长的比例，与统计分享图的 22/84 同一档 */
private const val BRAND_ICON_CORNER = 0.26f
/** 与激活登录页标题同字距：34sp 下的 -2.04sp ≈ -0.06em（Paint 的 letterSpacing 相对字号） */
private const val BRAND_NAME_SPACING = -0.06f
/** 与卡面颗粒同强度，见 DailyStampCardOverlay 的 GRAIN_ALPHA */
private const val BRAND_GRAIN_ALPHA = 0.08f

/**
 * 是否含有可导出的卡面像素。
 *
 * 空白快照通常是全透明或纯白。逐行扫描并在发现第一个非透明、非纯白像素时立即返回，
 * 不额外复制整张位图；真实卡面的暖色背景会在第一行就通过。
 */
internal fun Bitmap.hasDailyStampVisualContent(): Boolean {
    if (isRecycled || width <= 0 || height <= 0) return false

    val row = IntArray(width)
    for (y in 0 until height) {
        getPixels(row, 0, width, 0, y, width, 1)
        if (row.any { pixel ->
                android.graphics.Color.alpha(pixel) != 0 &&
                    (android.graphics.Color.red(pixel) != 255 ||
                        android.graphics.Color.green(pixel) != 255 ||
                        android.graphics.Color.blue(pixel) != 255)
            }
        ) {
            return true
        }
    }
    return false
}

/** 在任何相册或分享文件写入前拦截透明/纯白快照。 */
internal fun requireExportableStampBitmap(bitmap: Bitmap): Bitmap = bitmap.also {
    require(it.hasDailyStampVisualContent()) { "Daily stamp capture is blank" }
}

/** 存进相册。同名文件已存在时返回 [SaveToAlbumResult.ALREADY_EXISTS]，不重复写一份 */
internal suspend fun saveStampCard(
    context: Context,
    bitmap: Bitmap,
    date: LocalDate,
): SaveToAlbumResult {
    val exportableBitmap = withContext(Dispatchers.Default) {
        requireExportableStampBitmap(bitmap)
    }
    return saveBitmapToAlbum(
        context = context,
        bitmap = exportableBitmap,
        filename = fileName(date),
        subDirectory = ALBUM_SUB_DIR,
    )
}

/**
 * 分享出去的那张图是从哪来的。
 *
 * 分两档不只是为了记录来源：[Album] 是相册里的正式一份，用户分享完还留着，值得告诉他
 * 一声；[Cache] 是 cacheDir 里的临时文件，随时会被系统清掉，提示「已保存」反而是骗人。
 */
internal sealed interface StampShareSource {
    val uri: Uri

    /** 相册里的那一份。[alreadyExisted] 为真表示今天这张先前已经存过，这次没重复写 */
    data class Album(override val uri: Uri, val alreadyExisted: Boolean) : StampShareSource

    /** 写不进相册时退到 cacheDir + FileProvider 的临时一份 */
    data class Cache(override val uri: Uri) : StampShareSource
}

/**
 * 先把卡片存进相册，再把相册里那一项的 URI 交出去分享。
 *
 * 顺序是刻意的：分享完通常还想留一张，让用户为同一张图点两次是多余的；而且相册的
 * content URI 是系统媒体库里的一条正式记录，对接收方 App 的兼容性比临时授权的
 * FileProvider URI 好。
 *
 * 相册写不进去就退到 cacheDir + FileProvider。API 26-28 基本一定走这条：那几档往公共
 * 媒体库写要 WRITE_EXTERNAL_STORAGE 运行时权限，而本应用没有申请这条权限（存相册是
 * 顺手的附加能力，不值得为它多要一个权限）；MediaStore 的 RELATIVE_PATH 列也是 Q 才
 * 有的，在 26-28 上查询和写入都会直接抛异常，而不是好好地返回一个失败。
 *
 * 两条路都走不通时抛出去，让调用方弹提示——静默失败会让人以为分享成功了。
 */
internal suspend fun stampShareSource(
    context: Context,
    bitmap: Bitmap,
    date: LocalDate,
): StampShareSource {
    val exportableBitmap = withContext(Dispatchers.Default) {
        requireExportableStampBitmap(bitmap)
    }
    return albumShareSource(context, exportableBitmap, date)
        ?: StampShareSource.Cache(cacheShareUri(context, exportableBitmap, date))
}

/** 相册这一档：写进去，再把 URI 查回来。任何一步不成就返回 null，交给 FileProvider 兜底 */
private suspend fun albumShareSource(
    context: Context,
    bitmap: Bitmap,
    date: LocalDate,
): StampShareSource.Album? {
    val filename = fileName(date)
    return try {
        val alreadyExisted = when (
            saveBitmapToAlbum(
                context = context,
                bitmap = bitmap,
                filename = filename,
                subDirectory = ALBUM_SUB_DIR,
            )
        ) {
            SaveToAlbumResult.SAVED -> false
            SaveToAlbumResult.ALREADY_EXISTS -> true
            SaveToAlbumResult.FAILED -> return null
        }
        albumUri(context, filename)?.let { StampShareSource.Album(it, alreadyExisted) }
    } catch (e: CancellationException) {
        throw e
    } catch (_: Exception) {
        // 这里不按版本号分支：Q 以下写相册会直接抛，抛了就当相册这条路走不通
        null
    }
}

/**
 * 查回刚写进相册那一项的 URI。
 *
 * 两种 relative_path 写法都查一遍：媒体库存下来的值带尾斜杠，而写入时传的没有，
 * 只按一种查会在部分系统上查不到（统计分享长图那边同样处理）。
 */
private suspend fun albumUri(context: Context, filename: String): Uri? =
    withContext(Dispatchers.IO) {
        val relativePath = Environment.DIRECTORY_PICTURES + "/" + ALBUM_SUB_DIR
        queryExistingFile(context, filename, relativePath)
            ?: queryExistingFile(context, filename, "$relativePath/")
    }

/**
 * 写进 cacheDir 再交给 FileProvider，返回可分享的 URI。
 *
 * 每天一个固定文件名，重复分享同一天会覆盖上一次的临时文件而不是越积越多。
 */
private suspend fun cacheShareUri(
    context: Context,
    bitmap: Bitmap,
    date: LocalDate,
): Uri = withContext(Dispatchers.IO) {
    val dir = File(context.cacheDir, SHARE_CACHE_DIR).apply { if (!exists()) mkdirs() }
    val file = File(dir, fileName(date))
    FileOutputStream(file).use { out ->
        bitmap.compress(Bitmap.CompressFormat.PNG, 100, out)
    }
    FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
}
