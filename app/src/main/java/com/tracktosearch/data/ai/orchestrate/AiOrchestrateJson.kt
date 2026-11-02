package com.tracktosearch.data.ai.orchestrate

import com.tracktosearch.data.ai.AiWatchedTitleDto
import com.tracktosearch.data.ai.prompt.DAILY_NO_SOURCE_NAME
import com.tracktosearch.data.ai.prompt.DAILY_TRUSTED_SOURCE_HOSTS
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull

/**
 * BYOK 编排层的模型输出解析与共享文本工具。
 *
 * 对拍 TS：auth-worker/src/ai/mimo.ts 的 parseAssistantJson / repairAssistantJson，
 * 以及 handler.ts / daily-knowledge.ts 里散落的取值与文本门槛辅助。
 * 全部函数只做纯文本/JSON 处理，不发起网络请求；失败一律抛 AiProviderException。
 */
object AiOrchestrateJson {

    /** 宽松 JSON：模型输出字段多余/缺失都不炸，校验交给上层 normalize。 */
    val json: Json = Json {
        ignoreUnknownKeys = true
        isLenient = true
        encodeDefaults = false
    }

    // ------------------------------------------------------------------
    // 模型输出解析与修复
    // ------------------------------------------------------------------

    /**
     * 解析模型返回的 JSON 文本；等价 TS parseAssistantJson：
     * 去 ```json 围栏 → JSON.parse → 失败走四步修复（每步后重试，成功即返）。
     * 全部失败抛 INVALID_RESPONSE（对应 TS INVALID_AI_OUTPUT 'AI JSON response is invalid'）。
     */
    fun parseModelJson(raw: String): JsonElement {
        val stripped = stripJsonFence(raw.trim())
        val direct = runCatching { json.parseToJsonElement(stripped) }.getOrNull()
        if (direct != null) return direct
        // 四步修复顺序叠加：每一步修完都尝试解析，命中即返回（对拍 repairAssistantJson）。
        var current = quoteBareStringValues(stripped)
        runCatching { json.parseToJsonElement(current) }.getOrNull()?.let { return it }
        current = escapeStrayInnerQuotes(current)
        runCatching { json.parseToJsonElement(current) }.getOrNull()?.let { return it }
        current = unescapeStructuralQuotes(current)
        runCatching { json.parseToJsonElement(current) }.getOrNull()?.let { return it }
        current = closeTruncatedJson(current)
        runCatching { json.parseToJsonElement(current) }.getOrNull()?.let { return it }
        throw AiOrchestrateError.invalidResponse("AI JSON response is invalid")
    }

    /** 去掉模型常见的 Markdown 代码围栏（```json ... ```）。 */
    private fun stripJsonFence(text: String): String {
        if (!text.startsWith("```")) return text
        val withoutHead = text.removePrefix("```json").removePrefix("```JSON").removePrefix("```")
        return withoutHead.removeSuffix("```").trim()
    }

    /** 第一步：把「"键":裸值"」修成「"键":"裸值"」（模型漏开引号）。 */
    private fun quoteBareStringValues(text: String): String {
        // 用 ["] 结尾避免 raw string 以引号收尾的解析歧义。
        val regex = Regex("""("[A-Za-z_][A-Za-z0-9_]*")\s*:\s*([^"{[\s\d\-.tfn\]}][^"]*?)["]""")
        return regex.replace(text) { match ->
            match.groupValues[1] + ":\"" + match.groupValues[2] + "\""
        }
    }

    /** 第二步：字符串内部多出的裸引号补转义（逐字符状态机，对拍 escapeStrayInnerQuotes）。 */
    private fun escapeStrayInnerQuotes(text: String): String {
        val builder = StringBuilder(text.length + 16)
        var inString = false
        var i = 0
        while (i < text.length) {
            val c = text[i]
            if (c == '\\' && inString && i + 1 < text.length) {
                builder.append(c).append(text[i + 1])
                i += 2
                continue
            }
            if (c == '"') {
                if (!inString) {
                    inString = true
                    builder.append(c)
                } else {
                    // 向后跳过空白看下一个有效字符：是 , } ] : 或结尾才算合法收尾，否则补转义。
                    var j = i + 1
                    while (j < text.length && text[j].isWhitespace()) j++
                    val closes = j >= text.length || text[j] in ",}]:"
                    if (closes) {
                        inString = false
                        builder.append(c)
                    } else {
                        builder.append('\\').append('"')
                    }
                }
            } else {
                builder.append(c)
            }
            i++
        }
        return builder.toString()
    }

    /** 第三步：结构位置上多余的反斜杠转义去掉（\", \} 等误转义）。 */
    private fun unescapeStructuralQuotes(text: String): String =
        Regex("""\\(["\\])(\s*[,}\]])""").replace(text, "$1$2")

    /** 第四步：截断 JSON 补闭合（对拍 closeTruncatedJson：栈配平 + 键名残尾回退）。 */
    private fun closeTruncatedJson(text: String): String {
        val start = text.indexOf('{')
        if (start < 0) return text
        val stack = ArrayDeque<Char>()
        var inString = false
        var i = start
        var lastSafe = start
        while (i < text.length) {
            val c = text[i]
            if (inString) {
                if (c == '\\') {
                    i += 2
                    continue
                }
                if (c == '"') inString = false
            } else when (c) {
                '"' -> inString = true
                '{', '[' -> stack.addLast(c)
                '}', ']' -> stack.removeLastOrNull()
                ',' -> if (stack.isNotEmpty()) lastSafe = i
            }
            i++
        }
        var body = text.substring(start)
        if (inString) body += '"'
        // 截断点落在键名或冒号后：回退到最后一个逗号/左括号再补，避免残尾变成非法 JSON。
        val danglingKey = Regex("""(?:,\s*)?"[^"]*"\s*:\s*$""")
        if (danglingKey.containsMatchIn(body)) {
            body = body.substring(0, lastSafe - start + 1)
        }
        val reopen = ArrayDeque<Char>()
        var open = false
        i = 0
        while (i < body.length) {
            val c = body[i]
            if (open) {
                if (c == '\\') {
                    i += 2
                    continue
                }
                if (c == '"') open = false
            } else when (c) {
                '"' -> open = true
                '{', '[' -> reopen.addLast(c)
                '}', ']' -> reopen.removeLastOrNull()
            }
            i++
        }
        while (reopen.isNotEmpty()) {
            body += if (reopen.removeLast() == '{') '}' else ']'
        }
        return body
    }

    // ------------------------------------------------------------------
    // JSON 取值辅助（对拍 handler.ts 通用取值函数，语义逐条一致）
    // ------------------------------------------------------------------

    /** 断言是对象：不是对象抛 INVALID_RESPONSE。 */
    fun requireRecord(value: JsonElement?, label: String): JsonObject {
        val record = value as? JsonObject
            ?: throw AiOrchestrateError.invalidResponse("$label must be an object")
        return record
    }

    fun isRecord(value: JsonElement?): Boolean = value is JsonObject

    fun isString(value: JsonElement?): Boolean = value is JsonPrimitive && value.isString

    fun isIntArray(value: JsonElement?): Boolean =
        value is JsonArray && value.all { (it as? JsonPrimitive)?.intOrNull != null }

    /** 取对象里第一个命中键的原始值；都没有返回 null。 */
    fun firstField(record: JsonObject, fields: List<String>): JsonElement? {
        for (field in fields) {
            val element = record[field]
            if (element != null && element !is JsonNull) return element
        }
        return null
    }

    /**
     * 必填字符串：等价 TS requiredText——第一个「非空字符串」命中，
     * trim 后截 4000；全空抛 INVALID_RESPONSE（AI label is invalid）。
     */
    fun requiredText(record: JsonObject, fields: List<String>, label: String): String {
        for (field in fields) {
            val value = (record[field] as? JsonPrimitive)?.contentOrNull
            if (value != null && value.isNotBlank()) return value.trim().take(4000)
        }
        throw AiOrchestrateError.invalidResponse("AI $label is invalid")
    }

    /** 可选字符串：等价 TS optionalText——命中取 trim 后前 maxLength；没有返回 null。 */
    fun optionalText(record: JsonObject, fields: List<String>, maxLength: Int = 4000): String? {
        for (field in fields) {
            val value = (record[field] as? JsonPrimitive)?.contentOrNull
            if (value != null && value.isNotBlank()) return value.trim().take(maxLength)
        }
        return null
    }

    /** 必填字符串数组：等价 TS requiredTextArray——trim 截 240，最多 8 项，空项丢弃。 */
    fun requiredTextArray(record: JsonObject, fields: List<String>, label: String): List<String> {
        for (field in fields) {
            val array = record[field] as? JsonArray
            if (array != null && array.all { it is JsonPrimitive && it.isString }) {
                return array.mapNotNull { (it as JsonPrimitive).contentOrNull?.trim()?.take(240) }
                    .filter { it.isNotEmpty() }
                    .take(8)
            }
        }
        throw AiOrchestrateError.invalidResponse("AI $label is invalid")
    }

    /** 可选字符串数组：等价 TS readOptionalAiStringArray——缺省返回空表，非法抛错。 */
    fun readOptionalAiStringArray(value: JsonElement?): List<String> {
        if (value == null || value is JsonNull) return emptyList()
        val array = value as? JsonArray
            ?: throw AiOrchestrateError.invalidResponse("AI answer keywords are invalid")
        if (array.any { it !is JsonPrimitive || !it.isString }) {
            throw AiOrchestrateError.invalidResponse("AI answer keywords are invalid")
        }
        return array.mapNotNull { (it as JsonPrimitive).contentOrNull?.trim()?.take(80) }
            .filter { it.isNotEmpty() }
            .take(8)
    }

    /** 多选题答案数组：1-4 个字符串，否则抛错（对拍 readAnswerArray）。 */
    fun readAnswerArray(value: JsonElement?): List<String> {
        val array = value as? JsonArray
            ?: throw AiOrchestrateError.invalidResponse("AI multiple answer is invalid")
        if (array.isEmpty() || array.size > 4 || array.any { it !is JsonPrimitive || !it.isString }) {
            throw AiOrchestrateError.invalidResponse("AI multiple answer is invalid")
        }
        return array.map { (it as JsonPrimitive).content }
    }

    /** 不透明 id：字母数字与 ._:- 且 1-96 位（对拍 readAiOpaqueId）。 */
    fun readAiOpaqueId(value: JsonElement?, field: String): String {
        val text = (value as? JsonPrimitive)?.contentOrNull
        if (text == null || !Regex("^[A-Za-z0-9._:-]{1,96}$").matches(text)) {
            throw AiOrchestrateError.invalidResponse("AI $field is invalid")
        }
        return text
    }

    /** 整数取值：不是整数返回 null（对拍 optionalInteger）。 */
    fun optionalInteger(value: JsonElement?): Int? =
        (value as? JsonPrimitive)?.intOrNull

    /** 布尔取值。 */
    fun optionalBoolean(value: JsonElement?): Boolean? =
        (value as? JsonPrimitive)?.booleanOrNull

    /** 数值取值：有限浮点或 null（对拍 optionalNumber）。 */
    fun optionalNumber(value: JsonElement?): Double? =
        (value as? JsonPrimitive)?.doubleOrNull

    /** 数组断言：不是数组抛 INVALID_RESPONSE。 */
    fun requireArray(value: JsonElement?, label: String): JsonArray {
        val array = value as? JsonArray
            ?: throw AiOrchestrateError.invalidResponse("$label must be an array")
        return array
    }

    /** 逐项取对象：数组里出现非对象项抛 INVALID_RESPONSE。 */
    fun requireRecordArray(value: JsonElement?, label: String): List<JsonObject> {
        val array = requireArray(value, label)
        return array.mapIndexed { index, element ->
            element as? JsonObject
                ?: throw AiOrchestrateError.invalidResponse("$label item ${index + 1} must be an object")
        }
    }

    // ------------------------------------------------------------------
    // 文本归一化与门槛判定
    // ------------------------------------------------------------------

    /** trim 后把连续空白折叠成单空格（对拍 daily requireText 的归一化）。 */
    fun collapseSpaces(value: String): String = value.trim().replace(Regex("\\s+"), " ")

    /** 只去空白（对拍 normalizeForQuizMatch）。 */
    fun normalizeForQuizMatch(value: String): String = value.replace(Regex("\\s+"), "")

    /** 小写 + 去空白与全部标点符号（对拍 normalizeSemanticKey / normalizeForMatch）。 */
    fun normalizeSemanticKey(value: String): String =
        value.lowercase(java.util.Locale.SIMPLIFIED_CHINESE)
            .replace(Regex("[\\s\\p{P}\\p{S}]+"), "")

    /** 小写（en-US）+ 去空白与标点，用于 daily 全文匹配（对拍 normalizeForMatch）。 */
    fun normalizeForMatch(value: String): String =
        value.lowercase(java.util.Locale.ENGLISH)
            .replace(Regex("[\\s\\p{P}\\p{S}]+"), "")

    /** 去句子尾部标点（对拍 stripTrailingSentencePunctuation）。 */
    fun stripTrailingSentencePunctuation(value: String): String =
        value.trimEnd().replace(Regex("[。．.!?！？]+$"), "").trimEnd()

    /** 中文检索归一化：去空白与常见中英标点后小写（对拍 normalizedSearchText）。 */
    fun normalizedSearchText(value: String): String =
        value.lowercase(java.util.Locale.SIMPLIFIED_CHINESE)
            .replace(Regex("[\\s“”‘’《》【】()（）:：，。！？、.!?-]+"), "")

    /** quiz 证据切片：长度 6→4 递减、步长=size、最多 40 片、纯数字丢弃（对拍 evidenceFragments）。 */
    fun quizEvidenceFragments(value: String): List<String> {
        val normalized = normalizedSearchText(value)
        if (normalized.length < 4) return emptyList()
        val fragments = ArrayList<String>()
        for (size in 6 downTo 4) {
            if (normalized.length < size) continue
            var index = 0
            while (index + size <= normalized.length && fragments.size < 40) {
                val fragment = normalized.substring(index, index + size)
                if (!fragment.matches(Regex("^\\d+$"))) fragments.add(fragment)
                index += size
            }
        }
        return fragments.distinct()
    }

    /**
     * 证据包含判定：source 归一化后至少 4 字，且 text 整体包含或任一切片命中。
     * quiz / daily 共用同一形状，但切片步长不同——daily 用 [dailyEvidenceFragments]。
     */
    fun containsEvidenceText(text: String, source: String, fragments: List<String>): Boolean {
        val normalizedText = normalizedSearchText(text)
        val normalizedSource = normalizedSearchText(source)
        if (normalizedSource.length < 4) return false
        if (normalizedText.contains(normalizedSource)) return true
        return fragments.any { normalizedText.contains(it) }
    }

    /** daily 证据切片：长度 6→4 递减、每档内步长 1 全切片（对拍 concreteEvidenceFragments）。 */
    fun dailyEvidenceFragments(value: String): List<String> {
        val normalized = normalizedSearchText(value)
        if (normalized.length < 4) return emptyList()
        val fragments = ArrayList<String>()
        for (size in 6 downTo 4) {
            if (normalized.length < size) continue
            for (index in 0..normalized.length - size) {
                fragments.add(normalized.substring(index, index + size))
            }
        }
        return fragments.distinct()
    }

    // ------------------------------------------------------------------
    // 泛化套话与高风险内容门槛
    // ------------------------------------------------------------------

    /** 通用方法论套话表（对拍 GENERIC_METHODOLOGY_PHRASES，四语言全量）。 */
    private val GENERIC_METHODOLOGY_PHRASES = listOf(
        "避免过度解读", "不要过度解读", "如何避免过度解读", "怎么避免过度解读",
        "避免把主观联想当成影片事实", "把主观联想当成影片事实", "避免主观联想", "不要主观臆断",
        "向朋友推荐", "推荐给朋友", "如何向朋友推荐", "怎么向朋友推荐",
        "再看一遍", "重新看一遍", "再看一次", "重看一遍", "看第二遍", "二刷一遍", "复盘一遍", "回顾一遍",
        "avoid over interpreting", "avoid overinterpreting", "do not over interpret", "not overinterpret",
        "avoid reading too much into", "recommend this film to a friend", "recommend it to a friend",
        "watch it again", "watch again", "rewatch", "re-watch",
        "過剰解釈を避ける", "友達に勧める", "友だちに勧める", "もう一度見る", "見直す",
        "과잉해석을 피하", "친구에게 추천하", "다시 보", "복습하",
    )

    /**
     * 学科与「通用方法类内容」错配判定（对拍 isGenericMethodologyMismatch）：
     * 元认知学科本身就在讲学习方法，直接放行；其余学科命中套话即判废。
     */
    fun isGenericMethodologyMismatch(text: String, subject: String, metacognitionSubjects: Set<String>): Boolean {
        if (subject in metacognitionSubjects) return false
        val normalized = normalizeForMatch(text)
        return GENERIC_METHODOLOGY_PHRASES.any { normalized.contains(normalizeForMatch(it)) }
    }

    /** 泛化套话标记（对拍 isGenericText 的短语表）。 */
    private val GENERIC_TEXT_PHRASES = listOf(
        "人性的复杂性", "勇敢面对困难", "社会对个体的影响", "具有教育意义",
        "human complexity", "face difficulties bravely", "societal influence", "educational significance",
        "人間の複雑さ", "困難に立ち向かう", "社会の影響", "教育的意義",
        "인간의 복잡성", "어려움에 직면", "사회의 영향", "교육적 의미",
    )

    /**
     * 去掉已看片名后仍剩不下多少实词、或命中泛化套话 → 判为空泛模板
     * （对拍 isGenericText：split(title).join('') 逐片剔除）。
     */
    fun isGenericText(text: String, movieTitles: List<String>): Boolean {
        var withoutTitles = text
        for (title in movieTitles) {
            if (title.isNotBlank()) withoutTitles = withoutTitles.split(title).joinToString("")
        }
        if (normalizeForMatch(withoutTitles).length < 6) return true
        val normalized = normalizeForMatch(text)
        return GENERIC_TEXT_PHRASES.any { normalized.contains(normalizeForMatch(it)) }
    }

    /** quiz 题干套话标记（对拍 isGenericQuizText，任一子串命中即泛化）。 */
    private val GENERIC_QUIZ_TEXT_MARKERS = listOf(
        "哪些角度有助于理解作品", "人物如何在处境中作出选择", "如何更好地理解作品",
        "回到具体场面寻找依据", "哪种方法最不容易", "哪种做法更可靠", "哪种准备最能",
        "如何避免过度解读", "怎样避免过度解读", "向朋友推荐", "重看", "回想",
    )

    fun isGenericQuizText(prompt: String): Boolean =
        GENERIC_QUIZ_TEXT_MARKERS.any { prompt.contains(it) }

    /** 高风险操作指引正则组（对拍 containsHighRiskActionGuidance，中英日韩四语言）。 */
    private val HIGH_RISK_PATTERNS = listOf(
        // 中文
        Regex("现实个体.{0,16}(诊断|治疗方案)"), Regex("药物剂量"),
        Regex("现实案件.{0,16}(有罪|无罪|胜诉|败诉)"), Regex("危险化学品.{0,16}制备"),
        Regex("武器.{0,12}制造"), Regex("攻击步骤"), Regex("网络入侵"), Regex("绕过安全"),
        Regex("高风险金融操作建议"),
        // 英文
        Regex("(real|actual)\\s+individual.{0,24}(diagnosis|treatment\\s+plan)", RegexOption.IGNORE_CASE),
        Regex("drug\\s+dosage", RegexOption.IGNORE_CASE),
        Regex("(guilty|not\\s+guilty|win|lose).{0,32}(real|actual)\\s+case", RegexOption.IGNORE_CASE),
        Regex("dangerous\\s+chemical.{0,24}(preparation|synthesis)", RegexOption.IGNORE_CASE),
        Regex("weapon.{0,24}construction", RegexOption.IGNORE_CASE),
        Regex("attack\\s+steps?", RegexOption.IGNORE_CASE),
        Regex("network\\s+intrusion", RegexOption.IGNORE_CASE),
        Regex("(bypass|circumvent)\\s+security", RegexOption.IGNORE_CASE),
        Regex("high[- ]risk\\s+financial\\s+(advice|recommendation)", RegexOption.IGNORE_CASE),
        // 日文
        Regex("(現実|実際の個人).{0,24}(診断|治療方針)"), Regex("薬物投与量|服用量"),
        Regex("危険な化学物質.{0,24}製造"), Regex("武器.{0,24}製造"), Regex("攻撃手順"),
        Regex("ネットワーク侵入"), Regex("セキュリティ回避"), Regex("高リスク金融助言"),
        // 韩文
        Regex("(현실|실제)\\s*개인.{0,24}(진단|치료 방침)"), Regex("약물 용량|복용량"),
        Regex("위험 화학 물질.{0,24}제조"), Regex("무기.{0,24}제조"), Regex("공격 절차"),
        Regex("네트워크 침입"), Regex("보안 우회"), Regex("고위험 금융 조언"),
    )

    fun containsHighRiskActionGuidance(text: String): Boolean =
        HIGH_RISK_PATTERNS.any { it.containsMatchIn(text) }

    // ------------------------------------------------------------------
    // 证据行与锚点
    // ------------------------------------------------------------------

    /** 简介：前缀（与 TS SYNOPSIS_LINE_PREFIX 逐字对应）。 */
    const val SYNOPSIS_LINE_PREFIX = "简介："

    /**
     * 把已看条目转成证据行：行格式与 TS readMovies 逐字一致。
     * 服务端的「关键词」行依赖客户端未同步的字段，本地跳过。
     */
    fun buildEvidenceLines(item: AiWatchedTitleDto): List<String> {
        val lines = ArrayList<String>()
        item.year?.let { lines.add("上映年份：$it") }
        if (item.genres.isNotEmpty()) lines.add("类型：" + item.genres.joinToString("、"))
        if (item.overview.isNotBlank()) lines.add(SYNOPSIS_LINE_PREFIX + item.overview.take(600))
        if (item.originalTitle.isNotBlank()) lines.add("原名：" + item.originalTitle)
        item.runtime?.let { lines.add("片长/单集时长：${it}分钟") }
        if (item.country.isNotBlank()) lines.add("国家/地区：" + item.country)
        return lines
    }

    /** 从证据行里抽「简介：」行（对拍 synopsisEvidence）。 */
    fun synopsisEvidence(evidence: List<String>): String? =
        evidence.firstOrNull { it.startsWith(SYNOPSIS_LINE_PREFIX) }
            ?.removePrefix(SYNOPSIS_LINE_PREFIX)?.trim()
            ?.takeIf { it.isNotEmpty() }

    /** 证据锚点：年份、类型、证据行里长度 ≥2 的项（对拍 movieEvidenceAnchors）。 */
    fun movieEvidenceAnchors(year: Int?, genres: List<String>, evidence: List<String>): List<String> {
        val anchors = ArrayList<String>()
        year?.let { anchors.add(it.toString()) }
        anchors.addAll(genres)
        anchors.addAll(evidence)
        return anchors.filter { it.trim().length >= 2 }
    }

    /** 题面是否落在影片证据上：优先比简介，否则比碎片/锚点（对拍 containsEvidenceAnchor）。 */
    fun containsEvidenceAnchor(text: String, year: Int?, genres: List<String>, evidence: List<String>): Boolean {
        val synopsis = synopsisEvidence(evidence)
        if (synopsis != null) {
            return containsEvidenceText(text, synopsis, quizEvidenceFragments(synopsis))
        }
        val normalizedText = normalizedSearchText(text)
        return movieEvidenceAnchors(year, genres, evidence).any { normalizedText.contains(normalizedSearchText(it)) }
    }

    // ------------------------------------------------------------------
    // daily 来源可信与插图 brief
    // ------------------------------------------------------------------

    /** 可信来源域判定：整 host 精确匹配（对拍 isTrustedDailySourceHost）。 */
    fun isTrustedDailySourceHost(hostname: String): Boolean =
        DAILY_TRUSTED_SOURCE_HOSTS.contains(hostname.lowercase(java.util.Locale.ENGLISH))

    /**
     * 来源可核验：占位名、空 evidence、不可信域都算不可核验
     * （对拍 hasVerifiableSource）。解析失败按不可信处理。
     */
    fun hasVerifiableSource(sourceName: String, sourceUrl: String, sourceEvidence: String): Boolean {
        if (sourceName == DAILY_NO_SOURCE_NAME) return false
        if (sourceEvidence.isBlank()) return false
        val host = runCatching { java.net.URI(sourceUrl).host }.getOrNull() ?: return false
        return isTrustedDailySourceHost(host)
    }

    /** 插图 brief 禁词表（对拍 ILLUSTRATION_BRIEF_FORBIDDEN：人物/文字/钟表/书页等）。 */
    private val ILLUSTRATION_BRIEF_FORBIDDEN = Regex(
        "\\b(scene|scenes|portrait|portraits|person|people|human|humans|crowd|crowds|figure|figures|" +
            "silhouette|silhouettes|face|faces|man|men|woman|women|child|children|boy|girl|" +
            "clock|clocks|watch|watches|dial|dials|calendar|calendars|numeral|numerals|" +
            "number|numbers|digit|digits|text|letter|letters|word|words|caption|captions|" +
            "subtitle|subtitles|label|labels|logo|logos|sign|signs|poster|posters|billboard|" +
            "signature|watermark|document|documents|newspaper|page|pages|book|map|maps|" +
            "chart|charts|diagram|diagrams|screen|screens|monitor|mirror|mirrors|" +
            "photograph|photographs|photo|photos|screenshot)\\b",
        RegexOption.IGNORE_CASE,
    )

    private val CJK_RANGE = Regex("[\\u3040-\\u30ff\\u3400-\\u4dbf\\u4e00-\\u9fff\\uac00-\\ud7af\\u3000-\\u303f\\uff00-\\uffef]")

    /**
     * 插图 brief 清洗（对拍 normalizeIllustrationBrief）：
     * 非字符串/太短太长/含 CJK/无 3 个连续英文字母/命中禁词 → 一律返回 null（走本地抽象兜底）。
     */
    fun normalizeIllustrationBrief(value: JsonElement?): String? {
        val text = (value as? JsonPrimitive)?.contentOrNull ?: return null
        val normalized = collapseSpaces(text)
        if (normalized.length < 4 || normalized.length > 200) return null
        if (CJK_RANGE.containsMatchIn(normalized)) return null
        if (!Regex("[A-Za-z]{3}").containsMatchIn(normalized)) return null
        if (ILLUSTRATION_BRIEF_FORBIDDEN.containsMatchIn(normalized)) return null
        return normalized
    }
}

/**
 * 编排层统一异常出口：把各处失败折算成 AiProviderException。
 * 任务约定——JSON 解析失败/校验耗尽一律 INVALID_RESPONSE，输入问题用 INVALID_REQUEST。
 */
internal object AiOrchestrateError {

    fun invalidResponse(message: String): com.tracktosearch.data.ai.provider.AiProviderException =
        com.tracktosearch.data.ai.provider.AiProviderException(
            com.tracktosearch.data.ai.provider.AiProviderErrorCode.INVALID_RESPONSE,
            message,
        )

    fun invalidRequest(message: String): com.tracktosearch.data.ai.provider.AiProviderException =
        com.tracktosearch.data.ai.provider.AiProviderException(
            com.tracktosearch.data.ai.provider.AiProviderErrorCode.INVALID_REQUEST,
            message,
        )

    /** daily 单元判废：与 TS invalidUnit 的消息前缀逐字一致（修复提示按前缀翻译）。 */
    fun invalidUnit(message: String): com.tracktosearch.data.ai.provider.AiProviderException =
        invalidResponse("AI daily knowledge unit is invalid: $message")
}
