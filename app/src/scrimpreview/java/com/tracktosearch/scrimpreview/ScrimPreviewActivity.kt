package com.tracktosearch.scrimpreview

import android.content.Intent
import android.os.Bundle
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.tracktosearch.ui.component.SectionHeader
import com.tracktosearch.ui.theme.AmbientMeshBackground
import com.tracktosearch.ui.theme.LocalMainColorScheme
import com.tracktosearch.ui.theme.MeshPreset
import com.tracktosearch.ui.theme.MeshWashout
import com.tracktosearch.ui.theme.MonetAccent
import com.tracktosearch.ui.theme.TraktoSearchTheme
import com.tracktosearch.ui.theme.ambientPaletteFor
import com.tracktosearch.ui.theme.isDarkScheme
import dagger.hilt.android.AndroidEntryPoint
import java.util.Locale

private const val TAG = "ScrimPreview"

/**
 * 背景光晕压制量的候选档对照宿主。
 *
 * 要解决的问题：深色模式下光晕读不出颜色。压暗有两道——色点向页面背景混合的 `mix`
 * 与光晕之上那层全屏蒙层的 `scrim`，都在 `AmbientMeshBackground.kt` 的 [MeshWashout] 里。
 * 两道都是把颜色朝同一个背景拉近，所以只有合计量有意义，而合计量在网页上调参页算不准：
 * BLOOM / 熔岩灯走的着色器还会**内部**再朝 colorBack 合成一次。于是只能在真机上铺候选。
 *
 * 为什么每格要叠内容：主页面里顶栏大标题静止时没有垫层、`SectionHeader` 无背景、
 * 海报标题 12sp 印在卡片之外，这些文字是直接压在光晕上的。裸背景格子里会挑出最艳的一档，
 * 那个值放回真页面就糊字，所以每格照真页面排一遍（尺寸与字重取自 DiscoverScreen /
 * DiscoverComponents 的实际值）。
 *
 * 三处已知的偏差，回填终值时要记住：
 * 1. 卡片这里是实底 `surfaceVariant`（= 拟态档）。玻璃档的卡片净 alpha 只有 0.016~0.024，
 *    近乎全透，所以玻璃用户看到的"卡片浮起感"比这里更弱，终值要再回真页面复核一次。
 * 2. 主题色系那三档共用一个 mix，屏上只画 BLOOM（内部多一道合成，是最坏情况）。
 *    极光档在同样的 mix 下会比屏幕上更艳一点。
 * 3. 星云/水墨/海滩的固定色与主题无关，但 `mix` 是朝 `colorScheme.background` 混，
 *    那层底色按种子色相染了彩度 4.0，所以严格说仍随主题微变。这里统一用雷诺阿粉代表。
 *
 * 每格冻在 speed=0（`motionActive` 恒 false），四格同相位、可比。运动观感不在这里判。
 */
@AndroidEntryPoint
class ScrimPreviewActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        val screenIndex = intent.intExtraOrNull(EXTRA_SCREEN)
            ?.coerceIn(0, SCREENS.lastIndex)
            ?: 0
        val screen = SCREENS[screenIndex]
        // 参数生效与否走一遍 logcat 就能确认，省得对着两张看不出差别的截图猜
        Log.i(
            TAG,
            "screen=$screenIndex accent=${screen.accent} preset=${screen.preset} " +
                "candidates=${screen.candidates.joinToString { "${it.washout.mix}/${it.washout.scrim}" }}"
        )

        setContent {
            // 深色档是唯一要判的档；浅色档这次只重测不改值，所以不进网格。
            // 主题走 app 自己的 TraktoSearchTheme，且像 MainScreen 那样再包一层染色 scheme：
            // 光晕与文字吃的都是 LocalMainColorScheme，用外层那套纯中性底截出来的不是用户那份。
            TraktoSearchTheme(themeMode = "dark", accentColor = screen.accent) {
                MaterialTheme(colorScheme = LocalMainColorScheme.current ?: MaterialTheme.colorScheme) {
                    Column(
                        modifier = Modifier
                            .fillMaxSize()
                            .windowInsetsPadding(WindowInsets.safeDrawing)
                    ) {
                        screen.candidates.forEach { candidate ->
                            WashoutCell(
                                screen = screen,
                                candidate = candidate,
                                modifier = Modifier
                                    .weight(1f)
                                    .fillMaxWidth()
                            )
                        }
                    }
                }
            }
        }
    }

    @Composable
    private fun WashoutCell(
        screen: PreviewScreen,
        candidate: Candidate,
        modifier: Modifier = Modifier,
    ) {
        val washout = candidate.washout
        val colorScheme = MaterialTheme.colorScheme
        val isDark = colorScheme.isDarkScheme
        val tamed = remember(screen.preset, colorScheme, isDark, washout) {
            ambientPaletteFor(screen.preset, colorScheme, isDark, washout)
        }
        // 未压制的输入色：mix=0 就是原色，用来当场否掉取色（尤其是吸出来的自定义粉）
        val raw = remember(screen.preset, colorScheme, isDark) {
            ambientPaletteFor(screen.preset, colorScheme, isDark, MeshWashout(0f, 0f))
        }

        Box(modifier = modifier) {
            AmbientMeshBackground(
                modifier = Modifier.fillMaxSize(),
                preset = screen.preset,
                enabled = true,
                motionActive = { false },
                washoutOverride = washout,
            )
            Column(modifier = Modifier.fillMaxSize().padding(horizontal = 12.dp, vertical = 4.dp)) {
                // 顶栏大标题：28sp ExtraBold onSurface，静止时没有任何垫层，裸压光晕
                Text(
                    text = screen.topBarTitle,
                    fontSize = 28.sp,
                    fontWeight = FontWeight.ExtraBold,
                    letterSpacing = (-0.5).sp,
                    color = colorScheme.onSurface,
                    maxLines = 1,
                )
                // 分区标题：18sp Bold onBackground，同样零背景
                SectionHeader(title = "正在热映", actionText = "查看全部", onActionClick = {})
                Row(verticalAlignment = Alignment.Top) {
                    // 海报卡：实底 surfaceVariant，判的是"卡片还浮不浮得起来"
                    Card(
                        colors = CardDefaults.cardColors(containerColor = colorScheme.surfaceVariant)
                    ) {
                        Box(
                            modifier = Modifier
                                .size(width = 56.dp, height = 80.dp)
                                .background(colorScheme.primary)
                        )
                    }
                    // 卡外标题与副标题：12sp onBackground Medium / 10sp onSurfaceVariant 0.7
                    Column(
                        modifier = Modifier.padding(start = 8.dp),
                        verticalArrangement = Arrangement.spacedBy(2.dp)
                    ) {
                        Text(
                            text = screen.cardTitle,
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Medium,
                            color = colorScheme.onBackground,
                            maxLines = 1,
                        )
                        Text(
                            text = screen.cardSubtitle,
                            fontSize = 10.sp,
                            color = colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
                            maxLines = 1,
                        )
                    }
                    Column(
                        modifier = Modifier.weight(1f).padding(start = 8.dp),
                        verticalArrangement = Arrangement.spacedBy(2.dp),
                        horizontalAlignment = Alignment.End,
                    ) {
                        Text(
                            text = "mix=${fmt(washout.mix)} scrim=${fmt(washout.scrim)}" +
                                candidate.tag.takeIf { it.isNotEmpty() }?.let { "  ← $it" }.orEmpty(),
                            fontSize = 10.sp,
                            fontFamily = FontFamily.Monospace,
                            color = colorScheme.onSurface,
                        )
                        SwatchRow(colors = tamed, label = "渲后")
                        SwatchRow(colors = raw, label = "输入")
                    }
                }
            }
        }
    }

    @Composable
    private fun SwatchRow(colors: List<Color>, label: String) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(
                text = label,
                fontSize = 9.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            colors.forEach { color ->
                Box(
                    modifier = Modifier
                        .size(11.dp)
                        .background(color)
                )
            }
        }
    }

    private companion object {
        const val EXTRA_SCREEN = "screen"

        /** 档位数字固定两位小数，截图上要能一眼读回 mix 值并回填进 am 命令。 */
        fun fmt(value: Float): String = String.format(Locale.ROOT, "%.2f", value)
    }
}

/** 一个候选档：一道 mix + 一层蒙层，加一个写在格子上的标签。 */
private data class Candidate(val washout: MeshWashout, val tag: String = "")

private fun w(mix: Float, scrim: Float, tag: String = "") = Candidate(MeshWashout(mix, scrim), tag)

/** 一屏 = 一个待定的深色档自由量。第一格必须是改动前的样子，否则没有对照。 */
private data class PreviewScreen(
    val topBarTitle: String,
    val cardTitle: String,
    val cardSubtitle: String,
    val preset: MeshPreset,
    val accent: MonetAccent,
    val candidates: List<Candidate>,
)

// mix 那一轴已经拍完（见 AmbientMeshBackground.meshWashout 的注释与实测彩度），
// 这一轮铺的是剩下的蒙层：新 mix 定住，scrim 从现值收到 0。收到 0 那一格同时是
// 省掉每帧一次全屏混合的那一刀，所以它不只是观感候选。
private val THEME_CANDIDATES = listOf(
    w(0.62f, 0.18f, "改动前"),
    w(0.30f, 0.18f, "新值"),
    w(0.30f, 0.09f),
    w(0.30f, 0.00f, "撤蒙层"),
)

private val SCREENS = listOf(
    // 主题色系三档（弥散绽放 / 极光 / 熔岩灯）共用同一个 mix。三个 accent 各铺一屏，
    // 因为这套色全取自当前 scheme 的 primary/tertiary/secondary/两个 container，
    // 换主题等于换输入。屏上只画弥散绽放（着色器内部多一道朝 colorBack 的合成，最坏情况）。
    PreviewScreen(
        topBarTitle = "发现",
        cardTitle = "安妮·霍尔",
        cardSubtitle = "1977 · 爱情喜剧",
        preset = MeshPreset.BLOOM,
        accent = MonetAccent.RENOIR,
        candidates = THEME_CANDIDATES,
    ),
    PreviewScreen(
        topBarTitle = "我的",
        cardTitle = "坠落的审判",
        cardSubtitle = "2023 · 剧情 / 家庭",
        preset = MeshPreset.BLOOM,
        accent = MonetAccent.STARRY_NIGHT,
        candidates = THEME_CANDIDATES,
    ),
    PreviewScreen(
        topBarTitle = "搜索",
        cardTitle = "奥丽芙·基特里奇",
        cardSubtitle = "2015 · 剧情（迷你剧）",
        preset = MeshPreset.BLOOM,
        accent = MonetAccent.VINTAGE_TICKET,
        candidates = THEME_CANDIDATES,
    ),
    // 星云：固定五档浅色。这档单独调过渲后彩度排序（粉要排第一），mix 已从 0.45 压到
    // 0.20，排序与 ΔE 间距只会更宽，但结论要回头重算一次才算数。
    PreviewScreen(
        topBarTitle = "发现",
        cardTitle = "苦月亮",
        cardSubtitle = "1992 · 剧情 / 爱情",
        preset = MeshPreset.NEBULA,
        accent = MonetAccent.RENOIR,
        candidates = listOf(
            w(0.45f, 0.22f, "改动前"),
            w(0.20f, 0.22f, "新值"),
            w(0.20f, 0.11f),
            w(0.20f, 0.00f, "撤蒙层"),
        ),
    ),
    // 水墨：mix 这一轴没动（纯黑白，彩度量纲失效，只有亮度在动），所以四格全在试蒙层。
    // 它的白点是六档里唯一真会糊浅字的一处，撤蒙层那格要特别看字还分不分得开。
    PreviewScreen(
        topBarTitle = "我的",
        cardTitle = "花样年华",
        cardSubtitle = "2000 · 剧情 / 爱情",
        preset = MeshPreset.INK,
        accent = MonetAccent.RENOIR,
        candidates = listOf(
            w(0.50f, 0.30f, "现值"),
            w(0.50f, 0.20f),
            w(0.50f, 0.10f),
            w(0.50f, 0.00f, "撤蒙层"),
        ),
    ),
    // 海滩：mix 0.40 → 0.18 是这一轮新挑的，所以第一格留改动前。
    PreviewScreen(
        topBarTitle = "搜索",
        cardTitle = "海街日记",
        cardSubtitle = "2015 · 剧情 / 家庭",
        preset = MeshPreset.BEACH,
        accent = MonetAccent.RENOIR,
        candidates = listOf(
            w(0.40f, 0.22f, "改动前"),
            w(0.18f, 0.22f, "新值"),
            w(0.18f, 0.11f),
            w(0.18f, 0.00f, "撤蒙层"),
        ),
    ),
)

/**
 * `--ei`（int）与 `--el`（long）两种写法都收。
 *
 * 手敲 am 命令时把 `--ei` 打成 `--el` 是常事，而 [Intent.getIntExtra] 碰上 Long 值
 * 只会静默返回默认值 —— 表现是「参数好像没生效」，比直接报错难查。
 */
private fun Intent.intExtraOrNull(key: String): Int? {
    if (!hasExtra(key)) return null
    val asInt = getIntExtra(key, Int.MIN_VALUE)
    if (asInt != Int.MIN_VALUE) return asInt
    val asLong = getLongExtra(key, Long.MIN_VALUE)
    return if (asLong == Long.MIN_VALUE) null else asLong.toInt()
}
