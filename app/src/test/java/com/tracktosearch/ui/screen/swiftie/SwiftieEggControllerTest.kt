package com.tracktosearch.ui.screen.swiftie

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class SwiftieEggControllerTest {

    private fun action(
        count: Int,
        solved: Boolean = false,
        onboarded: Boolean = true,
        granted: Boolean = true
    ) = SwiftieEggController.resolveCloudAction(count, solved, onboarded, granted)

    @Test
    fun unsolved_cyclesEggPermissionLottie() {
        assertThat(action(0, granted = false)).isEqualTo(CloudAction.SWIFTIE_EGG)
        assertThat(action(1, granted = false)).isEqualTo(CloudAction.LOCATION_PERMISSION)
        assertThat(action(2, granted = false)).isEqualTo(CloudAction.RANDOM_LOTTIE)
        // 取模循环：关掉题面的人过两下还能再遇到彩蛋
        assertThat(action(3, granted = false)).isEqualTo(CloudAction.SWIFTIE_EGG)
    }

    @Test
    fun unsolved_permissionSlotFallsBackToLottieWhenAlreadyGranted() {
        assertThat(action(1, granted = true)).isEqualTo(CloudAction.RANDOM_LOTTIE)
    }

    @Test
    fun solved_collapsesToLegacyBehaviour() {
        assertThat(action(0, solved = true, granted = false))
            .isEqualTo(CloudAction.LOCATION_PERMISSION)
        assertThat(action(0, solved = true, granted = true))
            .isEqualTo(CloudAction.RANDOM_LOTTIE)
        assertThat(action(7, solved = true, granted = true))
            .isEqualTo(CloudAction.RANDOM_LOTTIE)
    }

    @Test
    fun onboardingIncomplete_ignoresClickAndDoesNotCount() {
        assertThat(action(0, onboarded = false)).isEqualTo(CloudAction.IGNORED)
        assertThat(SwiftieEggController.shouldCountCloudClick(false)).isFalse()
        assertThat(SwiftieEggController.shouldCountCloudClick(true)).isTrue()
    }

    @Test
    fun keyword_matchesCaseAndWhitespaceInsensitively() {
        assertThat(SwiftieEggController.matchesKeyword("  Taylor Swift ")).isTrue()
        assertThat(SwiftieEggController.matchesKeyword("TAYLORSWIFT")).isTrue()
        assertThat(SwiftieEggController.matchesKeyword("霉霉")).isTrue()
        assertThat(SwiftieEggController.matchesKeyword("泰勒斯威夫特")).isTrue()
        assertThat(SwiftieEggController.matchesKeyword("lover")).isTrue()
        assertThat(SwiftieEggController.matchesKeyword("swiftie")).isTrue()
    }

    @Test
    fun keyword_neverMatchesBareThirteenOrPartialText() {
        // 13 太短，会误伤真实搜索需求，明确不在清单里
        assertThat(SwiftieEggController.matchesKeyword("13")).isFalse()
        assertThat(SwiftieEggController.matchesKeyword("taylor swift 1989")).isFalse()
        assertThat(SwiftieEggController.matchesKeyword("")).isFalse()
        assertThat(SwiftieEggController.matchesKeyword("loverboy")).isFalse()
    }

    @Test
    fun answer_onlyThirteenIsCorrect() {
        assertThat(SwiftieEggController.isCorrect("13")).isTrue()
        assertThat(SwiftieEggController.isCorrect("12")).isFalse()
        assertThat(SwiftieEggController.isCorrect("")).isFalse()
        assertThat(SwiftieEggController.isCorrect("1")).isFalse()
        assertThat(SwiftieEggController.isCorrect("013")).isFalse()
    }
}
