package com.tracktosearch.ui.screen.swiftie.eras

import androidx.annotation.FontRes
import androidx.compose.ui.graphics.Color
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
    /** 3 · Speak Now：剧院帷幕一角（绒面 + 金流苏绳）+ 紫舞裙裙摆 */
    STAGE_CURTAIN,
    /** 4 · Red：垂下的红围巾 + 一顶 fedora */
    RED_SCARF,
    /** 5 · 1989：宝丽来白框 + 一只海鸥 */
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
    /** 11 · TTPD：米白麻纸信纸（写着 All’s fair in love）+ 羽毛笔 */
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
    /** 4 · 大幅红色针织横纹（毛线绞花与 V 字编织）+ 秋景 */
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
 * 前景飘落物。绘制实现在 `SwiftieEraParticles.kt`（L2 层）。
 *
 * **只有 10 种，Speak Now 与 reputation 故意没有** —— 那两张的背景本体
 * （三层紫纱正弦波、满屏半调网点 + 大幅蛇形）自己就在动，再叠一层碎屑是同语义重复。
 *
 * 六组运动模式刻意不共用一个 mover，否则 12 张看下来就是同一个屏保：
 * 上浮（[FIREFLY] / [HEART_BUTTERFLY] 的心）、斜落带三轴翻转（[GOLD_FLAKE] /
 * [AUTUMN_LEAF] / [DRY_LEAF] / [PAPER_SCRAP]）、打旋慢落（[FEATHER]）、
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
    /** 4 · 秋叶：大 + 低频翻转 + 叶脉可见 + 正反两面异色 */
    AUTUMN_LEAF,
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
 * @param tracks 标准版曲目，按官方顺序，不含客串信息
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
     * 发行日期用哪套字库。null = 系统默认（多数时代）。
     *
     * TTPD 那一张填打字机字体：卡片的曲目列与前摇都是这台机器打的，日期是印在
     * 纸头上的一行，同一支机器打出来的东西不该有两种字面。
     * 那套子集是按曲名与数字做的，`2024-04-19` 用得上的字符（0-9 与连字符）本来就在里面。
     */
    @FontRes val dateFontResId: Int? = null
)

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

    private val TAYLOR_SWIFT = SwiftieEra(
        name = "Taylor Swift",
        releaseDate = "2006-10-24",
        mainColor = Color(0xFF58B09C),
        textColor = Color(0xFF58B09C),
        fontResId = R.font.era_taylor_swift,
        motif = SwiftieEraMotif.PORCH_GUITAR,
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
            "Change"
        )
    )

    private val SPEAK_NOW = SwiftieEra(
        name = "Speak Now",
        releaseDate = "2010-10-25",
        mainColor = Color(0xFF7B4BA8),
        textColor = Color(0xFF7B4BA8),
        fontResId = R.font.era_speak_now,
        motif = SwiftieEraMotif.STAGE_CURTAIN,
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
            "Long Live"
        )
    )

    private val RED = SwiftieEra(
        name = "Red",
        releaseDate = "2012-10-22",
        mainColor = Color(0xFFC81E30),
        textColor = Color(0xFFC81E30),
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
            "Begin Again"
        )
    )

    private val NINETEEN_EIGHTY_NINE = SwiftieEra(
        name = "1989",
        releaseDate = "2014-10-27",
        mainColor = Color(0xFFA8CDE0),
        textColor = Color(0xFFA8CDE0),
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
            "Clean"
        )
    )

    private val REPUTATION = SwiftieEra(
        name = "reputation",
        releaseDate = "2017-11-10",
        mainColor = Color(0xFF111111),
        textColor = Color(0xFF111111),
        fontResId = R.font.era_reputation,
        motif = SwiftieEraMotif.COILED_SNAKE,
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
            "hoax"
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
            "evermore"
        )
    )

    private val MIDNIGHTS = SwiftieEra(
        name = "Midnights",
        releaseDate = "2022-10-21",
        mainColor = Color(0xFF1B2A5B),
        textColor = Color(0xFF1B2A5B),
        fontResId = R.font.era_midnights,
        motif = SwiftieEraMotif.LIGHTER_STARS,
        // 标准版 13 首。3am Edition 与 Til Dawn 的加曲不列
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
            "Mastermind"
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
            "The Life of a Showgirl"
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
        SwiftieEraStage(
            backdrop = SwiftieEraBackdrop.VEIL_SPOTLIGHT,
            backdropColors = listOf(Color(0xFFF0E4FA), Color(0xFF7B4BA8), Color(0xFF3E2159)),
            particle = null,
            darkStatusBarIcons = true,
            darkBottomInk = false
        ),
        // 4 · Red — 红。秋叶大幅翻转，正反异色
        SwiftieEraStage(
            backdrop = SwiftieEraBackdrop.KNIT_AUTUMN,
            backdropColors = listOf(Color(0xFFFBE3E3), Color(0xFFC81E30), Color(0xFF6E0F1A)),
            particle = SwiftieEraParticle.AUTUMN_LEAF,
            darkStatusBarIcons = true,
            darkBottomInk = false
        ),
        // 5 · 1989 — 淡蓝。远处海鸥滑翔
        //
        // 末档刻意比封面那层雾蓝更深（原来是 #5B8CA8）：轴线、播放头与 TS1-12 标签都压在
        // 屏幕下缘这一档上，而 #5B8CA8 的相对亮度是 0.238 —— 那个底色上**纯白**也只有
        // 3.64:1，12 张里唯一一张连理论上限都到不了 AA 4.5:1 的。压到 #4A7590 之后白字
        // 有 4.95:1，色相一度未动（仍是那层黄昏的雾蓝），换来的是这 6.4 秒里字读得出来。
        // `SwiftieEraContrastTest.axisInkMeetsAaOnEveryBackdropBottom` 守着这条。
        SwiftieEraStage(
            backdrop = SwiftieEraBackdrop.SKYLINE_POLAROIDS,
            backdropColors = listOf(Color(0xFFEAF4FA), Color(0xFFA8CDE0), Color(0xFF4A7590)),
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
        SwiftieEraStage(
            backdrop = SwiftieEraBackdrop.PASTEL_RAINBOW_HOUSE,
            backdropColors = listOf(Color(0xFFFDEFF5), Color(0xFFF7A8C4), Color(0xFF9BC4E8)),
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
        SwiftieEraStage(
            backdrop = SwiftieEraBackdrop.MIDNIGHT_CLOCK,
            backdropColors = listOf(Color(0xFF2A3A6B), Color(0xFF1B2A5B), Color(0xFF0A1130)),
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
