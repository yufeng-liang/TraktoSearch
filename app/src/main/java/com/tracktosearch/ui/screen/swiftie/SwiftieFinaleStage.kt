package com.tracktosearch.ui.screen.swiftie

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
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
import com.tracktosearch.ui.screen.swiftie.bracelet.braceletHeightFor

/** 落款。灯箱上的算式原样搬来，**三语均不翻译**（Spec §2.2）。 */
internal const val SIGNATURE_CAPTION = "13 + 87 = 100"

/** 落款在签名写完前 800ms 开始淡入，收尾闪光时正好到位。 */
private const val CAPTION_FADE_MS: Float = 800f

/**
 * 终局：签名 + 落款 + 手链（Spec §5 的 T99410–118000、§7、§8）。
 *
 * 排在倒滑之前 —— 配乐末尾那句 Lover 要留给绽放（见 `SwiftieTimeline` 的类注释）。
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

        // 插槽高度按宽度算，见 braceletHeightFor —— 下垂量是宽度的比例
        BoxWithConstraints(modifier = Modifier.fillMaxWidth()) {
            SwiftieBracelet(
                elapsedInBracelet = { elapsedMs() - SwiftieTimeline.BRACELET_START },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(braceletHeightFor(maxWidth))
            )
        }
    }
}
