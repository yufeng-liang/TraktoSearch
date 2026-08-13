package com.tracktosearch.ui.screen.ai

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class AiSpriteMotionTest {

    @Test
    fun automaticMotionUsesOnlyCharactersWithDedicatedArt() {
        assertThat(automaticSpriteArt("chiikawa")).isNotNull()
        assertThat(AiSpriteArt.forCharacter("chiikawa")).isSameInstanceAs(automaticSpriteArt("chiikawa"))
        assertThat(automaticSpriteArt("hachiware")).isNotNull()
        assertThat(automaticSpriteArt("usagi")).isNotNull()
        assertThat(automaticSpriteArt("rakko")).isNull()
    }

    @Test
    fun artSpecKeepsDistinctCharacterMotionParameters() {
        assertThat(automaticSpriteArt("chiikawa")!!.reaction).isEqualTo(SpriteReaction.GENTLE_LOOK)
        assertThat(automaticSpriteArt("hachiware")!!.reaction).isEqualTo(SpriteReaction.NOD)
        assertThat(automaticSpriteArt("usagi")!!.reaction).isEqualTo(SpriteReaction.BOUNCE)
    }

    @Test
    fun peekFrameUsesDedicatedArtAtDetailAnchors() {
        val art = automaticSpriteArt("chiikawa")!!

        assertThat(art.drawableFor(AiSpriteMotionState.PEEK, AiSpriteAnchor.DetailHeader))
            .isEqualTo(art.peekRes)
        assertThat(art.drawableFor(AiSpriteMotionState.PEEK, AiSpriteAnchor.DetailPanel))
            .isEqualTo(art.peekRes)
        assertThat(art.drawableFor(AiSpriteMotionState.PEEK, AiSpriteAnchor.RecommendationsTab))
            .isEqualTo(art.peekRes)
    }

    @Test
    fun motionInterruptAlwaysRetreatsToHidden() {
        val controller = AiSpriteMotionController()

        controller.start(AiSpriteMotionState.PEEK)
        controller.interrupt(AiSpriteInterruptReason.USER_INPUT)

        assertThat(controller.state).isEqualTo(AiSpriteMotionState.RETREAT)
        controller.finishRetreat()
        assertThat(controller.state).isEqualTo(AiSpriteMotionState.HIDDEN)
    }

    @Test
    fun publicAnchorsIncludeSearchAndDetailReusePoints() {
        assertThat(AiSpriteAnchor.entries).containsExactly(
            AiSpriteAnchor.Cloud,
            AiSpriteAnchor.SearchBox,
            AiSpriteAnchor.ResultCard,
            AiSpriteAnchor.BottomPanel,
            AiSpriteAnchor.DetailHeader,
            AiSpriteAnchor.DetailPanel,
            AiSpriteAnchor.RecommendationsTab
        ).inOrder()
    }

    @Test
    fun interruptRequestCarriesAUniqueRevisionAndReason() {
        val first = AiSpriteInterruptRequest(1L, AiSpriteInterruptReason.USER_INPUT)
        val second = AiSpriteInterruptRequest(2L, AiSpriteInterruptReason.SCROLL)

        assertThat(first).isNotEqualTo(second)
        assertThat(first.revision).isEqualTo(1L)
        assertThat(second.reason).isEqualTo(AiSpriteInterruptReason.SCROLL)
    }
}
