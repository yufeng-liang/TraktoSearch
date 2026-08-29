package com.tracktosearch.ui.screen.dailystamp

import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import androidx.core.content.FileProvider
import com.tracktosearch.ui.component.SaveToAlbumResult
import com.tracktosearch.ui.component.saveBitmapToAlbum
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
 * 位图来自 Compose 的 GraphicsLayer 录制，也就是屏幕上那张卡的原样——不像观看统计
 * 那样另写一套 Canvas 绘制。卡片是一块固定尺寸、不滚动的内容，录下来即所得，
 * 再手绘一遍只会出现「存下来的和看到的不一样」。
 *
 * 存 PNG 而不是 JPEG：卡面是大面积纯色加细字，JPEG 会在字缘留下彩边。
 */

/** 相册里单独开一个子目录，和海报保存的图分开 */
private const val ALBUM_SUB_DIR = "TrackToSearch/DailyStamp"

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
 * 写进 cacheDir 再交给 FileProvider，返回可分享的 URI。
 *
 * 每天一个固定文件名，重复分享同一天会覆盖上一次的临时文件而不是越积越多。
 */
internal suspend fun shareStampCardUri(
    context: Context,
    bitmap: Bitmap,
    date: LocalDate,
): Uri {
    return withContext(Dispatchers.IO) {
        val exportableBitmap = requireExportableStampBitmap(bitmap)
        val dir = File(context.cacheDir, "share").apply { if (!exists()) mkdirs() }
        val file = File(dir, fileName(date))
        FileOutputStream(file).use { out ->
            exportableBitmap.compress(Bitmap.CompressFormat.PNG, 100, out)
        }
        FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
    }
}
