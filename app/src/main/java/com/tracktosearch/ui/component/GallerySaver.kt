package com.tracktosearch.ui.component

import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import android.os.Environment
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** 写入相册的结果。同名文件已存在单独成一档：那不是失败，提示语也该不一样。 */
enum class SaveToAlbumResult { SAVED, ALREADY_EXISTS, FAILED }

/** 把已有位图写进相册，只返回结果、不弹提示；提示文案由调用方决定。 */
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
