@file:OptIn(dev.chrisbanes.haze.ExperimentalHazeApi::class)

package com.tracktosearch.ui.screen.swiftie

import android.os.Build
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Backspace
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.ripple
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.tracktosearch.R
import dev.chrisbanes.haze.HazeInput
import dev.chrisbanes.haze.HazeState
import dev.chrisbanes.haze.blur.HazeBlurStyle
import dev.chrisbanes.haze.blur.hazeBlur

/** 键帽最小高度。`aspectRatio(1.6f)` 在窄屏上算出来可能不足 48dp，触控目标要兜住。 */
private val KEY_MIN_HEIGHT = 48.dp

/** 数字字号。写成 dp 是因为键帽高度由 `aspectRatio` 从宽度算出，也是 dp。 */
private val KEY_DIGIT_SIZE = 34.dp

/** 提交键文案字号，对齐 `titleMedium` 的 16sp。 */
private val KEY_LABEL_SIZE = 16.dp

/** 键帽之间、行与行之间的间距。 */
private val KEY_GAP = 10.dp

/** 托盘圆角与内边距。 */
private val TRAY_RADIUS = 28.dp
private val TRAY_PADDING = 12.dp

/**
 * 键帽文字跟随系统字号的上限。
 *
 * 键帽是 dp 尺寸（`aspectRatio(1.6f)` 从宽度算高度），文字若无上限地跟着 `fontScale` 长，
 * 200% 档位下 34sp 的行盒约 80dp、键帽只有 62dp 高，`Text` 默认 `Clip` 会把字形横向切掉。
 * 完全不跟随（纯 `dp.toSp()`）对一个**可交互控件**又太粗暴 —— 键盘不是定时动画，
 * 放大字号的用户就是要看清它。所以放它长到 1.3 倍，之后停住。
 */
private const val KEY_TEXT_MAX_SCALE = 1.3f

/**
 * 托盘底色。
 *
 * 分两档是因为**模糊只有 API 31+ 有**（`minSdk = 26`）：糊过之后天空已经被压成一片
 * 低频色，托盘只要一点白就立得住；不糊的机器上底下是清晰的云和闪粉，同样的透明度会让
 * 数字读在云缝里，所以要厚一档。两档的取值都保证宝蓝数字在最亮的云上仍有 7:1 以上。
 */
private val TRAY_FILL_BLURRED = Color.White.copy(alpha = 0.30f)
private val TRAY_FILL_FLAT = Color.White.copy(alpha = 0.52f)

/** 模糊那一档自带的白纱，叠在糊过的天空上。 */
private val TRAY_HAZE_TINT = Color.White.copy(alpha = 0.22f)

private val TRAY_EDGE = Color.White.copy(alpha = 0.62f)

/** 模糊半径。够大才能把云的形状彻底抹平，留形状就成了「毛玻璃后面有东西」。 */
private val TRAY_BLUR_RADIUS = 24.dp

/** 键帽底色与描边。整块托盘已经把底压白了，键帽只需要再亮一层把自己分出来。 */
private val KEY_FILL = Color.White.copy(alpha = 0.46f)
private val KEY_EDGE = Color.White.copy(alpha = 0.70f)

/** 停用态（答对之后）整块压暗到这个不透明度。 */
private const val DISABLED_ALPHA = 0.45f

/**
 * 键盘的整体高度。
 *
 * 出题页要按它反算算式能停在哪儿，而它由**宽度**推出来（键帽 `aspectRatio(1.6f)`），
 * 所以调用方必须先有宽度才能问高度 —— 写死一个 dp 值在 640dp 高的屏上会把算式顶出画面。
 *
 * @param width 键盘的可用宽度，与传给 [SwiftieKeypad] 的那个一致
 */
fun swiftieKeypadHeight(width: Dp): Dp {
    val keyWidth = (width - TRAY_PADDING * 2 - KEY_GAP * 2) / 3
    val keyHeight = maxOf(keyWidth / 1.6f, KEY_MIN_HEIGHT)
    return keyHeight * 4 + KEY_GAP * 3 + TRAY_PADDING * 2
}

/**
 * 自绘数字键盘。浮在海报上的一整块玻璃托盘，键帽按下缩到 0.92 并出洋红涟漪。
 *
 * **与 Spec §2.3 不同：键盘不再跟随主题。** 它现在压在整屏闪粉海报上，跟着深色模式翻成
 * 深色玻璃 + 浅色数字会在粉蓝天空上糊成一团。配色因此和海报一样是固定值：浅玻璃 +
 * 宝蓝 Honey Script 数字 —— 键上按下去的那个字形，和海报上长出来的那个字形是同一个。
 *
 * 模糊只糊**托盘这一层**，不是 12 个键帽各糊一次：`hazeBlur` 每一处都要采一遍背景，
 * 12 份的代价换不来任何观感差别。
 *
 * @param canSubmit 提交键是否点亮。由 `SwiftieQuizState.canSubmit` 直接喂进来
 * @param enabled 答对后置 false，避免动画期间还能继续按
 * @param hazeState 海报那一层的采样源；为 null 或 API < 31 时退到厚一档的纯透明度
 * @param onSubmitCenter 提交键在 root 坐标系的中心点，供扩散层当原点用
 */
@Composable
fun SwiftieKeypad(
    canSubmit: Boolean,
    onDigit: (Char) -> Unit,
    onBackspace: () -> Unit,
    onSubmit: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    hazeState: HazeState? = null,
    onSubmitCenter: (Offset) -> Unit = {}
) {
    val blurred = hazeState != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S
    val shape = RoundedCornerShape(TRAY_RADIUS)
    val blurStyle = remember {
        HazeBlurStyle {
            blurRadius(TRAY_BLUR_RADIUS)
            backgroundColor(TRAY_HAZE_TINT)
        }
    }

    Column(
        modifier = modifier
            .fillMaxWidth()
            // clip 必须在 hazeBlur 之前：糊出来的那一块要收进圆角里
            .clip(shape)
            .then(
                if (blurred) {
                    Modifier.hazeBlur(input = HazeInput.Sources(hazeState!!), style = blurStyle)
                } else {
                    Modifier
                }
            )
            .background(if (blurred) TRAY_FILL_BLURRED else TRAY_FILL_FLAT)
            .border(width = 1.dp, color = TRAY_EDGE, shape = shape)
            .padding(TRAY_PADDING),
        verticalArrangement = Arrangement.spacedBy(KEY_GAP)
    ) {
        listOf("123", "456", "789").forEach { rowDigits ->
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(KEY_GAP)
            ) {
                rowDigits.forEach { digit ->
                    DigitKey(digit = digit, enabled = enabled, onClick = { onDigit(digit) })
                }
            }
        }
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(KEY_GAP)
        ) {
            BackspaceKey(enabled = enabled, onClick = onBackspace)
            DigitKey(digit = '0', enabled = enabled, onClick = { onDigit('0') })
            SubmitKey(
                highlight = canSubmit,
                enabled = enabled && canSubmit,
                onClick = onSubmit,
                onCenter = onSubmitCenter
            )
        }
    }
}

@Composable
private fun RowScope.DigitKey(digit: Char, enabled: Boolean, onClick: () -> Unit) {
    KeyCell(enabled = enabled, highlight = false, onClick = onClick) {
        Text(
            text = digit.toString(),
            style = TextStyle(
                fontFamily = SwiftieFonts.Marker,
                fontSize = cappedKeyFontSize(KEY_DIGIT_SIZE),
                color = SwiftiePalette.RoyalBlue,
                textAlign = TextAlign.Center
            ),
            maxLines = 1
        )
    }
}

@Composable
private fun RowScope.BackspaceKey(enabled: Boolean, onClick: () -> Unit) {
    val label = stringResource(R.string.swiftie_quiz_backspace)
    KeyCell(enabled = enabled, highlight = false, onClick = onClick) {
        Icon(
            imageVector = Icons.Outlined.Backspace,
            contentDescription = label,
            tint = SwiftiePalette.RoyalBlue,
            modifier = Modifier.size(26.dp)
        )
    }
}

@Composable
private fun RowScope.SubmitKey(
    highlight: Boolean,
    enabled: Boolean,
    onClick: () -> Unit,
    onCenter: (Offset) -> Unit
) {
    KeyCell(
        enabled = enabled,
        highlight = highlight,
        onClick = onClick,
        // root 坐标系的中心点，直接喂给扩散层
        cellModifier = Modifier.onGloballyPositioned { coordinates ->
            val position = coordinates.positionInRoot()
            onCenter(
                Offset(
                    x = position.x + coordinates.size.width / 2f,
                    y = position.y + coordinates.size.height / 2f
                )
            )
        }
    ) {
        Text(
            text = stringResource(R.string.swiftie_quiz_submit),
            style = TextStyle(
                fontSize = cappedKeyFontSize(KEY_LABEL_SIZE),
                // 点亮时底色是 Badge（对白字 5.04:1，合规）；没点亮就压淡的宝蓝
                color = if (highlight) Color.White else SwiftiePalette.RoyalBlue.copy(alpha = 0.55f),
                textAlign = TextAlign.Center
            ),
            maxLines = 1
        )
    }
}

/**
 * 按 [KEY_TEXT_MAX_SCALE] 封顶的字号。
 *
 * `Dp.toSp()` 会把 `fontScale` 除掉，再乘上封顶后的 `fontScale`：默认档位下渲染出来
 * 正好等于 [size] 那么多 dp，放大档位最多长到 1.3 倍。
 */
@Composable
private fun cappedKeyFontSize(size: Dp) = with(LocalDensity.current) {
    (size * fontScale.coerceAtMost(KEY_TEXT_MAX_SCALE)).toSp()
}

@Composable
private fun RowScope.KeyCell(
    enabled: Boolean,
    highlight: Boolean,
    onClick: () -> Unit,
    cellModifier: Modifier = Modifier,
    content: @Composable () -> Unit
) {
    val interactionSource = remember { MutableInteractionSource() }
    val isPressed by interactionSource.collectIsPressedAsState()
    val scale by animateFloatAsState(
        targetValue = if (isPressed) 0.92f else 1f,
        label = "swiftie_key_scale"
    )
    val shape = RoundedCornerShape(18.dp)
    Box(
        modifier = Modifier
            .weight(1f)
            .then(cellModifier)
            // heightIn 必须排在 aspectRatio 之前：它把 minHeight 塞进传下去的约束，
            // aspectRatio 再挑一个满足约束的尺寸。宽屏上比例照旧生效，
            // 窄屏上（宽度 / 1.6 < 48dp）才被 48dp 顶起来
            .heightIn(min = KEY_MIN_HEIGHT)
            // 1.6：窄屏与宽屏保持同一比例，不会在宽屏上摊成细长条
            .aspectRatio(1.6f)
            .scale(scale)
            // .alpha() 必须排在 .background() 之前：它只作用于链上位于其后的绘制，
            // 放在后面就只压暗内容、压不暗底板
            .alpha(if (enabled) 1f else DISABLED_ALPHA)
            .clip(shape)
            .background(if (highlight) SwiftiePalette.Badge else KEY_FILL)
            // 糖果玻璃的上缘高光
            .background(
                Brush.verticalGradient(
                    listOf(Color.White.copy(alpha = 0.30f), Color.Transparent)
                )
            )
            .border(width = 1.dp, color = KEY_EDGE, shape = shape)
            // clickable 不会自己带 Role，TalkBack 只会念内容、不说这是个按钮
            .semantics { role = Role.Button }
            .clickable(
                interactionSource = interactionSource,
                indication = ripple(color = SwiftiePalette.Glitter),
                enabled = enabled,
                onClick = onClick
            ),
        contentAlignment = Alignment.Center
    ) {
        content()
    }
}
