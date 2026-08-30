package com.tracktosearch.data.auth

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class AuthAccessPolicyTest {
    @Test
    fun `仅有效授权和离线宽限允许进入主界面`() {
        assertThat(AuthState.AUTHORIZED.hasGatewayAccess()).isTrue()
        assertThat(AuthState.OFFLINE.hasGatewayAccess()).isTrue()
        assertThat(AuthState.UNAUTHORIZED.hasGatewayAccess()).isFalse()
        assertThat(AuthState.EXPIRED.hasGatewayAccess()).isFalse()
    }
}
