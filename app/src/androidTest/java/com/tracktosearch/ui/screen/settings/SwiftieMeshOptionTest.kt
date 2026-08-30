package com.tracktosearch.ui.screen.settings

import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertDoesNotExist
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.tracktosearch.R
import com.tracktosearch.ui.theme.GlassVariant
import com.tracktosearch.ui.theme.MeshPreset
import com.tracktosearch.ui.theme.MonetAccent
import com.tracktosearch.ui.theme.VisualEffectMode
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * 背景光晕列表里「星云」按霉粉彩蛋解锁位显隐 + Lover 胶囊标签。
 *
 * 文案统一走 getString：设备语言不是英文时 values-zh / values-ja 生效，
 * 硬编码英文字面量会假失败。
 */
@RunWith(AndroidJUnit4::class)
class SwiftieMeshOptionTest {

    @get:Rule val rule = createComposeRule()

    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext

    private fun show(unlocked: Boolean) {
        rule.setContent {
            AccentColorDialog(
                currentAccent = MonetAccent.VINTAGE_TICKET,
                onAccentSelected = {},
                customAccentArgb = null,
                onCustomAccentSelected = {},
                currentMode = VisualEffectMode.BLUR,
                currentVariant = GlassVariant.CLEAR,
                onVisualEffectSelected = { _, _ -> },
                currentMeshPreset = MeshPreset.BLOOM,
                currentMeshEnabled = false,
                onMeshSelected = {},
                swiftieUnlocked = unlocked,
                onDismiss = {}
            )
        }
        // 背景光晕是折叠菜单，先展开
        rule.onNodeWithText(context.getString(R.string.bg_glow_title), substring = true)
            .performClick()
    }

    @Test
    fun locked_hidesNebulaOption() {
        show(unlocked = false)
        rule.onNodeWithText(context.getString(R.string.bg_glow_nebula)).assertDoesNotExist()
    }

    @Test
    fun unlocked_showsNebulaWithLoverBadge() {
        show(unlocked = true)
        rule.onNodeWithText(context.getString(R.string.bg_glow_nebula)).assertIsDisplayed()
        rule.onNodeWithText(context.getString(R.string.bg_glow_lover_inspired)).assertIsDisplayed()
    }
}
