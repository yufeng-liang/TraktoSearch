package com.tracktosearch.data.util

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/** UploadToastPolicy 纯函数单测（无 Android 依赖） */
class UploadToastPolicyTest {

    @Test
    fun success_prev为Uploading_触发toast() {
        val result = UploadToastPolicy.shouldNotify(
            prev = UploadState.Uploading,
            current = UploadState.Success,
            crashCount = 0,
        )
        assertThat(result).isTrue()
    }

    @Test
    fun success_首次挂载即终态且有崩溃_触发toast() {
        val result = UploadToastPolicy.shouldNotify(
            prev = null,
            current = UploadState.Success,
            crashCount = 1,
        )
        assertThat(result).isTrue()
    }

    @Test
    fun success_首次挂载即终态但无崩溃_不触发toast() {
        val result = UploadToastPolicy.shouldNotify(
            prev = null,
            current = UploadState.Success,
            crashCount = 0,
        )
        assertThat(result).isFalse()
    }

    @Test
    fun failed_prev为Uploading_触发toast() {
        val result = UploadToastPolicy.shouldNotify(
            prev = UploadState.Uploading,
            current = UploadState.Failed("network error"),
            crashCount = 0,
        )
        assertThat(result).isTrue()
    }

    @Test
    fun failed_首次挂载即终态且有崩溃_触发toast() {
        val result = UploadToastPolicy.shouldNotify(
            prev = null,
            current = UploadState.Failed("network error"),
            crashCount = 2,
        )
        assertThat(result).isTrue()
    }

    @Test
    fun idle或uploading_不触发toast() {
        assertThat(
            UploadToastPolicy.shouldNotify(null, UploadState.Idle, crashCount = 5)
        ).isFalse()
        assertThat(
            UploadToastPolicy.shouldNotify(UploadState.Idle, UploadState.Uploading, crashCount = 5)
        ).isFalse()
        assertThat(
            UploadToastPolicy.shouldNotify(UploadState.Success, UploadState.Uploading, crashCount = 5)
        ).isFalse()
    }
}
