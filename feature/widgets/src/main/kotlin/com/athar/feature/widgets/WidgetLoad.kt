package com.athar.feature.widgets

import kotlinx.coroutines.CancellationException

/**
 * Widget load lifecycle, deliberately three-valued: [Loading] is the transient
 * first composition, [Ready] carries the snapshot, and [Unavailable] is a real
 * data failure. Unknown/failure never collapses into a zero or an empty sum,
 * and never renders as the endless generic brand state.
 */
internal sealed interface WidgetLoad<out T> {
    data object Loading : WidgetLoad<Nothing>
    data class Ready<T>(val value: T) : WidgetLoad<T>
    data object Unavailable : WidgetLoad<Nothing>
}

/**
 * Maps one widget snapshot load into [WidgetLoad]. Cancellation is control
 * flow, not a data failure: it propagates so Glance can cancel cleanly. Any
 * other exception (e.g. the encrypted store failing to open) becomes
 * [WidgetLoad.Unavailable] so the widget renders a distinct, honest failure
 * state instead of silently freezing on the loading mark.
 */
internal suspend fun <T> loadWidgetState(load: suspend () -> T): WidgetLoad<T> =
    try {
        WidgetLoad.Ready(load())
    } catch (e: CancellationException) {
        throw e
    } catch (_: Exception) {
        WidgetLoad.Unavailable
    }
