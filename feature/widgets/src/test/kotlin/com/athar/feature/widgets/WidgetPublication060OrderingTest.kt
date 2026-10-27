package com.athar.feature.widgets

import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.ExperimentalCoroutinesApi
import org.junit.jupiter.api.Test

@OptIn(ExperimentalCoroutinesApi::class)
class WidgetPublication060OrderingTest {
    @Test
    fun `fully published provider is not asynchronously redrawn`() {
        assertThat(needsWidgetSessionFallback(WidgetPublicationOutcome.FullyPublished(listOf(4)))).isFalse()
    }

    @Test
    fun `partial and failed providers retain the ordinary recovery path`() {
        assertThat(needsWidgetSessionFallback(WidgetPublicationOutcome.PublishedWithFailures(listOf(4), listOf(5)))).isTrue()
        assertThat(needsWidgetSessionFallback(WidgetPublicationOutcome.LoadFailed)).isTrue()
        assertThat(needsWidgetSessionFallback(WidgetPublicationOutcome.RenderFailed(listOf(4)))).isTrue()
        assertThat(needsWidgetSessionFallback(WidgetPublicationOutcome.EnumerationFailed)).isTrue()
    }

    @Test
    fun `absent providers need no session while callback provider remains deferred`() {
        assertThat(needsWidgetSessionFallback(WidgetPublicationOutcome.NoInstances)).isFalse()
        assertThat(needsWidgetSessionFallback(WidgetPublicationOutcome.DeferredByPolicy)).isTrue()
    }

    @Test
    fun `earlier publication cannot complete after restored publication`() = runTest {
        val gate = WidgetPublicationGate()
        val earlierStarted = CompletableDeferred<Unit>()
        val finishEarlier = CompletableDeferred<Unit>()
        val events = mutableListOf<String>()
        val earlier = launch {
            gate.publish {
                events += "earlier-started"
                earlierStarted.complete(Unit)
                finishEarlier.await()
                events += "earlier-published"
            }
        }
        earlierStarted.await()
        val restored = async {
            gate.publish { events += "restored-published" }
        }
        runCurrent()
        assertThat(events).containsExactly("earlier-started")
        finishEarlier.complete(Unit)
        earlier.join()
        restored.await()
        assertThat(events).containsExactly("earlier-started", "earlier-published", "restored-published").inOrder()
    }
}
