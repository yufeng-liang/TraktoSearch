package com.tracktosearch.widget

import android.content.Context
import android.content.Intent
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.glance.GlanceId
import androidx.glance.GlanceModifier
import androidx.glance.GlanceTheme
import androidx.glance.Image
import androidx.glance.ImageProvider
import androidx.glance.LocalSize
import androidx.glance.action.clickable
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.GlanceAppWidgetReceiver
import androidx.glance.appwidget.SizeMode
import androidx.glance.appwidget.action.actionStartActivity
import androidx.glance.appwidget.cornerRadius
import androidx.glance.appwidget.provideContent
import androidx.glance.background
import androidx.glance.color.ColorProvider as dayNightColorProvider
import androidx.glance.layout.Alignment
import androidx.glance.layout.Box
import androidx.glance.layout.Column
import androidx.glance.layout.Row
import androidx.glance.layout.Spacer
import androidx.glance.layout.fillMaxHeight
import androidx.glance.layout.fillMaxSize
import androidx.glance.layout.fillMaxWidth
import androidx.glance.layout.height
import androidx.glance.layout.padding
import androidx.glance.layout.size
import androidx.glance.layout.width
import androidx.glance.semantics.SemanticsProperties
import androidx.glance.semantics.semantics
import androidx.glance.text.FontWeight
import androidx.glance.text.Text
import androidx.glance.text.TextAlign
import androidx.glance.text.TextStyle
import androidx.glance.unit.ColorProvider
import com.tracktosearch.MainActivity
import com.tracktosearch.R
import com.tracktosearch.data.local.ThemeStorage
import com.tracktosearch.ui.navigation.SearchNavigator
import com.tracktosearch.ui.theme.MonetAccent
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent

private val CompactWidgetSize = DpSize(120.dp, 60.dp)
private val ExpandedWidgetSize = DpSize(240.dp, 100.dp)
private val WidgetLightSurface = Color(0xFFFFF8F3)
private val WidgetDarkSurface = Color(0xFF251B16)
private val WidgetLightOnSurface = Color(0xFF2D211D)
private val WidgetDarkOnSurface = Color(0xFFFFEEE6)

@EntryPoint
@InstallIn(SingletonComponent::class)
interface QuickSearchWidgetThemeEntryPoint {
    fun themeStorage(): ThemeStorage
}

internal enum class QuickSearchWidgetLayout {
    COMPACT,
    EXPANDED
}

internal fun resolveWidgetLayout(size: DpSize): QuickSearchWidgetLayout {
    return if (size.width >= ExpandedWidgetSize.width && size.height >= ExpandedWidgetSize.height) {
        QuickSearchWidgetLayout.EXPANDED
    } else {
        QuickSearchWidgetLayout.COMPACT
    }
}

internal fun resolveWidgetAccent(accent: MonetAccent?): MonetAccent {
    return accent ?: MonetAccent.VINTAGE_TICKET
}

class QuickSearchWidget : GlanceAppWidget() {

    override val sizeMode: SizeMode = SizeMode.Responsive(
        setOf(CompactWidgetSize, ExpandedWidgetSize)
    )

    override suspend fun provideGlance(context: Context, id: GlanceId) {
        val accent = runCatching {
            EntryPointAccessors.fromApplication(
                context.applicationContext,
                QuickSearchWidgetThemeEntryPoint::class.java
            ).themeStorage().readAccentColorSnapshot()
        }.getOrNull()

        provideContent {
            GlanceTheme {
                QuickSearchWidgetContent(
                    context = context,
                    accent = resolveWidgetAccent(accent)
                )
            }
        }
    }
}

@Composable
private fun QuickSearchWidgetContent(
    context: Context,
    accent: MonetAccent
) {
    val layout = resolveWidgetLayout(LocalSize.current)
    val surfaceColor = dayNightColorProvider(WidgetLightSurface, WidgetDarkSurface)
    val contentColor = dayNightColorProvider(WidgetLightOnSurface, WidgetDarkOnSurface)
    val accentColor = dayNightColorProvider(accent.light, accent.dark)
    val openSearchIntent = Intent(context, MainActivity::class.java).apply {
        flags = Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
        putExtra(SearchNavigator.EXTRA_OPEN_SEARCH, true)
    }

    Box(
        modifier = GlanceModifier
            .fillMaxSize()
            .cornerRadius(20.dp)
            .background(surfaceColor)
            .semantics {
                set(
                    SemanticsProperties.ContentDescription,
                    listOf(context.getString(R.string.widget_quick_search_content_description))
                )
            }
            .clickable(actionStartActivity(openSearchIntent))
    ) {
        // 左侧票券色条只承担主题识别，不形成第二个点击区域。
        Box(
            modifier = GlanceModifier
                .fillMaxHeight()
                .width(4.dp)
                .background(accentColor)
        ) {}
        when (layout) {
            QuickSearchWidgetLayout.COMPACT -> {
                QuickSearchWidgetCompactContent(contentColor)
            }
            QuickSearchWidgetLayout.EXPANDED -> {
                QuickSearchWidgetExpandedContent(contentColor, accentColor)
            }
        }
    }
}

@Composable
private fun QuickSearchWidgetCompactContent(contentColor: ColorProvider) {
    Row(
        modifier = GlanceModifier
            .fillMaxSize()
            .padding(horizontal = 8.dp, vertical = 8.dp),
        verticalAlignment = Alignment.Vertical.CenterVertically
    ) {
        Image(
            provider = ImageProvider(R.mipmap.ic_launcher),
            contentDescription = null,
            modifier = GlanceModifier.size(22.dp)
        )
        Spacer(modifier = GlanceModifier.width(6.dp))
        Text(
            text = androidx.glance.LocalContext.current.getString(R.string.widget_quick_search_title),
            modifier = GlanceModifier.defaultWeight(),
            style = TextStyle(
                color = contentColor,
                fontSize = 10.sp,
                fontWeight = FontWeight.Bold,
                textAlign = TextAlign.Start
            )
        )
        Text(
            text = "\u2192",
            style = TextStyle(
                color = contentColor,
                fontSize = 14.sp,
                fontWeight = FontWeight.Bold,
                textAlign = TextAlign.Center
            )
        )
    }
}

@Composable
private fun QuickSearchWidgetExpandedContent(
    contentColor: ColorProvider,
    accentColor: ColorProvider
) {
    val context = androidx.glance.LocalContext.current
    Column(
        modifier = GlanceModifier
            .fillMaxSize()
            .padding(start = 16.dp, end = 14.dp, top = 10.dp, bottom = 10.dp)
    ) {
        Row(
            modifier = GlanceModifier.fillMaxWidth(),
            verticalAlignment = Alignment.Vertical.CenterVertically
        ) {
            Image(
                provider = ImageProvider(R.mipmap.ic_launcher),
                contentDescription = null,
                modifier = GlanceModifier.size(38.dp)
            )
            Spacer(modifier = GlanceModifier.width(10.dp))
            Column(
                modifier = GlanceModifier.defaultWeight(),
                verticalAlignment = Alignment.Vertical.CenterVertically
            ) {
                Text(
                    text = context.getString(R.string.app_name),
                    style = TextStyle(
                        color = contentColor,
                        fontSize = 10.sp
                    )
                )
                Text(
                    text = context.getString(R.string.widget_quick_search_title),
                    style = TextStyle(
                        color = contentColor,
                        fontSize = 17.sp,
                        fontWeight = FontWeight.Bold
                    )
                )
            }
            Text(
                text = "\u2192",
                style = TextStyle(
                    color = accentColor,
                    fontSize = 18.sp,
                    fontWeight = FontWeight.Bold,
                    textAlign = TextAlign.Center
                )
            )
        }
        Spacer(modifier = GlanceModifier.height(5.dp))
        Row(
            modifier = GlanceModifier.fillMaxWidth(),
            verticalAlignment = Alignment.Vertical.CenterVertically
        ) {
            Text(
                text = context.getString(R.string.widget_quick_search_scope),
                modifier = GlanceModifier.defaultWeight(),
                style = TextStyle(
                    color = contentColor,
                    fontSize = 10.sp
                )
            )
            Text(
                text = context.getString(R.string.widget_quick_search_title),
                style = TextStyle(
                    color = accentColor,
                    fontSize = 10.sp,
                    fontWeight = FontWeight.Bold,
                    textAlign = TextAlign.End
                )
            )
        }
    }
}

class QuickSearchWidgetReceiver : GlanceAppWidgetReceiver() {
    override val glanceAppWidget: GlanceAppWidget = QuickSearchWidget()
}
