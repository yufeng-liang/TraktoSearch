package com.tracktosearch.ui.screen.watchlist

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.runtime.Composable
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.ComposeContentTestRule
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * 「我的」页首次进入时列表停在底部的回归测试。
 *
 * 复刻 WatchlistScreen 的关键结构：列表还在加载、currentItems 仍为空的那一帧也会挂载
 * LazyVerticalGrid（空状态分支要求 `currentItems.isEmpty() && !isCurrentLoading`）。
 * 分页 footer 若无条件发出，它就是首帧网格里唯一的 item，key 锚点落在 "load_more_footer" 上；
 * 数据到达后这个 key 的索引从 0 变成 items.size，Lazy 网格按 key 把锚点找回首个可见位置，
 * firstVisibleItemIndex 直接被带到列表末尾，用户看到的是列表停在底部而不是从头显示。
 *
 * 真机复现步骤：`adb shell am force-stop com.tracktosearch` 后重新打开、切到「我的」页。
 */
@RunWith(AndroidJUnit4::class)
class WatchlistGridScrollPositionTest {

    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun `footer 仅在列表非空时发出，列表到达后视口停在顶部`() {
        val items = mutableStateOf<List<String>>(emptyList())
        val gridState = composeRule.setGridContent(items, footerAlwaysPresent = false)

        items.value = List(200) { "t$it" }
        composeRule.waitForIdle()

        assertThat(gridState().firstVisibleItemIndex).isEqualTo(0)
    }

    /**
     * 记录触发这个 bug 的 Compose 行为本身：空列表上发出 footer，数据到达后视口被带走。
     *
     * 这条断言的作用是说明上面那个条件判断为什么不能去掉。若某天 Compose 改掉了这套
     * key 重定位语义、本测试开始失败，说明产品代码里的条件判断可以简化。
     */
    @Test
    fun `footer 无条件发出时列表到达后视口被带到末尾`() {
        val items = mutableStateOf<List<String>>(emptyList())
        val gridState = composeRule.setGridContent(items, footerAlwaysPresent = true)

        items.value = List(200) { "t$it" }
        composeRule.waitForIdle()

        assertThat(gridState().firstVisibleItemIndex).isGreaterThan(0)
    }
}

/**
 * 挂一个 3 列网格，配置对齐 WatchlistScreen 的想看/已看网格。
 *
 * @param footerAlwaysPresent true 时 footer 无条件发出（修复前的形态）。
 * @return 取当前 LazyGridState 的读取函数 —— setContent 的 lambda 在测试线程外执行，
 * 直接返回实例需要额外的可见性保证，用读取函数交给 Compose 的快照系统。
 */
private fun ComposeContentTestRule.setGridContent(
    items: MutableState<List<String>>,
    footerAlwaysPresent: Boolean
): () -> LazyGridState {
    var state: LazyGridState? = null
    setContent {
        val gridState = rememberSaveable(saver = LazyGridState.Saver) { LazyGridState() }
        state = gridState
        WatchlistLikeGrid(
            items = items.value,
            gridState = gridState,
            footerAlwaysPresent = footerAlwaysPresent
        )
    }
    waitForIdle()
    return { requireNotNull(state) { "setContent 未执行" } }
}

@Composable
private fun WatchlistLikeGrid(
    items: List<String>,
    gridState: LazyGridState,
    footerAlwaysPresent: Boolean
) {
    LazyVerticalGrid(
        state = gridState,
        columns = GridCells.Fixed(3),
        contentPadding = PaddingValues(top = 122.dp, bottom = 80.dp),
        modifier = Modifier.fillMaxSize()
    ) {
        items(items.size, key = { items[it] }) {
            Box(Modifier.height(180.dp))
        }
        if (footerAlwaysPresent || items.isNotEmpty()) {
            item(span = { GridItemSpan(3) }, key = "load_more_footer") {
                Box(Modifier.fillMaxWidth().height(48.dp))
            }
        }
    }
}
