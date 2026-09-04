package com.tracktosearch.ui.screen.swiftie

import androidx.compose.ui.graphics.Path

/**
 * 0..1 单位方框里的心形轮廓。
 *
 * 灯箱上浮的心、Lover 母题里的心、Lover 曲目行右侧那颗点亮的心、雪景球里升起的心
 * 用的是**同一条**轮廓。之前各写一份，共四处 —— 调形状时必然漏掉其中几处。
 *
 * 调用方自己 `scale` / `translate` 到目标尺寸，本函数不关心大小。
 */
fun unitHeartPath(): Path = Path().apply {
    moveTo(0.5f, 0.92f)
    cubicTo(-0.18f, 0.52f, 0.16f, 0.02f, 0.5f, 0.30f)
    cubicTo(0.84f, 0.02f, 1.18f, 0.52f, 0.5f, 0.92f)
    close()
}
