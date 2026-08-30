package com.tracktosearch

import com.google.common.truth.Truth.assertThat
import com.tracktosearch.data.remote.panhub.PanHubPlugin
import com.tracktosearch.data.repository.SyncMode
import org.junit.Test

class EnumsTest {

    @Test
    fun syncMode_containsExactlyTwoValues() {
        val values = SyncMode.values().map { it.name }.toSet()
        assertThat(values).containsExactly(
            "INCREMENTAL_WITH_CHANGES",
            "FULL_REWRITE"
        )
    }

    @Test
    fun panHubPlugin_idsAreUnique() {
        val ids = PanHubPlugin.values().map { it.id }
        assertThat(ids).containsNoDuplicates()
    }

}
