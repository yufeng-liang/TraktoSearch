package com.tracktosearch.ui.component

import android.app.Activity
import android.view.Gravity
import android.widget.FrameLayout
import android.widget.ImageView
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect as ComposeRect
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.ViewCompat
import com.flyjingfish.openimagelib.OpenImage
import com.flyjingfish.openimagelib.beans.ClickViewParam
import com.flyjingfish.openimagelib.beans.CloseParams
import com.flyjingfish.openimagelib.beans.DownloadParams
import com.flyjingfish.openimagelib.beans.OpenImageUrl
import com.flyjingfish.openimagelib.enums.MediaType
import com.tracktosearch.R

/**
 * OpenImage 成熟查看器的 Compose 桥接层。
 *
 * 设计约束（来自 OpenImage 官方 Compose 示例与源码契约）：
 * - 缩略图留在 Compose：每张可点缩略图用 [recordOpenImageBounds] 把 `boundsInWindow()`
 *   记进一张按数据下标索引的 map；点击时把整份列表转成 [ClickViewParam] 交给查看器，
 *   打开/返回动画的落点由这些矩形决定，不需要 AndroidView。
 * - 数据：列表每一项包装成 [OpenImageViewerItem]（实现库的 [OpenImageUrl]）：
 *   `largeUrl` 给 original 大图（展示 + 内置下载保存都取它），`coverUrl` 给列表真正显示的
 *   小图（打开动画占位并命中 App Coil 磁盘缓存）。
 * - 保存：直接启用库内置下载按钮（MediaStore 写入 + 权限弹窗由库处理），不再手写相册逻辑。
 * - 视频：本项目预告片走 YouTube 现状，不进入本查看器（openimage-coil 无视频引擎）。
 *
 * 已知取舍：懒加载列表滚出屏幕的项拿不到 bounds，启动时给 1px 占位矩形；
 * 用户从大图页滑到那张再关闭时落点会退化（微信同场景也这样）。点击项必在屏内，动画不受影响。
 */
internal data class OpenImageViewerItem(
    val largeUrl: String,
    val coverUrl: String = largeUrl,
) : OpenImageUrl, java.io.Serializable {
    override fun getImageUrl(): String = largeUrl
    override fun getVideoUrl(): String = ""
    override fun getCoverImageUrl(): String = coverUrl
    override fun getType(): MediaType = MediaType.IMAGE
}

/** 记录全屏图数据下标对应的缩略图矩形（window 坐标，px）。每屏/每组图各持一份。 */
@Composable
internal fun rememberOpenImageBounds(): MutableMap<Int, ComposeRect> = remember { mutableMapOf() }

/**
 * 挂在可点缩略图上的记录器：布局完成后把该图在 window 中的矩形写进 [bounds]。
 * 与点击回调同 key 的 item 一定已组合，点击时能取到最新值。
 */
internal fun Modifier.recordOpenImageBounds(
    index: Int,
    bounds: MutableMap<Int, ComposeRect>,
): Modifier = onGloballyPositioned { bounds[index] = it.boundsInWindow() }

/**
 * 从 Compose 启动 OpenImage 查看器。
 *
 * @param items 全屏查看的数据（按下标与缩略图一一对应）
 * @param bounds 各缩略图矩形（recordOpenImageBounds 收集）
 * @param clickedIndex 点开的那一张（决定转场起点）
 * @param thumbnailScaleType 缩略图实际的 ContentScale 对应的 ImageView.ScaleType。
 *   默认 CENTER_CROP（ContentScale.Crop）；反馈截图等 Fit 场景必须传 FIT_CENTER，
 *   否则打开动画的形状与缩略图不一致。
 * @param onExit 查看器返回动画完全结束后回调（含点图/返回键/拖拽关闭）
 */
internal fun openImageViewer(
    activity: Activity,
    items: List<OpenImageViewerItem>,
    bounds: Map<Int, ComposeRect>,
    clickedIndex: Int,
    thumbnailScaleType: ImageView.ScaleType = ImageView.ScaleType.CENTER_CROP,
    onExit: () -> Unit = {},
) {
    val decorView = activity.window.decorView
    val browserWidth = decorView.width
    val safeIndex = clickedIndex.coerceIn(0, items.lastIndex)
    val builder = OpenImage.with(activity)
        .setClickPosition(safeIndex)
        // 缩略图侧统一是 ContentScale.Crop（含反馈截图等 fit 场景由调用方决定是否覆盖）
        .setSrcImageViewScaleType(thumbnailScaleType, true)
        .setImageUrlList(items)
        // 内置保存按钮（底部右侧小圆钮 + 进度环），不再手写相册逻辑
        .setShowDownload(bottomEndDownloadParams(activity))
        // 关闭按钮放左上角，拖动图片时隐藏
        .setShowClose(topStartCloseParams(activity))
        .setOnExitListener { onExit() }

    val clickedBounds = bounds[safeIndex]
    if (clickedBounds != null) {
        // WEB_VIEW 模式：把每一张缩略图矩形按数据下标交给库，转场起点严格等于点击矩形。
        // 懒加载未组合/滚出屏幕的项给 1px 占位，保证列表下标与数据下标对齐。
        val params = items.indices.map { i ->
            val b = bounds[i]
            if (b != null) {
                ClickViewParam(
                    b.width.toInt(),
                    b.height.toInt(),
                    b.top.toInt(),
                    b.left.toInt(),
                    browserWidth,
                )
            } else {
                ClickViewParam(1, 1, 0, 0, browserWidth)
            }
        }
        builder.setClickWebView(decorView, params).show()
    } else {
        // 理论上不会发生（点击项必在屏内）；兜底退化为无转场打开，保住可用性
        builder.setNoneClickView().show()
    }
}

/**
 * 关闭按钮：36dp 白图标放左上（Telegram 风格），触摸图片时隐藏。
 *
 * OpenImage 查看器是沉浸式（内容延伸到状态栏底下），按钮坐标相对整窗计算；
 * 只留 margin 会把按钮顶进状态栏，视觉压住系统图标且点击被状态栏消费。
 * 因此 topMargin 额外加状态栏高度，让按钮落在状态栏下方。
 */
private fun topStartCloseParams(activity: Activity): CloseParams {
    val density = activity.resources.displayMetrics.density
    val size = (44 * density).toInt()
    val margin = (16 * density).toInt()
    val lp = FrameLayout.LayoutParams(size, size, Gravity.TOP or Gravity.START).apply {
        topMargin = systemBarInsetTop(activity) + margin
        leftMargin = margin
    }
    return CloseParams()
        .setCloseSrc(R.drawable.open_image_btn_close)
        .setTouchingHide(true)
        .setCloseLayoutParams(lp)
}

/**
 * 下载/保存按钮：36dp 白图标放右下（Telegram 风格），触摸图片时隐藏。
 *
 * 与关闭按钮同理，查看器整窗布局会贴到屏幕底；默认下载按钮不带导航条 inset，
 * 会压住手势条/三键导航。bottomMargin 额外加导航条高度，让按钮浮在安全区之上。
 */
private fun bottomEndDownloadParams(activity: Activity): DownloadParams {
    val density = activity.resources.displayMetrics.density
    val size = (44 * density).toInt()
    val margin = (16 * density).toInt()
    val lp = FrameLayout.LayoutParams(size, size, Gravity.BOTTOM or Gravity.END).apply {
        bottomMargin = systemBarInsetBottom(activity) + margin
        rightMargin = margin
    }
    return DownloadParams()
        .setDownloadSrc(R.drawable.open_image_btn_download)
        .setTouchingHide(true)
        .setDownloadLayoutParams(lp)
}

/**
 * 状态栏高度（px）。优先取主窗口 insets（edge-to-edge 下更准），
 * 取不到再退回系统资源里的 status_bar_height。
 */
private fun systemBarInsetTop(activity: Activity): Int {
    val inset = ViewCompat.getRootWindowInsets(activity.window.decorView)
        ?.getInsets(WindowInsetsCompat.Type.statusBars())?.top
    if (inset != null && inset > 0) return inset
    return systemDimen(activity, "status_bar_height")
}

/** 导航条高度（px），逻辑同 [systemBarInsetTop]。 */
private fun systemBarInsetBottom(activity: Activity): Int {
    val inset = ViewCompat.getRootWindowInsets(activity.window.decorView)
        ?.getInsets(WindowInsetsCompat.Type.navigationBars())?.bottom
    if (inset != null && inset > 0) return inset
    return systemDimen(activity, "navigation_bar_height")
}

private fun systemDimen(activity: Activity, name: String): Int = runCatching {
    val id = activity.resources.getIdentifier(name, "dimen", "android")
    if (id > 0) activity.resources.getDimensionPixelSize(id) else 0
}.getOrDefault(0)
