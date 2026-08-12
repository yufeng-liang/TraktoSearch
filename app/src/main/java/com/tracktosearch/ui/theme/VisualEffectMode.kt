package com.tracktosearch.ui.theme

import androidx.compose.runtime.staticCompositionLocalOf

/** 全局视觉材质模式。缺少或无法识别持久化值时回退到稳定的 Haze 模糊。*/
enum class VisualEffectMode(val storageValue: String) {
    BLUR("blur"),
    GLASS("glass");

    companion object {
        fun fromStorageValue(value: String?): VisualEffectMode {
            return entries.firstOrNull { it.storageValue == value } ?: BLUR
        }
    }
}

/** Glass 模式的细分风格。缺省或未知值都回退到 CLEAR，避免污染用户选择。*/
enum class GlassVariant(val storageValue: String) {
    CLEAR("clear"),
    FOCUSED("focused");

    companion object {
        fun fromStorageValue(value: String?): GlassVariant {
            return entries.firstOrNull { it.storageValue == value } ?: CLEAR
        }
    }
}

val LocalVisualEffectMode = staticCompositionLocalOf { VisualEffectMode.BLUR }
val LocalGlassVariant = staticCompositionLocalOf { GlassVariant.CLEAR }
