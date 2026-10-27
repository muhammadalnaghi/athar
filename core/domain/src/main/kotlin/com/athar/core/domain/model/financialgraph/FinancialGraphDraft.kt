package com.athar.core.domain.model.financialgraph

import com.athar.core.domain.model.financial.FinancialObservation
import kotlinx.collections.immutable.toPersistentList

/**
 * Caller-supplied intent to post one canonical financial event graph for a
 * [FinancialObservation] that already passed the recording policy (WM028-11).
 *
 * Draft facts are raw and unvalidated: the factory, not this type, decides
 * eligibility, composition and structure and returns typed outcomes. The
 * [components] and [legs] collections are defensively copied at construction,
 * so a later caller mutation of the original lists can never change what the
 * factory validates.
 */
class FinancialGraphDraft(
    /** Raw caller-supplied event id; blank is flagged, never repaired. */
    val eventId: String,
    val observation: FinancialObservation,
    /** Source namespace used to derive the graph's [ObservationIdentity]. */
    val sourceNamespace: String,
    /** Optional proven cross-source economic identity (never inferred). */
    val economicIdentity: EconomicIdentity? = null,
    components: List<ComponentSpec> = emptyList(),
    legs: List<LegSpec> = emptyList(),
    /** Independent optional classification metadata; null is a normal state. */
    val category: CategoryAssignment? = null,
    /**
     * Reference to another event that already represents this event's fee, so
     * the fee is never charged twice. Blank is flagged, never repaired.
     */
    val linkedRepresentedFeeRef: String? = null,
) {
    val components: List<ComponentSpec> = components.toPersistentList()
    val legs: List<LegSpec> = legs.toPersistentList()
}
