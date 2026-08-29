package com.tracktosearch.ui.screen.dailystamp

import androidx.compose.runtime.Immutable
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.tracktosearch.data.repository.DailyStamp
import com.tracktosearch.data.repository.DailyStampRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.time.LocalDate
import java.time.YearMonth
import javax.inject.Inject

/**
 * 日签页状态。
 *
 * [stamps] 只含签到过的那些天，升序。空日子由 UI 按 [month] 自己排格子——
 * 一个月里没来过的那天在数据层没有任何内容可带。
 *
 * [selected] 是卡片浮层选中的那天，null 表示没打开。存日期而不是整张卡：
 * 卡片内容随界面语言变，语言解析在 UI 层（Configuration 才知道「跟随系统」到底是哪种），
 * 这里只记「选了哪天」。
 */
@Immutable
data class DailyStampUiState(
    val month: YearMonth = YearMonth.now(),
    val stamps: List<DailyStamp> = emptyList(),
    /** 连续签到天数 */
    val streak: Int = 0,
    /** 累计签到天数 */
    val total: Int = 0,
    /** 第一次签到那个月，往前翻到这里为止；一条都没有时为 null */
    val earliestMonth: YearMonth? = null,
    val loading: Boolean = true,
    val selected: LocalDate? = null,
) {
    /** 还能往前翻：当前月晚于第一次签到那个月 */
    val canGoPrevious: Boolean
        get() = earliestMonth?.let { month.isAfter(it) } ?: false

    /** 还能往后翻：不给看未来的月份，日签只记录已经过去的日子 */
    val canGoNext: Boolean
        get() = month.isBefore(YearMonth.now())
}

@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class DailyStampViewModel @Inject constructor(
    private val repository: DailyStampRepository,
) : ViewModel() {

    private val month = MutableStateFlow(YearMonth.now())

    private val _uiState = MutableStateFlow(DailyStampUiState())
    val uiState: StateFlow<DailyStampUiState> = _uiState.asStateFlow()

    init {
        viewModelScope.launch {
            month
                .flatMapLatest { target -> repository.observeMonth(target).map { target to it } }
                // 日签是只读展示，查询失败就当这个月没有记录，不该把整页变成错误页
                .catch { _uiState.update { it.copy(loading = false) } }
                .collect { (target, stamps) ->
                    _uiState.update { it.copy(month = target, stamps = stamps, loading = false) }
                    refreshCounters()
                }
        }
    }

    fun previousMonth() {
        if (!_uiState.value.canGoPrevious) return
        switchTo(month.value.minusMonths(1))
    }

    fun nextMonth() {
        if (!_uiState.value.canGoNext) return
        switchTo(month.value.plusMonths(1))
    }

    /** 打开某天的卡片；传 null 关闭 */
    fun select(date: LocalDate?) {
        _uiState.update { it.copy(selected = date) }
    }

    /**
     * 切月。顺手关掉卡片浮层：卡片属于某一天，月份一换它就悬空了。
     * loading 只用来遮首次进入，切月时不置回 true——月视图已经画在那儿，
     * 让它闪一下骨架比直接换内容更难看。
     */
    private fun switchTo(target: YearMonth) {
        _uiState.update { it.copy(selected = null) }
        month.value = target
    }

    private suspend fun refreshCounters() {
        val streak = repository.streak()
        val total = repository.totalDays()
        val earliest = repository.earliestMonth()
        _uiState.update { it.copy(streak = streak, total = total, earliestMonth = earliest) }
    }
}
