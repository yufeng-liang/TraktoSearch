package com.tracktosearch.ui.screen.swiftie.bracelet

import androidx.compose.ui.unit.dp
import com.google.common.truth.Truth.assertThat
import org.junit.Test

class SwiftieBraceletNicknameTest {

    private companion object {
        /** 👍 + 肤色修饰符 U+1F3FD。 */
        const val THUMBS_UP = "👍🏽"

        /** ❤ + 变体选择符 U+FE0F。 */
        const val HEART = "❤️"

        /** 👨‍👩‍👧：三个人两个 ZWJ。 */
        const val FAMILY = "👨‍👩‍👧"

        /** 🇨🇳 / 🇺🇸：各两个区域指示符。 */
        const val FLAG_CN = "🇨🇳"
        const val FLAG_US = "🇺🇸"
    }

    @Test
    fun keepsLettersDigitsAndCjkAndForcesUppercase() {
        assertThat(braceletNicknameTokens("Ava")).containsExactly("A", "V", "A").inOrder()
        assertThat(braceletNicknameTokens("k13")).containsExactly("K", "1", "3").inOrder()
        // 汉字没有大小写，原样上珠
        assertThat(braceletNicknameTokens("阿霉")).containsExactly("阿", "霉").inOrder()
    }

    @Test
    fun dropsWhitespacePunctuationAndZeroWidth() {
        assertThat(braceletNicknameTokens("  a b  ")).containsExactly("A", "B").inOrder()
        assertThat(braceletNicknameTokens("a-b_c.d!")).containsExactly("A", "B", "C", "D").inOrder()
        // + $ ^ 是别的符号类别，珠子上印它们没有意义
        assertThat(braceletNicknameTokens("a+b\$c^")).containsExactly("A", "B", "C").inOrder()
        // 零宽空格，和一个谁也没连上的尾巴 ZWJ
        assertThat(braceletNicknameTokens("​x‍")).containsExactly("X")
    }

    @Test
    fun emptySourcesGiveNoBeads() {
        assertThat(braceletNicknameTokens(null)).isEmpty()
        assertThat(braceletNicknameTokens("")).isEmpty()
        // 全是标点：手链就只挂两条，不是挂一条空的
        assertThat(braceletNicknameTokens("...")).isEmpty()
        assertThat(braceletNicknameTokens("   ")).isEmpty()
    }

    @Test
    fun oneEmojiIsOneBead() {
        assertThat(braceletNicknameTokens(THUMBS_UP)).containsExactly(THUMBS_UP)
        assertThat(braceletNicknameTokens(HEART)).containsExactly(HEART)
        assertThat(braceletNicknameTokens(FAMILY)).containsExactly(FAMILY)
        // 混在字母中间也不会把字母吃掉
        assertThat(braceletNicknameTokens("a${FAMILY}b"))
            .containsExactly("A", FAMILY, "B").inOrder()
    }

    @Test
    fun flagsPairUpAndDoNotBleedIntoEachOther() {
        // 四个区域指示符是两面旗，不是一面也不是四颗
        assertThat(braceletNicknameTokens(FLAG_CN + FLAG_US))
            .containsExactly(FLAG_CN, FLAG_US).inOrder()
    }

    @Test
    fun combiningMarksStayWithTheirBase() {
        // e + 组合尖音符：一颗珠，大写只落在基字符上，音符留着
        assertThat(braceletNicknameTokens("é")).containsExactly("É")
        // 预组合的那个码点自己就有大写形式
        assertThat(braceletNicknameTokens("é")).containsExactly("É")
    }

    @Test
    fun capsAtNineBeads() {
        val long = braceletNicknameTokens("abcdefghijklmn")
        assertThat(long).hasSize(NICKNAME_MAX_BEADS)
        // 数满 9 颗就停
        assertThat(long.last()).isEqualTo("I")
        assertThat(braceletNicknameTokens("abc", limit = 2)).containsExactly("A", "B").inOrder()
    }

    @Test
    fun strandLengthIsTheSameWhoeverYouAre() {
        (1..NICKNAME_MAX_BEADS).forEach { count ->
            val beads = nicknameStrandBeadCount(count)
            // 只有 10 或 11 两种：另外两条都是 11 颗，差一颗看不出来
            assertThat(beads).isIn(listOf(10, 11))
            // 两侧补得一样多，字母才居中
            assertThat((beads - count) % 2).isEqualTo(0)
            assertThat(beads).isAtLeast(count)
        }
    }

    @Test
    fun nicknameBeadsAreTheBiggestOnesOnScreen() {
        val front = BRACELET_SLOTS.last()
        val middle = BRACELET_SLOTS[BRACELET_SLOTS.size - 2]
        val middleSize = beadSizeFraction(beadCount = 11, widthFraction = middle.widthFraction)
        (1..NICKNAME_MAX_BEADS).forEach { count ->
            val size = beadSizeFraction(nicknameStrandBeadCount(count), front.widthFraction)
            // 最前那条的珠子最大，纵深才读得出来
            assertThat(size).isGreaterThan(middleSize)
            assertThat(size).isGreaterThan(0.085f)
            assertThat(size).isLessThan(0.095f)
        }
    }

    @Test
    fun slotsGetWiderAndSaggierTowardsTheFront() {
        BRACELET_SLOTS.zipWithNext { back, front ->
            assertThat(front.yFraction).isGreaterThan(back.yFraction)
            assertThat(front.widthFraction).isGreaterThan(back.widthFraction)
            assertThat(front.sagFraction).isGreaterThan(back.sagFraction)
        }
    }

    @Test
    fun theSlotIsTallEnoughForTheFrontStrandToHangFreely() {
        val width = 360f
        val height = braceletHeightFor(width.dp).value
        val front = BRACELET_SLOTS.last()
        // 最低那颗珠的下缘：基线 + 下垂 + 半径。垂出插槽不会被裁，会画到下面那句文案上
        val lowest = height * front.yFraction +
            width * front.widthFraction * front.sagFraction +
            width * beadSizeFraction(10, front.widthFraction) / 2f
        assertThat(lowest).isLessThan(height)
    }
}
