package com.tracktosearch.ui.util

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class ScrollToTopProviderTest {

    @Test
    fun scrollToTop_afterRegister_invokesAction() {
        val provider = ScrollToTopProvider()
        var invoked = false
        provider.register { invoked = true }

        provider.scrollToTop()

        assertThat(invoked).isTrue()
    }

    @Test
    fun scrollToTop_afterUnregister_doesNotInvokeAction() {
        val provider = ScrollToTopProvider()
        var invoked = false
        provider.register { invoked = true }
        provider.unregister()

        provider.scrollToTop()

        assertThat(invoked).isFalse()
    }

    @Test
    fun scrollToTop_withoutRegister_doesNotThrow() {
        val provider = ScrollToTopProvider()
        // 无 register 直接调用，应安全无异常
        provider.scrollToTop()
    }

    @Test
    fun register_twice_secondActionTakesEffect() {
        val provider = ScrollToTopProvider()
        var firstInvoked = false
        var secondInvoked = false
        provider.register { firstInvoked = true }
        provider.register { secondInvoked = true }

        provider.scrollToTop()

        // 源码用单个 var currentAction 存储，后注册的覆盖前者
        assertThat(secondInvoked).isTrue()
        assertThat(firstInvoked).isFalse()
    }
}
