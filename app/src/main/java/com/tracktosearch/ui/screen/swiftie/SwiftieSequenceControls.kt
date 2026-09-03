package com.tracktosearch.ui.screen.swiftie

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.SkipNext
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.tracktosearch.R

/** 浮出 / 收回的时长。200ms 是「浮上来」而不是「弹出来」，与整页的淡入同一档。 */
private const val CONTROLS_MOTION_MS = 200

/** 胶囊高度，也是每个半边的触控目标 —— 48dp 是 Material 下限（与轴同一个理由）。 */
private val CAPSULE_HEIGHT = 48.dp

/**
 * 胶囊底色。
 *
 * **压深底而不是提亮底**：这一层浮在 12 张各不相同的专辑背景上，末档底色是深色的
 * 占 10 张（reputation 直接是纯黑）。黑 42% 的压底配纯白内容，在最浅的那一档
 * （Lover 的天蓝 `#9BC4E8`）仍有 5.0:1、在纯黑上是 21:1 —— 一套值吃满 12 张，
 * 所以这个控件不需要 `activeIndex`，也不会在换张的 500ms 交叉淡变里跟着闪。
 */
private val CAPSULE_FILL = Color.Black.copy(alpha = 0.42f)

/** 细描边。纯黑底上压底自己看不出边界，胶囊的轮廓全靠这一圈。 */
private val CAPSULE_BORDER = Color.White.copy(alpha = 0.28f)

/** 顶部一层极淡的白，给压底一点玻璃的厚度感。 */
private val CAPSULE_SHEEN = Brush.verticalGradient(
    colors = listOf(Color.White.copy(alpha = 0.10f), Color.Transparent)
)

/** 胶囊里的图标、文字与水波纹共用这一个前景色（靠 `LocalContentColor` 下发）。 */
private val CAPSULE_INK = Color.White

/**
 * 序列播放期间的暂停 / 跳过控件。
 *
 * 「点屏幕浮出一组按钮、选了才动作」取代了原先的「点一下就暂停」与「按住暂停」——
 * 那两种都会在用户只想看清一张卡片时误触发，而且没有任何可见的确认。原先常驻右下角的
 * 「跳过」也并进这一组，屏上因此只剩一个悬浮物。
 *
 * 毛玻璃是**假的**：半透明压底 + 细描边 + 顶部一层淡白，没有真的背景模糊。真模糊要把
 * 全屏背景每帧重采样一遍，而这一层待着的正是一段 126s、每帧都在动的背景 —— 那会是整条
 * 序列里最贵的一笔，换来的只是一个几秒就收回去的胶囊。
 *
 * 自动隐藏的计时**不在这里**（`SwiftieEggScreen` 管 3.5s 与 [visible]），
 * 本函数只负责「给了 [visible] 就正确显示」。
 *
 * @param paused true 时「暂停」变「继续」
 * @param skipEnabled false 时「跳过」那半边**整块不渲染**、胶囊自己收窄。收尾的倒滑与
 *   绽放期间跳过没有意义（会把配乐钉死的两个点拨错），而灰掉一个按钮只会让人反复去点
 * @param modifier 摆位（居中偏下）交给调用方，本控件只包住自己的内容
 */
@Composable
fun SwiftieSequenceControls(
    visible: Boolean,
    paused: Boolean,
    skipEnabled: Boolean,
    onTogglePause: () -> Unit,
    onSkip: () -> Unit,
    modifier: Modifier = Modifier
) {
    // 整块挂载 / 卸载，而不是把 alpha 压到 0：这一层的历史坑正是「alpha = 0 的按钮
    // 照样点得到」（graphicsLayer 只改绘制、不改命中区域）
    AnimatedVisibility(
        visible = visible,
        // 淡入 + 轻微上移：从自己 1/4 高的下方浮上来
        enter = fadeIn(animationSpec = tween(CONTROLS_MOTION_MS)) +
            slideInVertically(animationSpec = tween(CONTROLS_MOTION_MS)) { it / 4 },
        exit = fadeOut(animationSpec = tween(CONTROLS_MOTION_MS)) +
            slideOutVertically(animationSpec = tween(CONTROLS_MOTION_MS)) { it / 4 },
        modifier = modifier
    ) {
        // 图标 / 文字 / 水波纹一起走 CAPSULE_INK。不下发的话水波纹会取主题的
        // onSurface —— 浅色主题下是近黑，压在这个深底胶囊上等于没有按压反馈
        CompositionLocalProvider(LocalContentColor provides CAPSULE_INK) {
            Row(
                modifier = Modifier
                    .clip(CircleShape)
                    .background(CAPSULE_FILL)
                    .background(CAPSULE_SHEEN)
                    .border(width = 1.dp, color = CAPSULE_BORDER, shape = CircleShape),
                verticalAlignment = Alignment.CenterVertically
            ) {
                SwiftieControlHalf(
                    icon = if (paused) Icons.Rounded.PlayArrow else Icons.Rounded.Pause,
                    label = stringResource(
                        if (paused) R.string.swiftie_resume else R.string.swiftie_pause
                    ),
                    // 退场那 200ms 里节点仍挂在树上，而此时 visible 已经是 false ——
                    // 顺手把 enabled 一起关掉，收回途中点不出第二次动作
                    enabled = visible,
                    onClick = onTogglePause
                )
                if (skipEnabled) {
                    Box(
                        modifier = Modifier
                            .width(1.dp)
                            .height(20.dp)
                            .background(CAPSULE_BORDER)
                    )
                    SwiftieControlHalf(
                        icon = Icons.Rounded.SkipNext,
                        label = stringResource(R.string.swiftie_skip),
                        enabled = visible,
                        onClick = onSkip
                    )
                }
            }
        }
    }
}

/**
 * 胶囊里的半边：图标 + 文字。
 *
 * 文字**跟随系统字号**（与轴上那行 TS 标签相反）—— 这是个真按钮而不是定时动画里的
 * 固定版面，放大档位下必须跟着长。胶囊本身是自适应宽高，只在极端档位配上最长的译文
 * （日文「一時停止」）时用省略号收尾，而不是把字形裁掉。
 */
@Composable
private fun SwiftieControlHalf(
    icon: ImageVector,
    label: String,
    enabled: Boolean,
    onClick: () -> Unit
) {
    Row(
        modifier = Modifier
            // 把触控目标撑到 48dp：图标 + 单行文字的自然高只有 20dp 上下，
            // 不撑起来手指就只有胶囊中间那一条能点。撑在 clickable **之前**，
            // 命中区域才跟着一起长
            .defaultMinSize(minHeight = CAPSULE_HEIGHT)
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = 18.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            imageVector = icon,
            // 语义交给可见的那行文字：clickable 会把这半边合并成一个按钮节点，
            // 图标再带一份 contentDescription 就会念成「暂停 暂停」
            contentDescription = null,
            modifier = Modifier.size(18.dp)
        )
        Spacer(modifier = Modifier.width(6.dp))
        Text(
            text = label,
            style = MaterialTheme.typography.labelLarge,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}
