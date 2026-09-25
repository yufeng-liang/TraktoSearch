package com.tracktosearch.ui.screen.swiftie.eras

import androidx.annotation.FontRes
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.tracktosearch.R

/**
 * 12 个时代**卡片上**的元素。绘制实现在 `SwiftieEraMotifs.kt`。
 *
 * 这一层只画得下中小尺寸的道具，占卡片右侧约 32% 一列 —— 大件（打字机、舞台、
 * 天际线、王座）在 [SwiftieEraBackdrop] 那一层放大画到整个页面背景。
 */
enum class SwiftieEraMotif {
    /** 1 · Taylor Swift：木门廊栏杆一角 + 斜靠的民谣吉他 */
    PORCH_GUITAR,
    /** 2 · Fearless：城堡阳台一角 + 几缕金色流苏 */
    CASTLE_BALCONY,
    /** 3 · Speak Now：紫色薄纱底纹 + 一束系着缎带的紫色花束 */
    SPEAK_NOW_BOUQUET,
    /** 4 · Red：垂下的红围巾 + 一杯枫糖拿铁（杯套上刻着一片枫叶） */
    RED_SCARF,
    /** 5 · 1989：宝丽来白框（照面是真实照片） */
    POLAROID,
    /** 6 · reputation：盘绕的黑鳞蛇 + 蛇戒 */
    COILED_SNAKE,
    /**
     * 7 · Lover：一把上了弦的弓 + 一只蝴蝶。
     *
     * 原来这里是 Lover House 小屋。**背景那一层已经有一栋大屋**（`PASTEL_RAINBOW_HOUSE`：
     * 彩虹 + 小屋），终局的雪景球里还有第三栋 —— 同一件道具同屏画两遍，正是用户对 Red
     * 那条围巾提过的问题（「不和卡片的围巾重复」）。屋子留在背景，卡片先换成了 Lover
     * 第 8 首的纸环，再换成这把弓：这一张真正要发生的事是「箭射中背景彩虹上那颗心」
     * （见 `SwiftieLoverArcher`），而弓是那件事的起点，得占着道具位才画得开。
     */
    LOVER_ARCHER,
    /** 8 · folklore：开衫挂在木椅背上 + 松枝 */
    CARDIGAN_CHAIR,
    /** 9 · evermore：三股辫 + 橙棕格纹布角 */
    BRAID_PLAID,
    /** 10 · Midnights：打火机（火苗跳动）+ 几颗四芒星 */
    LIGHTER_STARS,
    /** 11 · TTPD：卡片右下角常驻一支羽毛笔 */
    LETTER_QUILL,
    /** 12 · Showgirl：更衣室化妆镜台（环绕灯泡）+ 口红与粉扑 */
    VANITY_MIRROR
}

/**
 * 12 个时代的**页面背景**主体。绘制实现在 `SwiftieEraBackdrop.kt`（L1 层）。
 *
 * 这一层是全屏的，面积约是卡片的 8 倍，所以大件道具都在这里 —— 卡片那 32% 一列
 * 塞不下打字机和舞台。
 */
enum class SwiftieEraBackdrop {
    /** 1 · 夜色门廊全景 + 大片青绿水彩晕染 + 一棵树剪影 */
    FIREFLY_PORCH,
    /** 2 · 旋转金色光晕 + 城堡尖顶剪影 + 甩动长发弧线 */
    GOLDEN_CASTLE,
    /** 3 · 三层紫色薄纱正弦波 + 两侧垂落的帷幕 + 一束追光 */
    VEIL_SPOTLIGHT,
    /** 4 · 低对比红色平针织纹 + 秋景 */
    KNIT_AUTUMN,
    /** 5 · 纽约天际线剪影 + 海岸线海浪 + 数张散落的宝丽来 */
    SKYLINE_POLAROIDS,
    /** 6 · 满屏报纸半调网点 + 金色王座剪影 + 大幅蛇形曲线 */
    HALFTONE_THRONE,
    /** 7 · pastel 云海 + 一道彩虹弧 + 远处 Lover House + 霓虹心形招牌 */
    PASTEL_RAINBOW_HOUSE,
    /** 8 · 松林剪影（松针）+ 灰雾横带 + 苔藓覆盖的白色大钢琴 + 窗透暖火光 */
    PINE_MOSS_PIANO,
    /** 9 · 枝条分形 + 一条贯穿画面的金线 + 挂在枝上的灯笼 */
    BRANCH_LANTERNS,
    /** 10 · 午夜星空 + 一座带星星的大钟 + 薰衣草色雾 */
    MIDNIGHT_CLOCK,
    /** 11 · 打字机（键盘 + 压纸卷筒 + 卷纸）+ 散落手稿页 + 台灯光锥 */
    TYPEWRITER_DESK,
    /** 12 · 剧院舞台（红丝绒帷幕 + 桁架灯）+ 聚光灯锥 + 中央舞女剪影 */
    THEATRE_STAGE
}

/**
 * 前景飘落物。绘制实现在 `SwiftieEraParticles.kt`（L2 层，**卡片之下**）。
 *
 * **只有 9 种，三张故意没有** —— Speak Now 与 reputation 的背景本体（三层紫纱正弦波、
 * 满屏半调网点 + 大幅蛇形）自己就在动，再叠一层碎屑是同语义重复；Red 的秋叶则整批
 * 搬到了卡片**之上**那一层（见 `SwiftieRedLeafFall`），留在 L2 会被半透明白卡片盖住，
 * 而「落叶飘过曲目表」正是这一张要演的东西。
 *
 * 六组运动模式刻意不共用一个 mover，否则 12 张看下来就是同一个屏保：
 * 上浮（[FIREFLY] / [HEART_BUTTERFLY] 的心）、斜落带三轴翻转（[GOLD_FLAKE] /
 * [DRY_LEAF] / [PAPER_SCRAP]）、打旋慢落（[FEATHER]）、
 * 横向滑翔（[SEAGULL] / [HEART_BUTTERFLY] 的蝴蝶）、极慢下沉（[PURPLE_GLITTER]）、
 * 竖直缓落（[PINE_NEEDLE]）。
 *
 * **不做无形状的粒子** —— 光斑、光尘、碎屑一律砍掉，那才是廉价感的来源。
 */
enum class SwiftieEraParticle {
    /** 1 · 萤火虫：上浮 + 明暗呼吸 */
    FIREFLY,
    /** 2 · 金箔：极小 + 高频翻转 + 镜面高光 */
    GOLD_FLAKE,
    /** 5 · 海鸥：远处横向滑翔，翼展随透视变化 */
    SEAGULL,
    /** 7 · 上浮的亮粉心 + 横穿扑翼的蝴蝶 */
    HEART_BUTTERFLY,
    /** 8 · 松针：竖直缓落 */
    PINE_NEEDLE,
    /** 9 · 枯叶：中等 + 卷边 + 深棕 */
    DRY_LEAF,
    /** 10 · 紫色闪粉：极慢下沉 */
    PURPLE_GLITTER,
    /** 11 · 纸片：大 + 薄 + 纸面残留字迹 */
    PAPER_SCRAP,
    /** 12 · 单根羽毛：打旋慢落，锯齿摆动（羽枝 / 羽轴 / 边缘绒毛都画出来） */
    FEATHER
}

/**
 * 一个时代。
 *
 * @param name 专辑名，**永不翻译**，严格按官方大小写
 * @param releaseDate ISO `yyyy-MM-dd`，**不做本地化**
 * @param mainColor 时代主色，用于色带、卡片底色、曲目序号
 * @param textColor 卡片文字色。默认同 [mainColor]；米白的 TTPD 必须覆写
 * @param fontResId 专辑名专用子集化字体。**只够渲染专辑名**，不要拿去画曲目
 * @param tracks 展示用曲目，按官方顺序；含该专辑最多曲目版本的独有曲目，
 *   同版独占曲目以浅色 `(TV)` 标记，不含客串信息
 */
data class SwiftieEra(
    val name: String,
    val releaseDate: String,
    val mainColor: Color,
    val textColor: Color,
    @FontRes val fontResId: Int,
    val motif: SwiftieEraMotif,
    val tracks: List<String>,
    /**
     * 专辑名**合成加粗**的描边宽度，单位是 em（字号的比例，与字号一起缩放）。
     *
     * 0f = 不加粗，也就是原样。12 套字库里多数只有 Regular 一个字重
     * （Libre Caslon Display 就没有粗体可换），而 High contrast 的标题字面在大字号下
     * 读着偏细 —— 需要更重的那几张用描边垫一层（见 `SwiftieEraCard` 的标题那一支）。
     *
     * 这个值**逐张填**，不设全局默认值：改这个数会改到卡片的标题排版，而其余 11 张
     * 的观感是逐张定过的。
     */
    val titleStrokeEm: Float = 0f,
    /**
     * 专辑名字号的覆写值。null = 按名字长度走 `SwiftieEraCard.titleFontSizeFor` 的通用档位。
     *
     * 只有 reputation 填：它是 10 个字母的**全小写**词，而通用档位按**字符数**分档
     * （≤14 走 27dp）。小写字母的 x-height 比大写矮一截，同样 27dp 排出来就是比同档的
     * "Speak Now"、"Midnights" 小一号。需求方要「reputation 字号大一点」，这里按
     * **视觉大小**而不是字符数补上那 2dp。
     *
     * 与 [titleStrokeEm] 同一条规矩：逐张填、不动通用档位 —— 那三档的其余成员
     * 是逐张定过的，整体抬一档会顺带改到它们。
     */
    val titleSizeOverride: Dp? = null,
    /**
     * 专辑名填真闪粉（[com.tracktosearch.ui.screen.swiftie.rememberShowgirlGlitterBrush]）。
     *
     * 只有 Showgirl 用：官方封面那十二个字母本来就是橙红闪粉贴出来的，这是全场唯一一处
     * 「材质本身就是封面的一部分」的标题。填 true 时标题**上层走贴板、下层走
     * [titleStrokeEm] 的实色描边**，所以字缘仍有一圈实色撑着，读不读得清不靠闪粉赌运气。
     */
    val titleGlitter: Boolean = false,
    /**
     * 发行日期用哪套字库。null = 系统默认（多数时代）。
     *
     * TTPD 那一张填打字机字体：卡片的曲目列与前摇都是这台机器打的，日期是印在
     * 纸头上的一行，同一支机器打出来的东西不该有两种字面。
     * 那套子集是按曲名与数字做的，`2024-04-19` 用得上的字符（0-9 与连字符）本来就在里面。
     */
    @FontRes val dateFontResId: Int? = null,
    /**
     * 曲目列拆两栏时左栏占几行；0 = 不拆，整列单栏（其余 11 张都是）。
     *
     * 只有 Speak Now 填。这一张的背景主体是教堂内景：尖拱窗收在 0.40h、第一排长椅收在
     * 0.483h，而 22 首单栏把卡片顶边压到它们上面去了，整个教堂下半截全被盖住。
     * 卡片高度 = 标题与日期那一截 + **较长那一栏**的行数 × 行高，所以要让它矮到
     * 0.483h 以下，只能减少较长那一栏的行数 —— 拆栏是唯一不买账本的那条路
     * （压行高会挤字，压标题预算省不出 100dp）。
     *
     * 代价由右栏承担：它只有半张卡宽，所以拆点右边的几行必须是短名字。具体填几行
     * 是**真机量出来的**，不是算出来的 —— 卡片实高取决于标题那一截真正占多少 dp，
     * 而 `CARD_CHROME_HEIGHT` 是预留预算、不等于渲染高度。改这个数要重抓截图核对
     * 长椅下缘，别只看数字。
     */
    val leftColumnRows: Int = 0,
    /**
     * 花束在**右栏顶部**占掉几行（0 = 不占）。
     *
     * 只管排布，不管花束多大：它决定右栏那 7 行从第几行开始，也就是两栏底边齐不齐。
     * Speak Now 填 8 正好让两栏都是 15 行（8 + 7 = 15 = 左栏），于是 15 与 22 齐底，
     * 卡片也不必为花束长高（见 [columnRowCount]）。填 9 会把右栏整栏压低一行。
     *
     * 花束的**尺寸**另有来源：它的绘制领地横向是右栏那一栏，纵向从专辑名那一行的上缘
     * 一直铺到「Row 上缘 + 这几行」。标题与日期只吃卡片左半边，右半边那条空档借给了
     * 花束 —— 所以想让花束更大不必动这一格，两件事各自成立。
     * （原来是 `propScale = 0.62f` 一个缩放系数同时管排布和大小，于是要么花束小要么
     * 两栏不齐，两头抢同一格。）
     *
     * 改这个数要重抓截图核对两栏底边。
     */
    val propRowBand: Int = 0
) {
    /**
     * 卡片高度按**较长那一栏**折算，不是按曲目总数。
     *
     * 没拆栏时就是曲目总数，与拆栏前逐像素一致。拆栏的那一张还要把道具占的那几行
     * 算进它那一栏（[propRowBand]）—— 花束坐在右栏顶上，它和右栏的字是同栏的上下两截，
     * 不是各占一栏。
     */
    val columnRowCount: Int
        get() = if (leftColumnRows <= 0) {
            tracks.size
        } else {
            maxOf(leftColumnRows, tracks.size - leftColumnRows + propRowBand)
        }
}

/**
 * 一个时代的**舞台参数** —— 页面背景怎么铺、飘什么、系统栏与轴用深墨还是浅墨。
 *
 * @param backdrop 背景 L1 层的大主体
 * @param backdropColors 背景 L0 层的三档渐变色，**顶 → 中 → 底**。
 *   `drawWithCache` 缓存成 Brush，不随相位变 —— 全屏面积是卡片的 8 倍，
 *   这一层若每帧重建就白扔掉整个分层的意义
 * @param particle 背景 L2 层的飘落物。`null` = 这一段刻意不放粒子
 * @param darkStatusBarIcons 状态栏图标用**深色**（即 [backdropColors] 首档是浅底）。
 *   **手填，不算相对亮度** —— 算出来的值会在换张的 500ms 交叉淡变里来回跨过阈值，
 *   图标就一路闪
 * @param darkBottomInk 屏幕下缘的前景用**深色**（即 [backdropColors] 末档是浅底）。
 *   一个布尔量同时驱动三处：导航栏图标、时间轴的轴线 / 刻度 / 播放头旋钮、
 *   以及 TS1-12 标签 —— 它们全落在同一条底色上，判断只能有一个
 */
data class SwiftieEraStage(
    val backdrop: SwiftieEraBackdrop,
    val backdropColors: List<Color>,
    val particle: SwiftieEraParticle?,
    val darkStatusBarIcons: Boolean,
    val darkBottomInk: Boolean
)

object SwiftieErasData {

    /** 归宿那一张：序列末尾播放头会倒滑回这里。 */
    const val LOVER_INDEX: Int = 6

    /** 第四张 Red。它的枫叶剧本（`SwiftieRedLeafFall`）与落叶层的挂载门控都认这个号。 */
    const val RED_INDEX: Int = 3

    /** 加曲那张 Showgirl。续章（The Encore）挂在它上面。 */
    const val SHOWGIRL_INDEX: Int = 11

    /**
     * Showgirl 曲目列里**第一首加曲**的下标（12 起）。
     *
     * 这一条是「原版 / 加曲」的分界：曲目列靠它决定第 13 首起要逐行落墨，
     * 日期靠它决定何时翻新，触感谱靠它认「第一首加曲落墨」那一记。
     * 写成常量而不是各处写 12，是因为加曲再有增减时只该改这一处。
     */
    const val ENCORE_FIRST_TRACK: Int = 12

    /** 加曲那张的发行日（The Encore）。原版是 `2025-10-03`，见 [SHOWGIRL]。 */
    const val ENCORE_RELEASE_DATE: String = "2026-09-25"

    private val TAYLOR_SWIFT = SwiftieEra(
        name = "Taylor Swift",
        releaseDate = "2006-10-24",
        mainColor = Color(0xFF58B09C),
        textColor = Color(0xFF58B09C),
        fontResId = R.font.era_taylor_swift,
        motif = SwiftieEraMotif.PORCH_GUITAR,
        // Great Vibes 是细笔画的手写体，34dp 上压在米白卡片上读着偏轻
        // （与 Lover 的 Parisienne 同一个问题）。需求方要「再粗一些」，
        // 取 0.03em —— 与 TTPD / Showgirl 同一档，是这套合成加粗里最重的一档。
        // 手写体的游丝比 Parisienne 疏，0.03 不会糊在一起（Lover 那 0.02 是给更密的连笔留的余量）
        titleStrokeEm = 0.03f,
        tracks = listOf(
            "Tim McGraw",
            "Picture to Burn",
            "Teardrops on My Guitar",
            "A Place in This World",
            "Cold as You",
            "The Outside",
            "Tied Together with a Smile",
            "Stay Beautiful",
            "Should've Said No",
            "Mary's Song (Oh My My My)",
            "Our Song"
        )
    )

    private val FEARLESS = SwiftieEra(
        name = "Fearless",
        releaseDate = "2008-11-11",
        mainColor = Color(0xFFD4AF37),
        textColor = Color(0xFFD4AF37),
        fontResId = R.font.era_fearless,
        motif = SwiftieEraMotif.CASTLE_BALCONY,
        tracks = listOf(
            "Fearless",
            "Fifteen",
            "Love Story",
            "Hey Stephen",
            "White Horse",
            "You Belong with Me",
            "Breathe",
            "Tell Me Why",
            "You're Not Sorry",
            "The Way I Loved You",
            "Forever & Always",
            "The Best Day",
            "Change",
            // ---- Taylor's Version 独有曲目（13 首）----
            "Jump Then Fall (TV)",
            "Untouchable (TV)",
            "Forever & Always (Piano Version) (TV)",
            "Come In with the Rain (TV)",
            "Superstar (TV)",
            "The Other Side of the Door (TV)",
            "Today Was a Fairytale (TV)",
            "You All Over Me (TV)",
            "Mr. Perfectly Fine (TV)",
            "We Were Happy (TV)",
            "That's When (TV)",
            "Don't You (TV)",
            "Bye Bye Baby (TV)"
        )
    )

    private val SPEAK_NOW = SwiftieEra(
        name = "Speak Now",
        releaseDate = "2010-10-25",
        mainColor = Color(0xFF7B4BA8),
        textColor = Color(0xFF7B4BA8),
        fontResId = R.font.era_speak_now,
        motif = SwiftieEraMotif.SPEAK_NOW_BOUQUET,
        tracks = listOf(
            "Mine",
            "Sparks Fly",
            "Back to December",
            "Speak Now",
            "Dear John",
            "Mean",
            "The Story of Us",
            "Never Grow Up",
            "Enchanted",
            "Better than Revenge",
            "Innocent",
            "Haunted",
            "Last Kiss",
            "Long Live",
            // ---- Taylor's Version 独有曲目（8 首）----
            "Ours (TV)",
            "Superman (TV)",
            "Electric Touch (TV)",
            "When Emma Falls in Love (TV)",
            "I Can See You (TV)",
            "Castles Crumbling (TV)",
            "Foolish One (TV)",
            "Timeless (TV)"
        ),
        // 拆点定在 15 而不是更好讲的「14 = 原专 14 首 / TV 8 首」那条界线上：
        // 左栏多一行，卡片就矮一行 —— 拆栏的全部目的是让这张卡矮到露出背景教堂的第一排长椅。
        // 代价是 "Ours (TV)" 落在左栏末行，TV 那一块跨了两栏。这个数是真机量出来的，
        // 动它要重抓图核对长椅下缘
        leftColumnRows = 15,
        // 花束坐在右栏顶上（与那 7 行上下对调），占 8 行 —— 右栏共 15 行，与左栏齐平，
        // 15 与 22 的底边因此对齐，卡片也不必为花束长高。花束比原来小约一成，
        // 这是需求方在「两栏底边对齐」与「花束保持原尺寸」之间选前者的代价（见 [propRowBand]）
        propRowBand = 8
    )

    private val RED = SwiftieEra(
        name = "Red",
        releaseDate = "2012-10-22",
        mainColor = Color(0xFFEB3440),
        textColor = Color(0xFFEB3440),
        fontResId = R.font.era_red,
        motif = SwiftieEraMotif.RED_SCARF,
        tracks = listOf(
            "State of Grace",
            "Red",
            "Treacherous",
            // 官方带句点，别顺手删掉
            "I Knew You Were Trouble.",
            "All Too Well",
            "22",
            "I Almost Do",
            "We Are Never Ever Getting Back Together",
            "Stay Stay Stay",
            "The Last Time",
            "Holy Ground",
            "Sad Beautiful Tragic",
            "The Lucky One",
            "Everything Has Changed",
            "Starlight",
            "Begin Again",
            // ---- Taylor's Version 独有曲目（14 首）----
            "The Moment I Knew (TV)",
            "Come Back... Be Here (TV)",
            "Girl at Home (TV)",
            "State of Grace (Acoustic Version) (TV)",
            "Ronan (TV)",
            "Better Man (TV)",
            "Nothing New (TV)",
            "Babe (TV)",
            "Message in a Bottle (TV)",
            "I Bet You Think About Me (TV)",
            "Forever Winter (TV)",
            "Run (TV)",
            "The Very First Night (TV)",
            "All Too Well (10 Minute Version) (TV)"
        )
    )

    private val NINETEEN_EIGHTY_NINE = SwiftieEra(
        name = "1989",
        releaseDate = "2014-10-27",
        mainColor = Color(0xFF92CFEA),
        textColor = Color(0xFF92CFEA),
        fontResId = R.font.era_1989,
        motif = SwiftieEraMotif.POLAROID,
        tracks = listOf(
            "Welcome to New York",
            "Blank Space",
            "Style",
            "Out of the Woods",
            "All You Had to Do Was Stay",
            "Shake It Off",
            "I Wish You Would",
            "Bad Blood",
            "Wildest Dreams",
            "How You Get the Girl",
            "This Love",
            "I Know Places",
            "Clean",
            // 豪华版三首（需求方定案 2026-09）：账本 [SwiftieTimeline.ERA_TRACK_COUNTS]
            // 同步为 16，卡片多出的停留由定格弹性段吸收
            "Wonderland",
            "You Are In Love",
            "New Romantics",
            // ---- Taylor's Version 独有曲目（5 首）----
            "\"Slut!\" (TV)",
            "Say Don't Go (TV)",
            "Now That We Don't Talk (TV)",
            "Suburban Legends (TV)",
            "Is It Over Now? (TV)"
        )
    )

    private val REPUTATION = SwiftieEra(
        name = "reputation",
        releaseDate = "2017-11-10",
        mainColor = Color(0xFF111111),
        textColor = Color(0xFF111111),
        fontResId = R.font.era_reputation,
        motif = SwiftieEraMotif.COILED_SNAKE,
        // 全小写词，x-height 比同档的大写标题矮，27dp 看着偏小。需求方要「大一点」，
        // 覆写到 29dp（见 [SwiftieEra.titleSizeOverride]）
        titleSizeOverride = 29.dp,
        tracks = listOf(
            "...Ready for It?",
            "End Game",
            "I Did Something Bad",
            "Don't Blame Me",
            "Delicate",
            "Look What You Made Me Do",
            "So It Goes...",
            "Gorgeous",
            "Getaway Car",
            "King of My Heart",
            "Dancing with Our Hands Tied",
            "Dress",
            "This Is Why We Can't Have Nice Things",
            "Call It What You Want",
            "New Year's Day"
        )
    )

    private val LOVER = SwiftieEra(
        name = "Lover",
        releaseDate = "2019-08-23",
        mainColor = Color(0xFFF7A8C4),
        textColor = Color(0xFFF7A8C4),
        fontResId = R.font.era_lover,
        motif = SwiftieEraMotif.LOVER_ARCHER,
        // Parisienne 只有 400 一档字重，没有粗体可换；34dp 上手写体的细笔画压在半透明白卡上
        // 读着偏轻。合成加粗半档（0.02em ≈ 0.68dp，每侧只胀 0.34dp）—— 再大一档就会把
        // 手写体那些相连的游丝糊成一坨，这一档是「更实」而不是「更粗」
        titleStrokeEm = 0.02f,
        tracks = listOf(
            "I Forgot That You Existed",
            "Cruel Summer",
            "Lover",
            "The Man",
            "The Archer",
            "I Think He Knows",
            "Miss Americana & The Heartbreak Prince",
            "Paper Rings",
            "Cornelia Street",
            "Death by a Thousand Cuts",
            "London Boy",
            "Soon You'll Get Better",
            "False God",
            "You Need to Calm Down",
            "Afterglow",
            // 官方全大写带感叹号
            "ME!",
            "It's Nice to Have a Friend",
            "Daylight"
        )
    )

    private val FOLKLORE = SwiftieEra(
        name = "folklore",
        releaseDate = "2020-07-24",
        mainColor = Color(0xFF8C8C8C),
        textColor = Color(0xFF8C8C8C),
        fontResId = R.font.era_imfell,
        motif = SwiftieEraMotif.CARDIGAN_CHAIR,
        // 全小写是官方写法。the lakes 是豪华版，不列
        tracks = listOf(
            "the 1",
            "cardigan",
            "the last great american dynasty",
            "exile",
            "my tears ricochet",
            "mirrorball",
            "seven",
            "august",
            "this is me trying",
            "illicit affairs",
            "invisible string",
            "mad woman",
            "epiphany",
            "betty",
            "peace",
            "hoax",
            "the lakes"
        )
    )

    private val EVERMORE = SwiftieEra(
        name = "evermore",
        releaseDate = "2020-12-11",
        mainColor = Color(0xFF8B5A2B),
        textColor = Color(0xFF8B5A2B),
        fontResId = R.font.era_imfell,
        motif = SwiftieEraMotif.BRAID_PLAID,
        // right where you left me 与 it's time to go 是豪华版，不列
        tracks = listOf(
            "willow",
            "champagne problems",
            "gold rush",
            "'tis the damn season",
            "tolerate it",
            "no body, no crime",
            "happiness",
            "dorothea",
            "coney island",
            "ivy",
            "cowboy like me",
            "long story short",
            "marjorie",
            "closure",
            "evermore",
            "right where you left me",
            "it's time to go"
        )
    )

    private val MIDNIGHTS = SwiftieEra(
        name = "Midnights",
        releaseDate = "2022-10-21",
        mainColor = Color(0xFF1B2A5B),
        textColor = Color(0xFF1B2A5B),
        fontResId = R.font.era_midnights,
        motif = SwiftieEraMotif.LIGHTER_STARS,
        // The Til Dawn Edition 22 首：标准版 13 首 + 3am Edition 7 首 + You're Losing Me
        // 与 Hits Different；不列 More Lana / Ice Spice 两条重复版本。
        tracks = listOf(
            "Lavender Haze",
            "Maroon",
            "Anti-Hero",
            "Snow on the Beach",
            "You're on Your Own, Kid",
            "Midnight Rain",
            "Question...?",
            "Vigilante Shit",
            "Bejeweled",
            "Labyrinth",
            "Karma",
            "Sweet Nothing",
            "Mastermind",
            "The Great War",
            "Bigger Than the Whole Sky",
            "Paris",
            "High Infidelity",
            "Glitch",
            "Would've, Could've, Should've",
            "Dear Reader",
            "Hits Different",
            "You're Losing Me"
        )
    )

    private val TORTURED_POETS = SwiftieEra(
        name = "The Tortured Poets Department",
        releaseDate = "2024-04-19",
        mainColor = Color(0xFFF5F1EA),
        // 米白主色当文字色读不出来，换深灰（Spec §6.3）
        textColor = Color(0xFF4A453E),
        fontResId = R.font.era_ttpd,
        motif = SwiftieEraMotif.LETTER_QUILL,
        // 长专辑名在 18dp 上用的是 Caslon Display（高对比、细笔画），字号又小，
        // 读着比下面那一列打字机曲名还轻。合成加粗一档（0.03em ≈ 0.54dp）
        titleStrokeEm = 0.03f,
        // 日期也跟着这台机器走同一支字面
        dateFontResId = R.font.era_typewriter,
        // The Anthology 版 31 首（需求方指定）：标准版 16 首 + Anthology 加曲 15 首
        tracks = listOf(
            "Fortnight",
            "The Tortured Poets Department",
            "My Boy Only Breaks His Favorite Toys",
            "Down Bad",
            "So Long, London",
            "But Daddy I Love Him",
            "Fresh Out the Slammer",
            "Florida!!!",
            "Guilty as Sin?",
            "Who's Afraid of Little Old Me?",
            "I Can Fix Him (No Really I Can)",
            // 官方全小写
            "loml",
            "I Can Do It With a Broken Heart",
            "The Smallest Man Who Ever Lived",
            "The Alchemy",
            "Clara Bow",
            // ---- The Anthology 加曲 17–31 ----
            "The Black Dog",
            // 官方无空格全小写
            "imgonnagetyouback",
            "The Albatross",
            "Chloe or Sam or Sophia or Marcus",
            "How Did It End?",
            "So High School",
            "I Hate It Here",
            // 官方大小写刻意拼出 KIM，别顺手改成 Thank You Aimee
            "thanK you aIMee",
            "I Look in People's Windows",
            "The Prophecy",
            "Cassandra",
            "Peter",
            "The Bolter",
            "Robin",
            "The Manuscript"
        )
    )

    private val SHOWGIRL = SwiftieEra(
        name = "The Life of a Showgirl",
        releaseDate = "2025-10-03",
        mainColor = Color(0xFFE8620F),
        textColor = Color(0xFFE8620F),
        fontResId = R.font.era_showgirl,
        motif = SwiftieEraMotif.VANITY_MIRROR,
        // 官方封面那行字是又重又厚的闪粉贴字，`era_showgirl` 只有 Regular 一个字重，
        // 所以描边垫一层加粗（与 TTPD 同一档），再填真闪粉贴板
        titleStrokeEm = 0.03f,
        titleGlitter = true,
        tracks = listOf(
            "The Fate of Ophelia",
            "Elizabeth Taylor",
            "Opalite",
            "Father Figure",
            "Eldest Daughter",
            "Ruin the Friendship",
            "Actually Romantic",
            // 美元符号是官方写法
            "Wi\$h Li\$t",
            "Wood",
            "Cancelled!",
            "Honey",
            "The Life of a Showgirl",
            // ---- The Encore 加曲 13–16（2026-09-25 发行）----
            // 这四首与上面 12 首**不是同一批**：原版发行时明确说过没有加曲，它们是
            // 一年后为庆祝首周成绩在瑞典写出来的。卡片要在原版展示完之后才把它们落墨，
            // 所以这条边界在别处也要认人（见 `SwiftieErasData.ENCORE_FIRST_TRACK`）。
            "Patient Zero",
            "Cleveland!",
            "Pink Clouding",
            "Babylon"
        )
    )

    /** 播放顺序 = 发行顺序。索引与 `SwiftieTimeline.ERA_TRACK_COUNTS` 一一对应。 */
    val ALL: List<SwiftieEra> = listOf(
        TAYLOR_SWIFT, FEARLESS, SPEAK_NOW, RED,
        NINETEEN_EIGHTY_NINE, REPUTATION, LOVER, FOLKLORE,
        EVERMORE, MIDNIGHTS, TORTURED_POETS, SHOWGIRL
    )

    /**
     * 每个时代的**页面级**舞台参数。索引与 [ALL] 一一对应（`SwiftieErasDataTest` 守着）。
     *
     * 刻意**不塞进 [SwiftieEra]**：那个 data class 是「一张专辑的事实」（曲目、日期、
     * 官方大小写），而这里全是「这一段怎么演」的表演参数。混在一起之后，改一次配色
     * 就要碰 12 处曲目列表，diff 里根本看不出动了什么。
     */
    val STAGE: List<SwiftieEraStage> = listOf(
        // 1 · Taylor Swift — 青绿。萤火虫上浮
        SwiftieEraStage(
            backdrop = SwiftieEraBackdrop.FIREFLY_PORCH,
            backdropColors = listOf(Color(0xFFDDF2EA), Color(0xFF58B09C), Color(0xFF2E6B5E)),
            particle = SwiftieEraParticle.FIREFLY,
            darkStatusBarIcons = true,
            darkBottomInk = false
        ),
        // 2 · Fearless — 金。金箔斜落翻转
        SwiftieEraStage(
            backdrop = SwiftieEraBackdrop.GOLDEN_CASTLE,
            backdropColors = listOf(Color(0xFFFFF6DC), Color(0xFFD4AF37), Color(0xFF8A6A16)),
            particle = SwiftieEraParticle.GOLD_FLAKE,
            darkStatusBarIcons = true,
            darkBottomInk = false
        ),
        // 3 · Speak Now — 紫。三层纱波自己在动，**故意无粒子**
        //
        // 2026-09-25 饱和度 +10%（需求方）：三档整体走一遍 HSL 提饱和（口径同
        // `SwiftieEraContrast.toHsl`，L 与 H 不动）。底色是 L0 天空渐变**和** L1 大主体
        // （石柱、檐口、尖拱窗、三排长椅）的共同色源，所以改这三档就等于整页一起提
        SwiftieEraStage(
            backdrop = SwiftieEraBackdrop.VEIL_SPOTLIGHT,
            backdropColors = listOf(Color(0xFFF0E3FB), Color(0xFF7B46AD), Color(0xFF3E1E5C)),
            particle = null,
            darkStatusBarIcons = true,
            darkBottomInk = false
        ),
        // 4 · Red — 红。秋叶不在这一层飘，见下面那条 2026-09-24 的说明
        //
        // 2026-09-19 提亮：中档曾是 #C81E30，需求方「主题红有点暗，要 Red 重录专辑那口
        // 亮红」，提到 #EB3440（三档候选里最亮的猩红）；末档跟着同调 #6E0F1A → #981A28。
        // 轴墨靠 `readableOnDark` 提亮，守着 `SwiftieEraContrastTest.axisInkMeetsAaOnEveryBackdropBottom`。
        //
        // 2026-09-24 飘落物改到卡片之上：这一张的叶要「长在枝上 → 松手 → 落过曲目表」，
        // L2 在卡片背后，那条弧线演不出来。剧本与图层见 `SwiftieRedLeafFall`。
        SwiftieEraStage(
            backdrop = SwiftieEraBackdrop.KNIT_AUTUMN,
            backdropColors = listOf(Color(0xFFFBE3E3), Color(0xFFEB3440), Color(0xFF981A28)),
            particle = null,
            darkStatusBarIcons = true,
            darkBottomInk = false
        ),
        // 5 · 1989 — 天蓝。远处海鸥滑翔
        //
        // 2026-09-19 整组去灰：中档曾是 #A8CDE0 —— 饱和度只有 47% 的「黄昏雾蓝」，
        // 需求方「主题蓝有点暗灰」，提到 #92CFEA（与拍立得照片的水色同向）；末档跟着同调，
        // #4A7590 → #437A98。更早的历史：末档本来是 #5B8CA8，轴线、播放头与 TS1-12 标签
        // 都压在屏幕下缘这一档上，而 #5B8CA8 的相对亮度是 0.238 —— 那个底色上**纯白**
        // 也只有 3.64:1，12 张里唯一一张连理论上限都到不了 AA 4.5:1 的，当时压到 #4A7590
        // （白字 4.95:1）。#437A98 白字 4.69:1 依然过线，
        // `SwiftieEraContrastTest.axisInkMeetsAaOnEveryBackdropBottom` 守着这条。
        SwiftieEraStage(
            backdrop = SwiftieEraBackdrop.SKYLINE_POLAROIDS,
            backdropColors = listOf(Color(0xFFF0F7FB), Color(0xFF92CFEA), Color(0xFF437A98)),
            particle = SwiftieEraParticle.SEAGULL,
            darkStatusBarIcons = true,
            darkBottomInk = false
        ),
        // 6 · reputation — 黑。全场唯一顶部也要浅色图标的两张之一；网点 + 蛇已经够满，无粒子
        SwiftieEraStage(
            backdrop = SwiftieEraBackdrop.HALFTONE_THRONE,
            backdropColors = listOf(Color(0xFF3A3A3A), Color(0xFF111111), Color(0xFF000000)),
            particle = null,
            darkStatusBarIcons = false,
            darkBottomInk = false
        ),
        // 7 · Lover — 粉蓝。上浮的心 + 横穿的蝴蝶。底部是天蓝，是唯一底部仍用深墨的两张之一
        //
        // 2026-09-25 饱和度 +10%（需求方）：同 Speak Now，三档走一遍 HSL 提饱和。
        // 底色同样是 L0 天空与 L1 彩虹小屋的共同色源
        SwiftieEraStage(
            backdrop = SwiftieEraBackdrop.PASTEL_RAINBOW_HOUSE,
            backdropColors = listOf(Color(0xFFFEEEF5), Color(0xFFFBA4C3), Color(0xFF97C4EC)),
            particle = SwiftieEraParticle.HEART_BUTTERFLY,
            darkStatusBarIcons = true,
            darkBottomInk = true
        ),
        // 8 · folklore — 灰。松针竖直缓落
        SwiftieEraStage(
            backdrop = SwiftieEraBackdrop.PINE_MOSS_PIANO,
            backdropColors = listOf(Color(0xFFE8E6E2), Color(0xFF8C8C8C), Color(0xFF4A4844)),
            particle = SwiftieEraParticle.PINE_NEEDLE,
            darkStatusBarIcons = true,
            darkBottomInk = false
        ),
        // 9 · evermore — 棕。枯叶卷边翻落
        SwiftieEraStage(
            backdrop = SwiftieEraBackdrop.BRANCH_LANTERNS,
            backdropColors = listOf(Color(0xFFEFE6DA), Color(0xFF8B5A2B), Color(0xFF3E2A14)),
            particle = SwiftieEraParticle.DRY_LEAF,
            darkStatusBarIcons = true,
            darkBottomInk = false
        ),
        // 10 · Midnights — 深蓝。紫闪粉极慢下沉。顶底皆深，两处图标都要浅色
        //
        // 2026-09-25 饱和度 +10%（需求方）：同 Speak Now，三档走一遍 HSL 提饱和。
        // 这一张的 L1 主体（表盘、星野、斜雾）也吃这三档，只有那圈薰衣草刻度是
        // `SwiftiePalette.Lavender`（共享调色板，被海报/键盘/雪景球共用，不能动它）
        SwiftieEraStage(
            backdrop = SwiftieEraBackdrop.MIDNIGHT_CLOCK,
            backdropColors = listOf(Color(0xFF27386E), Color(0xFF18285E), Color(0xFF081032)),
            particle = SwiftieEraParticle.PURPLE_GLITTER,
            darkStatusBarIcons = false,
            darkBottomInk = false
        ),
        // 11 · TTPD — 米白。纸片翻转飘落。
        // 第三档 #B8B0A2 要吃掉下半屏：主色近白，不压深整屏会显得是空的
        SwiftieEraStage(
            backdrop = SwiftieEraBackdrop.TYPEWRITER_DESK,
            backdropColors = listOf(Color(0xFFFBF8F2), Color(0xFFF5F1EA), Color(0xFFB8B0A2)),
            particle = SwiftieEraParticle.PAPER_SCRAP,
            darkStatusBarIcons = true,
            darkBottomInk = true
        ),
        // 12 · Showgirl — 橙金。单根羽毛打旋慢落（羽毛扇降格成粒子，扇子画出来是扫帚）
        SwiftieEraStage(
            backdrop = SwiftieEraBackdrop.THEATRE_STAGE,
            backdropColors = listOf(Color(0xFFFFF0E0), Color(0xFFE8620F), Color(0xFF8A3300)),
            particle = SwiftieEraParticle.FEATHER,
            darkStatusBarIcons = true,
            darkBottomInk = false
        )
    )
}
