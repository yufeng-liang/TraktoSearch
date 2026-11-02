package com.tracktosearch.data.ai.orchestrate

import com.tracktosearch.data.ai.AiDetailInterestLevel
import com.tracktosearch.data.ai.AiDetailWatchTiming
import com.tracktosearch.data.ai.AiQuizOption
import com.tracktosearch.data.ai.AiQuizQuestion
import com.tracktosearch.data.ai.AiQuizQuestionType
import com.tracktosearch.data.ai.AiWatchedTitleDto
import com.tracktosearch.data.ai.prompt.DAILY_LOCALES
import com.tracktosearch.data.ai.prompt.DAILY_NO_SOURCE_NAME
import com.tracktosearch.data.ai.prompt.DailyCheckOption
import com.tracktosearch.data.ai.prompt.DailyCheckQuestion
import com.tracktosearch.data.ai.prompt.DailyKnowledgeMovieInput
import com.tracktosearch.data.ai.prompt.DailyKnowledgeSource
import com.tracktosearch.data.ai.prompt.DailyKnowledgeUnit
import com.tracktosearch.data.ai.prompt.DailyRelatedMedia
import com.tracktosearch.data.ai.prompt.EVIDENCE_ENHANCED_SUBJECTS
import com.tracktosearch.data.ai.prompt.KNOWLEDGE_EVIDENCE_MODES
import com.tracktosearch.data.ai.prompt.KNOWLEDGE_RELATION_TYPES
import com.tracktosearch.data.ai.prompt.KNOWLEDGE_UNIT_VERSION
import com.tracktosearch.data.ai.prompt.METACOGNITION_SUBJECTS
import com.tracktosearch.data.ai.prompt.SUBJECT_CATALOG
import com.tracktosearch.data.ai.prompt.SUBJECT_GROUP_IDS
import com.tracktosearch.data.ai.prompt.QuestionSlot
import com.tracktosearch.data.ai.prompt.SlotUnit
import com.tracktosearch.data.ai.prompt.UnitSlot
import com.tracktosearch.data.ai.prompt.timingBasis
import com.tracktosearch.data.ai.prompt.timingLevel
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

/**
 * BYOK 编排层的模型输出校验与落地：daily 知识单元、quiz 槽位单元/题目、detail 分析。
 *
 * 对拍 TS：daily-knowledge.ts 的 normalizeDailyKnowledgeUnit / normalizeQuizSlotUnit 与
 * handler.ts 的 normalizeQuestion / assertQuestionQuality / validateReviewedQuiz /
 * groundQuestionToUnit / fallbackQuestionForSlot / normalizeDetailOutput。
 * 全部校验失败抛 AiProviderException(INVALID_RESPONSE)，消息逐字对齐服务端，
 * 以便 dailyRepairHintFor 能把英文门槛串翻译成中文修复指令。
 */
object AiOrchestrateValidate {

    // ------------------------------------------------------------------
    // 内部载体
    // ------------------------------------------------------------------

    /** quiz 编排用影片视图：证据行已就绪，与 prompt 层解耦。 */
    data class OrchestrateMovie(
        val title: String,
        val year: Int?,
        val genres: List<String>,
        val overview: String?,
        val evidence: List<String>,
    )

    fun watchedToOrchestrateMovie(item: AiWatchedTitleDto): OrchestrateMovie = OrchestrateMovie(
        title = item.title,
        year = item.year,
        genres = item.genres,
        overview = item.overview.ifBlank { null },
        evidence = AiOrchestrateJson.buildEvidenceLines(item),
    )

    /** quiz 编排内部题：域模型之外额外保留本地判分需要的 correctAnswer。 */
    data class OrchestratedQuestion(
        val question: AiQuizQuestion,
        /** single=正确选项 id；short=参考答案全文；multiple=null（用 correctOptionIds 比对）。 */
        val correctAnswer: String?,
        /**
         * 归一时解析出的 unitId（AiQuizQuestion 域模型无此字段，单元锚定校验要用）；
         * 模型未写时为 null，锚定断言会以「未挂单元」判废。
         */
        val unitId: String? = null,
    )

    /** quiz 槽位单元校验结果：供题位消息与槽位断言使用。 */
    data class OrchestratedUnit(
        val unitId: String,
        val subjectGroup: String,
        val subject: String,
        val concept: String,
        val filmEvidence: String,
        val relatedMediaTitle: String?,
    ) {
        fun toSlotUnit(): SlotUnit = SlotUnit(
            unitId = unitId,
            subject = subject,
            concept = concept,
            filmEvidence = filmEvidence,
            relatedMediaTitle = relatedMediaTitle,
        )
    }

    /** subject → 学科组反查表（TS SUBJECT_TO_GROUP 的等价物，由目录反推）。 */
    private val SUBJECT_TO_GROUP: Map<String, String> =
        SUBJECT_CATALOG.flatMap { (group, subjects) -> subjects.map { it to group } }.toMap()

    /** 角色归属词探测标记（对拍 ATTRIBUTION_MARKER）。 */
    private val ATTRIBUTION_MARKER = Regex("(饰|出演|主演|扮演|饰演)")

    /** 演职员提取时排除的通称词（对拍 NAME_STOPWORDS）。 */
    private val NAME_STOPWORDS = setOf(
        "主角", "主人公", "导演", "编剧", "演员", "观众", "人物", "角色", "配角", "反派",
        "影片", "电影", "剧集", "摄影", "剪辑", "配乐", "作品", "故事", "叙事", "画面",
        "解读", "事实", "资料", "说明", "来源", "学科", "概念", "理论", "情况", "现象",
    )

    /** 角色归属名首字停用字（对拍 roleAttributedNames 的首字过滤）。 */
    private val ROLE_NAME_LEADING_STOPPERS = "是的了在有和与或而就都也还只把被让使向从对为".toSet()

    // ------------------------------------------------------------------
    // daily 基础取值（错误统一走 invalidUnit，保证修复提示可翻译）
    // ------------------------------------------------------------------

    private fun unitRecord(value: JsonElement?): JsonObject {
        val record = value as? JsonObject
            ?: throw AiOrchestrateError.invalidUnit("unit must be an object")
        return record
    }

    private fun unitText(record: JsonObject, fields: List<String>, label: String, min: Int, max: Int): String {
        for (field in fields) {
            val text = (record[field] as? JsonPrimitive)?.contentOrNull ?: continue
            val normalized = AiOrchestrateJson.collapseSpaces(text)
            if (normalized.isEmpty()) continue
            if (normalized.length < min || normalized.length > max) {
                throw AiOrchestrateError.invalidUnit("$label length is invalid")
            }
            return normalized
        }
        throw AiOrchestrateError.invalidUnit("$label must be a string")
    }

    private fun unitOptionalText(record: JsonObject, field: String, max: Int): String? {
        val element = record[field] ?: return null
        if (element is JsonNull) return null
        val text = (element as? JsonPrimitive)?.contentOrNull
            ?: throw AiOrchestrateError.invalidUnit("$field must be a string")
        val normalized = AiOrchestrateJson.collapseSpaces(text)
        return normalized.takeIf { it.isNotEmpty() }?.take(max)
    }

    private fun unitInt(record: JsonObject, fields: List<String>): Int? {
        for (field in fields) {
            val value = (record[field] as? JsonPrimitive)?.contentOrNull ?: continue
            val trimmed = value.trim()
            if (trimmed.isEmpty()) continue
            return trimmed.toIntOrNull() ?: continue
        }
        return null
    }

    // ------------------------------------------------------------------
    // daily 共享结构校验
    // ------------------------------------------------------------------

    /** 相关影片按归一化片名精确匹配输入（对拍 matchedWatchedMovie）。 */
    private fun matchedWatchedMovie(
        rawTitle: JsonElement?,
        movies: List<DailyKnowledgeMovieInput>,
    ): DailyKnowledgeMovieInput? {
        val title = unitText(
            JsonObject(mapOf("t" to (rawTitle ?: JsonNull))),
            listOf("t"), "relatedMedia.title", 1, 300,
        )
        val key = AiOrchestrateJson.normalizeForMatch(title)
        return movies.firstOrNull { AiOrchestrateJson.normalizeForMatch(it.title) == key }
    }

    /** 证据行里的具体内容行（剔除年份/类型这类元信息行，对拍 concreteMovieEvidence）。 */
    private fun concreteMovieEvidence(movie: DailyKnowledgeMovieInput): List<String> =
        (movie.evidence ?: emptyList()).filter { line ->
            !line.startsWith("上映年份：") && !line.startsWith("类型：")
        }

    /**
     * 证据是否落到输入材料上：filmEvidence 含片名 + 要求具体信号时必须命中证据行
     * （对拍 evidenceOverlapsInput）。
     */
    private fun evidenceOverlapsInput(
        filmEvidence: String,
        movie: DailyKnowledgeMovieInput,
        requireSpecificSignal: Boolean,
    ): Boolean {
        val hasTitle = filmEvidence.contains(movie.title)
        val specific = concreteMovieEvidence(movie).any { source ->
            AiOrchestrateJson.containsEvidenceText(
                filmEvidence, source, AiOrchestrateJson.dailyEvidenceFragments(source),
            )
        }
        return if (requireSpecificSignal) specific else (hasTitle || specific)
    }

    /** 来源三件套校验（对拍 normalizeSource）。 */
    private fun normalizeSource(record: JsonObject): DailyKnowledgeSource {
        val name = unitText(record, listOf("name"), "source.name", 2, 120)
        if (name == DAILY_NO_SOURCE_NAME) {
            // 占位名不许配 URL：写了 URL 也丢弃。
            return DailyKnowledgeSource(
                name = name,
                url = "",
                evidence = unitText(record, listOf("evidence"), "source.evidence", 8, 600),
            )
        }
        val urlElement = (record["url"] as? JsonPrimitive)?.contentOrNull
        if (urlElement == null || urlElement.length < 8 || urlElement.length > 2000 ||
            !(urlElement.startsWith("https://") || urlElement.startsWith("http://"))
        ) {
            throw AiOrchestrateError.invalidUnit("source.url must be http(s)")
        }
        return DailyKnowledgeSource(
            name = name,
            url = urlElement,
            evidence = unitText(record, listOf("evidence"), "source.evidence", 8, 600),
        )
    }

    /** 随堂小题校验（对拍 normalizeCheckQuestion）。 */
    private fun normalizeCheckQuestion(value: JsonElement?): DailyCheckQuestion {
        val record = unitRecord(value ?: JsonNull)
        val prompt = unitText(record, listOf("prompt"), "checkQuestion.prompt", 8, 500)
        val optionsElement = record["options"]
        val optionsArray = optionsElement as? JsonArray
            ?: throw AiOrchestrateError.invalidUnit("checkQuestion.options must contain 2 to 4 items")
        if (optionsArray.size < 2 || optionsArray.size > 4) {
            throw AiOrchestrateError.invalidUnit("checkQuestion.options must contain 2 to 4 items")
        }
        data class ParsedOption(val id: String, val text: String)
        val options = optionsArray.mapIndexed { index, element ->
            val option = element as? JsonObject
                ?: throw AiOrchestrateError.invalidUnit("checkQuestion.options must contain 2 to 4 items")
            val id = unitText(option, listOf("id"), "checkQuestion.option ${index + 1}.id", 1, 16)
            if (!Regex("^[A-Za-z0-9_-]+$").matches(id)) {
                throw AiOrchestrateError.invalidUnit("option.id has invalid characters")
            }
            val text = AiOrchestrateJson.stripTrailingSentencePunctuation(
                unitText(option, listOf("text"), "checkQuestion.option ${index + 1}.text", 2, 300),
            )
            ParsedOption(id, text)
        }
        if (options.map { it.id }.distinct().size != options.size ||
            options.map { AiOrchestrateJson.normalizeForMatch(it.text) }.distinct().size != options.size
        ) {
            throw AiOrchestrateError.invalidUnit("checkQuestion option id/text must be unique")
        }
        val correctIdsElement = record["correctOptionIds"] as? JsonArray
        val correctIds = correctIdsElement?.mapNotNull { (it as? JsonPrimitive)?.contentOrNull } ?: emptyList()
        if (correctIds.size != 1) {
            throw AiOrchestrateError.invalidUnit("checkQuestion must have exactly one best answer")
        }
        if (correctIds[0] !in options.map { it.id }) {
            throw AiOrchestrateError.invalidUnit("correctOptionId is not in options")
        }
        val explanation = unitText(record, listOf("explanation"), "checkQuestion.explanation", 12, 800)
        return DailyCheckQuestion(
            prompt = prompt,
            options = options.map { DailyCheckOption(it.id, it.text) },
            correctOptionIds = correctIds,
            explanation = explanation,
        )
    }

    /** 边界句必须与证据模式同口径（对拍 validateBoundary）。 */
    private fun validateBoundary(boundary: String, evidenceMode: String) {
        val markers = when (evidenceMode) {
            "film_fact" -> listOf("事实", "资料", "说明", "fact", "source", "資料", "説明", "사실", "자료", "설명")
            "viewing_interpretation" -> listOf("解读", "不是", "interpretation", "not", "解釈", "解説", "해석", "아님")
            "external_fact" -> listOf("来源", "事实", "说明", "source", "fact", "事実", "資料", "説明", "出典", "출처", "사실", "자료", "설명")
            else -> listOf("延伸", "不是", "extension", "not", "拡張", "확장")
        }
        val lowered = boundary.lowercase()
        if (markers.none { lowered.contains(it) }) {
            throw AiOrchestrateError.invalidUnit("boundary does not match evidence mode")
        }
    }

    /** 剧透级别不得低于正文透露程度（对拍 validateSpoilerLevel）。 */
    private fun validateSpoilerLevel(texts: List<String>, spoilerLevel: String) {
        val lowered = texts.joinToString("\n").lowercase()
        val heavyWords = listOf(
            "结局", "结尾", "死亡", "凶手", "真相", "逆转",
            "結末", "死ぬ", "犯人", "どんでん返し",
            "결말", "마지막", "죽다", "사망", "범인", "진실", "반전",
        )
        val hasHeavy = heavyWords.any { lowered.contains(it) } ||
            Regex("\\b(ending|finale|dies|death|killer|murderer|truth|twist)\\b", RegexOption.IGNORE_CASE)
                .containsMatchIn(lowered) ||
            Regex("(?<![\\u30A0-\\u30FF])ラスト(?![\\u30A0-\\u30FF])").containsMatchIn(lowered)
        if (!hasHeavy) return
        if (spoilerLevel == "none") {
            throw AiOrchestrateError.invalidUnit("spoilerLevel understates plot disclosure")
        }
        if (spoilerLevel == "light") {
            throw AiOrchestrateError.invalidUnit("heavy plot disclosure requires heavy spoilerLevel")
        }
    }

    /**
     * 禁编造演职员/角色名（对拍 hasInventedEntityName）：
     * 正文里提取出的中文名若在输入材料（片名+证据行）里完全没出现过，判废。
     */
    private fun hasInventedEntityName(
        explanation: String,
        realWorldExample: String,
        boundary: String,
        movies: List<DailyKnowledgeMovieInput>,
    ): Boolean {
        val input = movies.joinToString("\n") { movie ->
            (listOf(movie.title) + (movie.evidence ?: emptyList())).joinToString("\n")
        }
        val normalizedInput = input.lowercase().replace(Regex("\\s+"), "")
        val text = listOf(explanation, realWorldExample, boundary).joinToString("\n")
        val candidates = ArrayList<String>()
        for (match in Regex("([\\u4e00-\\u9fa5]{2,4})\\s*(?:饰|出演|主演)").findAll(text)) {
            candidates.add(match.groupValues[1])
        }
        if (ATTRIBUTION_MARKER.containsMatchIn(text)) {
            for (match in Regex("(?:主角|主人公|男主|女主|反派|配角)\\s*([\\u4e00-\\u9fa5]{2,4})").findAll(text)) {
                var name = match.groupValues[1]
                if (name.firstOrNull() in ROLE_NAME_LEADING_STOPPERS) continue
                name = name.trimEnd('主', '演', '饰', '扮', '出').trim()
                if (name.length >= 2 && name !in NAME_STOPWORDS) candidates.add(name)
            }
        }
        return candidates.any { name ->
            name !in NAME_STOPWORDS && name.length >= 2 &&
                !normalizedInput.contains(name.lowercase().replace(Regex("\\s+"), ""))
        }
    }

    // ------------------------------------------------------------------
    // daily 单元归一化（对拍 normalizeDailyKnowledgeUnit）
    // ------------------------------------------------------------------

    /**
     * 完整 daily 单元校验：成功返回 prompt 层的 DailyKnowledgeUnit（直接喂二审消息）。
     * 失败抛 invalidUnit，错误串与 TS 逐字一致。
     */
    fun normalizeDailyKnowledgeUnit(
        value: JsonElement?,
        day: String,
        locale: String,
        movies: List<DailyKnowledgeMovieInput>,
    ): DailyKnowledgeUnit {
        val record = unitRecord(value)
        val unitId = unitText(record, listOf("unitId", "id"), "unitId", 3, 96)
        if (!Regex("^[A-Za-z0-9._:-]+$").matches(unitId)) {
            throw AiOrchestrateError.invalidUnit("unitId has invalid characters")
        }
        val version = unitInt(record, listOf("unitVersion", "version"))
        if (version != KNOWLEDGE_UNIT_VERSION) {
            throw AiOrchestrateError.invalidUnit("unitVersion must be 1")
        }
        val unitLocale = (record["locale"] as? JsonPrimitive)?.contentOrNull?.trim().orEmpty()
        if (unitLocale !in DAILY_LOCALES || unitLocale != locale) {
            throw AiOrchestrateError.invalidUnit("locale does not match request")
        }
        val relationType = unitText(record, listOf("relationType"), "relationType", 2, 40)
        if (relationType !in KNOWLEDGE_RELATION_TYPES) {
            throw AiOrchestrateError.invalidUnit("relationType is invalid")
        }
        val evidenceMode = unitText(record, listOf("evidenceMode"), "evidenceMode", 2, 40)
        if (evidenceMode !in KNOWLEDGE_EVIDENCE_MODES) {
            throw AiOrchestrateError.invalidUnit("evidenceMode is invalid")
        }
        val subjectGroup = unitText(record, listOf("subjectGroup"), "subjectGroup", 2, 60)
        val subject = unitText(record, listOf("subject"), "subject", 2, 80)
        if (subjectGroup !in SUBJECT_GROUP_IDS || SUBJECT_TO_GROUP[subject] != subjectGroup) {
            throw AiOrchestrateError.invalidUnit("subject is outside the controlled catalog")
        }
        val concept = unitText(record, listOf("concept"), "concept", 2, 120)
        val title = unitText(record, listOf("title"), "title", 4, 160)
        val takeaway = unitText(record, listOf("takeaway"), "takeaway", 12, 320)
        val filmEvidence = unitText(record, listOf("filmEvidence"), "filmEvidence", 12, 600)
        val explanation = unitText(record, listOf("explanation"), "explanation", 20, 900)
        val realWorldExample = unitText(record, listOf("realWorldExample"), "realWorldExample", 12, 500)
        val boundary = unitText(record, listOf("boundary"), "boundary", 12, 500)
        val difficulty = unitText(record, listOf("difficulty"), "difficulty", 4, 12)
        if (difficulty !in listOf("easy", "medium", "hard")) {
            throw AiOrchestrateError.invalidUnit("difficulty is invalid")
        }
        val spoilerLevel = unitText(record, listOf("spoilerLevel"), "spoilerLevel", 4, 8)
        if (spoilerLevel !in listOf("none", "light", "heavy")) {
            throw AiOrchestrateError.invalidUnit("spoilerLevel is invalid")
        }
        val sourceRecord = record["source"]?.takeIf { it !is JsonNull }
        val source = if (sourceRecord == null) {
            throw AiOrchestrateError.invalidUnit("source must be an object")
        } else {
            normalizeSource(unitRecord(sourceRecord))
        }
        val checkQuestion = normalizeCheckQuestion(record["checkQuestion"])
        val characterLine = unitOptionalText(record, "characterLine", 240)

        // 相关影片：只有 direct_watch 允许出现；值必须逐字命中输入材料。
        val relatedMediaElement = record["relatedMedia"]?.takeIf { it !is JsonNull }
        var relatedMedia: DailyRelatedMedia? = null
        if (relationType == "direct_watch") {
            val mediaRecord = relatedMediaElement?.let { it as? JsonObject }
                ?: throw AiOrchestrateError.invalidUnit("direct_watch media must come from watched input")
            val matched = matchedWatchedMovie(mediaRecord["title"], movies)
                ?: throw AiOrchestrateError.invalidUnit("direct_watch media must come from watched input")
            if (!evidenceOverlapsInput(filmEvidence, matched, requireSpecificSignal = true)) {
                throw AiOrchestrateError.invalidUnit("filmEvidence must use specific watched input besides the title")
            }
            relatedMedia = DailyRelatedMedia(
                title = matched.title,
                mediaType = matched.mediaType,
                traktId = matched.traktId,
                tmdbId = matched.tmdbId,
                imdbId = matched.imdbId,
                doubanId = matched.doubanId,
            )
        } else if (relatedMediaElement != null) {
            throw AiOrchestrateError.invalidUnit("only direct_watch may declare relatedMedia")
        }
        if (relationType == "theme_extension") {
            if (evidenceMode != "theme_extension") {
                throw AiOrchestrateError.invalidUnit("theme_extension requires theme_extension evidence mode")
            }
            if (movies.isEmpty()) {
                throw AiOrchestrateError.invalidUnit("theme_extension requires watched input")
            }
            if (movies.none { evidenceOverlapsInput(filmEvidence, it, requireSpecificSignal = true) }) {
                throw AiOrchestrateError.invalidUnit("theme_extension must use specific watched input")
            }
        }
        if (relationType == "direct_watch" && evidenceMode == "theme_extension") {
            throw AiOrchestrateError.invalidUnit("direct_watch cannot use theme_extension evidence mode")
        }
        if (relationType == "general_knowledge" && evidenceMode == "theme_extension") {
            throw AiOrchestrateError.invalidUnit("general_knowledge cannot use theme_extension evidence mode")
        }
        val movieTitles = movies.map { it.title }
        if (listOf(title, takeaway, filmEvidence).any { AiOrchestrateJson.isGenericText(it, movieTitles) }) {
            throw AiOrchestrateError.invalidUnit("content is a generic template")
        }
        val methodologySurface = buildString {
            append(concept); append('\n'); append(title); append('\n'); append(takeaway)
            append('\n'); append(checkQuestion.prompt)
            checkQuestion.options.forEach { append('\n'); append(it.text) }
            append('\n'); append(checkQuestion.explanation)
        }
        if (AiOrchestrateJson.isGenericMethodologyMismatch(methodologySurface, subject, METACOGNITION_SUBJECTS)) {
            throw AiOrchestrateError.invalidUnit("subject does not match generic methodology content")
        }
        if (subject in EVIDENCE_ENHANCED_SUBJECTS &&
            !AiOrchestrateJson.hasVerifiableSource(source.name, source.url, source.evidence)
        ) {
            throw AiOrchestrateError.invalidUnit("evidence-enhanced subject requires external source evidence")
        }
        if (!AiOrchestrateJson.normalizeForMatch(checkQuestion.explanation)
            .contains(AiOrchestrateJson.normalizeForMatch(concept))
        ) {
            throw AiOrchestrateError.invalidUnit("checkQuestion explanation must return to the same concept")
        }
        val visibleTexts = buildList {
            add(subject); add(concept); add(title); add(takeaway); add(filmEvidence)
            add(explanation); add(realWorldExample); add(boundary)
            characterLine?.let { add(it) }
            add(checkQuestion.prompt)
            checkQuestion.options.forEach { add(it.text) }
            add(checkQuestion.explanation)
            add(source.evidence)
        }
        if (visibleTexts.any { AiOrchestrateJson.containsHighRiskActionGuidance(it) }) {
            throw AiOrchestrateError.invalidUnit("high-risk action guidance is not allowed")
        }
        if (hasInventedEntityName(explanation, realWorldExample, boundary, movies)) {
            throw AiOrchestrateError.invalidUnit(
                "explanation或realWorldExample出现了输入材料里没有的人物名（禁编造演职员/角色名）",
            )
        }
        validateBoundary(boundary, evidenceMode)
        validateSpoilerLevel(
            buildList {
                add(title); add(takeaway); add(filmEvidence)
                characterLine?.let { add(it) }
                add(checkQuestion.prompt)
                checkQuestion.options.forEach { add(it.text) }
                add(checkQuestion.explanation)
            },
            spoilerLevel,
        )
        return DailyKnowledgeUnit(
            unitId = unitId,
            version = version,
            locale = unitLocale,
            relationType = relationType,
            evidenceMode = evidenceMode,
            subjectGroup = subjectGroup,
            subject = subject,
            concept = concept,
            title = title,
            takeaway = takeaway,
            relatedMedia = relatedMedia,
            filmEvidence = filmEvidence,
            explanation = explanation,
            realWorldExample = realWorldExample,
            boundary = boundary,
            difficulty = difficulty,
            spoilerLevel = spoilerLevel,
            source = source,
            checkQuestion = checkQuestion,
            characterLine = characterLine,
            illustrationBrief = AiOrchestrateJson.normalizeIllustrationBrief(record["illustrationBrief"]),
        )
    }

    // ------------------------------------------------------------------
    // quiz 槽位单元归一化（对拍 normalizeQuizSlotUnit：精简版单元）
    // ------------------------------------------------------------------

    /** quiz 槽位单元校验：只保留出题必要字段，多余展示字段忽略不判废。 */
    fun normalizeQuizSlotUnit(
        value: JsonElement?,
        locale: String,
        movies: List<DailyKnowledgeMovieInput>,
    ): OrchestratedUnit {
        val record = unitRecord(value)
        val unitId = unitText(record, listOf("unitId", "id"), "unitId", 3, 96)
        if (!Regex("^[A-Za-z0-9._:-]+$").matches(unitId)) {
            throw AiOrchestrateError.invalidUnit("unitId has invalid characters")
        }
        val version = unitInt(record, listOf("unitVersion", "version"))
        if (version != KNOWLEDGE_UNIT_VERSION) {
            throw AiOrchestrateError.invalidUnit("unitVersion must be 1")
        }
        val unitLocale = (record["locale"] as? JsonPrimitive)?.contentOrNull?.trim().orEmpty()
        if (unitLocale !in DAILY_LOCALES || unitLocale != locale) {
            throw AiOrchestrateError.invalidUnit("locale does not match request")
        }
        val relationType = unitText(record, listOf("relationType"), "relationType", 2, 40)
        if (relationType != "direct_watch") {
            throw AiOrchestrateError.invalidUnit("quiz slot units must stay on watched media")
        }
        val evidenceMode = unitText(record, listOf("evidenceMode"), "evidenceMode", 2, 40)
        if (evidenceMode !in KNOWLEDGE_EVIDENCE_MODES || evidenceMode == "theme_extension") {
            throw AiOrchestrateError.invalidUnit("evidenceMode is invalid")
        }
        val subjectGroup = unitText(record, listOf("subjectGroup"), "subjectGroup", 2, 60)
        val subject = unitText(record, listOf("subject"), "subject", 2, 80)
        if (subjectGroup !in SUBJECT_GROUP_IDS || SUBJECT_TO_GROUP[subject] != subjectGroup) {
            throw AiOrchestrateError.invalidUnit("subject is outside the controlled catalog")
        }
        if (subject in EVIDENCE_ENHANCED_SUBJECTS) {
            throw AiOrchestrateError.invalidUnit("evidence-enhanced subject requires external source evidence")
        }
        val concept = unitText(record, listOf("concept"), "concept", 2, 120)
        val filmEvidence = unitText(record, listOf("filmEvidence"), "filmEvidence", 12, 600)
        val mediaRecord = record["relatedMedia"]?.takeIf { it !is JsonNull } as? JsonObject
            ?: throw AiOrchestrateError.invalidUnit("direct_watch media must come from watched input")
        val matched = matchedWatchedMovie(mediaRecord["title"], movies)
            ?: throw AiOrchestrateError.invalidUnit("direct_watch media must come from watched input")
        if (!evidenceOverlapsInput(filmEvidence, matched, requireSpecificSignal = true)) {
            throw AiOrchestrateError.invalidUnit("filmEvidence must use specific watched input besides the title")
        }
        if (AiOrchestrateJson.isGenericText(filmEvidence, movies.map { it.title })) {
            throw AiOrchestrateError.invalidUnit("content is a generic template")
        }
        if (AiOrchestrateJson.isGenericMethodologyMismatch(concept, subject, METACOGNITION_SUBJECTS)) {
            throw AiOrchestrateError.invalidUnit("subject does not match generic methodology content")
        }
        if (AiOrchestrateJson.containsHighRiskActionGuidance(concept) ||
            AiOrchestrateJson.containsHighRiskActionGuidance(filmEvidence)
        ) {
            throw AiOrchestrateError.invalidUnit("high-risk action guidance is not allowed")
        }
        return OrchestratedUnit(
            unitId = unitId,
            subjectGroup = subjectGroup,
            subject = subject,
            concept = concept,
            filmEvidence = filmEvidence,
            relatedMediaTitle = matched.title,
        )
    }

    // ------------------------------------------------------------------
    // quiz 题目归一化与质量门槛（对拍 normalizeQuestion / assertQuestionQuality）
    // ------------------------------------------------------------------

    /** 题目类型 wire 值 → 枚举；非法值由调用方判废。 */
    private fun questionType(value: String): AiQuizQuestionType? = when (value) {
        "single" -> AiQuizQuestionType.SINGLE
        "multiple" -> AiQuizQuestionType.MULTIPLE
        "short" -> AiQuizQuestionType.SHORT
        else -> null
    }

    /** 单题校验：字段别名、选项结构、答案合法性（对拍 normalizeQuestion）。 */
    fun normalizeQuestion(
        value: JsonElement?,
        index: Int,
        movies: List<OrchestrateMovie>,
    ): OrchestratedQuestion {
        val record = value as? JsonObject
            ?: throw AiOrchestrateError.invalidResponse("AI question must be an object")
        val typeRaw = (record["type"] as? JsonPrimitive)?.contentOrNull?.trim().orEmpty()
        val type = questionType(typeRaw)
            ?: throw AiOrchestrateError.invalidResponse("AI question type is invalid")

        // 选项：short 必须为空；其余 2-4 项，id/text 归一化并查重。
        val rawOptions = record["options"] as? JsonArray
        val options: List<AiQuizOption> = if (type == AiQuizQuestionType.SHORT) {
            if (!rawOptions.isNullOrEmpty()) {
                throw AiOrchestrateError.invalidResponse("AI question options are invalid")
            }
            emptyList()
        } else {
            if (rawOptions == null || rawOptions.size < 2 || rawOptions.size > 4) {
                throw AiOrchestrateError.invalidResponse("AI question options are invalid")
            }
            val parsed = rawOptions.mapIndexed { optionIndex, element ->
                when (element) {
                    is JsonPrimitive -> {
                        val text = element.contentOrNull?.trim().orEmpty()
                        AiQuizOption(
                            id = "a" + ('a' + optionIndex),
                            text = AiOrchestrateJson.stripTrailingSentencePunctuation(text).take(240),
                        )
                    }
                    is JsonObject -> AiQuizOption(
                        id = AiOrchestrateJson.requiredText(element, listOf("id", "key"), "option id").take(32),
                        text = AiOrchestrateJson.stripTrailingSentencePunctuation(
                            AiOrchestrateJson.requiredText(element, listOf("text", "label"), "option text"),
                        ).take(240),
                    )
                    else -> throw AiOrchestrateError.invalidResponse("AI question options are invalid")
                }
            }
            if (parsed.any { it.text.isEmpty() }) {
                throw AiOrchestrateError.invalidResponse("AI option text is empty")
            }
            if (parsed.map { it.id }.distinct().size != parsed.size) {
                throw AiOrchestrateError.invalidResponse("AI option ids must be unique")
            }
            if (parsed.map { AiOrchestrateJson.normalizeSemanticKey(it.text) }.distinct().size != parsed.size) {
                throw AiOrchestrateError.invalidResponse("AI option texts must be unique")
            }
            parsed
        }
        val optionIds = options.map { it.id }.toSet()

        // 正确答案：multiple 走数组；single/short 走文本；非 short 答案必须落在选项内。
        val correctAnswerRaw: JsonElement? = if (type == AiQuizQuestionType.MULTIPLE) {
            AiOrchestrateJson.readAnswerArray(record.firstFieldSafe(listOf("correctAnswer", "answer")))
                .let { JsonArray(it.map { item -> JsonPrimitive(item) }) }
        } else {
            AiOrchestrateJson.requiredText(record, listOf("correctAnswer", "answer"), "answer")
                .let { JsonPrimitive(it) }
        }
        val correctAnswers: List<String> = when (correctAnswerRaw) {
            is JsonArray -> correctAnswerRaw.mapNotNull { (it as? JsonPrimitive)?.contentOrNull }
            is JsonPrimitive -> listOfNotNull(correctAnswerRaw.contentOrNull)
            else -> emptyList()
        }
        if (type != AiQuizQuestionType.SHORT) {
            if (correctAnswers.any { it !in optionIds } ||
                correctAnswers.map { it }.distinct().size != correctAnswers.size
            ) {
                throw AiOrchestrateError.invalidResponse("AI question answer is invalid")
            }
            if (type == AiQuizQuestionType.SINGLE && correctAnswers.size != 1) {
                throw AiOrchestrateError.invalidResponse("AI single question must have one answer")
            }
            if (type == AiQuizQuestionType.MULTIPLE && (correctAnswers.size < 2 || correctAnswers.size > 3)) {
                throw AiOrchestrateError.invalidResponse("AI multiple question must have two or three answers")
            }
        }

        // 片名来源：缺省轮转取片；显式给值必须逐字命中已看片名。
        val sourceTitleRaw = AiOrchestrateJson.optionalText(record, listOf("sourceTitle", "mediaTitle"))
        val fallbackMovie = movies[index % movies.size]
        val sourceTitle = if (sourceTitleRaw == null) {
            fallbackMovie.title
        } else {
            val key = sourceTitleRaw.trim().lowercase(java.util.Locale.SIMPLIFIED_CHINESE)
            movies.firstOrNull {
                it.title.trim().lowercase(java.util.Locale.SIMPLIFIED_CHINESE) == key
            }?.title
                ?: throw AiOrchestrateError.invalidResponse("AI question source must be watched")
        }

        val subject = AiOrchestrateJson.requiredText(record, listOf("subject", "discipline"), "subject").take(80)
        val concept = AiOrchestrateJson.requiredText(record, listOf("concept", "knowledgeConcept"), "concept").take(160)
        val learningTakeaway = AiOrchestrateJson.requiredText(
            record, listOf("learningTakeaway", "takeaway", "learningConclusion"), "learning takeaway",
        ).take(400)
        val evidenceUsed = AiOrchestrateJson.requiredText(
            record, listOf("evidenceUsed", "evidence", "evidenceText"), "evidence used",
        ).take(600)
        val explanation = AiOrchestrateJson.requiredText(record, listOf("explanation", "analysis"), "explanation")
        val difficultyRaw = AiOrchestrateJson.optionalText(record, listOf("difficulty")) ?: "medium"
        val difficulty = difficultyRaw.trim().lowercase(java.util.Locale.ENGLISH)
        if (difficulty !in listOf("easy", "medium", "hard")) {
            throw AiOrchestrateError.invalidResponse("AI question difficulty is invalid")
        }
        val answerRationale = AiOrchestrateJson.optionalText(record, listOf("answerRationale", "correctRationale"))
            ?: explanation
        val distractorRationale = AiOrchestrateJson.optionalText(
            record, listOf("distractorRationale", "wrongAnswerRationale"),
        ) ?: if (type == AiQuizQuestionType.SHORT) {
            ""
        } else {
            "其他选项没有同时满足题干条件，或缺少可回到作品验证的依据。"
        }
        val id = if (record["id"] == null || record["id"] is JsonNull) {
            "q" + (index + 1)
        } else {
            AiOrchestrateJson.readAiOpaqueId(record["id"], "question id")
        }
        val unitIdElement = record["unitId"]
        val unitId = if (unitIdElement == null || unitIdElement is JsonNull) {
            null
        } else {
            AiOrchestrateJson.readAiOpaqueId(unitIdElement, "unit id")
        }
        val knowledgePoint = AiOrchestrateJson.optionalText(record, listOf("knowledgePoint", "skill")) ?: concept
        val answerKeywords = AiOrchestrateJson.readOptionalAiStringArray(record["answerKeywords"])
        val quote = AiOrchestrateJson.optionalText(record, listOf("quote"), 600)
        val question = AiQuizQuestion(
            id = id,
            type = type,
            prompt = AiOrchestrateJson.requiredText(record, listOf("prompt", "question"), "prompt"),
            options = options,
            mediaTitle = sourceTitle,
            quote = quote,
            maxScore = when (type) {
                AiQuizQuestionType.SHORT -> 0
                AiQuizQuestionType.MULTIPLE -> 10
                AiQuizQuestionType.SINGLE -> 8
            },
            difficulty = difficulty,
            knowledgePoint = knowledgePoint,
            sourceTitle = sourceTitle,
            answerRationale = answerRationale,
            distractorRationale = distractorRationale,
            explanation = explanation,
            correctOptionIds = if (type == AiQuizQuestionType.SHORT) emptyList() else correctAnswers,
            subject = subject,
            concept = concept,
            learningTakeaway = learningTakeaway,
            evidenceUsed = evidenceUsed,
            answerKeywords = answerKeywords,
            // 判分依据随题目一起落缓存（多选为 null，靠 correctOptionIds 判分）。
            correctAnswer = when (type) {
                AiQuizQuestionType.MULTIPLE -> null
                else -> correctAnswers.firstOrNull()
            },
            unitId = unitId,
        )
        return OrchestratedQuestion(
            question = question,
            correctAnswer = when (type) {
                AiQuizQuestionType.MULTIPLE -> null
                else -> correctAnswers.firstOrNull()
            },
            unitId = unitId,
        )
    }

    private fun JsonObject.firstFieldSafe(fields: List<String>): JsonElement? {
        for (field in fields) {
            val element = this[field]
            if (element != null && element !is JsonNull) return element
        }
        return null
    }

    /** 题干/证据是否落在影片可核验内容上（对拍 hasConcreteMovieEvidence）。 */
    private fun hasConcreteMovieEvidence(question: AiQuizQuestion, movie: OrchestrateMovie): Boolean {
        val synopsis = AiOrchestrateJson.synopsisEvidence(movie.evidence)
        return if (synopsis != null) {
            AiOrchestrateJson.containsEvidenceText(
                question.evidenceUsed, synopsis, AiOrchestrateJson.quizEvidenceFragments(synopsis),
            )
        } else {
            val normalized = AiOrchestrateJson.normalizedSearchText(question.evidenceUsed)
            AiOrchestrateJson.movieEvidenceAnchors(movie.year, movie.genres, movie.evidence)
                .any { normalized.contains(AiOrchestrateJson.normalizedSearchText(it)) }
        }
    }

    /** 解析链完整性：解释+结论要么回到概念，要么出现推理连接词（对拍 containsLearningChain）。 */
    private fun containsLearningChain(question: AiQuizQuestion): Boolean {
        val surface = (question.explanation.orEmpty() + question.learningTakeaway).lowercase()
        val conceptKey = AiOrchestrateJson.normalizeForQuizMatch(question.concept)
        if (conceptKey.isNotEmpty() && surface.contains(conceptKey)) return true
        return Regex("因为|因此|说明|意味着|结论|可以复述|从而|这告诉我们")
            .containsMatchIn(question.explanation.orEmpty() + question.learningTakeaway)
    }

    /** 题干+选项是否真的考察所声明的概念（对拍 questionTextReferencesConcept）。 */
    private fun questionTextReferencesConcept(question: AiQuizQuestion): Boolean {
        val surface = AiOrchestrateJson.normalizeSemanticKey(
            question.prompt + question.options.joinToString("") { it.text },
        )
        val conceptKey = AiOrchestrateJson.normalizeSemanticKey(question.concept)
        val pointKey = AiOrchestrateJson.normalizeSemanticKey(question.knowledgePoint)
        return (conceptKey.isNotEmpty() && surface.contains(conceptKey)) ||
            (pointKey.isNotEmpty() && surface.contains(pointKey))
    }

    /** 解析是否针对本题而非套话（对拍 rationaleIsQuestionSpecific）。 */
    private fun rationaleIsQuestionSpecific(rationale: String, question: AiQuizQuestion): Boolean {
        if (question.type == AiQuizQuestionType.SHORT && rationale.isEmpty()) return true
        val key = AiOrchestrateJson.normalizeSemanticKey(rationale)
        if (key.length < 12) return false
        val conceptKey = AiOrchestrateJson.normalizeSemanticKey(question.concept)
        val pointKey = AiOrchestrateJson.normalizeSemanticKey(question.knowledgePoint)
        if ((conceptKey.isNotEmpty() && key.contains(conceptKey)) ||
            (pointKey.isNotEmpty() && key.contains(pointKey))
        ) {
            return true
        }
        if (question.type == AiQuizQuestionType.SHORT) {
            return listOf("证据", "结论", "概念").any { rationale.contains(it) }
        }
        return question.options.any { option ->
            option.text.length >= 4 && key.contains(AiOrchestrateJson.normalizeSemanticKey(option.text))
        }
    }

    /** 单题质量门槛（对拍 assertQuestionQuality，错误消息逐字一致）。 */
    fun assertQuestionQuality(
        orchestrated: OrchestratedQuestion,
        movies: List<OrchestrateMovie>,
        unitById: Map<String, OrchestratedUnit>,
    ) {
        val question = orchestrated.question
        val movie = movies.firstOrNull { it.title == question.sourceTitle }
            ?: throw AiOrchestrateError.invalidResponse("AI question source must be watched")
        val unitId = orchestrated.unitId
            ?: throw AiOrchestrateError.invalidResponse("AI question must come from a reviewed knowledge unit")
        val unit = unitById[unitId]
            ?: throw AiOrchestrateError.invalidResponse("AI question must come from a reviewed knowledge unit")
        if (question.subject != unit.subject) {
            throw AiOrchestrateError.invalidResponse("AI question subject must match its unit")
        }
        val conceptKey = AiOrchestrateJson.normalizeForQuizMatch(question.concept)
        val unitConceptKey = AiOrchestrateJson.normalizeForQuizMatch(unit.concept)
        val conceptMatched = conceptKey.isNotEmpty() && unitConceptKey.isNotEmpty() &&
            (conceptKey.contains(unitConceptKey) || unitConceptKey.contains(conceptKey))
        if (!conceptMatched) {
            throw AiOrchestrateError.invalidResponse("AI question concept must match its unit")
        }
        if (!AiOrchestrateJson.containsEvidenceText(
                question.evidenceUsed, unit.filmEvidence, AiOrchestrateJson.quizEvidenceFragments(unit.filmEvidence),
            )
        ) {
            throw AiOrchestrateError.invalidResponse("AI question evidence must come from its unit")
        }
        if (question.concept.length < 2 || question.learningTakeaway.length < 12 || question.evidenceUsed.length < 8) {
            throw AiOrchestrateError.invalidResponse("AI quiz education fields are too short")
        }
        if (!hasConcreteMovieEvidence(question, movie)) {
            throw AiOrchestrateError.invalidResponse("AI quiz evidence is not grounded in watched content")
        }
        if (!AiOrchestrateJson.containsEvidenceAnchor(question.prompt, movie.year, movie.genres, movie.evidence)) {
            throw AiOrchestrateError.invalidResponse("AI quiz prompt is too generic")
        }
        if (AiOrchestrateJson.isGenericQuizText(question.prompt)) {
            throw AiOrchestrateError.invalidResponse("AI quiz prompt is generic")
        }
        val explanation = question.explanation.orEmpty()
        if (explanation.length < 30 || !containsLearningChain(question)) {
            throw AiOrchestrateError.invalidResponse("AI quiz explanation is not educational")
        }
        val methodologySurface = buildString {
            append(question.prompt); append('\n'); append(question.knowledgePoint)
            append('\n'); append(question.learningTakeaway); append('\n'); append(question.answerRationale)
            append('\n'); append(question.distractorRationale)
            question.options.forEach { append('\n'); append(it.text) }
        }
        if (AiOrchestrateJson.isGenericMethodologyMismatch(methodologySurface, question.subject, METACOGNITION_SUBJECTS)) {
            throw AiOrchestrateError.invalidResponse("AI question subject conflicts with generic methodology content")
        }
        if (!questionTextReferencesConcept(question)) {
            throw AiOrchestrateError.invalidResponse("AI quiz prompt and options must test the declared concept")
        }
        if (!rationaleIsQuestionSpecific(question.answerRationale, question)) {
            throw AiOrchestrateError.invalidResponse("AI quiz answer rationale is not question-specific")
        }
        if (question.type != AiQuizQuestionType.SHORT &&
            !rationaleIsQuestionSpecific(question.distractorRationale, question)
        ) {
            throw AiOrchestrateError.invalidResponse("AI quiz distractor rationale is not question-specific")
        }
    }

    /** 整包 13 题复核（对拍 validateReviewedQuiz）：逐题质量门槛 + 学科覆盖 3-8。 */
    fun validateReviewedQuiz(
        questions: List<OrchestratedQuestion>,
        movies: List<OrchestrateMovie>,
        unitById: Map<String, OrchestratedUnit>,
    ) {
        questions.forEach { assertQuestionQuality(it, movies, unitById) }
        val subjects = questions.map { it.question.subject }.toSet()
        if (subjects.size < 3 || subjects.size > 8) {
            throw AiOrchestrateError.invalidResponse("AI quiz subject coverage is invalid")
        }
    }

    // ------------------------------------------------------------------
    // 槽位断言与题面回接（对拍 assertUnitMatchesSlot / assertQuestionMatchesSlot /
    // groundQuestionToUnit）
    // ------------------------------------------------------------------

    /** 单元槽位一致性断言（错误消息为中文，与 TS 一致）。 */
    fun assertUnitMatchesSlot(unit: OrchestratedUnit, slot: UnitSlot) {
        if (unit.unitId != "unit-" + slot.index) {
            throw AiOrchestrateError.invalidResponse("单元 unitId 必须是 unit-${slot.index}")
        }
        if (unit.subjectGroup != slot.subjectGroup) {
            throw AiOrchestrateError.invalidResponse("单元 subjectGroup 必须是 ${slot.subjectGroup}")
        }
        if (unit.subject !in slot.allowedSubjects) {
            throw AiOrchestrateError.invalidResponse("单元 subject 不在该槽位允许的学科目录里")
        }
        if (slot.movieTitle != null && unit.relatedMediaTitle?.trim() != slot.movieTitle) {
            throw AiOrchestrateError.invalidResponse("单元 relatedMedia.title 必须逐字等于「${slot.movieTitle}」")
        }
    }

    /** 题目槽位一致性断言。 */
    fun assertQuestionMatchesSlot(orchestrated: OrchestratedQuestion, slot: QuestionSlot, unit: OrchestratedUnit) {
        val question = orchestrated.question
        if (question.type.name.lowercase() != slot.type.lowercase()) {
            throw AiOrchestrateError.invalidResponse("题目题型必须与槽位一致（${slot.type}）")
        }
        if (question.difficulty != slot.difficulty) {
            throw AiOrchestrateError.invalidResponse("题目难度必须与槽位一致（${slot.difficulty}）")
        }
        if (orchestrated.unitId != unit.unitId) {
            throw AiOrchestrateError.invalidResponse("题目 unitId 必须指向「${unit.unitId}」")
        }
        if (AiOrchestrateJson.normalizeSemanticKey(question.concept) !=
            AiOrchestrateJson.normalizeSemanticKey(unit.concept)
        ) {
            throw AiOrchestrateError.invalidResponse("题目 concept 必须与单元概念一致")
        }
    }

    /**
     * 题面回接到单元：证据、概念锚点、解析链逐项补齐（对拍 groundQuestionToUnit 六步）。
     * 修的是「模型漏写锚点」类小错，不改变题目语义。
     */
    fun groundQuestionToUnit(
        orchestrated: OrchestratedQuestion,
        unit: OrchestratedUnit,
        movies: List<OrchestrateMovie>,
    ): OrchestratedQuestion {
        var question = orchestrated.question
        // ① 证据必须包含单元 filmEvidence。
        if (unit.filmEvidence.isNotBlank() &&
            !AiOrchestrateJson.containsEvidenceText(
                question.evidenceUsed, unit.filmEvidence, AiOrchestrateJson.quizEvidenceFragments(unit.filmEvidence),
            )
        ) {
            question = question.copy(evidenceUsed = unit.filmEvidence + "——" + question.evidenceUsed)
        }
        // ② 题干+选项要出现概念锚点。
        val surface = AiOrchestrateJson.normalizeSemanticKey(
            question.prompt + question.options.joinToString("") { it.text },
        )
        val anchors = listOf(question.concept, question.knowledgePoint, unit.concept)
            .map { AiOrchestrateJson.normalizeSemanticKey(it) }
            .filter { it.isNotEmpty() }
        val anchored = anchors.any { surface.contains(it) }
        if (!anchored) {
            question = question.copy(prompt = "关于“" + unit.concept + "”，" + question.prompt)
        }
        // ③ 题干要能锚回影片证据。
        val movie = movies.firstOrNull { it.title == question.sourceTitle }
        if (movie != null &&
            !AiOrchestrateJson.containsEvidenceAnchor(question.prompt, movie.year, movie.genres, movie.evidence) &&
            unit.filmEvidence.isNotBlank()
        ) {
            question = question.copy(prompt = "已看记录里写着“" + unit.filmEvidence + "”。" + question.prompt)
        }
        // ④⑤ 解析/干扰项要落到考点上。
        val anchor = question.knowledgePoint.ifBlank { unit.concept }
        if (!rationaleIsQuestionSpecific(question.answerRationale, question)) {
            question = question.copy(answerRationale = "本题考点是“" + anchor + "”：" + question.answerRationale)
        }
        if (question.type != AiQuizQuestionType.SHORT &&
            !rationaleIsQuestionSpecific(question.distractorRationale, question)
        ) {
            question = question.copy(
                distractorRationale = "干扰项都没有落到“" + anchor + "”上：" + question.distractorRationale,
            )
        }
        // ⑥ 解析要有推理链。
        if (!containsLearningChain(question)) {
            question = question.copy(explanation = "用“" + unit.concept + "”来看，" + question.explanation.orEmpty())
        }
        return OrchestratedQuestion(question, orchestrated.correctAnswer, orchestrated.unitId)
    }

    // ------------------------------------------------------------------
    // 槽位降级题（对拍 SLOT_FALLBACK_SUFFIXES / slotFallbackPrompt /
    // slotFallbackOptions / fallbackQuestionForSlot / degradedQuestion）
    // ------------------------------------------------------------------

    /** 兜底考点后缀：按角度给概念加不同尾缀，避免跨槽位 knowledgePoint 撞车。 */
    private val SLOT_FALLBACK_SUFFIXES: Map<String, List<String>> = mapOf(
        "象征" to listOf("的象征含义", "的意象作用", "的符号指向"),
        "因果" to listOf("的因果链", "的连锁后果", "的触发条件"),
        "对比" to listOf("的对照维度", "的差异焦点", "的反差处理"),
        "应用" to listOf("的迁移用法", "的新情境适用", "的判断入口"),
        "机制解释" to listOf("的运作机制", "的实现环节", "的作用路径"),
        "证据辨识" to listOf("的证据边界", "的材料来源", "的依据范围"),
    )

    /** 片名展示兜底：原名行带 CJK 时优先用原名（对拍 fallbackDisplayTitle）。 */
    fun fallbackDisplayTitle(movie: OrchestrateMovie): String {
        val cjk = Regex("[\\u3040-\\u30ff\\u3400-\\u4dbf\\u4e00-\\u9fff\\uac00-\\ud7af]")
        if (cjk.containsMatchIn(movie.title)) return movie.title
        val original = movie.evidence.firstOrNull { it.startsWith("原名：") }
            ?.removePrefix("原名：")?.trim()
        return if (original != null && cjk.containsMatchIn(original)) original else movie.title
    }

    private fun slotFallbackPrompt(
        question: AiQuizQuestion,
        unit: OrchestratedUnit,
        movie: OrchestrateMovie,
        angleType: String,
    ): String {
        val evidence = unit.filmEvidence
        val anchor = "《" + fallbackDisplayTitle(movie) + "》的已看记录里写着“" + evidence + "”"
        val opening = "关于“" + question.knowledgePoint + "”，" + anchor + "。"
        val body = when (angleType) {
            "象征" -> "这处细节在片中承载的意义最接近哪一项？"
            "因果" -> "这一设定最直接导致的后果是哪一项？"
            "对比" -> "把它与片中的另一组处理放在一起比，哪种判断最站得住？"
            "应用" -> "把这一层意思用到新的观影情境，哪种说法最恰当？"
            "机制解释" -> "它之所以成立，关键在哪一环？"
            else -> "下列哪一条才是记录里真有的信息？"
        }
        var text = opening + body
        if (question.type == AiQuizQuestionType.MULTIPLE) {
            text = text.replace("哪一项？", "哪些？").replace("哪一种？", "哪些？") + "（多选）"
        }
        if (question.type == AiQuizQuestionType.SHORT) {
            text = opening + "请用一句话说明这一判断的依据。"
        }
        return text
    }

    private fun slotFallbackOptions(question: AiQuizQuestion, evidence: String): List<AiQuizOption> =
        when (question.type) {
            AiQuizQuestionType.MULTIPLE -> listOf(
                AiQuizOption("opt-a", AiOrchestrateJson.stripTrailingSentencePunctuation("判断要落回记录里的“$evidence”这一具体线索")),
                AiQuizOption("opt-b", AiOrchestrateJson.stripTrailingSentencePunctuation("结论的范围要限定在“$evidence”能支持的部分")),
                AiQuizOption("opt-c", AiOrchestrateJson.stripTrailingSentencePunctuation("凭片名和上映年份就能下结论，不必看记录里的细节")),
                AiQuizOption("opt-d", AiOrchestrateJson.stripTrailingSentencePunctuation("个人第一印象比记录里写着的细节更可靠")),
            )
            AiQuizQuestionType.SINGLE -> listOf(
                AiQuizOption("opt-a", AiOrchestrateJson.stripTrailingSentencePunctuation("判断要落回记录里的“$evidence”这一具体线索")),
                AiQuizOption("opt-b", AiOrchestrateJson.stripTrailingSentencePunctuation("凭片名和上映年份就能下结论，不必看记录里的细节")),
                AiQuizOption("opt-c", AiOrchestrateJson.stripTrailingSentencePunctuation("个人第一印象比记录里写着的细节更可靠")),
                AiQuizOption("opt-d", AiOrchestrateJson.stripTrailingSentencePunctuation("这一层意思与影片内容无关，只是评论者的额外联想")),
            )
            AiQuizQuestionType.SHORT -> emptyList()
        }

    /**
     * 槽位兜底题：LLM 反复失败时用单元 filmEvidence 造一道证据核辨题，
     * 保住「13 题 = 10 单选 + 2 多选 + 1 简答」的不变量。
     */
    fun fallbackQuestionForSlot(
        slot: QuestionSlot,
        unit: OrchestratedUnit,
        movie: OrchestrateMovie,
        movieIndex: Int,
        variant: Int,
    ): OrchestratedQuestion {
        val type = AiQuizQuestionType.entries.firstOrNull { it.name.equals(slot.type, ignoreCase = true) } ?: AiQuizQuestionType.SINGLE
        val suffixes = SLOT_FALLBACK_SUFFIXES[slot.angleType] ?: SLOT_FALLBACK_SUFFIXES.values.first()
        val evidence = unit.filmEvidence
        val knowledgePoint = (unit.concept + suffixes[variant % suffixes.size]).take(24)
        val title = unit.relatedMediaTitle ?: movie.title
        val question = AiQuizQuestion(
            id = "q" + slot.index.toString().padStart(2, '0'),
            type = type,
            prompt = "",
            options = emptyList(),
            mediaTitle = movie.title,
            quote = null,
            maxScore = when (type) {
                AiQuizQuestionType.SHORT -> 0
                AiQuizQuestionType.MULTIPLE -> 10
                AiQuizQuestionType.SINGLE -> 8
            },
            difficulty = slot.difficulty,
            knowledgePoint = knowledgePoint,
            sourceTitle = title,
            answerRationale = "本题考察“" + knowledgePoint + "”：正确选项回到记录里的“" + evidence +
                "”，判断依据只在这段材料能支撑的范围内。",
            distractorRationale = if (type == AiQuizQuestionType.SHORT) {
                ""
            } else {
                "其余选项都离开了“" + knowledgePoint + "”要求的依据来源，要么只看片名年份，" +
                    "要么用个人印象替换记录里的细节，都不能作为本题的判断依据。"
            },
            explanation = "记录里写着“" + evidence + "”。把它对应到“" + unit.concept + "”，因此" +
                knowledgePoint + "这一层意思只能从这段材料出发去判断。",
            correctOptionIds = emptyList(),
            subject = unit.subject,
            concept = unit.concept,
            learningTakeaway = "读“" + knowledgePoint + "”时，用记录里的具体线索核对判断，而不是凭印象下结论。",
            evidenceUsed = "《" + title + "》的已看记录提供了“" + evidence + "”这一具体线索。",
            answerKeywords = if (type == AiQuizQuestionType.SHORT) {
                listOf(knowledgePoint, "已看记录", "具体线索", unit.concept, "判断依据")
            } else {
                emptyList()
            },
            unitId = unit.unitId,
        )
        var question2 = question.copy(prompt = slotFallbackPrompt(question, unit, movie, slot.angleType))
        if (type != AiQuizQuestionType.SHORT) {
            question2 = question2.copy(options = slotFallbackOptions(question2, evidence))
            question2 = question2.copy(
                correctOptionIds = if (type == AiQuizQuestionType.MULTIPLE) listOf("opt-a", "opt-b") else listOf("opt-a"),
            )
        }
        val correctAnswer = when (type) {
            AiQuizQuestionType.MULTIPLE -> null
            AiQuizQuestionType.SINGLE -> "opt-a"
            AiQuizQuestionType.SHORT ->
                "记录里的“" + evidence + "”是本题的直接依据，结论只应限定在它能支持的范围里。"
        }
        // 判分依据同时写进题目本体（缓存判分零损）与 OrchestratedQuestion。
        return OrchestratedQuestion(question2.copy(correctAnswer = correctAnswer), correctAnswer)
    }

    // ------------------------------------------------------------------
    // detail 归一化（对拍 normalizeDetailOutput）
    // ------------------------------------------------------------------

    /** detail 单次输出校验：等级/长文本/理由条数，失败抛 INVALID_RESPONSE。 */
    fun normalizeDetailOutput(
        value: JsonElement?,
        scene: String,
        environment: com.tracktosearch.data.ai.prompt.DetailEnvironmentInput?,
        profileVersion: Long,
    ): com.tracktosearch.data.ai.AiDetailAnalysis {
        val record = value as? JsonObject
            ?: throw AiOrchestrateError.invalidResponse("AI detail must be an object")
        fun readLevel(field: String): AiDetailInterestLevel {
            val raw = (record[field] as? JsonPrimitive)?.contentOrNull?.trim().orEmpty()
            return AiDetailInterestLevel.entries.firstOrNull { it.name.equals(raw, ignoreCase = true) }
                ?: throw AiOrchestrateError.invalidResponse("AI level is invalid")
        }
        fun readText(field: String, max: Int): String {
            val raw = (record[field] as? JsonPrimitive)?.contentOrNull?.trim().orEmpty()
            if (raw.isEmpty() || raw.length > max) {
                throw AiOrchestrateError.invalidResponse("AI $field is invalid")
            }
            return raw
        }
        fun readReasons(field: String, fallback: List<String>? = null): List<String> {
            val array = record[field] as? JsonArray
            if (array == null) {
                return fallback ?: throw AiOrchestrateError.invalidResponse("AI reasons are invalid")
            }
            if (array.size > 4) {
                throw AiOrchestrateError.invalidResponse("AI reasons are invalid")
            }
            return array.map { item ->
                val raw = (item as? JsonPrimitive)?.contentOrNull?.trim().orEmpty()
                if (raw.isEmpty() || raw.length > 180) {
                    throw AiOrchestrateError.invalidResponse("AI reasons are invalid")
                }
                raw
            }
        }
        val interestLevel = readLevel("interestLevel")
        val confidence = readLevel("confidence")
        val spoilerFreeSummary = readText("spoilerFreeSummary", 1200)
        val publicRatingInterpretation = readText("publicRatingInterpretation", 1200)
        val watchAdvice = readText("watchAdvice", 1200)
        val reasons = readReasons("reasons")
        var watchTiming = AiDetailWatchTiming.UNKNOWN
        var watchTimingBasis: List<String> = emptyList()
        if (scene == "WATCHLIST_CONTEXT") {
            val timingRecord = record["watchTiming"]?.takeIf { it !is JsonNull } as? JsonObject
            if (timingRecord == null) {
                watchTiming = AiDetailWatchTiming.entries.firstOrNull {
                    it.name == timingLevel(environment)
                } ?: AiDetailWatchTiming.UNKNOWN
                watchTimingBasis = timingBasis(environment)
            } else {
                val rawLevel = (timingRecord["level"] as? JsonPrimitive)?.contentOrNull?.trim().orEmpty()
                watchTiming = AiDetailWatchTiming.entries.firstOrNull {
                    it.name.equals(rawLevel, ignoreCase = true)
                } ?: AiDetailWatchTiming.entries.firstOrNull { it.name == timingLevel(environment) }
                    ?: AiDetailWatchTiming.UNKNOWN
                val fallbackBasis = timingBasis(environment)
                watchTimingBasis = if (timingRecord["basis"] == null || timingRecord["basis"] is JsonNull) {
                    fallbackBasis
                } else {
                    readReasons("basis", fallbackBasis)
                }
            }
        }
        return com.tracktosearch.data.ai.AiDetailAnalysis(
            scene = scene,
            interestLevel = interestLevel,
            confidence = confidence,
            spoilerFreeSummary = spoilerFreeSummary,
            publicRatingInterpretation = publicRatingInterpretation,
            watchAdvice = watchAdvice,
            reasons = reasons,
            watchTiming = watchTiming,
            watchTimingBasis = watchTimingBasis,
            profileVersion = profileVersion,
        )
    }

    /** 排序回填：模型只给 orderedMediaKeys，缺的垫底、重复去重（对拍 applyModelOrder）。 */
    fun normalizeRankOrder(
        value: JsonElement?,
        candidates: List<com.tracktosearch.data.ai.prompt.DetailCandidate>,
    ): List<com.tracktosearch.data.ai.prompt.DetailCandidate> {
        val record = value as? JsonObject ?: return candidates
        val keys = (record["orderedMediaKeys"] as? JsonArray)
            ?.mapNotNull { (it as? JsonPrimitive)?.contentOrNull }
            ?: return candidates
        val byKey = candidates.associateBy { it.mediaKey }
        val ordered = ArrayList<com.tracktosearch.data.ai.prompt.DetailCandidate>()
        val seen = HashSet<String>()
        for (key in keys) {
            val candidate = byKey[key] ?: continue
            if (seen.add(candidate.mediaKey)) ordered.add(candidate)
        }
        for (candidate in candidates) {
            if (seen.add(candidate.mediaKey)) ordered.add(candidate)
        }
        return ordered
    }
}
