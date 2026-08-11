package com.tracktosearch.data.auth

import android.content.Context
import com.google.common.truth.Truth.assertThat
import com.tracktosearch.data.ai.AiCacheFeature
import com.tracktosearch.data.ai.AiStorage
import com.tracktosearch.data.repository.TraktRepository
import com.tracktosearch.data.local.TokenStorage
import io.mockk.every
import io.mockk.mockk
import io.mockk.coEvery
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import retrofit2.Response
import java.lang.reflect.Constructor
import java.lang.reflect.Method
import kotlin.coroutines.Continuation
import kotlin.coroutines.intrinsics.COROUTINE_SUSPENDED
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import javax.inject.Provider

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class AuthManagerAiCacheTest {

    @Test
    fun initialize_restoresPersistedFriendIdForOfflineCache() = runTest {
        val context = RuntimeEnvironment.getApplication()
        val aiStorage = testStorage(context)
        val friendId = "offline-friend-${System.nanoTime()}"
        invokeSuspend(aiStorage, "saveCurrentFriendId", friendId)

        val tokenStorage = mockk<TokenStorage>(relaxed = true)
        coEvery { tokenStorage.ensureCacheLoaded() } returns Unit
        every { tokenStorage.getCachedDeviceId() } returns "device-id"
        every { tokenStorage.getCachedNextCheckAt() } returns System.currentTimeMillis() / 1000 + 3_600
        every { tokenStorage.getCachedLastOnlineAt() } returns System.currentTimeMillis() / 1000
        every { tokenStorage.getCachedAccessToken() } returns "access-token"
        coEvery { tokenStorage.isTokenValid() } returns true
        coEvery { tokenStorage.getRefreshToken() } returns "refresh-token"

        val manager = newManager(
            api = mockk(),
            tokenStorage = tokenStorage,
            aiStorage = aiStorage,
        )

        manager.initialize()

        assertThat(manager.friendId.value).isEqualTo(friendId)
        invokeSuspend(aiStorage, "clearCurrentFriendId")
    }

    @Test
    fun deauthorize_clearsFriendScopedAiCacheAndPersistedIdentity() = runTest {
        val context = RuntimeEnvironment.getApplication()
        val aiStorage = testStorage(context)
        val friendId = "logout-friend-${System.nanoTime()}"
        invokeSuspend(aiStorage, "saveCurrentFriendId", friendId)
        invokeSuspend(aiStorage, "write", friendId, AiCacheFeature.GREETING, "{\"greeting\":\"private\"}", null)

        val manager = newManager(
            api = mockk(),
            tokenStorage = mockk(relaxed = true),
            aiStorage = aiStorage,
        )

        manager.deauthorize()

        assertThat(invokeSuspend(aiStorage, "readCurrentFriendId")).isNull()
        assertThat(invokeSuspend(aiStorage, "read", friendId, AiCacheFeature.GREETING, null)).isNull()
    }

    @Test
    fun check_clearsPreviousFriendCacheBeforePersistingNewIdentity() = runTest {
        val context = RuntimeEnvironment.getApplication()
        val aiStorage = testStorage(context)
        val previousFriendId = "switch-old-${System.nanoTime()}"
        invokeSuspend(aiStorage, "saveCurrentFriendId", previousFriendId)
        invokeSuspend(aiStorage, "write", previousFriendId, AiCacheFeature.TASTE, "{\"private\":true}", null)

        val api = mockk<AuthApiService>()
        coEvery { api.check(any()) } returns Response.success(
            GatewayResponse(
                code = "SUCCESS",
                message = "OK",
                data = CheckResponse(
                    authorized = true,
                    friendId = "switch-new-${System.nanoTime()}",
                    deviceId = "device-id",
                    nickname = "new friend",
                    deviceStatus = "ACTIVE",
                    nextCheckAt = System.currentTimeMillis() / 1000 + 3_600,
                    configVersion = 1,
                )
            )
        )
        val manager = newManager(api = api, tokenStorage = mockk(relaxed = true), aiStorage = aiStorage)

        manager.check()

        assertThat(invokeSuspend(aiStorage, "read", previousFriendId, AiCacheFeature.TASTE, null)).isNull()
        assertThat(invokeSuspend(aiStorage, "readCurrentFriendId")).isEqualTo(manager.friendId.value)
        invokeSuspend(aiStorage, "clearCurrentFriendId")
    }

    private fun newManager(
        api: AuthApiService,
        tokenStorage: TokenStorage,
        aiStorage: AiStorage,
    ): AuthManager {
        val traktRepository = mockk<TraktRepository>(relaxed = true)
        val provider = mockk<Provider<TraktRepository>>()
        every { provider.get() } returns traktRepository
        val constructor = AuthManager::class.java.declaredConstructors.firstOrNull { constructor ->
            constructor.parameterTypes.any { it == AiStorage::class.java }
        }
        assertThat(constructor).isNotNull()
        constructor!!.isAccessible = true
        val arguments = constructor.parameterTypes.map { type ->
            when {
                type == AuthApiService::class.java -> api
                type == DeviceKeyManager::class.java -> mockk<DeviceKeyManager>(relaxed = true)
                type == DeviceContinuityManager::class.java -> mockk<DeviceContinuityManager>(relaxed = true)
                type == TokenStorage::class.java -> tokenStorage
                type == Json::class.java -> Json
                type == AiStorage::class.java -> aiStorage
                Provider::class.java.isAssignableFrom(type) -> provider
                else -> error("Unexpected AuthManager constructor parameter: $type")
            }
        }.toTypedArray()
        return constructor.newInstance(*arguments) as AuthManager
    }

    private fun testStorage(context: Context): AiStorage = AiStorage(
        context,
        context.getSharedPreferences("ai-auth-test-${System.nanoTime()}", Context.MODE_PRIVATE)
    )

    private suspend fun invokeSuspend(target: Any, name: String, vararg arguments: Any?): Any? =
        suspendCancellableCoroutine { continuation ->
            val method = target.javaClass.methods.firstOrNull { candidate ->
                candidate.name == name && candidate.parameterTypes.lastOrNull() == Continuation::class.java
            }
            if (method == null) {
                continuation.resumeWithException(AssertionError("Missing suspend method $name"))
                return@suspendCancellableCoroutine
            }
            method.isAccessible = true
            val invocationArguments = arrayOfNulls<Any?>(arguments.size + 1)
            arguments.copyInto(invocationArguments)
            invocationArguments[arguments.size] = continuation
            val returned = runCatching {
                method.invoke(target, *invocationArguments)
            }.getOrElse { error ->
                continuation.resumeWithException(error.cause ?: error)
                return@suspendCancellableCoroutine
            }
            if (returned !== COROUTINE_SUSPENDED && continuation.isActive) {
                continuation.resume(returned)
            }
        }
}
