package com.athar.feature.widgets

import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test

/** WM059: strong publication boundary regression proof (pure decision + ordering). */
class WidgetPublication059Test {

    @Test
    fun `ready snapshot publishes to every original widget id after load`() = runTest {
        val published = mutableListOf<Pair<List<Int>, String>>()
        val order = mutableListOf<String>()

        val decision = publishStronglyIfReady(
            appWidgetIds = intArrayOf(3, 9, 1),
            load = { order.add("load"); "S-1" },
            publish = { ids, value -> order.add("publish"); published.add(ids.toList() to value) },
        )

        assertThat(decision).isEqualTo(WidgetPublicationDecision.PublishPerIds(listOf(3, 9, 1)))
        // Load strictly precedes publish: no publish without a completed encrypted read.
        assertThat(order).containsExactly("load", "publish").inOrder()
        assertThat(published).containsExactly(listOf(3, 9, 1) to "S-1")
    }

    @Test
    fun `encrypted source failure defers to glance session without publishing`() = runTest {
        val published = mutableListOf<Pair<List<Int>, String>>()

        val decision = publishStronglyIfReady(
            appWidgetIds = intArrayOf(7),
            load = { throw IllegalStateException("encrypted store unavailable") },
            publish = { ids, value -> published.add(ids.toList() to value) },
        )

        // Fail-closed: last-good host frame is kept; session renders honest Unavailable.
        assertThat(decision).isEqualTo(WidgetPublicationDecision.DeferToGlanceSession)
        assertThat(published).isEmpty()
    }

    @Test
    fun `loading placeholder is never a strong publication payload`() {
        assertThat(widgetPublicationDecision(intArrayOf(1), WidgetLoad.Loading))
            .isEqualTo(WidgetPublicationDecision.DeferToGlanceSession)
    }

    @Test
    fun `unavailable load is deferred not published`() {
        assertThat(widgetPublicationDecision(intArrayOf(1), WidgetLoad.Unavailable))
            .isEqualTo(WidgetPublicationDecision.DeferToGlanceSession)
    }

    @Test
    fun `no host instances means defer without loading or publishing`() = runTest {
        val order = mutableListOf<String>()

        val decision = publishStronglyIfReady(
            appWidgetIds = IntArray(0),
            load = { order.add("load"); "s" },
            publish = { _, _ -> order.add("publish") },
        )

        assertThat(decision).isEqualTo(WidgetPublicationDecision.DeferToGlanceSession)
        assertThat(order).isEmpty()
    }

    @Test
    fun `publication preserves original widget id identity`() {
        val ids = intArrayOf(41, 2, 7)
        val decision = widgetPublicationDecision(ids, WidgetLoad.Ready("snapshot"))
        assertThat(decision).isEqualTo(WidgetPublicationDecision.PublishPerIds(listOf(41, 2, 7)))
        assertThat((decision as WidgetPublicationDecision.PublishPerIds).appWidgetIds)
            .containsExactly(41, 2, 7).inOrder()
    }

    @Test
    fun `typed report distinguishes per-provider outcomes without hiding failures`() {
        val report = WidgetPublicationReport(
            monthly = WidgetPublicationOutcome.FullyPublished(listOf(4)),
            today = WidgetPublicationOutcome.PublishedWithFailures(publishedAppWidgetIds = listOf(5), failedAppWidgetIds = listOf(6)),
            pending = WidgetPublicationOutcome.DeferredByPolicy,
        )

        WidgetPublicationTelemetry.record(report)
        val observed = requireNotNull(WidgetPublicationTelemetry.lastReport.value)

        assertThat(observed.monthly).isEqualTo(WidgetPublicationOutcome.FullyPublished(listOf(4)))
        // One id's success never hides another id's failure within a provider.
        assertThat(observed.today).isEqualTo(
            WidgetPublicationOutcome.PublishedWithFailures(listOf(5), listOf(6)),
        )
        // Pending is deferred by policy, never forged as published.
        assertThat(observed.pending).isEqualTo(WidgetPublicationOutcome.DeferredByPolicy)
    }
}
