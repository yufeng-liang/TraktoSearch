package com.tracktosearch.ui.component

import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.MutableTransitionState
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.rememberTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupPositionProvider
import androidx.compose.ui.window.PopupProperties
import com.tracktosearch.ui.theme.floatingDialogColor

/**
 * 锚点 + 菜单一体的公共下拉菜单组件（窗口管理与动画复刻官方 Material3 DropdownMenu）。
 *
 * 位置策略（对齐选项所在行）：
 * - 锚点位于窗口右半 → 菜单右边缘对齐锚点右边缘（即选项行的下拉箭头处）；
 * - 锚点位于窗口左半 → 菜单左边缘对齐锚点左边缘；
 * - 锚点下方空间不足 → 自动向上展开（避免下方显示区域不够）。
 *
 * 窗口生命周期与动画同官方 DropdownMenu：
 * - MutableTransitionState 状态机——关闭动画播完前 Popup 保持组合，动画完成后自动移除；
 * - focusable 恒定 true，窗口类型从不切换（避免重建导致的残留/跑位/卡死）；
 * - 展开动画为 scale + alpha（graphicsLayer），缩放原点朝向锚点一侧。
 *
 * @param expanded 是否展开
 * @param onDismissRequest 点击外部或系统返回时回调
 * @param anchor 锚点内容（如当前值 + 下拉箭头），展开触发由调用方在 anchor 内部处理
 * @param menuWidth 固定菜单宽度；null 时按内容自适应
 * @param menu 菜单内容
 */
@Composable
fun DropdownAnchorMenu(
    expanded: Boolean,
    onDismissRequest: () -> Unit,
    anchor: @Composable () -> Unit,
    menuWidth: Dp? = null,
    menu: @Composable ColumnScope.() -> Unit
) {
    val density = LocalDensity.current
    val windowWidth = LocalWindowInfo.current.containerSize.width
    var anchorCenterX by remember { mutableIntStateOf(0) }

    // 官方状态机：currentState 在关闭动画播完前保持 true，
    // 保证 Popup 在动画期间不被移除，动画完成后自动移除（干净关闭）
    val expandedState = remember { MutableTransitionState(false) }
    expandedState.targetState = expanded

    Box(
        modifier = Modifier.onGloballyPositioned { coordinates ->
            val bounds = coordinates.boundsInWindow()
            anchorCenterX = (bounds.left + bounds.width / 2f).toInt()
        }
    ) {
        anchor()

        if (expandedState.currentState || expandedState.targetState) {
            val alignToEnd = anchorCenterX >= windowWidth / 2
            val positionProvider = remember(alignToEnd) {
                AnchorDropdownPositionProvider(
                    alignToEnd = alignToEnd,
                    verticalGapPx = with(density) { 8.dp.roundToPx() }
                )
            }
            Popup(
                popupPositionProvider = positionProvider,
                onDismissRequest = onDismissRequest,
                // 官方同款：focusable 恒定 true，窗口类型从不切换
                properties = PopupProperties(focusable = true, dismissOnBackPress = true)
            ) {
                // 官方同款动画：scale + alpha（graphicsLayer），非 AnimatedVisibility
                val transition = rememberTransition(expandedState, "AnchorDropdownMenu")
                val scale by transition.animateFloat(
                    transitionSpec = { tween(150, easing = FastOutSlowInEasing) }
                ) { if (it) 1f else 0.8f }
                val alpha by transition.animateFloat(
                    transitionSpec = { tween(150, easing = FastOutSlowInEasing) }
                ) { if (it) 1f else 0f }

                Column(
                    modifier = Modifier
                        .graphicsLayer {
                            scaleX = scale
                            scaleY = scale
                            this.alpha = alpha
                            // 缩放原点朝向锚点一侧：菜单像从锚点处展开
                            transformOrigin = if (alignToEnd) {
                                TransformOrigin(1f, 0f)
                            } else {
                                TransformOrigin(0f, 0f)
                            }
                        }
                        .then(
                            if (menuWidth != null) Modifier.width(menuWidth)
                            else Modifier.wrapContentWidth()
                        )
                        // 官方 MenuDefaults：shadowElevation = Level2(3.dp)、tonalElevation = Level0、
                        // shape = CornerExtraSmall(4dp)、默认无边框（border = null）
                        .shadow(
                            elevation = 3.dp,
                            shape = MaterialTheme.shapes.extraSmall,
                            clip = false
                        )
                        .background(floatingDialogColor(), MaterialTheme.shapes.extraSmall)
                        .clip(MaterialTheme.shapes.extraSmall)
                        .padding(vertical = 8.dp)
                ) {
                    menu()
                }
            }
        }
    }
}

/**
 * 下拉菜单位置计算：右缘/左缘对齐锚点，垂直优先下方、空间不足向上翻转。
 */
private class AnchorDropdownPositionProvider(
    private val alignToEnd: Boolean,
    private val verticalGapPx: Int
) : PopupPositionProvider {
    override fun calculatePosition(
        anchorBounds: IntRect,
        windowSize: IntSize,
        layoutDirection: LayoutDirection,
        popupContentSize: IntSize
    ): IntOffset {
        val menuWidth = popupContentSize.width
        val menuHeight = popupContentSize.height

        // 水平：锚点靠右 → 右缘对齐锚点右缘；靠左 → 左缘对齐锚点左缘
        // 使用框架传入的 anchorBounds（相对窗口的真实锚点位置）
        val anchorLeft = anchorBounds.left
        val anchorRight = anchorBounds.right
        var x = if (alignToEnd) anchorRight - menuWidth else anchorLeft
        // 水平溢出保护
        if (x < 0) x = anchorLeft
        if (x + menuWidth > windowSize.width) x = anchorRight - menuWidth

        // 垂直：优先锚点下方，下方空间不足则向上展开
        var y = anchorBounds.bottom + verticalGapPx
        if (y + menuHeight > windowSize.height) {
            y = anchorBounds.top - verticalGapPx - menuHeight
        }
        if (y < 0) y = 0

        return IntOffset(x, y)
    }
}
