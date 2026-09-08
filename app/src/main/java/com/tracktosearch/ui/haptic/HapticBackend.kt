package com.tracktosearch.ui.haptic

import android.view.View

/**
 * 一层触感通路的统一契约。
 *
 * 引擎持有一组后端，排成**两条**次序不同的降级链（规则见 [tier]：离散那条不是单纯的
 * tier 降序），对一个语义沿链逐个尝试：先看 [isAvailable] 与 [supports]，再调 [perform]，
 * 谁先返回 true 就停在谁那里；全部返回 false 就是本机发不出这个语义，静默放过，不抛异常。
 * 实现分布在四层上：RichTap / HapticPlayer 两条波形通路、厂商语义效果（MIUI 与 OPlus）、
 * AOSP 振幅波形、AOSP 常量。
 *
 * 所有实现共享的红线：
 *
 * 1. 转子马达（`HapticCapabilities.lockedToConstants` 为 true，即 `hasAmplitudeControl()`
 *    为 false）只允许 tier 0 上场，tier 1 以上的 [isAvailable] 必须返回 false。
 *    没有振幅控制时所有非零振幅都会被抬到 100%，"细腻"全部失真成一声嗡。
 * 2. 用 `VibrationEffect.Composition` 的实现必须**整组**探测：把一个效果用到的全部
 *    primitive 一次传进 `areAllPrimitivesSupported(vararg)`，缺一个就降级整个效果。
 *    任一 primitive 不支持时整段 Composition 一点都不震，属静默失败。
 * 3. tier 2 不得按 ROM 版本号硬编码可用 ID 范围，必须逐 ID 探测并缓存结果。
 * 4. 反射与厂商 IPC 一律丢到**单线程** `Executor`：反射加上到 `VibratorService` 的 IPC
 *    会阻塞主线程；单线程是硬要求，彩蛋编排依赖事件顺序。
 * 5. 厂商通路整层 `catch (Throwable)`（不是 `catch (Exception)`，`NoClassDefFoundError`
 *    是 Error 不是 Exception），首次调用失败后整层禁用，不要每次重试。
 * 6. 禁止 `HapticFeedbackConstants.FLAG_IGNORE_GLOBAL_SETTING`，不覆盖用户的系统触感设置。
 * 7. 高于 minSdk 26 的每个常量与方法都要 `Build.VERSION.SDK_INT` 门控，低版本走退化目标。
 * 8. 触感层不发网络请求、不做日志上报。
 *
 * 线程约定：[isAvailable] 与 [supports] 会落在每次点击的主线程路径上，必须是纯查表；
 * 真正的能力探测只在构造期或首次 [isAvailable] 里做一次并缓存。
 * 三态开关（[HapticMode]）与系统总开关的判定在引擎侧，后端不重复判断，但也不得绕过。
 */
interface HapticBackend {
    /**
     * 3 = 波形层（RichTap SDK 与 MiHaptic HE 两条通路）；2 = 厂商语义效果；
     * 1 = 振幅波形；0 = AOSP 常量。
     *
     * 数字越大表达力越强：tier 3 是唯一能画自定义波形与连续包络的一层（RichTap 走
     * vendored SDK 的四点包络，HapticPlayer 走 framework 的 HE JSON —— 两条通路不同、
     * 能力同级，哪条可用取决于 ROM：OPPO 类机器 RichTap 真包络在，小米 14 Pro 上
     * 则只有 HE）；tier 2 是厂商自己调过的预置效果，手感好但只能选不能画；
     * tier 1 能自己拼振幅数组；tier 0 由 ROM 把常量映射到线性马达，
     * 是降级链的最后一站，在 minSdk 26 上必须能独立工作。
     *
     * ### 引擎的两条链，只有包络那条是 tier 降序
     *
     * 1. **离散语义**（[perform]）按 `AppHaptics.kt` 里的 `discreteRank()` 排：
     *    **tier 2 优先于 tier 3**，其余按 tier 降序 —— 实际次序是 2、3、1、0。
     *    理由是 tier 2 那些预置效果经过厂商自己调音，手感好过我们在 tier 3 上拼出来的波形。
     * 2. **连续包络**（[playEnvelope]）按 tier 降序 —— 次序 3、2、1、0，但**厂商层
     *    （tier 2）可用时跳过 tier 1**（真机反馈：`createWaveform` 的通用波形在线性
     *    马达机型上是转子式的「普通震动」，宁可让引擎返 false 退离散替身，见
     *    `AppHaptics.playEnvelope`）。只有 tier 3 与 tier 1 真能播（tier 2 是固定预置
     *    效果、tier 0 只有常量，两者恒返 false），而 tier 3 的 [playEnvelope] 表达力
     *    更强，所以两层都可用时落在 tier 3。
     *
     * 第 1 条不是笔误：**改这个顺序前先读设计文档
     * docs/superpowers/plans/2026-09-01-haptics-overhaul.md 的「厂商通路矩阵」末段那条裁定**
     * —— 同机两条通路都可用时，离散语义走厂商预置效果、彩蛋那两段连续包络走 RichTap。
     * 把离散链「修正」回单纯的 tier 降序读起来完全合理，代价是目标机（RichTap 与厂商通路
     * 都可用的机型）上整片离散手感被无声换掉，不崩也不报错。`AppHapticsDegradationTest`
     * 有专门钉这个次序的用例，动了会立刻判红。
     *
     * 后端自己只需诚实报告属于哪一层，不要为了抢优先级虚报 —— 换位归引擎侧决定。
     */
    val tier: Int

    /**
     * 日志与测试用的可读名，例如 `"RichTap"`、`"MIUI"`、`"OPlus"`、`"AOSP"`。
     *
     * 单测靠它断言降级矩阵最终选中了哪一层，所以取值要稳定，建议是编译期常量。
     * 只作标识用：不得拼进任何上报或网络请求；[release] 之后仍应可读。
     */
    val name: String

    /**
     * 本机是否整体可用。构造后调一次，结果缓存。
     *
     * 探测里含类查找、反射构造、`getSystemService` 与 `Vibrator` 查询这些昂贵调用，
     * 而引擎在每次点击路径上都会问一次，所以**真实探测只做一次**（构造期或首次调用），
     * 之后直接返回缓存值，本方法自身要能在主线程零成本调用。
     *
     * 返回 false 的典型场景：本机无马达；类不在（`NoClassDefFoundError`）；反射构造失败；
     * 厂商接口首次调用抛错后整层禁用；`HapticCapabilities.lockedToConstants` 为 true
     * 而本后端 tier 大于 0（红线 1，转子马达锁 tier 0）。
     *
     * 一旦因失败从 true 翻成 false，就不要再翻回 true —— 整层禁用，不做重试。
     */
    fun isAvailable(): Boolean

    /**
     * 能否表达这个语义。false 时调用方继续往下降级。
     *
     * 判断必须建立在**探测结果**上，不能靠 ROM 版本号或机型白名单推断：
     * tier 2 逐 ID 调 `isSupportExtHapticFeedback(id)` 并缓存（红线 3）；
     * tier 1 把该语义用到的全部 primitive 一次传进 `areAllPrimitivesSupported(vararg)`
     * 整组探测（红线 2）；tier 0 按常量引入的 API level 门控，报的是算上退化目标之后的结果。
     *
     * 与 [isAvailable] 一样要求纯查表、可在主线程调用 —— 探测在 [isAvailable] 阶段一次做完。
     * 返回 false 只表示"这一层表达不了"，不是错误。
     */
    fun supports(semantic: HapticSemantic): Boolean

    /**
     * 发一次离散触感。
     *
     * 返回 false 的含义是「本次没发出去，**调用方应继续降级**」，不是"出错了要抛异常"。
     * 实现内部把所有失败吃掉再返回 false（厂商通路用 `catch (Throwable)`），
     * 任何逃出本方法的异常都算实现缺陷。
     *
     * `View.performHapticFeedback` 返回 `Boolean` 且不抛异常，tier 0 要**检查返回值**，
     * 不要围着它写 try-catch 兜底链 —— 那条链永不触发，是死代码。
     * 也禁止给它加 `FLAG_IGNORE_GLOBAL_SETTING`：用户在系统里关了触感就是关了。
     *
     * 反射与厂商 IPC 投到单线程 `Executor` 后本方法立即返回，
     * 所以 true 表示"已派发"，不表示"马达已经震完"。
     *
     * @param view 需要 View 通道的后端（AOSP 常量）用它；其余后端可忽略。
     *   为 null 时依赖 View 通道的后端返回 false，不要自己去翻找一个 View。
     * @return true 已派发；false 本次失败，调用方应降级
     */
    fun perform(view: View?, semantic: HapticSemantic): Boolean

    /**
     * 播一段连续振幅包络（彩蛋用）。
     *
     * 不支持就老实返回 false，**不许拿一记离散点击假装成功** —— 引擎靠这个返回值决定
     * 要不要退成"每段起点一记 tick"的稀疏编排，假装成功会让整条降级判断失效。
     *
     * 只有 tier 3（RichTap）与 tier 1（`createWaveform`）真能做；tier 2 是固定预置效果、
     * tier 0 只有常量，两者一律返回 false。走 API 36 AOSP 包络的实现要先比对
     * `HapticCapabilities.envelopeMaxSize`，控制点数超了返回 false 让上层拆段
     * （RichTap 不受此限制）。
     *
     * @param timingsMs 每个控制点的持续毫秒，长度须大于 0
     * @param amplitudes 每个控制点的目标振幅 0f..1f，与 timingsMs 等长；
     *   两者长度不等或振幅越界时返回 false，不要抛异常
     * @return false 表示本后端不支持连续包络
     */
    fun playEnvelope(timingsMs: IntArray, amplitudes: FloatArray): Boolean

    /**
     * 释放资源（RichTap quit、反射实例、单线程 Executor）。
     *
     * **必须 shutdown 自己那个单线程** `Executor` —— 漏了就是线程泄漏。
     * 反射持有的实例与 `Method` 引用一并清掉，RichTap 侧调 quit。
     *
     * 要求幂等：重复调用不抛异常；[release] 之后再收到 [perform] 或 [playEnvelope]
     * 一律返回 false，不要重新起线程。
     */
    fun release()
}
