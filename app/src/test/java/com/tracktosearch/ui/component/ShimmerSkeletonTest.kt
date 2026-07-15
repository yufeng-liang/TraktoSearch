package com.tracktosearch.ui.component

import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ShimmerSkeletonTest {

    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun `rememberShimmerBrush 返回非空 Brush`() {
        var brush: Brush? = null
        composeRule.setContent {
            brush = rememberShimmerBrush()
        }
        composeRule.waitForIdle()
        assertThat(brush).isNotNull()
    }

    @Test
    fun `MovieCardSkeleton 正常渲染不崩溃`() {
        composeRule.setContent {
            MovieCardSkeleton()
        }
        composeRule.waitForIdle()
        // 骨架屏渲染不崩溃即通过
    }

    @Test
    fun `DoubanHotCardSkeleton 正常渲染不崩溃`() {
        composeRule.setContent {
            DoubanHotCardSkeleton()
        }
        composeRule.waitForIdle()
    }
}
