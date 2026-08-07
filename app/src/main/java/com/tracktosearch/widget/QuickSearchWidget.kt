package com.tracktosearch.widget

import android.content.Context
import android.content.res.Configuration
import androidx.compose.runtime.Composable
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.glance.GlanceId
import androidx.glance.GlanceModifier
import androidx.glance.GlanceTheme
import androidx.glance.LocalSize
import androidx.glance.action.actionStartActivity
import androidx.glance.action.clickable
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.GlanceAppWidgetReceiver
import androidx.glance.appwidget.SizeMode
import androidx.glance.appwidget.cornerRadius
import androidx.glance.appwidget.provideContent
import androidx.glance.background
import androidx.glance.layout.Alignment
import androidx.glance.layout.Box
import androidx.glance.layout.Column
import androidx.glance.layout.Spacer
import androidx.glance.layout.fillMaxSize
import androidx.glance.layout.height
import androidx.glance.layout.padding
import androidx.glance.text.FontWeight
import androidx.glance.text.Text
import androidx.glance.text.TextAlign
import androidx.glance.text.TextStyle
import androidx.glance.unit.ColorProvider
import com.tracktosearch.MainActivity
import com.tracktosearch.R
import com.tracktosearch.data.local.ThemeStorage
import com.tracktosearch.ui.theme.MonetAccent
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent

private val CompactWidgetSize = DpSize(120.dp, 60.dp)
private val ExpandedWidgetSize = DpSize(240.dp, 100.dp)

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
        setOf(
            CompactWidgetSize,
            ExpandedWidgetSize
        )
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
    val isDarkTheme = (context.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) ==
        Configuration.UI_MODE_NIGHT_YES
    val backgroundColor = ColorProvider(if (isDarkTheme) accent.dark else accent.light)
    val contentColor = ColorProvider(if (isDarkTheme) accent.darkOn else accent.lightOn)

    Box(
        modifier = GlanceModifier
            .fillMaxSize()
            .cornerRadius(20.dp)
            .background(backgroundColor)
            .clickable(actionStartActivity<MainActivity>()),
        contentAlignment = Alignment.Center
    ) {
        when (layout) {
            QuickSearchWidgetLayout.COMPACT -> QuickSearchWidgetCompactContent(context, contentColor)
            QuickSearchWidgetLayout.EXPANDED -> QuickSearchWidgetExpandedContent(context, contentColor)
        }
    }
}

@Composable
private fun QuickSearchWidgetCompactContent(
    context: Context,
    contentColor: ColorProvider
) {
    Column(
        modifier = GlanceModifier
            .fillMaxSize()
            .padding(horizontal = 10.dp, vertical = 8.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = "TraktoSearch",
            style = TextStyle(
                color = contentColor,
                fontSize = 14.sp,
                fontWeight = FontWeight.Bold,
                textAlign = TextAlign.Center
            )
        )
        Spacer(modifier = GlanceModifier.height(2.dp))
        Text(
            text = context.getString(R.string.widget_quick_search_hint),
            style = TextStyle(
                color = contentColor,
                fontSize = 10.sp,
                textAlign = TextAlign.Center
            )
        )
    }
}

@Composable
private fun QuickSearchWidgetExpandedContent(
    context: Context,
    contentColor: ColorProvider
) {
    Column(
        modifier = GlanceModifier
            .fillMaxSize()
            .padding(12.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = "TraktoSearch",
            style = TextStyle(
                color = contentColor,
                fontSize = 16.sp,
                fontWeight = FontWeight.Bold,
                textAlign = TextAlign.Center
            )
        )
        Spacer(modifier = GlanceModifier.height(4.dp))
        Text(
            text = context.getString(R.string.widget_quick_search_hint),
            style = TextStyle(
                color = contentColor,
                fontSize = 12.sp,
                textAlign = TextAlign.Center
            )
        )
    }
}

class QuickSearchWidgetReceiver : GlanceAppWidgetReceiver() {
    override val glanceAppWidget: GlanceAppWidget = QuickSearchWidget()
}
