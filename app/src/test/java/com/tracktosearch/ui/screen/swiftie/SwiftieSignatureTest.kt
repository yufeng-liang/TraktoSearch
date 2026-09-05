package com.tracktosearch.ui.screen.swiftie

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class SwiftieSignatureTest {

    private fun windows(
        write: FloatArray,
        pause: FloatArray,
        writeMs: Long = SIGNATURE_WRITE_MS,
        pauseMs: Long = SIGNATURE_PAUSE_TOTAL_MS
    ) = buildSignatureWindows(write, pause, writeMs, pauseMs)

    private fun table() = windows(
        SwiftieSignaturePath.WRITE_WEIGHT,
        SwiftieSignaturePath.PAUSE_WEIGHT
    )

    @Test
    fun windowsFillTheSignatureBudgetExactly() {
        val table = table()
        assertThat(table).hasSize(SwiftieSignaturePath.POINTS.size)
        assertThat(table.first().startMs).isEqualTo(0L)
        // 书写 + 停顿一分不剩，取整误差不许留在末尾
        assertThat(table.writeEndMs).isEqualTo(SIGNATURE_WRITE_MS + SIGNATURE_PAUSE_TOTAL_MS)
        // 再加收尾闪光，正好是账本给签名段的 8000ms
        assertThat(table.writeEndMs + SIGNATURE_FLASH_MS)
            .isEqualTo(SwiftieTimeline.SIGNATURE_MS)
        assertThat(table.sumOf { it.endMs - it.startMs }).isEqualTo(SIGNATURE_WRITE_MS)
    }

    @Test
    fun everyStrokeTakesTimeAndTheyNeverOverlap() {
        val table = table()
        table.forEach { assertThat(it.endMs - it.startMs).isGreaterThan(0L) }
        // 后一笔落笔不早于前一笔收笔 —— 中间那段就是抬笔的空程
        table.zipWithNext { earlier, later ->
            assertThat(later.startMs).isAtLeast(earlier.endMs)
        }
        val gaps = table.zipWithNext { earlier, later -> later.startMs - earlier.endMs }
        assertThat(gaps.sum()).isEqualTo(SIGNATURE_PAUSE_TOTAL_MS)
    }

    @Test
    fun writeTimeFollowsTheWriteWeights() {
        val table = windows(
            write = floatArrayOf(3f, 1f),
            pause = floatArrayOf(0f, 0f),
            writeMs = 4_000L,
            pauseMs = 0L
        )
        // 权重 3 倍就写 3 倍久 —— 笔速恒定才像手写
        assertThat(table[0].endMs - table[0].startMs).isEqualTo(3_000L)
        assertThat(table[1].endMs - table[1].startMs).isEqualTo(1_000L)
        assertThat(table.writeEndMs).isEqualTo(4_000L)
    }

    @Test
    fun pauseTimeFollowsThePauseWeightsAndIsIndependentOfWriteTime() {
        val table = windows(
            write = floatArrayOf(1f, 1f, 1f),
            pause = floatArrayOf(3f, 1f, 0f),
            writeMs = 3_000L,
            pauseMs = 400L
        )
        assertThat(table.map { it.endMs - it.startMs }).containsExactly(1_000L, 1_000L, 1_000L)
        // 停顿只按停顿权重分：300 / 100，与三笔等长的书写无关
        assertThat(table[1].startMs - table[0].endMs).isEqualTo(300L)
        assertThat(table[2].startMs - table[1].endMs).isEqualTo(100L)
        assertThat(table.writeEndMs).isEqualTo(3_400L)
    }

    @Test
    fun theLongestLiftIsTheOneBeforeTheDotOnTheI() {
        val glyphs = SwiftieSignaturePath.GLYPH_INDEX
        val gaps = table().zipWithNext { earlier, later -> later.startMs - earlier.endMs }
        // 同一个字形连着出现两笔，就是主体写完之后回头补那一点
        val body = glyphs.indices.first { it + 1 < glyphs.size && glyphs[it] == glyphs[it + 1] }
        assertThat(SwiftieSignaturePath.TEXT[glyphs[body]]).isEqualTo('i')
        // 笔要从字底抬到字顶，那段空程最长
        assertThat(gaps.withIndex().maxByOrNull { it.value }!!.index).isEqualTo(body)
    }

    @Test
    fun singleStrokeNeedsNoPause() {
        val table = windows(
            write = floatArrayOf(1f),
            pause = floatArrayOf(0f),
            writeMs = 900L,
            pauseMs = 500L
        )
        // 只有一笔就没有间隙，500ms 的停顿预算不许硬塞进去
        assertThat(table.writeEndMs).isEqualTo(900L)
    }

    @Test
    fun generatedTableIsSelfConsistent() {
        val count = SwiftieSignaturePath.POINTS.size
        assertThat(SwiftieSignaturePath.GLYPH_INDEX).hasLength(count)
        assertThat(SwiftieSignaturePath.WRITE_WEIGHT).hasLength(count)
        assertThat(SwiftieSignaturePath.PAUSE_WEIGHT).hasLength(count)
        // 末笔之后没有下一笔，不能有停顿
        assertThat(SwiftieSignaturePath.PAUSE_WEIGHT.last()).isEqualTo(0f)
        // 笔序按字形从左到右，同一个字形的几笔必须挨着 —— 运行时靠「下一笔换字形了」
        // 判断整字写完（见 SwiftieSignature 的 finishedGlyph）
        SwiftieSignaturePath.GLYPH_INDEX.toList().zipWithNext { earlier, later ->
            assertThat(later).isAtLeast(earlier)
        }
        SwiftieSignaturePath.GLYPH_INDEX.forEach { index ->
            assertThat(SwiftieSignaturePath.TEXT[index].isWhitespace()).isFalse()
        }
        SwiftieSignaturePath.POINTS.forEachIndexed { order, points ->
            assertThat(points.size % SwiftieSignaturePath.STRIDE).isEqualTo(0)
            assertThat(points.size / SwiftieSignaturePath.STRIDE).isAtLeast(2)
            assertThat(SwiftieSignaturePath.WRITE_WEIGHT[order]).isGreaterThan(0f)
            var previous = -1f
            for (start in points.indices step SwiftieSignaturePath.STRIDE) {
                // 半宽为 0 的点铺不出墨
                assertThat(points[start + 2]).isGreaterThan(0f)
                val t = points[start + 3]
                assertThat(t).isAtLeast(previous)
                previous = t
            }
            // 每一笔自己从 0 走到 1，运行时才好按窗口内的进度查表
            assertThat(points[3]).isEqualTo(0f)
            assertThat(previous).isEqualTo(1f)
        }
    }
}
