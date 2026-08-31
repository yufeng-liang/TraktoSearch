@file:OptIn(dev.chrisbanes.haze.ExperimentalHazeApi::class)

package com.tracktosearch.ui.screen.login

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.Backspace
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.ripple
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.scale
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.tracktosearch.R
import com.tracktosearch.ui.component.GlassScene
import com.tracktosearch.ui.component.GlassSurfaceRole
import com.tracktosearch.ui.component.appVisualEffect
import com.tracktosearch.ui.theme.PixelFontFamily
import com.tracktosearch.ui.theme.pixelFontSize
import dev.chrisbanes.haze.HazeInput
import dev.chrisbanes.haze.HazeState
import dev.chrisbanes.haze.blur.materials.HazeMaterials
import kotlin.math.roundToInt

/** 取票码位数。六格与读屏播报都按它算，改长度只改这里。 */
private const val CODE_LENGTH = 6

// 机壳不用 surface 而用深金属色：取票机是台设备，玻璃层该压出金属而不是纸面的观感。
// 浅色主题一档暖灰金属，深色主题压得更深，都带 alpha 让背景还能透上来。
private val MachineMetalLight = Color(0xFF6E6259)
private val MachineMetalDark = Color(0xFF3A322C)
private const val MACHINE_METAL_ALPHA = 0.45f

// 像素屏凹槽：屏是熄灭的黑玻璃，比机壳更深一档才像嵌进去的
private val DisplayWellLight = Color(0xFF241C16)
private val DisplayWellDark = Color(0xFF15100C)

// 老式点阵屏的荧光绿，报错转红
private val DisplayInkNormal = Color(0xFF9FE870)
private val DisplayInkError = Color(0xFFFF6B5A)

/** 出票口内壁。比屏幕还深，看上去是机器里面的暗处。 */
private val SlotWallColor = Color(0xFF1A1310)

/**
 * 电影院取票机。面板上按自绘数字键盘输入 6 位取票码，按通栏「取票」键，票从底部出票口打印出来。
 *
 * 自上而下七层：机壳、铭牌、像素屏、六格取票码、键盘、取票键、出票口。
 * 状态文案和错误判定全在调用方算好，这里只负责画机器、把按键抛回去。
 *
 * @param code 已输入的数字串，长度 0..6，调用方保证只含数字
 * @param codeDescription 六格整体的读屏文案，为 null 时按已输入位数自动生成。
 *   已取票态屏上那串是占位符不是真码，念它没有意义，由调用方给一句实话
 * @param statusText 像素屏上那行短状态，已是最终文案
 * @param hintText 机器下方的完整引导句（像素屏装不下的长句子），为 null 时不占位
 * @param keypadEnabled 已取票态整块键盘置灰但保留，机器不该只剩半截
 * @param ticketSlot 出票口里的内容，票从这里长出来
 */
@Composable
internal fun TicketMachine(
    code: String,
    statusText: String,
    statusIsError: Boolean,
    hintText: String?,
    hintIsError: Boolean,
    isLoading: Boolean,
    keypadEnabled: Boolean,
    submitEnabled: Boolean,
    hazeState: HazeState,
    scene: GlassScene,
    onDigit: (Char) -> Unit,
    onBackspace: () -> Unit,
    onPaste: () -> Unit,
    onSubmit: () -> Unit,
    modifier: Modifier = Modifier,
    codeDescription: String? = null,
    ticketSlot: @Composable () -> Unit = {},
) {
    val accent = MaterialTheme.colorScheme.primary
    val isDarkTheme = MaterialTheme.colorScheme.background.luminance() < 0.5f
    val shellShape = RoundedCornerShape(14.dp)
    val shellMetal = (if (isDarkTheme) MachineMetalDark else MachineMetalLight)
        .copy(alpha = MACHINE_METAL_ALPHA)
    val hazeStyle = HazeMaterials.thin(shellMetal).then {
        blurRadius(36.dp)
        noiseFactor(0f)
        blurEnabled(true)
    }
    val focusRequester = remember { FocusRequester() }
    // 节点还没挂上时 requestFocus 会抛；争取不到焦点只是少了外接键盘，触屏输入照旧
    LaunchedEffect(Unit) { runCatching { focusRequester.requestFocus() } }

    Column(
        modifier = modifier
            .fillMaxWidth()
            // 自绘键盘拿不到系统输入法白送的外接键盘支持，得自己接一遍。
            // onKeyEvent 放在 focusable 之前，焦点无论落在机器本身还是里面的按钮都能冒泡上来
            .focusRequester(focusRequester)
            .onKeyEvent { event ->
                if (event.type != KeyEventType.KeyDown) return@onKeyEvent false
                when {
                    !keypadEnabled -> false
                    event.key == Key.Backspace -> {
                        onBackspace()
                        true
                    }
                    event.key == Key.Enter || event.key == Key.NumPadEnter -> {
                        if (submitEnabled && !isLoading) {
                            onSubmit()
                            true
                        } else {
                            false
                        }
                    }
                    else -> {
                        val digit = digitFromKey(event.key)
                        if (digit != null) {
                            onDigit(digit)
                            true
                        } else {
                            false
                        }
                    }
                }
            }
            .focusable()
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .clip(shellShape)
                .appVisualEffect(
                    input = HazeInput.Sources(hazeState),
                    hazeStyle = hazeStyle,
                    glassRole = GlassSurfaceRole.LoginSurface,
                    glassShape = shellShape,
                    glassTint = shellMetal,
                    scene = scene
                )
                .background(Color.Transparent, shellShape)
                .border(BorderStroke(1.dp, accent.copy(alpha = 0.30f)), shellShape)
                // 金属折边高光：左右内缩，画满整宽会变成一条生硬的白边。
                // 线宽居中在 y = 0 会被 clip 掉一半，下移半个线宽让 1.dp 完整可见
                .drawBehind {
                    val stroke = 1.dp.toPx()
                    val inset = 14.dp.toPx()
                    drawLine(
                        color = Color.White.copy(alpha = 0.28f),
                        start = Offset(inset, stroke / 2f),
                        end = Offset(size.width - inset, stroke / 2f),
                        strokeWidth = stroke
                    )
                }
                .padding(horizontal = 16.dp, vertical = 14.dp)
        ) {
            // 机器上的金属铭牌小字，保持等宽字体，不换像素字体
            Text(
                text = stringResource(R.string.login_personal_cinema_access),
                style = MaterialTheme.typography.labelSmall.copy(
                    fontFamily = FontFamily.Monospace,
                    fontSize = 11.sp,
                    letterSpacing = 1.43.sp
                ),
                color = accent,
                fontWeight = FontWeight.Bold
            )

            val displayShape = RoundedCornerShape(4.dp)
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 10.dp)
                    // 屏内文案长短不一，不锁死高度整台机器会随状态跳动
                    .height(34.dp)
                    .clip(displayShape)
                    .background(if (isDarkTheme) DisplayWellDark else DisplayWellLight, displayShape)
                    .border(1.dp, Color.Black.copy(alpha = 0.55f), displayShape)
                    .padding(start = 10.dp),
                contentAlignment = Alignment.CenterStart
            ) {
                // 字号不能再往上加档：像素字体走 12px 网格，pixelFontSize 向下取整到网格倍数，
                // 中文 5-6 个字在这个宽度下 4 倍才不顶右边缘
                Text(
                    text = statusText,
                    fontFamily = PixelFontFamily,
                    fontSize = pixelFontSize(16.dp),
                    color = if (statusIsError) DisplayInkError else DisplayInkNormal,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }

            // 错误时六格整体左右抖两下。key 里带 statusText 是为了同一类错误连续发生两次也能重抖
            val shake = remember { Animatable(0f) }
            LaunchedEffect(statusIsError, statusText) {
                if (statusIsError) {
                    listOf(-6f, 6f, -4f, 4f, 0f).forEach { target ->
                        shake.animateTo(target, tween(durationMillis = 45))
                    }
                } else {
                    // 抖动途中错误被清掉会把偏移留在半路上，复位一次
                    shake.snapTo(0f)
                }
            }
            // 自绘键盘拿不到系统输入法白送的读屏播报，六格合成一个语义节点整体报。
            // 数字之间加空格是故意的：连读「492013」会被念成一个大数字
            val progressDescription = codeDescription ?: stringResource(
                R.string.machine_code_progress,
                code.map { it }.joinToString(" "),
                CODE_LENGTH - code.length,
            )
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 12.dp)
                    .offset { IntOffset(shake.value.roundToInt(), 0) }
                    .semantics(mergeDescendants = true) {
                        contentDescription = progressDescription
                    },
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                repeat(CODE_LENGTH) { index ->
                    CodeCell(
                        digit = code.getOrNull(index),
                        // 已取票态没有当前输入位，光标自然不出现，也就不闪
                        showCaret = index == code.length &&
                            code.length < CODE_LENGTH &&
                            keypadEnabled
                    )
                }
            }

            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 14.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                listOf("123", "456", "789").forEach { rowDigits ->
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        rowDigits.forEach { digit ->
                            DigitKey(digit = digit, enabled = keypadEnabled, onClick = { onDigit(digit) })
                        }
                    }
                }
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    PasteKey(enabled = keypadEnabled, onClick = onPaste)
                    DigitKey(digit = '0', enabled = keypadEnabled, onClick = { onDigit('0') })
                    BackspaceKey(enabled = keypadEnabled, onClick = onBackspace)
                }
            }
            Button(
                onClick = onSubmit,
                enabled = submitEnabled && !isLoading,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 12.dp)
                    .height(48.dp),
                shape = RoundedCornerShape(10.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = accent,
                    disabledContainerColor = Color(0xFFDED6CE),
                    disabledContentColor = Color(0xFF9A9189)
                )
            ) {
                if (isLoading) {
                    CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
                } else {
                    Text(
                        text = stringResource(R.string.machine_submit),
                        fontFamily = PixelFontFamily,
                        fontSize = pixelFontSize(18.dp)
                    )
                }
            }

            val slotShape = RoundedCornerShape(3.dp)
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 14.dp)
                    .height(10.dp)
                    .clip(slotShape)
                    .background(SlotWallColor, slotShape)
                    // 上齿边压暗、下齿边提亮，凹槽才有厚度；两条线各内移半个线宽避免被裁掉
                    .drawBehind {
                        val stroke = 1.dp.toPx()
                        drawLine(
                            color = Color.Black.copy(alpha = 0.35f),
                            start = Offset(0f, stroke / 2f),
                            end = Offset(size.width, stroke / 2f),
                            strokeWidth = stroke
                        )
                        drawLine(
                            color = Color.White.copy(alpha = 0.18f),
                            start = Offset(0f, size.height - stroke / 2f),
                            end = Offset(size.width, size.height - stroke / 2f),
                            strokeWidth = stroke
                        )
                    }
            )
            // 票紧贴凹槽下沿，中间不留 padding，看上去是从槽里长出来的
            ticketSlot()
        }

        // 完整引导句放机器外面用系统字体：像素屏只装短状态，长句子用点阵字体读不清
        if (hintText != null) {
            Text(
                text = hintText,
                modifier = Modifier.padding(top = 8.dp),
                style = MaterialTheme.typography.bodySmall,
                color = if (hintIsError) {
                    MaterialTheme.colorScheme.error
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant
                }
            )
        }
    }
}

/**
 * 取票码单格。只在底部压一道横线：画整框会变成六个输入框，取票机面板上是压印的横线。
 *
 * @param digit 该位已输入的数字，未输入为 null
 * @param showCaret 是否是当前输入位，闪烁光标只在这一格出现
 */
@Composable
private fun RowScope.CodeCell(digit: Char?, showCaret: Boolean) {
    val accent = MaterialTheme.colorScheme.primary
    val underline = accent.copy(alpha = if (digit != null) 0.9f else 0.55f)
    Box(
        modifier = Modifier
            .weight(1f)
            .height(40.dp)
            .drawBehind {
                val stroke = 2.dp.toPx()
                drawLine(
                    color = underline,
                    start = Offset(0f, size.height - stroke / 2f),
                    end = Offset(size.width, size.height - stroke / 2f),
                    strokeWidth = stroke
                )
            },
        contentAlignment = Alignment.Center
    ) {
        when {
            digit != null -> Text(
                text = digit.toString(),
                fontFamily = PixelFontFamily,
                fontSize = pixelFontSize(24.dp),
                color = accent
            )
            showCaret -> BlinkingCaret(color = accent)
        }
    }
}

/** 当前输入位的闪烁竖条。光标换位时整个节点重建，闪烁从亮开始，刚按完键光标一定看得见。 */
@Composable
private fun BlinkingCaret(color: Color) {
    val transition = rememberInfiniteTransition(label = "machine_caret")
    val caretAlpha by transition.animateFloat(
        initialValue = 1f,
        targetValue = 0f,
        animationSpec = infiniteRepeatable(tween(durationMillis = 500), RepeatMode.Reverse),
        label = "machine_caret_alpha"
    )
    Box(
        modifier = Modifier
            .width(2.dp)
            .height(20.dp)
            // 闪烁只改 alpha，走 graphicsLayer 留在绘制阶段，不每帧触发重组
            .graphicsLayer { alpha = caretAlpha }
            .background(color)
    )
}

@Composable
private fun RowScope.DigitKey(digit: Char, enabled: Boolean, onClick: () -> Unit) {
    // 数字键不给 contentDescription：数字文本本身就是读屏内容
    MachineKey(enabled = enabled, onClick = onClick) {
        Text(
            text = digit.toString(),
            fontFamily = PixelFontFamily,
            fontSize = pixelFontSize(20.dp),
            color = MaterialTheme.colorScheme.onSurface
        )
    }
}

@Composable
private fun RowScope.PasteKey(enabled: Boolean, onClick: () -> Unit) {
    MachineKey(enabled = enabled, onClick = onClick) {
        // 中文比数字宽，字号得比数字键降一档才不顶键帽边缘
        Text(
            text = stringResource(R.string.machine_key_paste),
            fontFamily = PixelFontFamily,
            fontSize = pixelFontSize(16.dp),
            color = MaterialTheme.colorScheme.onSurface
        )
    }
}

@Composable
private fun RowScope.BackspaceKey(enabled: Boolean, onClick: () -> Unit) {
    MachineKey(enabled = enabled, onClick = onClick) {
        Icon(
            imageVector = Icons.AutoMirrored.Outlined.Backspace,
            contentDescription = stringResource(R.string.machine_key_backspace_desc),
            tint = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.size(22.dp)
        )
    }
}

/** 机器按键键帽。方角、按下缩到 0.92、上缘一道高光，摸上去像塑料按键而不是玻璃卡片。 */
@Composable
private fun RowScope.MachineKey(
    enabled: Boolean,
    onClick: () -> Unit,
    content: @Composable () -> Unit
) {
    val interactionSource = remember { MutableInteractionSource() }
    val isPressed by interactionSource.collectIsPressedAsState()
    val scale by animateFloatAsState(
        targetValue = if (isPressed) 0.92f else 1f,
        label = "machine_key_scale"
    )
    val isDarkTheme = MaterialTheme.colorScheme.background.luminance() < 0.5f
    val shape = RoundedCornerShape(8.dp)
    Box(
        modifier = Modifier
            .weight(1f)
            // 1.5：常见宽度下每键约 100x67dp，触达面积够，也不会在宽屏上摊成细长条
            .aspectRatio(1.5f)
            .scale(scale)
            // .alpha() 必须排在 .background() 之前：它只作用于链上位于其后的绘制，
            // 放在后面就只压暗内容、压不暗键帽
            .alpha(if (enabled) 1f else 0.4f)
            .clip(shape)
            .background(Color.White.copy(alpha = if (isDarkTheme) 0.10f else 0.34f))
            .background(
                Brush.verticalGradient(
                    listOf(Color.White.copy(alpha = 0.22f), Color.Transparent)
                )
            )
            .border(
                width = 1.dp,
                color = Color.White.copy(alpha = if (isDarkTheme) 0.14f else 0.42f),
                shape = shape
            )
            .clickable(
                interactionSource = interactionSource,
                indication = ripple(color = MaterialTheme.colorScheme.primary),
                enabled = enabled,
                onClick = onClick
            ),
        contentAlignment = Alignment.Center
    ) {
        content()
    }
}

/** 外接键盘按键到数字的映射。主键区和小键盘都收，两者都可能被用来输码。 */
private fun digitFromKey(key: Key): Char? = when (key) {
    Key.Zero, Key.NumPad0 -> '0'
    Key.One, Key.NumPad1 -> '1'
    Key.Two, Key.NumPad2 -> '2'
    Key.Three, Key.NumPad3 -> '3'
    Key.Four, Key.NumPad4 -> '4'
    Key.Five, Key.NumPad5 -> '5'
    Key.Six, Key.NumPad6 -> '6'
    Key.Seven, Key.NumPad7 -> '7'
    Key.Eight, Key.NumPad8 -> '8'
    Key.Nine, Key.NumPad9 -> '9'
    else -> null
}
