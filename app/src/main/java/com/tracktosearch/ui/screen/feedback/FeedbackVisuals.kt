package com.tracktosearch.ui.screen.feedback

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.BugReport
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.DoneAll
import androidx.compose.material.icons.rounded.Image
import androidx.compose.material.icons.rounded.Lightbulb
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material.icons.rounded.MoreHoriz
import androidx.compose.material.icons.rounded.Person
import androidx.compose.material.icons.rounded.Schedule
import androidx.compose.material.icons.rounded.SentimentDissatisfied
import androidx.compose.material.icons.rounded.SupportAgent
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.tracktosearch.R
import com.tracktosearch.ui.component.LoadMoreFooterState
import com.tracktosearch.ui.theme.FeedbackBug
import com.tracktosearch.ui.theme.FeedbackDeveloper
import com.tracktosearch.ui.theme.FeedbackFeature
import com.tracktosearch.ui.theme.FeedbackOther
import com.tracktosearch.ui.theme.FeedbackReplied
import com.tracktosearch.ui.theme.FeedbackUx
import com.tracktosearch.ui.theme.readableOn
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import java.util.concurrent.TimeUnit

/**
 * 反馈系列页面（列表 / 详情 / 消息 / 写新反馈）的共用视觉层。
 *
 * 这四个页面原先各自抄一份 type→颜色/图标/文案 的 when 表，改一个类型要改四处，
 * 而且列表页画成裸彩色文字、详情页画成胶囊，同一条反馈在两页长得不一样。
 * 映射和小组件都收在这里，页面只负责排版。
 */

// ====== 类型 / 状态映射 ======

/**
 * 固定强调色调到在当前卡片底上读得清。
 *
 * 这几个色是为深色底挑的，直接拿 0xFFFBBF24 当浅色主题下 12sp 小字，
 * 落在同色淡底胶囊上对比度不够。
 *
 * 原先是无条件 `lerp(base, Black, 0.32f)`，一个系数管所有色相 —— 对黄和黄绿远远不够：
 * 体验问题的 #FBBF24 压暗后压在浅色卡片上只有 2.77:1。改成按目标对比度反解，
 * 深色档也一样走（深色卡片上 4.5:1 达不到的色会朝白推），不再靠「深色档原样返回」赌运气。
 */
@Composable
internal fun feedbackAccent(base: Color): Color {
    val surface = MaterialTheme.colorScheme.surfaceVariant
    return remember(base, surface) { readableOn(base, surface) }
}

internal fun feedbackTypeBaseColor(type: String): Color = when (type) {
    "FEATURE" -> FeedbackFeature
    "BUG" -> FeedbackBug
    "UX" -> FeedbackUx
    else -> FeedbackOther
}

@Composable
internal fun feedbackTypeColor(type: String): Color = feedbackAccent(feedbackTypeBaseColor(type))

internal fun feedbackTypeIcon(type: String): ImageVector = when (type) {
    "FEATURE" -> Icons.Rounded.Lightbulb
    "BUG" -> Icons.Rounded.BugReport
    "UX" -> Icons.Rounded.SentimentDissatisfied
    else -> Icons.Rounded.MoreHoriz
}

@Composable
internal fun feedbackTypeLabel(type: String): String = stringResource(
    when (type) {
        "FEATURE" -> R.string.feedback_type_feature
        "BUG" -> R.string.feedback_type_bug
        "UX" -> R.string.feedback_type_ux
        else -> R.string.feedback_type_other
    }
)

@Composable
internal fun feedbackStatusColor(status: String): Color = when (status) {
    "PENDING" -> MaterialTheme.colorScheme.primary
    "REPLIED" -> feedbackAccent(FeedbackReplied)
    else -> MaterialTheme.colorScheme.onSurfaceVariant
}

internal fun feedbackStatusIcon(status: String): ImageVector = when (status) {
    "PENDING" -> Icons.Rounded.Schedule
    "REPLIED" -> Icons.Rounded.DoneAll
    else -> Icons.Rounded.Lock
}

@Composable
internal fun feedbackStatusLabel(status: String): String = stringResource(
    when (status) {
        "PENDING" -> R.string.feedback_status_pending
        "REPLIED" -> R.string.feedback_status_replied
        else -> R.string.feedback_status_closed
    }
)

/** 开发者用薄荷绿、自己用主题主色，四个页面的角色配色统一走这里。 */
@Composable
internal fun feedbackRoleColor(isDeveloper: Boolean): Color =
    if (isDeveloper) feedbackAccent(FeedbackDeveloper) else MaterialTheme.colorScheme.primary

// ====== 共用小组件 ======

/** 类型胶囊：淡色底 + 同色图标文字，列表页与详情页共用一种画法。 */
@Composable
internal fun FeedbackTypeBadge(type: String, modifier: Modifier = Modifier) {
    val accent = feedbackTypeColor(type)
    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(50),
        color = accent.copy(alpha = 0.14f)
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            Icon(feedbackTypeIcon(type), contentDescription = null, tint = accent, modifier = Modifier.size(14.dp))
            Text(feedbackTypeLabel(type), fontSize = 12.sp, color = accent, fontWeight = FontWeight.SemiBold, maxLines = 1)
        }
    }
}

/** 状态胶囊。 */
@Composable
internal fun FeedbackStatusPill(status: String, modifier: Modifier = Modifier) {
    val accent = feedbackStatusColor(status)
    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(50),
        color = accent.copy(alpha = 0.14f)
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            Icon(feedbackStatusIcon(status), contentDescription = null, tint = accent, modifier = Modifier.size(13.dp))
            Text(feedbackStatusLabel(status), fontSize = 12.sp, color = accent, fontWeight = FontWeight.SemiBold, maxLines = 1)
        }
    }
}

/** 反馈编号 `#A12`。 */
@Composable
internal fun FeedbackIdLabel(displayId: String, modifier: Modifier = Modifier) {
    if (displayId.isBlank()) return
    Text(
        text = stringResource(R.string.feedback_id_format, displayId),
        modifier = modifier,
        fontSize = 11.sp,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        fontWeight = FontWeight.Medium,
        maxLines = 1
    )
}

/** 截图角标：原先是 `📷` emoji，读屏念不出、字体渲染还跟着系统 emoji 走。 */
@Composable
internal fun ScreenshotCountBadge(count: Int, modifier: Modifier = Modifier) {
    if (count <= 0) return
    val label = stringResource(R.string.feedback_screenshots)
    Row(
        modifier = modifier.clearAndSetSemantics { contentDescription = label },
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(2.dp)
    ) {
        Icon(
            Icons.Rounded.Image,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(13.dp)
        )
        Text(
            text = count.toString(),
            fontSize = 11.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            fontWeight = FontWeight.Medium
        )
    }
}

/** 角色头像：原先是代码里硬编码的 `"D"` / `"我"`，中文字面量绕过了 strings.xml。 */
@Composable
internal fun RoleAvatar(isDeveloper: Boolean, modifier: Modifier = Modifier, size: Dp = 36.dp) {
    val label = stringResource(
        if (isDeveloper) R.string.feedback_role_developer else R.string.feedback_role_me
    )
    Box(
        modifier = modifier
            .size(size)
            .clip(CircleShape)
            .background(feedbackRoleColor(isDeveloper)),
        contentAlignment = Alignment.Center
    ) {
        Icon(
            imageVector = if (isDeveloper) Icons.Rounded.SupportAgent else Icons.Rounded.Person,
            contentDescription = label,
            tint = Color.White,
            modifier = Modifier.size(size * 0.58f)
        )
    }
}

/** 提示条：图标 + 说明，淡色底。已关闭提示、提交失败提示共用。 */
@Composable
internal fun FeedbackNoticeBanner(
    icon: ImageVector,
    text: String,
    accent: Color,
    modifier: Modifier = Modifier
) {
    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(14.dp),
        color = accent.copy(alpha = 0.12f)
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Icon(icon, contentDescription = null, tint = accent, modifier = Modifier.size(18.dp))
            Text(text = text, fontSize = 13.sp, color = accent, lineHeight = 18.sp)
        }
    }
}

/**
 * 状态时间线：已提交 → 已回复 → 已关闭。
 *
 * 状态字段只有三个值且单向推进，画成三个点比一个胶囊更能回答「我这条走到哪了」。
 * 未到达的段落用弱化色，已到达的用状态色。
 */
@Composable
internal fun FeedbackStatusTimeline(status: String, modifier: Modifier = Modifier) {
    val reachedIndex = when (status) {
        "PENDING" -> 0
        "REPLIED" -> 1
        else -> 2
    }
    val steps = listOf(
        stringResource(R.string.feedback_timeline_submitted) to Icons.Rounded.CheckCircle,
        stringResource(R.string.feedback_status_replied) to Icons.Rounded.DoneAll,
        stringResource(R.string.feedback_status_closed) to Icons.Rounded.Lock
    )
    val activeColor = feedbackStatusColor(status)
    val idleColor = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.45f)
    Row(modifier = modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        steps.forEachIndexed { index, (label, icon) ->
            val reached = index <= reachedIndex
            val tint = if (reached) activeColor else idleColor
            if (index > 0) {
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .padding(horizontal = 6.dp)
                        .height(1.5.dp)
                        .background(if (index <= reachedIndex) activeColor.copy(alpha = 0.5f) else idleColor.copy(alpha = 0.35f))
                )
            }
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                Icon(icon, contentDescription = null, tint = tint, modifier = Modifier.size(14.dp))
                Text(
                    text = label,
                    fontSize = 11.sp,
                    color = tint,
                    fontWeight = if (index == reachedIndex) FontWeight.SemiBold else FontWeight.Normal,
                    maxLines = 1
                )
            }
        }
    }
}

// ====== 时间文案 ======

/**
 * 时间文案格式化器。
 *
 * 相对时间原先是个五参数的顶层函数，每个调用点都要先取五条 stringResource 再传进去；
 * 消息页嫌麻烦就直接打了完整的 `yyyy-MM-dd HH:mm`。文案一次取好裹进对象，
 * 页面直接调 [relative] / [dayLabel] / [clock]。
 */
@Stable
internal class FeedbackTimeLabels(
    private val justNow: String,
    private val minutesAgo: String,
    private val hoursAgo: String,
    private val daysAgo: String,
    private val monthsAgo: String,
    private val today: String,
    private val yesterday: String
) {
    /** 相对时间：刚刚 / n 分钟前 / n 小时前 / n 天前 / n 个月前。入参是秒级时间戳。 */
    fun relative(epochSeconds: Long): String {
        val minutes = TimeUnit.MILLISECONDS.toMinutes(
            (System.currentTimeMillis() - epochSeconds * 1000).coerceAtLeast(0L)
        )
        return when {
            minutes < 1 -> justNow
            minutes < 60 -> minutesAgo.format(minutes)
            minutes < 1440 -> hoursAgo.format(minutes / 60)
            minutes < 43200 -> daysAgo.format(minutes / 1440)
            else -> monthsAgo.format(minutes / 43200)
        }
    }

    // SimpleDateFormat 构造不便宜；本类只在组合（主线程）里逐条格式化，实例级复用即可
    private val mdFormat by lazy { SimpleDateFormat("MM-dd", Locale.getDefault()) }
    private val ymdFormat by lazy { SimpleDateFormat("yyyy-MM-dd", Locale.getDefault()) }
    private val hmFormat by lazy { SimpleDateFormat("HH:mm", Locale.getDefault()) }

    /** 日期分隔标签：今天 / 昨天 / 本年 MM-dd / 往年 yyyy-MM-dd。 */
    fun dayLabel(epochSeconds: Long): String {
        val target = Calendar.getInstance().apply { timeInMillis = epochSeconds * 1000 }
        val now = Calendar.getInstance()
        val sameYear = target.get(Calendar.YEAR) == now.get(Calendar.YEAR)
        val dayDiff = daysBetween(target, now)
        return when {
            dayDiff == 0 -> today
            dayDiff == 1 -> yesterday
            sameYear -> mdFormat.format(Date(epochSeconds * 1000))
            else -> ymdFormat.format(Date(epochSeconds * 1000))
        }
    }

    /** 时刻 HH:mm。 */
    fun clock(epochSeconds: Long): String = hmFormat.format(Date(epochSeconds * 1000))

    /** 两个日历实例相差几个自然日（只比日期，不比时刻）。 */
    private fun daysBetween(target: Calendar, now: Calendar): Int {
        if (target.get(Calendar.YEAR) == now.get(Calendar.YEAR)) {
            return now.get(Calendar.DAY_OF_YEAR) - target.get(Calendar.DAY_OF_YEAR)
        }
        // 跨年只需要区分「是不是昨天」：拿今天零点和目标时刻比一个自然日
        val startOfToday = (now.clone() as Calendar).apply {
            set(Calendar.HOUR_OF_DAY, 0)
            set(Calendar.MINUTE, 0)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }
        val diff = startOfToday.timeInMillis - target.timeInMillis
        return if (diff in 1..86_400_000L) 1 else 2
    }
}

@Composable
internal fun rememberFeedbackTimeLabels(): FeedbackTimeLabels {
    val justNow = stringResource(R.string.feedback_time_just_now)
    val minutesAgo = stringResource(R.string.feedback_time_minutes_ago)
    val hoursAgo = stringResource(R.string.feedback_time_hours_ago)
    val daysAgo = stringResource(R.string.feedback_time_days_ago)
    val monthsAgo = stringResource(R.string.feedback_time_months_ago)
    val today = stringResource(R.string.feedback_day_today)
    val yesterday = stringResource(R.string.feedback_day_yesterday)
    return remember(justNow, minutesAgo, hoursAgo, daysAgo, monthsAgo, today, yesterday) {
        FeedbackTimeLabels(justNow, minutesAgo, hoursAgo, daysAgo, monthsAgo, today, yesterday)
    }
}

// ====== 分页 ======

/**
 * 触底分页的尾部状态。
 *
 * ViewModel 在翻页失败时保留旧列表、只把 isRefreshing 收回去，光看 state 分不出
 * 「还在请求」和「请求回来但一条没加上」。这里记下发请求那一刻的条目数：
 * 请求结束后条目数没变就是失败，给重试而不是永远转圈。
 */
@Stable
internal class FeedbackPagingTracker {
    private var requestedSize: Int? by mutableStateOf(null)

    fun onRequest(currentSize: Int) {
        requestedSize = currentSize
    }

    /** 下拉刷新等整批重置的场景要清掉，否则新旧请求的条目数会互相干扰。 */
    fun reset() {
        requestedSize = null
    }

    fun footerState(loading: Boolean, currentSize: Int): LoadMoreFooterState = when {
        loading || requestedSize == null -> LoadMoreFooterState.Loading
        currentSize == requestedSize -> LoadMoreFooterState.Error
        else -> LoadMoreFooterState.Loading
    }
}

@Composable
internal fun rememberFeedbackPagingTracker(): FeedbackPagingTracker = remember { FeedbackPagingTracker() }





