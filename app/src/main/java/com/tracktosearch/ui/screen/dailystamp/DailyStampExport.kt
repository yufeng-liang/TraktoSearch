package com.tracktosearch.ui.screen.dailystamp

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Typeface
import android.net.Uri
import android.os.Environment
import android.text.TextPaint
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.core.content.FileProvider
import androidx.core.graphics.drawable.toBitmap
import com.tracktosearch.R
import com.tracktosearch.ui.component.SaveToAlbumResult
import com.tracktosearch.ui.component.queryExistingFile
import com.tracktosearch.ui.component.saveBitmapToAlbum
import com.tracktosearch.ui.screen.splash.SplashPalette
import com.tracktosearch.ui.screen.splash.StampDesignHeight
import com.tracktosearch.ui.screen.splash.StampTearBottom
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * 日签卡片的导出。
 *
 * 位图是把那一页的绘制录下来的 Picture 重放一遍（见 DailyStampCardOverlay 的
 * captureCardPicture），也就是屏幕上那一张的原样——不像观看统计那样另写一套 Canvas
 * 绘制。那一页是一块固定尺寸、不滚动的内容，录下来即所得，再手绘一遍只会出现
 * 「存下来的和看到的不一样」。
 *
 * 页面底部那一条（撕口虚线以下那一段）在屏幕上、开屏上、导出图上都在（见 [StampBrand]）：
 * 开屏在那儿写「轻触跳过」，卡片与导出图在那儿落款。它长在页面自己留出来的槽里
 * （见 StampPage 的 footnote），所以三张只差那一行的内容。
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
 * 导出图底部那一行落款：图标在上、应用名在下，整块水平居中。
 *
 * 位置是撕口虚线以下那一段的正中（见 StampPage 的 footnote 槽与 [StampTearBottom]）：那一段
 * 是存根，落款是它唯一的内容。开屏在那儿是「轻触跳过」，日签卡与导出图在这儿是这枚落款，
 * 未来那一页则是「那天见」——三种呈现同高。
 *
 * 页面本身——纸、光锥、齿孔轨、海报、台词、印章、日期、撕口虚线、落款——都在录下来的
 * Picture 里一并重放了（屏幕与导出来自同一段绘制），所以这里不用再铺底色与颗粒，
 * 直接画在页面底部的纸面上。
 *
 * 尺寸按页面高度换算（见 [stampBrandLayout]）：导出图的分辨率跟着屏幕走（就是卡面那点像素），
 * 页内每个元素都是「设计尺寸 × 这个比例」，这一行不跟上就会在别的机器上比页内元素大一号。
 * 不跟 fontScale：图标与名字是一枚固定落款（图标本来就是 dp，不随字号变），
 * 跟着放大反而会撞到上面那条撕口虚线。
 */
internal class StampBrand(context: Context, palette: SplashPalette) {

    private val name: String = context.getString(R.string.app_name)

    /**
     * 图标解析不出来时（自适应图标异常等）整带退化成一行应用名，不让导出整体失败。
     *
     * 拖到第一次绘制才解码：日签卡一打开就会画这一行，而自适应图标要解析一遍 XML，
     * 塞进打开卡片的那一帧只是白掉帧。
     */
    private val icon: Bitmap? by lazy {
        try {
            context.packageManager.getApplicationIcon(context.packageName)
                .toBitmap(BRAND_ICON_DECODE_PX, BRAND_ICON_DECODE_PX)
        } catch (_: Exception) {
            null
        }
    }

    private val iconPaint = Paint(Paint.FILTER_BITMAP_FLAG)

    /** 落款走次级墨色：它是注解，不该和台词抢。字重压到粗体是因为这一行只有 11sp */
    private val namePaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
        color = palette.inkSoft.toArgb()
        typeface = Typeface.create(Typeface.SERIF, Typeface.BOLD)
        letterSpacing = BRAND_NAME_SPACING
    }

    /** 把这一行画进 [canvas] 底部的那一条，[widthPx]×[heightPx] 是整页的尺寸 */
    fun draw(canvas: Canvas, widthPx: Float, heightPx: Float) {
        val unit = designUnit(heightPx)
        namePaint.textSize = BRAND_NAME_SP * unit
        val layout = stampBrandLayout(
            widthPx = widthPx,
            heightPx = heightPx,
            unit = unit,
            nameWidthPx = namePaint.measureText(name),
            nameAscentPx = namePaint.ascent(),
            nameDescentPx = namePaint.descent(),
        )
        icon?.let { bitmap ->
            val left = (widthPx - layout.iconSidePx) / 2f
            val dst = RectF(
                left,
                layout.iconTopPx,
                left + layout.iconSidePx,
                layout.iconTopPx + layout.iconSidePx,
            )
            // 圆角裁切：方形图标压在暖纸上显得生硬，和统计分享图同一处理
            val radius = layout.iconSidePx * BRAND_ICON_CORNER
            canvas.save()
            canvas.clipPath(Path().apply { addRoundRect(dst, radius, radius, Path.Direction.CW) })
            canvas.drawBitmap(bitmap, null, dst, iconPaint)
            canvas.restore()
        }
        canvas.drawText(name, layout.nameLeftPx, layout.nameBaselinePx, namePaint)
    }
}

/** 1 设计 dp 在这张导出图上有多少像素。页面是按 [StampDesignHeight] 排的 */
internal fun designUnit(heightPx: Float): Float = heightPx / StampDesignHeight.value

/** 落款那一块在页面里的落点，单位是像素 */
internal class StampBrandLayout(
    val iconSidePx: Float,
    val iconTopPx: Float,
    val nameBaselinePx: Float,
    val nameLeftPx: Float,
)

/**
 * 竖排：图标在上、名字在下，整块水平居中。
 *
 * 整块在「页底 → 撕口虚线」那一段（[StampTearBottom] = 80dp）里垂直居中：那一段本来就
 * 是撕下来的存根，落款是它唯一的内容，钉死在页底只会显得下半张比上半张空。整块多高由
 * 图标边长加间距加名字那一行算出来，于是改图标尺寸时居中关系跟着走，不必再配一个底距。
 *
 * 落位一律从**页底**往上量（位图的 y 向下长，页底在 [heightPx] 那一头）：上下两个空相等
 * 时，块的顶边 = 页高 − 空 − 块高。
 *
 * 抽成纯函数只为单测：改字号或改图标尺寸时这几个数要一起动，靠人眼核对很容易漏。
 */
internal fun stampBrandLayout(
    widthPx: Float,
    heightPx: Float,
    unit: Float,
    nameWidthPx: Float,
    nameAscentPx: Float,
    nameDescentPx: Float,
): StampBrandLayout {
    val iconSide = BRAND_ICON_DP * unit
    // ascent 是负值，减掉它才是从行顶量到基线
    val nameHeight = nameDescentPx - nameAscentPx
    val block = iconSide + BRAND_GAP_DP * unit + nameHeight
    val band = StampTearBottom.value * unit
    val gap = ((band - block) / 2f).coerceAtLeast(0f)
    val blockTop = heightPx - gap - block
    val nameTop = blockTop + iconSide + BRAND_GAP_DP * unit
    return StampBrandLayout(
        iconSidePx = iconSide,
        iconTopPx = blockTop,
        nameBaselinePx = nameTop - nameAscentPx,
        nameLeftPx = (widthPx - nameWidthPx) / 2f,
    )
}

@Composable
internal fun rememberStampBrand(palette: SplashPalette): StampBrand {
    val context = LocalContext.current
    return remember(context, palette) { StampBrand(context, palette) }
}

/**
 * 屏幕上的那一行落款：和导出图共用 [StampBrand.draw] 与 [stampBrandLayout]。
 *
 * 画布是撕口虚线以下那一条（页内高 [StampTearBottom]），而 [stampBrandLayout] 的坐标系是
 * 整页——导出图那边传的是位图尺寸。于是这里把画布往上平移「这一条在页内的顶边」
 * （页高减这一条的高），落点就与导出的那一张逐像素相同：两者本来就是同一段绘制代码，
 * 导出那边重放的是这一页录下来的 Picture，这一行就在 Picture 里。
 *
 * 页高在这里自己量（[StampDesignHeight] 按当前密度换算）：这一页的密度是缩过的
 * （见 DailyStampCardOverlay 的 StampPageBox），必须在这个槽里读，读到页外的密度会
 * 整整大出一档。
 */
@Composable
internal fun StampBrandRow(brand: StampBrand, modifier: Modifier = Modifier) {
    val pageHeightPx = with(LocalDensity.current) { StampDesignHeight.toPx() }
    Canvas(modifier = modifier.fillMaxSize()) {
        translate(top = size.height - pageHeightPx) {
            brand.draw(drawContext.canvas.nativeCanvas, size.width, pageHeightPx)
        }
    }
}

/**
 * 落款那一块的设计尺寸，单位是设计 dp / 设计 sp。
 *
 * 这几个数的上下限不是审美问题而是版面约束：整块（图标 + 间距 + 名字行）要装进「页底 →
 * 撕口虚线（[StampTearBottom] 80dp）」那一段里并居中，改之前先看 StampBrandLayoutTest。
 */
private const val BRAND_ICON_DP = 32.5f
private const val BRAND_GAP_DP = 5f
/** 比开屏那行「轻触跳过」大两档：卡片那一档的字号，见 [StampTextSize] */
private const val BRAND_NAME_SP = 14f
/** 图标圆角占边长的比例，与统计分享图的 22/84 同一档 */
private const val BRAND_ICON_CORNER = 0.26f
/** 与激活登录页标题同字距：34sp 下的 -2.04sp ≈ -0.06em（Paint 的 letterSpacing 相对字号） */
private const val BRAND_NAME_SPACING = -0.06f
/**
 * 图标解码边长。
 *
 * 按最终尺寸解码就得先知道导出图多大，而那个尺寸要等这一页画完才知道；干脆解一档够大的
 * 再缩——128px 缩到 60px 上下，自适应图标的边缘仍旧干净。
 */
private const val BRAND_ICON_DECODE_PX = 128

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
