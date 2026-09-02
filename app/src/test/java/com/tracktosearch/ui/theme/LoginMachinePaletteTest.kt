package com.tracktosearch.ui.theme

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.luminance
import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import com.tracktosearch.ui.theme.ThemeTestSupport.AA_NORMAL
import com.tracktosearch.ui.theme.ThemeTestSupport.contrast
import org.junit.Test

/**
 * 取票机机壳那一套 Machine* 固定色的可读性护栏。
 *
 * 这套色一个 `colorScheme` 槽位都不读（机壳的材质就是这一屏的身份，见 Color.kt），
 * 所以主题色那几个测试类一条都覆盖不到它 —— 而它承载的文字比主题色更多：
 * 铭牌、像素屏两行、六格取票码、12 个键面、取票键。
 *
 * 每条都验明暗两档：两档机壳不是同一个颜色调亮调暗，是两种金属，
 * 一档够读不代表另一档也够。
 */
class LoginMachinePaletteTest {

    private val shells = listOf(
        "浅色档" to MachineShellLight,
        "深色档" to MachineShellDark,
    )

    /**
     * 机壳渐变只许往下压暗，不许往上提亮。
     *
     * 这是下面所有对比度数字的前提：只要最亮的一点就是机壳本身，
     * 拿 `MachineShell*` 当底算出来的比值就是真实最坏情况。
     * 一旦有人把渐变改成「上亮下暗」，顶部那一带（铭牌、像素屏、六格都在那儿）
     * 会比机壳亮，压在上面的墨色实际对比度就低于这里验过的值。
     */
    @Test
    fun 机壳渐变最亮的一点就是机壳本身() {
        for ((name, shell) in shells) {
            val shaded = lerp(shell, Color.Black, MachineShellShadeFraction)
            assertWithMessage("$name 机壳渐变的另一端 ${shaded.hex()} 比机壳本身还亮")
                .that(shaded.luminance()).isAtMost(shell.luminance())
        }
    }

    @Test
    fun 六格取票码压在两档机壳上都达到AA() {
        for ((name, shell) in shells) {
            assertWithMessage("$name 机壳上的取票码数字不足")
                .that(contrast(MachineCodeInk, shell)).isAtLeast(AA_NORMAL)
        }
    }

    /** 铭牌不直接压在机壳上，而是压在自己的凹槽里 —— 正因为直接压在机壳上不够。 */
    @Test
    fun 铭牌蚀刻字压在两档凹槽上都达到AA() {
        val plates = listOf(
            "浅色档" to MachinePlateLight,
            "深色档" to MachinePlateDark,
        )
        for ((name, plate) in plates) {
            assertWithMessage("$name 铭牌凹槽上的蚀刻字不足")
                .that(contrast(MachinePlateInk, plate)).isAtLeast(AA_NORMAL)
        }
    }

    @Test
    fun 键面数字压在对应键帽上达到AA() {
        val keycaps = listOf(
            "浅色档" to (MachineKeyInkLight to MachineKeycapLight),
            "深色档" to (MachineKeyInkDark to MachineKeycapDark),
        )
        for ((name, pair) in keycaps) {
            assertWithMessage("$name 键面数字不足")
                .that(contrast(pair.first, pair.second)).isAtLeast(AA_NORMAL)
        }
    }

    /** 像素屏两行都是小字号，正常态和报错态各一套墨色，两档屏槽都要够。 */
    @Test
    fun 点阵屏两种墨色压在两档屏槽上都达到AA() {
        val wells = listOf(
            "浅色档" to MachineDisplayWellLight,
            "深色档" to MachineDisplayWellDark,
        )
        val inks = listOf(
            "正常" to MachineDisplayInk,
            "报错" to MachineDisplayInkError,
        )
        for ((wellName, well) in wells) {
            for ((inkName, ink) in inks) {
                assertWithMessage("$wellName 屏槽上的$inkName 墨色不足")
                    .that(contrast(ink, well)).isAtLeast(AA_NORMAL)
            }
        }
    }

    /** 取票键是整台机器唯一上赭红的地方，键面文字压在它上面必须够读。 */
    @Test
    fun 取票键文字压在赭红上达到AA() {
        assertThat(contrast(LoginPaperLight, LoginTitleInk)).isAtLeast(AA_NORMAL)
    }

    /** 凹槽要比机壳暗，否则「嵌进去」的观感反过来变成贴上去的一块亮片。 */
    @Test
    fun 像素屏槽和出票口都比机壳暗() {
        assertThat(MachineDisplayWellLight.luminance())
            .isLessThan(MachineShellLight.luminance())
        assertThat(MachineDisplayWellDark.luminance())
            .isLessThan(MachineShellDark.luminance())
        assertThat(MachineSlotWall.luminance())
            .isLessThan(MachineDisplayWellLight.luminance())
    }

    private fun Color.hex(): String = "#%02X%02X%02X".format(
        (red * 255).toInt(),
        (green * 255).toInt(),
        (blue * 255).toInt()
    )
}
