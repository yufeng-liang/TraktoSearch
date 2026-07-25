package com.tracktosearch.data.auth

import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runTest
import org.junit.Test

class AuthRefreshCoordinatorTest {
    @Test
    fun concurrentFailuresReuseRotatedToken() = runTest {
        val coordinator = AuthRefreshCoordinator()
        var accessToken = "old-token"
        var refreshCalls = 0
        val firstStarted = CompletableDeferred<Unit>()
        val releaseFirst = CompletableDeferred<Unit>()

        val first = async {
            coordinator.refreshIfNeeded("old-token", { accessToken }) {
                refreshCalls++
                firstStarted.complete(Unit)
                releaseFirst.await()
                accessToken = "new-token"
                true
            }
        }
        firstStarted.await()

        val second = async {
            coordinator.refreshIfNeeded("old-token", { accessToken }) {
                refreshCalls++
                true
            }
        }
        releaseFirst.complete(Unit)

        assertThat(first.await()).isTrue()
        assertThat(second.await()).isTrue()
        assertThat(refreshCalls).isEqualTo(1)
    }
}
