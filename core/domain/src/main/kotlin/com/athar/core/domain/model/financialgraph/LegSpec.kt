package com.athar.core.domain.model.financialgraph

import com.athar.core.common.money.Money
import com.athar.core.domain.model.financial.AmountSupport

/**
 * How completely an event's money is routed to accounts. Missing routing
 * never suppresses known posted money and never invents a manual-account leg.
 */
enum class RoutingAllocation {
    /** No account leg covers the booked money. */
    UNALLOCATED,

    /** Some, but not all, of the booked money is covered by legs. */
    PARTIAL,

    /** Legs cover the booked money (per-kind coverage rules). */
    ROUTED,
}

/**
 * Caller-supplied specification of one signed-net-worth account leg (draft
 * input). Positive [delta] increases the account's signed position, negative
 * decreases it: card purchase −200, bank repayment −200 / card +200, card
 * refund +50.
 *
 * Raw input by design: blank refs, zero deltas and dangling [componentRef]
 * values are constructible so the graph factory can accumulate typed
 * [GraphBuildIssue] values — validation never happens by construction here.
 */
data class LegSpec(
    val legRef: String,
    val accountRef: String,
    val delta: Money,
    val componentRef: String? = null,
    val support: AmountSupport = AmountSupport.HEURISTIC_ONLY,
    val evidenceRef: String? = null,
)
