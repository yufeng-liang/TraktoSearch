package com.tracktosearch.ui.haptic

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue

/**
 * 弹窗「自己冒出来」时发一记 [HapticSemantic.POPUP_SHOW]。放在 `if (visible)` 的**外面**：
 *
 * ```kotlin
 * PopupShowEffect(uiState.showRatingDialog)
 * if (showRatingDialog || uiState.showRatingDialog) { RatingDialog(...) }
 * ```
 *
 * 放在里面就收不到 false → true 那一次 —— 那一帧组合才刚开始，effect 的第一次运行看到的
 * 已经是 true，边沿检测无从下手。
 *
 * ### 不是每个弹窗都该用
 *
 * 全仓 71 个 `AlertDialog` / `ModalBottomSheet`，绝大多数是 `onClick = { showX = true }` ——
 * 用户按了按钮，按钮已经按 T8 的规则发过 `tap()` / `lightTap()`，弹窗跟着出现是**看得见的**
 * 确认。那种地方再补一记 `popupShow()`，两记落在同一帧里凑成一团糊响，反而把「按下」与
 * 「出现了」这两件事的区分抹掉。
 *
 * 该用的是另一类：**这一帧之前没有任何按压**。启动时读到待处理数据弹出来的、剪贴板里认出
 * 配置弹出来的、网络回来才弹出来的、长按触感刻意静默之后弹出来的面板。用户没做动作而屏幕
 * 上多了一层东西，这一记是在说「注意，这儿冒出来一个东西」。
 *
 * 判据落到一句话：**开这个弹窗的手势，是不是同一帧里已经震过了。** 震过就别再震。
 *
 * ### 为什么用 remember 而不是 rememberSaveable
 *
 * 基线要在每次重建组合时重新取当前值。弹窗开着旋屏，`visible` 一直是 true，
 * 重建后基线也是 true，于是不会补震一记。用 `rememberSaveable` 存基线反而会把
 * 「旋屏前是 false」这个陈旧事实带过来，正好震错。
 *
 * ### 为什么不挂生命周期闸门
 *
 * 与 [HapticOutcomeEffect] 不同，这里不需要。`visible` 是组合树自己的状态，屏幕不在前台
 * 时它不会翻 —— 翻了也说明界面真的变了。而结果流是从 ViewModel 来的，那才需要闸门拦住
 * 「用户已经离开这一屏」的情况。
 *
 * @param visible 弹窗当前是否显示。只在 false → true 的**这一次**发，之后要等它回到
 *   false 才会再武装。
 */
@Composable
fun PopupShowEffect(visible: Boolean) {
    val haptics = rememberAppHaptics()
    PopupShowEffect(visible = visible, haptics = haptics)
}

/** 可测的那一层：把 `haptics` 从 composition local 挪到参数上。 */
@Composable
internal fun PopupShowEffect(visible: Boolean, haptics: ComposeHaptics) {
    var observed by remember { mutableStateOf(visible) }
    LaunchedEffect(visible) {
        val wasVisible = observed
        observed = visible
        if (visible && !wasVisible) haptics.popupShow()
    }
}
