package com.athar.core.domain.calc.financialgraph

import com.athar.core.common.money.Money
import com.athar.core.domain.model.financial.EconomicKind
import com.athar.core.domain.model.financialgraph.ComponentSpec
import com.athar.core.domain.model.financialgraph.ComponentType
import com.athar.core.domain.model.financialgraph.CompositionResolution
import com.athar.core.domain.model.financialgraph.FeeRelation
import com.athar.core.domain.model.financialgraph.FinancialComponent
import com.athar.core.domain.model.financialgraph.FinancialGraphDraft
import com.athar.core.domain.model.financialgraph.GraphBuildIssue
import java.math.BigDecimal

internal data class ComponentValidation(
    val components: List<FinancialComponent>,
    val composition: CompositionResolution,
    val principal: BigDecimal,
    val includedFee: BigDecimal,
    val additionalFee: BigDecimal,
    val valid: Boolean,
)

internal object GraphComponentValidator {
    fun validate(
        draft: FinancialGraphDraft,
        booked: Money,
        kind: EconomicKind,
        issues: MutableSet<GraphBuildIssue>,
    ): ComponentValidation {
        val local = linkedSetOf<GraphBuildIssue>()
        val items = draft.components
        if (items.any { it.componentRef.isBlank() }) local += GraphBuildIssue.BLANK_COMPONENT_ID
        if (items.map { it.componentRef }.distinct().size != items.size) local += GraphBuildIssue.DUPLICATE_COMPONENT_ID
        for (item in items) validateItem(item, draft.observation.stableId.value, booked, kind, local)
        val feeRefs = items.filter { it.type == ComponentType.FEE }.mapNotNull { it.feeEvidenceRef?.takeIf(String::isNotBlank) }
        if (feeRefs.distinct().size != feeRefs.size) local += GraphBuildIssue.DUPLICATE_FEE_EVIDENCE
        val fees = items.filter { it.type == ComponentType.FEE }
        if (draft.linkedRepresentedFeeRef != null && fees.isNotEmpty()) local += GraphBuildIssue.DUPLICATE_FEE_REPRESENTATION
        if (kind == EconomicKind.REVERSAL && items.filter { it.type != ComponentType.FEE }.map { it.type }.distinct().size > 1) {
            local += GraphBuildIssue.COMPONENT_NOT_ALLOWED_FOR_KIND
        }
        if (local.isNotEmpty()) {
            issues += local
            return unresolved(booked, valid = false)
        }
        if (items.isEmpty()) return unresolved(booked, valid = true)
        if (fees.any { it.feeRelation == FeeRelation.UNKNOWN }) {
            // No certified split/fee total: every raw amount/relation remains on draft.
            return unresolved(booked, valid = true).copy(composition = CompositionResolution.UNRESOLVED_FEE_RELATION)
        }
        val principal = items.filter { it.type != ComponentType.FEE }.sumAmounts()
        val included = fees.filter { it.feeRelation == FeeRelation.INCLUDED }.sumAmounts()
        val additional = fees.filter { it.feeRelation == FeeRelation.ADDITIONAL }.sumAmounts()
        if (principal.add(included).compareTo(booked.amount) != 0 ||
            (kind == EconomicKind.FEE && additional.signum() != 0)
        ) {
            issues += GraphBuildIssue.COMPOSITION_CONFLICT
            return unresolved(booked, valid = false)
        }
        return ComponentValidation(
            items.map { FinancialComponent(it.componentRef, it.type, it.amount, it.feeRelation, it.feeEvidenceRef) },
            CompositionResolution.RESOLVED, principal, included, additional, true,
        )
    }

    private fun validateItem(
        item: ComponentSpec,
        observationId: String,
        booked: Money,
        kind: EconomicKind,
        issues: MutableSet<GraphBuildIssue>,
    ) {
        if (!item.support.isExplicit) issues += GraphBuildIssue.UNSUPPORTED_COMPONENT_AMOUNT
        if (item.evidenceRef.isNullOrBlank()) issues += GraphBuildIssue.COMPONENT_EVIDENCE_REQUIRED
        validateGraphMoney(item.amount, observationId, GraphMoneyRole.COMPONENT, issues)
        if (item.amount.currency != booked.currency) issues += GraphBuildIssue.COMPONENT_CURRENCY_MISMATCH
        if (item.type != ComponentType.FEE && (item.feeRelation != null || item.feeEvidenceRef != null)) {
            issues += GraphBuildIssue.FEE_RELATION_FORBIDDEN
        }
        if (item.type == ComponentType.FEE) {
            if (item.feeRelation == null) issues += GraphBuildIssue.FEE_RELATION_REQUIRED
            if (item.feeRelation != FeeRelation.UNKNOWN && item.feeEvidenceRef.isNullOrBlank()) {
                issues += GraphBuildIssue.FEE_EVIDENCE_REQUIRED
            }
        }
        val allowed = when (item.type) {
            ComponentType.PRINCIPAL -> kind != EconomicKind.REFUND && kind != EconomicKind.FEE
            ComponentType.REFUND -> kind == EconomicKind.REFUND || kind == EconomicKind.REVERSAL
            ComponentType.FEE -> kind in setOf(EconomicKind.PURCHASE, EconomicKind.FEE,
                EconomicKind.OWN_TRANSFER, EconomicKind.CARD_REPAYMENT, EconomicKind.UNRESOLVED_POSTED_MOVEMENT)
        }
        if (!allowed) issues += GraphBuildIssue.COMPONENT_NOT_ALLOWED_FOR_KIND
    }

    private fun unresolved(booked: Money, valid: Boolean) = ComponentValidation(
        emptyList(), CompositionResolution.UNSPECIFIED, booked.amount, BigDecimal.ZERO, BigDecimal.ZERO, valid,
    )

    /** Called only after every item was validated in the exact booked currency. */
    private fun List<ComponentSpec>.sumAmounts(): BigDecimal = fold(BigDecimal.ZERO) { total, item -> total.add(item.amount.amount) }
}
