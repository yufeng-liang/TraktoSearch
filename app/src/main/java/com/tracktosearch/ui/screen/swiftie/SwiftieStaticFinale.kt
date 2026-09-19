package com.tracktosearch.ui.screen.swiftie

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.tracktosearch.ui.screen.swiftie.bracelet.BRACELET_SETTLED_MS
import com.tracktosearch.ui.screen.swiftie.bracelet.SwiftieBracelet
import com.tracktosearch.ui.screen.swiftie.bracelet.braceletHeightFor
import com.tracktosearch.ui.screen.swiftie.eras.CARD_GAP_MS
import com.tracktosearch.ui.screen.swiftie.eras.CARD_RECEDE_MS
import com.tracktosearch.ui.screen.swiftie.eras.SwiftieEra
import com.tracktosearch.ui.screen.swiftie.eras.SwiftieEraCard
import com.tracktosearch.ui.screen.swiftie.eras.SwiftieErasData

/** 签名段总长。喂这个值给签名就是写完并闪过的样子。 */
private val SIGNATURE_DONE_MS: Long = SwiftieTimeline.SIGNATURE_MS

/**
 * 这张卡片停在「停留末帧」的时刻：回落还没开始，曲目已全部点亮，扫光已走完。
 *
 * 减 1 是为了让回落进度算出来是负数并被夹到 0 —— 正好等于 `recedeStartMs` 会开始回落。
 */
private fun staticElapsedFor(era: SwiftieEra, index: Int): Long =
    SwiftieTimeline.cardDurationMs(index, era.tracks.size) - CARD_RECEDE_MS - CARD_GAP_MS - 1L

/**
 * 「减少动效」下的静态终态（Spec §11.1）。
 *
 * 一次性把签名、手链、文案、12 个时代全铺出来，用户自己滚动看。没有任何时序，
 * 也没有配乐。
 *
 * @param nickname 串在最前那条手链上的昵称，null 时只挂两条
 */
@Composable
fun SwiftieStaticFinale(nickname: String?, modifier: Modifier = Modifier) {
    Box(modifier = modifier.fillMaxSize()) {
        SwiftieWatercolorSky(modifier = Modifier.fillMaxSize())

        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .windowInsetsPadding(WindowInsets.safeDrawing)
                .padding(horizontal = 20.dp, vertical = 32.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(20.dp)
        ) {
            // 签名、手链、文案合读成一句；时代卡片各有自己的 a11y 文案，不能一起并进来
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(20.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .then(finaleSemantics(nickname))
            ) {
                // animated = false：这是一张停着的画面，闪粉挂无限动画会把帧时钟永久唤着
                SwiftieSignature(
                    elapsedInSignature = { SIGNATURE_DONE_MS },
                    animated = false
                )

                BoxWithConstraints(modifier = Modifier.fillMaxWidth()) {
                    SwiftieBracelet(
                        elapsedInBracelet = { BRACELET_SETTLED_MS },
                        interactive = false,
                        nickname = nickname,
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(braceletHeightFor(maxWidth))
                    )
                }

                SwiftieTagline(nickname = nickname, modifier = Modifier.fillMaxWidth())
            }

            Spacer(modifier = Modifier.height(4.dp))

            SwiftieErasData.ALL.forEachIndexed { index, era ->
                SwiftieEraCard(
                    era = era,
                    eraIndex = index,
                    elapsedInCard = { staticElapsedFor(era, index) },
                    durationMs = SwiftieTimeline.cardDurationMs(index, era.tracks.size),
                    // 静态列表里没有色带，从卡片自己中间长出即可
                    originFractionX = 0.5f,
                    // 外层是 verticalScroll，高度不受限，所以曲目行高不必压
                    slotHeight = Dp.Infinity,
                    modifier = Modifier
                        .fillMaxWidth()
                        .widthIn(max = 480.dp)
                )
            }
        }
    }
}
