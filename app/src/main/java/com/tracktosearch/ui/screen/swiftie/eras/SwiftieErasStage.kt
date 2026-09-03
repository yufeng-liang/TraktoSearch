package com.tracktosearch.ui.screen.swiftie.eras

import androidx.compose.animation.core.EaseInOutCubic
import androidx.compose.animation.core.EaseOutCubic
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.tracktosearch.R
import com.tracktosearch.ui.screen.swiftie.SwiftiePalette
import com.tracktosearch.ui.screen.swiftie.SwiftieSequenceClock
import com.tracktosearch.ui.screen.swiftie.SwiftieTimeline
import kotlinx.coroutines.delay

/** 「跳过」在 T3000 淡入 —— 前 3 秒先让惊喜落地，别一上来就劝人走（Spec §6.1）。 */
private const val SKIP_FADE_IN_AT_MS: Long = 3_000L
private const val SKIP_FADE_MS: Float = 600f

/** 首次拖动播放头后，3s 无操作自动续播。 */
private const val AUTO_RESUME_MS: Long = 3_000L

/** Lover 绽放的放大倍率。1.5 配上上移，视觉上就铺满了。 */
private const val BLOOM_SCALE: Float = 0.5f

/**
 * 「跳过」那一行占的高度。
 *
 * 它**排在轴下面的常规流里**，不是浮在 `BottomEnd` —— 浮着的时候按钮
 * （TextButton 最小 40dp + 4dp 外边距）正好压在轴下面那行年份上，右边的
 * 「2025」和「跳过」两个字叠在一起。给它一行自己的高度，年份就落在按钮上方。
 */
private val SKIP_ROW_HEIGHT = 44.dp

/**
 * 「继续」离底边的距离：跳过那一行 + 年份 + 轴本身，再留 8dp。
 *
 * 写成加法而不是一个常量，是因为它要的语义是「停在轴上方」—— 上面几个数字
 * 任何一个改了，这里必须跟着走，否则它会掉进轴里，而那一段轴是可拖的。
 */
private val RESUME_BOTTOM_PADDING = SKIP_ROW_HEIGHT + 16.dp + 48.dp + 8.dp

/** 当前该显示第几张卡片。倒滑与绽放期间钉在 Lover。 */
private fun activeEraIndexAt(elapsedMs: Long): Int =
    SwiftieTimeline.eraIndexAt(elapsedMs) ?: if (elapsedMs < SwiftieTimeline.ERAS_CARDS_START) {
        0
    } else {
        SwiftieErasData.LOVER_INDEX
    }

/**
 * Eras 回顾段的驱动层：轴 + 当前卡片 + 跳过 / 继续。
 *
 * 挂载区间是 `ERAS_INTRO`..`ERAS_CARDS` 与 `REWIND`..`FADE_OUT` 两段 ——
 * 中间的终局（签名 / 手链 / 定格）期间整层卸载，配乐唱到 Lover 时再回来做收尾，
 * 一直留到最后那 2998ms 的淡出（要淡的主体正是绽放开的 Lover 卡片）。
 * 所以内部有两处要判：终局之后「跳过」关掉（`skipVisible`），
 * 轴也不再收触摸（`axisInteractive`）。
 *
 * @param frozen 拖过播放头之后的定格状态，由 `SwiftieEggScreen` 持有 ——
 *   它要和「按住暂停」或起来一起喂给 `clock.paused`
 * @param replay 从「关于」页重看：「跳过」立刻可用，不等 3s
 */
@Composable
fun SwiftieErasStage(
    clock: SwiftieSequenceClock,
    frozen: Boolean,
    onFrozenChange: (Boolean) -> Unit,
    replay: Boolean,
    modifier: Modifier = Modifier
) {
    // 每帧变的量只在 draw lambda 里读；组合里只读这一个「翻转 12 次」的派生量
    val activeIndex by remember { derivedStateOf { activeEraIndexAt(clock.elapsedMs) } }
    val era = SwiftieErasData.ALL[activeIndex]
    val cardDurationMs = SwiftieTimeline.cardDurationMs(activeIndex, era.tracks.size)
    // 倒滑与绽放期间把 Lover 钉在停留末帧，不许它回落
    val holdCapMs = cardDurationMs - CARD_RECEDE_MS - CARD_GAP_MS - 1L

    var autoResumeArmed by remember { mutableStateOf(false) }
    var seekTick by remember { mutableIntStateOf(0) }

    // 首次拖动给 3s 宽限自动续播（手滑碰一下不该把人困在定格里）；
    // 再拖第二次就说明是真想手动看，从此只认「继续」
    LaunchedEffect(frozen, autoResumeArmed, seekTick) {
        if (!frozen || !autoResumeArmed) return@LaunchedEffect
        delay(AUTO_RESUME_MS)
        onFrozenChange(false)
    }

    val introProgress: () -> Float = {
        ((clock.elapsedMs - SwiftieTimeline.ERAS_INTRO_START).toFloat() /
            SwiftieTimeline.ERAS_INTRO_MS).coerceIn(0f, 1f)
    }

    // 倒滑：从轴末端滑回 Lover 段中心
    val loverCenter = swiftieEraCenterFraction(SwiftieErasData.LOVER_INDEX)
    val playheadFraction: () -> Float = {
        val elapsed = clock.elapsedMs
        when {
            elapsed < SwiftieTimeline.REWIND_START -> swiftiePlayheadFraction(elapsed)
            elapsed < SwiftieTimeline.LOVER_BLOOM_START -> {
                val p = EaseInOutCubic.transform(
                    ((elapsed - SwiftieTimeline.REWIND_START).toFloat() /
                        SwiftieTimeline.REWIND_MS).coerceIn(0f, 1f)
                )
                1f + (loverCenter - 1f) * p
            }
            else -> loverCenter
        }
    }

    val bloomProgress: () -> Float = {
        EaseOutCubic.transform(
            ((clock.elapsedMs - SwiftieTimeline.LOVER_BLOOM_START).toFloat() /
                SwiftieTimeline.LOVER_BLOOM_MS).coerceIn(0f, 1f)
        )
    }

    // 卡片段之外不收触摸：重挂载之后（倒滑 / 绽放 / 淡出）碰一下就会把时钟
    // 倒拨回卡片段，而那三段正是配乐钉死的收尾
    val axisInteractive by remember {
        derivedStateOf { clock.elapsedMs < SwiftieTimeline.ERAS_CARDS_END }
    }

    // alpha = 0 的按钮照样点得到（graphicsLayer 只改绘制、不改命中区域），
    // 所以淡入没走完之前必须同时 enabled = false。
    // 收尾的倒滑与绽放期间也一并关掉：那时终局已经放完，跳过没有意义，
    // 而 skipToFinalHold 会把时钟倒拨回去，Lover 就与配乐错开了
    val skipVisible by remember {
        derivedStateOf {
            clock.elapsedMs < SwiftieTimeline.SIGNATURE_START &&
                (replay || clock.elapsedMs >= SKIP_FADE_IN_AT_MS)
        }
    }

    Box(modifier = modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing)) {
        Column(modifier = Modifier.fillMaxSize()) {
            BoxWithConstraints(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .padding(horizontal = 20.dp),
                contentAlignment = Alignment.BottomCenter
            ) {
                // 卡片自己按这个高度折算曲目行高：31 首的 TTPD Anthology 在小屏上要压行
                val slotHeight = maxHeight
                // key 换值就重挂：新卡片的 elapsedInCard 从 0 起算，
                // 上一张此时 scaleY 已经收到 0，看不到硬切
                key(activeIndex) {
                    SwiftieEraCard(
                        era = era,
                        elapsedInCard = {
                            if (clock.elapsedMs >= SwiftieTimeline.REWIND_START) {
                                holdCapMs
                            } else {
                                clock.elapsedMs - SwiftieTimeline.eraStartMs(activeIndex)
                            }
                        },
                        durationMs = cardDurationMs,
                        originFractionX = swiftieEraCenterFraction(activeIndex),
                        slotHeight = slotHeight,
                        modifier = Modifier
                            .fillMaxWidth()
                            .widthIn(max = 480.dp)
                            // Lover 绽放：放大 + 上移，铺开整屏交给 Phase E 接手
                            .graphicsLayer {
                                val p = bloomProgress()
                                scaleX = 1f + BLOOM_SCALE * p
                                scaleY = 1f + BLOOM_SCALE * p
                                translationY = -p * size.height * 0.22f
                            }
                    )
                }
            }

            Spacer(modifier = Modifier.height(14.dp))

            SwiftieErasAxis(
                introProgress = introProgress,
                playheadFraction = playheadFraction,
                interactive = axisInteractive,
                onSeekToEra = { index, gestureStart ->
                    // 「首次」只能在手势的第一个事件上判：seekToEra 会把 userSeeked 置真，
                    // 拖动的第二个事件读到的就已经是 true，宽限会被拖动自己作废
                    if (gestureStart) autoResumeArmed = !clock.userSeeked
                    clock.seekToEra(index)
                    seekTick++
                    onFrozenChange(true)
                },
                // 绽放时轴让位
                modifier = Modifier.graphicsLayer { alpha = 1f - bloomProgress() }
            )

            // 「跳过」占住轴下面这一行。高度写死，按钮淡出时这一行也不塌 ——
            // 塌下去整根轴会往下挪 44dp，收尾那几秒看着像画面沉了一下
            Box(
                modifier = Modifier.fillMaxWidth().height(SKIP_ROW_HEIGHT),
                contentAlignment = Alignment.CenterEnd
            ) {
                TextButton(
                    onClick = {
                        clock.skipToFinalHold()
                        onFrozenChange(false)
                    },
                    enabled = skipVisible,
                    modifier = Modifier
                        .padding(end = 8.dp)
                        .graphicsLayer {
                            // 重看时立刻可用；首次要等到 T3000 才淡入
                            alpha = when {
                                clock.elapsedMs >= SwiftieTimeline.SIGNATURE_START -> 0f
                                replay -> 1f
                                else ->
                                    ((clock.elapsedMs - SKIP_FADE_IN_AT_MS) / SKIP_FADE_MS)
                                        .coerceIn(0f, 1f)
                            }
                        }
                ) {
                    Text(
                        text = stringResource(R.string.swiftie_skip),
                        color = SwiftiePalette.RoyalBlue.copy(alpha = 0.75f),
                        style = MaterialTheme.typography.labelMedium
                    )
                }
            }
        }

        if (frozen) {
            TextButton(
                onClick = { onFrozenChange(false) },
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(bottom = RESUME_BOTTOM_PADDING)
            ) {
                Text(
                    text = stringResource(R.string.swiftie_resume),
                    color = SwiftiePalette.RoyalBlue,
                    style = MaterialTheme.typography.labelLarge
                )
            }
        }
    }
}
