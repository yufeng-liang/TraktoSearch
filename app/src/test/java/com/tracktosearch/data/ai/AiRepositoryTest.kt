package com.tracktosearch.data.ai

import com.google.common.truth.Truth.assertThat
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import org.junit.Test
import retrofit2.Response

class AiRepositoryTest {

    @Test
    fun activationNameNormalizer_acceptsTraditionalAndPinyinLikeInput() {
        val character = AiCharacterCatalog.all.single { it.id == "usagi" }

        assertThat(AiActivationNormalizer.normalize(" 烏薩奇！ ")).isEqualTo("乌萨奇")
        assertThat(AiActivationNormalizer.matches(character, "wusaqi")).isTrue()
    }

    @Test
    fun storageKey_containsSchemaAndSeparatesFriends() {
        val first = AiStorageKey.forFriend("friend-a", AiCacheFeature.GREETING)
        val second = AiStorageKey.forFriend("friend-b", AiCacheFeature.GREETING)

        assertThat(first).startsWith("ai_v1_greeting_")
        assertThat(first).contains("friend-a")
        assertThat(first).isNotEqualTo(second)
    }

    @Test
    fun activationDomain_preservesServerActivationPhrase() {
        val activation = AiActivationDto(
            activated = true,
            activationPhrase = "Activated!"
        ).toDomain()

        assertThat(activation.activationPhrase).isEqualTo("Activated!")
    }

    @Test
    fun repository_mapsDailyQuotaErrorCode() = runTest {
        val api = mockk<AiApiService>()
        val storage = mockk<AiStorage>(relaxed = true)
        coEvery { api.getGreeting(any()) } returns Response.success(
            AiApiResponse<AiGreetingDto>(
                code = "DAILY_QUOTA_EXCEEDED",
                message = "daily quota reached",
                requestId = "req-1",
                data = null
            )
        )
        val repository = AiRepository(api, storage, Json { ignoreUnknownKeys = true })

        val result = repository.getGreeting("friend-a", "usagi", forceRefresh = true)

        assertThat(result.isFailure).isTrue()
        val error = result.exceptionOrNull() as AiApiException
        assertThat(error.errorCode).isEqualTo(AiErrorCode.QUOTA_EXCEEDED)
        assertThat(error.serverCode).isEqualTo("DAILY_QUOTA_EXCEEDED")
    }
}
