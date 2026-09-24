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

    // ─────────────────── 白云暗示抖动 ───────────────────

    /** 停留多久起一轮暗示。 */
    const val NUDGE_DWELL_MS: Long = 1_800L

    /** 一记抖动时长。 */
    const val NUDGE_SHAKE_MS: Long = 200L

    /** 两记之间的静默。 */
    const val NUDGE_GAP_MS: Long = 1_000L

    /** 跨进程累计预算：抖满这么多轮之后永久不再抖。 */
    const val NUDGE_BUDGET: Int = 3

    /**
     * 一记抖动的峰值角度（度）。
     *
     * `sin(2πp)·sin(πp)` 自身的极大值约 0.7698（在 tan(πp)=√2 处），除回去才让
     * 这个常量就是真机上的实际峰值 —— 不除的话它是个说谎的名字。
     */
    const val NUDGE_MAX_DEGREES: Float = 4f

    /**
     * 该不该给这个用户抖一轮暗示。两个未解锁位缺一不可，理由见函数体上方注释。
     *
     * 只看 `!quizSolved` 会给存量迁移用户白抖（`SwiftieEggStorage.migrateLegacyNebulaUser`
     * 只写 unlocked）；只看 `!unlocked` 会在 `quizSolved=true && unlocked=false`
     * 的半写窗口里抖，而那个状态下题面已经还不出来了。
     */
    fun shouldShowCloudNudge(
        unlocked: Boolean,
        quizSolved: Boolean,
        nudgeShown: Int
    ): Boolean = !unlocked && !quizSolved && nudgeShown < NUDGE_BUDGET

    /**
     * 一记抖动在给定相位的旋转角度。
     *
     * `sin(2πp)` 给两摆等幅反向，`sin(πp)` 是把两端压回 0 的包络。两端都归零才
     * 不会在开头/结尾留一个歪着的云。
     */
    fun cloudNudgeDegrees(phase: Float): Float {
        val p = phase.coerceIn(0f, 1f)
        val pi = kotlin.math.PI.toFloat()
        val swing = kotlin.math.sin(2f * pi * p)
        val envelope = kotlin.math.sin(pi * p)
        return NUDGE_MAX_DEGREES * (swing * envelope / 0.7698f)
    }

    // ─────────────────── 揭示遮罩几何 ───────────────────

    /** 遮罩总时长。比现有 fadeIn 的 200ms 长，揭示要看得见才有效。 */
    const val REVEAL_MS: Long = 520L

    /** 扰动幅度上限，随进度线性收到 0。 */
    const val REVEAL_WIGGLE_MAX: Float = 0.34f

    /**
     * 这一次打开到底走不走揭示。
     *
     * 抽成纯函数是为了能在 JVM 里测。「其他入口保持淡入」是 spec 明写的要求，而
     * 它原本只能靠 instrumentation 断言一个看不见摸不着的转场类型 —— 判据落成
     * 三个布尔的与之后，覆盖就变成一行断言。
     */
    fun shouldRevealFromCloud(
        openedFromCloud: Boolean,
        hasCloudCoordinates: Boolean,
        reducedMotion: Boolean
    ): Boolean = openedFromCloud && hasCloudCoordinates && !reducedMotion

    /**
     * 主半径。
     *
     * 终点**不是** `maxCorner` 而是 `maxCorner + feather`：最后一帧如果只长到刚好
     * 够到四角，四角正好落在羽化带中心、alpha≈0.5，屏幕上留一圈半透明的边。
     * 外沿带宽只有 0.35W，所以用 maxCorner + W 一并满足还多留 0.65W 余量。
     */
    fun revealBaseRadius(maxCornerPx: Float, featherPx: Float, eased: Float): Float =
        (maxCornerPx + featherPx) * eased

    /**
     * 羽化带宽。
     *
     * 用 `eased` 而不是 `eased²`：平方会让它到 0.9 之前几乎不出现，而前半段最需要软。
     */
    fun revealFeatherPx(maxCornerPx: Float, eased: Float, maxFeatherPx: Float): Float {
        val edge = maxCornerPx * eased
        return minOf(maxFeatherPx, 0.18f * edge)
    }

    /** 扰动幅度。收尾必须精确为 0，最后一帧才是标准圆。 */
    fun revealWiggle(eased: Float): Float = REVEAL_WIGGLE_MAX * (1f - eased)

    /**
     * 三朵互质频率叠加的形状函数。频率取 2/3/5：两两互质，叠出来「不均匀但没有
     * 明显周期」。系数与相位是常量表而不是随机数 —— 必须可单测、可复现、每帧同形
     * （一帧里只有半径在长，形状不变）。
     *
     * 绝对值合计 0.34+0.22+0.14 = 0.70，配合 REVEAL_WIGGLE_MAX 得
     * 1 + A·S ≥ 0.762 > 0，所以 r(θ) 恒正是合法星形域。
     */
    fun revealLobeSum(theta: Float): Float =
        0.34f * kotlin.math.sin(2f * theta) +
            0.22f * kotlin.math.sin(3f * theta + 1.9f) +
            0.14f * kotlin.math.sin(5f * theta + 0.7f)

    /**
     * 上面那个函数的倍角展开形式，供 AGSL 使用。
     *
     * AGSL 侧拿不到可依证的 `atan`，所以那里不调三角函数：c = dx/r 就是 cosθ、
     * s = dy/r 就是 sinθ，倍角全是多项式，而 sin(kθ+φ) 的 cosφ/sinφ 直接烤成常量。
     * 两个形式的等价由 `revealLobeSum_polynomialFormMatchesTrigonometricForm` 钉住。
     */
    fun revealLobeSumFromCosSin(cosTheta: Float, sinTheta: Float): Float {
        val c = cosTheta
        val s = sinTheta
        // 幂次要单独算：sin5θ = 16s⁵-20s³+5s 里的 s³ 是 s 的三次幂，不是 sin3θ。
        // 直接拿下面的 sin3 变量去顶 s³ 是错的（实测最大误差 4.8，整条曲线全跑偏）。
        val s2 = s * s
        val s3 = s2 * s
        val s5 = s3 * s2
        val c2 = c * c
        val c3 = c2 * c
        val c5 = c3 * c2
        val l2 = 2f * c * s                                        // φ=0，只剩正弦项
        val sin3 = 3f * s - 4f * s3
        val cos3 = 4f * c3 - 3f * c
        val l3 = -0.32329f * sin3 + 0.94630f * cos3                 // φ=1.9
        val sin5 = 16f * s5 - 20f * s3 + 5f * s
        val cos5 = 16f * c5 - 20f * c3 + 5f * c
        val l5 = 0.764842f * sin5 + 0.644218f * cos5                // φ=0.7
        return 0.34f * l2 + 0.22f * l3 + 0.14f * l5
    }
}
