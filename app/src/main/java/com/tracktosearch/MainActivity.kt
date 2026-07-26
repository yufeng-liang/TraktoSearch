package com.tracktosearch

import android.app.AlertDialog
import android.content.DialogInterface
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.widget.Toast
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.app.AppCompatDelegate
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.core.os.LocaleListCompat
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.lifecycleScope
import com.tracktosearch.data.local.DefaultTabStorage
import com.tracktosearch.data.local.GuestModeStorage
import com.tracktosearch.data.local.LanguageStorage
import com.tracktosearch.data.local.ThemeStorage
import com.tracktosearch.data.auth.AuthManager
import com.tracktosearch.data.auth.AuthState
import com.tracktosearch.data.remote.trakt.TraktAuthManager
import com.tracktosearch.data.util.CrashLogUploader
import com.tracktosearch.push.JPushHelper
import com.tracktosearch.ui.navigation.AppNavigation
import com.tracktosearch.ui.navigation.Routes
import com.tracktosearch.ui.theme.TraktToSearchTheme
import com.tracktosearch.ui.util.LocalScrollToTopProvider
import com.tracktosearch.ui.util.ScrollToTopProvider
import com.tracktosearch.ui.util.showToast
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import java.util.Locale
import javax.inject.Inject

// 闂佺绻堥崝宀勬儑椤掑嫬绀傞柛娆愶耿閻撯晠鏌?OAuth 缂傚倷鐒﹂幐濠氭倶婢舵劖鏅悘鐐跺亹鏉?MainActivity 婵炵鍋愭繛鈧柍褜鍓氱敮鐐靛垝?LoginViewModel
// 闂?StateFlow 闂佸搫娲ら妵姗€宕?@Volatile var闂佹寧绋戦惌鍌涘閳哄懎绀?LoginScreen 闁哄鍎愰崰娑㈩敋?300ms 閻庣偣鍊栭崕鑲╂崲?object OAuthCallback {
object OAuthCallback {
    private val _pendingCodeFlow = kotlinx.coroutines.flow.MutableStateFlow<String?>(null)
    val pendingCodeFlow: kotlinx.coroutines.flow.StateFlow<String?> = _pendingCodeFlow

    private val _authDeniedFlow = kotlinx.coroutines.flow.MutableStateFlow(false)
    val authDeniedFlow: kotlinx.coroutines.flow.StateFlow<Boolean> = _authDeniedFlow

    // 闂佺绻掗崢褔顢欓幇鏉跨睄鐟滃矂宕鍕剺濞达絿鍎ら悾閬嶆煕濮橆剚婀版い鎺斿仧閹峰宕稿Δ渚婄椽
    val pendingCode: String? get() = _pendingCodeFlow.value
    val authDenied: Boolean get() = _authDeniedFlow.value

    fun setPendingCode(code: String?) { _pendingCodeFlow.value = code }
    fun setAuthDenied(denied: Boolean) { _authDeniedFlow.value = denied }

    fun clear() {
        _pendingCodeFlow.value = null
        _authDeniedFlow.value = false
    }
}

/** 闂備緡鍋呭銊╂偂閳ュ煄搴ㄦ焼瀹ュ棙鍤戦柣鐘辫閸ㄤ即顢氶柆宥嗗殝妞ゅ繐鎳庨惁鐟懊归悩顔尖偓褏妲愬┑鍥╃懝?MainActivity 婵炵鍋愭繛鈧柍褜鍓氱敮鐐靛垝?AppNavigation 闂?NavController */
object DeepLinkNavigator {
    data class NavigateToDetail(
        val type: String,
        val traktId: Int,
        val tmdbId: Int,
        val title: String
    )

    private val _pendingNavigation = kotlinx.coroutines.flow.MutableStateFlow<NavigateToDetail?>(null)
    val pendingNavigation: kotlinx.coroutines.flow.StateFlow<NavigateToDetail?> = _pendingNavigation

    fun navigateToDetail(type: String, traktId: Int, tmdbId: Int, title: String) {
        _pendingNavigation.value = NavigateToDetail(type, traktId, tmdbId, title)
    }

    fun consume() { _pendingNavigation.value = null }
}

@AndroidEntryPoint
class MainActivity : AppCompatActivity() {

    companion object {
        private const val MIN_SYSTEM_SPLASH_DURATION_MS = 1350L
        // 闂佸搫鐗冮崑鎾绘倶?splash 闂佸搫瀚晶浠嬪Φ濮樿泛绫嶉柛顐ｆ礃閿涚喖鏌ㄥ☉妯煎濠殿喒鏅犻幃鐣岀磼濡椿妲梻鍌楀亾闁告牭绱曠粈鍡涙煥濞戞瀚伴柣銈呭濮婁粙宕ㄩ鐐碘偓濂告煙楠炲灝鐏繛纰卞灡缁?= max(闂佸搫鐗冮崑鎾绘倶韫囨挾绠叉俊鐐插€垮? 婵☆偅婢樼€氼剝銇愰崶顒€鏋侀柣妤€鐗嗙粊锕傛倶韫囨矮鍚柛?
        // 闂佸憡鍑归崹鐗堟叏閳哄懎绀夐柕濞у嫭姣庨梺鍝勫暢濞夋稓鑺遍銏℃櫖婵﹩鍓氶崐妤呮煕韫囨挸鏆熺紒鈧畝鍕闁靛鍎茬花姘扁偓鐐瑰€栭崕鑲╂崲濠婂牊鏅柛褜鏆€闂?
    }

    @Inject
    lateinit var authManager: AuthManager

    @Inject
    lateinit var traktAuthManager: TraktAuthManager

    @Inject
    lateinit var guestModeStorage: GuestModeStorage

    @Inject
    lateinit var themeStorage: ThemeStorage

    @Inject
    lateinit var languageStorage: LanguageStorage

    @Inject
    lateinit var defaultTabStorage: DefaultTabStorage

    @Inject
    lateinit var traktRepository: com.tracktosearch.data.repository.TraktRepository

    @Inject
    lateinit var tmdbRepository: com.tracktosearch.data.repository.TmdbRepository

    @Inject
    lateinit var sharedTransitionStorage: com.tracktosearch.data.local.SharedTransitionStorage

    // 闂佺绻堥崝宀勬儑?scrollToTop 闂佸湱绮崝鎺旀閻㈠憡鍤€?
    private val scrollToTopProvider = ScrollToTopProvider()
    private var authInitializationJob: Job? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        var isReady by mutableStateOf(false)
        // 闁诲海鎳撻ˇ鎶剿?SplashScreen闂佹寧绋戦懟顖炈囬埡鍛仩闁糕剝鐟╅崗鍥╃磽娴ｅ搫鏋涚紒顕呭灣閹峰濡堕崨顔煎壔闂佸憡鏌ｉ崝鎴﹀Υ婢舵劕绀嗛柟宄拌嫰濞堜即鎮楃憴鍕叝缂?splash 闂佹眹鍔岀€氼剟鏌﹂埡渚囩叆闁硅揪绲跨粻鏍ㄧ箾?
        val splashScreen = installSplashScreen()
        // 婵炶揪缍€濞夋洟寮?mutableStateOf 闁?Compose 闂佺厧鍟块埀顒傚櫏濞煎酣鎮楅悽闈涙瀻闁糕晛鐭傚畷锝呂熼崫鍕靛殭
        splashScreen.setKeepOnScreenCondition { !isReady }
        super.onCreate(savedInstanceState)
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.auto(
                lightScrim = android.graphics.Color.TRANSPARENT,
                darkScrim = android.graphics.Color.TRANSPARENT,
            ),
            navigationBarStyle = SystemBarStyle.auto(
                lightScrim = android.graphics.Color.TRANSPARENT,
                darkScrim = android.graphics.Color.TRANSPARENT,
            ),
        )
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            window.isNavigationBarContrastEnforced = false
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            window.attributes = window.attributes.apply {
                layoutInDisplayCutoutMode = android.view.WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
            }
        }

        var startDest by mutableStateOf(Routes.LOGIN)
        var initialTab by mutableStateOf(0)
        var isTraktConnected by mutableStateOf(false)

        authInitializationJob = lifecycleScope.launch {
            val splashStartTime = System.currentTimeMillis()
            authManager.initialize()
            val authState = authManager.authState.value
            val isAuthorized = authState == AuthState.AUTHORIZED || authState == AuthState.OFFLINE
            // 缂傚倸鍟崹鐢稿矗瑜嶉埞鎴﹀焵椤掆偓鐓ら悹浣哥－閻?Trakt OAuth 闂佸搫瀚烽崹顖滄偖鏉堛劎闄勬俊銈呭暙椤忣亞绱掗弬娆惧剮缂佽鲸澹嗛幏鐘测攽閸曘劌浜鹃柛灞剧閸庢瑩鏌涢弬璇插婵＄偛鍊界粻娑㈠川濞ｎ兘鍋撹箛娑欏剳闁绘棃顥撻弶鑺ヤ繆椤愮喎浜鹃梺?Trakt闂?
            // 闂佸憡鐔粻鎴﹀垂椤栨埃鏀介柍褜鍓欑叅缂備焦锚閻忓洭鏌涢弮鍌氭瀺缂佽鲸澹嗛幃顕€顢曢妷顔兼閻熸粎澧楅幐濠氬垂?Trakt 閻庤鐡曠亸娆忊枍閵夈劊浜归柡鍥朵簽缁€澶愭煟閳轰胶鎽犻悽顖氼嚟閹瑰嫮鈧湱濮风粻鏍煕濡厧鏋戞繝鈧笟鈧幆鍌滄嫚閼碱剛协婵＄偑鍊楅崑锝夊焵?
            isTraktConnected = isAuthorized && traktRepository.checkTraktConnection()
            startDest = when {
                !isAuthorized -> Routes.LOGIN
                isTraktConnected -> Routes.MAIN
                else -> Routes.LOGIN
            }
            // 閻庤鐡曠亸娆忊枍閵夈劊浜归柡鍥╁枑椤ρ囨偣閸ヮ亶鍤欑憸鏉挎喘閹粙濡搁敃鈧悡鏇㈡偣娴ｇ鈷旈柣銈呮閹啴宕熼婊冪闁荤姳闄嶉崐鏇㈠箚鎼淬劌绀夐柕濞炬杺閳ь剙顦甸弫宥囦沪閻愵剙绱﹂梺璋庡棭鍤欑紓宥呯Ч瀵噣寮甸棃娑掓瀼闂佹椿娼块崝宥夊箖瀹曞洦顫曢柕蹇嬪€戦埀顒€顦甸弫?闂?
            initialTab = if (isTraktConnected) {
                defaultTabStorage.defaultTab.first()
            } else {
                0
            }

            val language = languageStorage.language.first()
            applyLanguage(language)

            // 婵☆偅婢樼€氼剚鎱ㄩ悙瀛樺闁芥ê顦ぐ娆徝瑰搴′簻闁告ɑ鎸惧Σ鎰版偐閻戞銈梺闈╃畱閹碱偆妲愰幋锕€绀傜紒娑樻贡缁€澶娾攽婢舵ê浜滈柛?StateFlow 闂佹椿浜為崰搴ㄦ偪閸曨垰纾圭痪顓㈩棑缁€澶嬬箾閹存繄澧︽繛鍛捣閹峰宕滆閺嬪倸顪冮妶澶嬫锭缂佽鲸鍨垮畷妤冣偓鍦Х閸庢煡鏌?
            sharedTransitionStorage.preloadAndGetValue()

            // Splash 闂佸搫鐗忛崰鏍涢崸妤冨祦闁诡垶鍋婇弨钘壩涢弶鍨仼鐟滄澘娲﹂—鈧俊顖涱儥閸氬洤螞閿濆棛澧甸柕鍡楊樀瀵偊鎮ч崼婵堛偊闂佹寧绋戦惉鑲╁垝閵娾晛鍑犳繝濠傚暙閺呮悂鏌?Repository 闂佸憡鍔曢幊搴ㄦ偤閵娧呯＝闁规儳纾幗鐘层€?MainScreen 婵犮垼娉涚粔鍫曞极?
            val prefetchJobs = mutableListOf<kotlinx.coroutines.Job>()
            if (isTraktConnected) {
                prefetchJobs.add(launch { runCatching { traktRepository.getMovieWatchlist(page = 1, limit = 200) } })
                prefetchJobs.add(launch { runCatching { tmdbRepository.getPopularMovies() } })
                prefetchJobs.add(launch { runCatching { tmdbRepository.getUpcomingMovies() } })
            }

            if (isTraktConnected) {
                // 鐎规瓕灏欏▍銉ㄣ亹閺囨氨鐟繝纰樺亾婵炶弓绱槐鎵寲閼姐倗鍩?Splash 闁归晲鑳堕悽濠氬礆娴兼惌娴曞銈囨暬椤ｂ晠宕ｉ弽锔藉床闁告柡鈧啿寮块梺顔哄妿缁劑寮?
                prefetchJobs.forEach { it.join() }
            } else {
                // 闁哄牜浜炲▍銉ㄣ亹閺囩喎鐏楅柡鍫簼缁哄搫煤娴兼瑧绐楅柛娆樹簷缁绘岸骞愭担娲厙缂?Splash 闁汇劌瀚〒鍓佷焊韫囨挾娼旂紒鈧悜妯活槯闂傗偓?
                val elapsed = System.currentTimeMillis() - splashStartTime
                val remaining = MIN_SYSTEM_SPLASH_DURATION_MS - elapsed
                if (remaining > 0) delay(remaining)
            }

            isReady = true
        }

        handleIntent(intent)
        // 缂備礁顦抽褔宕?IO 缂備焦宕樺▔鏇㈠煝婵傚憡鏅€光偓閸曨亞绱氶梺绋跨箰缁夋挳骞冮幘鎰佹桨闁靛鐓堥崵?SharedPreferences + 閻庤娲嶉弲婊呰姳濠靛绫嶉柕澶堝劤缁犲爼鏌￠崒姘煑婵炲棎鍨藉鑲╂嫚瀹割喗鏆佹繛鎴炴崄瀹曠敻宕垫惔锝囩煓?
        lifecycleScope.launch(kotlinx.coroutines.Dispatchers.IO) {
            checkCrashAndPrompt()
        }

        // 闂佺儵鏅滈崹鐢稿箚婢舵劖鍋愰柤鍝ヮ暯閸嬫挻鎷呴崷顓炵効闂佺粯鍔楅幊鎾诲吹椤曗偓閺佸秴鐣濋崘鍐╂礋瀹?scrollToTop
        setupStatusBarTapListener()

        setContent {
            val themeMode by themeStorage.themeMode.collectAsStateWithLifecycle()
            val accentColor by themeStorage.accentColor.collectAsStateWithLifecycle()
            TraktToSearchTheme(themeMode = themeMode, accentColor = accentColor) {
                CompositionLocalProvider(LocalScrollToTopProvider provides scrollToTopProvider) {
                // 闂佺厧顨庢禍婊堟偩閻愵剛鈻曞璺猴攻閸庢瑩鏌涢弬璇插闁靛棗顦妴鎺楀矗婢跺苯甯梺鍛婅壘閸戠晫妲愬┑鍫濆闁煎鍊楅崺鐘绘煕濮樼厧鐏犲┑顔规櫆閵囧嫰鎮介悽闈涚秳闂佸憡顨夊▍鏇犵矓闁垮绶?
                if (isReady) {
                    var currentDestination by remember { mutableStateOf(startDest) }
                    val authStateHolder = remember {
                        com.tracktosearch.ui.navigation.AuthStateHolder(authManager, traktAuthManager)
                    }
                    AppNavigation(
                        startDestination = currentDestination,
                        initialTab = initialTab,
                        initialTraktLoggedIn = isTraktConnected,
                        authStateHolder = authStateHolder,
                        onLoginSuccess = {
                            currentDestination = Routes.MAIN
                        }
                    )
                }

                } // CompositionLocalProvider
            }
        }
    }

    override fun onResume() {
        super.onResume()
        JPushHelper.onResume(this)
        lifecycleScope.launch {
            authInitializationJob?.join()
            if (authManager.authState.value != AuthState.UNAUTHORIZED) {
                authManager.check()
            }
        }
    }

    private var statusBarHeight: Int = 0

    private fun setupStatusBarTapListener() {
        statusBarHeight = resources.getIdentifier("status_bar_height", "dimen", "android")
            .let { if (it > 0) resources.getDimensionPixelSize(it) else 0 }
    }

    override fun dispatchTouchEvent(event: android.view.MotionEvent): Boolean {
        if (event.action == android.view.MotionEvent.ACTION_UP && statusBarHeight > 0) {
            if (event.rawY <= statusBarHeight) {
                scrollToTopProvider.scrollToTop()
            }
        }
        return super.dispatchTouchEvent(event)
    }

    override fun onPause() {
        super.onPause()
        JPushHelper.onPause(this)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleIntent(intent)
    }

    private fun handleIntent(intent: Intent?) {
        // 婵犮垼娉涚€氼噣骞?OAuth 闂佹悶鍎抽崑鐘绘儍?        intent?.data?.let { uri ->
        intent?.data?.let { uri ->
            if (uri.scheme == "tracktosearch" && uri.host == "oauth") {
                val error = uri.getQueryParameter("error")
                if (!error.isNullOrEmpty()) {
                    OAuthCallback.setAuthDenied(true)
                } else {
                    val code = uri.getQueryParameter("code")
                    if (!code.isNullOrEmpty()) {
                        OAuthCallback.setPendingCode(code)
                    }
                }
                return
            }
        }
        // 婵犮垼娉涚€氼噣骞冩繝鍥ㄧ劵婵浜崣鈧┑鐑囩秮娴滃爼骞楅懖鈺傚磯妞ゆ牓鍊楃粈鍕槈閹剧鏀绘俊?闂佸搫鍊瑰姗€顢楀鍫熺劵婵浜崣鈧梺缁樺姉閹虫捇宕甸鐐磯閻庡湱濮电粊顕€鎮归崶鐑芥闁稿骸绻戦妵鍕枈婢跺瞼顦?        if (intent?.getStringExtra("navigate_to") == "detail") {
        if (intent?.getStringExtra("navigate_to") == "detail") {
            val type = intent.getStringExtra("type") ?: return
            val traktId = intent.getIntExtra("traktId", 0)
            val tmdbId = intent.getIntExtra("tmdbId", 0)
            val title = intent.getStringExtra("title") ?: ""
            if (traktId > 0) {
                DeepLinkNavigator.navigateToDetail(type, traktId, tmdbId, title)
            }
        }
    }

    private fun applyLanguage(language: String) {
        val locales = when (language) {
            LanguageStorage.LANGUAGE_CHINESE -> LocaleListCompat.forLanguageTags("zh-CN")
            LanguageStorage.LANGUAGE_ENGLISH -> LocaleListCompat.forLanguageTags("en")
            LanguageStorage.LANGUAGE_JAPANESE -> LocaleListCompat.forLanguageTags("ja")
            LanguageStorage.LANGUAGE_KOREAN -> LocaleListCompat.forLanguageTags("ko")
            else -> LocaleListCompat.getEmptyLocaleList()
        }
        AppCompatDelegate.setApplicationLocales(locales)
        // ComponentActivity 婵炴垶鎸哥粔宕囨娴煎瓨鍤婃い蹇撳琚熸繝銏ｆ硾鐎氼噣骞?AppCompatDelegate 闂?locale 闂佸憡鐟﹁摫婵炴彃娼￠弫?
        // 闂傚倸娲犻崑鎾绘煙闂堟侗鍎忓┑顔规櫊瀵鈧稒蓱閻撯偓 Configuration 婵炲濮伴崕鐗堢箾?Compose 闁荤姴娲╅褑銇愰崶顒€绀嗛柣妤€鐗滈崝鈧紒缁㈠弾閸犳艾鈻?locale
        if (locales.isEmpty) {
            // 闁荤姾娅ｉ崰鏍р枔閵忋垹瀵查柤濮愬€楅崺鐘绘煥濞戞瑧顣茬紒鏂款煼濮婁粙濡堕崼婵囶唶闁诲氦顫夐惌顔剧不?locale
            val config = resources.configuration
            config.setLocale(Locale.getDefault())
            @Suppress("DEPRECATION")
            resources.updateConfiguration(config, resources.displayMetrics)
        } else {
            val tag = locales.get(0)?.toLanguageTag() ?: return
            val locale = Locale.forLanguageTag(tag)
            Locale.setDefault(locale)
            val config = resources.configuration
            config.setLocale(locale)
            @Suppress("DEPRECATION")
            resources.updateConfiguration(config, resources.displayMetrics)
        }
    }

    private suspend fun checkCrashAndPrompt() {
        val crashCount = CrashHandler.getAndResetCrashCount(this)
        if (crashCount < 1) return

        // 缂備焦绋戦ˇ顖滄閻斿吋鍤婃い蹇撳琚熸繛鎴炴尭閿曪絿妲愰崜浣虹＜闁规儳顕禍顖炴煥濞戞澧涙繛鎾瑰煐瀵?8s闂佹寧绋戦¨鈧紒杈ㄧ箞楠炲骞囬鈧～鐘绘煕閹烘挾鎳佺紒妤€顦锝夊捶椤撶姴鐐?        val uploadOk = kotlinx.coroutines.withTimeoutOrNull(8_000L) {
        val uploadOk = kotlinx.coroutines.withTimeoutOrNull(8_000L) {
            CrashLogUploader.uploadResult.await()
        } ?: false

        if (uploadOk) {
            CrashHandler.clearCrashLogs(this)
            return
        }

        val logs = CrashHandler.getCrashLogs(this)
        runOnUiThread {
            AlertDialog.Builder(this)
                .setTitle(getString(R.string.crash_dialog_title))
                .setMessage(getString(R.string.crash_dialog_message, crashCount))
                .setPositiveButton(getString(R.string.crash_dialog_send)) { _: DialogInterface, _: Int ->
                    sendCrashEmail(logs)
                    CrashHandler.clearCrashLogs(this)
                }
                .setNegativeButton(getString(R.string.crash_dialog_cancel)) { dialog: DialogInterface, _: Int ->
                    dialog.dismiss()
                    CrashHandler.clearCrashLogs(this)
                }
                .setCancelable(false)
                .show()
        }
    }

    private fun sendCrashEmail(logs: String) {
        val subject = getString(R.string.crash_email_subject)
        val body = if (logs.isNotEmpty()) {
            getString(R.string.crash_email_body) + logs
        } else {
            getString(R.string.crash_email_no_log)
        }

        val intent = Intent(Intent.ACTION_SENDTO).apply {
            data = Uri.parse("mailto:1577865546@qq.com")
            putExtra(Intent.EXTRA_SUBJECT, subject)
            putExtra(Intent.EXTRA_TEXT, body)
        }

        try {
            startActivity(intent)
        } catch (e: Exception) {
            val clipboard = getSystemService(CLIPBOARD_SERVICE) as android.content.ClipboardManager
            clipboard.setPrimaryClip(
                android.content.ClipData.newPlainText("Crash Log", body)
            )
            showToast(getString(R.string.crash_toast_copied), Toast.LENGTH_LONG)
        }
    }


}
