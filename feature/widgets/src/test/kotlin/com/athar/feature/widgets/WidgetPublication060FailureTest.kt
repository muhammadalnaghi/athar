package com.athar.feature.widgets

import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

class WidgetPublication060FailureTest {
    @Test
    fun `all failed widget ids remain observable`() = runTest {
        val outcome = publishEachWidgetId(intArrayOf(7, 3)) {
            throw IllegalStateException("synthetic render failure")
        }
        assertThat(outcome).isEqualTo(WidgetPublicationOutcome.RenderFailed(listOf(7, 3)))
    }

    @Test
    fun `failed first id does not hide or prevent later publications`() = runTest {
        val visited = mutableListOf<Int>()
        val outcome = publishEachWidgetId(intArrayOf(7, 3, 8)) { id ->
            visited += id
            if (id == 7) throw IllegalStateException("synthetic binder failure")
        }
        assertThat(visited).containsExactly(7, 3, 8).inOrder()
        assertThat(outcome).isEqualTo(WidgetPublicationOutcome.PublishedWithFailures(listOf(3, 8), listOf(7)))
    }

    @Test
    fun `load cancellation propagates without publication`() {
        var published = false
        assertThrows<CancellationException> {
            runTest {
                publishStronglyIfReady(
                    appWidgetIds = intArrayOf(4),
                    load = { throw CancellationException("synthetic cancellation") },
                    publish = { _, _ -> published = true },
                )
            }
        }
        assertThat(published).isFalse()
    }

    @Test
    fun `publication cancellation stops remaining ids and is not a failed result`() {
        val visited = mutableListOf<Int>()
        assertThrows<CancellationException> {
            runTest {
                publishEachWidgetId(intArrayOf(1, 2, 3)) { id ->
                    visited += id
                    if (id == 2) throw CancellationException("synthetic cancellation")
                }
            }
        }
        assertThat(visited).containsExactly(1, 2).inOrder()
    }
}
