package com.tracktosearch.ui.haptic

import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Test

/**
 * [HapticOutcome] 与 [HapticOutcomeEmitter]：映射方向、防漏、以及 `tryEmit` 的两条性质。
 *
 * 这一层是纯逻辑，纯 JVM 就能钉住。三种故障都是静默的：映射反了会在成功时震「失败」那一记；
 * 加了新结果值忘了表态会被 `else` 吞掉（所以产品代码里不写 `else`，这里再加一条防漏断言）；
 * 而「没人听就丢」若失效，用户切回前台时会莫名震一记 —— 那时屏幕上早就没有对应的东西了。
 */
@OptIn(ExperimentalCoroutinesApi::class)
class HapticOutcomeTest {

    @Test
    fun `成功映射到 CONFIRM，失败映射到 REJECT`() {
        assertThat(HapticOutcome.SUCCESS.semantic()).isEqualTo(HapticSemantic.CONFIRM)
        assertThat(HapticOutcome.FAILURE.semantic()).isEqualTo(HapticSemantic.REJECT)
    }

    /**
     * 防漏：往 [HapticOutcome] 加值时，产品代码那个不带 `else` 的 `when` 会先编译报错；
     * 万一有人图省事补了 `else`，这条用例接着咬 —— 它要求每个值都被这里列过一遍。
     */
    @Test
    fun `每个结果值都有映射且互不相同`() {
        val mapped = HapticOutcome.entries.associateWith { it.semantic() }
        assertThat(mapped.keys).containsExactlyElementsIn(HapticOutcome.entries)
        assertThat(mapped.values.toSet()).hasSize(HapticOutcome.entries.size)
    }

    @Test
    fun `success 与 failure 发的是对应的结果`() = runTest {
        val emitter = HapticOutcomeEmitter()
        val seen = mutableListOf<HapticOutcome>()
        val job = launch { emitter.outcomes.collect { seen += it } }
        advanceUntilIdle()

        // 每记之间让收集方跑一轮：连着发属于「合流」那条用例的事
        emitter.success()
        advanceUntilIdle()
        emitter.failure()
        advanceUntilIdle()

        assertThat(seen).containsExactly(HapticOutcome.SUCCESS, HapticOutcome.FAILURE).inOrder()
        job.cancel()
    }

    @Test
    fun `布尔糖走同一条路`() = runTest {
        val emitter = HapticOutcomeEmitter()
        val seen = mutableListOf<HapticOutcome>()
        val job = launch { emitter.outcomes.collect { seen += it } }
        advanceUntilIdle()

        emitter.emit(success = true)
        advanceUntilIdle()
        emitter.emit(success = false)
        advanceUntilIdle()

        assertThat(seen).containsExactly(HapticOutcome.SUCCESS, HapticOutcome.FAILURE).inOrder()
        job.cancel()
    }

    /**
     * 合流：收集方还没来得及取就连着到达的多个结果，只留最新那一个。
     *
     * 这是 `extraBufferCapacity = 1` 加 `DROP_OLDEST` 的合成效果，也是想要的行为 ——
     * 隔着 0ms 的两记振动手上分不出是两记，只会糊成一下，而用户在等的是最后那个结果。
     * 换成大缓冲就会把一批结果放成一串连震。
     */
    @Test
    fun `同一批里连着到达的结果只留最新那一记`() = runTest {
        val emitter = HapticOutcomeEmitter()
        val seen = mutableListOf<HapticOutcome>()
        val job = launch { emitter.outcomes.collect { seen += it } }
        advanceUntilIdle()

        emitter.success()
        emitter.success()
        emitter.failure()
        advanceUntilIdle()

        assertThat(seen).containsExactly(HapticOutcome.FAILURE)
        job.cancel()
    }

    /**
     * 无收集方时丢弃：之后才订阅的收集方一记都不该收到。
     *
     * 这条钉的是 `replay = 0`。留了 replay 的话，用户在后台时办成的事会在他回到前台的
     * 那一瞬间补震一记。
     */
    @Test
    fun `没有收集方时发出的结果不会补给之后的收集方`() = runTest {
        val emitter = HapticOutcomeEmitter()

        emitter.success()
        emitter.failure()

        val seen = mutableListOf<HapticOutcome>()
        val job = launch { emitter.outcomes.collect { seen += it } }
        advanceUntilIdle()

        assertThat(seen).isEmpty()
        job.cancel()
    }

    /**
     * 不挂起：没有收集方时发一万记也必须立即返回。
     *
     * 用例本身就是断言 —— 真挂起了 `runTest` 会在超时后判红，而不是悄悄慢下来。
     * 发射点常在业务协程中途（`DetailViewModel` 有 `_markEvent.emit` 挂在豆瓣往返之前），
     * 会挂起的流等于让触感给业务流程当刹车。
     */
    @Test
    fun `没有收集方时连发一万记也不挂起`() = runTest {
        val emitter = HapticOutcomeEmitter()
        repeat(10_000) { emitter.success() }
    }
}
