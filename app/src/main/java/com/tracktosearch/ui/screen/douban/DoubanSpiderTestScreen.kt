// 豁免说明：本页是 DEBUG-only 的豆瓣爬虫调试页（AppNavigation 以 BuildConfig.DEBUG 门控入口），
// 仅开发者可见，页面文案（含直接展示异常堆栈/原始英文错误）不要求走 stringResource 国际化。
package com.tracktosearch.ui.screen.douban

import android.annotation.SuppressLint
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.webkit.CookieManager
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.ExpandCircleDown
import androidx.compose.material.icons.rounded.FileDownload
import androidx.compose.material.icons.rounded.Movie
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.PrimaryTabRow
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import coil.compose.AsyncImage
import coil.request.ImageRequest
import com.tracktosearch.R
import com.tracktosearch.data.local.DoubanAuthStorage
import com.tracktosearch.data.remote.douban.DoubanDetailInfo
import com.tracktosearch.data.remote.douban.DoubanRepository
import com.tracktosearch.data.remote.douban.DoubanSearchTestResult
import com.tracktosearch.data.remote.douban.DoubanSpider
import com.tracktosearch.data.remote.douban.MarkTestResult
import com.tracktosearch.data.remote.douban.RatingWriteTestResult
import com.tracktosearch.data.remote.douban.RecommendTestResult
import com.tracktosearch.data.remote.douban.TestFetchResult
import com.tracktosearch.data.repository.DoubanRetryManager
import com.tracktosearch.ui.util.showToast
import com.tracktosearch.ui.component.AppFloatingDialog
import com.tracktosearch.ui.component.isAppDarkTheme
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

// ==================== 数据类 ====================

enum class UrlPreset { ITEM, USER_HOME }
enum class TestUa { PC, MOBILE }

data class SpiderTestResult(
    val statusCode: Int,
    val durationMs: Long,
    val html: String,
    val isLoginPage: Boolean,
    val parsed: DoubanDetailInfo?,
    val parseError: String?
)

data class SpiderTestUiState(
    val cookie: String = "",
    val isLoggedIn: Boolean = false,
    val userId: String = "",
    val url: String = "https://movie.douban.com/subject/37335468/",
    val urlPreset: UrlPreset = UrlPreset.ITEM,
    val ua: TestUa = TestUa.PC,
    val isFetching: Boolean = false,
    val result: SpiderTestResult? = null,
    val error: String? = null,
    // ── 标记写回测试 ──
    val markDoubanId: String = "",
    val isMarking: Boolean = false,
    val markResult: MarkTestResult? = null,
    val markError: String? = null,
    val markRemoveMode: String = "web_remove", // 取消标记方式: "web_remove"(POST /subject/{id}/remove 网页表单) / "j_remove"(POST /j/subject/{id}/remove)
    // ── 为你推荐测试(带 cookie) ──
    val recommendType: String = "tv",      // "movie" | "tv"
    val isRecommending: Boolean = false,
    val recommendResult: RecommendTestResult? = null,
    val recommendError: String? = null,
    // ── 看过+评分写入测试(带 cookie, 路径 B) ──
    val ratingDoubanId: String = "",       // 目标条目 doubanId
    val ratingValue: Int = 5,              // 星级 1..5
    val ratingComment: String = "",        // 短评(可选)
    val isRatingWriting: Boolean = false,
    val ratingResult: RatingWriteTestResult? = null,
    val ratingError: String? = null,
    // ── imdb→豆瓣ID 搜索测试 ──
    val searchImdbId: String = "",         // 输入的 imdbId(如 tt39528392)
    val isSearching: Boolean = false,
    val searchResult: DoubanSearchTestResult? = null,
    val searchError: String? = null
)

/** 本地已缓存的豆瓣条目(用于快选弹窗展示) */
data class CachedDoubanItem(
    val doubanId: String,
    val title: String?,
    val posterUrl: String?,
    val doubanUrl: String
)

// ==================== ViewModel ====================

@HiltViewModel
class DoubanSpiderTestViewModel @Inject constructor(
    private val doubanRepository: DoubanRepository,
    private val doubanAuthStorage: DoubanAuthStorage,
    private val doubanRetryManager: DoubanRetryManager
) : ViewModel() {

    private val _uiState = MutableStateFlow(SpiderTestUiState())
    val uiState: StateFlow<SpiderTestUiState> = _uiState.asStateFlow()

    /** 本地已缓存的豆瓣条目列表(快选弹窗用) */
    private val _cachedItems = MutableStateFlow<List<CachedDoubanItem>>(emptyList())
    val cachedItems: StateFlow<List<CachedDoubanItem>> = _cachedItems.asStateFlow()

    init {
        // 初始化:读取 cookie 和登录状态
        val cred = doubanAuthStorage.getCredentials()
        _uiState.value = _uiState.value.copy(
            cookie = cred?.cookie ?: "",
            isLoggedIn = cred != null,
            userId = cred?.userId ?: ""
        )
    }

    fun updateUrl(url: String) {
        _uiState.value = _uiState.value.copy(url = url)
    }

    fun updateUrlPreset(preset: UrlPreset) {
        val template = when (preset) {
            UrlPreset.ITEM -> "https://movie.douban.com/subject/37335468/"
            UrlPreset.USER_HOME -> "https://www.douban.com/people/${_uiState.value.userId.ifBlank { "your_user_id" } }/"
        }
        _uiState.value = _uiState.value.copy(
            urlPreset = preset,
            url = template
        )
    }

    fun updateUa(ua: TestUa) {
        _uiState.value = _uiState.value.copy(ua = ua)
    }

    /** 临时清除 cookie(仅置空 uiState.cookie,不影响 DoubanAuthStorage 真实存储),用于测试未登录场景 */
    fun clearCookieTemporarily() {
        _uiState.value = _uiState.value.copy(cookie = "")
    }

    /** 恢复 cookie(从 DoubanAuthStorage 重新读取) */
    fun restoreCookie() {
        val cred = doubanAuthStorage.getCredentials()
        _uiState.value = _uiState.value.copy(
            cookie = cred?.cookie ?: "",
            isLoggedIn = cred != null,
            userId = cred?.userId ?: ""
        )
    }

    /** 加载失败项列表(快选弹窗用,失败项有海报/标题/id) */
    fun loadCachedItems() {
        viewModelScope.launch {
            val failures = doubanRetryManager.getAllFailures()
            _cachedItems.value = failures.map { failure ->
                CachedDoubanItem(
                    doubanId = failure.doubanId,
                    title = failure.title,
                    posterUrl = failure.posterUrl,
                    doubanUrl = failure.doubanUrl
                )
            }.sortedByDescending { it.doubanId }  // doubanId 大的(通常是较新条目)在前
        }
    }

    /** 触发爬取(不走缓存,每次真实请求) */
    fun fetch() {
        val current = _uiState.value
        _uiState.value = current.copy(isFetching = true, error = null, result = null)
        viewModelScope.launch {
            try {
                val fetchResult: TestFetchResult = doubanRepository.fetchHtmlForTest(
                    url = current.url,
                    cookie = current.cookie,
                    useMobileUa = current.ua == TestUa.MOBILE
                )
                // 解析(parseDetail 异常隔离)
                val isLogin = DoubanSpider.isLoginPage(fetchResult.html)
                val (parsed, parseError) = try {
                    Pair(DoubanSpider.parseDetail(fetchResult.html), null)
                } catch (e: Exception) {
                    Pair(null, e.stackTraceToString())
                }
                _uiState.value = _uiState.value.copy(
                    isFetching = false,
                    result = SpiderTestResult(
                        statusCode = fetchResult.statusCode,
                        durationMs = fetchResult.durationMs,
                        html = fetchResult.html,
                        isLoginPage = isLogin,
                        parsed = parsed,
                        parseError = parseError
                    )
                )
            } catch (e: Exception) {
                _uiState.value = _uiState.value.copy(
                    isFetching = false,
                    error = e.stackTraceToString()
                )
            }
        }
    }

    /** 更新标记测试用的豆瓣条目 ID */
    fun updateMarkDoubanId(id: String) {
        _uiState.value = _uiState.value.copy(markDoubanId = id, markError = null, markResult = null)
    }

    /** 更新取消标记方式(empty / remove),仅 remove 动作使用 */
    fun updateMarkRemoveMode(mode: String) {
        _uiState.value = _uiState.value.copy(markRemoveMode = mode)
    }

    /**
     * 标记写回测试:先抓 PC 详情页解析 ck,再 POST 候选写接口端点(wish/do/collect/remove)。
     * 仅用于逆向确认端点与字段,不进入正式业务逻辑。结果存入 markResult / markError。
     */
    fun markTest(endpoint: String) {
        val current = _uiState.value
        val doubanId = current.markDoubanId.trim()
        if (doubanId.isBlank()) {
            _uiState.value = current.copy(markError = "doubanId is blank")
            return
        }
        _uiState.value = current.copy(isMarking = true, markError = null, markResult = null)
        viewModelScope.launch {
            try {
                // 1. 抓 PC 详情页(含 ck 凭证),PC UA 与写接口一致
                val detailUrl = "https://movie.douban.com/subject/$doubanId/"
                val fetch = doubanRepository.fetchHtmlForTest(detailUrl, current.cookie, useMobileUa = false)
                if (DoubanSpider.isLoginPage(fetch.html)) {
                    _uiState.value = _uiState.value.copy(isMarking = false, markError = "Cookie expired (login page returned)")
                    return@launch
                }
                val ck = DoubanSpider.parseCsrfToken(fetch.html)
                if (ck == null) {
                    _uiState.value = _uiState.value.copy(isMarking = false, markError = "Failed to parse ck from detail page HTML")
                    return@launch
                }
                // 2. 对一个动作探测多个候选写接口端点
                val result = doubanRepository.markTestCandidates(
                    doubanId, current.cookie, ck, endpoint, useMobileUa = false,
                    removeMode = if (endpoint == "remove") current.markRemoveMode else "web_remove"
                )
                _uiState.value = _uiState.value.copy(isMarking = false, markResult = result)
            } catch (e: Exception) {
                _uiState.value = _uiState.value.copy(isMarking = false, markError = e.stackTraceToString())
            }
        }
    }

    /** 更新为你推荐测试的类型(movie / tv) */
    fun updateRecommendType(type: String) {
        _uiState.value = _uiState.value.copy(recommendType = type, recommendError = null, recommendResult = null)
    }

    /**
     * 为你推荐测试:带当前 cookie 请求 m.douban.com/rexxar/api/v2/{type}/recommend。
     * 仅用于逆向确认端点返回结构(片单/豆列),不进入正式业务逻辑。
     */
    fun recommendTest() {
        val current = _uiState.value
        _uiState.value = current.copy(isRecommending = true, recommendError = null, recommendResult = null)
        viewModelScope.launch {
            try {
                val result = doubanRepository.fetchRecommendForTest(current.recommendType, current.cookie)
                _uiState.value = _uiState.value.copy(isRecommending = false, recommendResult = result)
            } catch (e: Exception) {
                _uiState.value = _uiState.value.copy(isRecommending = false, recommendError = e.stackTraceToString())
            }
        }
    }

    /** 更新看过+评分测试用的豆瓣条目 ID */
    fun updateRatingDoubanId(id: String) {
        _uiState.value = _uiState.value.copy(ratingDoubanId = id, ratingError = null, ratingResult = null)
    }

    /** 更新评分星级(1..5) */
    fun updateRatingValue(v: Int) {
        _uiState.value = _uiState.value.copy(ratingValue = v.coerceIn(1, 5))
    }

    /** 更新短评(可选) */
    fun updateRatingComment(c: String) {
        _uiState.value = _uiState.value.copy(ratingComment = c)
    }

    /**
     * 看过+评分写入测试(路径 B):先抓 PC 详情页解析 ck,再 POST /j/subject/{id}/interest
     * (interest=collect + rating=N)。复用已真机验证的 markInterest 端点族,标记「看过」时一并提交评分。
     * 仅用于测试页逆向确认 rating 字段是否被接受,不进入正式业务逻辑。
     */
    fun ratingWriteTest() {
        val current = _uiState.value
        val doubanId = current.ratingDoubanId.trim()
        if (doubanId.isBlank()) {
            _uiState.value = current.copy(ratingError = "doubanId is blank")
            return
        }
        _uiState.value = current.copy(isRatingWriting = true, ratingError = null, ratingResult = null)
        viewModelScope.launch {
            try {
                // 1. 抓 PC 详情页(含 ck 凭证),PC UA 与写接口一致
                val detailUrl = "https://movie.douban.com/subject/$doubanId/"
                val fetch = doubanRepository.fetchHtmlForTest(detailUrl, current.cookie, useMobileUa = false)
                if (DoubanSpider.isLoginPage(fetch.html)) {
                    _uiState.value = _uiState.value.copy(isRatingWriting = false, ratingError = "Cookie expired (login page returned)")
                    return@launch
                }
                val ck = DoubanSpider.parseCsrfToken(fetch.html)
                if (ck == null) {
                    _uiState.value = _uiState.value.copy(isRatingWriting = false, ratingError = "Failed to parse ck from detail page HTML")
                    return@launch
                }
                // 2. 提交「看过 + 评分」
                val result = doubanRepository.markWatchedWithRatingForTest(
                    doubanId = doubanId,
                    cookie = current.cookie,
                    ck = ck,
                    rating = current.ratingValue,
                    comment = current.ratingComment.trim()
                )
                _uiState.value = _uiState.value.copy(isRatingWriting = false, ratingResult = result)
            } catch (e: Exception) {
                _uiState.value = _uiState.value.copy(isRatingWriting = false, ratingError = e.stackTraceToString())
            }
        }
    }

    /** 更新 imdb→豆瓣ID 搜索测试的输入 imdbId */
    fun updateSearchImdbId(id: String) {
        _uiState.value = _uiState.value.copy(searchImdbId = id, searchError = null, searchResult = null)
    }

    /**
     * imdb→豆瓣ID 搜索测试:用 imdbId 搜索 m.douban.com/search/?query={imdbId},
     * 返回原始 HTML + 解析出的搜索结果列表。
     * 用于验证 cookie 是否必需、端点是否可用、解析是否正确。
     */
    fun searchByImdbTest() {
        val current = _uiState.value
        val imdbId = current.searchImdbId.trim()
        if (imdbId.isBlank()) {
            _uiState.value = current.copy(searchError = "imdbId is blank")
            return
        }
        _uiState.value = current.copy(isSearching = true, searchError = null, searchResult = null)
        viewModelScope.launch {
            try {
                val result = doubanRepository.searchDoubanIdByImdbForTest(
                    imdbId = imdbId,
                    cookie = current.cookie,
                    useMobileUa = true
                )
                _uiState.value = _uiState.value.copy(isSearching = false, searchResult = result)
            } catch (e: Exception) {
                _uiState.value = _uiState.value.copy(isSearching = false, searchError = e.stackTraceToString())
            }
        }
    }
}

// ==================== Composable ====================

@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun DoubanSpiderTestScreen(
    onBack: () -> Unit,
    viewModel: DoubanSpiderTestViewModel = hiltViewModel()
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val cachedItems by viewModel.cachedItems.collectAsStateWithLifecycle()
    val isDark = isAppDarkTheme()
    val context = LocalContext.current
    var selectedTab by rememberSaveable { mutableIntStateOf(0) }
    var showPickerDialog by remember { mutableStateOf(false) }

    BackHandler(enabled = true) { onBack() }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            text = stringResource(R.string.douban_spider_test_title),
                            style = MaterialTheme.typography.titleLarge,
                            fontWeight = FontWeight.ExtraBold
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Surface(
                            shape = RoundedCornerShape(10.dp),
                            color = MaterialTheme.colorScheme.tertiaryContainer
                        ) {
                            Text(
                                text = "DEBUG",
                                modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp),
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onTertiaryContainer
                            )
                        }
                    }
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Rounded.ArrowBack,
                            contentDescription = stringResource(R.string.content_desc_back),
                            tint = MaterialTheme.colorScheme.primary
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface
                )
            )
        },
        contentWindowInsets = WindowInsets(0, 0, 0, 0)
    ) { padding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
        ) {
            LazyColumn(
                modifier = Modifier.fillMaxSize()
            ) {
                // ── Cookie 信息区 ──
                item {
                    SectionCard(title = stringResource(R.string.douban_spider_test_cookie_section)) {
                        Surface(
                            shape = RoundedCornerShape(8.dp),
                            color = MaterialTheme.colorScheme.surface
                        ) {
                            Text(
                                text = uiState.cookie.ifBlank { "(empty)" },
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(10.dp),
                                style = MaterialTheme.typography.bodySmall,
                                fontFamily = FontFamily.Monospace,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 5
                            )
                        }
                        Spacer(modifier = Modifier.height(8.dp))
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            val dotColor = if (uiState.isLoggedIn) Color(0xFF4CAF50) else Color(0xFFF44336)
                            Surface(shape = RoundedCornerShape(50), color = dotColor, modifier = Modifier.size(8.dp)) {}
                            Spacer(modifier = Modifier.width(6.dp))
                            Text(
                                text = if (uiState.isLoggedIn)
                                    stringResource(R.string.douban_spider_test_logged_in, uiState.userId)
                                else
                                    stringResource(R.string.douban_spider_test_not_logged_in),
                                style = MaterialTheme.typography.bodySmall,
                                color = if (uiState.isLoggedIn) Color(0xFF4CAF50) else Color(0xFFF44336)
                            )
                        }
                        Spacer(modifier = Modifier.height(10.dp))
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            OutlinedButton(
                                onClick = { viewModel.clearCookieTemporarily() },
                                enabled = uiState.cookie.isNotBlank(),
                                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp)
                            ) {
                                Text(stringResource(R.string.douban_spider_test_cookie_clear), style = MaterialTheme.typography.labelSmall)
                            }
                            OutlinedButton(
                                onClick = { viewModel.restoreCookie() },
                                enabled = uiState.cookie.isBlank(),
                                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp)
                            ) {
                                Text(stringResource(R.string.douban_spider_test_cookie_restore), style = MaterialTheme.typography.labelSmall)
                            }
                        }
                    }
                }

                // ── 爬取目标区 ──
                item {
                    SectionCard(title = stringResource(R.string.douban_spider_test_url_section)) {
                        OutlinedTextField(
                            value = uiState.url,
                            onValueChange = viewModel::updateUrl,
                            modifier = Modifier.fillMaxWidth(),
                            singleLine = true,
                            textStyle = androidx.compose.ui.text.TextStyle(
                                fontFamily = FontFamily.Monospace,
                                fontSize = 13.sp
                            )
                        )
                        Spacer(modifier = Modifier.height(10.dp))
                        // 快选行:标题 + 影视条目/用户主页 + 快选弹窗按钮
                        Row(
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = stringResource(R.string.douban_spider_test_preset),
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            FilterChip(
                                selected = uiState.urlPreset == UrlPreset.ITEM,
                                border = if (uiState.urlPreset == UrlPreset.ITEM) BorderStroke(1.dp, MaterialTheme.colorScheme.primary) else BorderStroke(1.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.3f)),
                                colors = FilterChipDefaults.filterChipColors(
                                    containerColor = MaterialTheme.colorScheme.surfaceContainerHighest,
                                    labelColor = MaterialTheme.colorScheme.onSurfaceVariant,
                                    selectedContainerColor = MaterialTheme.colorScheme.primary,
                                    selectedLabelColor = MaterialTheme.colorScheme.onPrimary
                                ),
                                onClick = { viewModel.updateUrlPreset(UrlPreset.ITEM) },
                                label = { Text(stringResource(R.string.douban_spider_test_url_preset_item)) }
                            )
                            FilterChip(
                                selected = uiState.urlPreset == UrlPreset.USER_HOME,
                                border = if (uiState.urlPreset == UrlPreset.USER_HOME) BorderStroke(1.dp, MaterialTheme.colorScheme.primary) else BorderStroke(1.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.3f)),
                                colors = FilterChipDefaults.filterChipColors(
                                    containerColor = MaterialTheme.colorScheme.surfaceContainerHighest,
                                    labelColor = MaterialTheme.colorScheme.onSurfaceVariant,
                                    selectedContainerColor = MaterialTheme.colorScheme.primary,
                                    selectedLabelColor = MaterialTheme.colorScheme.onPrimary
                                ),
                                onClick = { viewModel.updateUrlPreset(UrlPreset.USER_HOME) },
                                label = { Text(stringResource(R.string.douban_spider_test_url_preset_user)) }
                            )
                            Spacer(modifier = Modifier.weight(1f))
                            IconButton(onClick = {
                                viewModel.loadCachedItems()
                                showPickerDialog = true
                            }) {
                                Icon(
                                    imageVector = Icons.Rounded.ExpandCircleDown,
                                    contentDescription = stringResource(R.string.douban_spider_test_pick_cached),
                                    tint = MaterialTheme.colorScheme.primary
                                )
                            }
                        }
                        Spacer(modifier = Modifier.height(10.dp))
                        // UA 行:标题 + 电脑版/手机版 + 爬取按钮
                        Row(
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = "User-Agent",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            FilterChip(
                                selected = uiState.ua == TestUa.PC,
                                border = if (uiState.ua == TestUa.PC) BorderStroke(1.dp, MaterialTheme.colorScheme.primary) else BorderStroke(1.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.3f)),
                                colors = FilterChipDefaults.filterChipColors(
                                    containerColor = MaterialTheme.colorScheme.surfaceContainerHighest,
                                    labelColor = MaterialTheme.colorScheme.onSurfaceVariant,
                                    selectedContainerColor = MaterialTheme.colorScheme.primary,
                                    selectedLabelColor = MaterialTheme.colorScheme.onPrimary
                                ),
                                onClick = { viewModel.updateUa(TestUa.PC) },
                                label = { Text(stringResource(R.string.douban_spider_test_ua_pc)) }
                            )
                            FilterChip(
                                selected = uiState.ua == TestUa.MOBILE,
                                border = if (uiState.ua == TestUa.MOBILE) BorderStroke(1.dp, MaterialTheme.colorScheme.primary) else BorderStroke(1.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.3f)),
                                colors = FilterChipDefaults.filterChipColors(
                                    containerColor = MaterialTheme.colorScheme.surfaceContainerHighest,
                                    labelColor = MaterialTheme.colorScheme.onSurfaceVariant,
                                    selectedContainerColor = MaterialTheme.colorScheme.primary,
                                    selectedLabelColor = MaterialTheme.colorScheme.onPrimary
                                ),
                                onClick = { viewModel.updateUa(TestUa.MOBILE) },
                                label = { Text(stringResource(R.string.douban_spider_test_ua_mobile)) }
                            )
                            Spacer(modifier = Modifier.weight(1f))
                            Button(
                                onClick = {
                                    viewModel.fetch()
                                },
                                enabled = !uiState.isFetching,
                                contentPadding = PaddingValues(horizontal = 16.dp, vertical = 6.dp)
                            ) {
                                if (uiState.isFetching) {
                                    CircularProgressIndicator(
                                        modifier = Modifier.size(16.dp),
                                        strokeWidth = 2.dp,
                                        color = MaterialTheme.colorScheme.onPrimary
                                    )
                                    Spacer(modifier = Modifier.width(6.dp))
                                    Text(stringResource(R.string.douban_spider_test_fetching))
                                } else {
                                    Text(stringResource(R.string.douban_spider_test_fetch))
                                }
                            }
                        }
                    }
                }

                // ── 标记写回测试区 ──
                item {
                    SectionCard(title = stringResource(R.string.douban_spider_test_mark_section)) {
                        OutlinedTextField(
                            value = uiState.markDoubanId,
                            onValueChange = viewModel::updateMarkDoubanId,
                            modifier = Modifier.fillMaxWidth(),
                            singleLine = true,
                            placeholder = { Text(stringResource(R.string.douban_spider_test_mark_douban_id), color = if (isDark) Color.White.copy(alpha = 0.4f) else Color(0xFF90A4AE)) },
                            textStyle = androidx.compose.ui.text.TextStyle(
                                fontFamily = FontFamily.Monospace,
                                fontSize = 13.sp
                            )
                        )
                        Spacer(modifier = Modifier.height(10.dp))
                        // 4 个写回测试按钮(两行两列)
                        val markActions = listOf(
                            "wish" to stringResource(R.string.douban_spider_test_mark_wish),
                            "do" to stringResource(R.string.douban_spider_test_mark_do),
                            "collect" to stringResource(R.string.douban_spider_test_mark_collect),
                            "remove" to stringResource(R.string.douban_spider_test_mark_remove)
                        )
                        val canMark = uiState.isLoggedIn && uiState.markDoubanId.isNotBlank() && !uiState.isMarking
                        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                markActions.take(2).forEach { (ep, label) ->
                                    Button(
                                        onClick = { viewModel.markTest(ep) },
                                        enabled = canMark,
                                        modifier = Modifier.weight(1f),
                                        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp)
                                    ) { Text(label) }
                                }
                            }
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                markActions.takeLast(2).forEach { (ep, label) ->
                                    Button(
                                        onClick = { viewModel.markTest(ep) },
                                        enabled = canMark,
                                        modifier = Modifier.weight(1f),
                                        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp)
                                    ) { Text(label) }
                                }
                            }
                        }
                        // 取消标记方式选择(仅 remove 动作生效): web_remove / j_remove
                        Spacer(modifier = Modifier.height(10.dp))
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                text = stringResource(R.string.douban_spider_test_mark_remove_mode),
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            FilterChip(
                                selected = uiState.markRemoveMode == "web_remove",
                                border = if (uiState.markRemoveMode == "web_remove") BorderStroke(1.dp, MaterialTheme.colorScheme.primary) else BorderStroke(1.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.3f)),
                                colors = FilterChipDefaults.filterChipColors(
                                    containerColor = MaterialTheme.colorScheme.surfaceContainerHighest,
                                    labelColor = MaterialTheme.colorScheme.onSurfaceVariant,
                                    selectedContainerColor = MaterialTheme.colorScheme.primary,
                                    selectedLabelColor = MaterialTheme.colorScheme.onPrimary
                                ),
                                onClick = { viewModel.updateMarkRemoveMode("web_remove") },
                                label = { Text(stringResource(R.string.douban_spider_test_mark_remove_empty)) },
                                modifier = Modifier.height(32.dp)
                            )
                            Spacer(modifier = Modifier.width(6.dp))
                            FilterChip(
                                selected = uiState.markRemoveMode == "j_remove",
                                border = if (uiState.markRemoveMode == "j_remove") BorderStroke(1.dp, MaterialTheme.colorScheme.primary) else BorderStroke(1.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.3f)),
                                colors = FilterChipDefaults.filterChipColors(
                                    containerColor = MaterialTheme.colorScheme.surfaceContainerHighest,
                                    labelColor = MaterialTheme.colorScheme.onSurfaceVariant,
                                    selectedContainerColor = MaterialTheme.colorScheme.primary,
                                    selectedLabelColor = MaterialTheme.colorScheme.onPrimary
                                ),
                                onClick = { viewModel.updateMarkRemoveMode("j_remove") },
                                label = { Text(stringResource(R.string.douban_spider_test_mark_remove_naked)) },
                                modifier = Modifier.height(32.dp)
                            )
                        }
                        // 未输入 ID 提示
                        if (uiState.markDoubanId.isBlank()) {
                            Spacer(modifier = Modifier.height(8.dp))
                            Text(
                                text = stringResource(R.string.douban_spider_test_mark_no_id),
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        // 标记中
                        if (uiState.isMarking) {
                            Spacer(modifier = Modifier.height(10.dp))
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.Center
                            ) {
                                CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp, color = MaterialTheme.colorScheme.primary)
                                Spacer(modifier = Modifier.width(6.dp))
                                Text(stringResource(R.string.douban_spider_test_marking), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                        // 错误(技术堆栈/英文)
                        uiState.markError?.let { err ->
                            Spacer(modifier = Modifier.height(10.dp))
                            Surface(shape = RoundedCornerShape(8.dp), color = MaterialTheme.colorScheme.errorContainer) {
                                Text(
                                    text = err,
                                    modifier = Modifier.fillMaxWidth().padding(10.dp),
                                    style = MaterialTheme.typography.bodySmall,
                                    fontFamily = FontFamily.Monospace,
                                    color = MaterialTheme.colorScheme.onErrorContainer
                                )
                            }
                        }
                        // 结果(多候选端点对照)
                        uiState.markResult?.let { res ->
                            val copyCtx = LocalContext.current
                            val copiedToast = stringResource(R.string.douban_spider_test_mark_copied)
                            val clipboard = copyCtx.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                            Spacer(modifier = Modifier.height(10.dp))
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                Text(
                                    text = "${stringResource(R.string.douban_spider_test_mark_result)} · ${res.action}",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                                TextButton(
                                    onClick = {
                                        val sb = buildString {
                                            appendLine("action: ${res.action}")
                                            res.candidates.forEachIndexed { i, c ->
                                                appendLine("[${c.label.ifBlank { "候选${'A' + i}" }}] ${c.endpoint} → HTTP ${c.statusCode} → ${c.responseBody.ifBlank { "(empty body)" }}")
                                            }
                                        }
                                        clipboard.setPrimaryClip(ClipData.newPlainText("result", sb))
                                        copyCtx.showToast(copiedToast)
                                    },
                                    contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp)
                                ) {
                                    Text(stringResource(R.string.douban_spider_test_copy_result))
                                }
                            }
                            Spacer(modifier = Modifier.height(6.dp))
                            res.candidates.forEachIndexed { idx, cand ->
                                if (idx > 0) Spacer(modifier = Modifier.height(8.dp))
                                Surface(
                                    modifier = Modifier.fillMaxWidth(),
                                    shape = RoundedCornerShape(8.dp),
                                    color = MaterialTheme.colorScheme.surfaceVariant
                                ) {
                                    Column(modifier = Modifier.padding(10.dp)) {
                                        Text(
                                            text = cand.label.ifBlank { cand.endpoint },
                                            style = MaterialTheme.typography.labelMedium,
                                            fontFamily = FontFamily.Monospace,
                                            color = MaterialTheme.colorScheme.primary
                                        )
                                        Spacer(modifier = Modifier.height(4.dp))
                                        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                                            val okColor = Color(0xFF4CAF50)
                                            Text(
                                                text = "endpoint: ${cand.endpoint}",
                                                style = MaterialTheme.typography.labelSmall,
                                                fontFamily = FontFamily.Monospace,
                                                color = MaterialTheme.colorScheme.onSurface
                                            )
                                            Text(
                                                text = "HTTP ${cand.statusCode}",
                                                style = MaterialTheme.typography.labelSmall,
                                                fontFamily = FontFamily.Monospace,
                                                color = if (cand.statusCode in 200..299) okColor else MaterialTheme.colorScheme.error
                                            )
                                        }
                                        Spacer(modifier = Modifier.height(6.dp))
                                        Box(
                                            modifier = Modifier
                                                .fillMaxWidth()
                                                .heightIn(max = 180.dp)
                                                .verticalScroll(rememberScrollState())
                                        ) {
                                            Text(
                                                text = cand.responseBody.ifBlank { "(empty body)" },
                                                style = MaterialTheme.typography.bodySmall,
                                                fontFamily = FontFamily.Monospace,
                                                color = MaterialTheme.colorScheme.onSurface
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    }
                }

                // ── 看过+评分写入测试(带 Cookie, 路径 B) ──
                item {
                    SectionCard(title = stringResource(R.string.douban_spider_test_rating_section)) {
                        OutlinedTextField(
                            value = uiState.ratingDoubanId,
                            onValueChange = viewModel::updateRatingDoubanId,
                            modifier = Modifier.fillMaxWidth(),
                            singleLine = true,
                            placeholder = { Text(stringResource(R.string.douban_spider_test_rating_douban_id), color = if (isDark) Color.White.copy(alpha = 0.4f) else Color(0xFF90A4AE)) },
                            textStyle = androidx.compose.ui.text.TextStyle(
                                fontFamily = FontFamily.Monospace,
                                fontSize = 13.sp
                            )
                        )
                        // 星级选择(整星 1..5)
                        Spacer(modifier = Modifier.height(10.dp))
                        Text(
                            text = stringResource(R.string.douban_spider_test_rating_stars),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Spacer(modifier = Modifier.height(6.dp))
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            (1..5).forEach { star ->
                                val selected = uiState.ratingValue == star
                                val label = "$star"
                                FilterChip(
                                    selected = selected,
                                    border = if (selected) BorderStroke(1.dp, MaterialTheme.colorScheme.primary) else BorderStroke(1.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.3f)),
                                    colors = FilterChipDefaults.filterChipColors(
                                        containerColor = MaterialTheme.colorScheme.surfaceContainerHighest,
                                        labelColor = MaterialTheme.colorScheme.onSurfaceVariant,
                                        selectedContainerColor = MaterialTheme.colorScheme.primary,
                                        selectedLabelColor = MaterialTheme.colorScheme.onPrimary
                                    ),
                                    onClick = { viewModel.updateRatingValue(star) },
                                    label = { Text(label) },
                                    modifier = Modifier.height(32.dp)
                                )
                                Spacer(modifier = Modifier.width(6.dp))
                            }
                        }
                        // 短评(可选)
                        Spacer(modifier = Modifier.height(10.dp))
                        OutlinedTextField(
                            value = uiState.ratingComment,
                            onValueChange = viewModel::updateRatingComment,
                            modifier = Modifier.fillMaxWidth(),
                            singleLine = true,
                            placeholder = { Text(stringResource(R.string.douban_spider_test_rating_comment_hint), color = if (isDark) Color.White.copy(alpha = 0.4f) else Color(0xFF90A4AE)) },
                            textStyle = androidx.compose.ui.text.TextStyle(fontSize = 13.sp)
                        )
                        Spacer(modifier = Modifier.height(10.dp))
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Spacer(modifier = Modifier.weight(1f))
                            Button(
                                onClick = { viewModel.ratingWriteTest() },
                                enabled = uiState.isLoggedIn && uiState.ratingDoubanId.isNotBlank() && !uiState.isRatingWriting,
                                contentPadding = PaddingValues(horizontal = 16.dp, vertical = 6.dp)
                            ) {
                                if (uiState.isRatingWriting) {
                                    CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp, color = MaterialTheme.colorScheme.onPrimary)
                                    Spacer(modifier = Modifier.width(6.dp))
                                    Text(stringResource(R.string.douban_spider_test_rating_writing))
                                } else {
                                    Text(stringResource(R.string.douban_spider_test_rating_run))
                                }
                            }
                        }
                        // 未登录提示
                        if (!uiState.isLoggedIn) {
                            Spacer(modifier = Modifier.height(8.dp))
                            Text(
                                text = stringResource(R.string.douban_spider_test_rating_no_login),
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        // 错误(技术堆栈/英文)
                        uiState.ratingError?.let { err ->
                            Spacer(modifier = Modifier.height(10.dp))
                            Surface(shape = RoundedCornerShape(8.dp), color = MaterialTheme.colorScheme.errorContainer) {
                                Text(
                                    text = err,
                                    modifier = Modifier.fillMaxWidth().padding(10.dp),
                                    style = MaterialTheme.typography.bodySmall,
                                    fontFamily = FontFamily.Monospace,
                                    color = MaterialTheme.colorScheme.onErrorContainer
                                )
                            }
                        }
                        // 结果
                        uiState.ratingResult?.let { res ->
                            val copyCtx = LocalContext.current
                            val copiedToast = stringResource(R.string.douban_spider_test_mark_copied)
                            val clipboard = copyCtx.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                            Spacer(modifier = Modifier.height(10.dp))
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                Text(
                                    text = "HTTP ${res.statusCode} · ${res.durationMs}ms · ${res.rating}" +
                                        stringResource(R.string.douban_spider_test_rating_star_unit) + " · " +
                                        if (res.success) stringResource(R.string.douban_spider_test_rating_success) else stringResource(R.string.douban_spider_test_rating_failed),
                                    style = MaterialTheme.typography.labelSmall,
                                    color = if (res.success) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error
                                )
                                TextButton(
                                    onClick = {
                                        clipboard.setPrimaryClip(ClipData.newPlainText("rating", res.body))
                                        copyCtx.showToast(copiedToast)
                                    },
                                    contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp)
                                ) {
                                    Text(stringResource(R.string.douban_spider_test_copy_result))
                                }
                            }
                            Spacer(modifier = Modifier.height(6.dp))
                            Surface(
                                modifier = Modifier.fillMaxWidth(),
                                shape = RoundedCornerShape(8.dp),
                                color = MaterialTheme.colorScheme.surfaceVariant
                            ) {
                                Text(
                                    text = res.body.ifBlank { "(empty body)" },
                                    modifier = Modifier.fillMaxWidth().padding(10.dp),
                                    style = MaterialTheme.typography.bodySmall,
                                    fontFamily = FontFamily.Monospace,
                                    color = MaterialTheme.colorScheme.onSurface
                                )
                            }
                        }
                    }
                }

                // ── 为你推荐测试(带 Cookie) ──
                item {
                    SectionCard(title = stringResource(R.string.douban_spider_test_recommend_section)) {
                        // 类型切换:电影 / 电视剧
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                text = stringResource(R.string.douban_spider_test_recommend_type),
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            FilterChip(
                                selected = uiState.recommendType == "movie",
                                border = if (uiState.recommendType == "movie") BorderStroke(1.dp, MaterialTheme.colorScheme.primary) else BorderStroke(1.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.3f)),
                                colors = FilterChipDefaults.filterChipColors(
                                    containerColor = MaterialTheme.colorScheme.surfaceContainerHighest,
                                    labelColor = MaterialTheme.colorScheme.onSurfaceVariant,
                                    selectedContainerColor = MaterialTheme.colorScheme.primary,
                                    selectedLabelColor = MaterialTheme.colorScheme.onPrimary
                                ),
                                onClick = { viewModel.updateRecommendType("movie") },
                                label = { Text(stringResource(R.string.douban_spider_test_recommend_movie)) },
                                modifier = Modifier.height(32.dp)
                            )
                            Spacer(modifier = Modifier.width(6.dp))
                            FilterChip(
                                selected = uiState.recommendType == "tv",
                                border = if (uiState.recommendType == "tv") BorderStroke(1.dp, MaterialTheme.colorScheme.primary) else BorderStroke(1.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.3f)),
                                colors = FilterChipDefaults.filterChipColors(
                                    containerColor = MaterialTheme.colorScheme.surfaceContainerHighest,
                                    labelColor = MaterialTheme.colorScheme.onSurfaceVariant,
                                    selectedContainerColor = MaterialTheme.colorScheme.primary,
                                    selectedLabelColor = MaterialTheme.colorScheme.onPrimary
                                ),
                                onClick = { viewModel.updateRecommendType("tv") },
                                label = { Text(stringResource(R.string.douban_spider_test_recommend_tv)) },
                                modifier = Modifier.height(32.dp)
                            )
                            Spacer(modifier = Modifier.weight(1f))
                            Button(
                                onClick = { viewModel.recommendTest() },
                                enabled = uiState.isLoggedIn && !uiState.isRecommending,
                                contentPadding = PaddingValues(horizontal = 16.dp, vertical = 6.dp)
                            ) {
                                if (uiState.isRecommending) {
                                    CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp, color = MaterialTheme.colorScheme.onPrimary)
                                    Spacer(modifier = Modifier.width(6.dp))
                                    Text(stringResource(R.string.douban_spider_test_recommending))
                                } else {
                                    Text(stringResource(R.string.douban_spider_test_recommend_run))
                                }
                            }
                        }
                        // 未登录提示
                        if (!uiState.isLoggedIn) {
                            Spacer(modifier = Modifier.height(8.dp))
                            Text(
                                text = stringResource(R.string.douban_spider_test_recommend_no_login),
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        // 测试中
                        if (uiState.isRecommending) {
                            Spacer(modifier = Modifier.height(10.dp))
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.Center
                            ) {
                                CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp, color = MaterialTheme.colorScheme.primary)
                                Spacer(modifier = Modifier.width(6.dp))
                                Text(stringResource(R.string.douban_spider_test_recommending), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                        // 错误(技术堆栈/英文)
                        uiState.recommendError?.let { err ->
                            Spacer(modifier = Modifier.height(10.dp))
                            Surface(shape = RoundedCornerShape(8.dp), color = MaterialTheme.colorScheme.errorContainer) {
                                Text(
                                    text = err,
                                    modifier = Modifier.fillMaxWidth().padding(10.dp),
                                    style = MaterialTheme.typography.bodySmall,
                                    fontFamily = FontFamily.Monospace,
                                    color = MaterialTheme.colorScheme.onErrorContainer
                                )
                            }
                        }
                        // 结果
                        uiState.recommendResult?.let { res ->
                            val copyCtx = LocalContext.current
                            val copiedToast = stringResource(R.string.douban_spider_test_mark_copied)
                            val clipboard = copyCtx.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                            Spacer(modifier = Modifier.height(10.dp))
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                Text(
                                    text = "${res.type.uppercase()} · HTTP ${res.statusCode} · ${res.durationMs}ms" +
                                        (res.itemCount?.let { " · $it ${stringResource(R.string.douban_spider_test_recommend_doulist)}" } ?: ""),
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                                TextButton(
                                    onClick = {
                                        clipboard.setPrimaryClip(ClipData.newPlainText("recommend", res.body))
                                        copyCtx.showToast(copiedToast)
                                    },
                                    contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp)
                                ) {
                                    Text(stringResource(R.string.douban_spider_test_copy_result))
                                }
                            }
                            Spacer(modifier = Modifier.height(6.dp))
                            if (res.titles.isNotEmpty()) {
                                res.titles.forEachIndexed { i, title ->
                                    Surface(
                                        modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp),
                                        shape = RoundedCornerShape(8.dp),
                                        color = MaterialTheme.colorScheme.surfaceVariant
                                    ) {
                                        Row(modifier = Modifier.padding(8.dp)) {
                                            Text(
                                                text = "${i + 1}.",
                                                modifier = Modifier.width(24.dp),
                                                style = MaterialTheme.typography.bodySmall,
                                                color = MaterialTheme.colorScheme.onSurfaceVariant
                                            )
                                            Text(
                                                text = title,
                                                modifier = Modifier.fillMaxWidth(),
                                                style = MaterialTheme.typography.bodySmall,
                                                color = MaterialTheme.colorScheme.onSurface
                                            )
                                        }
                                    }
                                }
                            } else {
                                Text(
                                    text = stringResource(R.string.douban_spider_test_recommend_no_items),
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                    }
                }

                // ── imdb→豆瓣ID 搜索测试 ──
                item {
                    SectionCard(title = stringResource(R.string.douban_spider_test_search_section)) {
                        OutlinedTextField(
                            value = uiState.searchImdbId,
                            onValueChange = viewModel::updateSearchImdbId,
                            modifier = Modifier.fillMaxWidth(),
                            singleLine = true,
                            placeholder = { Text(stringResource(R.string.douban_spider_test_search_imdb_hint), color = if (isDark) Color.White.copy(alpha = 0.4f) else Color(0xFF90A4AE)) },
                            textStyle = androidx.compose.ui.text.TextStyle(
                                fontFamily = FontFamily.Monospace,
                                fontSize = 13.sp
                            )
                        )
                        Spacer(modifier = Modifier.height(10.dp))
                        Row(
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Spacer(modifier = Modifier.weight(1f))
                            Button(
                                onClick = { viewModel.searchByImdbTest() },
                                enabled = uiState.searchImdbId.isNotBlank() && !uiState.isSearching,
                                contentPadding = PaddingValues(horizontal = 16.dp, vertical = 6.dp)
                            ) {
                                if (uiState.isSearching) {
                                    CircularProgressIndicator(
                                        modifier = Modifier.size(16.dp),
                                        strokeWidth = 2.dp,
                                        color = MaterialTheme.colorScheme.onPrimary
                                    )
                                    Spacer(modifier = Modifier.width(6.dp))
                                    Text(stringResource(R.string.douban_spider_test_searching))
                                } else {
                                    Text(stringResource(R.string.douban_spider_test_search_run))
                                }
                            }
                        }
                        // 未输入提示
                        if (uiState.searchImdbId.isBlank()) {
                            Spacer(modifier = Modifier.height(8.dp))
                            Text(
                                text = stringResource(R.string.douban_spider_test_search_no_imdb),
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        // 搜索中
                        if (uiState.isSearching) {
                            Spacer(modifier = Modifier.height(10.dp))
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.Center
                            ) {
                                CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp, color = MaterialTheme.colorScheme.primary)
                                Spacer(modifier = Modifier.width(6.dp))
                                Text(stringResource(R.string.douban_spider_test_searching), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                        // 错误(技术堆栈/英文)
                        uiState.searchError?.let { err ->
                            Spacer(modifier = Modifier.height(10.dp))
                            Surface(shape = RoundedCornerShape(8.dp), color = MaterialTheme.colorScheme.errorContainer) {
                                Text(
                                    text = err,
                                    modifier = Modifier.fillMaxWidth().padding(10.dp),
                                    style = MaterialTheme.typography.bodySmall,
                                    fontFamily = FontFamily.Monospace,
                                    color = MaterialTheme.colorScheme.onErrorContainer
                                )
                            }
                        }
                        // 结果
                        uiState.searchResult?.let { res ->
                            val copyCtx = LocalContext.current
                            val copiedToast = stringResource(R.string.douban_spider_test_mark_copied)
                            val clipboard = copyCtx.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                            Spacer(modifier = Modifier.height(10.dp))
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                Text(
                                    text = "${res.imdbId} · HTTP ${res.statusCode} · ${res.durationMs}ms" +
                                        if (res.isLoginPage) " · 登录页" else "",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                                TextButton(
                                    onClick = {
                                        clipboard.setPrimaryClip(ClipData.newPlainText("html", res.html))
                                        copyCtx.showToast(copiedToast)
                                    },
                                    contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp)
                                ) {
                                    Text(stringResource(R.string.douban_spider_test_copy_result))
                                }
                            }
                            Spacer(modifier = Modifier.height(6.dp))
                            if (res.results.isNotEmpty()) {
                                res.results.forEachIndexed { i, item ->
                                    Surface(
                                        modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp),
                                        shape = RoundedCornerShape(8.dp),
                                        color = MaterialTheme.colorScheme.surfaceVariant
                                    ) {
                                        Column(modifier = Modifier.padding(10.dp)) {
                                            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                                                Text(
                                                    text = "${i + 1}.",
                                                    modifier = Modifier.width(24.dp),
                                                    style = MaterialTheme.typography.bodySmall,
                                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                                )
                                                Column {
                                                    Text(
                                                        text = item.title,
                                                        style = MaterialTheme.typography.bodySmall,
                                                        color = MaterialTheme.colorScheme.onSurface
                                                    )
                                                    Spacer(modifier = Modifier.height(4.dp))
                                                    Text(
                                                        text = "doubanId: ${item.doubanId}",
                                                        style = MaterialTheme.typography.labelSmall,
                                                        fontFamily = FontFamily.Monospace,
                                                        color = MaterialTheme.colorScheme.primary
                                                    )
                                                    Text(
                                                        text = "url: ${item.doubanUrl}",
                                                        style = MaterialTheme.typography.labelSmall,
                                                        fontFamily = FontFamily.Monospace,
                                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                                    )
                                                    item.rating?.let { r ->
                                                        Text(
                                                            text = "rating: $r",
                                                            style = MaterialTheme.typography.labelSmall,
                                                            fontFamily = FontFamily.Monospace,
                                                            color = MaterialTheme.colorScheme.onSurfaceVariant
                                                        )
                                                    }
                                                }
                                            }
                                        }
                                    }
                                }
                            } else {
                                Text(
                                    text = stringResource(R.string.douban_spider_test_search_no_result),
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                            // 原始 HTML(可折叠)
                            Spacer(modifier = Modifier.height(10.dp))
                            var showHtml by remember { mutableStateOf(false) }
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                TextButton(
                                    onClick = { showHtml = !showHtml },
                                    contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp)
                                ) {
                                    Text(if (showHtml) stringResource(R.string.douban_spider_test_search_hide_html) else stringResource(R.string.douban_spider_test_search_show_html))
                                }
                            }
                            if (showHtml) {
                                Surface(
                                    modifier = Modifier.fillMaxWidth(),
                                    shape = RoundedCornerShape(8.dp),
                                    color = MaterialTheme.colorScheme.surfaceVariant
                                ) {
                                    Box(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .heightIn(max = 300.dp)
                                            .verticalScroll(rememberScrollState())
                                    ) {
                                        Text(
                                            text = res.html,
                                            modifier = Modifier.padding(10.dp),
                                            style = MaterialTheme.typography.bodySmall,
                                            fontFamily = FontFamily.Monospace,
                                            color = MaterialTheme.colorScheme.onSurface
                                        )
                                    }
                                }
                            }
                        }
                    }
                }

                // ── 错误区(网络异常) ──
                if (uiState.error != null) {
                    item {
                        SectionCard(title = "Error") {
                            Text(
                                text = uiState.error ?: "",
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(8.dp),
                                style = MaterialTheme.typography.bodySmall,
                                fontFamily = FontFamily.Monospace,
                                color = MaterialTheme.colorScheme.error
                            )
                        }
                    }
                }

                // ── 结果区(TabRow 吸顶,各 Tab 独立滚动保留位置) ──
                uiState.result?.let { result ->
                    // meta-stats 行
                    item {
                        Surface(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 12.dp),
                            shape = RoundedCornerShape(8.dp),
                            color = MaterialTheme.colorScheme.surfaceVariant
                        ) {
                            Row(
                                modifier = Modifier.padding(10.dp),
                                horizontalArrangement = Arrangement.spacedBy(12.dp)
                            ) {
                                val okColor = Color(0xFF4CAF50)
                                Text(
                                    text = "HTTP ${result.statusCode}",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = if (result.statusCode in 200..299) okColor else MaterialTheme.colorScheme.error
                                )
                                Text(
                                    text = "${result.durationMs}ms",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                                Text(
                                    text = "isLoginPage: ${result.isLoginPage}",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = if (result.isLoginPage) MaterialTheme.colorScheme.error else okColor
                                )
                            }
                        }
                    }

                    // TabRow 吸顶
                    stickyHeader {
                        PrimaryTabRow(selectedTabIndex = selectedTab) {
                            val tabs = listOf(
                                stringResource(R.string.douban_spider_test_tab_fields),
                                stringResource(R.string.douban_spider_test_tab_html),
                                stringResource(R.string.douban_spider_test_tab_preview)
                            )
                            tabs.forEachIndexed { index, title ->
                                Tab(
                                    selected = selectedTab == index,
                                    onClick = { selectedTab = index },
                                    text = { Text(title, style = MaterialTheme.typography.labelMedium) }
                                )
                            }
                        }
                    }

                    // Tab 内容:FieldsTab/HtmlTab 自然高度在 LazyColumn 中滚动;PreviewTab 用 fillParentMaxSize 让 WebView 自带滚动
                    when (selectedTab) {
                        0 -> item { FieldsTab(result) }
                        1 -> item { HtmlTab(result.html, context) }
                        2 -> item {
                            Box(modifier = Modifier.fillParentMaxSize()) {
                                PreviewTab(uiState.url, uiState.cookie, uiState.ua)
                            }
                        }
                    }
                }
            }

            // 快选已有条目弹窗
            if (showPickerDialog) {
                CachedItemsPickerDialog(
                    items = cachedItems,
                    onPick = { url ->
                        viewModel.updateUrl(url)
                        showPickerDialog = false
                    },
                    onDismiss = { showPickerDialog = false }
                )
            }
        }
    }
}

// ==================== 子组件 ====================

@Composable
private fun SectionCard(title: String, content: @Composable () -> Unit) {
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 6.dp),
        shape = RoundedCornerShape(14.dp),
        color = MaterialTheme.colorScheme.surfaceVariant,
        border = androidx.compose.foundation.BorderStroke(
            1.dp,
            MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f)
        )
    ) {
        Column(modifier = Modifier.padding(14.dp)) {
            Text(
                text = "── $title",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(modifier = Modifier.height(10.dp))
            content()
        }
    }
}

/** 提取字段 Tab:逐行展示 DoubanDetailInfo,空值标红 */
@Composable
private fun FieldsTab(result: SpiderTestResult) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp)
            .navigationBarsPadding()
    ) {
        // parseDetail 异常时显示堆栈
        result.parseError?.let { err ->
            Surface(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(8.dp),
                color = MaterialTheme.colorScheme.errorContainer
            ) {
                Text(
                    text = err,
                    modifier = Modifier.padding(10.dp),
                    style = MaterialTheme.typography.bodySmall,
                    fontFamily = FontFamily.Monospace,
                    color = MaterialTheme.colorScheme.onErrorContainer
                )
            }
            return
        }
        val info = result.parsed ?: run {
            Text(
                text = "parseDetail returned null",
                modifier = Modifier.padding(10.dp),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            return
        }
        Text(
            text = "DoubanDetailInfo:",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(vertical = 8.dp)
        )
        FieldRow("imdbId", info.imdbId)
        FieldRow("isTvShow", info.isTvShow.toString())
        FieldRow("title", info.title)
        FieldRow("posterUrl", info.posterUrl)
        FieldRow("doubanRating", info.doubanRating?.toString())
        FieldRow("ratingCount", info.ratingCount?.toString())
        FieldRow("summary", info.summary)
        FieldRow("episodeCount", info.episodeCount?.toString())
        FieldRow("episodeDuration", info.episodeDuration)
        FieldRow("aka", info.aka.takeIf { it.isNotEmpty() }?.toString())
        FieldRow("runtime", info.runtime)
        FieldRow("genres", info.genres.takeIf { it.isNotEmpty() }?.toString())
        FieldRow("year", info.year)
        FieldRow("countries", info.countries.takeIf { it.isNotEmpty() }?.toString())
        FieldRow("directors", info.directors.takeIf { it.isNotEmpty() }?.toString())
        FieldRow("writers", info.writers.takeIf { it.isNotEmpty() }?.toString())
        FieldRow("cast", info.cast.takeIf { it.isNotEmpty() }?.toString())
        FieldRow("languages", info.languages.takeIf { it.isNotEmpty() }?.toString())
        FieldRow("initialReleaseDates", info.initialReleaseDates.takeIf { it.isNotEmpty() }?.toString())
        FieldRow("ratingDistribution", info.ratingDistribution.takeIf { it.isNotEmpty() }?.toString())
        FieldRow(
            "celebrities",
            info.celebrities.takeIf { it.isNotEmpty() }?.joinToString("\n") { c ->
                "${c.name} | ${c.role ?: "-"} | ${c.avatarUrl ?: "-"} | ${c.doubanPersonageUrl ?: "-"}"
            }
        )
    }
}

@Composable
private fun FieldRow(label: String, value: String?) {
    val isEmpty = value.isNullOrBlank()
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 2.dp),
        shape = RoundedCornerShape(8.dp),
        color = MaterialTheme.colorScheme.surfaceVariant
    ) {
        Row(modifier = Modifier.padding(8.dp)) {
            Text(
                text = label,
                modifier = Modifier.width(110.dp),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Text(
                text = if (isEmpty) "(${stringResource(R.string.douban_spider_test_empty)})" else value,
                modifier = Modifier.fillMaxWidth(),
                style = MaterialTheme.typography.bodySmall,
                fontFamily = FontFamily.Monospace,
                color = if (isEmpty) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface,
                fontWeight = if (isEmpty) FontWeight.Normal else FontWeight.Normal
            )
        }
    }
}

/** 原始 HTML Tab:等宽字体 + 复制/导出按钮(自然高度在 LazyColumn 中滚动) */
@Composable
private fun HtmlTab(html: String, context: Context) {
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    val txtLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("text/plain")) { uri ->
        if (uri != null) {
            scope.launch {
                kotlinx.coroutines.withContext(Dispatchers.IO) {
                    context.contentResolver.openOutputStream(uri)?.use { os ->
                        os.write(html.toByteArray(Charsets.UTF_8))
                    }
                }
                context.showToast(context.getString(R.string.douban_spider_test_exported))
            }
        }
    }
    val htmlLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("text/html")) { uri ->
        if (uri != null) {
            scope.launch {
                kotlinx.coroutines.withContext(Dispatchers.IO) {
                    context.contentResolver.openOutputStream(uri)?.use { os ->
                        os.write(html.toByteArray(Charsets.UTF_8))
                    }
                }
                context.showToast(context.getString(R.string.douban_spider_test_exported))
            }
        }
    }
    val ts = remember { System.currentTimeMillis() }
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp)
            .navigationBarsPadding()
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            OutlinedButton(
                onClick = {
                    val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                    clipboard.setPrimaryClip(ClipData.newPlainText("html", html))
                    context.showToast(context.getString(R.string.douban_spider_test_copied))
                },
                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp)
            ) {
                Text(stringResource(R.string.douban_spider_test_copy_html))
            }
            OutlinedButton(
                onClick = { txtLauncher.launch("douban_$ts.txt") },
                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp)
            ) {
                Icon(Icons.Rounded.FileDownload, contentDescription = null, modifier = Modifier.size(16.dp))
                Spacer(modifier = Modifier.width(4.dp))
                Text(stringResource(R.string.douban_spider_test_export_txt))
            }
            OutlinedButton(
                onClick = { htmlLauncher.launch("douban_$ts.html") },
                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp)
            ) {
                Icon(Icons.Rounded.FileDownload, contentDescription = null, modifier = Modifier.size(16.dp))
                Spacer(modifier = Modifier.width(4.dp))
                Text(stringResource(R.string.douban_spider_test_export_html))
            }
        }
        Surface(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 8.dp),
            shape = RoundedCornerShape(8.dp),
            color = Color(0xFF0A0C10)
        ) {
            Text(
                text = html,
                modifier = Modifier.padding(10.dp),
                style = androidx.compose.ui.text.TextStyle(
                    fontFamily = FontFamily.Monospace,
                    fontSize = 10.sp,
                    lineHeight = 14.sp
                ),
                color = Color(0xFF9AA0A6)
            )
        }
    }
}

/** 网页预览 Tab:WebView 用同 UA + cookie 加载同一 URL */
@SuppressLint("SetJavaScriptEnabled")
@Composable
private fun PreviewTab(url: String, cookie: String, ua: TestUa) {
    val pcUa = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36"
    val mobileUa = "Mozilla/5.0 (Linux; Android 13; Pixel 7) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Mobile Safari/537.36"
    AndroidView(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 12.dp)
            .clip(RoundedCornerShape(8.dp)),
        factory = { ctx ->
            // 同步 cookie 到 WebView
            CookieManager.getInstance().setAcceptCookie(true)
            if (cookie.isNotBlank()) {
                CookieManager.getInstance().setCookie(url, cookie)
            }
            CookieManager.getInstance().flush()
            WebView(ctx).apply {
                settings.javaScriptEnabled = true
                settings.userAgentString = if (ua == TestUa.PC) pcUa else mobileUa
                webViewClient = WebViewClient()
                loadUrl(url)
            }
        },
        update = { webView ->
            // URL 变化时重新加载
            if (webView.url != url) {
                CookieManager.getInstance().setCookie(url, cookie)
                CookieManager.getInstance().flush()
                webView.loadUrl(url)
            }
        }
    )
}

/** 快选已有豆瓣条目弹窗:grid 3 列展示海报+名字,点击填入爬取地址 */
@Composable
private fun CachedItemsPickerDialog(
    items: List<CachedDoubanItem>,
    onPick: (String) -> Unit,
    onDismiss: () -> Unit
) {
    AppFloatingDialog(
        onDismissRequest = onDismiss,
        title = stringResource(R.string.douban_spider_test_pick_cached_title) +
            if (items.isNotEmpty()) " (${items.size})" else ""
    ) {
        if (items.isEmpty()) {
            Text(
                text = stringResource(R.string.douban_spider_test_no_cached),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        } else {
            LazyVerticalGrid(
                columns = GridCells.Fixed(3),
                modifier = Modifier.heightIn(max = 420.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                items(items) { item ->
                    CachedItemCard(item = item, onClick = { onPick(item.doubanUrl) })
                }
            }
        }
    }
}

@Composable
private fun CachedItemCard(item: CachedDoubanItem, onClick: () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .clickable(onClick = onClick)
            .padding(4.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(132.dp)
                .clip(RoundedCornerShape(6.dp))
                .background(MaterialTheme.colorScheme.surfaceVariant)
        ) {
            if (!item.posterUrl.isNullOrBlank()) {
                AsyncImage(
                    model = ImageRequest.Builder(LocalContext.current)
                        .data(item.posterUrl)
                        .crossfade(true)
                        .build(),
                    contentDescription = item.title,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize()
                )
            } else {
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Icon(
                        Icons.Rounded.Movie,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
        Spacer(modifier = Modifier.height(4.dp))
        Text(
            text = item.title ?: item.doubanId,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurface,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}
