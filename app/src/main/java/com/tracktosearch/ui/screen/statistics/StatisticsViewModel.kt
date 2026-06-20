package com.tracktosearch.ui.screen.statistics

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.compose.runtime.Immutable
import com.tracktosearch.data.remote.trakt.dto.TraktWatchlistMovieItem
import com.tracktosearch.data.remote.trakt.dto.TraktWatchlistShowItem
import com.tracktosearch.data.repository.TraktRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import javax.inject.Inject

@Immutable
data class StatisticsUiState(
    val totalWatched: Int = 0,
    val thisMonthWatched: Int = 0,
    val thisYearWatched: Int = 0,
    val genreDistribution: Map<String, Int> = emptyMap(),
    val heatmapData: Map<String, Int> = emptyMap(),
    val isLoading: Boolean = false,
    val error: String? = null
)

@HiltViewModel
class StatisticsViewModel @Inject constructor(
    private val traktRepository: TraktRepository
) : ViewModel() {

    private val _uiState = MutableStateFlow(StatisticsUiState(isLoading = true))
    val uiState: StateFlow<StatisticsUiState> = _uiState.asStateFlow()

    private var loadJob: kotlinx.coroutines.Job? = null

    // 解析 ISO 8601 时间戳，返回 Date 或 null
    private fun parseDate(dateStr: String): Date? {
        if (dateStr.isBlank()) return null
        val formats = listOf(
            "yyyy-MM-dd'T'HH:mm:ss.SSS'Z'",
            "yyyy-MM-dd'T'HH:mm:ss'Z'",
            "yyyy-MM-dd'T'HH:mm:ssXXX",
            "yyyy-MM-dd"
        )
        for (format in formats) {
            try {
                val sdf = SimpleDateFormat(format, Locale.US)
                sdf.timeZone = TimeZone.getTimeZone("UTC")
                return sdf.parse(dateStr)
            } catch (_: Exception) {
                // 尝试下一个格式
            }
        }
        return null
    }

    fun loadStatistics() {
        loadJob?.cancel()
        _uiState.value = _uiState.value.copy(isLoading = true, error = null)
        loadJob = viewModelScope.launch {
            try {
                val movieResult = traktRepository.getAllMovieHistory()
                val showResult = traktRepository.getAllShowHistory()

                val movies = movieResult.getOrElse {
                    _uiState.value = _uiState.value.copy(isLoading = false, error = it.message ?: "加载失败")
                    return@launch
                }
                val shows = showResult.getOrElse {
                    _uiState.value = _uiState.value.copy(isLoading = false, error = it.message ?: "加载失败")
                    return@launch
                }

                // 合并所有观看记录的时间戳（history 端点用 watched_at，watchlist 端点用 listed_at）
                val allDates = mutableListOf<Date>()
                movies.forEach { item ->
                    val dateStr = item.watched_at.ifBlank { item.listed_at }
                    parseDate(dateStr)?.let { allDates.add(it) }
                }
                shows.forEach { item ->
                    val dateStr = item.watched_at.ifBlank { item.listed_at }
                    parseDate(dateStr)?.let { allDates.add(it) }
                }

                val totalWatched = movies.size + shows.size

                // 本月/本年统计
                val calendar = Calendar.getInstance()
                val currentYear = calendar.get(Calendar.YEAR)
                val currentMonth = calendar.get(Calendar.MONTH)
                var thisMonthWatched = 0
                var thisYearWatched = 0
                for (date in allDates) {
                    val cal = Calendar.getInstance()
                    cal.time = date
                    if (cal.get(Calendar.YEAR) == currentYear) {
                        thisYearWatched++
                        if (cal.get(Calendar.MONTH) == currentMonth) {
                            thisMonthWatched++
                        }
                    }
                }

                // 类型分布
                val genreCount = mutableMapOf<String, Int>()
                movies.forEach { item ->
                    item.movie.genres.forEach { genre ->
                        genreCount[genre] = (genreCount[genre] ?: 0) + 1
                    }
                }
                shows.forEach { item ->
                    item.show.genres.forEach { genre ->
                        genreCount[genre] = (genreCount[genre] ?: 0) + 1
                    }
                }

                // 热力图数据：过去 365 天每天的观看次数
                val heatmapData = mutableMapOf<String, Int>()
                val dateKeyFormat = SimpleDateFormat("yyyy-MM-dd", Locale.US)
                dateKeyFormat.timeZone = TimeZone.getDefault()
                for (date in allDates) {
                    val key = dateKeyFormat.format(date)
                    heatmapData[key] = (heatmapData[key] ?: 0) + 1
                }

                _uiState.value = StatisticsUiState(
                    totalWatched = totalWatched,
                    thisMonthWatched = thisMonthWatched,
                    thisYearWatched = thisYearWatched,
                    genreDistribution = genreCount.toMap().toList()
                        .sortedByDescending { it.second }
                        .toMap(),
                    heatmapData = heatmapData.toMap(),
                    isLoading = false,
                    error = null
                )
            } catch (e: Exception) {
                _uiState.value = _uiState.value.copy(
                    isLoading = false,
                    error = e.message ?: "加载失败"
                )
            }
        }
    }
}
