package com.tracktosearch.benchmark

import androidx.benchmark.macro.MacrobenchmarkScope
import androidx.test.uiautomator.By
import androidx.test.uiautomator.Direction
import androidx.test.uiautomator.Until

/** 被测应用包名。 */
const val TARGET_PACKAGE = "com.tracktosearch"

/** 等待 UI 出现的统一超时。真机冷启动 + 首屏网络/数据库读取留足余量。 */
private const val UI_TIMEOUT_MS = 8_000L

/**
 * 底部导航 tab 的 contentDescription。
 *
 * 取自 `MainScreen.NavTabItem` 的 `contentDescription = stringResource(labelRes)`，
 * 值来自 `R.string.tab_*`。设备语言不定，中英两套候选都试一遍。
 */
enum class MainTab(val zh: String, val en: String) {
    SEARCH("搜索", "Search"),
    DISCOVER("发现", "Discover"),
    ME("我的", "Me"),
    SETTINGS("设置", "Settings"),
}

/**
 * 启动应用并等到首屏内容真正挂上来。
 *
 * `startActivityAndWait()` 只保证窗口出现，Compose 首帧内容可能还没组合完；
 * 再等一次包内节点出现，避免后续 swipe 打在空屏上。
 */
fun MacrobenchmarkScope.launchAndWaitForContent() {
    startActivityAndWait()
    device.wait(Until.hasObject(By.pkg(TARGET_PACKAGE).depth(0)), UI_TIMEOUT_MS)
}

/**
 * 切到指定底部导航 tab。
 *
 * @return 是否成功点到。找不到时返回 false 而不抛异常 —— 「我的」页依赖 Trakt 授权数据，
 * 未登录设备上该 tab 仍存在但内容为空，这里不该因此让整个基准失败。
 */
fun MacrobenchmarkScope.openTab(tab: MainTab): Boolean {
    val target = device.wait(Until.findObject(By.desc(tab.zh)), UI_TIMEOUT_MS)
        ?: device.wait(Until.findObject(By.desc(tab.en)), 2_000L)
        ?: return false
    target.click()
    device.waitForIdle()
    return true
}

/**
 * 在屏幕中部竖向滚动一屏。
 *
 * 用坐标 swipe 而不是 `UiObject2.fling`：应用里的列表分别是 LazyColumn / LazyVerticalGrid /
 * LazyRow，没有统一的可滚动节点标记，按坐标操作对所有页面一致。
 *
 * @param steps swipe 的插值步数。步数越大手势越慢，越接近真实滑动；
 * 12 步约 120ms，能触发列表的惯性滚动而不至于快到跳过中间帧。
 */
fun MacrobenchmarkScope.scrollDownOnce(steps: Int = 12) {
    val width = device.displayWidth
    val height = device.displayHeight
    device.swipe(
        width / 2,
        (height * 0.72f).toInt(),
        width / 2,
        (height * 0.28f).toInt(),
        steps
    )
    device.waitForIdle()
}

/** 反向滚动，用于回到列表顶部或覆盖向上滚的代码路径。 */
fun MacrobenchmarkScope.scrollUpOnce(steps: Int = 12) {
    val width = device.displayWidth
    val height = device.displayHeight
    device.swipe(
        width / 2,
        (height * 0.28f).toInt(),
        width / 2,
        (height * 0.72f).toInt(),
        steps
    )
    device.waitForIdle()
}

/**
 * 打开当前页第一张海报卡片的详情页，再返回。
 *
 * 覆盖「详情进入 + 返回」这条路径 —— 现有性能报告里返回首帧尖峰 300~680ms 的那条。
 * 卡片没有稳定的 testTag，按坐标点首行中部；点不到详情页时 pressBack 不会有副作用。
 */
fun MacrobenchmarkScope.openFirstDetailAndBack() {
    val width = device.displayWidth
    val height = device.displayHeight
    device.click(width / 4, (height * 0.4f).toInt())
    device.waitForIdle()
    device.wait(Until.hasObject(By.pkg(TARGET_PACKAGE).depth(0)), UI_TIMEOUT_MS)
    device.pressBack()
    device.waitForIdle()
}

/**
 * 让屏幕上任意可滚动容器向前滚动。
 *
 * 在 LazyRow（发现页的横向片区）这类场景下坐标 swipe 会误触发竖向滚动，
 * 这里用 uiautomator 的可滚动节点查找兜底；找不到就回落到坐标 swipe。
 */
fun MacrobenchmarkScope.flingScrollableForward(direction: Direction = Direction.DOWN) {
    val scrollable = device.findObject(By.scrollable(true))
    if (scrollable != null) {
        scrollable.setGestureMargin(device.displayWidth / 5)
        scrollable.fling(direction)
        device.waitForIdle()
    } else {
        scrollDownOnce()
    }
}
