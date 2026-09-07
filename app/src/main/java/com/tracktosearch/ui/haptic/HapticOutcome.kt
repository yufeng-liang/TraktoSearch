package com.tracktosearch.ui.haptic

import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow

/**
 * 输出侧的结果：一次操作办成了还是没办成。
 *
 * 只有两个值，刻意不加第三个。「部分成功」不是第三种手感 —— 还有东西需要重试就是
 * [FAILURE]（批量移除有失败项、导入有冲突、长图存了相册但分享面板打不开都归这一档），
 * 判据是「用户还得再动一次手吗」。分成三档意味着要发明第三种波形，而手上分不出来。
 *
 * 与 [HapticSemantic] 分开是因为这一层描述的是**业务结果**而不是振动形状：调用点写
 * 「这次失败了」，由 [semantic] 决定它在马达上长什么样。中间隔一层，输出侧的分类
 * （散在几十个 ViewModel 里）与波形设计（收在 `ui/haptic` 里）就能各自演进。
 */
enum class HapticOutcome {
    SUCCESS,
    FAILURE,
}

/**
 * 结果到语义的唯一映射。
 *
 * `when` 穷举且**不写 `else`**：往 [HapticOutcome] 里加值时编译器会在这里报错，
 * 逼着新值明确表态，而不是被 `else` 悄悄吞成某一档。
 */
fun HapticOutcome.semantic(): HapticSemantic = when (this) {
    HapticOutcome.SUCCESS -> HapticSemantic.CONFIRM
    HapticOutcome.FAILURE -> HapticSemantic.REJECT
}

/**
 * ViewModel 侧的结果出口。持有它的一方调 [success] / [failure]，界面侧用
 * `HapticOutcomeEffect(vm.hapticOutcomes)` 接一行就完事。
 *
 * ### 为什么是 `tryEmit` 而不是 `emit`
 *
 * 两条性质都是必需的：
 *
 * - **永不挂起。** 发射点紧贴既有那句用户可见反馈，而那些位置常在业务协程中途 ——
 *   `DetailViewModel` 有 `_markEvent.emit` 挂在豆瓣往返之前。会挂起的流等于让触感
 *   给业务流程当刹车。
 * - **没人听就丢。** 触感是即时反馈。用户切后台时办成的事，攒着等他回来再补震一记
 *   是 bug 不是功能 —— 那时屏幕上早就没有对应的东西了。
 *
 * ### 缓冲为什么是 1 且 DROP_OLDEST
 *
 * `replay = 0` 保证了「没有订阅者时直接丢」这一半。另一半是订阅者**在**但正忙的瞬间：
 * 零缓冲的 `tryEmit` 那时会返回 false 把事件丢掉，而这种忙通常就是收集方正在派发上一记
 * 触感。留一格缓冲把「最新的一记一定收得到」这件事变成确定的；因为 `replay = 0`，
 * 缓冲里的值也不会补给之后才订阅的收集方。
 *
 * 一格 + [BufferOverflow.DROP_OLDEST] 的合成效果是**合流**：同一批里连着到达、收集方还
 * 没来得及取的多个结果，只留最新那一个。这不是妥协而是想要的行为 —— 隔着 0ms 的两记
 * 振动手上分不出是两记，只会糊成一下；真有一批结果挤在一起时，用户在等的是最后那个。
 * 批量操作也不该靠连震 N 记来表达，它发一记聚合结果（有失败项就整批算 [HapticOutcome.FAILURE]）。
 */
class HapticOutcomeEmitter {

    private val _outcomes = MutableSharedFlow<HapticOutcome>(
        extraBufferCapacity = 1,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )

    /** 给界面侧收集。`HapticOutcomeEffect` 会挂生命周期闸门，这里不必也不该自己判。 */
    val outcomes: SharedFlow<HapticOutcome> = _outcomes.asSharedFlow()

    fun success() = emit(HapticOutcome.SUCCESS)

    fun failure() = emit(HapticOutcome.FAILURE)

    /** 糖：手上已经有个 `Boolean` 时少写一个 `if`。 */
    fun emit(success: Boolean) =
        emit(if (success) HapticOutcome.SUCCESS else HapticOutcome.FAILURE)

    fun emit(outcome: HapticOutcome) {
        // 返回值刻意丢掉：false 只说明「此刻没人接」，而那正是设计要的结果，
        // 调用点没有一种合理的处置方式
        _outcomes.tryEmit(outcome)
    }
}
