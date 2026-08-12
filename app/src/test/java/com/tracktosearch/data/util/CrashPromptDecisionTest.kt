package com.tracktosearch.data.util

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class CrashPromptDecisionTest {

    @Test
    fun 无崩溃_无动作() {
        assertThat(CrashPromptDecision.decide(crashCount = 0, enabled = false, prompted = false))
            .isEqualTo(CrashPromptDecision.Action.None)
    }

    @Test
    fun 未授权未弹窗_弹授权() {
        assertThat(CrashPromptDecision.decide(crashCount = 1, enabled = false, prompted = false))
            .isEqualTo(CrashPromptDecision.Action.Authorize)
    }

    @Test
    fun 拒绝过_清日志() {
        assertThat(CrashPromptDecision.decide(crashCount = 1, enabled = false, prompted = true))
            .isEqualTo(CrashPromptDecision.Action.ClearLogs)
    }

    @Test
    fun 已授权_自动上传() {
        assertThat(CrashPromptDecision.decide(crashCount = 1, enabled = true, prompted = false))
            .isEqualTo(CrashPromptDecision.Action.AutoUpload)
    }

    @Test
    fun 已授权且弹过窗_自动上传() {
        assertThat(CrashPromptDecision.decide(crashCount = 2, enabled = true, prompted = true))
            .isEqualTo(CrashPromptDecision.Action.AutoUpload)
    }
}
