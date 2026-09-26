package com.tracktosearch.ui.screen.dailystamp

import androidx.compose.runtime.Immutable
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.tracktosearch.data.repository.DailyStamp
import com.tracktosearch.data.repository.DailyStampRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
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
    /** 正在看的那个月：切月当场就换，不等这一月的数据到位，见 [DailyStampViewModel.switchTo] */
    val month: YearMonth = YearMonth.now(),
    val stamps: List<DailyStamp> = emptyList(),
    /**
     * 当月错过签到的那些天，台词是按日期现算的（见 [DailyStampRepository.missedMonth]）。
     * 和 [stamps] 分开存：这些天不算签到，不进连续天数、不进累计，
     * 混进同一个列表只会让每处用到的地方都要再筛一次。
     *
     * 格子上印不印海报看 DailyStampViewModel.readDays：没读过的那天只给一张糊海报，
     * 读过之后才换清晰的。
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
    private val readStorage: com.tracktosearch.data.local.DailyStampReadStorage,
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

    /**
     * 已经读过卡片的那些天（epochDay）。
     *
     * 只影响错过签到的那几格：没读过给糊海报，读过换清晰的。和
     * [splashQuoteEnabled] 一样不进 [uiState]——它不参与翻月、也不参与任何计数，
     * 混进去只会让每次切月都带上它。
     */
    val readDays: StateFlow<Set<Long>> = readStorage.readDays

    fun setSplashQuoteEnabled(enabled: Boolean) {
        viewModelScope.launch { splashQuoteStorage.setEnabled(enabled) }
    }

    init {
        viewModelScope.launch {
            month
                .flatMapLatest { target -> repository.observeMonth(target).map { target to it } }
                // 解析一个月的日签会为每条查一次海报文件在不在（SplashPosterStore.posterModels），
                // 一次目录列举。放在收集端就是切月动画期间在主线程读盘，搬到 IO 上
                .flowOn(Dispatchers.IO)
                // 日签是只读展示，查询失败就当这个月没有记录，不该把整页变成错误页
                .catch { _uiState.update { it.copy(loading = false) } }
                .collect { (target, stamps) -> publishMonth(target, stamps) }
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

    /**
     * 打开某天的卡片；传 null 关闭。
     *
     * 打开的同时记下「这天读过」，格子上那张糊海报就此换成清晰的。只记错过签到的那些天：
     * 签到过的当天就把台词念出来了，还没到的那天只有一张糊卡、没有可读的东西，
     * 给它们留标记只是往集合里塞一堆永远用不上的日子。
     *
     * 记在「打开」而不是「关掉」那一刻：卡片排左右滑过去同样走这里，滑过即看过，
     * 不必等用户专门退回日历一次。
     */
    fun select(date: LocalDate?) {
        _uiState.update { it.copy(selected = date) }
        if (date != null && _uiState.value.missed.any { it.date == date }) {
            viewModelScope.launch { readStorage.markRead(date.toEpochDay()) }
        }
    }

    /**
     * 切月。顺手关掉卡片浮层：卡片属于某一天，月份一换它就悬空了。
     *
     * [DailyStampUiState.month] 当场就换，不等数据：它决定报头那个月名、格子里排哪几天，
     * 以及两个箭头还让不让点。留着上个月的值会让连点箭头时后几下被 [nextMonth] 的闸门
     * 按掉——用户明明翻动了，界面却答非所问。格子上那一月的内容随后由 [publishMonth] 补上，
     * 中间那一两帧是干净的新月份空纸，不是上个月串过来的数字。
     */
    private fun switchTo(target: YearMonth) {
        _uiState.update { it.copy(selected = null, month = target) }
        month.value = target
    }

    /**
     * 整月要显示的东西一次凑齐、一次落状态。
     *
     * 分几波发布是这一页进页时最贵的做法：签到格画一次、计数画一次、错过和还没到的格子
     * 再画一次，而这一屏是 42 个格子带最多 31 张海报缩略图，每一波都是整页重排。
     * 这几项之间没有依赖（只有错过那些天要用 firstUse，而它和计数那几项同源于一条
     * `MIN(epochDay)`），并发取完总耗时就是最慢的那一项，比原来的串行之和短得多。
     *
     * 顺带先等 [DailyStampReadStorage.loadIntoMirror] 落地：镜像没读过就发布，已经补看过的
     * 日子会先按 12px 糊着请求一次、镜像落地后再换 160px 请求一次，白解一遍图，还留下一眼
     * 看得出的「先糊再清」。
     *
     * 凑不齐也要让格子出来：那几项查询失败时退化成只发 [stamps]，页面上就是少了计数和
     * 空格子的分类，不是白屏。
     */
    private suspend fun publishMonth(target: YearMonth, stamps: List<DailyStamp>) {
        val extras = try {
            withContext(Dispatchers.IO) {
                try {
                    readStorage.loadIntoMirror()
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    // 读不回「哪些天看过」只影响错过那几格糊不糊，不该把整月格子一起带走
                }
                coroutineScope {
                    val firstUse = async { repository.firstUseDate() }
                    val streak = async { repository.streak() }
                    val total = async { repository.totalDays() }
                    val latent = async { repository.latentMonth(target) }
                    val use = firstUse.await()
                    MonthExtras(
                        streak = streak.await(),
                        total = total.await(),
                        firstDay = use,
                        earliestMonth = use?.let(YearMonth::from),
                        missed = repository.missedMonth(target, use),
                        latent = latent.await(),
                    )
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            null
        }
        _uiState.update { current ->
            // 期间可能已经切到别的月，那这一批数据属于上一个月，丢掉
            if (current.month != target) current
            else when (extras) {
                null -> current.copy(stamps = stamps, loading = false)
                else -> current.copy(
                    stamps = stamps,
                    loading = false,
                    streak = extras.streak,
                    total = extras.total,
                    earliestMonth = extras.earliestMonth,
                    firstDay = extras.firstDay,
                    missed = extras.missed,
                    latent = extras.latent,
                )
            }
        }
    }

    /** [publishMonth] 一次凑齐的那几项，除了签到格本身 */
    private data class MonthExtras(
        val streak: Int,
        val total: Int,
        val firstDay: LocalDate?,
        val earliestMonth: YearMonth?,
        val missed: List<DailyStamp>,
        val latent: List<DailyStamp>,
    )
}
