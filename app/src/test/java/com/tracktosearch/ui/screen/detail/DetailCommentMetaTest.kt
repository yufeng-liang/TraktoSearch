package com.tracktosearch.ui.screen.detail

import com.google.common.truth.Truth.assertThat
import java.util.Locale
import org.junit.Test

/**
 * 豆瓣短评底部信息行（时间 / 点赞数）的纯函数测试。
 *
 * 这两段文案是详情页评论卡片的可见文本，不依赖 Compose 运行时，所以直接按函数断言。
 */
class DetailCommentMetaTest {

    @Test
    fun commentTimeLabel_trimsSeconds() {
        assertThat(commentTimeLabel("2008-02-27 21:43:23")).isEqualTo("2008-02-27 21:43")
    }

    @Test
    fun commentTimeLabel_blankReturnsNull() {
        assertThat(commentTimeLabel("   ")).isNull()
    }

    @Test
    fun commentTimeLabel_unparsableFallsBackToRaw() {
        assertThat(commentTimeLabel("unknown")).isEqualTo("unknown")
    }

    @Test
    fun formatCommentLikes_groupsByThousandSeparator() {
        val previous = Locale.getDefault()
        try {
            Locale.setDefault(Locale.US)
            assertThat(formatCommentLikes(26473)).isEqualTo("26,473")
            assertThat(formatCommentLikes(7)).isEqualTo("7")
        } finally {
            Locale.setDefault(previous)
        }
    }
}
