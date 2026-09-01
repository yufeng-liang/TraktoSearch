package com.tracktosearch.ui.screen.swiftie

/** 题面的三个态。`WRONG` 是短暂的摇晃窗口，`clearWrong()` 后回到 `INPUT`。 */
enum class SwiftieQuizPhase { INPUT, WRONG, SOLVED }

/**
 * 题面状态。不可变，每个操作返回新实例，因此可以直接放进 `remember { mutableStateOf(...) }`。
 *
 * 全部判定都在这里，Composable 只负责画和转发按键 —— 这样答案校验与提交条件能纯单测覆盖
 * （Spec §13.1）。
 *
 * @param wrongCount 累计答错次数，**永不重置**：连错 3 次的提示出现后不再收回。
 */
data class SwiftieQuizState(
    val input: String = "",
    val wrongCount: Int = 0,
    val phase: SwiftieQuizPhase = SwiftieQuizPhase.INPUT
) {
    /**
     * 满 2 位且正处在可作答态，提交键才点亮。
     *
     * `WRONG` 也要排除：那 300ms 里 `input` 还留着答错的两位（摇晃要看得见），
     * 只判长度的话用户能在摇晃窗口里对**同一个错答案**再点一次提交 ——
     * [submit] 会把 `wrongCount` 又加一次，两次真实尝试就浮出 `Her lucky number.`。
     */
    val canSubmit: Boolean
        get() = input.length == SwiftieEggController.MAX_INPUT_LENGTH &&
            phase == SwiftieQuizPhase.INPUT

    val solved: Boolean get() = phase == SwiftieQuizPhase.SOLVED

    /** 连错 [WRONG_HINT_THRESHOLD] 次后才浮出 `Her lucky number.` */
    val showLuckyHint: Boolean get() = wrongCount >= WRONG_HINT_THRESHOLD

    fun append(digit: Char): SwiftieQuizState = when {
        phase == SwiftieQuizPhase.SOLVED -> this
        !digit.isDigit() -> this
        // 摇晃还没走完就继续按：当作重新作答，而不是接在错的后面
        phase == SwiftieQuizPhase.WRONG ->
            copy(input = digit.toString(), phase = SwiftieQuizPhase.INPUT)
        input.length >= SwiftieEggController.MAX_INPUT_LENGTH -> this
        else -> copy(input = input + digit)
    }

    fun backspace(): SwiftieQuizState = when (phase) {
        SwiftieQuizPhase.SOLVED -> this
        SwiftieQuizPhase.WRONG -> copy(input = "", phase = SwiftieQuizPhase.INPUT)
        SwiftieQuizPhase.INPUT -> copy(input = input.dropLast(1))
    }

    fun submit(): SwiftieQuizState = when {
        !canSubmit -> this
        SwiftieEggController.isCorrect(input) -> copy(phase = SwiftieQuizPhase.SOLVED)
        // 输入保留，摇晃期间要看得见错的那两位；300ms 后由 clearWrong 收尾
        else -> copy(wrongCount = wrongCount + 1, phase = SwiftieQuizPhase.WRONG)
    }

    /** 答错摇晃结束后清空。对非 `WRONG` 态是空操作。 */
    fun clearWrong(): SwiftieQuizState =
        if (phase == SwiftieQuizPhase.WRONG) copy(input = "", phase = SwiftieQuizPhase.INPUT)
        else this

    companion object {
        const val WRONG_HINT_THRESHOLD: Int = 3
    }
}
