package com.athar.feature.widgets

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Per-provider outcome of one WM059 strong publication attempt. Widget ids
 * and status only — never financial data. Outcomes are disjoint so a
 * scheduled-only fallback, a partial write, or a failed read can never be
 * mistaken for strong publication, and one id's success can never hide
 * another id's failure.
 */
sealed interface WidgetPublicationOutcome {
    /** Every enumerated id received the synchronous AppWidgetManager write. */
    data class FullyPublished(val appWidgetIds: List<Int>) : WidgetPublicationOutcome

    /** Some ids published; [failedAppWidgetIds] keep their last-good host frame. */
    data class PublishedWithFailures(
        val publishedAppWidgetIds: List<Int>,
        val failedAppWidgetIds: List<Int>,
    ) : WidgetPublicationOutcome

    /** Snapshot load failed before composition: nothing published, last-good kept. */
    data object LoadFailed : WidgetPublicationOutcome

    /** Every composition/write failed: nothing published, last-good kept. */
    data class RenderFailed(val failedAppWidgetIds: List<Int>) : WidgetPublicationOutcome

    /** AppWidgetManager id enumeration threw: nothing attempted for this provider. */
    data object EnumerationFailed : WidgetPublicationOutcome

    /** No host instances of this provider: nothing to publish. */
    data object NoInstances : WidgetPublicationOutcome

    /**
     * Deferred by policy to the Glance session path (Pending): its actions use
     * `actionRunCallback`, whose routing from one-shot frames is not
     * guaranteed, so its callback identity is never forged here.
     */
    data object DeferredByPolicy : WidgetPublicationOutcome
}

/** Typed synchronous result of one WM059 strong publication pass. */
data class WidgetPublicationReport(
    val monthly: WidgetPublicationOutcome,
    val today: WidgetPublicationOutcome,
    val pending: WidgetPublicationOutcome,
)

/**
 * Bounded, in-memory publication telemetry, observable by instrumentation
 * (device QA). A provider reports [WidgetPublicationOutcome.FullyPublished]
 * only after its synchronous [android.appwidget.AppWidgetManager] write
 * returned for those exact ids; deferred/failed providers keep their
 * last-good host frame, and restored ledger data is never rolled back by a
 * publication failure.
 */
object WidgetPublicationTelemetry {
    private val _lastReport = MutableStateFlow<WidgetPublicationReport?>(null)
    val lastReport: StateFlow<WidgetPublicationReport?> = _lastReport.asStateFlow()

    internal fun record(report: WidgetPublicationReport) {
        _lastReport.value = report
    }
}
