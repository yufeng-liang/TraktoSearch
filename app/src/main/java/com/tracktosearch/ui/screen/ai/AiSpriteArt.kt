package com.tracktosearch.ui.screen.ai

import androidx.annotation.DrawableRes
import com.tracktosearch.R

/** 自动探头只使用已经核对过的真实 Chiikawa 角色素材。 */
data class AiCharacterArtSpec(
    val characterId: String,
    @DrawableRes val peekRes: Int,
    @DrawableRes val standbyRes: Int,
    @DrawableRes val reactRes: Int,
    val reaction: SpriteReaction,
    val peekScale: Float = 1f,
    val reactionRotation: Float = 0f
)

enum class SpriteReaction {
    GENTLE_LOOK,
    NOD,
    BOUNCE
}

private val automaticArt = listOf(
    AiCharacterArtSpec(
        characterId = "chiikawa",
        peekRes = R.drawable.ai_sprite_chiikawa_peek,
        standbyRes = R.drawable.ai_sprite_chiikawa_standby,
        reactRes = R.drawable.ai_sprite_chiikawa_react,
        reaction = SpriteReaction.GENTLE_LOOK,
        peekScale = 1.08f,
        reactionRotation = -2.5f
    ),
    AiCharacterArtSpec(
        characterId = "hachiware",
        peekRes = R.drawable.ai_sprite_hachiware_peek,
        standbyRes = R.drawable.ai_sprite_hachiware_standby,
        reactRes = R.drawable.ai_sprite_hachiware_react,
        reaction = SpriteReaction.NOD,
        peekScale = 1.02f,
        reactionRotation = 2f
    ),
    AiCharacterArtSpec(
        characterId = "usagi",
        peekRes = R.drawable.ai_sprite_usagi_peek,
        standbyRes = R.drawable.ai_sprite_usagi_standby,
        reactRes = R.drawable.ai_sprite_usagi_react,
        reaction = SpriteReaction.BOUNCE,
        peekScale = 0.96f,
        reactionRotation = -3f
    )
).associateBy(AiCharacterArtSpec::characterId)

/** 跨页面共享的角色资产目录；页面只按角色 ID 取配置，不持有资源路径。 */
object AiSpriteArt {
    fun forCharacter(characterId: String): AiCharacterArtSpec? = automaticArt[characterId]
}

fun automaticSpriteArt(characterId: String): AiCharacterArtSpec? = AiSpriteArt.forCharacter(characterId)

@DrawableRes
fun AiCharacterArtSpec.drawableFor(
    state: AiSpriteMotionState,
    anchor: AiSpriteAnchor = AiSpriteAnchor.Cloud
): Int = when (state) {
    // 边缘锚点使用专属探头姿态；云朵/详情区域使用完整姿态，避免把竖边抓取线带进内容区。
    AiSpriteMotionState.PEEK -> when (anchor) {
        AiSpriteAnchor.SearchBox,
        AiSpriteAnchor.ResultCard,
        AiSpriteAnchor.DetailHeader,
        AiSpriteAnchor.DetailPanel,
        AiSpriteAnchor.BottomPanel,
        AiSpriteAnchor.RecommendationsTab -> peekRes
        AiSpriteAnchor.Cloud -> standbyRes
    }
    AiSpriteMotionState.REACT -> reactRes
    AiSpriteMotionState.PREPARE,
    AiSpriteMotionState.OBSERVE,
    AiSpriteMotionState.HIDDEN,
    AiSpriteMotionState.RETREAT -> standbyRes
}
