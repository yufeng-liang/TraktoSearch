package com.tracktosearch.ui.screen.main

/** AI 精灵中心打开时，主页面底部导航必须让出完整交互区域。 */
internal fun isMainBottomNavigationVisible(aiSpriteCenterVisible: Boolean): Boolean =
    !aiSpriteCenterVisible
