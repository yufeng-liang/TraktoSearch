package com.tracktosearch.ui.component

import android.content.Context
import android.graphics.Rect
import android.view.accessibility.AccessibilityWindowInfo
import android.util.Log
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.UiDevice
import com.google.common.truth.Truth.assertWithMessage
import com.tracktosearch.data.repository.UpdateInfo
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.net.InetAddress
import java.net.ServerSocket
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import java.util.regex.Pattern

/**
 * 进入下载态后，弹窗窗口高度必须一次到位、之后不再逐帧变化。
 *
 * 回归的是这个 bug：内容根节点挂 animateContentSize 时，弹窗窗口（wrap_content）动画中的尺寸
 * 会反过来成为内容的测量约束，内容要变高就每次布局只放出约 1px；下载进度又每 8KB 写一次状态
 * 让它每帧失效 —— 模拟器实测窗口高度从 752px 爬到 846px 用掉整个下载的 20 秒，
 * 期间「取消下载」整块在窗口外，用户想取消也点不到。修法见 UpdateDialog 里 Column 上方的注释。
 *
 * 为什么走真下载而不是 fake 进度：这条链的成因是「ApkDownloader 每 8KB 回调 → 跨线程写状态」，
 * 直接构造 Downloading 状态测不到它。测试进程内起一个限速 ServerSocket 就够，
 * 127.0.0.1 在本机回环上，省掉 adb reverse 与防火墙入站规则
 * （代价是 debug 变体要放行回环明文，见 src/debug/res/xml/network_security_config.xml）。
 *
 * 为什么用 UiDevice.click 而不是 performClick：后者内部 waitForIdle，而下载期间补间一直在跑，
 * idle 等不到。坐标从 uiautomator 层级里取。
 *
 * 为什么手动逐帧推进：composeRule 接管了帧时钟，测试线程光 Thread.sleep 一帧都不会有，
 * 状态写了也不重组 —— 第一版就是这么测出「高度恒定」的假阴性。
 */
@RunWith(AndroidJUnit4::class)
class UpdateDialogDownloadLayoutTest {

    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun 进入下载态后弹窗高度不再逐帧变化() {
        val device = UiDevice.getInstance(InstrumentationRegistry.getInstrumentation())
        val server = FakeApkServer().also { it.start() }

        composeRule.setContent {
            UpdateDialog(
                updateInfo = UpdateInfo(
                    latestVersion = "4.0.1",
                    downloadUrl = "http://127.0.0.1:${server.port}/dl/TraktoSearch-v4.0.1.apk",
                    changelog = LAB_CHANGELOG,
                    fileSize = TOTAL_BYTES,
                    hasUpdate = true,
                    // 留空即跳过 SHA-256 校验：这里要的是进度节奏，不是文件内容
                    sha256 = ""
                ),
                onDismiss = {}
            )
        }
        composeRule.waitForIdle()

        val idleXml = device.dumpTo(tempDir(), "idle.xml")
        val downloadBtn = findCenter(idleXml, BUILTIN_DOWNLOAD_LABEL)
            ?: error("层级里找不到「$BUILTIN_DOWNLOAD_LABEL」，弹窗可能没起来或系统语言不是英文")

        device.click(downloadBtn.first, downloadBtn.second)

        // 逐帧推进：composeRule 接管了帧时钟，只 sleep 的话一帧都不会有，状态写了也不重组。
        // 先给 SETTLE_FRAMES 帧让 Idle→Downloading 那一次跳变落地，再取两个点判稳。
        composeRule.mainClock.autoAdvance = false
        advance(SETTLE_FRAMES)
        val firstHeight = dialogWindowHeight()
        advance(OBSERVE_FRAMES)
        val laterHeight = dialogWindowHeight()
        Log.i(TAG, "first=$firstHeight later=$laterHeight sent=${server.sent.get()}")

        server.stopped.set(true)
        composeRule.mainClock.autoAdvance = true

        // 回归点：坏版本这里窗口停在 759 而内容需要 846，多出来的部分被窗口裁掉，
        // 「取消下载」在窗口外点不到。注意 assertIsDisplayed() 抓不到这种窗口级裁切
        // （它比对的是 Compose 根节点，实测负控制下依然通过），所以判据只能落在窗口高度上：
        // animateContentSize 与 wrap_content 窗口互为约束时会每帧只放出约 1px，
        // 于是第二个点一定比第一个点高。不写死像素差，换设备也不用改。
        assertWithMessage(
            "弹窗窗口在 %s 帧里从 %spx 又长到 %spx：高度仍在逐帧爬，超出的内容被窗口裁掉",
            OBSERVE_FRAMES, firstHeight, laterHeight
        )
            .that(laterHeight)
            .isEqualTo(firstHeight)
    }

    private fun advance(frames: Int) {
        repeat(frames) {
            composeRule.mainClock.advanceTimeByFrame()
            Thread.sleep(FRAME_MS)
        }
    }

    /**
     * 弹窗窗口（wrap_content）当前在屏幕上占的高度。
     *
     * 走 UiAutomation 的窗口列表而不是 Compose 语义节点：裁掉多出来那 94px 的是窗口，
     * Compose 根节点自己按内容量到 846，所以 assertIsDisplayed() 一类判据看不见这种裁切
     * （负控制实测过）。uiautomator 2.4.0 的 findWindows 要 ByWindowSelector，
     * 而它的构造是包私有，所以直接问 AccessibilityService.getWindows()。
     *
     * 宿主 Activity 与弹窗同为 TYPE_APPLICATION，靠「不满屏」区分；取不到就抛，
     * 免得判据静默拿到 null 退化成空断言。
     */
    private fun dialogWindowHeight(): Int {
        val fullscreen =
            UiDevice.getInstance(InstrumentationRegistry.getInstrumentation()).displayHeight
        val rect = Rect()
        var best = 0
        for (window in InstrumentationRegistry.getInstrumentation().uiAutomation.windows) {
            if (window.type != AccessibilityWindowInfo.TYPE_APPLICATION) continue
            window.getBoundsInScreen(rect)
            if (rect.height() in 1 until fullscreen) best = maxOf(best, rect.height())
        }
        if (best == 0) error("窗口列表里没有非全屏的应用窗口，弹窗可能没显示")
        return best
    }

    private fun tempDir(): File =
        File(ApplicationProvider.getApplicationContext<Context>().getExternalFilesDir(null), "heightlab")
            .apply { mkdirs() }

    /** 下载体限速到真机量级：2.9 MB/s、每 8KB 一次读回调，把状态写入频率还原成真实节奏。 */
    private class FakeApkServer : Thread("fake-apk-server") {
        private val socket = ServerSocket(0, 16, InetAddress.getByName("127.0.0.1"))
        val port: Int get() = socket.localPort
        val sent = AtomicLong(0L)
        val stopped = AtomicBoolean(false)

        init {
            isDaemon = true
        }

        override fun run() {
            try {
                socket.accept().use { client ->
                    client.getInputStream().bufferedReader().use { reader ->
                        // 请求体不重要，读到空行即认为请求头结束
                        while (true) {
                            val line = reader.readLine() ?: return@use
                            if (line.isEmpty()) break
                        }
                        client.getOutputStream().use { out ->
                            out.write(
                                ("HTTP/1.1 200 OK\r\n" +
                                    "Content-Type: application/vnd.android.package-archive\r\n" +
                                    "Content-Length: $TOTAL_BYTES\r\n" +
                                    "Connection: close\r\n\r\n").toByteArray()
                            )
                            out.flush()
                            val chunk = ByteArray(CHUNK_BYTES)
                            val startNanos = System.nanoTime()
                            var written = 0L
                            while (written < TOTAL_BYTES && !stopped.get()) {
                                val n = minOf(chunk.size.toLong(), TOTAL_BYTES - written).toInt()
                                out.write(chunk, 0, n)
                                // 每块都 flush：攒到 64KB 缓冲再吐，客户端就成了突发读，
                                // 而这条链要复现的正是「每 8KB 一次回调」的节奏
                                out.flush()
                                written += n
                                sent.set(written)
                                // 绝对时钟节流。sleep 的粒度到不了 3ms，只能自旋等到达发送时刻
                                val dueNanos = startNanos + written * 1_000_000_000L / TARGET_BPS
                                while (System.nanoTime() < dueNanos && !stopped.get()) {
                                    Thread.yield()
                                }
                            }
                        }
                    }
                }
            } catch (_: Exception) {
                // 客户端取消会关掉连接，这里不是失败
            } finally {
                runCatching { socket.close() }
            }
        }
    }

    /** uiautomator 2.4.0 的 dumpWindowHierarchy 只收 File，不收 Writer，所以直接落盘再读回。 */
    private fun UiDevice.dumpTo(dir: File, name: String): String {
        val file = File(dir, name)
        dumpWindowHierarchy(file)
        return file.readText()
    }

    /**
     * 取某个文本节点的中心屏幕坐标。
     *
     * 节点文本与 bounds 在同一个标签内且 bounds 排在后面，所以从文本位置往后找第一组。
     * 用层级坐标而不是 SemanticsNode.boundsInRoot：后者在 Dialog 里以弹窗自身为原点，
     * 换不成 UiDevice.click 要的全屏坐标。
     */
    private fun findCenter(xml: String, needle: String): Pair<Int, Int>? {
        val at = xml.indexOf(needle)
        if (at < 0) return null
        val matcher = BOUNDS_PATTERN.matcher(xml)
        matcher.region(at, xml.length)
        if (!matcher.find()) return null
        val x1 = matcher.group(1)!!.toInt()
        val y1 = matcher.group(2)!!.toInt()
        val x2 = matcher.group(3)!!.toInt()
        val y2 = matcher.group(4)!!.toInt()
        return Pair((x1 + x2) / 2, (y1 + y2) / 2)
    }

    private companion object {
        const val TAG = "UpdateDialogLayout"
        const val TOTAL_BYTES = 60L * 1024 * 1024
        const val TARGET_BPS = 2_900_000L
        const val CHUNK_BYTES = 8 * 1024

        const val FRAME_MS = 30L

        /** 点完下载先给这么多帧，让 Idle→Downloading 那一次合法的高度跳变落地 */
        const val SETTLE_FRAMES = 25

        /** 落地后再走这么多帧并复查窗口高度；坏版本每帧约 +1px，这里足够暴露出来 */
        const val OBSERVE_FRAMES = 25

        // 弹窗文案来自资源，测试跑在英文系统镜像上，所以钉死英文
        const val BUILTIN_DOWNLOAD_LABEL = "Built-in download"

        val BOUNDS_PATTERN: Pattern =
            Pattern.compile("bounds=\"\\[(\\d+),(\\d+)\\]\\[(\\d+),(\\d+)\\]\"")

        /** 与真机截图同款的日志区内容，保证高度分布和线上观感一致 */
        val LAB_CHANGELOG = """
            ## v4.0.1 更新内容（2026-09-26）

            🐛 修复

            - 修复设置页「更新日志」、发现页「栏目管理」与详情页「选择要标记已看的集」三处弹窗打开即闪退的问题
        """.trimIndent()
    }
}
