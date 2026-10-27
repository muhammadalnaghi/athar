package com.athar.feature.widgets

import android.appwidget.AppWidgetManager
import android.content.ComponentName
import android.content.Context
import android.os.Bundle
import androidx.compose.runtime.Composable
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.glance.GlanceTheme
import androidx.glance.appwidget.ExperimentalGlanceRemoteViewsApi
import androidx.glance.appwidget.GlanceRemoteViews
import com.athar.feature.widgets.monthly.MonthlyWidget
import com.athar.feature.widgets.monthly.MonthlyWidgetReceiver
import com.athar.feature.widgets.today.TodayWidget
import com.athar.feature.widgets.today.TodayWidgetReceiver
import kotlinx.coroutines.CancellationException

/**
 * WM059: strong publication boundary for [updateAtharWidgets].
 *
 * `GlanceAppWidget.updateAll` (pinned glance-appwidget 1.1.1) returns once the
 * session update is scheduled, not once the host holds the new RemoteViews —
 * tearing the process down in that window leaves the host on stale frames
 * after an authenticated restore. This publisher closes that gap for the
 * **Monthly and Today** providers with the pinned public one-shot API:
 * `GlanceRemoteViews.compose` runs the same RemoteViewsTranslator pipeline
 * the session path uses, but synchronously in this coroutine, and the
 * resulting RemoteViews is written to each original appWidgetId through
 * [AppWidgetManager.updateAppWidget] — a synchronous binder write that
 * completes before this returns.
 *
 * Pending is excluded by policy: its rows use `actionRunCallback`, whose
 * routing is not guaranteed from one-shot frames, so it stays on the original
 * session scheduling path untouched and is reported
 * [WidgetPublicationOutcome.DeferredByPolicy].
 *
 * Failure semantics are fail-closed and last-good: a failed enumeration,
 * load, size resolution, or render publishes nothing for that provider/id
 * (the host keeps its last-good frame; the caller's follow-up `updateAll`
 * renders the honest states exactly as before). Outcomes are reported per
 * provider via [WidgetPublicationTelemetry] — ids and status only, never
 * financial data — and a scheduled-only fallback is never counted as strong
 * publication. Restored ledger data is never touched.
 * [CancellationException][kotlinx.coroutines.CancellationException] propagates.
 */
internal object WidgetSyncPublisher {

    /**
     * Typed per-provider description. Concrete snapshot and render types keep
     * the invocation monomorphic — no star-projected `Spec<*>` erasure and no
     * `WidgetLoad` re-wrap on the publish path.
     */
    private class ProviderSpec<T>(
        val receiverClass: Class<*>,
        val load: suspend (Context) -> T,
        val render: @Composable (Context, T) -> Unit,
    )

    suspend fun publishAtharWidgets(context: Context): WidgetPublicationReport {
        val appContext = context.applicationContext
        val manager = AppWidgetManager.getInstance(appContext)
        val report = WidgetPublicationReport(
            monthly = publishProvider(appContext, manager, monthlySpec()),
            today = publishProvider(appContext, manager, todaySpec()),
            pending = WidgetPublicationOutcome.DeferredByPolicy,
        )
        WidgetPublicationTelemetry.record(report)
        return report
    }

    private fun monthlySpec(): ProviderSpec<WidgetDataLoader.MonthlySnapshot> {
        // Render through the widget instance's own full shared wrapper
        // (background + padding + launch action), pinned to Ready — the exact
        // frame the session path renders, not a naked Ready column fork.
        val widget = MonthlyWidget()
        return ProviderSpec(
            receiverClass = MonthlyWidgetReceiver::class.java,
            load = { c -> WidgetDataLoader.loadMonthlySnapshot(c) },
            render = { c, snapshot -> widget.MonthlyWidgetContent(c, WidgetLoad.Ready(snapshot)) },
        )
    }

    private fun todaySpec(): ProviderSpec<WidgetDataLoader.TodaySnapshot> {
        val widget = TodayWidget()
        return ProviderSpec(
            receiverClass = TodayWidgetReceiver::class.java,
            load = { c -> WidgetDataLoader.loadTodaySnapshot(c) },
            render = { c, snapshot -> widget.TodayWidgetContent(c, WidgetLoad.Ready(snapshot)) },
        )
    }

    private suspend fun <T> publishProvider(
        appContext: Context,
        manager: AppWidgetManager,
        spec: ProviderSpec<T>,
    ): WidgetPublicationOutcome {
        val ids: IntArray = try {
            manager.getAppWidgetIds(ComponentName(appContext, spec.receiverClass))
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            return WidgetPublicationOutcome.EnumerationFailed
        }
        if (ids.isEmpty()) return WidgetPublicationOutcome.NoInstances
        var outcome: WidgetPublicationOutcome = WidgetPublicationOutcome.LoadFailed
        publishStronglyIfReady(
            appWidgetIds = ids,
            load = { spec.load(appContext) },
            publish = { widgetIds, snapshot ->
                outcome = composeAndPublish(appContext, manager, widgetIds, spec, snapshot)
            },
        )
        return outcome
    }

    /**
     * Composes the shared full wrapper once per id (each id may be placed at a
     * different size) and writes it synchronously. Reports every id whose
     * publish completed and every id that failed any step — size
     * resolution included; a failed id keeps its last-good host frame.
     */
    @OptIn(ExperimentalGlanceRemoteViewsApi::class)
    private suspend fun <T> composeAndPublish(
        context: Context,
        manager: AppWidgetManager,
        widgetIds: IntArray,
        spec: ProviderSpec<T>,
        snapshot: T,
    ): WidgetPublicationOutcome {
        val composer = GlanceRemoteViews()
        return publishEachWidgetId(widgetIds) { id ->
            val size = resolveSizeDp(context, manager, id)
                ?: throw IllegalStateException("Widget size unavailable")
            val views = composer.compose(context = context, size = size) {
                GlanceTheme { spec.render(context, snapshot) }
            }.remoteViews
            manager.updateAppWidget(id, views)
        }
    }

    /**
     * Per-id size resolution, honest about the two different units Android
     * exposes here:
     *  - `getAppWidgetOptions` `OPTION_APPWIDGET_MIN_WIDTH/HEIGHT`: already
     *    **dp** (the host updates them on layout);
     *  - `AppWidgetProviderInfo.minWidth/minHeight`: client-converted
     *    **pixels** — divided by `displayMetrics.density`, never `.dp`'d
     *    directly.
     * Neither source available -> null; the caller records a truthful per-id
     * failure instead of guessing a size.
     */
    private fun resolveSizeDp(context: Context, manager: AppWidgetManager, appWidgetId: Int): DpSize? {
        val options: Bundle = try {
            manager.getAppWidgetOptions(appWidgetId)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            Bundle.EMPTY
        }
        val optionsWidth = options.getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_WIDTH, 0)
        val optionsHeight = options.getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_HEIGHT, 0)
        if (optionsWidth > 0 && optionsHeight > 0) return DpSize(optionsWidth.dp, optionsHeight.dp)
        val info = manager.getAppWidgetInfo(appWidgetId) ?: return null
        val density = context.resources.displayMetrics.density
        if (density > 0f && info.minWidth > 0 && info.minHeight > 0) {
            return DpSize((info.minWidth / density).dp, (info.minHeight / density).dp)
        }
        return null
    }
}
