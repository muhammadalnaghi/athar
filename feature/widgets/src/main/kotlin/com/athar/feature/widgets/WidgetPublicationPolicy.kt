package com.athar.feature.widgets

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * WM059: decision core for the strong (synchronous) widget publication path.
 * Pure Kotlin so the publication contract is JVM-testable without Android types.
 *
 * Rules, in order:
 *  - no host instances (empty [appWidgetIds]) -> defer to the Glance session update;
 *  - anything but a [WidgetLoad.Ready] snapshot (load failure mapped by
 *    [loadWidgetState], or a still-loading placeholder) -> defer. Fail-closed: a
 *    failed encrypted read must never publish fabricated or stale-by-pretense
 *    money, and `Loading` must never be the final strong-path frame;
 *  - otherwise publish the preloaded snapshot to every original widget id.
 */
internal sealed interface WidgetPublicationDecision {
    data class PublishPerIds(val appWidgetIds: List<Int>) : WidgetPublicationDecision
    data object DeferToGlanceSession : WidgetPublicationDecision
}

internal fun widgetPublicationDecision(
    appWidgetIds: IntArray,
    load: WidgetLoad<*>,
): WidgetPublicationDecision = when {
    appWidgetIds.isEmpty() -> WidgetPublicationDecision.DeferToGlanceSession
    load !is WidgetLoad.Ready<*> -> WidgetPublicationDecision.DeferToGlanceSession
    else -> WidgetPublicationDecision.PublishPerIds(appWidgetIds.toList())
}

/**
 * Loads (fail-closed), decides, then hands the preloaded ready snapshot to
 * [publish] — the **raw** snapshot loader is wrapped by [loadWidgetState]
 * exactly once, load always happens before any publish, and publish only ever
 * receives the unwrapped snapshot value, so a placeholder can never be
 * published as the final strong-path money frame.
 */
internal suspend fun <T> publishStronglyIfReady(
    appWidgetIds: IntArray,
    load: suspend () -> T,
    publish: suspend (IntArray, T) -> Unit,
): WidgetPublicationDecision {
    if (appWidgetIds.isEmpty()) return WidgetPublicationDecision.DeferToGlanceSession
    return when (val loaded = loadWidgetState(load)) {
        is WidgetLoad.Ready -> {
            publish(appWidgetIds, loaded.value)
            WidgetPublicationDecision.PublishPerIds(appWidgetIds.toList())
        }
        WidgetLoad.Loading, WidgetLoad.Unavailable -> WidgetPublicationDecision.DeferToGlanceSession
    }
}


/** Actual per-id publication core: keep every failure, continue other ids, propagate cancellation. */
internal suspend fun publishEachWidgetId(
    ids: IntArray,
    publish: suspend (Int) -> Unit,
): WidgetPublicationOutcome {
    if (ids.isEmpty()) return WidgetPublicationOutcome.NoInstances
    val published = mutableListOf<Int>()
    val failed = mutableListOf<Int>()
    for (id in ids) {
        try {
            publish(id)
            published += id
        } catch (cancelled: kotlinx.coroutines.CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            failed += id
        }
    }
    return when {
        published.isEmpty() -> WidgetPublicationOutcome.RenderFailed(failed.toList())
        failed.isEmpty() -> WidgetPublicationOutcome.FullyPublished(published.toList())
        else -> WidgetPublicationOutcome.PublishedWithFailures(published.toList(), failed.toList())
    }
}


/** A previously started refresh must finish before a committed-restore pass publishes. */
internal class WidgetPublicationGate {
    private val mutex = Mutex()
    suspend fun publish(block: suspend () -> Unit) = mutex.withLock { block() }
}

/** A successful one-shot frame must not be replaced by another asynchronous session render. */
internal fun needsWidgetSessionFallback(outcome: WidgetPublicationOutcome): Boolean =
    outcome !is WidgetPublicationOutcome.FullyPublished && outcome != WidgetPublicationOutcome.NoInstances
