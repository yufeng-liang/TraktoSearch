package com.tracktosearch.ui.screen.swiftie

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalView
import com.tracktosearch.ui.haptic.AppHaptics
import com.tracktosearch.ui.haptic.HapticSemantic
import com.tracktosearch.ui.haptic.LocalAppHaptics
import javax.inject.Provider

/**
 * 把 [SwiftieHapticScore] 接到序列时钟上。谱子是「该发什么」，本类是「现在还该不该发」。
 *
 * 三条规则全在这里，谱子一条都不管 —— 它是纯函数，看不见时钟也看不见引擎：
 *
 * 1. **seek 丢弃，不补发。** [seekEpoch] 变了就把游标直接挪到新位置、一记不发。
 *    用户拖轴线跳到第 9 张卡，不能把 1–8 张的落地声一次性放出来。
 * 2. **闸门静音也是丢弃，不是攒着。** [muted] 期间照样推游标，只是不派发；
 *    闸门重开之后从当时的时刻接着走，不会把欠下的几十记补发成一串乱码。
 * 3. **包络被接下时，它的替身要丢掉。** 表里每条包络都有一份「最低层」替身
 *    （见 [SwiftieHapticCueKind.standInFor]），两者会同时排在谱子上；
 *    `playEnvelope` 返回 true 就记住这一类，之后它的替身一律跳过。
 *
 * 派发一律经 [perform] / [playEnvelope] 两个注入的口子转给 `AppHaptics`，
 * **不绕过降级链自己挑 backend**，也**不走 `ComposeHaptics`** ——
 * 后者的 `frequentTick()` 自带 40ms 节流，而编排里按乐句发的密集 tick 是设计好的节奏，
 * 被节流吞掉就不成谱子了（设计文档「节流为什么落在 Compose 这一层」）。
 *
 * 不是线程安全的：只在主线程用（tier 0 要碰 `View`）。
 *
 * @param score 谱子。reducedMotion 的过滤在它构造时就做完了，本类不重复判断。
 * @param perform 发一记离散触感，接 `AppHaptics.perform(view, semantic)`。
 * @param playEnvelope 播一段连续包络，接 `AppHaptics.playEnvelope`。
 *   **返回值是要处理的结果**：false 表示本机播不了，替身要放行。
 * @param quietDown 停掉在飞的波形，接 `AppHaptics.stopOngoing()`。
 *   闸门关上与页面退出时调，见 [stopOngoing]。
 */
class SwiftieHapticConductor(
    private val score: SwiftieHapticScore,
    private val perform: (HapticSemantic) -> Boolean,
    private val playEnvelope: (IntArray, FloatArray) -> Boolean,
    private val quietDown: () -> Unit = {},
) {
    /** 已经处理到的时刻。下一帧只看 `(cursorMs, elapsedMs]` 这一段。 */
    private var cursorMs: Long = 0L

    /** 上次见到的 `seekEpoch`。初值与 `SwiftieSequenceClock.seekEpoch` 的初值一致。 */
    private var lastSeekEpoch: Int = 0

    /** 哪几段包络真的被某一层接下了。它们的替身从此静音。 */
    private val envelopesTaken: MutableSet<SwiftieHapticCueKind> = mutableSetOf()

    /**
     * 派发过包络没有。[stopOngoing] 靠它短路。
     *
     * 一记都没派发过就没有「在飞的波形」可停，而 [quietDown] 会连带把触感引擎解析出来
     * （`Provider.get()` 第一次要跑完整套设备能力探测），那笔阻塞不该由「页面挂载」
     * 或「用户答题期间按住屏幕」这种什么都还没响过的时刻付。
     */
    private var everPlayedEnvelope: Boolean = false

    /**
     * 每帧调一次。
     *
     * @param elapsedMs 序列已用毫秒，取自 `SwiftieSequenceClock.elapsedMs`
     * @param seekEpoch `SwiftieSequenceClock.seekEpoch`，变了就是被拨过
     * @param muted `SwiftieMusic` 那三条闸门的合并结果：按住暂停 / 拖轴定格、
     *   `ON_STOP`（息屏或切后台）、`AUDIOFOCUS_LOSS`（焦点永久丢失）
     * @return 本帧真的派发出去的事件，按时间升序。包络无论有没有被某一层接下都算派发 ——
     *   接不下时它的替身会紧跟着出现在同一份列表里。给单测与日志用，调用方可以丢掉
     */
    fun onFrame(elapsedMs: Long, seekEpoch: Int, muted: Boolean): List<SwiftieHapticCue> {
        // 被拨过：跨过去的事件一律丢掉。这一条在最前面 —— 拨完那一帧连 muted 都不必看
        if (seekEpoch != lastSeekEpoch) {
            lastSeekEpoch = seekEpoch
            cursorMs = elapsedMs
            return emptyList()
        }
        // 时钟没走（暂停）或倒着走（不该发生，但别让它把一整段重放一遍）
        if (elapsedMs <= cursorMs) {
            cursorMs = elapsedMs
            return emptyList()
        }
        val due = score.cuesIn(cursorMs, elapsedMs)
        cursorMs = elapsedMs
        if (muted || due.isEmpty()) return emptyList()
        return due.filter(::dispatch)
    }

    /**
     * 立刻停掉在飞的波形。闸门关上、用户按 ✕ 退出、页面销毁时都要调。幂等，可以白调。
     *
     * 一段包络都还没派发过时直接返回，连引擎都不解析（见 [everPlayedEnvelope]）。
     *
     * **两层都停得住。** `AppHaptics.stopOngoing()` 落到 `HapticModule` 注入的
     * `quietDown`，那里 `RichTapBackend.stop()` 与 `AospWaveformBackend.cancel()` 两个
     * 都调、不短路：RichTap 那层在从未 init 成功的机型上直接返回，而那种机器上真正在播的
     * 正是 tier 1 那段包络，靠 `cancel()` 里的 `Vibrator.cancel()` 停。
     * 两个 cancel 都不在 `HapticBackend` 契约里 —— 只有真能画连续波形的 tier 3 与 tier 1
     * 需要停止通道，tier 2 的预置效果与 tier 0 的常量都是几十毫秒的一次性事件，没什么可停。
     */
    fun stopOngoing() {
        if (!everPlayedEnvelope) return
        quietDown()
    }

    /**
     * 派发一条，返回它是否真的发了出去（替身被抑制时返回 false）。
     *
     * `when` 穷举 [SwiftieHapticCue] 的两个实现、不写 `else`：以后加第三种事件形态时
     * 编译器会逼着表态。
     */
    private fun dispatch(cue: SwiftieHapticCue): Boolean = when (cue) {
        is SwiftieEnvelopeCue -> {
            everPlayedEnvelope = true
            // 谱子里包络排在自己的替身之前（见 SwiftieHapticScore.cues），
            // 所以同一毫秒上的替身一定能读到这一步的结果
            if (playEnvelope(cue.timingsMs.toIntArray(), cue.amplitudes.toFloatArray())) {
                envelopesTaken += cue.kind
            }
            true
        }

        is SwiftieDiscreteCue -> {
            val standInFor = cue.kind.standInFor
            if (standInFor != null && standInFor in envelopesTaken) {
                false
            } else {
                perform(cue.semantic)
                true
            }
        }
    }
}

/**
 * 建一个接到本组合树触感引擎上的指挥。
 *
 * **拿的是 `Provider<AppHaptics>` 而不是 `ComposeHaptics`。** 两个理由，缺一不可：
 *
 * - 编排要 `playEnvelope` 与 `stopOngoing`，`ComposeHaptics` 刻意不转发这两个 ——
 *   它们是「一段几秒的波形」而不是「一次交互反馈」。
 * - `ComposeHaptics.frequentTick()` 自带 40ms 节流。谱子里的 `FREQUENT_TICK`
 *   （12 段曲目列收尾、3 记摆动余震）按乐句排好了间隔，被那道闸吞掉就不成谱子。
 *   手链可拖交互那条「逐珠划过」是唯一该节流的一行，它走 `ComposeHaptics`，
 *   见 `SwiftieBracelet`。
 *
 * 引擎**惰性解析**，与 `ComposeHaptics` 同一套做法：`Provider.get()` 第一次会跑完
 * `HapticCapabilities.probe`（`getSystemService`、到 `VibratorService` 的 IPC、
 * 三次类查找），那笔阻塞成本由 `TraktSearchApp` 的启动预热在后台线程付掉；
 * 这里只在真要发第一记时取一次，解析失败就整条静音，不让「触感是装饰」拖垮彩蛋。
 *
 * 按 `LocalView` 与 [LocalAppHaptics] 两者 `remember`：换了宿主 `View` 必须重新捕获，
 * 否则 tier 0 会把触感发到已经 detach 的 `View` 上。
 *
 * @param reducedMotion 系统要求「减少动效」。它决定谱子的内容，所以是 `remember` 的键
 */
@Composable
internal fun rememberSwiftieHapticConductor(reducedMotion: Boolean): SwiftieHapticConductor {
    val view = LocalView.current
    val provider = LocalAppHaptics.current
    return remember(view, provider, reducedMotion) {
        val engine = SwiftieHapticEngine(provider)
        SwiftieHapticConductor(
            score = SwiftieHapticScore(reducedMotion = reducedMotion),
            perform = { semantic -> engine.get()?.perform(view, semantic) == true },
            playEnvelope = { timingsMs, amplitudes ->
                engine.get()?.playEnvelope(timingsMs, amplitudes) == true
            },
            quietDown = { engine.get()?.stopOngoing() },
        )
    }
}

/**
 * 惰性解析 [AppHaptics] 的一层薄壳。做法与 `ComposeHaptics` 里那个 `engine()` 一致。
 *
 * `runCatching`：正常情况下 [Provider.get] 只是读一个已经预热好的 `@Singleton`，
 * 但真要是 Hilt 图出了问题，不该由「用户答对了一道彩蛋题」这件事把进程带走。
 * 失败一次就闩住 —— 图坏了不是下一帧能修好的，每帧重试只是每帧抛一次异常。
 *
 * @param provider null 表示本次组合树里没人 provide `LocalAppHaptics`
 *   （`@Preview` 与不建 Hilt 图的屏幕测试），此时整条编排静音
 */
private class SwiftieHapticEngine(private val provider: Provider<AppHaptics>?) {
    private var resolved: AppHaptics? = null
    private var failed = false

    fun get(): AppHaptics? {
        resolved?.let { return it }
        if (failed) return null
        val engine = runCatching { provider?.get() }.getOrNull()
        if (engine == null) failed = true else resolved = engine
        return engine
    }
}

