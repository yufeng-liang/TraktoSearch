package com.tracktosearch.ui.theme

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class MeshPresetDefaultTest {

    @Test
    fun fromStorage_fallsBackToBloomNotNebula() {
        // 星云已改为霉粉彩蛋解锁内容，不能再当默认值，否则未解锁用户开动效跑的是看不见的选项
        assertThat(MeshPreset.fromStorage(null)).isEqualTo(MeshPreset.BLOOM)
        assertThat(MeshPreset.fromStorage("NOT_A_PRESET")).isEqualTo(MeshPreset.BLOOM)
        assertThat(MeshPreset.fromStorage("NEBULA")).isEqualTo(MeshPreset.NEBULA)
    }
}
