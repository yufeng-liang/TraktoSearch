package com.tracktosearch.ui.screen.splash

import androidx.compose.foundation.border
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp

/**
 * 日签关键词的印章。
 *
 * 关键词是从整部片提炼的主题词，不是台词里摘的短句，所以它需要一个「盖上去」的形态
 * 而不是又一行正文。方印取自闲章：朱色细边、印文占满、四角留一线白。
 *
 * 两种形态：
 * - 方印，用于 2–4 字的 CJK 关键词。四字排成两行两字，正方形才立得住；
 *   横排四个方块字会拉成一条，看着像标签不像印。
 * - 长印，用于英文这类拉丁词。「Farewell」塞不进正方形，改成圆角长条 + 小字距，
 *   仍是朱色边框，读起来还是一枚印。
 *
 * [latin] 是印在方印下方的英文小字，CJK 语言下才有；界面本来是英文时传 null，
 * 同一个词印两遍不是设计。
 */
@Composable
internal fun QuoteSeal(
    keyword: String,
    latin: String?,
    palette: SplashPalette,
    modifier: Modifier = Modifier,
    sealSize: Dp = 46.dp,
    fontSize: TextUnit = 15.sp,
    latinFontSize: TextUnit = 8.sp,
) {
    if (keyword.isBlank()) return
    Column(
        modifier = modifier,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        if (isSquareSeal(keyword)) {
            SquareSeal(keyword, palette, sealSize, fontSize)
        } else {
            RibbonSeal(keyword, palette, fontSize)
        }
        if (latin != null) {
            Text(
                text = latin.uppercase(),
                modifier = Modifier.padding(top = 6.dp),
                color = palette.seal.copy(alpha = 0.66f),
                fontSize = latinFontSize,
                fontFamily = FontFamily.Serif,
                letterSpacing = 0.24.em,
                textAlign = TextAlign.Center,
            )
        }
    }
}

/**
 * 方印。
 *
 * 印文按行铺：两字一行、三字两行（2+1）、四字两行（2+2）。
 * 字号随字数递减，保证四字也不会顶到边框——印文碰边就失了那圈留白。
 */
@Composable
private fun SquareSeal(
    keyword: String,
    palette: SplashPalette,
    sealSize: Dp,
    fontSize: TextUnit,
) {
    val rows = sealRows(keyword)
    val scale = when (keyword.length) {
        4 -> 0.52f
        3 -> 0.56f
        else -> 1f
    }
    val charSize = fontSize * scale
    Box(
        modifier = Modifier
            .size(sealSize)
            .background(palette.seal.copy(alpha = 0.07f), RoundedCornerShape(3.dp))
            .border(1.4.dp, palette.seal.copy(alpha = 0.82f), RoundedCornerShape(3.dp)),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            rows.forEach { row ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    row.forEach { char ->
                        Text(
                            text = char.toString(),
                            color = palette.seal,
                            fontSize = charSize,
                            lineHeight = charSize * 1.06f,
                            fontFamily = FontFamily.Serif,
                            fontWeight = FontWeight.SemiBold,
                        )
                    }
                }
            }
        }
    }
}

/** 长印：拉丁关键词专用，圆角长条 + 拉开的字距 */
@Composable
private fun RibbonSeal(
    keyword: String,
    palette: SplashPalette,
    fontSize: TextUnit,
) {
    Box(
        modifier = Modifier
            .background(palette.seal.copy(alpha = 0.07f), RoundedCornerShape(3.dp))
            .border(1.2.dp, palette.seal.copy(alpha = 0.82f), RoundedCornerShape(3.dp))
            .padding(horizontal = 11.dp, vertical = 5.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = keyword.uppercase(),
            color = palette.seal,
            fontSize = fontSize * 0.62f,
            fontFamily = FontFamily.Serif,
            fontWeight = FontWeight.SemiBold,
            letterSpacing = 0.2.em,
        )
    }
}

/**
 * 方印只给 4 字以内的 CJK 关键词。
 *
 * 判据用码位而不是语言标签：日文关键词是汉字、韩文是谚文，都在 0x2E80 之上，
 * 而拉丁字母在下面。这样加第五种语言时不用回来改这里。
 */
private fun isSquareSeal(keyword: String): Boolean =
    keyword.length <= 4 && keyword.all { it.code >= CJK_START }

/** 2 字一行；3 字排 2+1；4 字排 2+2 */
private fun sealRows(keyword: String): List<String> = when (keyword.length) {
    4 -> listOf(keyword.substring(0, 2), keyword.substring(2, 4))
    3 -> listOf(keyword.substring(0, 2), keyword.substring(2, 3))
    else -> listOf(keyword)
}

private const val CJK_START = 0x2E80
