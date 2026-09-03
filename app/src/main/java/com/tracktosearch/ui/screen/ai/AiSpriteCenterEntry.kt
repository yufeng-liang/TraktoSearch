package com.tracktosearch.ui.screen.ai

import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.tracktosearch.R
import com.tracktosearch.data.ai.AiCharacter
import com.tracktosearch.ui.haptic.rememberAppHaptics

/**
 * 标题栏上的精灵中心入口。
 *
 * 之前进精灵中心的唯一方式是长按主搜索页的云朵——界面上没有任何提示，只写在
 * 无障碍描述和帮助页里，新用户不可能发现；Trakt 搜索页更是完全没有入口，
 * 未激活的用户在那一页永远打不开精灵中心（那页只能点自动探头，而探头要求已激活）。
 *
 * 已激活时直接显示当前角色头像，顺带把「谁在陪你」这个状态也表达出来。
 * 头像走 [AiCharacterGlyph]，和精灵中心里的角色条用同一份立绘，不再是另画一版简笔画。
 */
@Composable
fun AiSpriteCenterEntryButton(
    activatedCharacter: AiCharacter?,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val description = stringResource(R.string.ai_sprite_open_center)
    // 标题栏图标按钮，按「次级入口」给轻一档；上游 SearchScreen / TraktSearchScreen 的
    // onClick 里没有触感，这一记必须由本组件自己发，否则这两个入口整条路都是哑的
    val haptics = rememberAppHaptics()
    IconButton(
        onClick = {
            haptics.lightTap()
            onClick()
        },
        modifier = modifier.semantics { contentDescription = description }
    ) {
        if (activatedCharacter != null) {
            // 比标准 24dp 图标略大：立绘是全身像，脸只占一半高度，太小认不出是谁
            AiCharacterGlyph(activatedCharacter, Modifier.size(28.dp))
        } else {
            Icon(
                imageVector = Icons.Rounded.AutoAwesome,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary
            )
        }
    }
}
