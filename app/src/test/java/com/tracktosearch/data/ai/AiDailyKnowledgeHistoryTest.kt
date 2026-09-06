package com.tracktosearch.data.ai

import android.app.Application
import android.content.Context
import com.google.common.truth.Truth.assertThat
import com.tracktosearch.data.repository.TmdbRepository
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.time.LocalDate
import java.util.UUID

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [33])
class AiDailyKnowledgeHistoryTest {

    private val context: Context = RuntimeEnvironment.getApplication()
    private val api = mockk<AiApiService>(relaxed = true)
    private val json = Json {
        ignoreUnknownKeys = true
        coerceInputValues = true
        encodeDefaults = true
    }
    private lateinit var storage: AiStorage
    private lateinit var repository: AiRepository
    private lateinit var friendId: String

    @Before
    fun setUp() = runTest {
        friendId = "daily-history-${UUID.randomUUID()}"
        val preferences = context.getSharedPreferences(
            "ai-daily-history-${UUID.randomUUID()}",
            Context.MODE_PRIVATE
        )
        storage = AiStorage(context, preferences)
        repository = AiRepository(
            api = api,
            storage = storage,
            json = json,
            tmdbRepository = mockk<TmdbRepository>(relaxed = true),
            context = context
        )
    }

    private fun knowledge(
        unitId: String,
        title: String = unitId,
        locale: String = AiDailyKnowledgeContract.DEFAULT_LOCALE,
        includeQuestion: Boolean = true
    ): AiDailyKnowledge = AiDailyKnowledge(
        id = "$unitId-response",
        unitId = unitId,
        title = title,
        fact = "$title 的事实",
        explanation = "$title 的解释",
        sourceName = "来源",
        sourceUrl = "",
        publishedAt = null,
        characterLine = null,
        locale = locale,
        subject = "社会心理学",
        concept = "从众压力",
        relatedMediaTitle = "十二怒汉",
        checkQuestion = if (includeQuestion) {
            AiDailyCheckQuestion(
                prompt = "谁先降低异议成本？",
                options = listOf(
                    AiQuizOption("a", "第一个异议者"),
                    AiQuizOption("b", "沉默多数")
                ),
                correctOptionIds = listOf("a", "b"),
                explanation = "答案来自影片场景。"
            )
        } else {
            null
        }
    )

    @Test
    fun shownHistory_repeatsSameUnitOnSameDayWithoutOverwriting() = runTest {
        val date = LocalDate.of(2026, 9, 6)
        val first = repository.markDailyKnowledgeShown(
            friendId,
            knowledge("unit-a", title = "首条"),
            shownDate = date
        )
        val second = repository.markDailyKnowledgeShown(
            friendId,
            knowledge("unit-a", title = "第二版"),
            shownDate = date
        )

        val history = repository.readDailyKnowledgeHistory(friendId)
        assertThat(first).isNotNull()
        assertThat(second).isEqualTo(first)
        assertThat(history).hasSize(1)
        assertThat(history.single().knowledge.title).isEqualTo("首条")
        assertThat(history.single().relatedMedia?.title).isEqualTo("十二怒汉")
        assertThat(repository.readDailyKnowledgeForDate(friendId, date)?.title).isEqualTo("首条")
        coVerify(exactly = 0) { api.getDailyKnowledge(any()) }
    }

    @Test
    fun shownHistory_appendsDifferentUnitsAndSeparatesLocales() = runTest {
        val date = LocalDate.of(2026, 9, 6)
        repository.markDailyKnowledgeShown(friendId, knowledge("unit-a"), shownDate = date)
        repository.markDailyKnowledgeShown(friendId, knowledge("unit-b"), shownDate = date)
        repository.markDailyKnowledgeShown(
            friendId,
            knowledge("unit-c", locale = "en-US"),
            shownDate = date
        )

        val history = repository.readDailyKnowledgeHistory(friendId)
        assertThat(history.map { it.unitId }).containsExactly("unit-c", "unit-b", "unit-a")
        assertThat(repository.readDailyKnowledgeForDate(friendId, date)?.unitId).isEqualTo("unit-b")
        assertThat(
            repository.readDailyKnowledgeForDate(friendId, date, locale = "en-US")?.unitId
        ).isEqualTo("unit-c")
        assertThat(
            repository.readDailyKnowledgeForDate(friendId, date, locale = "ja-JP")
        ).isNull()
    }

    @Test
    fun questionAnswer_keepsFirstResultAndStoresFeedbackOffline() = runTest {
        val date = LocalDate.of(2026, 9, 6)
        repository.markDailyKnowledgeShown(friendId, knowledge("unit-a"), shownDate = date)

        val firstAnswer = repository.recordDailyKnowledgeQuestionAnswer(
            friendId = friendId,
            unitId = "unit-a",
            selectedOptionIds = listOf("a"),
            shownDate = date
        )
        val secondAnswer = repository.recordDailyKnowledgeQuestionAnswer(
            friendId = friendId,
            unitId = "unit-a",
            selectedOptionIds = listOf("a", "b"),
            shownDate = date
        )
        repository.saveDailyKnowledgeContentFeedback(
            friendId = friendId,
            unitId = "unit-a",
            feedback = AiDailyKnowledgeContentFeedback.TOO_BROAD,
            shownDate = date
        )
        repository.saveDailyKnowledgeDifficultyFeedback(
            friendId = friendId,
            unitId = "unit-a",
            difficulty = AiQuizDifficulty.HARD,
            shownDate = date
        )

        val record = repository.readDailyKnowledgeHistory(friendId).single()
        assertThat(firstAnswer?.questionResult?.correct).isFalse()
        assertThat(secondAnswer).isEqualTo(firstAnswer)
        assertThat(record.questionCompleted).isTrue()
        assertThat(record.questionResult?.selectedOptionIds).containsExactly("a")
        assertThat(record.questionResult?.correct).isFalse()
        assertThat(record.knowledge.checkQuestion?.correctOptionIds).containsExactly("a", "b").inOrder()
        assertThat(record.contentFeedback).isEqualTo(AiDailyKnowledgeContentFeedback.TOO_BROAD)
        assertThat(record.difficultyFeedback).isEqualTo(AiQuizDifficulty.HARD)
        coVerify(exactly = 0) { api.getDailyKnowledge(any()) }
    }

    @Test
    fun historyRetention_keepsSmallerOfThirtyDaysOrFiftyRecords() = runTest {
        val referenceDate = LocalDate.of(2026, 9, 6)
        val seed = (0 until 70).map { index ->
            val shownDate = if (index < 60) {
                referenceDate.minusDays((index % 30).toLong())
            } else {
                referenceDate.minusDays(30L + (index - 60))
            }
            AiDailyKnowledgeHistoryRecord(
                unitId = "seed-$index",
                shownDate = shownDate.toString(),
                locale = AiDailyKnowledgeContract.DEFAULT_LOCALE,
                relatedMedia = AiDailyRelatedMedia(title = "电影 $index"),
                subject = "社会心理学",
                concept = "概念 $index",
                knowledge = knowledge("seed-$index"),
                sequence = index.toLong(),
                shownAt = index.toLong(),
                updatedAt = index.toLong()
            )
        }
        storage.write(
            friendId,
            AiCacheFeature.DAILY_KNOWLEDGE_HISTORY,
            json.encodeToString(
                AiDailyKnowledgeHistory.serializer(),
                AiDailyKnowledgeHistory(records = seed)
            )
        )

        repository.markDailyKnowledgeShown(
            friendId,
            knowledge("unit-new"),
            shownDate = referenceDate
        )

        val history = repository.readDailyKnowledgeHistory(friendId)
        assertThat(history).hasSize(AiDailyKnowledgeContract.HISTORY_MAX_ENTRIES)
        assertThat(history.first().unitId).isEqualTo("unit-new")
        assertThat((24..29).map { "seed-$it" }).containsNoneIn(history.map { it.unitId })
        assertThat((55 until 70).map { "seed-$it" }).containsNoneIn(history.map { it.unitId })
        assertThat(history.any { LocalDate.parse(it.shownDate).isBefore(referenceDate.minusDays(29L)) }).isFalse()
    }

    @Test
    fun unsupportedHistoryVersion_isNotReadOrOverwritten() = runTest {
        val unsupportedJson = """{"schemaVersion":99,"records":[]}"""
        storage.write(
            friendId,
            AiCacheFeature.DAILY_KNOWLEDGE_HISTORY,
            unsupportedJson
        )

        assertThat(repository.readDailyKnowledgeHistory(friendId)).isEmpty()
        val inserted = repository.markDailyKnowledgeShown(
            friendId,
            knowledge("unit-a"),
            shownDate = LocalDate.of(2026, 9, 6)
        )

        assertThat(inserted).isNull()
        assertThat(storage.read(friendId, AiCacheFeature.DAILY_KNOWLEDGE_HISTORY))
            .isEqualTo(unsupportedJson)
        assertThat(
            AiStorageKey.forFriend(friendId, AiCacheFeature.DAILY_KNOWLEDGE)
        ).isNotEqualTo(
            AiStorageKey.forFriend(friendId, AiCacheFeature.DAILY_KNOWLEDGE_HISTORY)
        )
    }
}
