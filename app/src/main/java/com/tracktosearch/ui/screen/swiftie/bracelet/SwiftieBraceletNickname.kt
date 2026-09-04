package com.tracktosearch.ui.screen.swiftie.bracelet

/** 昵称最多占几颗字母珠。再多这条就比另外两条长出去，堆叠读不出层次。 */
internal const val NICKNAME_MAX_BEADS = 9

/**
 * 昵称那条一共几颗珠：字母珠两侧补等量圆珠，补到 11 颗或 10 颗。
 *
 * 「等量」是硬要求 —— 字母不居中，这条就歪了。所以奇数个字配 11 颗、偶数个字配 10 颗，
 * 不管昵称几个字，这条的长度都一样，三条堆起来不会一人一形。珠径因此落在
 * `0.96W / (11 × 1.02)` 到 `0.96W / (10 × 1.02)`，即 0.086W–0.094W —— 比中间那条的
 * 0.084W 略大，正是「更靠前」该有的样子。
 */
internal fun nicknameStrandBeadCount(tokenCount: Int): Int {
    val padding = (NICKNAME_MAX_BEADS + 2 - tokenCount) / 2
    return tokenCount + padding * 2
}

/** 零宽连接符。emoji 组合序列全靠它把好几个人拼成一家。 */
private const val ZERO_WIDTH_JOINER = 0x200D

/**
 * 这个码点是不是「跟着前一个走」的附加符号。
 *
 * 变体选择符（U+FE0F 就是「按彩色 emoji 显示」那个）、肤色、旗帜用的 tag 字符、
 * 各种组合记号 —— 它们都不能自己占一颗珠。
 */
private fun isExtender(codePoint: Int): Boolean = when {
    codePoint == ZERO_WIDTH_JOINER -> true
    codePoint in 0xFE00..0xFE0F -> true
    codePoint in 0xE0100..0xE01EF -> true
    codePoint in 0x1F3FB..0x1F3FF -> true
    codePoint in 0xE0020..0xE007F -> true
    else -> when (Character.getType(codePoint)) {
        Character.NON_SPACING_MARK.toInt(),
        Character.ENCLOSING_MARK.toInt(),
        Character.COMBINING_SPACING_MARK.toInt() -> true
        else -> false
    }
}

/** 区域指示符。两个凑一面国旗。 */
private fun isRegionalIndicator(codePoint: Int): Boolean = codePoint in 0x1F1E6..0x1F1FF

/** 从 [start] 起这一簇结束在哪（不含）。 */
private fun clusterEnd(text: String, start: Int): Int {
    val base = text.codePointAt(start)
    var index = start + Character.charCount(base)
    if (isRegionalIndicator(base)) {
        // 两个区域指示符就是一面旗；第三个另起一簇，不然「🇨🇳🇺🇸」会粘成一颗
        if (index < text.length && isRegionalIndicator(text.codePointAt(index))) {
            index += Character.charCount(text.codePointAt(index))
        }
        return index
    }
    while (index < text.length) {
        val codePoint = text.codePointAt(index)
        if (codePoint == ZERO_WIDTH_JOINER) {
            // ZWJ 后面那个码点无论是什么都属于同一簇（👨‍👩‍👧 这种）。后面没东西了就说明这个
            // ZWJ 谁也没连上，别吃进来 —— 让它自己成一簇，然后被 isKeepable 丢掉
            val joined = index + Character.charCount(codePoint)
            if (joined >= text.length) break
            index = joined + Character.charCount(text.codePointAt(joined))
            continue
        }
        if (!isExtender(codePoint)) break
        index += Character.charCount(codePoint)
    }
    return index
}

/**
 * 这一簇留不留。
 *
 * 字母、数字、汉字留（`isLetterOrDigit` 全包）；`OTHER_SYMBOL` 是 emoji 和各种记号所在的
 * 类别，也留 —— 用户想在手链上挂一颗 🩷 就让他挂。空格、标点、零宽字符、`+` `$` `^`
 * 这些别的符号类别一律丢掉：珠子上印个逗号没有意义。
 */
private fun isKeepable(base: Int): Boolean =
    Character.isLetterOrDigit(base) || Character.getType(base) == Character.OTHER_SYMBOL.toInt()

/**
 * 逐码点转大写。
 *
 * **不用 `String.uppercase()`**：它会把 `ß` 变成两个字母（一颗珠上塞两个字），也会跟着
 * 区域设置把 `i` 变成 `İ`。逐码点的 `Character.toUpperCase` 进一个出一个，簇不会变长。
 */
private fun uppercaseCodePoints(cluster: String): String = buildString(cluster.length) {
    var index = 0
    while (index < cluster.length) {
        val codePoint = cluster.codePointAt(index)
        appendCodePoint(Character.toUpperCase(codePoint))
        index += Character.charCount(codePoint)
    }
}

/**
 * 把昵称切成一颗珠一个的字符串，最多 [limit] 颗。
 *
 * 按**字素簇**切，不是按 `Char` 也不是按码点：一个 emoji 常常是好几个码点（旗帜是两个
 * 区域指示符、👨‍👩‍👧 是三个人加两个 ZWJ、👍🏽 是手势加肤色），从中间切开会得到两颗谁也
 * 认不出的珠子。
 *
 * **不用 `java.text.BreakIterator`**：Android 上它走 ICU，JVM 上走 JDK 自己那套，而 JDK
 * 那套不认 emoji 的 ZWJ 序列 —— 单元测试会绿，真机上却把一家人切成三口。这里自己按
 * UAX #29 里用得上的那几条走，两边一致。
 */
internal fun braceletNicknameTokens(
    nickname: String?,
    limit: Int = NICKNAME_MAX_BEADS
): List<String> {
    val text = nickname ?: return emptyList()
    val tokens = mutableListOf<String>()
    var index = 0
    while (index < text.length && tokens.size < limit) {
        val end = clusterEnd(text, index)
        if (isKeepable(text.codePointAt(index))) {
            tokens += uppercaseCodePoints(text.substring(index, end))
        }
        index = end
    }
    return tokens
}
