package com.tracktosearch.ui.screen.swiftie

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class SwiftieSignatureTest {

    /** `Taylor Swift` 去掉空格是 11 个字形。 */
    private fun evenGlyphs(count: Int = 11) = List(count) { index ->
        SignatureGlyph(fromX = index * 10f, toX = (index + 1) * 10f, tipY = 0f)
    }

    @Test
    fun strokeTableFillsTheSignatureBudgetExactly() {
        val strokes = buildSignatureStrokes(
            glyphs = evenGlyphs(),
            writeMs = SIGNATURE_WRITE_MS,
            pauseMs = SIGNATURE_PAUSE_MS
        )
        assertThat(strokes).hasSize(11)
        assertThat(strokes.first().startMs).isEqualTo(0L)
        // 11 段书写 + 10 个落笔停顿
        assertThat(strokes.last().endMs)
            .isEqualTo(SIGNATURE_WRITE_MS + SIGNATURE_PAUSE_MS * 10)
        // 再加收尾闪光，正好是账本给签名段的 8000ms
        assertThat(strokes.last().endMs + SIGNATURE_FLASH_MS)
            .isEqualTo(SwiftieTimeline.SIGNATURE_MS)
    }

    @Test
    fun wideGlyphsGetProportionallyMoreTime() {
        val strokes = buildSignatureStrokes(
            glyphs = listOf(
                SignatureGlyph(fromX = 0f, toX = 30f, tipY = 0f),
                SignatureGlyph(fromX = 30f, toX = 40f, tipY = 0f)
            ),
            writeMs = 4_000L,
            pauseMs = 0L
        )
        // 宽 3 倍就写 3 倍久 —— 笔速恒定才像手写
        assertThat(strokes[0].endMs - strokes[0].startMs).isEqualTo(3_000L)
        assertThat(strokes[1].endMs - strokes[1].startMs).isEqualTo(1_000L)
        // 段间无停顿时，末段结束就是总书写时长
        assertThat(strokes.last().endMs).isEqualTo(4_000L)
    }
}
