package com.athar.feature.widgets

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.UUID

/** In-process invalidation metadata only. Financial values remain in encrypted Room. */
internal object WidgetRefreshSignal {
    private val token = MutableStateFlow(UUID.randomUUID().toString())
    val revision = token.asStateFlow()
    fun invalidate() { token.value = UUID.randomUUID().toString() }
}
