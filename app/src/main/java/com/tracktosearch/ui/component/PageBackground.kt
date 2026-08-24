package com.tracktosearch.ui.component

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import com.tracktosearch.ui.theme.AmbientMeshBackground
import com.tracktosearch.ui.theme.MeshPreset

/**
 * 主页面背景：彩色弥散光晕。作为独立于内容的背景层，置于 HorizontalPager 之下，
 * 让搜索/发现/我的/设置四页共享同一层连续光晕，实现跨页运动连贯。
 *
 * @param motionActive 为 false 时冻结动画（帧开销归零），见 [AmbientMotionState]。
 */
@Composable
fun PageBackground(
    modifier: Modifier = Modifier,
    preset: MeshPreset = MeshPreset.NEBULA,
    enabled: Boolean = true,
    motionActive: () -> Boolean = { true },
) {
    AmbientMeshBackground(
        modifier = modifier.fillMaxSize(),
        preset = preset,
        enabled = enabled,
        motionActive = motionActive,
    )
}