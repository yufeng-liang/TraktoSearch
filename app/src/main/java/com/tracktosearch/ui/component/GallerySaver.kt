package com.tracktosearch.ui.component

import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import android.os.Environment
import coil.ImageLoader
import coil.request.ImageRequest
import coil.request.SuccessResult
import coil.imageLoader
import androidx.core.graphics.drawable.toBitmap
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

    // 检查是否已存在同名文件（防重复保存）
    val existingUri = queryExistingFile(context, filename, relativePath)
    if (existingUri != null) {
        scope.launch(Dispatchers.Main) {
            context.showToast(context.getString(R.string.gallery_already_exists, filename))
        }
        return
    }

    val contentValues = android.content.ContentValues().apply {
        put(android.provider.MediaStore.Images.Media.DISPLAY_NAME, filename)
        put(android.provider.MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
        put(android.provider.MediaStore.Images.Media.RELATIVE_PATH, relativePath)
    }
    val uri = context.contentResolver.insert(
        android.provider.MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
        contentValues
    )
    if (uri != null) {
        context.contentResolver.openOutputStream(uri)?.use { stream ->
            bitmap.compress(Bitmap.CompressFormat.JPEG, 95, stream)
        }
        scope.launch(Dispatchers.Main) {
            context.showToast(context.getString(R.string.gallery_saved, relativePath, filename))
        }
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
