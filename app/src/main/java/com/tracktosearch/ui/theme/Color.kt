package com.tracktosearch.ui.theme

import androidx.annotation.StringRes
import androidx.compose.material3.ColorScheme
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.luminance
import com.tracktosearch.R

// Primary - 深红色系 (Trakt 品牌)
// 只在拿不到壁纸取色的低版本兜底时当种子色用，见 TraktoSearchTheme。
val Red500 = Color(0xFFED1C24)
val Red700 = Color(0xFFC4161C)

// 网盘官方品牌色
val QuarkBlue = Color(0xFF1E88E5)       // 夸克 - 蓝绿色
val BaiduBlue = Color(0xFF06A7FF)       // 百度网盘 - 亮蓝色
val AliIndigo = Color(0xFF6366F1)        // 阿里云盘 - 靛蓝色
val XunleiBlue = Color(0xFF2386EA)       // 迅雷 - 标准蓝
val UcOrange = Color(0xFFFF6B00)         // UC网盘 - 橙色
val Blue115 = Color(0xFF2563EB)          // 115网盘 - 蓝色

/**
 * 莫奈/印象派主题色，按 Lab 色相升序排列 —— 设置页的色块网格直接按 entries 顺序铺，
 * 排成色环用户扫一眼就能定位，比随机顺序好找。
 * `monetColorScheme` 用 Material You 的 TonalPalette 推导。前景色不在这里存 ——
 * 主色上的字色（onPrimary）直接用 surface（与开关选中态 thumb 同 token），
 * secondary 的字色仍按亮度取黑白，见 `monetColorScheme`。
 * 压在种子色上该用黑字还是白字由亮度算，见 `monetColorScheme` 的 onPrimary。
 *
 * **改动种子色时注意**：[VINTAGE_TICKET] 的两个值同时被 `vintageTicketColorScheme`
 * 和桌面小组件读，是主题色的唯一真值来源，别在别处再写一遍字面量。
 *
 * 删项是安全的：`ThemeStorage.decodeAccentName` 对认不出的名字有兜底，
 * 历史名字的迁移映射也在那里。[RENOIR] 不能删 ——
 * `CloudThemeManager.commitSwiftieUnlock` 直接引用它。
 */
enum class MonetAccent(@StringRes val labelResId: Int, val light: Color, val dark: Color) {
    RENOIR(R.string.accent_renoir, Color(0xFFD4748A), Color(0xFFE8909A)),                     //   7°
    POPPY(R.string.accent_poppy, Color(0xFFD94040), Color(0xFFE86060)),                       //  31°
    VINTAGE_TICKET(R.string.accent_vintage_ticket, Color(0xFFB85030), Color(0xFFE5906A)),      //  44°
    SUNRISE(R.string.accent_sunrise, Color(0xFFE8915A), Color(0xFFF4A460)),                    //  57°
    HAYSTACK(R.string.accent_haystack, Color(0xFFC4A94D), Color(0xFFD4B96A)),                  //  91°
    GIVERNY_MOSS(R.string.accent_giverny_moss, Color(0xFF5F8440), Color(0xFF98BE70)),          // 128°
    JAPANESE_BRIDGE(R.string.accent_japanese_bridge, Color(0xFF5A8F6B), Color(0xFF7AB68A)),    // 152°
    VENICE_CANAL(R.string.accent_venice_canal, Color(0xFF1C8078), Color(0xFF4FB8AC)),          // 187°
    BOAT_BREAKFAST(R.string.accent_boat_breakfast, Color(0xFF3A8AA0), Color(0xFF5AA8C0)),      // 227°
    STARRY_NIGHT(R.string.accent_starry_night, Color(0xFF4A7FB5), Color(0xFF6B9FD4)),          // 268°
    CATHEDRAL(R.string.accent_cathedral, Color(0xFF6A7180), Color(0xFF9DA4B4)),                // 275°
    WATER_LILY(R.string.accent_water_lily, Color(0xFF7B68AE), Color(0xFF9B8EC4)),              // 304°
    BALLET(R.string.accent_ballet, Color(0xFFB06AA0), Color(0xFFC88AB8));                      // 334°
}

// UI 重设计新增颜色
val CinemaBackground = Color(0xFF0F0F1A)
val CinemaSurface = Color(0xFF1A1A2E)
val CinemaCard = Color(0xFF242442)

/**
 * 压在 [background] 上该用黑字还是白字 —— 取对比度更高的那一个。
 *
 * 阈值 [WcagBlackWhiteCrossover] 是黑白两者对比度相等的那一点：
 * 白字对比 = 1.05 / (L + 0.05)，黑字对比 = (L + 0.05) / 0.05，
 * 两式相等解出 L = √(1.05 × 0.05) − 0.05 ≈ 0.1791。
 *
 * 早先这里的阈值写的是 0.5，于是黄金/橙色系主题的按钮全是白字 ——
 * 干草堆金上白字只有 2.30:1，黑字有 9.13:1。
 */
internal fun onColorFor(background: Color): Color =
    if (background.luminance() > WcagBlackWhiteCrossover) Color.Black else Color.White

/** 见 [onColorFor]。黑白等对比点，不是随手取的 0.5。 */
internal const val WcagBlackWhiteCrossover = 0.1791f

/** WCAG AA 普通字号要求。10sp 的角标、12sp 的次要文字都按这个来，不吃大字号豁免。 */
internal const val WcagAaNormal = 4.5

/** WCAG 相对亮度对比度。两个颜色都必须是不透明的，半透明色先自己合成好再传进来。 */
internal fun contrastRatio(a: Color, b: Color): Double {
    val la = a.luminance().toDouble()
    val lb = b.luminance().toDouble()
    return (maxOf(la, lb) + 0.05) / (minOf(la, lb) + 0.05)
}

/**
 * 把 [base] 朝黑或白推，直到压在 [background] 上够到 [targetContrast]。已经够了就原样返回。
 *
 * 用来救那些「颜色本身是语义/品牌的一部分，但原色压在底上读不清」的场景：
 * 平台品牌色当文字（Metacritic 橙压在浅卡片上只有 1.72:1）、反馈类型色、状态色。
 * 这类色不能换成主题色（换了就丢识别性），只能在保住色相的前提下调明度。
 *
 * 推的方向取 [onColorFor]：它给出的就是对这个底能拿到最高对比的那一端，
 * 所以浅底往黑推、深底往白推，一定是收敛的方向。沿这条直线对比度单调，二分即可。
 * 连纯黑/纯白都够不到目标（底色本身在中间调）时返回那一端，是能做到的最好结果。
 *
 * 每帧都算的话建议在调用点 remember(base, background) 一下。
 */
internal fun readableOn(
    base: Color,
    background: Color,
    targetContrast: Double = WcagAaNormal
): Color {
    if (contrastRatio(base, background) >= targetContrast) return base
    val ink = onColorFor(background)
    var insufficient = 0f
    var sufficient = 1f
    repeat(READABLE_SEARCH_STEPS) {
        val mid = (insufficient + sufficient) / 2f
        if (contrastRatio(lerp(base, ink, mid), background) >= targetContrast) {
            sufficient = mid
        } else {
            insufficient = mid
        }
    }
    return lerp(base, ink, sufficient)
}

/** 10 步二分把区间缩到 1/1024，对 8 位色深来说已经到底了。 */
private const val READABLE_SEARCH_STEPS = 10

/**
 * 当前配色是深色档吗。判据是 background 的亮度，不是 `isSystemInDarkTheme()` ——
 * 本 App 的明暗由 ThemeStorage 控制，可以和系统设置不一致。
 *
 * 这个判断此前在 8 处内联重写过（统计色板、氛围背景、设置页色块、豆瓣按钮…），
 * 阈值和写法各不相同。组合函数里请用 `isAppDarkTheme()`，它在这个之上加了一层
 * remember 缓存 —— 列表滚动时它是每张卡片都要问一遍的高频调用。
 */
val ColorScheme.isDarkScheme: Boolean get() = background.luminance() < DarkSchemeLuminance

/**
 * 深浅档的分界亮度。
 *
 * 这里是 0.5 而不是 [WcagBlackWhiteCrossover]：两者解决的是不同问题 ——
 * 交叉点回答「压在这个色上该用黑字还是白字」，这个回答「这套配色整体是深还是浅」。
 * 所有主题的 background 要么在 0.85 以上要么在 0.02 以下，落点离 0.5 都很远。
 */
private const val DarkSchemeLuminance = 0.5f

// ====== 错误色（所有主题共用，不跟主题色走） ======
// 取值来自 Material 3 基线的 Error 色板（material3 1.4.0 的 ColorLightTokens /
// ColorDarkTokens 实际映射：Error40/100/90/10 与 Error80/20/30/90）。
//
// 之所以要显式写出来：生成器原先把 errorContainer 接到了 **主色** 的 TonalPalette 上，
// 于是「确认删除」这类容器会跟着主题变成棕色或蓝色，红色警示语义丢失。
// 错误色是语义色，不参与主题化。
internal val ErrorLight = Color(0xFFB3261E)
internal val OnErrorLight = Color(0xFFFFFFFF)
internal val ErrorContainerLight = Color(0xFFF9DEDC)
internal val OnErrorContainerLight = Color(0xFF410E0B)
internal val ErrorDark = Color(0xFFF2B8B5)
internal val OnErrorDark = Color(0xFF601410)
internal val ErrorContainerDark = Color(0xFF8C1D18)
internal val OnErrorContainerDark = Color(0xFFF9DEDC)

// ====== 复古票根主题专用色（牛皮纸 + 赭红墨 + 褪色青绿副印） ======
// 只有这一个主题整套换纸：其余主题共用中性底色，主题色只换强调色。
// 票根的主题身份本身就是「纸」，所以底色也跟着走暖，主色才不必独自扛全部气质。
//
// 阶梯按 Material 3 的 surface 层级排：浅色档层级越高越暗，深色档反之。
// 每一级都验过对比度，改任何一档前先确认相邻两级还分得开
// （卡片压页面、输入框压卡片这两处最敏感，见 ThemeTest 的阶梯单调性测试）。
internal val TicketPaperLightLowest = Color(0xFFFFFFFF)
internal val TicketPaperLightSurface = Color(0xFFFFFCF6)   // 纸白，Haze 填充色
internal val TicketPaperLightBackground = Color(0xFFF7F3EC) // 暖米，页面底
internal val TicketPaperLightLow = Color(0xFFF4EEE2)        // 底部弹层
internal val TicketPaperLight = Color(0xFFF1EADD)
internal val TicketPaperLightHigh = Color(0xFFEDE5D6)       // 滚动后的顶栏
internal val TicketPaperLightVariant = Color(0xFFEDE2D3)    // 对话框 / 卡片
// 填充输入框和未选中 chip 吃这一级，压在 Variant 的卡片上，两级差得够多才看得出边界：
// L* 83 对 L* 90 是 1.22，跟生成器的浅色档 tone 84 对齐。
internal val TicketPaperLightHighest = Color(0xFFDCCDB5)
internal val TicketPaperLightDim = Color(0xFFD1BFA1)
internal val TicketInkLight = Color(0xFF3E2A1E)             // 正文
internal val TicketInkLightMuted = Color(0xFF574536)        // 次要文字

internal val TicketPaperDarkLowest = Color(0xFF0C0906)
internal val TicketPaperDarkBackground = Color(0xFF141009)  // 暖黑，页面底
internal val TicketPaperDarkLow = Color(0xFF1B150D)
internal val TicketPaperDarkSurface = Color(0xFF211A12)     // Haze 填充色
internal val TicketPaperDark = Color(0xFF261F15)
internal val TicketPaperDarkHigh = Color(0xFF302619)
internal val TicketPaperDarkHighest = Color(0xFF3A2F1F)
internal val TicketPaperDarkBright = Color(0xFF423525)
internal val TicketPaperDarkVariant = Color(0xFF4A382E)     // 对话框 / 卡片
internal val TicketInkDark = Color(0xFFF7EDE3)
internal val TicketInkDarkMuted = Color(0xFFDDCDBE)

// ====== 子页面中性底色（monet/动态主题专用） ======
// 主界面底色保留主题染色（见 Theme.kt 的 LocalMainColorScheme）；从主界面 push 出去的
// 目的地页面在 TraktoSearchTheme 统一覆盖为与主屏同明度（浅色 tone 95 / 深色 tone 5）
// 的零彩度中性灰，避免整站底色都带强调色相。票根主题不走这里：它的牛皮纸底色
// 本身就是主题身份，覆盖成灰会拆掉整套纸面。
internal val NeutralPageBackgroundLight = Color(0xFFF1F1F1)
internal val NeutralPageBackgroundDark = Color(0xFF111111)
internal val NeutralPageInkLight = Color(0xFF1B1B1B)
internal val NeutralPageInkDark = Color(0xFFE2E2E2)

// ====== 浮层中性底色（monet/动态主题专用） ======
// 弹窗/底部弹层使用与各自现有容器同明度档位的零彩度中性，只换「底色」不动卡片等层级：
// 弹窗对齐 surfaceVariant（浅 tone 90 / 深 tone 30），底部弹层对齐 surfaceContainerLow
// （浅 tone 94 / 深 tone 7）。票根主题不覆盖，其牛皮纸由 Theme.kt 的
// LocalFloatingDialogColor/LocalFloatingSheetColor 回退到纸色槽位。
internal val NeutralFloatingDialogLight = Color(0xFFE2E2E2)
internal val NeutralFloatingDialogDark = Color(0xFF474747)
internal val NeutralFloatingSheetLight = Color(0xFFEEEEEE)
internal val NeutralFloatingSheetDark = Color(0xFF151515)

// ====== 玻璃棱镜结构色（深色档） ======
// 深色档的玻璃是一层「白色薄雾」：填充和描边都是极低透明度的白。
// 这四个值在 30 处重复出现，原先只在注释里记着「在使用处直接写」——
// 于是想调一次玻璃质感要翻十几个文件，还容易漏掉几处、留下深浅不一的卡片。
//
// 浅色档的对应值**不**收在这里：那一侧各界面底色差别大，透明度是逐处试出来的
// （0.35 到 0.80 都有），收成常量等于把这些调整抹平。
//
// 填充和描边各有「标准」「淡一档」两级，四个常量里有两对当前取值相同
// （[GlassFillDark] 与 [GlassBorderDarkSubtle] 都是 0.10）。这是有意的：
// 它们是两个角色，只是眼下撞到同一档透明度，调玻璃观感时可以各自动。
val GlassFillDark = Color.White.copy(alpha = 0.10f)

/** 比 [GlassFillDark] 再淡一档，用在压在卡片上的小控件：同样的透明度会显得比卡片还实。 */
val GlassFillDarkSubtle = Color.White.copy(alpha = 0.08f)

val GlassBorderDark = Color.White.copy(alpha = 0.12f)

/** 比 [GlassBorderDark] 再淡一档，用在设置项这类整页平铺的卡片：满屏描边同亮度会显得脏。 */
val GlassBorderDarkSubtle = Color.White.copy(alpha = 0.10f)

val RatingGold = Color(0xFFFFD54F)
val RatingGoldDim = Color(0xFFFFD54F).copy(alpha = 0.7f)

// ====== 反馈类型/状态固定色（四个反馈页共用，不随主题变化） ======
// 浅色主题下小字号要压暗才够对比度，压暗逻辑见 FeedbackVisuals.feedbackAccent
val FeedbackFeature = Color(0xFF34D399)   // 功能建议 — 薄荷绿
val FeedbackBug = Color(0xFFFB7185)       // 问题反馈 — 珊瑚红
val FeedbackUx = Color(0xFFFBBF24)        // 体验问题 — 琥珀黄
val FeedbackOther = Color(0xFF9CA3AF)     // 其他 — 中性灰
val FeedbackReplied = Color(0xFF10B981)   // 已回复 / 上传成功 — 翠绿
val FeedbackDeveloper = Color(0xFF34D399) // 开发者角色标识 — 薄荷绿

// ====== 影视状态固定色（详情页状态绑带共用，不随主题变化） ======
// 原先散在 DetailHeaderContent.getStatusColor 里，9 个 Color(0xFF...) 直接写在 when 分支上，
// 违反「颜色统一收在 Color.kt」的约定。取色逻辑见 DetailVisuals.detailStatusColor
//
// 绑带上的文字颜色不在这里逐个配，走 onColorFor 按亮度算 —— 原先整排都是硬编码白字，
// 7 个色里 5 个不到 AA（制作中橙只有 2.16:1）。
val StatusReleased = Color(0xFF4CAF50)       // 已上映 / 连载中 — 绿
val StatusInProduction = Color(0xFFFF9800)   // 制作中 / 后期 / 试播 — 橙
val StatusPlanned = Color(0xFF2196F3)        // 计划中 — 蓝
val StatusRumored = Color(0xFF9C27B0)        // 传闻中 — 紫
val StatusCanceled = Color(0xFFF44336)       // 已取消 — 红
val StatusEnded = Color(0xFF9E9E9E)          // 已完结 — 灰
// 比 Grey 600 (#757575) 深两档：那个值的亮度 0.1779 正好卡在 WcagBlackWhiteCrossover
// 上，黑白两边都只有 4.6:1，绑带半透明压在海报上就会掉到 AA 以下。
// 这一档亮度 0.1441，离交叉点有 0.035 余量，白字有 5.41:1，
// 余量下限见 ThemeSemanticPaletteTest.MIN_CROSSOVER_MARGIN。
val StatusUnknown = Color(0xFF6A6A6A)        // 未知状态 — 深灰

// ====== 评分平台品牌色（详情页四平台评分区，不随主题变化） ======
// 原先五个 Color(0xFF...) 直接写在 DetailRatingsDialog.RatingsRow 的 badge 构造里。
// 顺带修正 TMDB：以前误用了 IMDb 的黄，两个平台底色一模一样分不出来。
//
// 两种用法要分清：IMDb / TMDB 是「品牌色底 + 反色字标」，配套前景色是 On* 那一对；
// 豆瓣 / 烂番茄 / Metacritic 是「图标 + 品牌色文字」，那种用法必须过 readableOn 压一下 ——
// 原色当文字压在卡片上全部不到 AA（Metacritic 橙在浅卡片上只有 1.72:1）。
val BrandImdb = Color(0xFFF5C518)            // IMDb 黄
val OnBrandImdb = Color(0xFF000000)
val BrandDouban = Color(0xFF2E963D)          // 豆瓣绿
val BrandTmdb = Color(0xFF01B4E4)            // TMDB 青
// TMDB 字标原先配白字，只有 2.43:1。黑字有 8.65:1，也更接近 TMDB 自己在浅底上的用法。
val OnBrandTmdb = Color(0xFF000000)
val BrandRottenTomatoes = Color(0xFFFA320A)  // 烂番茄红
val BrandMetacritic = Color(0xFFFF9500)      // Metacritic 橙

/** 已看标记专用绿：季集进度条 / 已看计数 / 勾选图标共用。 */
val WatchedGreen = Color(0xFF4CAF50)

// ====== 搜索类型固定色（搜索页类型下拉与历史标签，不随主题变化） ======
// 四种搜索目标要能一眼分辨，跟随强调色的话四个标签会变成同色系、只剩文字能区分。
// 这几个色当 10sp 文字用，必须过 readableOn —— 尤其影视剧的琥珀黄，原色压在浅底上不足 2:1。
val SearchTypeDisk = Color(0xFF26A69A)    // 网盘资源 — 青
val SearchTypeMovie = Color(0xFF7986CB)   // 电影 — 靛
val SearchTypeShow = Color(0xFFFFD54F)    // 剧集 — 琥珀
val SearchTypePerson = Color(0xFFF48FB1)  // 人物 — 粉
/** 历史记录里存了未知 type 时的兜底色（旧版本写入过别的字符串）。 */
val SearchTypeUnknown = Color(0xFF4CAF50)

/** 拟态玻璃浅色主题描边：原先在 DetailComments 抄了 3 遍、SearchScreen 1 遍。 */
val NeumorphicBorderLight = Color(0xFFD0D5DC)

// ====== 激活登录页固定色（取票机场景，整屏不随主题） ======
// 这一屏是一台放在牛皮纸上的取票机，它的「材质」就是它的身份，跟 SwiftiePalette 同理：
// 整屏不映射 MaterialTheme。原先是一半跟一半不跟 —— 标题和「什么是 Trakt」按钮读
// colorScheme.primary，选睡莲紫主题时紫色字压在牛皮纸上，跟场景里其他棕调打架。
//
// 两档纸都是浅色（深色档只是把纸压暗一点点，不翻成深底），所以墨色只有一套。
// 每个墨色对两档纸都验过 ≥ 4.5:1，见 ThemeSemanticPaletteTest。
val LoginPaperLight = Color(0xFFF7EFE2)
val LoginPaperDark = Color(0xFFD9CFC2)
/** 标题的赭红墨。和 [MonetAccent.VINTAGE_TICKET] 同一族，但独立取值：票根主题调色不该牵动登录页。 */
val LoginTitleInk = Color(0xFF8F4327)
val LoginSecondaryInk = Color(0xFF5E483A)
/** 「什么是 Trakt」这类次要动作的墨色。 */
val LoginActionInk = Color(0xFF7A4A2E)
/** 登录失败提示。不复用 [ErrorLight]：那个红是为中性底调的，压在牛皮纸上偏冷。 */
val LoginErrorInk = Color(0xFF96281F)

/** 影院座椅剪影。压在牛皮纸底上只是一层影子，不承载文字，所以不参与对比度校验。 */
val LoginSeatSilhouette = Color(0xFF6B5240)

// ====== 取票机机壳（自绘拟物，既不走毛玻璃也不随主题色） ======
// 原先机壳是一层 haze 毛玻璃，于是「机器」是半透明的，背景的爆米花能从机壳里透出来 ——
// 取票机是台设备，不是一块玻璃。现在自绘：竖向渐变 + 上下折边 + 四角螺丝 + 平铺噪点。
//
// 明暗两档都是深金属（设备本来就深），差别只是深多少。改这两个值必须连带复核下面
// 每一个墨色的对比度：机壳是这一屏几乎所有文字的底，护栏见 LoginMachinePaletteTest。
val MachineShellLight = Color(0xFF7C6552)
val MachineShellDark = Color(0xFF3B2E24)

/**
 * 机壳竖向渐变往下压暗的比例。只往下压、不往上提是刻意的：
 * 往上提会让顶部那一带（铭牌、像素屏、六格都在那儿）变亮，压在上面的墨色对比度跟着掉。
 * 只往下压，机壳最亮的一点就是 [MachineShellLight] 本身，下面那些对比度数字才算得准。
 */
const val MachineShellShadeFraction = 0.18f

/** 铭牌凹槽。铭牌不直接压在机壳上：机壳有渐变，凹槽给铭牌一个可控的底。 */
val MachinePlateLight = Color(0xFF5A4636)
val MachinePlateDark = Color(0xFF2A2118)

/** 铭牌蚀刻字。压在凹槽上 6.64:1（浅档）/ 11.8:1（深档）。 */
val MachinePlateInk = Color(0xFFF0DCC0)

/** 六格取票码的数字与光标。压在机壳上 4.97:1（浅档）/ 11.9:1（深档）。 */
val MachineCodeInk = Color(0xFFFBF3E8)

/** 像素屏凹槽。比机壳更深一档才像嵌进去的。 */
val MachineDisplayWellLight = Color(0xFF241C16)
val MachineDisplayWellDark = Color(0xFF15100C)

/** 老式点阵屏的琥珀荧光，压在凹槽上 9.42:1。 */
val MachineDisplayInk = Color(0xFFFFB347)

/** 报错时的点阵屏红，压在凹槽上 5.99:1。 */
val MachineDisplayInkError = Color(0xFFFF6B5A)

/** 键帽面。深档不是把浅档调暗，而是另一种塑料。 */
val MachineKeycapLight = Color(0xFFD9CBBB)
val MachineKeycapDark = Color(0xFF4A3E33)

/** 键面数字。压在键帽上 8.28:1（浅档）/ 7.27:1（深档）。 */
val MachineKeyInkLight = Color(0xFF3A2E24)
val MachineKeyInkDark = Color(0xFFE4D6C6)

/** 出票口内壁。比像素屏还深，看上去是机器里面的暗处。 */
val MachineSlotWall = Color(0xFF1A1310)

/**
 * 跑马灯灯泡。灯泡不承载文字，这几个色只管「像不像一颗白炽灯」。
 *
 * 三档而不是两档：白炽灯丝降温的时候色温跟着往下掉，先由暖白转橙，再转暗红才灭。
 * 只在暖白和暗坑之间插值，灭下去的过程看着像有人在拉调光旋钮，不像灯丝在冷。
 */
val MachineBulbLit = Color(0xFFFFE9B8)

/** 灯丝将冷时的余烬色。亮度低档的插值端点，不单独出现。 */
val MachineBulbEmber = Color(0xFFE2531B)
val MachineBulbUnlitLight = Color(0xFF5A4A3C)
val MachineBulbUnlitDark = Color(0xFF2A211A)

/**
 * 取票结果反馈的两档灯色：码对了整排亮绿，码错了整排亮红。
 *
 * 每档两个端点，跟白炽档一样按亮度插值 —— 信号灯升温时也是先暗后亮，
 * 只有一个颜色的话「整排亮起来」会是一次硬切。满亮端不取白：取白就不是绿灯红灯了。
 *
 * 颜色不是唯一的反馈通道：点阵屏同时切文案与墨色，六格还会左右抖。
 * 这两档灯是加强，不承担「只能靠颜色分辨成功失败」。
 */
val MachineBulbLitGreen = Color(0xFFA9F09A)
val MachineBulbEmberGreen = Color(0xFF1E6B2C)
val MachineBulbLitRed = Color(0xFFFF9C86)
val MachineBulbEmberRed = Color(0xFF8F1712)

private val MonetDoubanGreenLight = Color(0xFF5E916A)
private val MonetDoubanGreenDark = Color(0xFF78A985)

/** 豆瓣按钮专用色：保留绿色识别，同时轻微吸收当前莫奈主题主色。 */
fun ColorScheme.monetDoubanGreen(): Color {
    val base = if (isDarkScheme) MonetDoubanGreenDark else MonetDoubanGreenLight
    return lerp(base, primary, 0.14f)
}

fun ColorScheme.onMonetDoubanGreen(): Color = Color.White
