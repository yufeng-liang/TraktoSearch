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

    /**
     * 三档灯色的余烬端都要比满亮端暗。
     *
     * 灯泡的玻璃色是按亮度在这两端之间插值的，端点反了的话「灯在冷下去」会画成
     * 「灯在变亮」—— 整条尾巴和整排信号灯的升降都跟着反。
     */
    @Test
    fun 每档灯色的余烬端都比满亮端暗() {
        val pairs = listOf(
            "白炽" to (MachineBulbEmber to MachineBulbLit),
            "绿灯" to (MachineBulbEmberGreen to MachineBulbLitGreen),
            "红灯" to (MachineBulbEmberRed to MachineBulbLitRed),
        )
        for ((name, pair) in pairs) {
            assertWithMessage("$name 档的余烬端 ${pair.first.hex()} 不比满亮端暗")
                .that(pair.first.luminance()).isLessThan(pair.second.luminance())
        }
    }

    /**
     * 绿灯得是绿的、红灯得是红的，三档还要互相分得开。
     *
     * 「对了亮绿、错了亮红」全靠这两个颜色区分，而它们和白炽档共用同一套画法，
     * 只差 [MachineBulbLitGreen] / [MachineBulbLitRed] 这两个常量。
     * 谁把其中一个调到跟琥珀色接近，反馈就没了 —— 而这在装机截图上很难一眼看出来，
     * 因为灯泡只有 12dp。
     */
    @Test
    fun 绿灯红灯和白炽档三者互相分得开() {
        assertWithMessage("绿灯的绿通道没压过红通道").that(MachineBulbLitGreen.green)
            .isGreaterThan(MachineBulbLitGreen.red)
        assertWithMessage("绿灯的绿通道没压过蓝通道").that(MachineBulbLitGreen.green)
            .isGreaterThan(MachineBulbLitGreen.blue)
        assertWithMessage("红灯的红通道没明显压过绿通道").that(MachineBulbLitRed.red)
            .isGreaterThan(MachineBulbLitRed.green * 1.4f)

        val lits = listOf(
            "白炽" to MachineBulbLit,
            "绿灯" to MachineBulbLitGreen,
            "红灯" to MachineBulbLitRed,
        )
        for (i in lits.indices) {
            for (j in i + 1 until lits.size) {
                val (leftName, left) = lits[i]
                val (rightName, right) = lits[j]
                val distance = kotlin.math.abs(left.red - right.red) +
                    kotlin.math.abs(left.green - right.green) +
                    kotlin.math.abs(left.blue - right.blue)
                assertWithMessage("$leftName ${left.hex()} 和 $rightName ${right.hex()} 差得太近")
                    .that(distance).isGreaterThan(0.30f)
            }
        }
    }

    private fun Color.hex(): String = "#%02X%02X%02X".format(
        (red * 255).toInt(),
        (green * 255).toInt(),
        (blue * 255).toInt()
    )
}
