package com.athar.core.domain.model.financialgraph

import com.athar.core.common.money.Money
import com.athar.core.domain.calc.financial.BookedBasis
import com.athar.core.domain.calc.financial.RecordingDecision
import com.athar.core.domain.model.financial.EconomicKind
import com.athar.core.domain.model.financial.IncomeNature
import kotlinx.collections.immutable.toPersistentList
import kotlinx.collections.immutable.toPersistentSet

/** Certified identifier of one posted canonical financial event. */
@JvmInline
value class FinancialEventId(val value: String) {
    init {
        require(value.isNotBlank()) { "FinancialEventId must not be blank" }
    }
}

/**
 * One certified economic component of a posted graph. Economic amount only —
 * never an account movement; signed movement is [AccountLeg]'s concern.
 */
@ConsistentCopyVisibility
data class FinancialComponent internal constructor(
    val componentRef: String,
    val type: ComponentType,
    val amount: Money,
    /**
     * Stated fee relation. Null only for non-fee components; [FeeRelation.UNKNOWN]
     * is retained as-is and never conflated with "no fee".
     */
    val feeRelation: FeeRelation?,
    val feeEvidenceRef: String?,
)

/**
 * One certified signed-net-worth account leg (WM028-11): positive [delta]
 * increases the account's signed position, negative decreases it. No FX rate
 * or counterpart amount is ever synthesized; each leg carries its actual
 * known currency.
 */
@ConsistentCopyVisibility
data class AccountLeg internal constructor(
    val legRef: String,
    val accountRef: String,
    val delta: Money,
    /** Validated reference to a [FinancialComponent], when attributed. */
    val componentRef: String?,
)

/**
 * Validated posted graph. Construction belongs to the domain factory, not
 * data/UI callers; there is no public constructor or validation-bypassing copy.
 * Raw evidence and actual policy output remain distinct from certified values.
 */
class FinancialEventGraph internal constructor(
    val source: FinancialGraphDraft,
    val decision: RecordingDecision,
    val routing: RoutingAllocation,
    val composition: CompositionResolution,
    val category: CategoryAssignment?,
    classificationIssues: Set<GraphBuildIssue>,
    components: List<FinancialComponent>,
    legs: List<AccountLeg>,
) {
    init {
        require(decision is RecordingDecision.Recorded || decision is RecordingDecision.CapturedUnresolvedKind)
        require(decision.observation == source.observation)
    }

    val eventId = FinancialEventId(source.eventId)
    val observationIdentity = ObservationIdentity(source.sourceNamespace, source.observation.stableId.value)
    val economicIdentity = source.economicIdentity
    val observation get() = source.observation
    val components: List<FinancialComponent> = components.toPersistentList()
    val legs: List<AccountLeg> = legs.toPersistentList()
    val classificationIssues: Set<GraphBuildIssue> = classificationIssues.toPersistentSet()

    val bookedAmount: Money
        get() = when (val value = decision) {
            is RecordingDecision.Recorded -> value.bookedAmount
            is RecordingDecision.CapturedUnresolvedKind -> value.bookedAmount
            else -> error("Non-posted decision cannot construct a graph")
        }

    val bookedBasis: BookedBasis
        get() = when (val value = decision) {
            is RecordingDecision.Recorded -> value.bookedBasis
            is RecordingDecision.CapturedUnresolvedKind -> value.bookedBasis
            else -> error("Non-posted decision cannot construct a graph")
        }

    val kind: EconomicKind
        get() = when (val value = decision) {
            is RecordingDecision.Recorded -> value.economicKind
            is RecordingDecision.CapturedUnresolvedKind -> value.economicKind
            else -> error("Non-posted decision cannot construct a graph")
        }

    val kindResolved: Boolean get() = decision is RecordingDecision.Recorded
    val incomeNature: IncomeNature
        get() = (decision as? RecordingDecision.Recorded)?.incomeNature ?: IncomeNature.UNCLASSIFIED

    val originalAmount: Money?
        get() = when (val value = decision) {
            is RecordingDecision.Recorded -> value.originalAmount
            is RecordingDecision.CapturedUnresolvedKind -> value.originalAmount
            else -> null
        }

    val settlementAmount: Money?
        get() = when (val value = decision) {
            is RecordingDecision.Recorded -> value.settlementAmount
            is RecordingDecision.CapturedUnresolvedKind -> value.settlementAmount
            else -> null
        }
}
