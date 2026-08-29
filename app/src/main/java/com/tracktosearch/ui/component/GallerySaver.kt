package com.tracktosearch.ui.component

import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import android.os.Environment
import androidx.core.graphics.drawable.toBitmap
import coil.imageLoader
import coil.request.ImageRequest
import coil.request.SuccessResult
import com.tracktosearch.R
import com.tracktosearch.ui.util.showToast
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** 保存图片URL到相册 */
fun savePosterToGallery(
    context: Context,
    scope: CoroutineScope,
    posterUrl: String,
    title: String,
    onSaved: () -> Unit = {}
) {
    val imageLoader = context.imageLoader
    scope.launch(Dispatchers.IO) {
        try {
            val result = imageLoader.execute(
                ImageRequest.Builder(context)
                    .data(posterUrl)
                    .allowHardware(false)
                    .build()
            )
            val bitmap = (result as? SuccessResult)?.drawable?.toBitmap()
            if (bitmap != null) {
                saveBitmapToGallery(context, scope, bitmap, title)
                onSaved()
            }
        } catch (_: Exception) {
            withContext(Dispatchers.Main) {
                context.showToast(context.getString(R.string.gallery_save_failed))
            }
        }
    }
}

/** 保存Bitmap到相册 */
private fun saveBitmapToGallery(
    context: Context,
    scope: CoroutineScope,
    bitmap: Bitmap,
    title: String
) {
    val safeName = title.replace(Regex("[^a-zA-Z0-9\\u4e00-\\u9fa5]"), "_")
    val filename = "TrackToSearch_${safeName}.jpg"
    val relativePath = Environment.DIRECTORY_PICTURES + "/TrackToSearch"
    when (writeBitmapToAlbum(context, bitmap, filename, relativePath)) {
        SaveToAlbumResult.ALREADY_EXISTS -> scope.launch(Dispatchers.Main) {
            context.showToast(context.getString(R.string.gallery_already_exists, filename))
        }
        SaveToAlbumResult.SAVED -> scope.launch(Dispatchers.Main) {
            context.showToast(context.getString(R.string.gallery_saved, relativePath, filename))
        }
        SaveToAlbumResult.FAILED -> scope.launch(Dispatchers.Main) {
            context.showToast(context.getString(R.string.gallery_save_failed))
        }
    }
}

/** 写入相册的结果。同名文件已存在单独成一档：那不是失败，提示语也该不一样。 */
enum class SaveToAlbumResult { SAVED, ALREADY_EXISTS, FAILED }

/**
 * 把已有位图写进相册，只返回结果、不弹提示。
 *
 * 与 [savePosterToGallery] 的分工：那个负责「从 URL 取图再存」并顺手弹 toast；
 * 这个只做写入，让调用方自己决定用 toast 还是 snackbar、文案怎么写。
 */
suspend fun saveBitmapToAlbum(
    context: Context,
    bitmap: Bitmap,
    filename: String,
    subDirectory: String = "TrackToSearch",
): SaveToAlbumResult = withContext(Dispatchers.IO) {
    writeBitmapToAlbum(
        context = context,
        bitmap = bitmap,
        filename = filename,
        relativePath = Environment.DIRECTORY_PICTURES + "/" + subDirectory,
    )
}

private fun writeBitmapToAlbum(
    context: Context,
    bitmap: Bitmap,
    filename: String,
    relativePath: String,
): SaveToAlbumResult {
    // 检查是否已存在同名文件（防重复保存）
    if (queryExistingFile(context, filename, relativePath) != null) {
        return SaveToAlbumResult.ALREADY_EXISTS
    }

    val contentValues = android.content.ContentValues().apply {
        put(android.provider.MediaStore.Images.Media.DISPLAY_NAME, filename)
        put(
            android.provider.MediaStore.Images.Media.MIME_TYPE,
            if (filename.endsWith(".png", ignoreCase = true)) "image/png" else "image/jpeg"
        )
        put(android.provider.MediaStore.Images.Media.RELATIVE_PATH, relativePath)
    }
    return try {
        val uri = context.contentResolver.insert(
            android.provider.MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
            contentValues
        ) ?: return SaveToAlbumResult.FAILED
        context.contentResolver.openOutputStream(uri)?.use { stream ->
            if (filename.endsWith(".png", ignoreCase = true)) {
                bitmap.compress(Bitmap.CompressFormat.PNG, 100, stream)
            } else {
                bitmap.compress(Bitmap.CompressFormat.JPEG, 95, stream)
            }
        } ?: return SaveToAlbumResult.FAILED
        SaveToAlbumResult.SAVED
    } catch (e: Exception) {
        SaveToAlbumResult.FAILED
    }
}

/** 查询 MediaStore 中是否已存在同名文件 */
fun queryExistingFile(context: Context, filename: String, relativePath: String): Uri? {
    val selection = "${android.provider.MediaStore.Images.Media.DISPLAY_NAME} = ? AND ${android.provider.MediaStore.Images.Media.RELATIVE_PATH} = ?"
    val selectionArgs = arrayOf(filename, relativePath)
    val cursor = context.contentResolver.query(
        android.provider.MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
        arrayOf(android.provider.MediaStore.Images.Media._ID),
        selection,
        selectionArgs,
        null
    )
    cursor?.use {
        if (it.moveToFirst()) {
            val id = it.getLong(it.getColumnIndexOrThrow(android.provider.MediaStore.Images.Media._ID))
            return Uri.withAppendedPath(
                android.provider.MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                id.toString()
            )
        }
    }
    return null
}
