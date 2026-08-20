package com.tracktosearch.data.util.mcu.palettes

import com.tracktosearch.data.util.mcu.hct.Hct

/**
 * Tonal palette：从 hue + chroma 派生任意 tone 色，与 Material You 标准一致。
 * 用法：TonalPalette.fromInt(seedArgb).tone(40.0) → primary 浅色
 */
class TonalPalette private constructor(
    private val hue: Double,
    private val chroma: Double,
) {
    /** 取指定 tone 值的色（0..100），返回 ARGB int */
    fun tone(tone: Double): Int = Hct.from(hue, chroma, tone).toInt()

    /** 取指定 tone 的 Hct 对象 */
    fun get(tone: Double): Hct = Hct.from(hue, chroma, tone)

    companion object {
        fun fromHueAndChroma(hue: Double, chroma: Double) = TonalPalette(hue, chroma)
        fun fromHct(hct: Hct) = TonalPalette(hct.hue, hct.chroma)
        fun fromInt(argb: Int) = fromHct(Hct.fromInt(argb))
    }
}
