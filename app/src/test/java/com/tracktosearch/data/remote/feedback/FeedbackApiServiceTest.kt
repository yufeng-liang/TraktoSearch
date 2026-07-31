package com.tracktosearch.data.remote.feedback

import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Before
import org.junit.Test
import retrofit2.Retrofit
import retrofit2.converter.kotlinx.serialization.asConverterFactory

class FeedbackApiServiceTest {
    private lateinit var server: MockWebServer
    private lateinit var api: FeedbackApiService

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
        api = Retrofit.Builder()
            .baseUrl(server.url("/"))
            .addConverterFactory(
                Json { ignoreUnknownKeys = true }
                    .asConverterFactory("application/json".toMediaType())
            )
            .build()
            .create(FeedbackApiService::class.java)
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    @Test
    fun getMessages_decodesWorkerResponseWithScreenshotArray() = runTest {
        server.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setBody(
                    """{"code":"SUCCESS","message":"OK","requestId":"r1","data":{"messages":[{"id":"m1","feedbackId":"f1","displayId":"BUG001","type":"BUG","authorRole":"developer","content":"测试回复","screenshots":[],"createdAt":1785260618,"isUnread":true}],"limit":50,"offset":0,"total":1,"hasMore":false}}"""
                )
        )

        val result = api.getMessages()

        val message = result.body()?.data?.messages?.single()
        assertThat(message?.feedback_id).isEqualTo("f1")
        assertThat(message?.display_id).isEqualTo("BUG001")
        assertThat(message?.author_role).isEqualTo("developer")
        assertThat(message?.screenshots).isEmpty()
        assertThat(message?.created_at).isEqualTo(1785260618L)
        assertThat(message?.is_unread).isTrue()
    }

    @Test
    fun getDetail_decodesConversationReplyScreenshotArray() = runTest {
        server.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setBody(
                    """{"code":"SUCCESS","message":"OK","requestId":"r1","data":{"feedback":{"id":"f1","friend_id":"friend1","friend_nickname":"friend","device_id":null,"trakt_username":null,"douban_username":null,"type":"BUG","content":"问题描述","contact":null,"screenshots":null,"app_version":"1.0","os_version":"14","device_model":"Pixel","status":"REPLIED","created_at":1700000000,"displayId":"BUG001","lastReadAt":1700000000},"replies":[{"id":"reply1","authorRole":"developer","content":"已处理","screenshots":[],"createdAt":1700000100}]}}"""
                )
        )

        val result = api.getDetail("f1")
        val detail = result.body()?.data

        assertThat(detail?.feedback?.display_id).isEqualTo("BUG001")
        assertThat(detail?.replies?.single()?.author_role).isEqualTo("developer")
        assertThat(detail?.replies?.single()?.screenshots).isEmpty()
        assertThat(detail?.replies?.single()?.created_at).isEqualTo(1700000100L)
    }

    @Test
    fun reply_decodesWorkerReplyIdResponse() = runTest {
        server.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setBody("""{"code":"SUCCESS","message":"OK","requestId":"r1","data":{"replyId":"reply1","createdAt":1700000100}}""")
        )

        val result = api.reply("f1", ReplyRequest("继续补充"))

        assertThat(result.body()?.data).isNotNull()
        assertThat(server.takeRequest().path).isEqualTo("/feedback-api/f1/reply")
    }
}
