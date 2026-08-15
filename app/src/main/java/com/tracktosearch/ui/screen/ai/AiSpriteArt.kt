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
    val reactionRotation: Float = 0f,
    val layerArt: AiCharacterLayerArt? = null
)

enum class SpriteReaction {
    GENTLE_LOOK,
    NOD,
    BOUNCE
}

/** 角色的公共分层顺序。所有图层使用同一基准画布，避免动作时出现比例漂移。 */
enum class AiSpriteLayer {
    BODY,
    HEAD,
    EARS,
    FACE,
    ARMS,
    EFFECTS
}

/** 同一角色在一个动效姿态下的五个透明图层。 */
data class AiCharacterLayerFrame(
    @DrawableRes val bodyRes: Int,
    @DrawableRes val headRes: Int,
    @DrawableRes val earsRes: Int,
    @DrawableRes val faceRes: Int,
    @DrawableRes val armsRes: Int
)

/**
 * 角色图层资源。EFFECTS 保留给 Compose 绘制的心形、汗滴等瞬时效果。
 *
 * 三套姿态使用同一基准画布；动画时不再移动完整角色图，而是对各层分别施加变换。
 */
data class AiCharacterLayerArt(
    val standby: AiCharacterLayerFrame,
    val peek: AiCharacterLayerFrame,
    val react: AiCharacterLayerFrame,
    @DrawableRes val effectsRes: Int? = null
)

@DrawableRes
fun AiCharacterLayerArt.drawableFor(
    layer: AiSpriteLayer,
    state: AiSpriteMotionState = AiSpriteMotionState.OBSERVE
): Int? = when (layer) {
    AiSpriteLayer.BODY -> frameFor(state).bodyRes
    AiSpriteLayer.HEAD -> frameFor(state).headRes
    AiSpriteLayer.EARS -> frameFor(state).earsRes
    AiSpriteLayer.FACE -> frameFor(state).faceRes
    AiSpriteLayer.ARMS -> frameFor(state).armsRes
    AiSpriteLayer.EFFECTS -> effectsRes
}

private fun AiCharacterLayerArt.frameFor(state: AiSpriteMotionState): AiCharacterLayerFrame = when (state) {
    AiSpriteMotionState.PEEK -> peek
    AiSpriteMotionState.REACT -> react
    AiSpriteMotionState.PREPARE,
    AiSpriteMotionState.OBSERVE,
    AiSpriteMotionState.HIDDEN,
    AiSpriteMotionState.RETREAT -> standby
}

private fun layerFrame(
    characterId: String,
    state: String
): AiCharacterLayerFrame = when (characterId to state) {
    "chiikawa" to "standby" -> AiCharacterLayerFrame(
        R.drawable.ai_layer_chiikawa_standby_body,
        R.drawable.ai_layer_chiikawa_standby_head,
        R.drawable.ai_layer_chiikawa_standby_ears,
        R.drawable.ai_layer_chiikawa_standby_face,
        R.drawable.ai_layer_chiikawa_standby_arms
    )
    "chiikawa" to "peek" -> AiCharacterLayerFrame(
        R.drawable.ai_layer_chiikawa_peek_body,
        R.drawable.ai_layer_chiikawa_peek_head,
        R.drawable.ai_layer_chiikawa_peek_ears,
        R.drawable.ai_layer_chiikawa_peek_face,
        R.drawable.ai_layer_chiikawa_peek_arms
    )
    "chiikawa" to "react" -> AiCharacterLayerFrame(
        R.drawable.ai_layer_chiikawa_react_body,
        R.drawable.ai_layer_chiikawa_react_head,
        R.drawable.ai_layer_chiikawa_react_ears,
        R.drawable.ai_layer_chiikawa_react_face,
        R.drawable.ai_layer_chiikawa_react_arms
    )
    "hachiware" to "standby" -> AiCharacterLayerFrame(
        R.drawable.ai_layer_hachiware_standby_body,
        R.drawable.ai_layer_hachiware_standby_head,
        R.drawable.ai_layer_hachiware_standby_ears,
        R.drawable.ai_layer_hachiware_standby_face,
        R.drawable.ai_layer_hachiware_standby_arms
    )
    "hachiware" to "peek" -> AiCharacterLayerFrame(
        R.drawable.ai_layer_hachiware_peek_body,
        R.drawable.ai_layer_hachiware_peek_head,
        R.drawable.ai_layer_hachiware_peek_ears,
        R.drawable.ai_layer_hachiware_peek_face,
        R.drawable.ai_layer_hachiware_peek_arms
    )
    "hachiware" to "react" -> AiCharacterLayerFrame(
        R.drawable.ai_layer_hachiware_react_body,
        R.drawable.ai_layer_hachiware_react_head,
        R.drawable.ai_layer_hachiware_react_ears,
        R.drawable.ai_layer_hachiware_react_face,
        R.drawable.ai_layer_hachiware_react_arms
    )
    "usagi" to "standby" -> AiCharacterLayerFrame(
        R.drawable.ai_layer_usagi_standby_body,
        R.drawable.ai_layer_usagi_standby_head,
        R.drawable.ai_layer_usagi_standby_ears,
        R.drawable.ai_layer_usagi_standby_face,
        R.drawable.ai_layer_usagi_standby_arms
    )
    "usagi" to "peek" -> AiCharacterLayerFrame(
        R.drawable.ai_layer_usagi_peek_body,
        R.drawable.ai_layer_usagi_peek_head,
        R.drawable.ai_layer_usagi_peek_ears,
        R.drawable.ai_layer_usagi_peek_face,
        R.drawable.ai_layer_usagi_peek_arms
    )
    "usagi" to "react" -> AiCharacterLayerFrame(
        R.drawable.ai_layer_usagi_react_body,
        R.drawable.ai_layer_usagi_react_head,
        R.drawable.ai_layer_usagi_react_ears,
        R.drawable.ai_layer_usagi_react_face,
        R.drawable.ai_layer_usagi_react_arms
    )
    else -> error("Missing sprite layer frame: $characterId/$state")
}

private fun characterLayerArt(characterId: String): AiCharacterLayerArt = AiCharacterLayerArt(
    standby = layerFrame(characterId, "standby"),
    peek = layerFrame(characterId, "peek"),
    react = layerFrame(characterId, "react")
)

private val automaticArt = listOf(
    AiCharacterArtSpec(
        characterId = "chiikawa",
        peekRes = R.drawable.ai_sprite_chiikawa_peek,
        standbyRes = R.drawable.ai_sprite_chiikawa_standby,
        reactRes = R.drawable.ai_sprite_chiikawa_react,
        reaction = SpriteReaction.GENTLE_LOOK,
        peekScale = 1.08f,
        reactionRotation = -2.5f,
        layerArt = characterLayerArt("chiikawa")
    ),
    AiCharacterArtSpec(
        characterId = "hachiware",
        peekRes = R.drawable.ai_sprite_hachiware_peek,
        standbyRes = R.drawable.ai_sprite_hachiware_standby,
        reactRes = R.drawable.ai_hachiware_happy_v3,
        reaction = SpriteReaction.NOD,
        peekScale = 1.02f,
        reactionRotation = 2f,
        layerArt = characterLayerArt("hachiware")
    ),
    AiCharacterArtSpec(
        characterId = "usagi",
        peekRes = R.drawable.ai_sprite_usagi_peek,
        standbyRes = R.drawable.ai_sprite_usagi_standby,
        reactRes = R.drawable.ai_sprite_usagi_react,
        reaction = SpriteReaction.BOUNCE,
        peekScale = 0.96f,
        reactionRotation = -3f,
        layerArt = characterLayerArt("usagi")
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
        AiSpriteAnchor.RecommendationsTab,
        AiSpriteAnchor.QuizResult,
        AiSpriteAnchor.AiFeatureHeader -> peekRes
        AiSpriteAnchor.Cloud -> standbyRes
    }
    AiSpriteMotionState.REACT -> reactRes
    AiSpriteMotionState.PREPARE,
    AiSpriteMotionState.OBSERVE,
    AiSpriteMotionState.HIDDEN,
    AiSpriteMotionState.RETREAT -> standbyRes
}
