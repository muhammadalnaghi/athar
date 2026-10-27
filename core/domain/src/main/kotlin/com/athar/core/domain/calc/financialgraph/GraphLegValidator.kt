package com.athar.core.domain.calc.financialgraph

import com.athar.core.common.money.Money
import com.athar.core.domain.model.financial.AmountSupport
import com.athar.core.domain.model.financial.EconomicKind
import com.athar.core.domain.model.financial.MovementDirection
import com.athar.core.domain.model.financialgraph.AccountLeg
import com.athar.core.domain.model.financialgraph.FinancialGraphDraft
import com.athar.core.domain.model.financialgraph.GraphBuildIssue
import com.athar.core.domain.model.financialgraph.LegSpec
import com.athar.core.domain.model.financialgraph.RoutingAllocation
import java.math.BigDecimal

internal data class LegValidation(val legs: List<AccountLeg>, val routing: RoutingAllocation)

internal object GraphLegValidator {
    fun validate(
        draft: FinancialGraphDraft,
        booked: Money,
        kind: EconomicKind,
        composition: ComponentValidation,
        issues: MutableSet<GraphBuildIssue>,
    ): LegValidation {
        val local = linkedSetOf<GraphBuildIssue>()
        val items = draft.legs
        if (items.any { it.legRef.isBlank() }) local += GraphBuildIssue.BLANK_LEG_ID
        if (items.map { it.legRef }.distinct().size != items.size) local += GraphBuildIssue.DUPLICATE_LEG_ID
        val rawComponents = draft.components.associateBy { it.componentRef }
        for (item in items) {
            if (item.accountRef.isBlank()) local += GraphBuildIssue.BLANK_ACCOUNT_REF
            if (!item.support.isExplicit) local += GraphBuildIssue.UNSUPPORTED_LEG_AMOUNT
            if (item.evidenceRef.isNullOrBlank()) local += GraphBuildIssue.LEG_EVIDENCE_REQUIRED
            validateGraphMoney(item.delta, draft.observation.stableId.value, GraphMoneyRole.LEG, local)
            if (item.componentRef != null) {
                val component = rawComponents[item.componentRef]
                if (component == null) local += GraphBuildIssue.UNKNOWN_LEG_COMPONENT_REFERENCE
                else if (component.amount.currency != item.delta.currency) local += GraphBuildIssue.LEG_COMPONENT_CURRENCY_MISMATCH
            }
        }
        if (local.isNotEmpty() || !composition.valid) {
            issues += local
            return LegValidation(emptyList(), RoutingAllocation.UNALLOCATED)
        }
        if (items.isEmpty()) return LegValidation(emptyList(), RoutingAllocation.UNALLOCATED)
        validateReferencedCapacity(items, composition, local)
        // New borrowing creates an asset and a negative liability, not earned wealth.
        val paired = when (kind) {
            EconomicKind.OWN_TRANSFER, EconomicKind.CARD_REPAYMENT, EconomicKind.BORROWING -> true
            else -> false
        }
        val expected = expectedDirection(kind, draft.observation.direction)
        if (!paired && expected != null && items.any { it.delta.amount.signum() != expected }) {
            local += GraphBuildIssue.LEG_DIRECTION_CONFLICT
        }
        val sameCurrency = items.filter { it.delta.currency == booked.currency }
        val foreign = items.filter { it.delta.currency != booked.currency }
        val outgoing = sameCurrency.sideTotal(-1)
        val incoming = sameCurrency.sideTotal(1)
        val outCapacity = booked.amount.add(composition.additionalFee)
        val inCapacity = if (paired) composition.principal else booked.amount
        if (outgoing > outCapacity || incoming > inCapacity) local += GraphBuildIssue.LEG_OVERSHOOT
        if (paired) {
            val outAccounts = items.filter { it.delta.amount.signum() < 0 }.map { it.accountRef }.toSet()
            val inAccounts = items.filter { it.delta.amount.signum() > 0 }.map { it.accountRef }.toSet()
            if (outAccounts.intersect(inAccounts).isNotEmpty()) local += GraphBuildIssue.CONFLICTING_LEG_ACCOUNTS
            if (foreign.isEmpty() && outgoing.signum() > 0 && incoming.signum() > 0) {
                val difference = outgoing.subtract(incoming)
                val fees = composition.includedFee.add(composition.additionalFee)
                if (difference.signum() < 0 || difference > fees) local += GraphBuildIssue.CONFLICTING_LEG_AMOUNTS
            }
        }
        issues += local
        val certifiedRefs = composition.components.map { it.componentRef }.toSet()
        val certified = items.map {
            // Unresolved raw component attribution is not a certified split.
            AccountLeg(it.legRef, it.accountRef, it.delta, it.componentRef?.takeIf(certifiedRefs::contains))
        }
        val routing = when {
            items.any { it.componentRef != null && it.componentRef !in certifiedRefs } -> RoutingAllocation.PARTIAL
            foreign.isNotEmpty() -> foreignRouting(items, foreign, outgoing, incoming, outCapacity, inCapacity, paired)
            paired && outgoing.compareTo(outCapacity) == 0 && incoming.compareTo(inCapacity) == 0 -> RoutingAllocation.ROUTED
            paired -> RoutingAllocation.PARTIAL
            expected == -1 && outgoing.compareTo(outCapacity) == 0 -> RoutingAllocation.ROUTED
            expected == 1 && incoming.compareTo(inCapacity) == 0 -> RoutingAllocation.ROUTED
            expected == null && ((outgoing.compareTo(outCapacity) == 0 && incoming.signum() == 0) ||
                (incoming.compareTo(inCapacity) == 0 && outgoing.signum() == 0)) -> RoutingAllocation.ROUTED
            else -> RoutingAllocation.PARTIAL
        }
        return LegValidation(certified, routing)
    }

    private fun validateReferencedCapacity(
        items: List<LegSpec>,
        composition: ComponentValidation,
        issues: MutableSet<GraphBuildIssue>,
    ) {
        for (component in composition.components) {
            val attributed = items.filter { it.componentRef == component.componentRef }
            // A transfer's two opposite legs can each cover principal; never sum their absolute amounts.
            if (attributed.sideTotal(-1) > component.amount.amount || attributed.sideTotal(1) > component.amount.amount) {
                issues += GraphBuildIssue.LEG_OVERSHOOT
            }
        }
    }

    private fun foreignRouting(
        all: List<LegSpec>,
        foreign: List<LegSpec>,
        outgoing: BigDecimal,
        incoming: BigDecimal,
        outCapacity: BigDecimal,
        inCapacity: BigDecimal,
        paired: Boolean,
    ): RoutingAllocation {
        // Independently explicit full booked legs can establish known sides, not an exchange rate or current valuation.
        if (!paired || all.any { it.support != AmountSupport.EXPLICIT_BOOKED }) return RoutingAllocation.PARTIAL
        val knownOut = outgoing.compareTo(outCapacity) == 0 && incoming.signum() == 0 && foreign.all { it.delta.isPositive() }
        val knownIn = incoming.compareTo(inCapacity) == 0 && outgoing.signum() == 0 && foreign.all { it.delta.isNegative() }
        return if (knownOut || knownIn) RoutingAllocation.ROUTED else RoutingAllocation.PARTIAL
    }

    private fun expectedDirection(kind: EconomicKind, observed: MovementDirection?): Int? = when (kind) {
        EconomicKind.PURCHASE, EconomicKind.FEE -> -1
        EconomicKind.INCOME, EconomicKind.BORROWING, EconomicKind.REFUND -> 1
        else -> when (observed) { MovementDirection.IN -> 1; MovementDirection.OUT -> -1; null -> null }
    }

    /** Call only within a single already-validated currency/component group. */
    private fun List<LegSpec>.sideTotal(sign: Int): BigDecimal = filter { it.delta.amount.signum() == sign }
        .fold(BigDecimal.ZERO) { total, item -> total.add(item.delta.amount.abs()) }
}
