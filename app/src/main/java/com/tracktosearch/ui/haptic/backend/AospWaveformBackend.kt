package com.tracktosearch.ui.haptic.backend

import android.content.Context
import android.media.AudioAttributes
import android.os.Build
import android.os.VibrationAttributes
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.view.View
import androidx.annotation.RequiresApi
import com.tracktosearch.ui.haptic.HapticBackend
import com.tracktosearch.ui.haptic.HapticCapabilities
import com.tracktosearch.ui.haptic.HapticSemantic
import com.tracktosearch.ui.haptic.HapticStrength
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import kotlin.math.roundToInt

/**
 * tier 1：AOSP 振幅通路。两条子通路，运行期按能力择一。
 *
 * 1. `VibrationEffect.Composition`（API 30 起）—— 拿厂商自己调过的 primitive 拼，手感最好。
 *    目标机 `supportedPrimitives` 实测是空集，这条走不通；Pixel 上走得通，所以代码要留着。
 * 2. `VibrationEffect.createWaveform(timings, amplitudes, repeat)`（API 26 起）—— 自己画振幅台阶。
 *    只要 `hasAmplitudeControl()` 为 true 就一定能用，是本层的实际落点，也是 [playEnvelope] 的底。
 *
 * 顺序是「Composition 拼得出就拼，拼不出退振幅台阶」。同一个语义在两条子通路上是同一个手感
 * 目标的两种写法，映射表在 [tierOneRecipeOf]。
 *
 * 硬规则落点：
 * - 规则 1：`hasAmplitudeControl()` 为 false（转子马达）时 [isAvailable] 返回 false，本层不上场。
 *   没有振幅控制时所有非零振幅都会被抬到 100%，画多细的包络都失真成一声嗡。
 * - 规则 2：Composition **整组**探测 —— 一个效果用到的全部 primitive 一次传进
 *   `areAllPrimitivesSupported(vararg)`，缺一个就整条退振幅台阶。缺一个还照发的结果是整段
 *   一点都不震，属静默失败。
 * - 不用 `FLAG_IGNORE_GLOBAL_SETTING`；只标 `USAGE_TOUCH`，让系统按用户的触摸触感档位缩放。
 *
 * 线程：[isAvailable] 与 [supports] 是纯查表；`vibrate` 是到 `VibratorService` 的 IPC，
 * 一律丢单线程 `Executor`，[release] 里 shutdown。
 *
 * 设计依据见 docs/superpowers/plans/2026-09-01-haptics-overhaul.md 的「四层引擎」「语义词表」
 * 「目标机实测」三节。
 *
 * @param context 只用来取 `Vibrator`，**不持有引用**。构造期做一次 `getSystemService`，
 *   请和 `HapticCapabilities.probe` 一样放在 IO 上构造，别在主线程 new。
 * @param capabilities [HapticCapabilities.probe] 的结果，本层只读。
 */
class AospWaveformBackend(
    context: Context,
    private val capabilities: HapticCapabilities,
) : HapticBackend {

    override val tier: Int = TIER

    /**
     * tier 0 的常量后端也叫 AOSP，降级矩阵的断言要能把两层分开，所以这层带上子通路名。
     * 编译期常量，[release] 之后仍可读。
     */
    override val name: String = "AospWaveform"

    /** 构造期解析一次。为 null 就等于本层不可用。 */
    private val vibrator: Vibrator? = resolveVibrator(context)

    /**
     * `USAGE_TOUCH` 的 `VibrationAttributes`，API 33 起。
     *
     * `VibrationAttributes` 这个类是 API 30 引入的，但 `Vibrator.vibrate(effect, attributes)`
     * 这个重载要到 API 33 —— API 30..32 上公开 SDK 里根本没有入口能把它传进去，
     * 所以这里按 33 门控，更低版本走 [legacyAudioAttributes]。
     */
    private val touchAttributes: VibrationAttributes? =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            VibrationAttributes.createForUsage(VibrationAttributes.USAGE_TOUCH)
        } else {
            null
        }

    /**
     * API 26..32 的等价物：`AudioAttributes` 的 `USAGE_ASSISTANCE_SONIFICATION`。
     *
     * 框架侧 `VibrationAttributes.Builder(AudioAttributes)` 就是把这个 usage 翻成
     * `USAGE_TOUCH` 的（AOSP `VibrationAttributes.java` 里 `setUsage(AudioAttributes)` 那张
     * switch 表），所以两条入口在服务端等价。
     */
    private val legacyAudioAttributes: AudioAttributes =
        AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_ASSISTANCE_SONIFICATION)
            .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
            .build()

    /**
     * 13 个语义的配方，构造期一次算好。[tierOneRecipeOf] 是纯函数、无 IPC，
     * 算好之后 [supports] 与 [perform] 都只查表，不在点击路径上分配列表。
     */
    private val recipes: Map<HapticSemantic, TierOneRecipe> =
        HapticSemantic.entries.associateWith(::tierOneRecipeOf)

    /**
     * 单线程：`vibrate` 的 IPC 都在这上面跑，且彩蛋编排依赖事件顺序。
     * `newSingleThreadExecutor` 只建对象，线程要到首次 submit 才起，所以构造期不开线程。
     */
    private val executor: ExecutorService =
        Executors.newSingleThreadExecutor { runnable -> Thread(runnable, THREAD_NAME) }

    /** 首次失败即整层禁用，之后不再重试。executor 线程写、主线程读，所以要 volatile。 */
    @Volatile
    private var disabled = false

    /** [release] 之后一切返回 false，不重新起线程。 */
    @Volatile
    private var released = false

    /** 整组探测缓存（硬规则 2），键是排序后的 primitive ID 表。只在 executor 单线程上读写。 */
    private val compositionGroupSupport = HashMap<List<Int>, Boolean>()

    /** API 36 包络的设备限制，[envelopeLimitsOf] 查一次填进来。只在 executor 单线程上读写。 */
    private var envelopeLimits: EnvelopeLimits? = null

    /** 上面那项查过没有。查过而 [envelopeLimits] 仍为 null 就是这条通路不可用，不再重查。 */
    private var envelopeLimitsProbed = false

    /**
     * 纯字段读，可以在每次点击的主线程路径上调。
     *
     * `capabilities` 已经在 IO 上探完，这里不再发任何 IPC。
     * `lockedToConstants`（即 `hasAmplitudeControl()` 为 false）为 true 时返回 false，
     * 这是硬规则 1 —— 转子马达只许 tier 0 上场。
     * [disabled] 一旦被置上就不会翻回来：整层禁用是单向的。
     */
    override fun isAvailable(): Boolean =
        !released &&
            !disabled &&
            vibrator != null &&
            capabilities.hasVibrator &&
            !capabilities.lockedToConstants

    /**
     * 本层能表达全部 13 个语义：`createWaveform` 只要有振幅控制就一定可用，
     * 而 13 条配方每条都带一份振幅台阶。所以这里等价于 [isAvailable]，仍逐语义查一次表，
     * 好让以后往 [tierOneRecipeOf] 里加语义时忘了写配方能立刻暴露出来。
     *
     * Composition 与振幅台阶之间怎么选是子通路的事，在 executor 线程上决定 ——
     * 那一步要发 `areAllPrimitivesSupported` 的 IPC，不能落在主线程。
     */
    override fun supports(semantic: HapticSemantic): Boolean =
        isAvailable() && recipes[semantic]?.steps?.isNotEmpty() == true

    /**
     * 发一次离散触感，档位强度乘在振幅与 primitive scale 上（轻 ×0.6 / 强 ×1.2）。
     * 返回 true 只表示「已派发」—— 效果对象的构造与 `vibrate` 都在
     * executor 线程上完成，本方法立刻返回。
     *
     * 只缩放离散配方，**不碰 [playEnvelope]**：包络振幅是调用方谱子定的（签名段 0.25
     * 的「笔压」是编排的一部分），档位只管交互反馈的轻重。
     *
     * @param view 本层不需要 View 通道（不走 `performHapticFeedback`），忽略。
     */
    override fun perform(view: View?, semantic: HapticSemantic, strength: HapticStrength): Boolean {
        if (!isAvailable()) return false
        val recipe = recipes[semantic] ?: return false
        if (recipe.steps.isEmpty()) return false
        val scaled = tierOneRecipeScaled(recipe, strength)
        return dispatch { target -> vibrateThrough(target, discreteEffectFor(target, scaled)) }
    }

    /**
     * 播一段连续振幅包络。本层是彩蛋两段包络在目标机上的实际承载者。
     *
     * 优先 API 36 的 `VibrationEffect.WaveformEnvelopeBuilder`（硬件插值，最平滑），
     * 条件不满足就退 `createWaveform` 的振幅台阶。**退台阶时仍返回 true**：
     * 台阶版就是设计文档给目标机定的实现（「彩蛋两段连续触感先用 tier 1 的 `createWaveform`
     * 振幅数组实现」），返 false 会让引擎误判成「本机播不了包络」而退成稀疏 tick，反而更差。
     *
     * 参数不合法一律返回 false，不抛异常：长度为 0、两个数组不等长、某个时长不是正数、
     * 振幅越界或是 NaN。
     */
    override fun playEnvelope(timingsMs: IntArray, amplitudes: FloatArray): Boolean {
        if (!isAvailable()) return false
        if (timingsMs.isEmpty() || timingsMs.size != amplitudes.size) return false
        if (timingsMs.any { it <= 0 }) return false
        if (amplitudes.any { it.isNaN() || it < 0f || it > 1f }) return false
        val timings = timingsMs.copyOf()
        val levels = amplitudes.copyOf()
        return dispatch { target ->
            vibrateThrough(target, envelopeEffectFor(target, timings, levels))
        }
    }

    /**
     * 释放：停掉可能还在播的长包络，再 shutdown 单线程 `Executor`（漏了就是线程泄漏）。
     *
     * 幂等。[released] 先置上再排 `cancel`，而 `dispatch` 排进去的任务开头也查 [released]，
     * 加上 executor 是 FIFO 单线程，所以 `release()` 返回之后不可能再有新的震动起播。
     *
     * `Vibrator.cancel()` 取消的是**本应用**当前的震动，不只是本层的 —— 引擎不要在别的
     * backend 正在播的时候单独 release 这一层。
     */
    override fun release() {
        if (released) return
        released = true
        val target = vibrator
        try {
            if (target != null) {
                executor.execute { runCatching { target.cancel() } }
            }
            executor.shutdown()
        } catch (_: Throwable) {
            // shutdown 不该抛；兜一层保证 release 自身不外泄异常
        }
    }

    /**
     * 停掉正在播的震动，但**不** shutdown executor —— 本层随后还要继续用。
     *
     * 存在的理由：`playEnvelope` 排出去的是一整段波形（彩蛋最长的一段 7300 ms），
     * `Vibrator.vibrate` 把它交给 `VibratorService` 后立刻返回，之后本层再没有任何
     * 抓手能把它叫停。用户按 ✕ 退出、按住暂停、或者息屏时，屏幕上什么都没了而手里
     * 还在震完剩下的几秒 —— 那不是"细腻"，是坏掉。
     *
     * 这个方法**刻意不放进 [HapticBackend] 契约**，与 `RichTapBackend.stop()` 同一形状：
     * 只有真能画连续波形的那两层需要停止通道，tier 2 的预置效果与 tier 0 的常量都是几十
     * 毫秒的一次性事件，给它们加 cancel 只会让契约多一个所有实现都空着的成员。
     * 引擎侧由 `HapticModule` 把这两个一起包进 `AppHaptics` 的 `quietDown`。
     *
     * `Vibrator.cancel()` 取消的是**本应用**当前的震动，不只是本层排出去的那一段 ——
     * 这正是要的：调进来的时候意思就是"现在全部安静"。
     *
     * 幂等，且 release 之后调是空操作。`runCatching` 兜住是因为它可能落在
     * `release()` 与本方法竞态的窗口里被 `RejectedExecutionException` 打到,
     * 而"停一下"失败不该把整层禁死 —— 所以这里不走 [dispatch]（那条路会置 [disabled]）。
     */
    fun cancel() {
        val target = vibrator ?: return
        if (released || disabled) return
        runCatching { executor.execute { runCatching { target.cancel() } } }
    }

    /**
     * 把一次播放投到单线程 `Executor` 上。
     *
     * 返回 true = 已排进队列。任务里再查一次 [released] 与 [disabled]：排队期间可能已经
     * release 或整层禁用了。任务抛出任何 `Throwable` 就把整层禁死，不重试 ——
     * `catch (Throwable)` 不是 `catch (Exception)`，`NoClassDefFoundError` 之类是 Error。
     *
     * `executor` 已 shutdown 时 `execute` 抛 `RejectedExecutionException`，这时返回 false
     * 让调用方降级，而不是把异常抛给点击回调。
     */
    private fun dispatch(block: (Vibrator) -> Unit): Boolean {
        val target = vibrator ?: return false
        if (released || disabled) return false
        return try {
            executor.execute {
                if (!released && !disabled) {
                    try {
                        block(target)
                    } catch (_: Throwable) {
                        disabled = true
                    }
                }
            }
            true
        } catch (_: Throwable) {
            false
        }
    }

    /**
     * 真正下发。只在 executor 线程上调。
     *
     * API 33 起用 `VibrationAttributes(USAGE_TOUCH)`；API 26..32 用等价的 `AudioAttributes`
     * 重载（该重载在 33 被废弃，故整个函数 `@Suppress("DEPRECATION")`）。
     * 两条都不带任何绕过用户设置的 flag。
     */
    @Suppress("DEPRECATION")
    private fun vibrateThrough(target: Vibrator, effect: VibrationEffect) {
        val attributes = touchAttributes
        if (attributes != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            target.vibrate(effect, attributes)
        } else {
            target.vibrate(effect, legacyAudioAttributes)
        }
    }

    /**
     * 离散语义选子通路：Composition 拼得出就拼，拼不出退振幅台阶。只在 executor 线程上调。
     */
    private fun discreteEffectFor(target: Vibrator, recipe: TierOneRecipe): VibrationEffect =
        composeIfSupported(target, recipe) ?: buildStepWaveform(recipe.steps)

    /**
     * 试着拼 `Composition`，拼不出返回 null。只在 executor 线程上调。
     *
     * 四道关，任何一道过不去就退振幅台阶：
     * 1. `SDK_INT >= R`：`startComposition` / `addPrimitive` / `areAllPrimitivesSupported`
     *    都是 API 30 才有。
     * 2. 配方里有 primitive 可拼（`FREQUENT_TICK` 之类也有，只是 scale 低）。
     * 3. 容量够：`compositionSizeMax` 是「至少能塞几个」，≤ 0 表示整条通路不可用（目标机是 0）。
     * 4. **整组**探测（硬规则 2）：先看 `capabilities.supportedPrimitives` 这张便宜的单项表挡掉
     *    大多数情况，再把该效果用到的全部 ID 一次传进 `areAllPrimitivesSupported(vararg)`。
     *    单项表全中也不等于整组可播，所以第二步不能省。
     */
    private fun composeIfSupported(target: Vibrator, recipe: TierOneRecipe): VibrationEffect? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return null
        val primitives = recipe.primitives
        if (primitives.isEmpty()) return null
        if (primitives.size > capabilities.compositionSizeMax) return null
        val ids = primitives.map { it.primitiveId }.distinct().sorted()
        if (!capabilities.supportedPrimitives.containsAll(ids)) return null
        if (!groupSupported(target, ids)) return null
        return try {
            val composition = VibrationEffect.startComposition()
            for (step in primitives) {
                // 第三个参数是「上一笔播完到这一笔起播之间的停顿」，首笔为 0
                composition.addPrimitive(step.primitiveId, step.scale, step.delayMs)
            }
            composition.compose()
        } catch (_: Throwable) {
            null
        }
    }

    /**
     * 整组探测的结果缓存（硬规则 2）。只在 executor 单线程上读写，所以普通 `HashMap` 就够。
     *
     * 13 个语义只用到 6 组不同的 primitive 组合，所以最多 6 次 IPC，之后全命中缓存。
     * 版本门控由唯一调用方 [composeIfSupported] 的早返做，这里用 `@RequiresApi` 声明前置条件，
     * 免得 lint 按「单个方法内没门控」误报，也免得后来人从别处直接调它。
     */
    @RequiresApi(Build.VERSION_CODES.R)
    private fun groupSupported(target: Vibrator, ids: List<Int>): Boolean =
        compositionGroupSupport.getOrPut(ids) {
            try {
                target.areAllPrimitivesSupported(*ids.toIntArray())
            } catch (_: Throwable) {
                false
            }
        }

    /**
     * 连续包络选子通路：API 36 的硬件插值包络优先，退不了才画振幅台阶。
     * 只在 executor 线程上调。
     */
    private fun envelopeEffectFor(
        target: Vibrator,
        timingsMs: IntArray,
        amplitudes: FloatArray,
    ): VibrationEffect = buildAospEnvelope(target, timingsMs, amplitudes)
        ?: buildStepWaveform(
            timingsMs.indices.map { WaveformStep(timingsMs[it], amplitudes[it]) },
        )

    /**
     * API 36 的 `VibrationEffect.WaveformEnvelopeBuilder`。拼不出返回 null。
     * 只在 executor 线程上调。
     *
     * 这条通路在目标机上不可用（`areEnvelopeEffectsSupported()` 为 false、
     * `mMaxEnvelopeEffectSize` 为 0、`frequencyProfile` 全 NaN），Pixel 上可用，所以留着。
     *
     * 三个静默失败点，都在这里挡掉：
     * 1. **设备不支持包络时没有自动回退。** 照发的结果是一点都不震，所以必须自己按
     *    `envelopeSupported` 跳过。
     * 2. **频率超出设备支持区间时整段不播，框架也不会替我们修正。**
     *    所以频率必须从 `getFrequencyProfile()` 现取现夹，取不到就整条放弃。
     * 3. **控制点时长低于设备下限时不保证能播。** 规范只承诺支持低到 20 ms，
     *    而我们的台阶按 `rampStepDurationMs = 5` 画，5 ms 大概率被拒 —— 这时退振幅台阶。
     *
     * 控制点数超 `getMaxSize()` 时框架会自己把效果拆开播，不算失败；但拆开会在拆点处
     * 插进一个接缝，把彩蛋那条连续的包络听成两段，所以这里也退振幅台阶，保住「一段」。
     */
    private fun buildAospEnvelope(
        target: Vibrator,
        timingsMs: IntArray,
        amplitudes: FloatArray,
    ): VibrationEffect? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.BAKLAVA) return null
        if (!capabilities.envelopeSupported) return null
        val limits = envelopeLimitsOf(target) ?: return null
        if (!limits.accepts(timingsMs)) return null
        return try {
            val builder = VibrationEffect.WaveformEnvelopeBuilder()
            // 起始频率与各控制点同频：本层只画强度包络，不做频率扫掠（目标机连频率区间都不暴露）
            builder.setInitialFrequencyHz(limits.frequencyHz)
            for (index in timingsMs.indices) {
                builder.addControlPoint(
                    amplitudes[index],
                    limits.frequencyHz,
                    timingsMs[index].toLong(),
                )
            }
            builder.build()
        } catch (_: Throwable) {
            null
        }
    }

    /**
     * 查一次 API 36 包络的设备限制并缓存。返回 null 表示这条通路在本机不可用。
     * 只在 executor 线程上调，所以两个缓存字段不需要 volatile。
     *
     * `getEnvelopeEffectInfo()` 的四个 getter 在不支持包络时一律返回 0，
     * 所以拿到的 `maxSize <= 0` 就当整条不可用。
     */
    private fun envelopeLimitsOf(target: Vibrator): EnvelopeLimits? {
        if (envelopeLimitsProbed) return envelopeLimits
        envelopeLimitsProbed = true
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.BAKLAVA) return null
        envelopeLimits = try {
            val frequencyHz = resolveEnvelopeFrequencyHz(target)
            val info = target.envelopeEffectInfo
            if (frequencyHz.isNaN() || info.maxSize <= 0) {
                null
            } else {
                EnvelopeLimits(
                    frequencyHz = frequencyHz,
                    maxSize = info.maxSize,
                    minControlPointMs = info.minControlPointDurationMillis,
                    maxControlPointMs = info.maxControlPointDurationMillis,
                    maxTotalMs = info.maxDurationMillis,
                )
            }
        } catch (_: Throwable) {
            null
        }
        return envelopeLimits
    }

    /**
     * 挑一个能播的频率，Hz。拿不到返回 `NaN`，调用方据此整条放弃。
     *
     * `getFrequencyProfile()` 是 API 36 且 `@Nullable`；`getResonantFrequency()` 是 API 34，
     * 被 36 这道门控涵盖。版本门控在唯一调用方 [envelopeLimitsOf] 的早返里，
     * 这里用 `@RequiresApi` 把前置条件写在签名上。
     *
     * 优先谐振频率：LRA 在谐振点输出最强，同一个振幅在那里最有力；查不到（返回 `NaN`）
     * 就退区间中点。最后一律夹进 `[minFrequencyHz, maxFrequencyHz]` ——
     * 超出区间的频率会让整段静默，而框架不会替我们修正。
     */
    @RequiresApi(Build.VERSION_CODES.BAKLAVA)
    private fun resolveEnvelopeFrequencyHz(target: Vibrator): Float {
        val profile = target.frequencyProfile ?: return Float.NaN
        val min = profile.minFrequencyHz
        val max = profile.maxFrequencyHz
        if (min.isNaN() || max.isNaN() || min <= 0f || max < min) return Float.NaN
        val resonant = target.resonantFrequency
        val picked = if (resonant.isNaN() || resonant <= 0f) (min + max) / 2f else resonant
        return picked.coerceIn(min, max)
    }

    /**
     * 把振幅台阶拼成 `createWaveform` 效果（API 26，minSdk 就有，无需门控）。
     *
     * `timings[i]` 是这一台阶持续多久，`amplitudes[i]` 是这一台阶保持的振幅；HAL 会在相邻台阶
     * 之间自己爬坡（目标机 `rampStepDurationMs = 5`，所以台阶按 5 ms 的整数倍画才不浪费）。
     * 振幅整数范围 0..255，0 表示马达不转，正好用来表达配方里的静默间隙。
     *
     * `repeat = -1` 表示不循环 —— 循环效果得靠 `Vibrator.cancel()` 才停，触感层一次都不用。
     */
    private fun buildStepWaveform(steps: List<WaveformStep>): VibrationEffect {
        val timings = LongArray(steps.size) { steps[it].durationMs.toLong() }
        val amplitudes = IntArray(steps.size) { toWaveformAmplitude(steps[it].amplitude) }
        return VibrationEffect.createWaveform(timings, amplitudes, REPEAT_NONE)
    }
}

/**
 * API 36 包络在本机的限制。四个数值都取自 `VibratorEnvelopeEffectInfo`，
 * 不支持包络时那些 getter 一律返回 0，所以「≤ 0」在这里的含义统一是「未知 / 不设限」。
 */
private class EnvelopeLimits(
    /** 控制点统一用的频率，Hz。已夹进设备支持区间 —— 超出区间会让整段静默不震。 */
    val frequencyHz: Float,
    /** 最多几个控制点。规范保证支持包络的设备至少 16 个。 */
    val maxSize: Int,
    /** 单个控制点最短毫秒。规范只保证能低到 20 ms，比它短就别送了。 */
    val minControlPointMs: Long,
    /** 单个控制点最长毫秒。规范只保证能高到 1 秒。 */
    val maxControlPointMs: Long,
    /** 整段最长毫秒。 */
    val maxTotalMs: Long,
) {
    /** 这串时长能不能原样交给 `WaveformEnvelopeBuilder`。任何一项越界就该退振幅台阶。 */
    fun accepts(timingsMs: IntArray): Boolean {
        if (maxSize > 0 && timingsMs.size > maxSize) return false
        var totalMs = 0L
        for (durationMs in timingsMs) {
            if (minControlPointMs > 0 && durationMs < minControlPointMs) return false
            if (maxControlPointMs > 0 && durationMs > maxControlPointMs) return false
            totalMs += durationMs
        }
        return maxTotalMs <= 0 || totalMs <= maxTotalMs
    }
}

/**
 * Composition 子通路的一步：primitive ID + scale + 起播前的停顿。
 *
 * @param primitiveId `VibrationEffect.Composition.PRIMITIVE_*`。这些常量是 Java 的
 *   `static final int`，编译期就被折成字面量，所以在低版本机上读它们不会抛
 *   `NoSuchFieldError`；真正需要版本门控的是 `startComposition` 那几个方法。
 * @param scale 0f..1f，直接给 `addPrimitive`。
 * @param delayMs 上一笔播完到这一笔起播之间的停顿；首笔恒为 0。
 */
internal data class PrimitiveStep(val primitiveId: Int, val scale: Float, val delayMs: Int)

/**
 * `createWaveform` 子通路的一个等幅台阶。
 *
 * @param durationMs 这一台阶持续多久，必须为正（0 会被 `createWaveform` 直接忽略）。
 * @param amplitude 0f..1f。**0f 表示静默间隙**，会被 [toWaveformAmplitude] 映成整数 0（马达不转），
 *   不是映成 1。
 */
internal data class WaveformStep(val durationMs: Int, val amplitude: Float)

/**
 * tier 1 一条语义的配方：同一个手感目标的两种写法。
 *
 * @param primitives Composition 子通路的表达；为空表示这条语义不用 Composition 拼。
 * @param steps `createWaveform` 子通路的表达；13 条语义每条都非空，这是本层的兜底。
 */
internal data class TierOneRecipe(
    val primitives: List<PrimitiveStep>,
    val steps: List<WaveformStep>,
)

/**
 * 语义 → tier 1 配方。**纯函数**：不碰 `Context`、不碰反射、不查设备能力，
 * 所以是本层唯一能在 `src/test` 里裸跑的部分。
 *
 * primitive 一列逐条照设计文档「语义词表」的 tier 1 列抄，未标 scale 的按满幅算：
 * `tap`→CLICK、`lightTap`→TICK、`segmentTick`→LOW_TICK、`frequentTick`→LOW_TICK 低 scale、
 * `toggleOn`→CLICK 0.7、`toggleOff`→TICK 0.5、`confirm`→CLICK+THUD、`reject`→THUD 两连、
 * `dragStart`→THUD、`thresholdArmed`→QUICK_RISE、`gestureEnd`→THUD 0.5、
 * `scrollEdge`→LOW_TICK 0.4、`popupShow`→TICK。
 *
 * 振幅台阶一列是同一手感目标的等效写法，各 primitive 的形状与取值理由写在
 * [clickSteps]、[tickSteps]、[lowTickSteps]、[thudSteps]、[quickRiseSteps] 各自的注释里。
 *
 * `when` 刻意穷举、不写 `else`：往 [HapticSemantic] 里加语义时编译器会逼着补上配方。
 * 设计文档 tier 1 列没用到 `PRIMITIVE_SLOW_RISE` 与 `PRIMITIVE_QUICK_FALL`
 * （它们只出现在彩蛋的连续包络里，那条走 `playEnvelope`），所以这里不为它们造形状。
 */
internal fun tierOneRecipeOf(semantic: HapticSemantic): TierOneRecipe = when (semantic) {
    // 通用点击顶档，满幅 CLICK
    HapticSemantic.TAP -> clickRecipe(FULL_SCALE)
    // 次级操作，TICK 满幅：比 CLICK 短一半也轻一半，一屏里反复出现也不吵
    HapticSemantic.LIGHT_TAP -> tickRecipe(FULL_SCALE)
    // 卡进格子的刻度感，LOW_TICK 满幅
    HapticSemantic.SEGMENT_TICK -> lowTickRecipe(FULL_SCALE)
    // 一次手势里连发几十次，取整条梯度最低的 scale
    HapticSemantic.FREQUENT_TICK -> lowTickRecipe(SCALE_FREQUENT_TICK)
    // 开关立起来，CLICK 收一点力：满幅是 TAP 的位置，开关不该和主操作一样重
    HapticSemantic.TOGGLE_ON -> clickRecipe(SCALE_TOGGLE_ON)
    // 关比开轻，落回去用半幅 TICK
    HapticSemantic.TOGGLE_OFF -> tickRecipe(SCALE_TOGGLE_OFF)
    // 成功：先一记脆的 CLICK 报「收到」，再一记 THUD 落地
    HapticSemantic.CONFIRM -> confirmRecipe()
    // 失败：两记 THUD，闷、明确，但不脆到像报错惊吓
    HapticSemantic.REJECT -> rejectRecipe()
    // 抓住了，满幅 THUD 的沉
    HapticSemantic.DRAG_START -> thudRecipe(FULL_SCALE)
    // 可以松手了，QUICK_RISE 的上冲
    HapticSemantic.THRESHOLD_ARMED -> quickRiseRecipe(FULL_SCALE)
    // 落到位，半幅 THUD：要「到位」的实感，又不能像拖拽起手那么沉
    HapticSemantic.GESTURE_END -> thudRecipe(SCALE_GESTURE_END)
    // 撞墙，会被连着顶，比 SEGMENT_TICK 明显轻
    HapticSemantic.SCROLL_EDGE -> lowTickRecipe(SCALE_SCROLL_EDGE)
    // 面板落出来，TICK 半幅：梯度要求 POPUP_SHOW 比 LIGHT_TAP 轻（语义词表的轻重次序），
    // 与 tier 3 的 RichTapStrength.LIGHT（128/255 ≈ 0.5）对齐。原先满幅 TICK 把两个语义
    // 在这层压成同一个手感
    HapticSemantic.POPUP_SHOW -> tickRecipe(SCALE_POPUP_SHOW)
}

/**
 * 档位强度对 tier 1 配方的缩放：轻 ×0.6、强 ×1.2，[HapticStrength.SYSTEM] 原样返回。
 * 纯函数，可单测。
 *
 * 振幅台阶与 primitive 的 scale 同乘一个系数（primitive scale 夹回 0..1——
 * `addPrimitive` 越界会抛）。效果形状不动：轻/强是同一个配方更轻/更重，
 * 不是换配方（换配方 = 换性格，2026-09-09 裁定废止语义上移后落在这里的对应规则）。
 */
internal fun tierOneRecipeScaled(recipe: TierOneRecipe, strength: HapticStrength): TierOneRecipe {
    val factor = when (strength) {
        HapticStrength.SYSTEM -> return recipe
        HapticStrength.LIGHT -> TIER_ONE_LIGHT_FACTOR
        HapticStrength.STRONG -> TIER_ONE_STRONG_FACTOR
    }
    return TierOneRecipe(
        primitives = recipe.primitives.map {
            PrimitiveStep(it.primitiveId, (it.scale * factor).coerceIn(0f, 1f), it.delayMs)
        },
        steps = recipe.steps.map {
            WaveformStep(it.durationMs, (it.amplitude * factor).coerceIn(0f, 1f))
        },
    )
}

/** 单 primitive 配方：Composition 一笔，振幅台阶一段。 */
private fun singleRecipe(primitiveId: Int, scale: Float, steps: List<WaveformStep>) =
    TierOneRecipe(
        primitives = listOf(PrimitiveStep(primitiveId, scale, NO_DELAY_MS)),
        steps = steps,
    )

private fun clickRecipe(scale: Float) =
    singleRecipe(VibrationEffect.Composition.PRIMITIVE_CLICK, scale, clickSteps(scale))

private fun tickRecipe(scale: Float) =
    singleRecipe(VibrationEffect.Composition.PRIMITIVE_TICK, scale, tickSteps(scale))

private fun lowTickRecipe(scale: Float) =
    singleRecipe(VibrationEffect.Composition.PRIMITIVE_LOW_TICK, scale, lowTickSteps(scale))

private fun thudRecipe(scale: Float) =
    singleRecipe(VibrationEffect.Composition.PRIMITIVE_THUD, scale, thudSteps(scale))

private fun quickRiseRecipe(scale: Float) =
    singleRecipe(VibrationEffect.Composition.PRIMITIVE_QUICK_RISE, scale, quickRiseSteps(scale))

/** `confirm`：CLICK + THUD，中间隔 [CONFIRM_GAP_MS]。 */
private fun confirmRecipe() = TierOneRecipe(
    primitives = listOf(
        PrimitiveStep(VibrationEffect.Composition.PRIMITIVE_CLICK, FULL_SCALE, NO_DELAY_MS),
        PrimitiveStep(VibrationEffect.Composition.PRIMITIVE_THUD, FULL_SCALE, CONFIRM_GAP_MS),
    ),
    steps = clickSteps(FULL_SCALE) + silence(CONFIRM_GAP_MS) + thudSteps(FULL_SCALE),
)

/** `reject`：THUD 两连，中间隔 [REJECT_GAP_MS]。 */
private fun rejectRecipe() = TierOneRecipe(
    primitives = listOf(
        PrimitiveStep(VibrationEffect.Composition.PRIMITIVE_THUD, FULL_SCALE, NO_DELAY_MS),
        PrimitiveStep(VibrationEffect.Composition.PRIMITIVE_THUD, FULL_SCALE, REJECT_GAP_MS),
    ),
    steps = thudSteps(FULL_SCALE) + silence(REJECT_GAP_MS) + thudSteps(FULL_SCALE),
)

/** 一段静默间隙。振幅 0f 会被 [toWaveformAmplitude] 映成整数 0，马达真的不转。 */
private fun silence(durationMs: Int) = listOf(WaveformStep(durationMs, 0f))

// 下面五个形状是我们对 primitive 手感的复刻：设计文档只给了 primitive 名与 scale，
// 没给波形（也给不了 —— primitive 的实际波形在各家 HAL 里）。台阶长度一律取目标机实测的
// `rampStepDurationMs = 5` 的整数倍：那是 HAL 的爬坡粒度，比它更短的台阶只会被爬坡吃掉。
// 各段振幅写成「占标称峰值的比例」，由 [step] 乘上 [NOMINAL_PEAK] 与语义 scale 落成绝对值。

/**
 * 一段等幅台阶。
 *
 * @param ofPeak 占标称峰值 [NOMINAL_PEAK] 的比例，0f..1f。0f 表示静默间隙。
 * @param scale 语义 scale，来自设计文档「语义词表」tier 1 列。
 */
private fun step(durationMs: Int, ofPeak: Float, scale: Float) =
    WaveformStep(durationMs, ofPeak * NOMINAL_PEAK * scale)

/**
 * `PRIMITIVE_CLICK` 等效：快起、满幅、快收，总 20 ms。
 *
 * - 5 ms @ 0.60 起振。从 0 直接跳到峰值会削出「啪」的杂音，先垫一步。
 * - 10 ms @ 1.00 主体。CLICK 的性格是短促实心，主体再长就变成「嗡」。
 * - 5 ms @ 0.30 快收，掐掉 LRA 的余振尾巴。
 */
private fun clickSteps(scale: Float) = listOf(
    step(RAMP_STEP_MS, 0.60f, scale),
    step(RAMP_STEP_MS * 2, 1.00f, scale),
    step(RAMP_STEP_MS, 0.30f, scale),
)

/**
 * `PRIMITIVE_TICK` 等效：总 10 ms，只有 CLICK 的一半长、不到一半重。
 *
 * - 5 ms @ 0.45 主体。TICK 要的是「碰一下」，不追求实心。
 * - 5 ms @ 0.18 收尾，一步就够。
 */
private fun tickSteps(scale: Float) = listOf(
    step(RAMP_STEP_MS, 0.45f, scale),
    step(RAMP_STEP_MS, 0.18f, scale),
)

/**
 * `PRIMITIVE_LOW_TICK` 等效：和 TICK 同长，再轻一档。
 *
 * LOW_TICK 在 HAL 里是更低频、更闷的刻度感。没有频率通道可用时（目标机连频率区间都不暴露），
 * 只能靠更低的振幅去逼近「更沉更小」的观感。
 */
private fun lowTickSteps(scale: Float) = listOf(
    step(RAMP_STEP_MS, 0.26f, scale),
    step(RAMP_STEP_MS, 0.10f, scale),
)

/**
 * `PRIMITIVE_THUD` 等效：慢起、长尾，总 60 ms。THUD 的性格是闷，不是脆。
 *
 * - 10 ms @ 0.45 慢起，两个爬坡步长，起手就比 CLICK 钝。
 * - 15 ms @ 0.85 主体。刻意不到峰值 —— 顶到峰值会把它听成一记重 CLICK。
 * - 25 ms @ 0.35 长尾，「落地」的余韵全在这一段。
 * - 10 ms @ 0.12 收，尾巴自然消掉而不是被掐断。
 */
private fun thudSteps(scale: Float) = listOf(
    step(RAMP_STEP_MS * 2, 0.45f, scale),
    step(RAMP_STEP_MS * 3, 0.85f, scale),
    step(RAMP_STEP_MS * 5, 0.35f, scale),
    step(RAMP_STEP_MS * 2, 0.12f, scale),
)

/**
 * `PRIMITIVE_QUICK_RISE` 等效：[QUICK_RISE_STEPS] 个爬坡步线性升到峰值，总 80 ms。
 *
 * 用途是下拉刷新／侧滑到阈值那记「可以松手了」，要的是上冲感，所以只升不落 ——
 * 收尾那一下由调用方随后的动作（松手回弹）自己给。
 * 起点不取 0：0 之后紧跟一个极小值等于白等一个台阶，直接从能感知的 [QUICK_RISE_FROM] 起。
 */
private fun quickRiseSteps(scale: Float) = List(QUICK_RISE_STEPS) { index ->
    val progress = index.toFloat() / (QUICK_RISE_STEPS - 1)
    step(RAMP_STEP_MS, QUICK_RISE_FROM + (1f - QUICK_RISE_FROM) * progress, scale)
}

/**
 * 0f..1f 的相对振幅换算成 `createWaveform` 要的 0..255 整数。**纯函数。**
 *
 * 边界是这里唯一容易写错的地方：整数 **0 表示马达不转**，所以
 * - `0f`（以及任何非正值）必须映成 `0`，用来表达配方里的静默间隙；
 * - 任何大于 `0f` 的值至少映成 `1` —— 四舍五入把 0.001f 压成 0 就等于把一次「要震」
 *   悄悄吞掉，属静默失败。
 */
internal fun toWaveformAmplitude(fraction: Float): Int {
    if (fraction.isNaN() || fraction <= 0f) return MIN_OFF_AMPLITUDE
    val scaled = (fraction.coerceAtMost(1f) * MAX_AMPLITUDE).roundToInt()
    return scaled.coerceIn(MIN_ON_AMPLITUDE, MAX_AMPLITUDE)
}

/**
 * 取 `Vibrator`：API 31 起走 `VibratorManager.getDefaultVibrator()`，
 * 以下走已废弃的 `getSystemService(VIBRATOR_SERVICE)`。
 *
 * 与 `HapticCapabilities` 里那份是同一套逻辑，但那边的是 private，跨文件复用不了；
 * 两处都改的时候记得一起改。两条路都可能返回 null 或抛（个别 ROM 上服务缺失），
 * 拿不到就当本层不可用 —— 不持有 [context] 引用。
 */
@Suppress("DEPRECATION")
private fun resolveVibrator(context: Context): Vibrator? = try {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        val manager = context.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as? VibratorManager
        manager?.defaultVibrator
    } else {
        context.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
    }
} catch (_: Throwable) {
    null
}

/** 本层在四层引擎里的层号：1 = 振幅波形。见设计文档「四层引擎」。 */
private const val TIER = 1

/** 单线程 `Executor` 的线程名，带得出来源，便于在 trace / ANR 里认人。 */
private const val THREAD_NAME = "haptic-aosp-waveform"

/**
 * `createWaveform` 的 `repeat` 参数：-1 = 不循环。
 * 循环效果只能靠 `Vibrator.cancel()` 停，触感层一次都用不上。
 */
private const val REPEAT_NONE = -1

/**
 * `createWaveform` 振幅的整数上限。
 *
 * AOSP 里这个值叫 `VibrationEffect.MAX_AMPLITUDE`，但它是 `@hide`，公开 SDK 里没有
 * （android-37 的 android.jar 里 `VibrationEffect` 只暴露 `DEFAULT_AMPLITUDE` 与四个
 * `EFFECT_*`），所以只能自己写一份。取值出处是 `createWaveform` 的文档：
 * 「Amplitude values must be between 0 and 255」。
 */
private const val MAX_AMPLITUDE = 255

/** 静默：整数 0 表示马达不转。 */
private const val MIN_OFF_AMPLITUDE = 0

/** 要震就至少震这么点：不允许把一个正振幅四舍五入成 0。 */
private const val MIN_ON_AMPLITUDE = 1

/** 满幅，语义词表里没标 scale 的都按这个算。 */
private const val FULL_SCALE = 1f

/** 轻/强档对 tier 1 振幅与 primitive scale 的缩放系数，见 [tierOneRecipeScaled]。 */
private const val TIER_ONE_LIGHT_FACTOR = 0.6f

/** 同上，强档。台阶振幅以 [NOMINAL_PEAK] 为天花板，×1.2 仍在其下。 */
private const val TIER_ONE_STRONG_FACTOR = 1.2f

/**
 * 我们画振幅台阶时的标称峰值，占硬件最大振幅（255）的比例。
 *
 * 210 / 255 ≈ 0.82，210 是目标机实测的 `defaultVibrationAmplitude`
 * （见设计文档「目标机实测」）—— 也就是这台 ROM 自己认为「一次正常强度的震动」该多重。
 * 一次普通按钮点击顶到硬件最大值，会比这台机上所有系统级点击都硬，用户读作「这 App 震太狠」。
 * 所以本层的「满幅」以 ROM 的默认值为天花板，13 个语义之间的轻重差靠 scale 与形状体现。
 *
 * 只作用在 `createWaveform` 的台阶上：Composition 那条子通路把 scale 原样交给厂商波形，
 * 绝对强度由厂商决定；[playEnvelope] 的入参也不受这里影响，调用方给多少就是多少。
 *
 * **这是唯一一个必须上真机复核的数值。** 手感只能听，改它一处即可整层生效。
 */
private const val NOMINAL_PEAK = 210f / 255f

/** Composition 首笔的停顿：没有上一笔可等。 */
private const val NO_DELAY_MS = 0

/**
 * 台阶长度的基本单位，毫秒。取目标机实测的 `rampStepDurationMs = 5`
 * （见设计文档「目标机实测」）—— 那是 HAL 在相邻台阶之间爬坡用的粒度，
 * 比它更短的台阶会被爬坡吃掉。
 */
private const val RAMP_STEP_MS = 5

/** `toggleOn` = `PRIMITIVE_CLICK` scale 0.7，设计文档「语义词表」tier 1 列。 */
private const val SCALE_TOGGLE_ON = 0.7f

/** `toggleOff` = `PRIMITIVE_TICK` scale 0.5，同上。 */
private const val SCALE_TOGGLE_OFF = 0.5f

/**
 * `popupShow` = `PRIMITIVE_TICK` scale 0.5，与 tier 3 的 128/255 对齐：
 * 梯度要求它比 LIGHT_TAP（满幅 TICK）轻一档，又不轻到「面板出现没有存在感」。
 */
private const val SCALE_POPUP_SHOW = 0.5f

/** `gestureEnd` = `PRIMITIVE_THUD` scale 0.5，同上。 */
private const val SCALE_GESTURE_END = 0.5f

/** `scrollEdge` = `PRIMITIVE_LOW_TICK` scale 0.4，同上。 */
private const val SCALE_SCROLL_EDGE = 0.4f

/**
 * `frequentTick` 的 scale。设计文档只写了「`PRIMITIVE_LOW_TICK` 低 scale」没给数，取 0.35。
 *
 * 定得比 [SCALE_SCROLL_EDGE] 还低是有意的：`HapticSemantic` 把 `FREQUENT_TICK` 钉成整条
 * 梯度最轻的一档，而它一次拖动里要连发几十次 —— 单次稍微重一点，整段手势就会累成惩罚。
 */
private const val SCALE_FREQUENT_TICK = 0.35f

/**
 * `confirm` 里 CLICK 与 THUD 之间的停顿，毫秒。
 *
 * 40 ms 是「听得出先后又连成一句」的窗口：再短两笔糊成一记重击，再长就散成两件事。
 */
private const val CONFIRM_GAP_MS = 40

/**
 * `reject` 里两记 THUD 之间的停顿，毫秒。
 *
 * 比 [CONFIRM_GAP_MS] 长，读起来是两下明确的「不行」，而不是一次成功反馈。
 */
private const val REJECT_GAP_MS = 70

/** `quickRiseSteps` 的台阶数：16 × [RAMP_STEP_MS] = 80 ms 的上冲。 */
private const val QUICK_RISE_STEPS = 16

/** `quickRiseSteps` 的起始振幅。低到刚能感知即可，但不取 0（0 那一步等于白等）。 */
private const val QUICK_RISE_FROM = 0.12f
