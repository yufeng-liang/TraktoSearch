package com.tracktosearch.data.local

import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import com.tracktosearch.ui.haptic.HapticMode
import org.junit.Test

/**
 * [HapticStorage.decodeMode] 的脏数据回落，以及它背后那条编译期看不见的枚举改名约束。
 *
 * 只调 companion 上的 [HapticStorage.decodeMode]，不构造 [HapticStorage]、不碰
 * `preferencesDataStore` 委托：那个委托是进程单例，各用例新建实例仍读同一份磁盘数据，
 * 跨用例互相污染。纯 JVM 跑，被测路径上一个平台调用都没有，不用 Robolectric。
 *
 * 真正吃住实现的是 `"OFF"` 与 `"BOOST"` 两条正向用例。[HapticMode.DEFAULT] 就是
 * `FOLLOW_SYSTEM`，所以一个「无论输入恒返 DEFAULT」的坏实现能同时骗过缺值、空串、
 * 大小写、未知名字这四条回落用例，还能骗过 `"FOLLOW_SYSTEM"` 那条 —— 只有另外两档
 * 能把它抓出来。
 *
 * `枚举成员名与顺序被写死` 那条守的是 [HapticStorage.decodeMode] KDoc 里那条编译期
 * 毫无提示的约束：落盘存 `name` 不存 `ordinal`，代价是枚举成员不能改名。重构工具一键
 * 改名后编译照过、测试照绿，只有老用户存的「关闭」会静默回落成跟随系统。这条断言是
 * 唯一能在 CI 上把这种改动拦下来的地方。
 *
 * 设计依据见 docs/superpowers/plans/2026-09-01-haptics-overhaul.md 的「三态开关」一节。
 */
class HapticStorageTest {

    /** 枚举名与顺序的黄金副本。刻意写死，不从 [HapticMode.entries] 推导 —— 那样改名后期望值跟着变，永远绿 */
    private val expectedNames = listOf("FOLLOW_SYSTEM", "OFF", "BOOST")

    @Test
    fun `缺值回落跟随系统`() {
        // 全新安装、或用户从没进过触感设置：DataStore 里根本没有这个 key
        assertThat(HapticStorage.decodeMode(null)).isEqualTo(HapticMode.DEFAULT)
        assertThat(HapticStorage.decodeMode(null)).isEqualTo(HapticMode.FOLLOW_SYSTEM)
    }

    @Test
    fun `三个档位的枚举名各自解析回自己那一档`() {
        assertThat(HapticStorage.decodeMode("FOLLOW_SYSTEM")).isEqualTo(HapticMode.FOLLOW_SYSTEM)
        // 这两条是唯一能区分「真的解析了」与「恒返默认档」的用例
        assertThat(HapticStorage.decodeMode("OFF")).isEqualTo(HapticMode.OFF)
        assertThat(HapticStorage.decodeMode("BOOST")).isEqualTo(HapticMode.BOOST)
    }

    @Test
    fun `空串回落跟随系统`() {
        assertThat(HapticStorage.decodeMode("")).isEqualTo(HapticMode.DEFAULT)
    }

    @Test
    fun `小写与混合大小写的枚举名回落跟随系统，valueOf 是大小写敏感的`() {
        assertWithMessage("valueOf 大小写敏感，小写名字是脏数据而不是有效值")
            .that(HapticStorage.decodeMode("follow_system")).isEqualTo(HapticMode.DEFAULT)
        assertThat(HapticStorage.decodeMode("off")).isEqualTo(HapticMode.DEFAULT)
        assertThat(HapticStorage.decodeMode("Boost")).isEqualTo(HapticMode.DEFAULT)
    }

    @Test
    fun `未知枚举名回落跟随系统，且不把异常抛给调用方`() {
        // valueOf 对未知名字抛 IllegalArgumentException，必须被 runCatching 吃掉：
        // 这条路径挂在 DataStore 的 map 里，抛出去等于一条脏数据把整个 App 弄崩
        val decoded = runCatching { HapticStorage.decodeMode("LEGACY_MODE") }

        assertWithMessage("脏数据不许让 IllegalArgumentException 逃到调用方")
            .that(decoded.exceptionOrNull()).isNull()
        assertThat(decoded.getOrNull()).isEqualTo(HapticMode.DEFAULT)
    }

    @Test
    fun `枚举成员名与顺序被写死，改名或增删档位这条先红`() {
        assertWithMessage(
            "落盘存的是枚举 name：改名会把老用户存的档位变成脏数据，静默回落成跟随系统，" +
                "编译期毫无提示。真要改名就得先写迁移，然后才动这里的 $expectedNames"
        ).that(HapticMode.entries.map { it.name }).isEqualTo(expectedNames)
    }

    @Test
    fun `每一档写出的 name 都能被读回同一档`() {
        // setMode 落盘的就是 mode.name，这里把写读往返闭上环：
        // 存 name 不存 ordinal，所以往枚举中间插一档也不会把老用户的「关闭」读成别的档
        HapticMode.entries.forEach { mode ->
            assertWithMessage("档位 %s 的写读往返断了", mode.name)
                .that(HapticStorage.decodeMode(mode.name)).isEqualTo(mode)
        }
    }

    @Test
    fun `ordinal 数字串不是有效值，一律回落`() {
        // 钉住「存 name 不存 ordinal」的读取侧：谁把解析改成按下标取值，这条就红
        listOf("0", "1", "2", "-1", "99").forEach { raw ->
            assertWithMessage("ordinal 字面量 %s 不该被认成有效档位", raw)
                .that(HapticStorage.decodeMode(raw)).isEqualTo(HapticMode.DEFAULT)
        }
    }
}
