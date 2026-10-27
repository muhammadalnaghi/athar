package com.athar.feature.widgets.monthly

import android.content.Context
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.glance.GlanceId
import androidx.glance.GlanceModifier
import androidx.glance.GlanceTheme
import androidx.glance.action.actionStartActivity
import androidx.glance.action.clickable
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.GlanceAppWidgetReceiver
import androidx.glance.appwidget.provideContent
import androidx.glance.background
import androidx.glance.layout.Box
import androidx.glance.layout.Column
import androidx.glance.layout.Spacer
import androidx.glance.layout.fillMaxSize
import androidx.glance.layout.height
import androidx.glance.layout.padding
import androidx.glance.text.FontWeight
import androidx.glance.text.Text
import androidx.glance.text.TextStyle
import androidx.glance.unit.ColorProvider
import com.athar.feature.widgets.*

class MonthlyWidget : GlanceAppWidget() {
    override suspend fun provideGlance(context: Context, id: GlanceId) {
        provideContent { GlanceTheme { MonthlyLiveContent(context) } }
    }

    /** Session-composed path: reloads the encrypted snapshot, keyed on the refresh signal. */
    @Composable
    internal fun MonthlyLiveContent(context: Context) {
        val refreshToken by WidgetRefreshSignal.revision.collectAsState()
        val state by produceState<WidgetLoad<WidgetDataLoader.MonthlySnapshot>>(WidgetLoad.Loading, refreshToken) {
            value = loadWidgetState { WidgetDataLoader.loadMonthlySnapshot(context) }
        }
        MonthlyWidgetContent(context, state)
    }

    /**
     * Shared three-state frame. The one-shot strong publisher (WM059) renders this
     * same tree with a [WidgetLoad.Ready] value only, so both publication paths use
     * one visual definition.
     */
    @Composable
    internal fun MonthlyWidgetContent(context: Context, state: WidgetLoad<WidgetDataLoader.MonthlySnapshot>) {
        Box(GlanceModifier.fillMaxSize().background(WidgetColors.Parchment).padding(16.dp)
            .clickable(actionStartActivity(appLaunchComponent(context)))) {
            when (val loaded = state) {
                WidgetLoad.Loading -> Text(context.getString(R.string.widget_loading))
                WidgetLoad.Unavailable -> Text(context.getString(R.string.widget_unavailable))
                is WidgetLoad.Ready -> MonthlyReadyContent(context, loaded.value)
            }
        }
    }

    /** Ready-state money tree, reused verbatim by the session and one-shot composers. */
    @Composable
    internal fun MonthlyReadyContent(context: Context, s: WidgetDataLoader.MonthlySnapshot) {
        val strings = context.widgetLocaleContext(s.localeTag)
        Column(GlanceModifier.fillMaxSize()) {
            Text(strings.getString(R.string.widget_monthly_title, s.yearMonth), style = TextStyle(color = ColorProvider(WidgetColors.Muted), fontSize = 11.sp))
            Spacer(GlanceModifier.height(4.dp))
            Text(widgetMoneyText(s.netFlow, s.localeTag), style = TextStyle(
                color = ColorProvider(WidgetColors.Ink), fontSize = 22.sp, fontWeight = FontWeight.Bold))
            Spacer(GlanceModifier.height(8.dp))
            WidgetLine(strings.getString(R.string.widget_monthly_income), widgetMoneyText(s.income, s.localeTag))
            WidgetLine(strings.getString(R.string.widget_monthly_expense), widgetMoneyText(s.expense, s.localeTag))
            val position = s.declaredPosition
            if (position != null) {
                Text(strings.getString(R.string.widget_declared_position, position.asOfDate),
                    style = TextStyle(color = ColorProvider(WidgetColors.Muted), fontSize = 11.sp))
                Text(widgetMoneyText(position.net, s.localeTag),
                    style = TextStyle(color = ColorProvider(WidgetColors.Ink), fontSize = 15.sp))
            }
        }
    }
}

@Composable
private fun WidgetLine(label: String, value: String) {
    Text("$label: $value", style = TextStyle(color = ColorProvider(WidgetColors.Muted), fontSize = 13.sp))
}

class MonthlyWidgetReceiver : GlanceAppWidgetReceiver() {
    override val glanceAppWidget: GlanceAppWidget = MonthlyWidget()
}
