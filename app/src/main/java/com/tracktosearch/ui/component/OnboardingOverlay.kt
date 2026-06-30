package com.tracktosearch.ui.component

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Fill
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import com.tracktosearch.R
import kotlin.math.roundToInt

/**
 * 新手引导遮罩层
 *
 * @param targetRects 每个引导步骤对应的目标区域（像素坐标，相对于窗口）。
 *                    传入 Rect.Zero 表示该步骤为纯信息提示，不高亮任何区域，气泡居中显示。
 * @param titles 每步的标题
 * @param descriptions 每步的描述
 * @param onComplete 全部步骤完成后的回调
 * @param onSkip 用户点击"跳过"后的回调
 */
@Composable
fun OnboardingOverlay(
    targetRects: List<Rect>,
    titles: List<String>,
    descriptions: List<String>,
    onComplete: () -> Unit,
    onSkip: () -> Unit,
    onStepChanged: (stepIndex: Int) -> Unit = {}
) {
    var currentStep by remember { mutableIntStateOf(0) }
    val totalSteps = targetRects.size

    if (currentStep >= totalSteps) {
        onComplete()
        return
    }

    val targetRect = targetRects[currentStep]
    val density = LocalDensity.current
    val highlightPaddingPx = with(density) { 8.dp.toPx() }
    val cornerRadiusPx = with(density) { 12.dp.toPx() }
    val bubbleGapPx = with(density) { 12.dp.toPx() }
    // 气泡预估高度
    val estimatedBubbleHeightPx = with(density) { 160.dp.toPx() }

    // 是否为纯信息步骤（无高亮目标）
    val isInfoStep = targetRect == Rect.Zero

    // 高亮区域（仅非信息步骤需要）
    val highlightRect = if (!isInfoStep) {
        Rect(
            left = (targetRect.left - highlightPaddingPx).coerceAtLeast(0f),
            top = (targetRect.top - highlightPaddingPx).coerceAtLeast(0f),
            right = targetRect.right + highlightPaddingPx,
            bottom = targetRect.bottom + highlightPaddingPx
        )
    } else {
        Rect.Zero
    }

    BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
        val screenWidthPx = maxWidth.value * density.density
        val screenHeightPx = maxHeight.value * density.density

        val bubbleTopPx: Float

        if (isInfoStep) {
            // 信息步骤：居中显示
            bubbleTopPx = screenHeightPx * 0.35f
        } else {
            // 有目标：根据空间判断上下
            val placeAbove = (highlightRect.bottom + bubbleGapPx + estimatedBubbleHeightPx) > screenHeightPx

            bubbleTopPx = if (placeAbove) {
                highlightRect.top - bubbleGapPx - estimatedBubbleHeightPx
            } else {
                highlightRect.bottom + bubbleGapPx
            }

            // 安全边界
            bubbleTopPx.coerceIn(16f, screenHeightPx - estimatedBubbleHeightPx - 16f)
        }

        // 半透明遮罩 + 挖洞（拦截触摸事件，防止穿透点击）
        Canvas(
            modifier = Modifier
                .fillMaxSize()
                .pointerInput(Unit) {}
        ) {
            val scrimColor = Color.Black.copy(alpha = 0.6f)

            if (!isInfoStep && highlightRect != Rect.Zero) {
                val cutoutRect = RoundRect(
                    left = highlightRect.left,
                    top = highlightRect.top,
                    right = highlightRect.right,
                    bottom = highlightRect.bottom,
                    cornerRadius = CornerRadius(cornerRadiusPx, cornerRadiusPx)
                )

                drawPath(
                    path = Path().apply {
                        addRect(Rect(Offset.Zero, size))
                        addRoundRect(cutoutRect)
                    },
                    color = scrimColor,
                    style = Fill,
                )
            } else {
                // 纯信息步骤：全屏遮罩，无挖洞
                drawRect(color = scrimColor)
            }
        }

        // 气泡卡片
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 24.dp)
                .offset { IntOffset(0, bubbleTopPx.roundToInt()) },
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(
                        color = MaterialTheme.colorScheme.surface,
                        shape = RoundedCornerShape(12.dp)
                    )
                    .padding(16.dp)
            ) {
                Text(
                    text = titles[currentStep],
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.primary
                )
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = descriptions[currentStep],
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurface
                )
                Spacer(modifier = Modifier.height(16.dp))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "${currentStep + 1}/$totalSteps",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(modifier = Modifier.weight(1f))
                    OutlinedButton(
                        onClick = onSkip,
                        contentPadding = ButtonDefaults.ContentPadding
                    ) {
                        Text(stringResource(R.string.onboarding_skip))
                    }
                    Spacer(modifier = Modifier.width(8.dp))
                    Button(
                        onClick = {
                            currentStep++
                            onStepChanged(currentStep)
                        },
                        contentPadding = ButtonDefaults.ContentPadding
                    ) {
                        Text(
                            if (currentStep == totalSteps - 1)
                                stringResource(R.string.onboarding_done)
                            else
                                stringResource(R.string.onboarding_next)
                        )
                    }
                }
            }
        }
    }
}
