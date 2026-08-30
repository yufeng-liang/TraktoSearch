package com.tracktosearch.ui.screen.opensource

import com.google.common.truth.Truth.assertThat
import java.util.Base64
import org.junit.Test

class OpenSourceDataTest {

    @Test
    fun `页面中每个版本别名都来自构建期Version Catalog快照`() {
        OpenSourceData.groups
            .flatMap { it.libraries }
            .forEach { library ->
                library.versionAliases.forEach { alias ->
                    assertThat(OpenSourceVersionCatalog.version(alias)).isNotEmpty()
                }
                val expectedVersion = library.versionPrefix + library.versionAliases
                    .joinToString(" / ") { alias -> OpenSourceVersionCatalog.version(alias) }
                assertThat(library.version).isEqualTo(expectedVersion)
            }
    }

    @Test
    fun `组合版本与前缀按页面格式生成`() {
        val libraries = OpenSourceData.groups.flatMap { it.libraries }
        val compose = libraries.first { it.name == "Jetpack Compose & Material 3" }
        val kotlin = libraries.first { it.name == "Kotlin Coroutines & Serialization" }

        assertThat(compose.version).startsWith("BOM ")
        assertThat(kotlin.version).contains(" / ")
    }

    @Test
    fun `构建期快照解码保留版本别名和值`() {
        val encoded = Base64.getEncoder().encodeToString(
            "haze=2.0.0-beta02\nbackdrop=2.0.1".toByteArray(Charsets.UTF_8)
        )

        assertThat(OpenSourceVersionCatalog.decode(encoded)).containsExactly(
            "haze", "2.0.0-beta02",
            "backdrop", "2.0.1"
        )
    }
}
