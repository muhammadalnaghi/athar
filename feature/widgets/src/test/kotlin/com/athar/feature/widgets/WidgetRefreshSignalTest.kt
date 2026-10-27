package com.athar.feature.widgets

import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class WidgetRefreshSignalTest {
    @Test fun `invalidation changes only opaque application metadata`() {
        val before = WidgetRefreshSignal.revision.value
        WidgetRefreshSignal.invalidate()
        assertThat(WidgetRefreshSignal.revision.value).isNotEqualTo(before)
        assertThat(WidgetRefreshSignal.revision.value).matches("[0-9a-f-]{36}")
    }
    @Test fun `active composition observer receives each mutation key`() = runTest {
        val seen = mutableListOf<String>()
        val job = launch { WidgetRefreshSignal.revision.take(3).toList(seen) }
        runCurrent();WidgetRefreshSignal.invalidate();runCurrent();WidgetRefreshSignal.invalidate();runCurrent();job.join()
        assertThat(seen).hasSize(3)
        assertThat(seen.toSet()).hasSize(3)
    }
}
