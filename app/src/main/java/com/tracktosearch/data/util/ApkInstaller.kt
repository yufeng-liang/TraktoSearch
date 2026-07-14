package com.tracktosearch.data.util

import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import androidx.core.content.FileProvider
import java.io.File
import java.io.FileInputStream

object ApkInstaller {
    fun installApk(context: Context, apkFile: File) {
        // 优先使用 PackageInstaller API（无需系统安装器 UI 解析 FileProvider URI）
        try {
            installViaPackageInstaller(context, apkFile)
            return
        } catch (_: Exception) {
            // 失败时 fallback 到 Intent
        }
        installViaIntent(context, apkFile)
    }

    private fun installViaPackageInstaller(context: Context, apkFile: File) {
        val packageInstaller = context.packageManager.packageInstaller
        val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL)
        val sessionId = packageInstaller.createSession(params)
        val session = packageInstaller.openSession(sessionId)

        // 把 APK 写入 session
        FileInputStream(apkFile).use { input ->
            session.openWrite("package", 0, apkFile.length()).use { output ->
                input.copyTo(output)
                session.fsync(output)
            }
        }

        // 创建接收安装结果的 Intent
        val intent = Intent(context, InstallResultReceiver::class.java).apply {
            action = "com.tracktosearch.INSTALL_RESULT"
        }
        val pendingIntent = android.app.PendingIntent.getBroadcast(
            context,
            sessionId,
            intent,
            android.app.PendingIntent.FLAG_UPDATE_CURRENT or android.app.PendingIntent.FLAG_MUTABLE
        )

        session.commit(pendingIntent.intentSender)
        session.close()
    }

    private fun installViaIntent(context: Context, apkFile: File) {
        val uri = FileProvider.getUriForFile(
            context,
            "${context.packageName}.fileprovider",
            apkFile
        )
        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, "application/vnd.android.package-archive")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            addFlags(Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            putExtra(Intent.EXTRA_NOT_UNKNOWN_SOURCE, true)
            putExtra(Intent.EXTRA_RETURN_RESULT, true)
        }
        context.startActivity(intent)
    }
}
