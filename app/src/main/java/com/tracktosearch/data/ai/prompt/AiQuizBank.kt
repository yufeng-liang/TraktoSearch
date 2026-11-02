// 来源：auth-worker/src/ai/quiz-bank.ts（全量 409 行，移植纯函数部分）。
// 当天 AI 题库的「确定性」机制：抽片、quizId 派生、用量计数与预算判定。
//
// 只放纯函数与用量记录类型，不碰题库正文。服务端存储 IO（KV readBankUsage/writeBankUsage、
// D1 createD1BankUsageStore、usageKey/USAGE_TABLE）不移植——App 端用量记账由本地存储实现，
// 但预算判定与序号推进必须沿用同一套纯函数，保证与 TS 端逐位可复算。
//
// 为什么需要它：产品去掉换片后，当天每个用户固定 2 套 13 题，题库可以提前算好，
// 用户点「开始」时命中当天题库即可秒开。预生成的前提是「可复算」：同一
// (用户, 日期, 套序号) 任何时刻重新算都必须得到同一份选题与同一个 quizId，
// 所以本文件不用随机数，全部走自实现的稳定哈希（FNV-1a + 收尾混淆）。
package com.tracktosearch.data.ai.prompt

/** 每天为用户预生成的题库套数。 */
const val QUIZ_SETS_PER_DAY = 2

/** 题库与用量记录都按「日期 + 36 小时」过期，覆盖跨天边界与客户端时钟偏差。 */
const val QUIZ_BANK_TTL_SECONDS = 36 * 60 * 60

/** 一天毫秒数：把 ISO 日期折算成「天数」后做相差判定。 */
private const val DAY_MS = 24L * 60 * 60 * 1000

/** 当天题库的用量记录（对应 TS 的 BankUsage）。 */
data class BankUsage(
    /** 已经玩掉的套数 */
    val usedSets: Int,
    /** 已经成功生成并落库的套数 */
    val generatedSets: Int,
    /** 生成尝试次数（含失败），用于预算判定 */
    val attempts: Int,
)

/**
 * 空用量常量。
 * TS 里强调 readBankUsage 必须返回副本防止共享对象被原地累加改坏；
 * Kotlin data class 字段不可变，共享常量本身安全，emptyUsage 仅为与 TS 调用点一一对应。
 */
val EMPTY_BANK_USAGE: BankUsage = BankUsage(usedSets = 0, generatedSets = 0, attempts = 0)

/** 每次新建一份空用量（与 TS 的 emptyUsage 对应）。 */
private fun emptyUsage(): BankUsage = EMPTY_BANK_USAGE

/**
 * 校验客户端传来的本地日期：必须是 YYYY-MM-DD，且与 todayIso 相差不超过 1 天
 * （容忍时区/跨零点抖动）。
 *
 * 严格拒绝「格式对但日历上不存在」的日期（如 2026-02-30、2026-13-01）：
 * JS 侧靠回读三字段挡 Date 归一化，Kotlin 侧 LocalDate.of 对不存在日期直接抛异常，效果一致。
 * 放行会让用量记录落到错误的日期键上。todayIso 自身不合法时一律返回 false（拿不到基准就没法判断偏移）。
 */
fun isLocalDate(value: Any?, todayIso: String): Boolean {
    val day = parseIsoDay(value)
    val today = parseIsoDay(todayIso)
    if (day == null || today == null) return false
    return kotlin.math.abs(day - today) <= 1
}

/**
 * 确定性抽片：按 seed 从已看列表里稳定抽出 count 部。
 *
 * 做法：给每部片算一个「seed 派生排序键」（键只取决于 seed 与片名，与列表顺序无关），
 * 按键升序取前 count 部 —— 不是简单取前 N 部，所以同一列表在不同 seed 下抽到的片子不同。
 * 命中集合最后按 watched 的原顺序输出：同 seed 下逐元素稳定，也不会打乱用户已看列表的顺序，
 * count 覆盖全量时等价于返回入参的浅拷贝。
 *
 * 保证：同 (watched, seed, count) 逐字节可复算；不修改入参；count 超过列表长度时返回全部。
 *
 * @param titleOf 从元素里取片名（对应 TS 约束的 { title } 字段）
 */
fun <T> selectDailyMovies(
    watched: List<T>,
    seed: String,
    count: Int,
    titleOf: (T) -> String,
): List<T> {
    val movies = watched.toList()
    val take = if (count > 0) count else 0
    if (take == 0 || movies.isEmpty()) return emptyList()
    if (take >= movies.size) return movies

    // JS 的排序键是无符号 32 位数：转成 0..2^32-1 的 Long 再比较，保证与 TS 排序逐位一致
    // （直接用 Int 比较会让高位为 1 的键排到最前，抽中集合就与 TS 分叉了）
    val ranked = movies
        .mapIndexed { index, movie -> Triple(index, movieSortKey(seed, titleOf(movie)), movie) }
        .sortedWith(compareBy({ it.second }, { it.first }))

    val picked = HashSet<Int>()
    for (entry in ranked) {
        if (picked.size >= take) break
        picked.add(entry.first)
    }
    return movies.filterIndexed { index, _ -> picked.contains(index) }
}

/**
 * 当天的题库种子：同一 (用户, 日期, 套序号) 必须得到同一结果，用于复算槽位表与抽片。
 *
 * 格式与 quiz-slots.planUnitSlots(seed) 约定的 `{friendId}:{date}:{setIndex}` 一致。
 */
fun bankSeed(friendId: String, date: String, setIndex: Int): String =
    "$friendId:$date:$setIndex"

/**
 * 当天第 N 套题库的 quizId：形如 `bank-{date}-{setIndex}-{hash8}`。
 *
 * 稳定、可复算、不同套不同 id：hash8 取种子（含 friendId）的 32 位稳定哈希，
 * 所以不同用户即使同一天同一套序号，quizId 也不同 —— 它只用于定位缓存，不承载权限。
 */
fun deriveBankQuizId(friendId: String, date: String, setIndex: Int): String =
    "bank-$date-$setIndex-" + digest8(bankSeed(friendId, date, setIndex))

/**
 * 预算判定：attempts 达到 setsPerDay*2 就不再生成（避免异常客户端刷爆额度）。
 *
 * 边界取「达到即停」：attempts === setsPerDay*2 时为 false，即一天最多尝试 2 倍套数。
 */
fun canGenerateSet(usage: BankUsage?, setsPerDay: Int = QUIZ_SETS_PER_DAY): Boolean =
    countField(usage?.attempts) < resolveSetsPerDay(setsPerDay) * 2

/**
 * 该给用户第几套：usedSets + 1；超过 setsPerDay 时继续加（走按需生成的冷路径）。
 *
 * setsPerDay 只说明「前几套是当天预生成的」，序号本身不回绕也不截断 —— 预生成槽位用完后
 * 继续递增，调用方按「序号是否超过 setsPerDay」决定走按需生成；额度另由 canGenerateSet 把关。
 * （TS 里的 void setsPerDay 表明该参数仅为签名兼容保留。）
 */
fun nextSetIndex(usage: BankUsage?, setsPerDay: Int = QUIZ_SETS_PER_DAY): Int =
    countField(usage?.usedSets) + 1

/**
 * 该套是否属于「当天预生成的套」：序号在 setsPerDay 以内（1 起数）。
 *
 * 预生成是系统行为，只覆盖当天固定的前 setsPerDay 套；用户玩完这些套还想再来一局时，
 * 多出来的套改走按需生成（等待页有进度可看），否则每进一次出题页就会白烧一套上游调用，
 * 而那一套用户可能永远不玩。判定只吃序号，与是否已达尝试预算（canGenerateSet）是两回事。
 */
fun isPreGeneratedSet(setIndex: Int, setsPerDay: Int = QUIZ_SETS_PER_DAY): Boolean {
    val idx = countField(setIndex)
    return idx >= 1 && idx <= resolveSetsPerDay(setsPerDay)
}

/** 把 JSON 解析结果收敛成合法用量；非对象、数组、null 全部退化成空值。 */
internal fun parseBankUsage(value: Any?): BankUsage {
    if (value !is Map<*, *>) return emptyUsage()
    return BankUsage(
        usedSets = countField(value["usedSets"]),
        generatedSets = countField(value["generatedSets"]),
        attempts = countField(value["attempts"]),
    )
}

/** 计数字段归一：非数字、非整数、负数、NaN/Infinity 一律按 0 处理（JSON 解析结果可能是 Double）。 */
internal fun countField(value: Any?): Int {
    val n = value as? Number ?: return 0
    val d = n.toDouble()
    // Number.isSafeInteger 的上界 2^53-1；Double.toInt() 对超界值饱和到 Int 边界
    return if (d.isFinite() && d >= 0 && d == kotlin.math.floor(d) && d <= 9007199254740991.0) {
        d.toInt()
    } else 0
}

/** setsPerDay 兜底：非正整数（TS 里含 undefined，Kotlin 由默认参数覆盖）一律回落到 QUIZ_SETS_PER_DAY。 */
private fun resolveSetsPerDay(value: Int): Int =
    if (value > 0) value else QUIZ_SETS_PER_DAY

/**
 * 影片的排序键：只取决于 seed 与片名，与片子在列表里的位置无关，
 * 所以「同一批片 + 同一 seed」永远选中同一批（列表顺序变了也不影响抽中集合）。
 * 返回按无符号 32 位语义（0..2^32-1 的 Long）。
 */
private fun movieSortKey(seed: String, title: String): Long =
    mix32(fnv1a32(seed + "\u0000" + title)).toLong() and 0xFFFFFFFFL

/** 8 位十六进制摘要，用于 quizId 尾缀；Integer.toHexString 按 32 位无符号输出（负数同样得 8 位）。 */
private fun digest8(input: String): String =
    Integer.toHexString(mix32(fnv1a32(input))).padStart(8, '0')

/**
 * FNV-1a 32 位哈希：逐 UTF-16 码元异或后乘 FNV 质数。
 * Kotlin 的 for-in 天然按 UTF-16 码元迭代（等价 charCodeAt），Int 乘法天然 32 位截断（等价 Math.imul），
 * 两个实现逐位一致——这是与 TS 端对拍的前提：禁止改用 String.hashCode，禁止按代码点（codePoint）迭代。
 */
private fun fnv1a32(input: String): Int {
    var hash = -0x7ee3623b // 0x811c9dc5
    for (ch in input) {
        hash = hash xor ch.code
        hash *= 0x01000193
    }
    return hash
}

/**
 * 32 位收尾混淆（murmur3 finalizer 结构）：FNV-1a 对「只差末尾一两个字符」的输入，
 * 低位相关性偏强，而本模块的输入恰好共享很长的前缀（同一用户/日期的 seed），
 * 过一道雪崩让相邻 seed 的排序键充分散开，抽片分布才不会总是贴着同几部片。
 * ushr 即 JS 的 >>>（无符号右移）。
 */
private fun mix32(value: Int): Int {
    var hash = value
    hash = hash xor hash.ushr(16)
    hash *= 0x7feb352d
    hash = hash xor hash.ushr(15)
    hash *= 0x846ca68b
    hash = hash xor hash.ushr(16)
    return hash
}

/**
 * 解析严格 YYYY-MM-DD 并折算成 UTC 天数；格式不符或日历上不存在都返回 null。
 * JS 用「回读三个字段」挡掉 2026-02-30 / 2026-13-01 这类会被 Date 悄悄归一化的日期；
 * Kotlin 的 LocalDate.of 对不存在日期直接抛 DateTimeException，口径一致。
 */
private fun parseIsoDay(value: Any?): Long? {
    if (value !is String) return null
    val match = Regex("""^(\d{4})-(\d{2})-(\d{2})$""").matchEntire(value) ?: return null

    val year = match.groupValues[1].toInt()
    val month = match.groupValues[2].toInt()
    val day = match.groupValues[3].toInt()
    return try {
        java.time.LocalDate.of(year, month, day).toEpochDay()
    } catch (_: java.time.DateTimeException) {
        null
    }
}
