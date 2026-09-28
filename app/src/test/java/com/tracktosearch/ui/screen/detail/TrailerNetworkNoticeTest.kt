package com.tracktosearch.ui.screen.detail

import android.app.Application
import android.content.Context
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performScrollToIndex
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.unit.dp
import androidx.test.core.app.ApplicationProvider
import coil.Coil
import coil.ImageLoader
import coil.intercept.Interceptor
import coil.request.ErrorResult
import coil.request.ImageResult
import com.tracktosearch.R
import com.tracktosearch.data.remote.tmdb.dto.TmdbVideo
import com.tracktosearch.ui.component.rememberShimmer
import java.io.IOException
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.awaitCancellation
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], application = Application::class)
class TrailerNetworkNoticeTest {
    @get:Rule
    val composeRule = createComposeRule()

    private val context: Context = ApplicationProvider.getApplicationContext()
    private val requests = AtomicInteger()
    private lateinit var originalLoader: ImageLoader
    private lateinit var testLoader: ImageLoader

    @Before
    fun setUp() {
        originalLoader = Coil.imageLoader(context)
        testLoader = ImageLoader.Builder(context)
            .components {
                add(object : Interceptor {
                    override suspend fun intercept(chain: Interceptor.Chain): ImageResult {
                        // 第一次封面请求失败；列表回收后重建的请求停在加载中，复现提示消失窗口。
                        if (requests.incrementAndGet() == 1) {
                            return ErrorResult(null, chain.request, IOException("Thumbnail unavailable"))
                        }
                        awaitCancellation()
                    }
                })
            }
            .build()
        Coil.setImageLoader(testLoader)
    }

    @After
    fun tearDown() {
        Coil.setImageLoader(originalLoader)
        testLoader.shutdown()
    }

    @Test
    fun network_notice_survives_thumbnail_reload_after_scroll() {
        val video = TmdbVideo(key = "M7lc1UVf-VE", name = "Trailer", site = "YouTube", type = "Trailer")
        composeRule.setContent {
            MaterialTheme {
                val shimmer = rememberShimmer(subtle = true)
                val bounds = remember { mutableMapOf<Int, androidx.compose.ui.geometry.Rect>() }
                LazyColumn(Modifier.height(280.dp).testTag("test_detail_list")) {
                    item(key = "trailers") {
                        VideosAndImagesSection(
                            videos = listOf(video),
                            backdrops = emptyList(),
                            backdropBounds = bounds,
                            shimmer = shimmer
                        )
                    }
                    items(30) { Box(Modifier.height(160.dp)) }
                }
            }
        }

        val hint = context.getString(R.string.trailer_youtube_restricted)
        composeRule.waitUntil(5_000) { requests.get() >= 1 }
        composeRule.onNodeWithText(hint).assertIsDisplayed()
        composeRule.onNodeWithTag("test_detail_list").performScrollToIndex(25)
        composeRule.onNodeWithTag("test_detail_list").performScrollToIndex(0)
        composeRule.waitUntil(5_000) { requests.get() >= 2 }
        composeRule.onNodeWithText(hint).assertIsDisplayed()
    }

    @Test
    fun notice_hidden_while_browsing_backdrops_and_shown_at_trailers() {
        val video = TmdbVideo(key = "M7lc1UVf-VE", name = "Trailer", site = "YouTube", type = "Trailer")
        val backdrops = List(5) { "https://image.tmdb.org/t/p/w780/backdrop_$it.jpg" }
        composeRule.setContent {
            MaterialTheme {
                val shimmer = rememberShimmer(subtle = true)
                val bounds = remember { mutableMapOf<Int, androidx.compose.ui.geometry.Rect>() }
                VideosAndImagesSection(
                    videos = listOf(video),
                    backdrops = backdrops,
                    backdropBounds = bounds,
                    shimmer = shimmer
                )
            }
        }

        // 初始停在截图区，提示不得露头（行内条目定高，滚动不引起行高跳变）
        composeRule.onNodeWithTag("trailer_network_notice").assertIsNotDisplayed()

        // 横滑到预告片栏，提示随预告片一起出现
        composeRule.onNodeWithTag("videos_images_row")
            .performScrollToNode(hasTestTag("trailer_network_notice"))
        composeRule.onNodeWithTag("trailer_network_notice").assertIsDisplayed()
    }
}
