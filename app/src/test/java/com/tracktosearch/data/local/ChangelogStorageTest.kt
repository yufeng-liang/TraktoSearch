package com.tracktosearch.data.local

import android.app.Application
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * ChangelogStorage 键版本测试。
 *
 * 锁住缓存 key 的版本号：全量日志缓存没有 TTL、命中即返回，
 * 而 clear() 全项目无人调用——不换 key，重写后的日志对老用户永远不可见。
 * 这条测试的价值在于「有人改回 v2 时立刻变红」。
 *
 * application 必须指定成裸 Application：默认会用 AndroidManifest 里的 TrakSearchApp，
 * 它注入 Hilt 单例图时会走到 DatabaseModule 的 SQLiteDatabase.loadLibs()，
 * 本机没有桌面版 sqlcipher 原生库，四个用例会全部以 UnsatisfiedLinkError 收场。
 * 本测试只需要一个真实的 DataStore 目录，不需要 Hilt 图。
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], application = Application::class)
class ChangelogStorageTest {

    private val context: Context = ApplicationProvider.getApplicationContext<Context>()
    private val storage = ChangelogStorage(context)

    private fun prefName(field: String): String {
        val f = ChangelogStorage::class.java.getDeclaredField(field).apply { isAccessible = true }
        val key = f.get(storage)
        return key.javaClass.getMethod("getName").invoke(key) as String
    }

    @Test
    fun 全量日志写入与读取走同一键() = runTest {
        storage.clear()
        storage.saveCompleteChangelog("## v1.0.0 更新内容（2026-01-01）\n- 内容")
        assertThat(storage.getChangelog()).contains("v1.0.0")
        assertThat(storage.isFullChangelogComplete()).isTrue()
    }

    @Test
    fun 缓存键名已升到v3() {
        // 反射读运行时真实 key：字段被改名会直接 NoSuchField 报错，而不是静默漏测；
        // 期望值故意钉死 v3，这样实现回退到 v2 时测试立刻变红。
        assertThat(prefName("key")).isEqualTo("full_changelog_v3")
    }

    @Test
    fun 完整性标记键名已升到v3() {
        assertThat(prefName("fullChangelogCompleteKey")).isEqualTo("full_changelog_complete_v3")
    }

    @Test
    fun clear后完整性标记回到false() = runTest {
        storage.saveCompleteChangelog("## v1.0.0 更新内容（2026-01-01）\n- 内容")
        storage.clear()
        assertThat(storage.isFullChangelogComplete()).isFalse()
    }
}
