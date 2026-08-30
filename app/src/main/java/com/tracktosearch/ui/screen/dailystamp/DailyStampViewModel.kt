package com.tracktosearch.ui.screen.dailystamp

import androidx.compose.runtime.Immutable
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.tracktosearch.data.repository.DailyStamp
import com.tracktosearch.data.repository.DailyStampRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CancellationException
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
    /**
     * 当月错过签到的那些天，台词是按日期现算的（见 [DailyStampRepository.missedMonth]）。
     * 和 [stamps] 分开存：这些天不算签到，不进连续天数、不进累计、格子上不印海报，
     * 混进同一个列表只会让每处用到的地方都要再筛一次。
     */
    val missed: List<DailyStamp> = emptyList(),
    /** 当月未来的那些天，只用得上海报地址 */
    val latent: List<DailyStamp> = emptyList(),
    /** 连续签到天数 */
    val streak: Int = 0,
    /** 累计签到天数 */
    val total: Int = 0,
    /** 第一次签到那个月，往前翻到这里为止；一条都没有时为 null */
    val earliestMonth: YearMonth? = null,
    /** 初次使用那天，用来把「你来之前」和「你错过了」分开；一条签到都没有时为 null */
    val firstDay: LocalDate? = null,
    val loading: Boolean = true,
    val selected: LocalDate? = null,
) {
    /** 还能往前翻：当前月晚于第一次签到那个月 */
    val canGoPrevious: Boolean
        get() = earliestMonth?.let { month.isAfter(it) } ?: false

    /**
     * 还能往后翻。
     *
     * 未来的月份也给看：那些格子是空的，点开也只有一张糊掉的卡，但「翻到年底看看」
     * 这件事本身就是日历的用法。停在一年之后——台词池正好 365 条一年一轮，
     * 再往后连色调都开始重复，日历没有必要替人记那么远。
     */
    val canGoNext: Boolean
        get() = month.isBefore(YearMonth.now().plusMonths(FUTURE_MONTHS))
}

/** 往后最多翻多少个月，见 [DailyStampUiState.canGoNext] */
private const val FUTURE_MONTHS = 12L

@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class DailyStampViewModel @Inject constructor(
    private val repository: DailyStampRepository,
    private val splashQuoteStorage: com.tracktosearch.data.local.SplashQuoteStorage,
) : ViewModel() {

    private val month = MutableStateFlow(YearMonth.now())

    private val _uiState = MutableStateFlow(DailyStampUiState())
    val uiState: StateFlow<DailyStampUiState> = _uiState.asStateFlow()

    /**
     * 开屏是否显示每日台词。
     *
     * 放在日签的 ViewModel 里，是因为设置里的「每日台词」二级页把这个开关和日签日历
     * 摆在同一页——日签记的就是每天开屏那一句，两者本来是一件事的两面。开关不进
     * [uiState]：它跟日历的加载、翻月、选中都没有关系，混进去会让每次切月都带上它。
     */
    val splashQuoteEnabled: StateFlow<Boolean> = splashQuoteStorage.enabledState

    fun setSplashQuoteEnabled(enabled: Boolean) {
        viewModelScope.launch { splashQuoteStorage.setEnabled(enabled) }
    }

    init {
        viewModelScope.launch {
            month
                .flatMapLatest { target -> repository.observeMonth(target).map { target to it } }
                // 日签是只读展示，查询失败就当这个月没有记录，不该把整页变成错误页
                .catch { _uiState.update { it.copy(loading = false) } }
                .collect { (target, stamps) ->
                    _uiState.update { it.copy(month = target, stamps = stamps, loading = false) }
                    refreshCounters()
                    refreshUnstamped(target)
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

    /**
     * 补上「没有签到行但仍要显示」的那两类日子：错过的和未来的。
     *
     * 和 [refreshCounters] 一样跟着每次月份变化重算。查失败就保持原样：这三项决定的是
     * 空格子长什么样、点不点得开，缺了只是退回「你来之前」那种最保守的样子，
     * 不该让整页崩在一次查询上。
     */
    private suspend fun refreshUnstamped(target: YearMonth) {
        try {
            val firstDay = repository.firstUseDate()
            val missed = repository.missedMonth(target)
            val latent = repository.latentMonth(target)
            _uiState.update {
                // 期间可能已经切到别的月，那时这一批数据属于上一个月，丢掉
                if (it.month != target) it
                else it.copy(firstDay = firstDay, missed = missed, latent = latent)
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // 只读展示，查不到就当这个月没有这两类日子
        }
    }
}
