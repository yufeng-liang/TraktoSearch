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
 * 确认被测应用仍在前台，不在则重新拉起。
 *
 * 存在的理由：坐标点击和 pressBack 都可能把应用退到后台（点空白处后 pressBack 直接回桌面）。
 * 一旦回到桌面，后续 `By.desc("设置")` 这类选择器会匹配到桌面上的系统设置图标 ——
 * 曾经因此在基准运行中途真的打开了手机系统设置，那一段采到的帧数据全是无效的。
 *
 * 重新拉起会影响该次 iteration 的帧数据，但比继续在别的应用上操作要好得多。
 *
 * @return 应用原本就在前台时返回 true；需要重新拉起时返回 false，便于调用方判断该次采样是否可信。
 */
fun MacrobenchmarkScope.ensureAppForeground(): Boolean {
    if (device.currentPackageName == TARGET_PACKAGE) return true
    launchAndWaitForContent()
    return false
}

/**
 * 切到指定底部导航 tab。
 *
 * 选择器必须带 `.pkg(TARGET_PACKAGE)` 限定：uiautomator 的 `By.desc()` 是在整个屏幕的
 * 无障碍节点树上搜索，不限应用。少了这个限定，应用一旦不在前台，`By.desc("设置")`
 * 就会匹配到桌面上的系统设置图标并点开它。
 *
 * @return 是否成功点到。找不到时返回 false 而不抛异常 —— 「我的」页依赖 Trakt 授权数据，
 * 未登录设备上该 tab 仍存在但内容为空，这里不该因此让整个基准失败。
 */
fun MacrobenchmarkScope.openTab(tab: MainTab): Boolean {
    ensureAppForeground()
    val target = device.wait(Until.findObject(By.desc(tab.zh).pkg(TARGET_PACKAGE)), UI_TIMEOUT_MS)
        ?: device.wait(Until.findObject(By.desc(tab.en).pkg(TARGET_PACKAGE)), 2_000L)
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
    if (!ensureAppForeground()) return
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
    if (!ensureAppForeground()) return
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
 * 卡片没有稳定的 testTag，按坐标点首行中部。
 *
 * pressBack 前后都要确认还在应用内：点在空白处时详情页不会打开，此时 pressBack
 * 会直接把应用退到桌面，后续测试就会在桌面上误点图标。
 */
fun MacrobenchmarkScope.openFirstDetailAndBack() {
    if (!ensureAppForeground()) return
    val width = device.displayWidth
    val height = device.displayHeight
    device.click(width / 4, (height * 0.4f).toInt())
    device.waitForIdle()
    device.wait(Until.hasObject(By.pkg(TARGET_PACKAGE).depth(0)), UI_TIMEOUT_MS)
    if (device.currentPackageName != TARGET_PACKAGE) {
        ensureAppForeground()
        return
    }
    device.pressBack()
    device.waitForIdle()
    ensureAppForeground()
}

/**
 * 等到屏上出现可滚动容器再返回。
 *
 * 冷启动后「我的」页要等数据落地才挂上网格，此前整屏没有可滚动节点。
 * 这时候滑动既滑不到列表、也不产生新帧，FrameTimingMetric 会直接报
 * `At least one result is necessary, 0 found for frameDurationCpuMs` 让整个用例失败。
 *
 * @return 超时前是否等到。等不到时返回 false，调用方仍会滑一遍 —— 空屏采到的数据由
 * 指标本身暴露（帧数异常少），比在这里抛异常丢掉整轮迭代要好。
 */
fun MacrobenchmarkScope.waitForScrollableContent(): Boolean =
    device.wait(Until.hasObject(By.scrollable(true).pkg(TARGET_PACKAGE)), UI_TIMEOUT_MS)

/**
 * 让屏幕上任意可滚动容器向前滚动。
 *
 * 在 LazyRow（发现页的横向片区）这类场景下坐标 swipe 会误触发竖向滚动，
 * 这里用 uiautomator 的可滚动节点查找兜底；找不到就回落到坐标 swipe。
 * 同样要限定包名，否则应用不在前台时会去滚别的应用的列表。
 */
fun MacrobenchmarkScope.flingScrollableForward(direction: Direction = Direction.DOWN) {
    if (!ensureAppForeground()) return
    val scrollable = device.findObject(By.scrollable(true).pkg(TARGET_PACKAGE))
    if (scrollable != null) {
        scrollable.setGestureMargin(device.displayWidth / 5)
        scrollable.fling(direction)
        device.waitForIdle()
    } else {
        scrollDownOnce()
    }
}
