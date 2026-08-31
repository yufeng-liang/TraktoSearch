package com.tracktosearch.ui.screen.swiftie.eras

import androidx.annotation.FontRes
import androidx.compose.ui.graphics.Color
import com.tracktosearch.R

/** 12 个时代各自的视觉母题。绘制实现在 `SwiftieEraMotifs.kt`。 */
enum class SwiftieEraMotif {
    /** 1 · Taylor Swift：青绿水彩晕染 + 细碎星点 */
    WATERCOLOR_STARS,
    /** 2 · Fearless：金色旋转光晕，甩动长发弧线 */
    GOLDEN_SWIRL,
    /** 3 · Speak Now：紫色薄纱正弦波 */
    PURPLE_VEIL,
    /** 4 · Red：红色针织横纹 */
    KNIT_STRIPES,
    /** 5 · 1989：宝丽来白框 + 框内淡蓝天空 */
    POLAROID,
    /** 6 · reputation：报纸半调网点 + 蛇形曲线 */
    HALFTONE_SNAKE,
    /** 7 · Lover：粉蓝云 + 亮粉心 */
    PINK_CLOUD_HEART,
    /** 8 · folklore：灰雾 + 松林垂直剪影 */
    PINE_FOG,
    /** 9 · evermore：棕色枝条分形 + 辫子 */
    BRAID_BRANCH,
    /** 10 · Midnights：深蓝星芒 + 打火机火苗光 */
    STARBURST_FLAME,
    /** 11 · TTPD：米白纸纹 + 打字机游标 */
    TYPEWRITER_PAPER,
    /** 12 · The Life of a Showgirl：橙金亮粉 + 羽毛扇 + 聚光灯锥 */
    SPOTLIGHT_FEATHER
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
    val tracks: List<String>
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
        motif = SwiftieEraMotif.WATERCOLOR_STARS,
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
        motif = SwiftieEraMotif.GOLDEN_SWIRL,
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
        motif = SwiftieEraMotif.PURPLE_VEIL,
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
        motif = SwiftieEraMotif.KNIT_STRIPES,
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
        motif = SwiftieEraMotif.HALFTONE_SNAKE,
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
        motif = SwiftieEraMotif.PINK_CLOUD_HEART,
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
        motif = SwiftieEraMotif.PINE_FOG,
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
        motif = SwiftieEraMotif.BRAID_BRANCH,
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
        motif = SwiftieEraMotif.STARBURST_FLAME,
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
        motif = SwiftieEraMotif.TYPEWRITER_PAPER,
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
        motif = SwiftieEraMotif.SPOTLIGHT_FEATHER,
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
}
