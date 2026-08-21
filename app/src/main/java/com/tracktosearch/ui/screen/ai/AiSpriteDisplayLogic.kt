package com.tracktosearch.ui.screen.ai

/** 只有当前舞台角色已经完成激活时，才显示激活成功徽标。 */
internal fun shouldShowActivationSuccessBadge(
    characterId: String,
    activatedCharacterId: String?
): Boolean = characterId == activatedCharacterId
