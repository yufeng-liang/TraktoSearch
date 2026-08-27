package com.tracktosearch.ui.component

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.ProvidableCompositionLocal
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.material3.MaterialTheme
import com.kyant.backdrop.Backdrop
import com.kyant.backdrop.backdrops.LayerBackdrop
import com.kyant.backdrop.backdrops.layerBackdrop
import com.kyant.backdrop.backdrops.rememberLayerBackdrop

/** 当前屏幕的 Backdrop 采样源；缺少 host 时保持显式的 null fallback。 */
val LocalBackdrop: ProvidableCompositionLocal<Backdrop?> = staticCompositionLocalOf { null }

/** 控制页面内部是否允许注册 source，避免把包含 Glass overlay 的子树再次录制。 */
val LocalBackdropSourceEnabled: ProvidableCompositionLocal<Boolean> = staticCompositionLocalOf { true }

/**
 * 提供屏幕级 Backdrop 采样源。
 *
 * 每个屏幕应只在滚动内容/背景 source layer 上使用
 * `.layerBackdrop(LocalBackdrop.current ?: error("Backdrop host is missing"))`。
 * Glass overlay 必须与该 source layer 保持兄弟关系，不能把 `layerBackdrop` 加到包含
 * Glass overlay 的整个根布局上，否则会把 overlay 自身再次纳入采样。
 */
@Composable
fun BackdropProvider(
    modifier: Modifier = Modifier,
    backgroundColor: Color = MaterialTheme.colorScheme.background,
    content: @Composable BoxScope.() -> Unit
) {
    val backdrop = rememberLayerBackdrop(
        onDraw = remember(backgroundColor) {
            {
                drawRect(backgroundColor)
                drawContent()
            }
        }
    )
    CompositionLocalProvider(LocalBackdrop provides backdrop) {
        Box(modifier) {
            // 只把不含 drawBackdrop 的背景层注册为默认 source。页面内容中的 Glass
            // 可能再次采样同一 backdrop，整棵页面作为 source 会造成 RenderThread 递归。
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .layerBackdrop(backdrop)
            )
            content()
        }
    }
}

/**
 * 将当前页面的真实内容注册为 Backdrop source。调用点应只放在滚动内容/背景层，
 * 不要放在包含 Glass overlay 的外层根布局上。
 */
@Composable
fun Modifier.backdropSource(): Modifier {
    // 调用方多为完整滚动页面，无法在此保证子树没有 drawBackdrop。
    // 真实内容采样只能使用下方显式的 backdropContentSource()。
    return this
}

/**
 * 强制把纯影视内容注册为 source。
 *
 * 主 Tab 会默认关闭页面内部 source，避免设置页等包含 Glass 子树的滚动容器递归录制；
 * 只有确认不含 Glass overlay 的影视列表才使用这个入口。
 */
@Composable
fun Modifier.backdropContentSource(backdrop: LayerBackdrop? = null): Modifier {
    // MainScreen 会让四个 Pager 页面常驻组合。只有当前可见页面允许注册 source，
    // 否则隐藏页也会持续录制 GraphicsLayer，Glass 模式下一次切换会同时重录多页。
    if (!LocalBackdropSourceEnabled.current) return this
    // LocalBackdrop 可能是合并后的 Backdrop（Combined），只有 LayerBackdrop 可注册为 source
    val resolved = backdrop ?: LocalBackdrop.current as? LayerBackdrop ?: return this
    return then(Modifier.layerBackdrop(resolved))
}
