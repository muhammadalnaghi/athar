package com.athar.core.domain.model.financialgraph

import com.athar.core.domain.calc.financial.RecordingDecision
import kotlinx.collections.immutable.toPersistentSet

/**
 * Deterministic result of attempting to build one canonical financial event
 * graph (WM028-11). Every outcome is typed and recoverable: unsupported
 * combinations never return zero, never silently repair, and always retain
 * the complete underlying evidence or decision.
 */
sealed interface FinancialGraphOutcome {
    /** A complete validated graph was produced. */
    data class Created(val graph: FinancialEventGraph) : FinancialGraphOutcome

    /**
     * The observation was policy-eligible but the draft is structurally
     * unsupported; [issues] accumulates every defect (insertion order), so
     * callers see the full picture rather than the first failure.
     */
    class Rejected internal constructor(
        issues: Set<GraphBuildIssue>,
        val source: FinancialGraphDraft,
        val decision: RecordingDecision,
    ) : FinancialGraphOutcome {
        val issues: Set<GraphBuildIssue> = issues.toPersistentSet()
    }

    /**
     * The observation never produces a replacement graph. [reason] explains
     * why and [decision] retains the complete typed policy decision —
     * including the unmodified observation — verbatim.
     */
    data class NotEligible(
        val reason: NoGraphReason,
        val decision: RecordingDecision,
        val source: FinancialGraphDraft,
    ) : FinancialGraphOutcome
}
