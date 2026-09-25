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

    /**
     * 一记抖动时长。
     *
     * 200ms 时一整记 there-and-back 读起来是「抽一下」而不是「示意一下」，慢到 360ms
     * 才看得出是一次有意的摆动。两记之间那 1s 静默是停顿、不是速度，没跟着改。
     */
    const val NUDGE_SHAKE_MS: Long = 360L

    /** 一记与下一记之间的静默。 */
    const val NUDGE_GAP_MS: Long = 1_000L

    /**
     * 一轮抖几记。整轮时长 = 360×3 + 1000×2 ≈ 3.1s，落在「看一眼就懂」还没到「烦」的区间。
     *
     * 记数放在常量里而不是写死在 SearchScreen 的循环里，是为了让「配额只在整轮抖完时才 +1」
     * 这条判据有唯一的出处：改这里就是一轮，改那里就是两处。
     */
    const val NUDGE_SHAKES: Int = 3

    /** 跨进程累计预算：抖满这么多轮之后永久不再抖。 */
    const val NUDGE_BUDGET: Int = 3

    /**
     * 一记抖动的峰值角度（度）。
     *
     * `sin(2πp)·sin(πp)` 自身的极大值约 0.7698（在 tan(πp)=√2 处），除回去才让
     * 这个常量就是真机上的实际峰值 —— 不除的话它是个说谎的名字。
     */
    const val NUDGE_MAX_DEGREES: Float = 7f

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

    /**
     * 遮罩总时长。比现有 fadeIn 的 200ms 长，揭示要看得见才有效。
     *
     * 520ms 在真机上读起来仍是「一下就过去了」，看不清是从云心长出来的；按用户要求
     * 定到 1500ms。羽化带宽走 `min(cap, 0.18·edge)` 跟着 eased 缩放，扰动振幅走
     * `MAX·(1-eased)` 同样收敛到正圆，所以拉长时长不会让边缘变糊。
     * 时长翻到约三倍换来的是每帧一次全屏离屏合成的帧数同倍增长（约 90 帧 @60fps），
     * `p >= 1f` 之后立即不再挂 effect。
     */
    const val REVEAL_MS: Long = 1_500L

    /**
     * 星形扰动的**振幅常数**（起手那一帧有多歪）。
     *
     * 衰减不记在这里：随进度收到 0 是 `revealWiggle` 的职责，这个常量只管幅度上限。
     */
    const val REVEAL_WIGGLE_MAX: Float = 0.34f

    /**
     * 外沿羽化带占羽化带宽 `W` 的比例。
     *
     * 「铺满」的判据只落在这一条带上，所以 `revealBaseRadius` 的注释与 Task 4 的
     * AGSL 着色器都以它为准。着色器字符串里写不进 Kotlin 常量，那边只能抄字面量
     * `0.35`——这条常量存在的意义就是给 `SwiftieCloudRevealTest` 有个可比对的真值，
     * 不是说着色器真的引用了它。
     */
    const val REVEAL_OUTER_BAND_RATIO: Float = 0.35f

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
     * 外沿带宽只有 `REVEAL_OUTER_BAND_RATIO`·W，所以用 maxCorner + W 一并满足还多留余量。
     *
     * `eased` 钳到 0..1：过冲时不让半径长过终点值，理由同 `revealWiggle`。
     */
    fun revealBaseRadius(maxCornerPx: Float, featherPx: Float, eased: Float): Float =
        (maxCornerPx + featherPx) * eased.coerceIn(0f, 1f)

    /**
     * 羽化带宽。
     *
     * 用 `eased` 而不是 `eased²`：平方会让它到 0.9 之前几乎不出现，而前半段最需要软。
     *
     * 三个参数单位同为**像素**：`maxFeatherPx` 与 `0.18f·maxCornerPx·eased` 直接取小值，
     * dp 常量必须在传参前按 density 换算成 px，否则高 Density 屏上带宽会窄 2–3 倍。
     * `eased` 钳到 0..1，过冲也不会让带宽越过上面的比例关系。
     */
    fun revealFeatherPx(maxCornerPx: Float, eased: Float, maxFeatherPx: Float): Float {
        val edge = maxCornerPx * eased.coerceIn(0f, 1f)
        return minOf(maxFeatherPx, 0.18f * edge)
    }

    /**
     * 扰动幅度。收尾必须精确为 0，最后一帧才是标准圆。
     *
     * `eased` 钳到 0..1：`eased > 1` 会让返回值变负，星形扰动随之**静默反向**（外凸变
     * 内凹）。今天用的 FastOutSlowInEasing 不过冲所以咬不到这条，但契约不该只活在注释里。
     */
    fun revealWiggle(eased: Float): Float =
        REVEAL_WIGGLE_MAX * (1f - eased.coerceIn(0f, 1f))

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
     *
     * 别把它读成「三角形式那份是死代码」：`revealLobeSum` 是这条等价测的**参照物**，
     * 也是 Kotlin 侧按 0..2π 等分采样角建 Path 时的推荐写法。但要认清逐像素求值的
     * 那份**不是**这里——真机上画遮罩的是 `REVEAL_AGSL` 字符串里的第三份抄本，
     * 编译器与 Kotlin 测都看不见它的算术，所以它只能靠字符串钉（见 SwiftieCloudRevealTest）。
     */
    fun revealLobeSumFromCosSin(cosTheta: Float, sinTheta: Float): Float {
        val c = cosTheta
        val s = sinTheta
        // 幂次要单独算：sin5θ = 16s⁵-20s³+5s 里的 s³ 是 s 的三次幂，不是 sin3θ。
        // 拿下面的 sin3 变量去顶 s³ 是错的，偏差是 O(1) 不是 O(ε)：单看 sin5 那一项
        // 就有 0.14 · cos0.7 · max|100s³ − 60s| = 4.2831，两项合起来实测最大偏差 4.8167，
        // lobe 值域从 [-0.43, 0.69] 炸到 [-4.80, 5.03]。
        // 教训：这份 Kotlin 当时改对了，AGSL 那份没跟着改，而下面那条等价测**照样全绿**
        // —— 它比的是两份 Kotlin，碰不到字符串里的第三份。所以别指望这里红，只能靠
        // SwiftieCloudRevealTest 逐行钉 AGSL 的表达式。
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
