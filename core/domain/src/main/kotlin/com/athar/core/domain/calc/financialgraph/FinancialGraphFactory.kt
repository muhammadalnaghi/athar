package com.athar.core.domain.calc.financialgraph

import com.athar.core.domain.calc.financial.FinancialRecordingPolicy
import com.athar.core.domain.calc.financial.RecordingDecision
import com.athar.core.domain.calc.financial.UnresolvedReason
import com.athar.core.domain.model.financialgraph.EconomicIdentity
import com.athar.core.domain.model.financialgraph.FinancialEventGraph
import com.athar.core.domain.model.financialgraph.FinancialGraphDraft
import com.athar.core.domain.model.financialgraph.FinancialGraphOutcome
import com.athar.core.domain.model.financialgraph.GraphBuildIssue
import com.athar.core.domain.model.financialgraph.NoGraphReason

/**
 * Deterministic pure-boundary factory. Never accepts a caller-forged policy
 * decision, generates identities, reads a clock, merges observations or writes
 * history. Every outcome retains raw facts; only actual supported policy money
 * can become a graph. Room atomicity/idempotency remains a separate boundary.
 */
object FinancialGraphFactory {
    fun build(draft: FinancialGraphDraft): FinancialGraphOutcome {
        val decision = FinancialRecordingPolicy.decide(draft.observation)
        val noGraph = noGraphReason(decision)
        if (noGraph != null) return FinancialGraphOutcome.NotEligible(noGraph, decision, draft)
        val booked = when (decision) {
            is RecordingDecision.Recorded -> decision.bookedAmount
            is RecordingDecision.CapturedUnresolvedKind -> decision.bookedAmount
            else -> error("Eligible decision must carry actual posted money")
        }
        val kind = when (decision) {
            is RecordingDecision.Recorded -> decision.economicKind
            is RecordingDecision.CapturedUnresolvedKind -> decision.economicKind
            else -> error("Eligible decision must retain its kind")
        }
        val issues = linkedSetOf<GraphBuildIssue>()
        if (draft.eventId.isBlank()) issues += GraphBuildIssue.BLANK_EVENT_ID
        if (draft.sourceNamespace.isBlank()) issues += GraphBuildIssue.BLANK_SOURCE_NAMESPACE
        val identity = draft.economicIdentity
        if (identity is EconomicIdentity.IssuerReference && (identity.issuer.isBlank() || identity.reference.isBlank())) {
            issues += GraphBuildIssue.INVALID_ECONOMIC_IDENTITY
        }
        if (draft.linkedRepresentedFeeRef != null &&
            (draft.linkedRepresentedFeeRef.isBlank() || draft.linkedRepresentedFeeRef == draft.eventId)
        ) issues += GraphBuildIssue.DUPLICATE_LINKED_FEE_REFERENCE
        val components = GraphComponentValidator.validate(draft, booked, kind, issues)
        val legs = GraphLegValidator.validate(draft, booked, kind, components, issues)
        if (issues.isNotEmpty()) return FinancialGraphOutcome.Rejected(issues, draft, decision)

        val classificationIssues = linkedSetOf<GraphBuildIssue>()
        val category = draft.category?.takeUnless { it.categoryId.isBlank() }
        if (draft.category != null && category == null) classificationIssues += GraphBuildIssue.BLANK_CATEGORY_ID
        return FinancialGraphOutcome.Created(FinancialEventGraph(
            source = draft,
            decision = decision,
            routing = legs.routing,
            composition = components.composition,
            category = category,
            classificationIssues = classificationIssues,
            components = components.components,
            legs = legs.legs,
        ))
    }

    private fun noGraphReason(decision: RecordingDecision): NoGraphReason? = when (decision) {
        is RecordingDecision.Recorded, is RecordingDecision.CapturedUnresolvedKind -> null
        is RecordingDecision.PreservedLegacy -> NoGraphReason.LEGACY_PRESERVED
        is RecordingDecision.NonPosted -> NoGraphReason.NON_POSTED
        is RecordingDecision.InvalidObservation -> NoGraphReason.INVALID_INPUT_MONEY
        is RecordingDecision.Conflict -> NoGraphReason.KIND_DIRECTION_CONFLICT
        is RecordingDecision.Unresolved -> when (decision.reason) {
            UnresolvedReason.UNRELIABLE_POSTING_EVIDENCE -> NoGraphReason.UNRELIABLE_POSTING_EVIDENCE
            UnresolvedReason.UNRELIABLE_AMOUNT_SUPPORT -> NoGraphReason.UNRELIABLE_AMOUNT_SUPPORT
            UnresolvedReason.NO_KNOWN_AMOUNT -> NoGraphReason.NO_KNOWN_AMOUNT
        }
    }
}
