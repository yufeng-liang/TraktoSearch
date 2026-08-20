package com.tracktosearch.ui.theme

import androidx.compose.runtime.staticCompositionLocalOf

/** 全局视觉材质模式。新安装或无法识别持久化值时使用 Backdrop Glass。 */
enum class VisualEffectMode(val storageValue: String) {
    BLUR("blur"),
    GLASS("glass");

    companion object {
        fun fromStorageValue(value: String?): VisualEffectMode {
            return entries.firstOrNull { it.storageValue == value } ?: GLASS
        }
    }
}

/**
 * 旧版本留下的 Glass 风格字段，仅为 DataStore 兼容保留。
 * 当前产品只有一套 Glass，因此所有旧值都归一到 CLEAR。
 */
enum class GlassVariant(val storageValue: String) {
    CLEAR("clear"),
    FOCUSED("focused");

    companion object {
        fun fromStorageValue(value: String?): GlassVariant {
            return CLEAR
        }
    }
}

val LocalVisualEffectMode = staticCompositionLocalOf { VisualEffectMode.GLASS }
val LocalGlassVariant = staticCompositionLocalOf { GlassVariant.CLEAR }
