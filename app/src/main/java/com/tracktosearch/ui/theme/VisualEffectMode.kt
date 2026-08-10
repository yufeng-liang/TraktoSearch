package com.tracktosearch.ui.theme

import androidx.compose.runtime.staticCompositionLocalOf

/** 全局视觉材质模式。缺少或无法识别持久化值时回退到稳定的 Haze 模糊。 */
enum class VisualEffectMode(val storageValue: String) {
    BLUR("blur"),
    GLASS("glass");

    companion object {
        fun fromStorageValue(value: String?): VisualEffectMode {
            return entries.firstOrNull { it.storageValue == value } ?: BLUR
        }
    }
}

val LocalVisualEffectMode = staticCompositionLocalOf { VisualEffectMode.BLUR }
