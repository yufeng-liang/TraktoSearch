package com.tracktosearch.ui.screen.detail

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.assertHeightIsEqualTo
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.unit.dp
import com.google.common.truth.Truth.assertThat
import com.tracktosearch.R
import com.tracktosearch.data.repository.MultiRatings
import androidx.test.core.app.ApplicationProvider
import android.content.Context
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.Locale

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], application = android.app.Application::class)
class DetailRatingsDialogTest {

    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun doubanPublicRatingReplacesMetacriticInTheFirstRow() {
        composeRule.setContent {
            MaterialTheme {
                RatingsRow(
                    MultiRatings(
                        imdbRating = "8.0",
                        metacritic = "88%",
                        doubanRating = 9.2
                    )
                )
            }
        }

        val doubanLabel = ApplicationProvider.getApplicationContext<Context>()
            .getString(R.string.detail_info_douban_rating)
        composeRule.onNodeWithText(doubanLabel).assertIsDisplayed()
        composeRule.onNodeWithText("9.2").assertIsDisplayed()
        assertThat(composeRule.onAllNodesWithText("MTC").fetchSemanticsNodes()).isEmpty()
    }

    @Test
    fun emptyNormalDetailKeepsFourFixedSlotsAndFourPlaceholders() {
        composeRule.setContent {
            MaterialTheme {
                RatingsRow(MultiRatings())
            }
        }

        val context = ApplicationProvider.getApplicationContext<Context>()
        listOf(
            R.string.detail_info_imdb_rating,
            R.string.detail_info_metacritic_rating,
            R.string.detail_info_tmdb_rating,
            R.string.detail_info_rotten_tomatoes_rating
        ).forEach { resourceId ->
            composeRule.onNodeWithText(context.getString(resourceId)).assertIsDisplayed()
        }
        assertThat(
            composeRule.onAllNodesWithText("—").fetchSemanticsNodes()
        ).hasSize(4)
        assertThat(
            composeRule.onAllNodesWithText(
                context.getString(R.string.detail_info_douban_rating)
            ).fetchSemanticsNodes()
        ).isEmpty()
    }

    @Test
    fun normalDetailWithoutDoubanRatingUsesMetacriticSlot() {
        composeRule.setContent {
            MaterialTheme {
                RatingsRow(
                    MultiRatings(
                        imdbRating = "8.0",
                        metacritic = "88%"
                    )
                )
            }
        }

        composeRule.onNodeWithText("MTC").assertIsDisplayed()
        composeRule.onNodeWithText("88%").assertIsDisplayed()
        assertThat(
            composeRule.onAllNodesWithText(
                ApplicationProvider.getApplicationContext<Context>()
                    .getString(R.string.detail_info_douban_rating)
            ).fetchSemanticsNodes()
        ).isEmpty()
    }

    @Test
    fun `豆瓣条目没有公开评分时不回退显示MTC`() {
        composeRule.setContent {
            MaterialTheme {
                RatingsRow(
                    MultiRatings(
                        metacritic = "88%"
                    ),
                    isDoubanItem = true
                )
            }
        }

        val context = ApplicationProvider.getApplicationContext<Context>()
        listOf(
            R.string.detail_info_imdb_rating,
            R.string.detail_info_douban_rating,
            R.string.detail_info_tmdb_rating,
            R.string.detail_info_rotten_tomatoes_rating
        ).forEach { resourceId ->
            composeRule.onNodeWithText(context.getString(resourceId)).assertIsDisplayed()
        }
        assertThat(
            composeRule.onAllNodesWithText("—").fetchSemanticsNodes()
        ).hasSize(4)
        assertThat(composeRule.onAllNodesWithText("MTC").fetchSemanticsNodes()).isEmpty()
        assertThat(composeRule.onAllNodesWithText("88%").fetchSemanticsNodes()).isEmpty()
    }

    @Test
    fun tenPointRatingTiersRenderValuesInFixedSlots() {
        composeRule.setContent {
            MaterialTheme {
                RatingsRow(
                    MultiRatings(
                        imdbRating = "5.9",
                        doubanRating = 6.0,
                        tmdbRating = 7.5
                    )
                )
            }
        }

        composeRule.onNodeWithText("IMDb").assertIsDisplayed()
        composeRule.onNodeWithText(
            ApplicationProvider.getApplicationContext<Context>()
                .getString(R.string.detail_info_douban_rating)
        ).assertIsDisplayed()
        composeRule.onNodeWithText("TMDB").assertIsDisplayed()
        composeRule.onNodeWithText("RT").assertIsDisplayed()
        composeRule.onNodeWithText("5.9").assertIsDisplayed()
        composeRule.onNodeWithText(String.format(Locale.getDefault(), "%.1f", 6.0)).assertIsDisplayed()
        composeRule.onNodeWithText(String.format(Locale.getDefault(), "%.1f", 7.5)).assertIsDisplayed()
        assertThat(composeRule.onAllNodesWithText("—").fetchSemanticsNodes()).hasSize(1)
    }

    @Test
    fun doubanRatingUsesActiveLocaleForDecimalSeparator() {
        val previousLocale = Locale.getDefault()
        try {
            Locale.setDefault(Locale.GERMANY)
            composeRule.setContent {
                MaterialTheme {
                    RatingsRow(MultiRatings(doubanRating = 9.2))
                }
            }

            composeRule.onNodeWithText("9,2").assertIsDisplayed()
        } finally {
            Locale.setDefault(previousLocale)
        }
    }

    @Test
    fun invalidRatingsRenderMissingValueInsteadOfRawValues() {
        composeRule.setContent {
            MaterialTheme {
                RatingsRow(
                    MultiRatings(
                        imdbRating = "88",
                        metacritic = "88",
                        tmdbRating = 88.0,
                        rottenTomatoes = "88"
                    )
                )
            }
        }

        assertThat(composeRule.onAllNodesWithText("88").fetchSemanticsNodes()).isEmpty()
        assertThat(composeRule.onAllNodesWithText("—").fetchSemanticsNodes()).hasSize(4)
    }

    @Test
    fun invalidDoubanRatingDoesNotSuppressValidMetacriticSlot() {
        composeRule.setContent {
            MaterialTheme {
                RatingsRow(
                    MultiRatings(doubanRating = 88.0, metacritic = "88%")
                )
            }
        }

        composeRule.onNodeWithText("MTC").assertIsDisplayed()
        composeRule.onNodeWithText("88%").assertIsDisplayed()
        assertThat(
            composeRule.onAllNodesWithText(
                ApplicationProvider.getApplicationContext<Context>()
                    .getString(R.string.detail_info_douban_rating)
            ).fetchSemanticsNodes()
        ).isEmpty()
    }

    @Test
    fun unknownRatingSourceKeepsPlatformSlotInLoadingState() {
        composeRule.setContent {
            MaterialTheme {
                RatingsRow(
                    ratings = MultiRatings(metacritic = "88%"),
                    ratingSource = DetailRatingSource.UNKNOWN
                )
            }
        }

        assertThat(composeRule.onAllNodesWithText("MTC").fetchSemanticsNodes()).isEmpty()
        composeRule.onNodeWithTag(RATING_CARD_TEST_TAG).assertHeightIsEqualTo(60.dp)
    }

    @Test
    fun ratingsCardAndLoadingPlaceholderKeepFixedHeight() {
        composeRule.setContent {
            MaterialTheme {
                RatingsRow(MultiRatings())
            }
        }
        composeRule.onNodeWithTag(RATING_CARD_TEST_TAG).assertHeightIsEqualTo(60.dp)
    }

    @Test
    fun loadingPlaceholderKeepsFixedHeight() {
        composeRule.setContent {
            MaterialTheme {
                RatingsLoadingPlaceholder()
            }
        }
        composeRule.onNodeWithTag(RATING_CARD_TEST_TAG).assertHeightIsEqualTo(60.dp)
    }
}
