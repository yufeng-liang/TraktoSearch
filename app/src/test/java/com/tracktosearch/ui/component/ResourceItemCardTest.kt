package com.tracktosearch.ui.component

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.longClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import com.tracktosearch.data.remote.dto.DiskType
import com.tracktosearch.data.remote.dto.ResourceItem
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import androidx.compose.ui.graphics.Color

@RunWith(AndroidJUnit4::class)
class ResourceItemCardTest {

    @get:Rule
    val composeRule = createComposeRule()

    private fun createResourceItem(
        name: String = "测试资源",
        diskType: DiskType = DiskType.BAIDU,
        source: String = "pansou",
        fileCount: Int = 0,
        fileDate: String = ""
    ): ResourceItem {
        return ResourceItem(
            name = name,
            diskType = diskType,
            fileSize = "1GB",
            fileDate = fileDate,
            fileCount = fileCount,
            status = "",
            url = "https://example.com",
            source = source
        )
    }

    @Test
    fun `source tag colors use fixed light and dark palettes`() {
        assertThat(sourceTagColors("pansou", isDark = false).background)
            .isEqualTo(Color(0xFFE8C8B8))
        assertThat(sourceTagColors("pansou", isDark = true).background)
            .isEqualTo(Color(0xFF6F453A))
        assertThat(sourceTagColors("panhub", isDark = false).background)
            .isEqualTo(Color(0xFFC9D4C5))
        assertThat(sourceTagColors("panhub", isDark = true).background)
            .isEqualTo(Color(0xFF405747))
        assertThat(sourceTagColors("zreso", isDark = false).background)
            .isEqualTo(Color(0xFFC7D2DF))
        assertThat(sourceTagColors("zreso", isDark = true).background)
            .isEqualTo(Color(0xFF43566B))
    }

    @Test
    fun `custom source color is stable by source id and changes with theme`() {
        val light = sourceTagColors("custom-source-a", isDark = false)
        val dark = sourceTagColors("custom-source-a", isDark = true)

        assertThat(sourceTagColors("custom-source-a", isDark = false)).isEqualTo(light)
        assertThat(dark.background).isNotEqualTo(light.background)
        assertThat(dark.content).isNotEqualTo(light.content)
    }

    @Test
    fun `source tag label uses custom name only for custom source`() {
        assertThat(sourceTagLabel("pansou", "自定义名称")).isEqualTo("PanSou")
        assertThat(sourceTagLabel("custom-source-a", "自定义名称")).isEqualTo("自定义名称")
        assertThat(sourceTagLabel("custom-source-a", null)).isEqualTo("custom-source-a")
    }

    @Test
    fun `name 正常显示`() {
        composeRule.setContent {
            ResourceItemCard(
                item = createResourceItem(name = "测试电影资源"),
                onClick = {}
            )
        }
        composeRule.onNodeWithText("测试电影资源").assertIsDisplayed()
    }

    @Test
    fun `diskType 正常显示`() {
        composeRule.setContent {
            ResourceItemCard(
                item = createResourceItem(diskType = DiskType.ALI),
                onClick = {}
            )
        }
        // Robolectric 默认加载 values/(英文)，DiskType.ALI -> "Ali"
        composeRule.onNodeWithText("Ali").assertIsDisplayed()
    }

    @Test
    fun `点击卡片触发 onClick`() {
        var clicked = false
        composeRule.setContent {
            ResourceItemCard(
                item = createResourceItem(),
                onClick = { clicked = true }
            )
        }
        composeRule.onNodeWithText("测试资源").performClick()
        composeRule.waitForIdle()
        assertThat(clicked).isTrue()
    }

    @Test
    fun `isViewed_true 时显示已查看状态`() {
        composeRule.setContent {
            ResourceItemCard(
                item = createResourceItem(),
                isViewed = true,
                onClick = {}
            )
        }
        composeRule.waitForIdle()
        // Robolectric 默认加载 values/(英文)，resource_viewed -> "Viewed"
        composeRule.onNodeWithText("Viewed").assertIsDisplayed()
    }

    @Test
    fun `isViewed_false 时正常显示`() {
        composeRule.setContent {
            ResourceItemCard(
                item = createResourceItem(),
                isViewed = false,
                onClick = {}
            )
        }
        composeRule.onNodeWithText("测试资源").assertIsDisplayed()
    }

    @Test
    fun `index 参数不影响渲染`() {
        composeRule.setContent {
            ResourceItemCard(
                item = createResourceItem(),
                onClick = {},
                index = 99
            )
        }
        composeRule.onNodeWithText("测试资源").assertIsDisplayed()
    }

    @Test
    fun `长按触发 onLongClick`() {
        var longClicked = false
        composeRule.setContent {
            ResourceItemCard(
                item = createResourceItem(),
                onClick = {},
                onLongClick = { longClicked = true }
            )
        }
        composeRule.onNodeWithText("测试资源").performTouchInput { longClick() }
        composeRule.waitForIdle()
        assertThat(longClicked).isTrue()
    }

    @Test
    fun `onLongClick 为 null 时长按不崩溃`() {
        composeRule.setContent {
            ResourceItemCard(
                item = createResourceItem(),
                onClick = {},
                onLongClick = null
            )
        }
        composeRule.onNodeWithText("测试资源").performTouchInput { longClick() }
        composeRule.waitForIdle()
        // 不崩溃即通过
    }
}
