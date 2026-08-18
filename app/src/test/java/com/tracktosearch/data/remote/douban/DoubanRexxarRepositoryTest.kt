package com.tracktosearch.data.remote.douban

import androidx.datastore.preferences.preferencesDataStore
import com.google.common.truth.Truth.assertThat
import androidx.datastore.preferences.core.longPreferencesKey
import com.tracktosearch.data.repository.DoubanPublicCommentsDocument
import com.tracktosearch.data.repository.DoubanPublicDataPoolManager
import com.tracktosearch.data.remote.douban.dto.DoubanRexxarCollectionPage
import com.tracktosearch.data.remote.douban.dto.DoubanRexxarDetailDto
import com.tracktosearch.data.remote.douban.dto.DoubanRexxarCoverDto
import com.tracktosearch.data.remote.douban.dto.DoubanRexxarImageDto
import com.tracktosearch.data.remote.douban.dto.DoubanRexxarImageVariantDto
import com.tracktosearch.data.remote.douban.dto.DoubanRexxarInterestDto
import com.tracktosearch.data.remote.douban.dto.DoubanRexxarInterestPageDto
import com.tracktosearch.data.remote.douban.dto.DoubanRexxarRatingDto
import com.tracktosearch.data.remote.douban.dto.DoubanRexxarPhotoDto
import com.tracktosearch.data.remote.douban.dto.DoubanRexxarPhotoPageDto
import com.tracktosearch.data.remote.douban.dto.DoubanRexxarUserDto
import com.tracktosearch.data.util.PersistentTtlCache
import com.tracktosearch.data.util.persistentTtlCache
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.flow.first
import kotlinx.serialization.json.Json
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.ResponseBody.Companion.toResponseBody
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import retrofit2.Response
import retrofit2.Retrofit
import retrofit2.converter.kotlinx.serialization.asConverterFactory

private val android.content.Context.rexxarTestDataStore by preferencesDataStore(
    name = "douban_rexxar_repository_test"
)

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class DoubanRexxarRepositoryTest {

    private val json = Json {
        ignoreUnknownKeys = true
        coerceInputValues = true
    }
    private val mediaType = "application/json".toMediaType()
    private var mockWebServer: MockWebServer? = null

    @After
    fun tearDown() {
        mockWebServer?.shutdown()
    }

    @Test
    fun apiService_usesMoviePathAndDoubanMobileHeaders() = runTest {
        val server = MockWebServer().also {
            it.start()
            mockWebServer = it
        }
        server.enqueue(
            MockResponse().setBody(
                """
                {"id":"1295644","title":"这个杀手不太冷","type":"movie",
                 "pubdate":["1994-09-14"],"genres":["剧情"],
                 "rating":{"value":9.4,"count":10,"max":10},
                 "pic":{"large":"https://img.example/large.jpg"}}
                """.trimIndent()
            )
        )
        val service = createApiService(server)

        val response = service.getDetail("movie", "1295644")
        val request = server.takeRequest()

        assertThat(response.isSuccessful).isTrue()
        assertThat(request.path).isEqualTo("/movie/1295644")
        assertThat(request.getHeader("User-Agent")).contains("Android")
        assertThat(request.getHeader("Referer")).isEqualTo("https://m.douban.com/")
    }

    @Test
    fun getDetail_decodesActualActorsAndIntroFieldsFromJson() = runTest {
        val server = MockWebServer().also {
            it.start()
            mockWebServer = it
        }
        server.enqueue(
            MockResponse().setBody(
                """
                {
                  "id":"1295644",
                  "title":"这个杀手不太冷",
                  "original_title":"Léon",
                  "type":"movie",
                  "year":"1994",
                  "genres":["剧情","动作","犯罪"],
                  "directors":[{"name":"吕克·贝松"}],
                  "writers":[{"name":"吕克·贝松"}],
                  "actors":[{"name":"让·雷诺"},{"name":"娜塔莉·波特曼"}],
                  "intro":"里昂是一名职业杀手。",
                  "countries":["法国","美国"],
                  "languages":["英语","意大利语","法语"],
                  "durations":["110分钟"],
                  "aka":["终极追杀令(台)"],
                  "imdb":"tt0110413",
                  "pic":{"large":"https://img.example/poster.jpg"},
                  "rating":{"value":9.4,"count":2568677,"max":10}
                }
                """.trimIndent()
            )
        )

        val detail = createRepository(createApiService(server))
            .getDetail("1295644", DoubanRexxarMediaType.MOVIE)
            .getOrThrow()
        val request = server.takeRequest()

        assertThat(request.path).isEqualTo("/movie/1295644")
        assertThat(detail.originalTitle).isEqualTo("Léon")
        assertThat(detail.directors).containsExactly("吕克·贝松")
        assertThat(detail.writers).containsExactly("吕克·贝松")
        assertThat(detail.cast).containsExactly("让·雷诺", "娜塔莉·波特曼").inOrder()
        assertThat(detail.summary).isEqualTo("里昂是一名职业杀手。")
        assertThat(detail.countries).containsExactly("法国", "美国").inOrder()
        assertThat(detail.languages).containsExactly("英语", "意大利语", "法语").inOrder()
        assertThat(detail.runtime).isEqualTo("110分钟")
        assertThat(detail.aka).containsExactly("终极追杀令(台)")
        assertThat(detail.imdbId).isEqualTo("tt0110413")
        assertThat(detail.poster?.largeUrl).isEqualTo("https://img.example/poster.jpg")
    }

    @Test
    fun apiService_usesPhotoPathAndPagination() = runTest {
        val server = MockWebServer().also {
            it.start()
            mockWebServer = it
        }
        server.enqueue(
            MockResponse().setBody(
                """
                {
                  "total":3,
                  "start":2,
                  "count":1,
                  "photos":[
                    {"id":"p3","image":{"large":{"url":"https://img.example/p3.jpg"}}}
                  ]
                }
                """.trimIndent()
            )
        )

        val response = createApiService(server).getPhotos("movie", "1295644", start = 2, count = 1)
        val request = server.takeRequest()

        assertThat(response.isSuccessful).isTrue()
        assertThat(request.path).isEqualTo("/movie/1295644/photos?start=2&count=1")
        assertThat(response.body()?.photos?.single()?.id).isEqualTo("p3")
        assertThat(response.body()?.photos?.single()?.image?.large?.url)
            .isEqualTo("https://img.example/p3.jpg")
    }

    @Test
    fun getDetail_mapsScoreYearGenresAndPoster() = runTest {
        val repository = createRepository(FakeRexxarService().apply {
            detailResponses += Response.success(
                DoubanRexxarDetailDto(
                    id = "1295644",
                    title = "这个杀手不太冷",
                    originalTitle = "Léon",
                    type = "movie",
                    pubdate = listOf("1994-09-14"),
                    genres = listOf("剧情", "动作"),
                    directors = json.parseToJsonElement("""[{"name":"吕克·贝松"}]"""),
                    writers = json.parseToJsonElement("""[{"name":"吕克·贝松"}]"""),
                    casts = json.parseToJsonElement("""[{"name":"让·雷诺"},{"name":"娜塔莉·波特曼"}]"""),
                    summary = "里昂是一名职业杀手。",
                    countries = json.parseToJsonElement("""["法国","美国"]"""),
                    languages = json.parseToJsonElement("""["英语","意大利语","法语"]"""),
                    durations = json.parseToJsonElement("""["110分钟","133分钟(中国大陆)"]"""),
                    aka = json.parseToJsonElement("""["终极追杀令(台)","杀手莱昂"]"""),
                    imdb = "tt0110413",
                    rating = DoubanRexxarRatingDto(value = 9.4, count = 2568677, max = 10),
                    cover = DoubanRexxarCoverDto(
                        image = DoubanRexxarImageDto(
                            large = DoubanRexxarImageVariantDto(url = "https://img.example/large.jpg"),
                            normal = DoubanRexxarImageVariantDto(url = "https://img.example/normal.jpg"),
                            small = DoubanRexxarImageVariantDto(url = "https://img.example/small.jpg")
                        )
                    )
                )
            )
        })

        val result = repository.getDetail("1295644", DoubanRexxarMediaType.MOVIE)

        assertThat(result.isSuccess).isTrue()
        val detail = result.getOrThrow()
        assertThat(detail.doubanId).isEqualTo("1295644")
        assertThat(detail.title).isEqualTo("这个杀手不太冷")
        assertThat(detail.originalTitle).isEqualTo("Léon")
        assertThat(detail.type).isEqualTo(DoubanRexxarMediaType.MOVIE)
        assertThat(detail.score).isEqualTo(9.4)
        assertThat(detail.ratingCount).isEqualTo(2568677)
        assertThat(detail.year).isEqualTo("1994")
        assertThat(detail.genres).containsExactly("剧情", "动作").inOrder()
        assertThat(detail.directors).containsExactly("吕克·贝松")
        assertThat(detail.writers).containsExactly("吕克·贝松")
        assertThat(detail.cast).containsExactly("让·雷诺", "娜塔莉·波特曼").inOrder()
        assertThat(detail.summary).isEqualTo("里昂是一名职业杀手。")
        assertThat(detail.countries).containsExactly("法国", "美国").inOrder()
        assertThat(detail.languages).containsExactly("英语", "意大利语", "法语").inOrder()
        assertThat(detail.initialReleaseDates).containsExactly("1994-09-14")
        assertThat(detail.durations).containsExactly("110分钟", "133分钟(中国大陆)").inOrder()
        assertThat(detail.runtime).isEqualTo("110分钟")
        assertThat(detail.aka).containsExactly("终极追杀令(台)", "杀手莱昂").inOrder()
        assertThat(detail.imdbId).isEqualTo("tt0110413")
        assertThat(detail.poster?.largeUrl).isEqualTo("https://img.example/large.jpg")
        assertThat(detail.poster?.normalUrl).isEqualTo("https://img.example/normal.jpg")
        assertThat(detail.poster?.smallUrl).isEqualTo("https://img.example/small.jpg")
    }

    @Test
    fun getDetail_mapsSingularStringFallbackFields() = runTest {
        val repository = createRepository(FakeRexxarService().apply {
            detailResponses += Response.success(
                DoubanRexxarDetailDto(
                    id = "100",
                    title = "测试条目",
                    director = json.parseToJsonElement("\"导演\""),
                    writer = json.parseToJsonElement("\"编剧\""),
                    cast = json.parseToJsonElement("\"演员\""),
                    region = json.parseToJsonElement("\"中国大陆\""),
                    language = json.parseToJsonElement("\"汉语普通话\""),
                    duration = "120分钟",
                    alias = json.parseToJsonElement("\"别名\""),
                    releaseDate = json.parseToJsonElement("\"2025-01-01\""),
                    imdbId = "tt1234567",
                    rating = DoubanRexxarRatingDto(average = 8.8, count = 12)
                )
            )
        })

        val detail = repository.getDetail("100", DoubanRexxarMediaType.MOVIE).getOrThrow()

        assertThat(detail.score).isEqualTo(8.8)
        assertThat(detail.directors).containsExactly("导演")
        assertThat(detail.writers).containsExactly("编剧")
        assertThat(detail.cast).containsExactly("演员")
        assertThat(detail.countries).containsExactly("中国大陆")
        assertThat(detail.languages).containsExactly("汉语普通话")
        assertThat(detail.durations).containsExactly("120分钟")
        assertThat(detail.runtime).isEqualTo("120分钟")
        assertThat(detail.aka).containsExactly("别名")
        assertThat(detail.initialReleaseDates).containsExactly("2025-01-01")
        assertThat(detail.imdbId).isEqualTo("tt1234567")
    }

    @Test
    fun getDetail_usesCardSubtitleWhenYearIsMissing() = runTest {
        val repository = createRepository(FakeRexxarService().apply {
            detailResponses += Response.success(
                DoubanRexxarDetailDto(
                    id = "100",
                    title = "测试剧集",
                    type = "tv",
                    cardSubtitle = "2025 / 中国大陆 / 剧情",
                    genres = listOf("剧情")
                )
            )
        })

        val result = repository.getDetail("100", DoubanRexxarMediaType.TV)

        assertThat(result.getOrThrow().year).isEqualTo("2025")
        assertThat(result.getOrThrow().type).isEqualTo(DoubanRexxarMediaType.TV)
    }

    @Test
    fun getDetail_readsPublicPoolBeforeRexxar() = runTest {
        val publicPool = mockk<DoubanPublicDataPoolManager>()
        val cachedDetail = DoubanRexxarDetail(
            doubanId = "public-detail",
            title = "公共池详情",
            type = DoubanRexxarMediaType.MOVIE,
            score = 9.1
        )
        coEvery {
            publicPool.getDetail("public-detail", DoubanRexxarMediaType.MOVIE)
        } returns cachedDetail
        val service = FakeRexxarService()

        val result = createRepository(service, publicDataPoolManager = publicPool)
            .getDetail("public-detail", DoubanRexxarMediaType.MOVIE)

        assertThat(result).isEqualTo(Result.success(cachedDetail))
        assertThat(service.detailCallCount).isEqualTo(0)
        coVerify(exactly = 1) {
            publicPool.getDetail("public-detail", DoubanRexxarMediaType.MOVIE)
        }
    }

    @Test
    fun getPhotos_readsPublicPoolBeforeRexxar() = runTest {
        val publicPool = mockk<DoubanPublicDataPoolManager>()
        val cachedPhotos = DoubanRexxarPhotoCacheEntry(
            total = 2,
            lastFetchedAt = System.currentTimeMillis(),
            photos = listOf(
                DoubanRexxarPhoto(id = "public-p1", position = 0),
                DoubanRexxarPhoto(id = "public-p2", position = 1)
            )
        )
        coEvery {
            publicPool.getPhotos("public-photos", DoubanRexxarMediaType.MOVIE)
        } returns cachedPhotos
        val service = FakeRexxarService()

        val result = createRepository(service, publicDataPoolManager = publicPool)
            .getPhotos("public-photos", DoubanRexxarMediaType.MOVIE, start = 0, count = 2)

        assertThat(result.getOrThrow().photos.map { it.id })
            .containsExactly("public-p1", "public-p2")
            .inOrder()
        assertThat(service.photoCallCount).isEqualTo(0)
        coVerify(exactly = 1) {
            publicPool.getPhotos("public-photos", DoubanRexxarMediaType.MOVIE)
        }
    }

    @Test
    fun getShortComments_readsFreshPublicPoolBeforeRexxar() = runTest {
        val publicPool = mockk<DoubanPublicDataPoolManager>()
        val publicPage = DoubanRexxarShortCommentPage(
            total = 1,
            start = 0,
            count = 1,
            comments = listOf(DoubanRexxarShortComment(id = "public-comment", text = "公共短评"))
        )
        coEvery {
            publicPool.getComments("public-comments", DoubanRexxarMediaType.MOVIE, 0, 20)
        } returns DoubanPublicCommentsDocument(
            page = publicPage,
            fetchedAt = System.currentTimeMillis()
        )
        val service = FakeRexxarService()

        val result = createRepository(service, publicDataPoolManager = publicPool)
            .getShortComments("public-comments", DoubanRexxarMediaType.MOVIE, start = 0, count = 20)

        assertThat(result.getOrThrow()).isEqualTo(publicPage)
        coVerify(exactly = 1) {
            publicPool.getComments("public-comments", DoubanRexxarMediaType.MOVIE, 0, 20)
        }
    }

    @Test
    fun getShortComments_preservesPublicFetchedAtAsPersistentExpiry() = runTest {
        val context = RuntimeEnvironment.getApplication().applicationContext
        val prefix = "test_comments_expiry_${System.nanoTime()}"
        val commentsCache = persistentTtlCache<DoubanRexxarShortCommentPage>(
            ttlMillis = 6 * 60 * 60 * 1000L,
            maxSize = 100,
            dataStore = context.rexxarTestDataStore,
            json = json,
            keyPrefix = prefix,
            scope = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.Unconfined)
        )
        val publicPool = mockk<DoubanPublicDataPoolManager>()
        val commentsTtlMillis = 6 * 60 * 60 * 1000L
        val fetchedAt = System.currentTimeMillis() - commentsTtlMillis / 2
        coEvery {
            publicPool.getComments("public-comments", DoubanRexxarMediaType.MOVIE, 0, 20)
        } returns DoubanPublicCommentsDocument(
            page = DoubanRexxarShortCommentPage(
                total = 1,
                start = 0,
                count = 1,
                comments = listOf(DoubanRexxarShortComment(id = "public-comment"))
            ),
            fetchedAt = fetchedAt
        )

        val result = createRepository(
            service = FakeRexxarService(),
            commentsCache = commentsCache,
            publicDataPoolManager = publicPool
        ).getShortComments("public-comments", DoubanRexxarMediaType.MOVIE, 0, 20)

        assertThat(result.isSuccess).isTrue()
        coVerify(exactly = 1) {
            publicPool.getComments("public-comments", DoubanRexxarMediaType.MOVIE, 0, 20)
        }
        Thread.sleep(100)
        val preferences = context.rexxarTestDataStore.data.first()
        val expireAt = preferences[longPreferencesKey("$prefix:movie:public-comments:0:20:exp")]
        assertThat(expireAt).isNotNull()
        assertThat(expireAt!!).isAtMost(fetchedAt + commentsTtlMillis + 1_000L)
    }

    @Test
    fun getPhotos_mergesLaterPageIntoPersistentUrlSet() = runTest {
        val service = FakeRexxarService().apply {
            photoResponses += Response.success(
                DoubanRexxarPhotoPageDto(
                    total = 3,
                    start = 0,
                    count = 2,
                    photos = listOf(photoDto("p1"), photoDto("p2"))
                )
            )
            photoResponses += Response.success(
                DoubanRexxarPhotoPageDto(
                    total = 3,
                    start = 2,
                    count = 1,
                    photos = listOf(photoDto("p3"))
                )
            )
        }
        val repository = createRepository(service)

        val firstPage = repository.getPhotos("1295644", DoubanRexxarMediaType.MOVIE, 0, 2).getOrThrow()
        val secondPage = repository.getPhotos("1295644", DoubanRexxarMediaType.MOVIE, 2, 2).getOrThrow()
        val mergedPage = repository.getPhotos("1295644", DoubanRexxarMediaType.MOVIE, 0, 3).getOrThrow()

        assertThat(firstPage.photos.map { it.id }).containsExactly("p1", "p2").inOrder()
        assertThat(secondPage.photos.map { it.id }).containsExactly("p3")
        assertThat(mergedPage.photos.map { it.id }).containsExactly("p1", "p2", "p3").inOrder()
        assertThat(service.photoCallCount).isEqualTo(2)
    }

    @Test
    fun getPhotos_returnsRequestedPageWhenFirstRequestStartsAfterZero() = runTest {
        val service = FakeRexxarService().apply {
            photoResponses += Response.success(
                DoubanRexxarPhotoPageDto(
                    total = 3,
                    start = 2,
                    count = 1,
                    photos = listOf(photoDto("p3"))
                )
            )
        }
        val repository = createRepository(service)

        val result = repository
            .getPhotos("1295644", DoubanRexxarMediaType.MOVIE, start = 2, count = 1)
            .getOrThrow()

        assertThat(result.photos.map { it.id }).containsExactly("p3")
        assertThat(result.start).isEqualTo(2)

        val cachedResult = repository
            .getPhotos("1295644", DoubanRexxarMediaType.MOVIE, start = 2, count = 1)
            .getOrThrow()

        assertThat(cachedResult.photos.map { it.id }).containsExactly("p3")
        assertThat(service.photoCallCount).isEqualTo(1)
    }

    @Test
    fun getPhotos_concurrentDifferentPages_fetchesAndReturnsEachRequestedPage() = runTest {
        val service = FakeRexxarService().apply {
            blockFirstPhotoRequest = true
            photoResponses += Response.success(
                DoubanRexxarPhotoPageDto(
                    total = 3,
                    start = 0,
                    count = 1,
                    photos = listOf(photoDto("p1"))
                )
            )
            photoResponses += Response.success(
                DoubanRexxarPhotoPageDto(
                    total = 3,
                    start = 2,
                    count = 1,
                    photos = listOf(photoDto("p3"))
                )
            )
        }
        val repository = createRepository(service)
        val first = async {
            repository.getPhotos("1295644", DoubanRexxarMediaType.MOVIE, start = 0, count = 1).getOrThrow()
        }
        service.firstPhotoRequestStarted.await()
        val second = async(start = CoroutineStart.UNDISPATCHED) {
            repository.getPhotos("1295644", DoubanRexxarMediaType.MOVIE, start = 2, count = 1).getOrThrow()
        }

        service.releaseFirstPhotoRequest.complete(Unit)

        assertThat(first.await().photos.map { it.id }).containsExactly("p1")
        assertThat(second.await().photos.map { it.id }).containsExactly("p3")
        assertThat(service.photoCallCount).isEqualTo(2)
    }

    @Test
    fun getPhotos_refreshesStaleCollectionWithoutDroppingPreviousUrls() = runTest {
        val service = FakeRexxarService().apply {
            photoResponses += Response.success(
                DoubanRexxarPhotoPageDto(
                    total = 2,
                    start = 1,
                    count = 1,
                    photos = listOf(photoDto("p2"))
                )
            )
        }
        val context = RuntimeEnvironment.getApplication().applicationContext
        val photosCache = persistentTtlCache<DoubanRexxarPhotoCacheEntry>(
            ttlMillis = 24 * 60 * 60 * 1000L,
            maxSize = 100,
            dataStore = context.rexxarTestDataStore,
            json = json,
            keyPrefix = "stale_photos_${System.nanoTime()}",
            scope = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.Unconfined)
        )
        photosCache.put(
            "movie:1295644",
            DoubanRexxarPhotoCacheEntry(
                total = 2,
                lastFetchedAt = System.currentTimeMillis() - 24 * 60 * 60 * 1000L - 1,
                photos = listOf(DoubanRexxarPhoto(id = "p1", position = 0))
            )
        )
        val repository = createRepository(service, photosCache)

        val refreshed = repository
            .getPhotos("1295644", DoubanRexxarMediaType.MOVIE, start = 1, count = 1)
            .getOrThrow()
        val allPhotos = repository
            .getPhotos("1295644", DoubanRexxarMediaType.MOVIE, start = 0, count = 2)
            .getOrThrow()

        assertThat(refreshed.photos.map { it.id }).containsExactly("p2")
        assertThat(allPhotos.photos.map { it.id }).containsExactly("p1", "p2").inOrder()
        assertThat(service.photoCallCount).isEqualTo(1)
    }

    @Test
    fun getShortComments_mapsAuthorTextAndFiveStarRating() = runTest {
        val repository = createRepository(FakeRexxarService().apply {
            interestResponses += Response.success(
                DoubanRexxarInterestPageDto(
                    total = 1,
                    start = 0,
                    count = 1,
                    interests = listOf(
                        DoubanRexxarInterestDto(
                            id = "comment-1",
                            comment = "很喜欢",
                            createTime = "2024-01-02 03:04:05",
                            rating = DoubanRexxarRatingDto(value = 4.0, max = 5),
                            user = DoubanRexxarUserDto(name = "观众")
                        )
                    )
                )
            )
        })

        val comment = repository
            .getShortComments("1295644", DoubanRexxarMediaType.MOVIE, 0, 20)
            .getOrThrow()
            .comments
            .single()

        assertThat(comment.id).isEqualTo("comment-1")
        assertThat(comment.authorName).isEqualTo("观众")
        assertThat(comment.ratingStars).isEqualTo(4)
        assertThat(comment.text).isEqualTo("很喜欢")
        assertThat(comment.createdAt).isEqualTo("2024-01-02 03:04:05")
    }

    @Test
    fun getDetail_retriesOneTimeAfterServerError() = runTest {
        val service = FakeRexxarService().apply {
            detailResponses += errorResponse(500)
            detailResponses += Response.success(DoubanRexxarDetailDto(id = "100", title = "恢复"))
        }
        val repository = createRepository(service)

        val result = repository.getDetail("100", DoubanRexxarMediaType.MOVIE)

        assertThat(result.isSuccess).isTrue()
        assertThat(service.detailCallCount).isEqualTo(2)
    }

    @Test
    fun getDetail_doesNotRetryForbidden() = runTest {
        val service = FakeRexxarService().apply {
            detailResponses += errorResponse(403)
            detailResponses += Response.success(DoubanRexxarDetailDto(id = "100", title = "不应请求"))
        }
        val repository = createRepository(service)

        val result = repository.getDetail("100", DoubanRexxarMediaType.MOVIE)

        assertThat(result.isFailure).isTrue()
        assertThat(result.exceptionOrNull()).isInstanceOf(DoubanRexxarHttpException::class.java)
        assertThat((result.exceptionOrNull() as DoubanRexxarHttpException).statusCode).isEqualTo(403)
        assertThat(service.detailCallCount).isEqualTo(1)
    }

    @Test
    fun getDetail_doesNotRetryTooManyRequests() = runTest {
        val service = FakeRexxarService().apply {
            detailResponses += errorResponse(429)
            detailResponses += Response.success(DoubanRexxarDetailDto(id = "100", title = "不应请求"))
        }
        val repository = createRepository(service)

        val result = repository.getDetail("100", DoubanRexxarMediaType.MOVIE)

        assertThat(result.isFailure).isTrue()
        assertThat((result.exceptionOrNull() as DoubanRexxarHttpException).statusCode).isEqualTo(429)
        assertThat(service.detailCallCount).isEqualTo(1)
    }

    @Test
    fun getDetail_propagatesCancellation() = runTest {
        val service = FakeRexxarService().apply {
            detailExceptions += CancellationException("cancelled")
        }
        val repository = createRepository(service)

        var thrown: CancellationException? = null
        try {
            repository.getDetail("100", DoubanRexxarMediaType.MOVIE)
        } catch (error: CancellationException) {
            thrown = error
        }

        assertThat(thrown).isNotNull()
    }

    private fun createRepository(
        service: DoubanRexxarApiService,
        photosCache: PersistentTtlCache<DoubanRexxarPhotoCacheEntry>? = null,
        commentsCache: PersistentTtlCache<DoubanRexxarShortCommentPage>? = null,
        publicDataPoolManager: DoubanPublicDataPoolManager? = null
    ): DoubanRexxarRepository {
        val context = RuntimeEnvironment.getApplication().applicationContext
        return DoubanRexxarRepository(
            service = service,
            detailCache = persistentTtlCache(
                ttlMillis = 7 * 24 * 60 * 60 * 1000L,
                maxSize = 100,
                dataStore = context.rexxarTestDataStore,
                json = json,
                keyPrefix = "test_detail_${System.nanoTime()}",
                scope = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.Unconfined)
            ),
            photosCache = photosCache ?: persistentTtlCache(
                ttlMillis = 24 * 60 * 60 * 1000L,
                maxSize = 100,
                dataStore = context.rexxarTestDataStore,
                json = json,
                keyPrefix = "test_photos_${System.nanoTime()}",
                scope = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.Unconfined)
            ),
            commentsCache = commentsCache ?: persistentTtlCache(
                ttlMillis = 6 * 60 * 60 * 1000L,
                maxSize = 100,
                dataStore = context.rexxarTestDataStore,
                json = json,
                keyPrefix = "test_comments_${System.nanoTime()}",
                scope = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.Unconfined)
            ),
            retryDelay = {},
            publicDataPoolManager = publicDataPoolManager
        )
    }

    private fun createApiService(server: MockWebServer): DoubanRexxarApiService = Retrofit.Builder()
        .baseUrl(server.url("/").toString())
        .client(
            okhttp3.OkHttpClient.Builder()
                .addInterceptor(DoubanRexxarRequestInterceptor())
                .build()
        )
        .addConverterFactory(json.asConverterFactory(mediaType))
        .build()
        .create(DoubanRexxarApiService::class.java)

    private fun photoDto(id: String): DoubanRexxarPhotoDto = DoubanRexxarPhotoDto(
        id = id,
        image = DoubanRexxarImageDto(
            large = DoubanRexxarImageVariantDto(url = "https://img.example/$id-large.jpg"),
            normal = DoubanRexxarImageVariantDto(url = "https://img.example/$id-normal.jpg"),
            small = DoubanRexxarImageVariantDto(url = "https://img.example/$id-small.jpg")
        )
    )

    private fun <T> errorResponse(code: Int): Response<T> = Response.error(
        code,
        "error".toResponseBody(mediaType)
    )

    private class FakeRexxarService : DoubanRexxarApiService {
        val detailResponses = ArrayDeque<Response<DoubanRexxarDetailDto>>()
        val photoResponses = ArrayDeque<Response<DoubanRexxarPhotoPageDto>>()
        val interestResponses = ArrayDeque<Response<DoubanRexxarInterestPageDto>>()
        val collectionResponses = ArrayDeque<Response<DoubanRexxarCollectionPage>>()
        val detailExceptions = ArrayDeque<Throwable>()
        var blockFirstPhotoRequest = false
        val firstPhotoRequestStarted = CompletableDeferred<Unit>()
        val releaseFirstPhotoRequest = CompletableDeferred<Unit>()
        var detailCallCount = 0
            private set
        var photoCallCount = 0
            private set

        override suspend fun getDetail(type: String, id: String): Response<DoubanRexxarDetailDto> {
            detailCallCount++
            detailExceptions.removeFirstOrNull()?.let { throw it }
            return detailResponses.removeFirst()
        }

        override suspend fun getPhotos(
            type: String,
            id: String,
            start: Int,
            count: Int
        ): Response<DoubanRexxarPhotoPageDto> {
            photoCallCount++
            if (blockFirstPhotoRequest && photoCallCount == 1) {
                firstPhotoRequestStarted.complete(Unit)
                releaseFirstPhotoRequest.await()
            }
            return photoResponses.removeFirst()
        }

        override suspend fun getInterests(
            type: String,
            id: String,
            start: Int,
            count: Int
        ): Response<DoubanRexxarInterestPageDto> = interestResponses.removeFirst()

        override suspend fun getCollectionItems(
            collectionId: String,
            start: Int,
            count: Int
        ): Response<DoubanRexxarCollectionPage> = collectionResponses.removeFirst()
    }
}
