package com.tracktosearch.ui.screen.dailystamp

import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import android.os.Environment
import androidx.core.content.FileProvider
import com.tracktosearch.ui.component.SaveToAlbumResult
import com.tracktosearch.ui.component.queryExistingFile
import com.tracktosearch.ui.component.saveBitmapToAlbum
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
 * 位图是把卡面那次绘制录下来的 Picture 重放一遍（见 DailyStampCardOverlay 的
 * captureCardPicture），也就是屏幕上那张卡的原样——不像观看统计那样另写一套 Canvas
 * 绘制。卡片是一块固定尺寸、不滚动的内容，录下来即所得，再手绘一遍只会出现
 * 「存下来的和看到的不一样」。
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
