package com.tracktosearch.data.local

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class TicketStubStorageTest {

    private val storage =
        TicketStubStorage(ApplicationProvider.getApplicationContext<Context>())

    private fun stub(nickname: String = "小明") = TicketStub(
        nickname = nickname,
        // 20696 是 2026-08-31 的 epoch day
        issuedEpochDay = 20_696L,
        hall = 2,
        row = 7,
        seat = 12,
    )

    @After
    fun tearDown() = runTest {
        // preferencesDataStore 委托挂在 Context 上、同一进程内共享同一个文件，
        // 不清会把这里写的票根漏给后面的用例。
        storage.clear()
    }

    @Test
    fun `写入的票根能原样读回`() = runTest {
        val saved = stub()
        storage.save(saved)

        assertThat(storage.stub.first()).isEqualTo(saved)
    }

    @Test
    fun `没有票根时读到 null`() = runTest {
        storage.clear()

        assertThat(storage.stub.first()).isNull()
    }

    @Test
    fun `清空后不再返回旧票根`() = runTest {
        storage.save(stub())
        storage.clear()

        assertThat(storage.stub.first()).isNull()
    }

    @Test
    fun `昵称为空串仍算一张有效票根`() = runTest {
        // 静默恢复路径拿不到昵称，此时票根只是少印一行名字，不该整张作废
        storage.save(stub(nickname = ""))

        val restored = storage.stub.first()
        assertThat(restored).isNotNull()
        assertThat(restored?.nickname).isEmpty()
        assertThat(restored?.hall).isEqualTo(2)
    }
}
