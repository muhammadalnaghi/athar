package com.athar.core.domain.repo

import com.athar.core.domain.model.CatalogRefreshPhase
import com.athar.core.domain.model.MerchantCatalogEntry
import com.athar.core.domain.model.MerchantCatalogSnapshot
import com.athar.core.domain.model.MerchantCatalogState
import kotlinx.coroutines.flow.StateFlow

/**
 * Domain contract for the opt-in signed shared merchant catalog (ENH-009).
 *
 * Nonfinancial by construction: the implementation stores only signed public
 * envelopes (AtomicFile) and nonfinancial opt-in/timing metadata (DataStore).
 * No merchant strings from this device are ever uploaded — refresh is a fixed
 * whole-catalog GET or nothing.
 *
 * Contract notes:
 * - [initialize] is idempotent; [entries] awaits/reuses it so cold-start
 *   consumers never miss cached knowledge.
 * - [refresh] requires actual opt-in and network capability regardless of
 *   [force]; force only bypasses the staleness window and failure backoff.
 * - Disabling updates cancels pending work and prevents stale responses from
 *   activating, while an already-verified library stays usable offline.
 * - [resetToBundled] clears caches and entries but preserves the durable
 *   highest-accepted version so the identical verified catalog can be
 *   reacquired later; personal rules and history are never touched.
 */
interface MerchantCatalogRepository {
    val state: StateFlow<MerchantCatalogState>

    suspend fun initialize()

    suspend fun setUpdatesEnabled(enabled: Boolean)

    suspend fun refresh(force: Boolean = false)

    suspend fun resetToBundled()

    /** A lookup linearizes here; completed reset makes subsequent reads null. */
    suspend fun snapshot(): MerchantCatalogSnapshot?

    /** Compatibility accessor derived only from the coherent snapshot. */
    suspend fun entries(): List<MerchantCatalogEntry> = snapshot()?.entries.orEmpty()
}

/** Convenience accessor mirroring [CatalogRefreshPhase] for eager callers. */
val MerchantCatalogRepository.phase: CatalogRefreshPhase
    get() = state.value.phase
