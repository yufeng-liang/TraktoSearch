package com.tracktosearch.loverpreview

import android.content.Intent
import android.os.Bundle
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameMillis
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.tracktosearch.ui.screen.swiftie.SwiftieEggScreen
import com.tracktosearch.ui.screen.swiftie.SwiftieTimeline
import com.tracktosearch.ui.theme.TraktoSearchTheme
import dagger.hilt.android.AndroidEntryPoint

private const val TAG = "SwiftieEggPreview"

/**
 * 霉粉彩蛋的截图迭代宿主：装完点图标直接进彩蛋，参数从 Intent extras 进来。
 *
 * 本副本属于 **loverpreview** 变体（Lover 时代专项优化专用，包名
 * `com.tracktosearch.loverpreview`），与 folklore 那套 eggpreview 并存于同一台设备：
 * 两边在各自的工作树里并行改舞台代码，装机身份分开才不会互相覆盖安装。
 *
 * 正式包里那条路走不通 —— 彩蛋压在一道算术题（`X + 87 = 100`）之后，且只能从头顺序播，
 * Lover 那张卡片（索引 6）要等 49 秒才到。改一处视觉细节要几分钟才看得到结果。
 *
 * 预览宿主挂 `@AndroidEntryPoint`：彩蛋页会通过 `hiltViewModel()` 获取昵称 ViewModel，
 * 如果宿主不接入 Hilt，页面在首次组合时会直接崩溃，连视觉预览都进不去。
 * 预览页本身不注入业务对象，只复用正式 Application 的 Hilt 容器。
 *
 * 主题用的是 app 自己的 [TraktoSearchTheme] 而不是裸 `MaterialTheme`：它一个参数都不需要注入
 * （全带默认值），而彩蛋的底色、mesh 星云的取色都来自 `colorScheme`，
 * 换成 M3 基线配色截出来的颜色就不是用户看到的那份，截图迭代也就失去了意义。
 */
@AndroidEntryPoint
class SwiftieEggPreviewActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // 彩蛋是全屏页，自己用 windowInsetsPadding(safeDrawing) 给系统栏让位，
        // 宿主只负责把窗口铺到边
        enableEdgeToEdge()

        val startMs = resolveStartMs(intent)
        // 定格的默认值跟着「有没有给落点」走：
        // 给了 ms/era 就是来截图的，默认停住；什么都没给（比如从桌面点进来）
        // 要的是「打开就是完整彩蛋」，那就得让它正常播。
        val paused = intent.getBooleanExtra(EXTRA_PAUSED, startMs != null)
        // 默认 true：正式包里「跳过」要等 3s 淡入才可点，预览包没有等它的道理
        val replay = intent.getBooleanExtra(EXTRA_REPLAY, true)
        // 默认关：这行字会一起进截图
        val hud = intent.getBooleanExtra(EXTRA_HUD, false)
        // 参数是否真的生效过一遍 logcat 就能确认，省得对着一张看不出差别的截图猜
        Log.i(TAG, "start: ms=$startMs paused=$paused replay=$replay hud=$hud")

        setContent {
            TraktoSearchTheme {
                Box(modifier = Modifier.fillMaxSize()) {
                    SwiftieEggScreen(
                        visible = true,
                        // 预览包里关掉就是退出，没有可以退回去的宿主页面
                        onDismiss = { finish() },
                        // 空实现：落主题是 commitSwiftieUnlock 的三写入，
                        // 一个调试包不该去改用户的强调色与网格预设
                        onCommitUnlock = { },
                        replay = replay,
                        previewStartMs = startMs,
                        previewPaused = paused
                    )
                    if (hud) {
                        PreviewHud(
                            startMs = startMs ?: 0L,
                            paused = paused,
                            modifier = Modifier.align(Alignment.BottomStart)
                        )
                    }
                }
            }
        }
    }

    /**
     * 解析落点：`ms` 优先，其次 `era`，都没给返回 null（= 从 0 开始正常播完整彩蛋）。
     *
     * era 的毫秒**现算**，绝不抄一份常量表进来：卡片时长是按曲目数派生的
     * （[SwiftieTimeline.cardDurationMs]），需求方改一次 TTPD 用哪个版本，
     * 它后面所有卡片的起点全都要挪。
     */
    private fun resolveStartMs(intent: Intent): Long? {
        intent.longExtraOrNull(EXTRA_MS)?.let { ms ->
            return ms.coerceIn(0L, SwiftieTimeline.TOTAL_MS)
        }
        val era = intent.longExtraOrNull(EXTRA_ERA)?.toInt() ?: return null
        val index = era.coerceIn(0, SwiftieTimeline.ERA_TRACK_COUNTS.lastIndex)
        // 段起点之后还有前摇（TTPD 的打字机独奏、TS2/TS3/TS5 让背景先演 600ms），
        // 落点要把它加回去，不然截出来是「空插槽」或「机器 + 半张纸」，
        // 和其余卡片不是同一个时刻
        val preroll = SwiftieTimeline.cardPrerollMs(index)
        return (SwiftieTimeline.eraStartMs(index) + preroll + ERA_SETTLE_MS)
            .coerceAtMost(SwiftieTimeline.TOTAL_MS)
    }

    private companion object {
        /** 直接拨到这一毫秒；与 [EXTRA_ERA] 同时给时以本项为准。 */
        const val EXTRA_MS = "ms"

        /** 拨到第 N 张卡片的起点，N 为 0..11（与 `SwiftieTimeline` 的索引同一套编号）。 */
        const val EXTRA_ERA = "era"

        const val EXTRA_PAUSED = "paused"
        const val EXTRA_REPLAY = "replay"
        const val EXTRA_HUD = "hud"

        /**
         * era 落点的偏移量。
         *
         * 卡片起点那一帧屏幕上什么都还没有：长出动画要 400ms，曲目再按 130ms/行 逐条点亮。
         * 正落在起点上截出来是一张空卡。
         *
         * 2600ms 而不是更小的值：曲目是逐行点亮的，800ms 时只有三四行亮着，
         * 截出来永远看不到「曲目列排满」的样子（第一轮 12 张截图全是「第 4 行开始淡掉」，
         * 差点当成 bug 去查）。最短的卡片（首专 11 首）也有 `5900 + 11×130 = 7330ms`，
         * 2600ms 落在每一张的中段。
         */
        const val ERA_SETTLE_MS = 2600L
    }
}

/**
 * 读一个整数 extra，`--el`（long）与 `--ei`（int）两种写法都收。
 *
 * 手敲 am 命令时把 `--el` 打成 `--ei` 是常事，而 [Intent.getLongExtra] 碰上 Int 值
 * 只会静默返回默认值 —— 表现是「参数好像没生效」，比直接报错难查得多。
 *
 * 返回 null 而不是 0：要能区分「没给这个参数」和「给了 0」，
 * 前者意味着从头正常播，后者是明确要求拨到 T0。
 */
private fun Intent.longExtraOrNull(key: String): Long? {
    if (!hasExtra(key)) return null
    val asLong = getLongExtra(key, Long.MIN_VALUE)
    if (asLong != Long.MIN_VALUE) return asLong
    val asInt = getIntExtra(key, Int.MIN_VALUE)
    return if (asInt == Int.MIN_VALUE) null else asInt.toLong()
}

/**
 * 角上一行调试文字：当前毫秒 + 专辑索引，用来跟分镜对号。
 *
 * 只在 `hud = true` 时挂载，默认路径上这段根本不组合，不会污染截图。
 *
 * 自己数帧而不是读彩蛋内部的时钟 —— 那个 `SwiftieSequenceClock` 是 `SwiftieEggContent`
 * 的私有 remember，外面拿不到。定格时（[paused] = true，也就是截图那条路）两边都不动，
 * 显示的就是准确值；放开跑时两份计数各自累加，会差到几十毫秒的量级。
 * HUD 只用来认段落、不用来做精确对位，这点偏差无所谓。
 *
 * 逐帧变化的量只写进本 composable 自己的 state：重组范围就锁在这行文字上，
 * 不会把彩蛋整棵树拖着每帧重组一遍。
 */
@Composable
private fun PreviewHud(startMs: Long, paused: Boolean, modifier: Modifier = Modifier) {
    var elapsedMs by remember { mutableLongStateOf(startMs) }
    LaunchedEffect(paused, startMs) {
        elapsedMs = startMs
        if (paused) return@LaunchedEffect
        var last = withFrameMillis { it }
        while (elapsedMs < SwiftieTimeline.TOTAL_MS) {
            withFrameMillis { now ->
                elapsedMs = (elapsedMs + (now - last)).coerceAtMost(SwiftieTimeline.TOTAL_MS)
                last = now
            }
        }
    }
    // era 用 0 起的索引，跟 `--ei era` 是同一套编号：HUD 上看到的数字可以直接回填进
    // am 命令再来一张。scripts/egg-shot.sh 的 eraN 是 1 起的，差 1，别混。
    val era = SwiftieTimeline.eraIndexAt(elapsedMs)?.toString() ?: "-"
    Text(
        text = "${elapsedMs}ms  era=$era",
        color = Color.White,
        fontSize = 10.sp,
        fontFamily = FontFamily.Monospace,
        modifier = modifier
            .windowInsetsPadding(WindowInsets.safeDrawing)
            .background(Color.Black.copy(alpha = 0.55f))
            .padding(horizontal = 4.dp, vertical = 2.dp)
    )
}
