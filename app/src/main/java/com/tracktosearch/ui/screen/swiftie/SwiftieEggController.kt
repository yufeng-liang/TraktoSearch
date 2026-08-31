package com.tracktosearch.ui.screen.swiftie

/** 点击白云后应该走的分支。 */
enum class CloudAction {
    /** 霉粉彩蛋题面 */
    SWIFTIE_EGG,

    /** 既有定位权限链路 */
    LOCATION_PERMISSION,

    /** 既有随机 Lottie 彩蛋 */
    RANDOM_LOTTIE,

    /** 新手引导未完成，点击不做任何事、也不计数 */
    IGNORED
}

/**
 * 霉粉彩蛋的纯逻辑判定。刻意不依赖 Android / Compose，方便用普通 JVM 单测覆盖全部分支。
 */
object SwiftieEggController {

    /** 正确答案：X + 87 = 100。13 是 Taylor 的幸运数，87 是 Kelce 的球衣号。 */
    const val ANSWER: Int = 13

    /** 答案输入框最多两位，够 13 用。 */
    const val MAX_INPUT_LENGTH: Int = 2

    /**
     * 搜索框拦截词。全等匹配而非包含匹配：包含匹配会让「taylor swift 1989」这种正常搜索
     * 也被劫持。刻意不含 "13"——太短，误伤真实搜索需求。
     */
    val KEYWORDS: Set<String> = setOf(
        "taylor swift",
        "taylorswift",
        "霉霉",
        "泰勒斯威夫特",
        "lover",
        "swiftie"
    )

    fun matchesKeyword(raw: String): Boolean = raw.trim().lowercase() in KEYWORDS

    fun isCorrect(input: String): Boolean = input == ANSWER.toString()

    /** 引导未完成时连计数都不推进，否则用户走完引导第一下就已经不是「第一下」了。 */
    fun shouldCountCloudClick(onboardingCompleted: Boolean): Boolean = onboardingCompleted

    /**
     * @param clickCountBefore 本次点击**之前**已累计的白云点击数
     */
    fun resolveCloudAction(
        clickCountBefore: Int,
        quizSolved: Boolean,
        onboardingCompleted: Boolean,
        hasLocationPermission: Boolean
    ): CloudAction {
        if (!onboardingCompleted) return CloudAction.IGNORED
        if (quizSolved) {
            return if (hasLocationPermission) CloudAction.RANDOM_LOTTIE
            else CloudAction.LOCATION_PERMISSION
        }
        // 取模而非线性递增：用 ✕ 关掉题面的人（quizSolved 未消耗但 count 已 +1）
        // 过两下还能再遇到彩蛋，不会被永久挡在门外。
        return when (clickCountBefore % 3) {
            0 -> CloudAction.SWIFTIE_EGG
            1 -> if (hasLocationPermission) CloudAction.RANDOM_LOTTIE
                 else CloudAction.LOCATION_PERMISSION
            else -> CloudAction.RANDOM_LOTTIE
        }
    }
}
