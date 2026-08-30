package com.tracktosearch.data.local

import android.content.Context
import com.tracktosearch.data.remote.tmdb.TmdbImageUrls
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 开屏海报的地址出口：开屏下载、日签卡片、沉浸取色都从这里拿同一个地址。
 *
 * 台词库里写着一条 posterPath，但真正该显示的是 TMDB 当前那一版，而且海报分语言，
 * 两者可能不是同一张。地址只要有一处算得不一样就有两笔代价：多下一张图，
 * 以及 PosterColorCache 按地址做 key，主色白算一遍——点开日签卡片时详情页的沉浸背景于是慢一拍。
 *
 * 解析结果只放进程内存，不落盘也不走注入：日签卡片的映射是纯函数（见 DailyStamp.toCard），
 * 拿不到任何依赖，只能从这里同步取。缓存的是「台词 + 语言 → TMDB 路径」这种不会变的对应，
 * 语义与 PosterColorCache 的内存层一致。查不到就退回台词库自带的那条路径，
 * 所以进程刚起来、或者翻的是很久以前的日签时，一样有地址可用，只是那时命不中已算好的主色。
 */
object SplashPosterUrls {
    private val resolvedPaths = ConcurrentHashMap<String, String>()

    /** 记下 TMDB 在这个语言下给的路径；空路径不记，免得把兜底也污染掉 */
    internal fun remember(quoteId: String, lang: String, posterPath: String) {
        if (posterPath.isBlank()) return
        resolvedPaths[cacheKey(quoteId, lang)] = posterPath
    }

    /**
     * 这条台词在这个语言下该显示哪张海报的完整地址。
     *
     * 尺寸固定 w342，与 TmdbRepository 富化详情用的是同一档：详情页首屏的海报地址由那边给，
     * 两边一模一样，点开日签卡片才既复用图片缓存、又命中开屏之后就算好的主色。
     */
    fun url(quote: SplashQuote, lang: String): String =
        TmdbImageUrls.build(path(quote, lang), TmdbImageUrls.W342)

    /**
     * 同一张海报的最小一档地址，w92。
     *
     * 日签日历里未来那天的卡片把它解到十几个像素再放大成一团色块，取更大的档只是白下载。
     * 与 [url] 差一个尺寸段，等于另一条图片缓存记录——那两处本来也不该共用：
     * 正常卡片要的是清楚，这里要的就是糊。
     */
    fun tinyUrl(quote: SplashQuote, lang: String): String =
        TmdbImageUrls.build(path(quote, lang), TmdbImageUrls.W92)

    /** 解析过的走 TMDB 那一版，没解析过的退回台词库自带的 */
    private fun path(quote: SplashQuote, lang: String): String =
        resolvedPaths[cacheKey(quote.id, lang)] ?: quote.posterPath

    private fun cacheKey(quoteId: String, lang: String): String = "$quoteId|$lang"
}

/**
 * 开屏海报的专用磁盘存储。
 *
 * 不复用 Coil 的图片缓存，原因有两个：
 * 1. 那是 888MB 的 LRU，浏览几百张海报就可能把开屏用的那张挤掉；
 * 2. 它在 cacheDir 下，系统清理缓存或存储紧张时会被抹掉。
 * 开屏要求「永远不出现占位图」，所以放 filesDir 下自己管，一种语言的整池 365 条约 15MB。
 *
 * 内置的 6 条海报随 APK 走 assets，不占这里的空间，也不需要下载，更不分语言——
 * 它们是所有兜底路径的落点，任何时候都算就绪。
 */
@Singleton
class SplashPosterStore @Inject constructor(
    @ApplicationContext private val context: Context,
    private val okHttpClient: OkHttpClient,
    private val languageStorage: LanguageStorage,
) {
    private val dir: File by lazy {
        File(context.filesDir, DIR_NAME).apply { if (!exists()) mkdirs() }
    }

    /** 已下载海报的落盘位置 */
    private fun file(quote: SplashQuote): File = File(dir, "${cacheName(quote)}$IMAGE_SUFFIX")

    /**
     * 缓存文件名：`台词 id__语言__路径指纹`。
     *
     * 带语言是因为 TMDB 的海报分语言，同一条台词在中文界面和英文界面该拿到不同的那张；
     * 只按 id 命名两者会互相覆盖，切一次语言就把另一档的图冲掉，而且冲掉的那张还会被
     * [isReady] 当成就绪继续用，开屏于是显示上一种语言的海报。
     *
     * 换过命名规则之后旧文件一律不再命中，等 [pruneOrphans] 清掉、后台补齐重下一遍。
     * 这是一次性的：海报是几十 KB 一张的可再生数据，为此保留一套旧名兼容逻辑不值得。
     */
    private fun cacheName(quote: SplashQuote): String =
        "${quote.id}$NAME_SEPARATOR${lang()}$NAME_SEPARATOR${slug(quote.posterPath)}"

    /**
     * 路径指纹：取 TMDB 文件名去掉扩展名那一段（`/kqjL17yufvn9.jpg` → `kqjL17yufvn9`）。
     *
     * 用它而不是哈希，是因为排查缓存问题时得能一眼看出磁盘上这张对应哪条路径。
     * TMDB 的文件名本来就只有 base64url 那套字符，落到任何文件系统都安全；
     * 仍过一遍白名单并截断，是防台词库以后写进带斜杠、空格或引号的路径把文件名撑坏。
     *
     * 指纹进文件名的意义是换图能自动失效：台词库改了某条的 posterPath（换片、换版本海报），
     * 文件名跟着变，旧图不会被 [isReady] 当成新图接着用。台词 id 按约定只增不改，
     * 所以指纹是这里唯一会变的那一段。
     */
    private fun slug(posterPath: String): String =
        posterPath.substringAfterLast('/')
            .substringBeforeLast('.')
            .filter { it.isLetterOrDigit() || it == '-' || it == '_' }
            .take(MAX_SLUG_LENGTH)
            .ifEmpty { FALLBACK_SLUG }

    /**
     * 文件名与海报地址共用的语言档位。
     *
     * 「跟随系统」这一档得看系统当前语言，其余按用户选的；落不到台词库支持的四种一律走英文，
     * 与台词和片名同一个口径（见 [SplashQuote.resolveLang]），否则会出现台词是中文、
     * 海报却按英文缓存这种错配。
     */
    private fun lang(): String {
        val setting = languageStorage.language.value
        val tag = if (setting == LanguageStorage.LANGUAGE_SYSTEM) {
            Locale.getDefault().language
        } else {
            setting
        }
        return SplashQuote.resolveLang(tag)
    }

    /** assets 内置海报的相对路径 */
    private fun assetPath(id: String): String = "$ASSET_DIR/$id$IMAGE_SUFFIX"

    /**
     * 记下 TMDB 给的这一版海报路径，并返回它的完整地址。
     *
     * 语言只有这里知道（[lang]），所以由存储层负责把解析结果登记进 [SplashPosterUrls]，
     * 调用方只管把查到的路径递过来。
     */
    fun rememberPosterUrl(quote: SplashQuote, posterPath: String): String {
        val lang = lang()
        SplashPosterUrls.remember(quote.id, lang, posterPath)
        return SplashPosterUrls.url(quote, lang)
    }

    /**
     * 海报是否已就绪：内置的直接算就绪，其余看文件是否存在且非空。
     *
     * 长度为 0 的文件当作没下载——下载中途被杀会留下空文件，
     * 当成就绪会让开屏拿到一张解不出来的图，正是要避免的占位图场景。
     */
    fun isReady(quote: SplashQuote): Boolean =
        quote.bundled || file(quote).let { it.exists() && it.length() > 0 }

    /**
     * 取可直接解码的海报字节来源。内置走 assets，其余走 filesDir。
     * 返回 null 表示还没就绪，调用方不应该退化成占位图，而应换一条已就绪的台词。
     */
    suspend fun readBytes(quote: SplashQuote): ByteArray? = withContext(Dispatchers.IO) {
        try {
            if (quote.bundled) {
                context.assets.open(assetPath(quote.id)).use { it.readBytes() }
            } else {
                val f = file(quote)
                if (f.exists() && f.length() > 0) f.readBytes() else null
            }
        } catch (e: Exception) {
            null
        }
    }

    /**
     * Coil 能直接吃的海报来源：内置的给 assets URI，其余给 [File]。
     *
     * 给日签日历用。那一屏最多 31 张缩略图，走 [readBytes] 就是 31 次全量读盘 + 手动解码；
     * 交给 Coil 才能按控件尺寸降采样并复用它的内存缓存。
     * 返回 null 表示还没就绪，调用方按「没有这张图」处理，不要画占位框。
     */
    fun posterModel(quote: SplashQuote): Any? = if (quote.bundled) {
        "file:///android_asset/${assetPath(quote.id)}"
    } else {
        file(quote).takeIf { it.exists() && it.length() > 0 }
    }

    /**
     * 下载单条海报，已就绪则跳过。返回是否处于就绪状态。
     *
     * [posterPath] 由调用方查 TMDB 得到（查不到时是台词库自带的那条），下载前先登记进
     * [SplashPosterUrls]：日签卡片给详情页的地址、沉浸取色的缓存 key 都得和这里下的是同一张图。
     * 登记放在就绪判断之前，海报早就下好的条目（最常见的情况）也才拿得到 TMDB 那一版地址。
     *
     * 先写临时文件再改名：直接写目标文件的话，下载被中断就留下一个半张图的文件，
     * 而 [isReady] 只看长度非零，会把它当成完整海报。
     */
    suspend fun download(quote: SplashQuote, posterPath: String): Boolean = withContext(Dispatchers.IO) {
        val url = rememberPosterUrl(quote, posterPath)
        if (isReady(quote)) return@withContext true
        val target = file(quote)
        val temp = File(dir, "${cacheName(quote)}$TEMP_SUFFIX")
        try {
            val request = Request.Builder().url(url).build()
            okHttpClient.newCall(request).execute().use { response ->
                if (!response.isSuccessful) return@withContext false
                temp.outputStream().use { out -> response.body.byteStream().copyTo(out) }
            }
            if (temp.length() <= 0) {
                temp.delete()
                return@withContext false
            }
            temp.renameTo(target)
        } catch (e: Exception) {
            temp.delete()
            return@withContext false
        }
        isReady(quote)
    }

    /**
     * 清掉不该继续占空间的海报：台词已下线的，以及别的语言留下的。
     *
     * 语言那一档也清，是因为文件名带了语言：切过一次语言，旧语言那一池就再也不会被读到，
     * 留着只是白占十几 MB。切回去要重下，但那是不计费网络下的后台补齐；
     * 而且 [isReady] 对旧语言的文件本来就是 false，删不删都不影响开屏有没有画面。
     */
    suspend fun pruneOrphans(validIds: Set<String>) = withContext(Dispatchers.IO) {
        val lang = lang()
        try {
            dir.listFiles()?.forEach { f ->
                val parts = f.name
                    .removeSuffix(IMAGE_SUFFIX)
                    .removeSuffix(TEMP_SUFFIX)
                    .split(NAME_SEPARATOR)
                val id = parts.firstOrNull().orEmpty()
                // 旧命名（只有 id）解不出语言，一律当作过期文件删掉
                if (id !in validIds || parts.getOrNull(1) != lang) f.delete()
            }
        } catch (e: Exception) {
            // 清理失败只是多占几十 KB，不值得让调用方处理
        }
    }

    companion object {
        private const val DIR_NAME = "splash_posters"
        private const val ASSET_DIR = "splash_posters"
        private const val IMAGE_SUFFIX = ".jpg"
        private const val TEMP_SUFFIX = ".part"

        /** 文件名三段之间的分隔符：双下划线，台词 id 和 TMDB 文件名都不会出现它 */
        private const val NAME_SEPARATOR = "__"

        /** 指纹截断长度：TMDB 文件名一般 27 位，留点余量即可，防的是异常长的路径 */
        private const val MAX_SLUG_LENGTH = 40

        /** 路径里一个可用字符都没剩下时的指纹，保证文件名不会退化成 `id__zh__` */
        private const val FALLBACK_SLUG = "poster"
    }
}
