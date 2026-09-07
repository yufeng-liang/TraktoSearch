package com.tracktosearch.ui.screen.swiftie.eras

import androidx.compose.animation.core.EaseInOutCubic
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
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.unit.dp
import com.tracktosearch.ui.screen.swiftie.SwiftieLoverSnowGlobe
import com.tracktosearch.ui.screen.swiftie.SwiftieSequenceClock
import com.tracktosearch.ui.screen.swiftie.SwiftieTimeline
import kotlinx.coroutines.delay

/** 首次拖动播放头后，3s 无操作自动续播。 */
private const val AUTO_RESUME_MS: Long = 3_000L

/**
 * 玻璃球成型的时长。轴在这一段里让位 —— 球一旦成型它就是画面唯一的主体。
 *
 * 与 `SwiftieSnowGlobe` 里那一拍必须同长；两边都从 [SwiftieTimeline] 起算，
 * 所以改账本不会让它们错开。
 */
private const val GLOBE_FORM_MS: Float = 900f

/** 当前该显示第几张卡片。倒滑与绽放期间钉在 Lover。 */
private fun activeEraIndexAt(elapsedMs: Long): Int =
    SwiftieTimeline.eraIndexAt(elapsedMs) ?: if (elapsedMs < SwiftieTimeline.ERAS_CARDS_START) {
        0
    } else {
        SwiftieErasData.LOVER_INDEX
    }

/**
 * Eras 回顾段的驱动层：当前卡片 + 轴 + Lover 收尾的雪景球。
 *
 * 挂载区间是 `ERAS_INTRO`..`ERAS_CARDS` 与 `REWIND`..`FADE_OUT` 两段 ——
 * 中间的终局（签名 / 手链 / 定格）期间整层卸载，配乐唱到 Lover 时再回来做收尾，
 * 一直留到最后那 2998ms 的淡出。
 *
 * **页面背景不在这里** —— L0/L1/L2 三层是页面级的，由 `SwiftieEggScreen` 挂在本层之下，
 * 因为终局那 18.6s 本层是卸载的，而背景必须一直在。
 *
 * **按钮也不在这里** —— 暂停 / 跳过合并成一组浮出控件，由 `SwiftieEggScreen` 持有
 * （它才是收全屏触摸的那一层）。本层只在拖动播放头时把 `frozen` 翻真。
 *
 * @param frozen 拖过播放头之后的定格状态，由 `SwiftieEggScreen` 持有
 * @param onCardBoundsChange 卡片在根坐标里的边框（px）。**只有 TTPD 与 Lover 两张回报**：
 *   背景那台打字机按它把出纸口坐到纸的上缘、把立纸对齐纸宽（31 首的卡片高度按屏高派生、
 *   宽度在平板上封顶 480dp，写死比例换台设备机器就会浮在纸上方或比纸宽出一截）；
 *   Lover 那一箭则要按它算出弓在根坐标里的位置。
 *
 *   倒滑起点之后**不再回报** —— 那之后卡片一边卷收一边进玻璃球，量到的边框越来越小，
 *   已经不代表那张卡了；消费方留着最后一次完整测量，箭才不会跟着缩成一根牙签
 */
@Composable
fun SwiftieErasStage(
    clock: SwiftieSequenceClock,
    frozen: Boolean,
    onFrozenChange: (Boolean) -> Unit,
    onCardBoundsChange: (Rect) -> Unit = {},
    loverAimAngle: () -> Float = { LOVER_FALLBACK_AIM_ANGLE },
    modifier: Modifier = Modifier
) {
    // 每帧变的量只在 draw lambda 里读；组合里只读这一个「翻转 12 次」的派生量
    val activeIndex by remember { derivedStateOf { activeEraIndexAt(clock.elapsedMs) } }
    val era = SwiftieErasData.ALL[activeIndex]
    // TTPD 段头 1400ms 是打字机独奏，卡片的内部时钟整体后移这么多；
    // 卡片自己的总时长因此要把前摇减掉，回落点才仍落在段末 500ms 处
    val prerollMs = if (activeIndex == SwiftieTimeline.TTPD_INDEX) {
        SwiftieTimeline.TTPD_PREROLL_MS
    } else {
        0L
    }
    val cardDurationMs =
        SwiftieTimeline.cardDurationMs(activeIndex, era.tracks.size) - prerollMs
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

    /**
     * 卷收进度：18 行曲目自下而上逐行收起，只留专辑名 + 日期 + 描金那一行。
     *
     * **刻意线性**，不加缓动 —— 卡片内部按它折算每一行的错开时刻（70ms/行 × 18 = 1260ms），
     * 缓动会让最后几行挤在一起收完，读起来不像卷纸像抽断。
     */
    val collapseProgress: () -> Float = {
        ((clock.elapsedMs - SwiftieTimeline.REWIND_START).toFloat() /
            SwiftieTimeline.REWIND_MS).coerceIn(0f, 1f)
    }

    /** 玻璃球成型进度。轴按它让位。 */
    val globeForm: () -> Float = {
        ((clock.elapsedMs - SwiftieTimeline.LOVER_BLOOM_START).toFloat() / GLOBE_FORM_MS)
            .coerceIn(0f, 1f)
    }

    // 卡片段之外不收触摸：重挂载之后（倒滑 / 绽放 / 淡出）碰一下就会把时钟
    // 倒拨回卡片段，而那三段正是配乐钉死的收尾
    val axisInteractive by remember {
        derivedStateOf { clock.elapsedMs < SwiftieTimeline.ERAS_CARDS_END }
    }

    /**
     * 球是否已经接手。
     *
     * 切换点选在 [SwiftieTimeline.LOVER_BLOOM_START] 而不是 `REWIND_START`，是为了不跳位：
     * 卡片在倒滑那 1500ms 里一边卷收一边从插槽底部平移到插槽正中，走到这一刻
     * 正好是「110dp 高、居中」；球这时才长出来，包住的是一个位置和尺寸都已经对齐的东西。
     * 若在 REWIND_START 就换成居中的球，Lover 那张 406dp 的整卡会当场跳 111dp。
     */
    val globeMounted by remember {
        derivedStateOf { clock.elapsedMs >= SwiftieTimeline.LOVER_BLOOM_START }
    }

    /**
     * 还该不该回报卡片边框。
     *
     * 倒滑一起就停：那之后卡片逐行卷收、再缩进玻璃球，`boundsInRoot` 量到的是一个
     * 每帧都在变小的框。消费方（打字机、彩虹上那支箭）要的是「这张卡完整时占哪」，
     * 所以停在最后一次完整测量上。`derivedStateOf` 保证这一路只翻一次。
     */
    val cardBoundsLive by remember {
        derivedStateOf { clock.elapsedMs < SwiftieTimeline.REWIND_START }
    }

    /**
     * 要回报边框的两张：TTPD（背景那台打字机对齐纸）与 Lover（那一箭从卡片里的弓起飞）。
     * 其余 10 张不挂 `onGloballyPositioned`，一次布局回调都不多做。
     */
    val reportsBounds = cardBoundsLive &&
        (activeIndex == SwiftieTimeline.TTPD_INDEX || activeIndex == SwiftieErasData.LOVER_INDEX)

    Box(modifier = modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing)) {
        Column(modifier = Modifier.fillMaxSize()) {
            BoxWithConstraints(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .padding(horizontal = 20.dp),
                contentAlignment = if (globeMounted) Alignment.Center else Alignment.BottomCenter
            ) {
                // 卡片自己按这个高度折算曲目行高：31 首的 TTPD Anthology 在小屏上要压行。
                // TTPD 的卡片要从插槽底再抬高一台打字机的高度（出纸口坐在卡片下缘上），
                // 行高按抬高后的剩余空间折算，别让 31 行把机器顶出屏
                val machineReserve = if (prerollMs != 0L) TTPD_MACHINE_RESERVE else 0.dp
                val slotHeight = maxHeight - machineReserve
                // key 换值就重挂：新卡片的 elapsedInCard 从 0 起算，
                // 上一张此时 scaleY 已经收到 0，看不到硬切
                val card: @Composable (Modifier) -> Unit = { cardModifier ->
                    key(activeIndex) {
                        val elapsedInCard: () -> Long = {
                            if (clock.elapsedMs >= SwiftieTimeline.REWIND_START) {
                                holdCapMs
                            } else {
                                clock.elapsedMs -
                                    SwiftieTimeline.eraStartMs(activeIndex) - prerollMs
                            }
                        }
                        SwiftieEraCard(
                            era = era,
                            eraIndex = activeIndex,
                            elapsedInCard = elapsedInCard,
                            durationMs = cardDurationMs,
                            originFractionX = swiftieEraCenterFraction(activeIndex),
                            slotHeight = slotHeight,
                            collapseProgress = collapseProgress,
                            // 出纸只在 TTPD：前摇里 elapsedInCard 是负的，进度钳到 0f，
                            // 这张卡片连投影都不画
                            feedProgress = if (prerollMs == 0L) {
                                null
                            } else {
                                { elapsedInCard().toFloat() / CARD_FEED_MS }
                            },
                            loverAimAngle = loverAimAngle,
                            modifier = if (reportsBounds) {
                                cardModifier.onGloballyPositioned {
                                    onCardBoundsChange(it.boundsInRoot())
                                }
                            } else {
                                cardModifier
                            }
                        )
                    }
                }

                if (globeMounted) {
                    SwiftieLoverSnowGlobe(
                        elapsedMs = { clock.elapsedMs },
                        modifier = Modifier.fillMaxSize()
                    ) {
                        // 球内的小卡已经卷收完毕，恒为 1f
                        card(Modifier.fillMaxWidth().widthIn(max = 480.dp))
                    }
                } else {
                    card(
                        Modifier
                            .fillMaxWidth()
                            .widthIn(max = 480.dp)
                            // TTPD：卡片从插槽底抬高一台机器，纸的下缘坐在出纸口上；
                            // 其余 11 张仍贴插槽底（轴上长出）
                            .padding(bottom = machineReserve)
                            // 卷收的同时从插槽底部升到插槽正中，好让球在 LOVER_BLOOM_START
                            // 那一帧原地长出来。缓动用 EaseInOutCubic：线性升会在起停两端
                            // 各有一次可见的速度突变
                            .graphicsLayer {
                                val p = EaseInOutCubic.transform(collapseProgress())
                                if (p <= 0f) return@graphicsLayer
                                translationY = -((slotHeight.toPx() - size.height) / 2f) * p
                            }
                    )
                }
            }

            Spacer(modifier = Modifier.height(14.dp))

            SwiftieErasAxis(
                introProgress = introProgress,
                playheadFraction = playheadFraction,
                activeIndex = { activeEraIndexAt(clock.elapsedMs) },
                interactive = axisInteractive,
                onSeekToEra = { index, gestureStart ->
                    // 「首次」只能在手势的第一个事件上判：seekToEra 会把 userSeeked 置真，
                    // 拖动的第二个事件读到的就已经是 true，宽限会被拖动自己作废
                    if (gestureStart) autoResumeArmed = !clock.userSeeked
                    clock.seekToEra(index)
                    seekTick++
                    onFrozenChange(true)
                },
                // 球成型时轴让位
                modifier = Modifier.graphicsLayer { alpha = 1f - globeForm() }
            )

            Spacer(modifier = Modifier.height(24.dp))
        }
    }
}
