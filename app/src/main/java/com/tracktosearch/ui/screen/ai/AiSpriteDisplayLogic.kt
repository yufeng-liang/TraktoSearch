package com.tracktosearch.ui.screen.ai

/** 只有当前舞台角色已经完成激活时，才显示激活成功徽标。 */
internal fun shouldShowActivationSuccessBadge(
    characterId: String,
    activatedCharacterId: String?,
    isAuthorized: Boolean
): Boolean = isAuthorized && characterId == activatedCharacterId

/** 只有当前选中角色就是当前账号已激活角色且授权有效时，才展示私有功能内容。 */
internal fun shouldShowActivatedCharacterContent(
    selectedCharacterId: String,
    activatedCharacterId: String?,
    isAuthorized: Boolean
): Boolean = isAuthorized && selectedCharacterId == activatedCharacterId
