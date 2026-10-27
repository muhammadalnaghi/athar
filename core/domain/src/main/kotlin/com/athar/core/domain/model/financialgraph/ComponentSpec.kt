package com.athar.core.domain.model.financialgraph

import com.athar.core.common.money.Money
import com.athar.core.domain.model.financial.AmountSupport

/**
 * Economic role of one component amount. Components represent economic
 * amounts, never account legs; signed account movement is modeled by legs.
 */
enum class ComponentType {
    /** The main economic amount of the event (what was bought/earned/refunded). */
    PRINCIPAL,

    /** A fee amount, qualified by [FeeRelation]. */
    FEE,

    /** A refunded amount (WM028-11: unmatched refunds stay valid posted events). */
    REFUND,
}

/**
 * How a fee relates to the booked total of its event (approved design §3).
 */
enum class FeeRelation {
    /** The fee is already inside the booked total: principal + fee = booked. */
    INCLUDED,

    /** The fee is charged beyond the booked principal. */
    ADDITIONAL,

    /**
     * A fee relation is asserted but its composition is unknown. This state is
     * explicitly retainable and must never be conflated with "no fee", never
     * resolved by inference, and never split from uncertain evidence.
     */
    UNKNOWN,
}

/**
 * Caller-supplied specification of one economic component (draft input).
 *
 * Raw input by design: blank refs, zero amounts and forbidden fee relations
 * are constructible so the graph factory can accumulate typed
 * [GraphBuildIssue] values — validation never happens by construction here.
 */
data class ComponentSpec(
    val componentRef: String,
    val type: ComponentType,
    val amount: Money,
    val feeRelation: FeeRelation? = null,
    val feeEvidenceRef: String? = null,
    val support: AmountSupport = AmountSupport.HEURISTIC_ONLY,
    val evidenceRef: String? = null,
)

/** Whether certified components can describe the booking without guessing. */
enum class CompositionResolution { UNSPECIFIED, RESOLVED, UNRESOLVED_FEE_RELATION }
