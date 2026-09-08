package com.tracktosearch.di

import android.content.Context
import com.tracktosearch.data.local.HapticStorage
import com.tracktosearch.ui.haptic.AppHaptics
import com.tracktosearch.ui.haptic.HapticCapabilities
import com.tracktosearch.ui.haptic.backend.AospConstantsBackend
import com.tracktosearch.ui.haptic.backend.AospWaveformBackend
import com.tracktosearch.ui.haptic.backend.HapticPlayerBackend
import com.tracktosearch.ui.haptic.backend.MiuiBackend
import com.tracktosearch.ui.haptic.backend.OplusBackend
import com.tracktosearch.ui.haptic.backend.RichTapBackend
import com.tracktosearch.ui.haptic.systemHapticFeedbackEnabled
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

/**
 * 触感四层引擎的接线：能力探测、六个 backend、语义门面 [AppHaptics]。
 *
 * ### 第一次取 [AppHaptics] 必须在非主线程
 *
 * [provideHapticCapabilities] 里的 `HapticCapabilities.probe` 是阻塞调用
 * （`getSystemService`、到 `VibratorService` 的 IPC、四次类查找与反射），
 * 六个 backend 的构造还要再取一次 `Vibrator`。整条依赖在 Hilt 里是懒的：
 * 谁第一次解析 [AppHaptics]，这些成本就落在谁的线程上。
 *
 * 所以调用侧一律注入 `Provider<AppHaptics>`（或 `Lazy<AppHaptics>`），
 * 并在启动时于后台线程 `get()` 一次预热 —— 与 `TraktSearchApp` 里给 `AppDatabase`、
 * `DoubanAuthStorage` 做的预热同一个套路。直接写
 * `@Inject lateinit var appHaptics: AppHaptics` 会把整套探测同步跑在主线程的注入点上。
 *
 * 本模块刻意不在 provider 里包一层 `lazy`：那只是把阻塞从「启动时的后台线程」挪到
 * 「用户第一次点按钮的主线程」，更糟。
 *
 * ### 为什么 backend 按具体类型 provide
 *
 * 一是 [provideAppHaptics] 要按固定顺序装链：`Set` 多绑定是无序的，而同 tier 的先后由
 * 列表顺序决定。二是 [AppHaptics] 的静音钩子要拿到 `RichTapBackend.stop()` 与
 * `HapticPlayerBackend.stop()`，那是它们在 `HapticBackend` 契约之外多出来的成员，
 * 按接口类型注入就拿不到。
 *
 * ### 六层都得是单例，RichTap 尤其
 *
 * `RichTapUtils` 是进程级单例，`quit()` 还会把它的静态 `sInstance` 置空 ——
 * 两个 `RichTapBackend` 实例会互相把对方拆掉。其余五层的理由平常些：各自持着一条单线程
 * `Executor` 与一份探测缓存，多建一个就是多一条线程加一次重复探测。
 *
 * ### 两件不在本模块职责内、但缺了就不对的事
 *
 * 1. `HapticStorage.preloadAndGetValue()` 要在启动时调一次（T2 的活）。
 *    在那之前 `modeState` 是「跟随系统」，用户选的「关闭」还没生效 —— 这段窗口里点一下照样震。
 * 2. 没有人调 [AppHaptics] 的 `release()`。进程级单例活到进程结束，
 *    那几条单线程 `Executor` 也随进程回收，所以现在不调是对的；
 *    哪天把触感收进更窄的作用域，记得补上。
 */
@Module
@InstallIn(SingletonComponent::class)
object HapticModule {

    /**
     * 设备能力快照，整个进程探一次。
     *
     * **阻塞。** 见类注释：第一次解析本绑定的线程会承担全部探测成本，所以调用侧必须
     * 通过 `Provider` 在后台线程预热。`probe` 内部逐项 `catch (Throwable)`，
     * 任何一项失败只让该字段退成保守值，不会抛。
     */
    @Provides
    @Singleton
    fun provideHapticCapabilities(@ApplicationContext context: Context): HapticCapabilities =
        HapticCapabilities.probe(context)

    /** tier 3：RichTap 波形（vendored SDK 通路） */
    @Provides
    @Singleton
    fun provideRichTapBackend(
        @ApplicationContext context: Context,
        capabilities: HapticCapabilities,
    ): RichTapBackend = RichTapBackend(context, capabilities)

    /**
     * tier 3：MiHaptic / IEEE 2861.3 的 HE 波形（framework 反射通路）。
     *
     * 与 [provideRichTapBackend] 同 tier 不同通路：小米 14 Pro 上 RichTap 的包络
     * 通路不存在（ROM 没有 `createEnvelope`），HE 是它唯一画得出连续包络的层；
     * OPPO 这类 RichTap 真包络可用的机器上，包络链先问到 RichTap，轮不到这层。
     * 装配顺序里排在 RichTap 之后即这个次序。不需要 `Context`。
     */
    @Provides
    @Singleton
    fun provideHapticPlayerBackend(capabilities: HapticCapabilities): HapticPlayerBackend =
        HapticPlayerBackend(capabilities)

    /** tier 2：MIUI 与 HyperOS 的语义效果，全程反射 */
    @Provides
    @Singleton
    fun provideMiuiBackend(
        @ApplicationContext context: Context,
        capabilities: HapticCapabilities,
    ): MiuiBackend = MiuiBackend(context, capabilities)

    /** tier 2：ColorOS（OPPO、一加、realme）的语义效果，全程反射 */
    @Provides
    @Singleton
    fun provideOplusBackend(
        @ApplicationContext context: Context,
        capabilities: HapticCapabilities,
    ): OplusBackend = OplusBackend(context, capabilities)

    /** tier 1：AOSP 振幅通路，Composition 拼得出就拼，拼不出画振幅台阶 */
    @Provides
    @Singleton
    fun provideAospWaveformBackend(
        @ApplicationContext context: Context,
        capabilities: HapticCapabilities,
    ): AospWaveformBackend = AospWaveformBackend(context, capabilities)

    /**
     * tier 0：`HapticFeedbackConstants`，降级链的地板。
     *
     * 不吃 `HapticCapabilities` —— 这一层没有要探的能力，构造完就能用。
     */
    @Provides
    @Singleton
    fun provideAospConstantsBackend(): AospConstantsBackend = AospConstantsBackend()

    /**
     * 语义门面。构造本身廉价，贵的是上面那几个绑定（见类注释的预热要求）。
     *
     * `quietDown` 传的是三层波形的停止，**不是**设计文档写的
     * `RichTapUtils.switchHaptic(false)`：反编译 `richtap_sdk_lite.aar` 确认
     * `switchHaptic(boolean)` 与 `swapLR(boolean)` 落到同一个静态方法，
     * 写的是 SharedPreferences 文件 `swap_left_right` 的同名键，日志串也还是
     * 「swapLR null==context」—— 它是 SDK 里一处复制粘贴的错，对触感毫无作用，
     * 还会顺手覆盖左右声道交换的设置，而且是 `commit()` 同步写盘。
     * 真正能停的是各层自己的 stop / cancel。
     *
     * 三个停止一起调、不短路：`RichTapBackend.stop()` 在从未 init 成功的机型上直接返回；
     * `HapticPlayerBackend.stop()` 在从未播过 HE 的机型上同样直接返回；
     * 而那两种机器上真正在播的可能是 tier 1 那段波形，得靠
     * `AospWaveformBackend.cancel()` 才停得住。
     *
     * 三个 `stop` / `cancel` 都不在 [HapticBackend] 契约里，是刻意的：只有真能画连续
     * 波形的 tier 3（两通路）与 tier 1 需要停止通道，tier 2 的预置效果与 tier 0 的常量
     * 都是几十毫秒的一次性事件，给它们加成员只会让契约多一个所有实现都空着的方法。
     * **把三层的停止拼在一起是引擎侧的活，就拼在这里** —— 调用方只认识
     * `AppHaptics.stopOngoing()`。
     */
    @Provides
    @Singleton
    fun provideAppHaptics(
        @ApplicationContext context: Context,
        capabilities: HapticCapabilities,
        richTap: RichTapBackend,
        hapticPlayer: HapticPlayerBackend,
        miui: MiuiBackend,
        oplus: OplusBackend,
        waveform: AospWaveformBackend,
        constants: AospConstantsBackend,
        hapticStorage: HapticStorage,
    ): AppHaptics = AppHaptics(
        capabilities = capabilities,
        // 这个顺序只决定同 tier 之间的先后（两个 tier 3：RichTap 在前、HE 在后，
        // RichTap 真包络可用的机器先问它；两个 tier 2 不会在同一台机上同时可用）。
        // 跨 tier 的优先级由 AppHaptics 自己排，见那边的 discreteRank。
        backends = listOf(richTap, hapticPlayer, miui, oplus, waveform, constants),
        modeState = hapticStorage.modeState,
        systemHapticEnabled = { systemHapticFeedbackEnabled(context) },
        // 包成 lambda 而不是写 richTap::stop：stop() 返回 Boolean，
        // 而这里要的是 () -> Unit，lambda 才会把返回值丢掉。
        // 三层都调、不短路：哪层在播停哪层，没在播的那层自己会直接返回
        quietDown = {
            richTap.stop()
            hapticPlayer.stop()
            waveform.cancel()
        },
    )
}
