package com.tracktosearch.ui.screen.login

import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import org.junit.Test

/**
 * 取票机档位的取档规则，以及每档配哪种灯。
 *
 * 四个入参（验码中、有错、正在打印、已取票）互不排斥，五档互斥，所以优先级本身就是设计的一部分。
 * 而这一档位同时供着像素屏两行、屏上墨色、六格抖动和顶边那排灯 ——
 * 顺序错了不会编译失败，也不会在装机截图上一眼看出来，只有正好赶上那个时刻才看得见。
 */
class MachinePhaseTest {

    @Test
    fun 验码期间是验码档() {
        assertThat(phase(isLoading = true)).isEqualTo(MachinePhase.Verifying)
    }

    /**
     * 粘贴失败那个标志不随新请求清掉（`AuthViewModel.activate()` 只清 `error`），
     * 所以「上一次的结论」和「这一次在验码」确实会同时为真。该看到的是新一次的验码。
     */
    @Test
    fun 上一次的报错盖不住这一次的验码() {
        assertThat(phase(isLoading = true, hasError = true))
            .isEqualTo(MachinePhase.Verifying)
    }

    @Test
    fun 验码结束才轮到结果档() {
        assertThat(phase(hasError = true)).isEqualTo(MachinePhase.Failed)
        assertThat(phase(isPrinting = true)).isEqualTo(MachinePhase.Printing)
    }

    /** 万一两个结果同时为真（出票了又紧接着报错），报错优先：出错比出票更该被看见。 */
    @Test
    fun 报错压过打印() {
        assertThat(phase(hasError = true, isPrinting = true))
            .isEqualTo(MachinePhase.Failed)
    }

    /** 打印是已取票的那一瞬间，两个标志同时为真。这时该说的是「正在打印」而不是「已取票」。 */
    @Test
    fun 打印压过已取票() {
        assertThat(phase(isPrinting = true, isCollected = true))
            .isEqualTo(MachinePhase.Printing)
    }

    @Test
    fun 什么都没发生就是待输入档() {
        assertThat(phase()).isEqualTo(MachinePhase.Ready)
        assertThat(phase(isCollected = true)).isEqualTo(MachinePhase.Collected)
    }

    /**
     * 只有两个结果档带整排颜色。
     *
     * `MarqueeBulbs` 就是靠 `glow` 是不是 null 决定「整排同亮」还是「头灯扫」的：
     * 哪天给验码档配上一个 glow，黄灯就从流水变成整排常亮，跟「机器在忙」的意思相反。
     */
    @Test
    fun 只有结果档整排同色其余靠头灯扫() {
        val bulbs = MachinePhase.entries.associateWith { bulbSignalFor(it) }
        for ((machinePhase, signal) in bulbs) {
            val shouldBeSolid = machinePhase == MachinePhase.Failed ||
                machinePhase == MachinePhase.Printing
            assertWithMessage("$machinePhase 配的 $signal 整排同色与否不对")
                .that(signal.glow != null).isEqualTo(shouldBeSolid)
        }
    }

    /** 已取票和待输入都归进场那一遍：票已经静态停在出票口，灯再喊一次是重复。 */
    @Test
    fun 已取票和待输入共用进场那一遍() {
        assertThat(bulbSignalFor(MachinePhase.Collected)).isEqualTo(BulbSignal.Sweep)
        assertThat(bulbSignalFor(MachinePhase.Ready)).isEqualTo(BulbSignal.Sweep)
    }

    private fun phase(
        isLoading: Boolean = false,
        hasError: Boolean = false,
        isPrinting: Boolean = false,
        isCollected: Boolean = false,
    ): MachinePhase = machinePhaseOf(
        isLoading = isLoading,
        hasError = hasError,
        isPrinting = isPrinting,
        isCollected = isCollected
    )
}
