package com.tracktosearch.ui.screen.swiftie

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.tracktosearch.ui.screen.swiftie.bracelet.SwiftieBracelet

/** 落款。灯箱上的算式原样搬来，**三语均不翻译**（Spec §2.2）。 */
private const val SIGNATURE_CAPTION = "13 + 87 = 100"

/** 落款在签名写完前 800ms 开始淡入，收尾闪光时正好到位。 */
private const val CAPTION_FADE_MS: Float = 800f

/** 手链占的高度。三条堆叠 + 下垂，170dp 放得开。 */
private val BRACELET_HEIGHT = 170.dp

/**
 * 终局：签名 + 落款 + 手链（Spec §5 的 T100460–118460、§7、§8）。
 *
 * @param elapsedMs 序列全局已用毫秒。每一段自己减起点，段落之间不互相传时间
 */
@Composable
fun SwiftieFinaleStage(
    elapsedMs: () -> Long,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .windowInsetsPadding(WindowInsets.safeDrawing)
            // 整体偏上：给手链留出下垂的余地，也让签名落在视觉重心上（Spec §7）
            .padding(bottom = 48.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        SwiftieSignature(
            elapsedInSignature = { elapsedMs() - SwiftieTimeline.SIGNATURE_START }
        )

        Spacer(modifier = Modifier.height(24.dp))

        SwiftieMarkerText(
            text = SIGNATURE_CAPTION,
            fontSize = 14.sp,
            color = SwiftiePalette.RoyalBlue,
            modifier = Modifier.graphicsLayer {
                val captionStart =
                    SwiftieTimeline.BRACELET_START - CAPTION_FADE_MS.toLong()
                alpha = ((elapsedMs() - captionStart) / CAPTION_FADE_MS).coerceIn(0f, 1f)
            }
        )

        Spacer(modifier = Modifier.height(28.dp))

        SwiftieBracelet(
            elapsedInBracelet = { elapsedMs() - SwiftieTimeline.BRACELET_START },
            modifier = Modifier
                .fillMaxWidth()
                .height(BRACELET_HEIGHT)
        )
    }
}
