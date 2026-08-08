package com.tracktosearch.ui.component

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.chrisbanes.haze.HazeInputScale
import dev.chrisbanes.haze.HazeState
import dev.chrisbanes.haze.hazeEffect
import dev.chrisbanes.haze.blur.blurEffect
import dev.chrisbanes.haze.blur.materials.HazeMaterials

/**
 * 毛玻璃胶囊搜索栏
 *
 * @param placeholder 占位提示文字
 * @param typeLabel 搜索类型标签（如「电影」）
 * @param onClick 点击搜索栏回调（仅在非输入模式下生效）
 * @param onTypeClick 点击类型标签回调
 * @param hazeState Haze 模糊状态
 * @param value 当前输入值（传入后启用输入模式）
 * @param onValueChange 输入值变化回调（传入后启用输入模式）
 * @param keyboardOptions 键盘选项
 * @param keyboardActions 键盘动作
 * @param focusRequester 焦点请求器
 * @param trailingIcon 尾部图标
 */
@Composable
fun GlassSearchBar(
    placeholder: String,
    typeLabel: String? = null,
    onClick: () -> Unit = {},
    onTypeClick: () -> Unit = {},
    hazeState: HazeState? = null,
    modifier: Modifier = Modifier,
    value: String = "",
    onValueChange: ((String) -> Unit)? = null,
    keyboardOptions: KeyboardOptions = KeyboardOptions.Default,
    keyboardActions: KeyboardActions = KeyboardActions.Default,
    focusRequester: FocusRequester? = null,
    trailingIcon: @Composable (() -> Unit)? = null
) {
    val interactionSource = remember { MutableInteractionSource() }
    val isEditable = onValueChange != null

    val containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.30f)
    val contentColor = MaterialTheme.colorScheme.onSurface
    val hintColor = contentColor.copy(alpha = 0.55f)
    val hazeStyle = HazeMaterials.thin(MaterialTheme.colorScheme.background)

    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(28.dp))
            .border(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f), RoundedCornerShape(28.dp))
            .then(
                if (hazeState != null) {
                    Modifier.hazeEffect(state = hazeState) {
                        inputScale = HazeInputScale.Auto
                        blurEffect { style = hazeStyle }
                    }
                } else Modifier
            )
            .background(containerColor)
            .then(
                if (isEditable) {
                    Modifier
                } else {
                    Modifier.clickable(onClick = onClick)
                }
            )
            .padding(horizontal = 16.dp, vertical = 12.dp)
    ) {
        Icon(
            imageVector = Icons.Rounded.Search,
            contentDescription = null,
            tint = contentColor.copy(alpha = 0.7f)
        )
        if (isEditable) {
            val textFieldModifier = if (focusRequester != null) {
                Modifier
                    .weight(1f)
                    .padding(horizontal = 10.dp)
                    .focusRequester(focusRequester)
            } else {
                Modifier
                    .weight(1f)
                    .padding(horizontal = 10.dp)
            }
            BasicTextField(
                value = value,
                onValueChange = onValueChange,
                modifier = textFieldModifier,
                singleLine = true,
                textStyle = TextStyle(
                    color = contentColor,
                    fontSize = 14.sp
                ),
                keyboardOptions = keyboardOptions,
                keyboardActions = keyboardActions,
                interactionSource = interactionSource,
                cursorBrush = SolidColor(MaterialTheme.colorScheme.primary)
            ) { innerTextField ->
                Box(
                    modifier = Modifier.fillMaxWidth(),
                    contentAlignment = Alignment.CenterStart
                ) {
                    if (value.isEmpty()) {
                        Text(
                            text = placeholder,
                            color = hintColor,
                            fontSize = 14.sp
                        )
                    }
                    innerTextField()
                }
            }
        } else {
            Text(
                text = placeholder,
                color = hintColor,
                fontSize = 14.sp,
                modifier = Modifier
                    .weight(1f)
                    .padding(horizontal = 10.dp)
            )
        }
        if (trailingIcon != null) {
            trailingIcon()
        }
        if (!typeLabel.isNullOrBlank()) {
            Text(
                text = "$typeLabel ▾",
                color = contentColor.copy(alpha = 0.6f),
                fontSize = 12.sp,
                modifier = Modifier
                    .clip(RoundedCornerShape(12.dp))
                    .background(contentColor.copy(alpha = 0.08f))
                    .clickable(onClick = onTypeClick)
                    .padding(horizontal = 8.dp, vertical = 4.dp)
            )
        }
    }
}
