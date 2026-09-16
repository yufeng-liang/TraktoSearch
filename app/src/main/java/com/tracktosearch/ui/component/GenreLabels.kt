package com.tracktosearch.ui.component

import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import com.tracktosearch.R

/**
 * 把 TMDB / Trakt / 豆瓣返回的体裁原始 key（英文）翻译成当前语言。
 *
 * 数据层始终保留原始英文 key：筛选、聚合、统计都按 key 比较，
 * 只有展示时才经过这里翻译，避免把本地化文案写回缓存后无法再匹配。
 * 未知 key 回退为首字母大写的原串，保证不会显示空白。
 */
@Composable
fun localizedGenreName(genre: String): String = when (genre.trim().lowercase()) {
    "action" -> stringResource(R.string.genre_action)
    "adventure" -> stringResource(R.string.genre_adventure)
    "animation" -> stringResource(R.string.genre_animation)
    "anime" -> stringResource(R.string.genre_anime)
    "comedy" -> stringResource(R.string.genre_comedy)
    "crime" -> stringResource(R.string.genre_crime)
    "documentary" -> stringResource(R.string.genre_documentary)
    "drama" -> stringResource(R.string.genre_drama)
    "fantasy" -> stringResource(R.string.genre_fantasy)
    "history" -> stringResource(R.string.genre_history)
    "horror" -> stringResource(R.string.genre_horror)
    "music" -> stringResource(R.string.genre_music)
    "musical" -> stringResource(R.string.genre_musical)
    "mystery" -> stringResource(R.string.genre_mystery)
    "romance" -> stringResource(R.string.genre_romance)
    "science-fiction", "science_fiction", "sci-fi", "scifi" -> stringResource(R.string.genre_scifi)
    // Trakt 用「A & B」形式返回的组合体裁，按主体裁归并
    "sci-fi & fantasy", "science fiction & fantasy" -> stringResource(R.string.genre_scifi)
    "war & politics" -> stringResource(R.string.genre_war)
    "sport", "sports" -> stringResource(R.string.genre_sport)
    "thriller" -> stringResource(R.string.genre_thriller)
    // 豆瓣同步条目里出现的 TMDB 体裁别名
    "suspense" -> stringResource(R.string.genre_suspense)
    "donghua" -> stringResource(R.string.genre_donghua)
    "holiday" -> stringResource(R.string.genre_holiday)
    "war" -> stringResource(R.string.genre_war)
    "western" -> stringResource(R.string.genre_western)
    "family" -> stringResource(R.string.genre_family)
    "children", "kids" -> stringResource(R.string.genre_kids)
    "news" -> stringResource(R.string.genre_news)
    "reality" -> stringResource(R.string.genre_reality)
    "soap" -> stringResource(R.string.genre_soap)
    "talk", "talk_show", "talk-show" -> stringResource(R.string.genre_talk)
    "espionage", "spy" -> stringResource(R.string.genre_spy)
    "superhero" -> stringResource(R.string.genre_superhero)
    "biography" -> stringResource(R.string.genre_biography)
    "film-noir", "film_noir", "noir" -> stringResource(R.string.genre_noir)
    "game-show", "game_show" -> stringResource(R.string.genre_game_show)
    "other" -> stringResource(R.string.genre_other)
    else -> genre.trim().replaceFirstChar { it.uppercase() }
}

/**
 * 列表卡片里的一行体裁（用 `,` 或 `·` 分隔）。
 *
 * 逐项翻译后再拼回原分隔符，保持与数据层同一套切分规则。
 */
@Composable
fun localizedGenreLine(genres: String): String {
    // joinToString 的 transform 不是 @Composable 上下文，先收集再手动拼接
    val separator = if (genres.contains("·")) " · " else ", "
    val names = genres.split(",", "·")
        .map { it.trim() }
        .filter { it.isNotEmpty() }
        .map { localizedGenreName(it) }
    return names.joinToString(separator)
}
