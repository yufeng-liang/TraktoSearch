package com.tracktosearch.ui.util

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import com.tracktosearch.R
import com.tracktosearch.data.remote.dto.DiskType
import com.tracktosearch.data.remote.dto.ResourceItem

/**
 * 根据资源来源和类型，使用合适的方式打开资源链接。
 * - Zreso 来源：直接用浏览器打开
 * - 其他来源：优先尝试打开对应网盘 App，失败则用浏览器打开
 */
fun openResourceLink(context: Context, item: ResourceItem) {
    // Zreso 来源一律用浏览器打开
    if (item.source == "zreso") {
        openInBrowser(context, item.url)
        return
    }

    // 其他来源：优先尝试打开对应网盘 App
    val appPackages = getDiskAppPackages(item.diskType)
    for (pkg in appPackages) {
        try {
            val intent = Intent(Intent.ACTION_VIEW, Uri.parse(item.url)).apply {
                setPackage(pkg)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            if (intent.resolveActivity(context.packageManager) != null) {
                context.startActivity(intent)
                return
            }
        } catch (_: Exception) {}
    }

    // 磁力链接：尝试用系统默认 App 打开
    if (item.diskType == DiskType.MAGNET || item.url.startsWith("magnet:", ignoreCase = true)) {
        try {
            val magnetIntent = Intent(Intent.ACTION_VIEW, Uri.parse(item.url)).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            if (magnetIntent.resolveActivity(context.packageManager) != null) {
                context.startActivity(magnetIntent)
                return
            }
        } catch (_: Exception) {}
        // 无 App 处理磁力链接：复制到剪贴板
        copyToClipboard(context, item.url, "magnet")
        context.showToast(context.getString(R.string.magnet_copied))
        return
    }

    // Fallback: 浏览器打开
    openInBrowser(context, item.url)
}

/**
 * 复制资源链接到剪贴板
 */
fun copyResourceLink(context: Context, item: ResourceItem) {
    copyToClipboard(context, item.url, "resource_link")
    context.showToast(context.getString(R.string.link_copied))
}

private fun openInBrowser(context: Context, url: String) {
    try {
        val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url)).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        context.startActivity(intent)
    } catch (_: Exception) {
        context.showToast(context.getString(R.string.cannot_open_link))
    }
}

private fun copyToClipboard(context: Context, text: String, label: String) {
    val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
    val clip = ClipData.newPlainText(label, text)
    clipboard.setPrimaryClip(clip)
}

private fun getDiskAppPackages(diskType: DiskType): List<String> = when (diskType) {
    DiskType.QUARK -> listOf("com.quark.clouddrive", "com.quark.browser")
    DiskType.BAIDU -> listOf("com.baidu.netdisk")
    DiskType.ALI -> listOf("com.alicloud.databox")
    DiskType.XUNLEI -> listOf("com.xunlei.downloadprovider", "com.xunlei.browser")
    DiskType.UC -> listOf("com.UCMobile")
    DiskType.ONEONEFIVE -> listOf("com.crland.app")
    DiskType.MAGNET, DiskType.OTHER -> emptyList()
}
