package com.athar.feature.widgets.pending

import android.content.Context
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.unit.dp
import androidx.glance.GlanceId
import androidx.glance.GlanceModifier
import androidx.glance.GlanceTheme
import androidx.glance.action.Action
import androidx.glance.action.actionParametersOf
import androidx.glance.action.actionStartActivity
import androidx.glance.action.clickable
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.GlanceAppWidgetReceiver
import androidx.glance.appwidget.action.actionRunCallback
import androidx.glance.appwidget.provideContent
import androidx.glance.background
import androidx.glance.layout.Box
import androidx.glance.layout.Column
import androidx.glance.layout.Row
import androidx.glance.layout.Spacer
import androidx.glance.layout.fillMaxSize
import androidx.glance.layout.fillMaxWidth
import androidx.glance.layout.height
import androidx.glance.layout.padding
import androidx.glance.layout.width
import androidx.glance.text.FontWeight
import androidx.glance.text.Text
import androidx.glance.text.TextStyle
import androidx.glance.unit.ColorProvider
import com.athar.core.domain.model.Transaction
import com.athar.feature.widgets.WidgetLoad
import com.athar.feature.widgets.WidgetRefreshSignal
import com.athar.feature.widgets.loadWidgetState
import com.athar.feature.widgets.R
import com.athar.feature.widgets.WidgetColors
import com.athar.feature.widgets.WidgetDataLoader
import com.athar.feature.widgets.appLaunchComponent
import com.athar.feature.widgets.widgetLocaleContext

class PendingWidget : GlanceAppWidget() {

    override suspend fun provideGlance(context: Context, id: GlanceId) {
        provideContent {
            GlanceTheme {
                PendingContent(context)
            }
        }
    }

    @Composable
    private fun PendingContent(context: Context) {
        val refreshToken by WidgetRefreshSignal.revision.collectAsState()
        val snapshot by produceState<WidgetLoad<WidgetDataLoader.PendingSnapshot>>(WidgetLoad.Loading, refreshToken) {
            value = loadWidgetState { WidgetDataLoader.loadPendingHead(context, max = 3) }
        }
        Box(
            modifier = GlanceModifier
                .fillMaxSize()
                .background(WidgetColors.Parchment)
                .padding(12.dp),
        ) {
            val s = (snapshot as? WidgetLoad.Ready)?.value
            if (s == null) {
                Text(
                    text = context.getString(if (snapshot == WidgetLoad.Unavailable) R.string.widget_unavailable else R.string.widget_loading),
                    style = TextStyle(color = ColorProvider(WidgetColors.Muted)),
                    modifier = GlanceModifier.clickable(actionStartActivity(appLaunchComponent(context))),
                )
            } else {
                val strings = context.widgetLocaleContext(s.localeTag)
                Column(modifier = GlanceModifier.fillMaxSize()) {
                    Text(
                        text = strings.getString(R.string.widget_pending_count, s.totalCount),
                        style = TextStyle(
                            color = if (s.totalCount > 0) ColorProvider(WidgetColors.Ember) else ColorProvider(WidgetColors.Muted),
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Medium,
                        ),
                        modifier = GlanceModifier.clickable(actionStartActivity(appLaunchComponent(context))),
                    )
                    Spacer(modifier = GlanceModifier.height(8.dp))
                    if (s.head.isEmpty()) {
                        Text(
                            text = strings.getString(R.string.widget_pending_empty),
                            style = TextStyle(
                                color = ColorProvider(WidgetColors.Muted),
                                fontSize = 11.sp,
                            ),
                            modifier = GlanceModifier.clickable(actionStartActivity(appLaunchComponent(context))),
                        )
                    } else {
                        s.head.forEach { tx ->
                            PendingRow(context, strings, tx)
                        }
                        if (s.totalCount > s.head.size) {
                            Spacer(modifier = GlanceModifier.height(4.dp))
                            Text(
                                text = strings.getString(R.string.widget_pending_more, s.totalCount - s.head.size),
                                style = TextStyle(
                                    color = ColorProvider(WidgetColors.Muted),
                                    fontSize = 11.sp,
                                ),
                                modifier = GlanceModifier.clickable(actionStartActivity(appLaunchComponent(context))),
                            )
                        }
                    }
                }
            }
        }
    }

    @Composable
    private fun PendingRow(context: Context, strings: Context, tx: Transaction) {
        Column(modifier = GlanceModifier.fillMaxWidth().padding(vertical = 3.dp)) {
            Row(modifier = GlanceModifier.fillMaxWidth()) {
                Text(
                    text = tx.merchant.ifBlank { "?" },
                    style = TextStyle(
                        color = ColorProvider(WidgetColors.Ink),
                        fontSize = 13.sp,
                    ),
                    modifier = GlanceModifier
                        .defaultWeight()
                        .clickable(actionStartActivity(appLaunchComponent(context))),
                )
                Text(
                    text = "${tx.amount.amount.toPlainString()} ${tx.amount.currency}",
                    style = TextStyle(
                        color = ColorProvider(WidgetColors.Ember),
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Medium,
                    ),
                )
            }
            Spacer(modifier = GlanceModifier.height(3.dp))
            Row(modifier = GlanceModifier.fillMaxWidth()) {
                ActionText(
                    text = strings.getString(R.string.widget_pending_confirm),
                    color = WidgetColors.Olive,
                    action = actionRunCallback<ConfirmPendingAction>(
                        actionParametersOf(PendingWidgetActionParams.TransactionId to tx.id),
                    ),
                )
                Spacer(modifier = GlanceModifier.width(6.dp))
                ActionText(
                    text = strings.getString(R.string.widget_pending_dismiss),
                    color = WidgetColors.Ember,
                    action = actionRunCallback<DismissPendingAction>(
                        actionParametersOf(PendingWidgetActionParams.TransactionId to tx.id),
                    ),
                )
                Spacer(modifier = GlanceModifier.width(6.dp))
                ActionText(
                    text = strings.getString(R.string.widget_pending_categorize),
                    color = WidgetColors.Ink,
                    action = actionStartActivity(appLaunchComponent(context)),
                )
            }
        }
    }

    @Composable
    private fun ActionText(
        text: String,
        color: androidx.compose.ui.graphics.Color,
        action: Action,
    ) {
        Text(
            text = text,
            style = TextStyle(
                color = ColorProvider(color),
                fontSize = 10.sp,
                fontWeight = FontWeight.Medium,
            ),
            modifier = GlanceModifier
                .background(WidgetColors.Surface)
                .padding(horizontal = 5.dp, vertical = 3.dp)
                .clickable(action),
        )
    }
}

class PendingWidgetReceiver : GlanceAppWidgetReceiver() {
    override val glanceAppWidget: GlanceAppWidget = PendingWidget()
}

private val Int.sp get() = androidx.compose.ui.unit.TextUnit(this.toFloat(), androidx.compose.ui.unit.TextUnitType.Sp)
