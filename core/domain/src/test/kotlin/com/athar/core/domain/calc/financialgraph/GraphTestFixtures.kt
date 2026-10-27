package com.athar.core.domain.calc.financialgraph

import com.athar.core.common.money.Money
import com.athar.core.domain.model.financial.*
import com.athar.core.domain.model.financialgraph.*
import com.google.common.truth.Truth.assertThat

/** Synthetic explicit evidence only; never replaces the production policy. */
internal object GraphTestFixtures {
    fun observation(
        amount: String = "100.00",
        currency: String = "SAR",
        kind: EconomicKind? = EconomicKind.PURCHASE,
        direction: MovementDirection? = MovementDirection.OUT,
    ) = FinancialObservation(
        stableId = StableObservationId("observation-1"),
        posting = PostingEvidence(PostingState.POSTED, PostingSupport.EXPLICIT_POSTED),
        economicKind = kind,
        direction = direction,
        original = SupportedMoney(Money.of(amount, currency), AmountSupport.EXPLICIT_BOOKED),
        settlement = null,
        routing = RoutingCoverage.Unresolved,
        eventDate = null,
        legacy = null,
        userDeclaration = null,
    )

    fun component(
        id: String = "principal",
        amount: String = "100.00",
        currency: String = "SAR",
        type: ComponentType = ComponentType.PRINCIPAL,
        relation: FeeRelation? = null,
        feeEvidence: String? = null,
    ) = ComponentSpec(
        id, type, Money.of(amount, currency), relation, feeEvidence,
        support = AmountSupport.EXPLICIT_TRANSACTION,
        evidenceRef = "source:$id:amount",
    )

    fun leg(
        id: String = "bank-leg",
        account: String = "bank",
        amount: String = "-100.00",
        currency: String = "SAR",
        component: String? = null,
    ) = LegSpec(
        id, account, Money.of(amount, currency), component,
        support = AmountSupport.EXPLICIT_BOOKED,
        evidenceRef = "source:$id:amount",
    )

    fun draft(
        observation: FinancialObservation = observation(),
        components: List<ComponentSpec> = emptyList(),
        legs: List<LegSpec> = emptyList(),
        category: CategoryAssignment? = null,
    ) = FinancialGraphDraft("event-1", observation, "sms", components = components, legs = legs, category = category)

    fun created(draft: FinancialGraphDraft): FinancialEventGraph {
        val result = FinancialGraphFactory.build(draft)
        assertThat(result).isInstanceOf(FinancialGraphOutcome.Created::class.java)
        return (result as FinancialGraphOutcome.Created).graph
    }

    fun rejected(draft: FinancialGraphDraft): FinancialGraphOutcome.Rejected {
        val result = FinancialGraphFactory.build(draft)
        assertThat(result).isInstanceOf(FinancialGraphOutcome.Rejected::class.java)
        return result as FinancialGraphOutcome.Rejected
    }
}
