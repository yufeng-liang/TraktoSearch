// 来源：auth-worker/src/ai/quiz-slots.ts（全量 272 行）。
// 出题槽位表：把「一次让模型写 4 个单元 + 13 道题」拆成「每一格各自生成、哪一格不合格只补哪一格」。
// 本文件只放静态规则与纯函数，不做任何 IO —— 纯函数才能被单测覆盖，也才能让本地闭环统计
// 「单元一次通过率 / 题位一次通过率 / 修复后通过率」这些指标。
//
// 为什么需要它：整包生成时任意一处违规都会让 13 题全部作废（实测 5 次真实生成 0 次通过）。
// 拆成槽位后，违规的代价从「整包重来」降到「只补这一格」。
package com.tracktosearch.data.ai.prompt

/** 单元槽位数量：固定 4，让 13 个题位能稳定分配（每概念 3-4 题），学科覆盖要求也天然满足。 */
const val QUIZ_UNIT_SLOT_COUNT = 4

/** 题位槽位数量：与产品定义的 13 题一一对应。 */
const val QUIZ_QUESTION_SLOT_COUNT = 13

/** 角度类型：同一批单元之间、同一概念下的题位之间都不得重复，用来防同质化。 */
val QUIZ_ANGLE_TYPES: List<String> = listOf("象征", "因果", "对比", "应用", "机制解释", "证据辨识")

/**
 * 可选学科组：只有这 5 个「不需要外部来源」的组。强证据学科所在的
 * science_and_nature / technology_and_future / life_and_career 一律不入表，
 * society_and_institution 也按同样口径剔除了法学。
 */
private val QUIZ_UNIT_GROUPS: List<Pair<String, List<String>>> = listOf(
    "film_expression" to listOf("电影学", "叙事学", "摄影与视觉设计", "剪辑与声音", "表演与戏剧"),
    "people_and_mind" to listOf("心理学", "认知科学", "发展心理学", "教育学"),
    "society_and_institution" to listOf("社会学", "人类学", "传播学", "政治学", "经济学", "犯罪学"),
    "history_and_culture" to listOf("历史", "文化研究", "语言学与符号学", "宗教神话与民俗", "音乐与艺术史"),
    "philosophy_and_ethics" to listOf("哲学与伦理学", "马克思主义哲学"),
)

/**
 * FNV-1a 32 位稳定哈希：纯函数、不碰随机数，同一 seed 恒得同一偏移量，
 * 「按 (用户, 日期, 套序号) 复算槽位表」才成立。
 * 逐 char 与 TS 对齐：charCodeAt 取 UTF-16 码元，乘法用 Int 溢出语义等价 Math.imul 的 32 位截断。
 */
private fun hashSeed(seed: String): Int {
    var hash = -0x7ee3623b // 0x811c9dc5
    for (ch in seed) {
        hash = hash xor ch.code
        // Int 乘法天然按 32 位截断，等价 Math.imul；再转无符号比较由调用方处理
        hash *= 0x01000193
    }
    return hash
}

/** 从 start 起在 list 上轮转取 count 个；count ≤ list.size 时两两不重复。 */
private fun <T> rotateTake(list: List<T>, start: Int, count: Int): List<T> {
    val picked = ArrayList<T>(count)
    for (i in 0 until count) {
        picked.add(list[(start + i) % list.size])
    }
    return picked
}

/** evidence 里简介行的前缀，与 handler 构造 evidence 时一致；决定这部片有没有可锚定的简介原文。 */
private const val SYNOPSIS_LINE_PREFIX = "简介："

/**
 * 候选片优先级：按「材料厚度」降序排列，供 planUnitSlots 逐个槽位分配。
 *
 * 排序键依次是「有没有简介」「材料总字符数」「片名」：单元与题位的证据锚定都要求引用简介原文
 * （题位题干必须引用简介里 4 字以上连续片段），有简介的片子才拿得出可引用的材料；
 * 同分时按片名兜底，保证同一批候选片在任何环境下排出同一个顺序。
 *
 * @param movies 候选片（只需要片名与 evidence 两个字段）
 */
fun rankMovieTitlesForSlots(
    movies: List<Pair<String, List<String>?>>,
): List<String> {
    val seen = HashSet<String>()
    val ranked = ArrayList<Triple<String, Int, Int>>() // (title, synopsis, weight)
    for ((rawTitle, evidence) in movies) {
        val title = rawTitle.trim()
        if (title.isEmpty() || !seen.add(title)) continue
        val lines = evidence ?: emptyList()
        ranked.add(
            Triple(
                title,
                if (lines.any { it.startsWith(SYNOPSIS_LINE_PREFIX) }) 1 else 0,
                lines.sumOf { it.length },
            ),
        )
    }
    ranked.sortWith(
        compareByDescending<Triple<String, Int, Int>> { it.second }
            .thenByDescending { it.third }
            .thenBy { it.first },
    )
    return ranked.map { it.first }
}

/**
 * 单元槽位：生成前就把「这一格写哪个学科组、允许哪些学科、什么角度」钉死。
 *
 * 学科只用「不需要外部来源」的组与学科：强证据学科（物理/化学/生物与生态/医学与公共卫生/
 * 天文学/地理与气候/计算机与人工智能/数学与统计/工程与材料/建筑与城市规划/法学/
 * 军事学与战略/体育科学/食品科学）要求 external_fact 且 source.evidence 非空，
 * 实测是整包作废的高发点，槽位表从源头避开。
 */
data class UnitSlot(
    /** 槽位序号 1..4 */
    val index: Int,
    /** 受控 subjectGroup */
    val subjectGroup: String,
    /** 该组内允许的学科白名单（全部取自受控目录，且都不是强证据学科） */
    val allowedSubjects: List<String>,
    /** 本单元的角度类型 */
    val angleType: String,
    /** 本槽位钉死的影片（片单为空时缺省，退回「单元自选影片」） */
    val movieTitle: String? = null,
)

/** 题位槽位：题型、难度、归属单元、概念、角度全部预先分配，模型只负责把这一格写成题。 */
data class QuestionSlot(
    /** 槽位序号 1..13 */
    val index: Int,
    val type: String,
    val difficulty: String,
    /** 归属单元序号 1..4（按 surviving unit 的实际序号） */
    val unitIndex: Int,
    /** 该单元的概念：本格必须围绕它出题 */
    val concept: String,
    /** 本格角度类型 */
    val angleType: String,
    /** 同一概念其他题位的角度类型（喂给模型，明确「别人已经用了这些角度」） */
    val siblingAngles: List<String>,
    /** 本槽位相对所在单元的序号（第几个用这个概念），从 1 开始 */
    val conceptOrdinal: Int,
)

/**
 * 规划 4 个单元槽位。
 *
 * 规则：
 * - 4 个槽位取 4 个不同的 subjectGroup（从「不需要外部来源」的 5 个组里按 seed 轮转挑），
 *   保证学科覆盖 ≥3 的既有硬校验必然满足。
 * - 4 个角度类型互不重复（6 选 4，按 seed 轮转）。
 * - 传入 movieTitles 时第 i 个槽位钉死第 i 部片：13 个题位跟着单元走，单元固定在 4 部不同片上，
 *   一套必然覆盖 4 部片。片名不是 seed 派的（片单本身已按 seed 抽过一轮），换一套片单就换一批片。
 * - 同一 seed 必须得到完全相同的结果（确定性）：预生成按 (用户, 日期, 套序号) 复算槽位表，
 *   不需要把槽位表存库。
 *
 * @param seed 形如 `{friendId}:{date}:{setIndex}` 的确定性种子
 * @param movieTitles 候选片优先级顺序（见 rankMovieTitlesForSlots），缺省即不指定影片
 */
fun planUnitSlots(seed: String, movieTitles: List<String> = emptyList()): List<UnitSlot> {
    val hash = hashSeed(seed).toLong() and 0xFFFFFFFFL
    // 低位定组偏移，高位定角度偏移：5 与 6 互质，两个偏移各自独立轮转
    val groupOffset = (hash % QUIZ_UNIT_GROUPS.size).toInt()
    val angleOffset = ((hash / QUIZ_UNIT_GROUPS.size) % QUIZ_ANGLE_TYPES.size).toInt()

    val groups = rotateTake(QUIZ_UNIT_GROUPS, groupOffset, QUIZ_UNIT_SLOT_COUNT)
    val angles = rotateTake(QUIZ_ANGLE_TYPES, angleOffset, QUIZ_UNIT_SLOT_COUNT)
    // 候选片不足 4 部时按位次轮转复用：宁可重复，也不能有空槽位拿不到片
    val picked = ArrayList<String>(QUIZ_UNIT_SLOT_COUNT)
    for (title in movieTitles) {
        val trimmed = title.trim()
        if (trimmed.isEmpty() || picked.contains(trimmed)) continue
        picked.add(trimmed)
        if (picked.size >= QUIZ_UNIT_SLOT_COUNT) break
    }

    return groups.mapIndexed { position, (subjectGroup, allowed) ->
        UnitSlot(
            index = position + 1,
            subjectGroup = subjectGroup,
            // 复制一份，避免调用方拿到表内列表后改动影响后续调用
            allowedSubjects = allowed.toList(),
            angleType = angles[position],
            movieTitle = if (picked.isEmpty()) null else picked[position % picked.size],
        )
    }
}

/**
 * 题位形状表：题型与难度按 1..13 的位次写死，改这里等于改产品定义，测试会锁住计数。
 * 合计 10 single / 2 multiple / 1 short，4 easy / 5 medium / 4 hard。
 */
private val QUESTION_SLOT_SHAPES: List<Pair<String, String>> = listOf(
    "single" to "easy", // 1
    "single" to "easy", // 2
    "single" to "easy", // 3
    "single" to "easy", // 4
    "single" to "medium", // 5
    "single" to "medium", // 6
    "single" to "medium", // 7
    "single" to "medium", // 8
    "multiple" to "medium", // 9
    "single" to "hard", // 10
    "single" to "hard", // 11
    "multiple" to "hard", // 12
    "short" to "hard", // 13
)

/**
 * 规划 13 个题位槽位。
 *
 * 题型与难度按位次写死（合计 10 single / 2 multiple / 1 short，4 easy / 5 medium / 4 hard）：
 * - 第 1-4 位：single / easy
 * - 第 5-9 位：single ×4 + multiple ×1 / medium
 * - 第 10-13 位：single ×2 + multiple ×1 + short ×1 / hard
 *
 * 概念按位次对给定单元轮转分配（单位元数 3 或 4 都要能跑）；同一概念下各题位的角度类型
 * 互不重复，并把它作为 siblingAngles 传给模型防撞车。
 *
 * @param units 已通过校验的单元（至少 3 个），只需 index 与 concept
 */
fun planQuestionSlots(
    units: List<Pair<Int, String>>,
): List<QuestionSlot> {
    require(units.isNotEmpty()) { "planQuestionSlots 需要至少 3 个已通过校验的单元，收到的是空数组" }

    val angleCount = QUIZ_ANGLE_TYPES.size
    // 概念首次在 units 中出现的位置 = 该概念角度轮转的起点：
    // 不同概念的起点因此各不相同，不会所有概念都从「象征」开始
    val conceptStart = HashMap<String, Int>()
    units.forEachIndexed { position, (_, concept) ->
        if (!conceptStart.containsKey(concept)) conceptStart[concept] = position
    }
    // 概念 -> 已排出的角度（按题位序号升序），同时用于回填 siblingAngles
    val anglesByConcept = HashMap<String, MutableList<String>>()

    val slots = ArrayList<QuestionSlot>(QUIZ_QUESTION_SLOT_COUNT)
    for (position in 0 until QUIZ_QUESTION_SLOT_COUNT) {
        val (unitIndex, concept) = units[position % units.size]
        val (type, difficulty) = QUESTION_SLOT_SHAPES[position]
        val start = conceptStart[concept] ?: 0
        val used = anglesByConcept.getOrPut(concept) { mutableListOf() }

        // 按起点依次取用，跳过该概念已用过的角度，保证同概念内互不重复
        var angle = QUIZ_ANGLE_TYPES[start % angleCount]
        for (step in 0 until angleCount) {
            val candidate = QUIZ_ANGLE_TYPES[(start + step) % angleCount]
            if (!used.contains(candidate)) {
                angle = candidate
                break
            }
        }
        used.add(angle)

        slots.add(
            QuestionSlot(
                index = position + 1,
                type = type,
                difficulty = difficulty,
                unitIndex = unitIndex,
                concept = concept,
                angleType = angle,
                siblingAngles = emptyList(), // 占位，等全部角度定完后回填
                conceptOrdinal = used.size,
            ),
        )
    }

    // 回填同概念其他题位的角度：conceptOrdinal 就是本格在该概念角度表里的 1 基下标
    return slots.map { slot ->
        val used = anglesByConcept[slot.concept] ?: emptyList()
        slot.copy(
            siblingAngles = used.filterIndexed { position, _ -> position != slot.conceptOrdinal - 1 },
        )
    }
}
