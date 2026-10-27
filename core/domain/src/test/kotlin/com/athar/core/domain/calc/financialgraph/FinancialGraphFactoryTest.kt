package com.athar.core.domain.calc.financialgraph

import com.athar.core.common.money.Money
import com.athar.core.domain.model.financial.AmountSupport
import com.athar.core.domain.model.financial.EconomicKind
import com.athar.core.domain.model.financial.FinancialObservation
import com.athar.core.domain.model.financial.LegacyFacts
import com.athar.core.domain.model.financial.MovementDirection
import com.athar.core.domain.model.financial.PostingEvidence
import com.athar.core.domain.model.financial.PostingState
import com.athar.core.domain.model.financial.PostingSupport
import com.athar.core.domain.model.financial.RoutingCoverage
import com.athar.core.domain.model.financial.StableObservationId
import com.athar.core.domain.model.financial.SupportedMoney
import com.athar.core.domain.model.financialgraph.CategoryAssignment
import com.athar.core.domain.model.financialgraph.CategoryBasis
import com.athar.core.domain.model.financialgraph.ComponentSpec
import com.athar.core.domain.model.financialgraph.ComponentType
import com.athar.core.domain.model.financialgraph.EconomicIdentity
import com.athar.core.domain.model.financialgraph.FeeRelation
import com.athar.core.domain.model.financialgraph.FinancialGraphOutcome
import com.athar.core.domain.model.financialgraph.FinancialEventGraph
import com.athar.core.domain.model.financialgraph.FinancialGraphDraft
import com.athar.core.domain.model.financialgraph.GraphBuildIssue
import com.athar.core.domain.model.financialgraph.LegSpec
import com.google.common.truth.Truth.assertThat
import kotlinx.datetime.LocalDate
import org.junit.jupiter.api.Test

/**
 * Behavior-first tests for the canonical financial event graph factory
 * (WM028-11). Every test drives the ACTUAL production recording policy
 * (WM028-07) — never a hand-built Recorded instance — and hand-derived
 * synthetic arithmetic from the approved financial-events design (sections
 * 2–7): fee composition, signed net-worth legs, independent identities,
 * legacy continuity and typed recoverable rejections.
 *
 * These pure tests do not establish SQLite atomicity, persistence, migration,
 * backup, native behavior or whole-product acceptance.
 */
class FinancialGraphFactoryTest {

    // ---------------------------------------------------------------------
    // Helpers — synthetic, hand-derived cases only; no random values, no clock.
    // ---------------------------------------------------------------------

    private fun observation(
        id: String = "obs-1",
        kind: EconomicKind? = EconomicKind.PURCHASE,
        direction: MovementDirection? = MovementDirection.OUT,
        original: SupportedMoney? = SupportedMoney(Money.of("84.50"), AmountSupport.EXPLICIT_BOOKED),
        settlement: SupportedMoney? = null,
        posting: PostingEvidence = PostingEvidence(PostingState.POSTED, PostingSupport.EXPLICIT_POSTED),
        routing: RoutingCoverage = RoutingCoverage.Unresolved,
        legacy: LegacyFacts? = null,
        declaration: EconomicKind? = null,
    ) = FinancialObservation(
        stableId = StableObservationId(id),
        posting = posting,
        economicKind = kind,
        direction = direction,
        original = original,
        settlement = settlement,
        routing = routing,
        eventDate = LocalDate(2026, 10, 20),
        legacy = legacy,
        userDeclaration = declaration?.let { com.athar.core.domain.model.financial.ExplicitFinancialDeclaration(it) },
    )

    private fun purchaseDraft(
        obs: FinancialObservation = observation(),
        eventId: String = "evt-1",
        components: List<ComponentSpec> = listOf(ComponentSpec("c-principal", ComponentType.PRINCIPAL, Money.of("84.50"))),
        legs: List<LegSpec> = emptyList(),
        category: CategoryAssignment? = null,
        economicIdentity: EconomicIdentity? = null,
        linkedRepresentedFeeRef: String? = null,
        sourceNamespace: String = "sms:AlRajhi",
    ) = FinancialGraphDraft(
        eventId = eventId,
        observation = obs,
        sourceNamespace = sourceNamespace,
        economicIdentity = economicIdentity,
        // These proposed arithmetic fixtures explicitly state observed facts.
        // Raw default/heuristic support is exercised separately, without this helper.
        components = components.map {
            it.copy(support = AmountSupport.EXPLICIT_TRANSACTION, evidenceRef = "fixture:${it.componentRef}:amount")
        },
        legs = legs.map {
            it.copy(support = AmountSupport.EXPLICIT_BOOKED, evidenceRef = "fixture:${it.legRef}:amount")
        },
        category = category,
        linkedRepresentedFeeRef = linkedRepresentedFeeRef,
    )

    private fun created(outcome: FinancialGraphOutcome): FinancialEventGraph {
        assertThat(outcome).isInstanceOf(FinancialGraphOutcome.Created::class.java)
        return (outcome as FinancialGraphOutcome.Created).graph
    }

    private fun rejectedIssues(outcome: FinancialGraphOutcome): Set<GraphBuildIssue> {
        assertThat(outcome).isInstanceOf(FinancialGraphOutcome.Rejected::class.java)
        return (outcome as FinancialGraphOutcome.Rejected).issues
    }

    private fun notEligibleReason(outcome: FinancialGraphOutcome): FinancialGraphOutcome.NotEligible {
        assertThat(outcome).isInstanceOf(FinancialGraphOutcome.NotEligible::class.java)
        return outcome as FinancialGraphOutcome.NotEligible
    }

    // ---------------------------------------------------------------------
    // Eligibility, category independence, unresolved kinds, legacy continuity.
    // ---------------------------------------------------------------------

    @Test
    fun `eligible purchase builds graph through the actual policy with certified booked money`() {
        val graph = created(FinancialGraphFactory.build(purchaseDraft()))

        assertThat(graph.eventId.value).isEqualTo("evt-1")
        assertThat(graph.kind).isEqualTo(EconomicKind.PURCHASE)
        assertThat(graph.kindResolved).isTrue()
        assertThat(graph.bookedAmount).isEqualTo(Money.of("84.50"))
        assertThat(graph.bookedAmount.currency).isEqualTo("SAR")
        assertThat(graph.bookedBasis).isEqualTo(com.athar.core.domain.calc.financial.BookedBasis.ORIGINAL)
        assertThat(graph.incomeNature).isEqualTo(com.athar.core.domain.model.financial.IncomeNature.UNCLASSIFIED)
        assertThat(graph.observationIdentity.namespace).isEqualTo("sms:AlRajhi")
        assertThat(graph.observationIdentity.sourceItemId).isEqualTo("obs-1")
    }

    @Test
    fun `categorized and uncategorized eligible observations are equivalent`() {
        val withCategory = created(
            FinancialGraphFactory.build(
                purchaseDraft(category = CategoryAssignment("cat-groceries", CategoryBasis.RULE_DERIVED)),
            ),
        )
        val withoutCategory = created(FinancialGraphFactory.build(purchaseDraft()))

        // Compare financial facts, not a public validation-bypassing graph copy.
        assertThat(withCategory.decision).isEqualTo(withoutCategory.decision)
        assertThat(withCategory.bookedAmount).isEqualTo(withoutCategory.bookedAmount)
        assertThat(withCategory.components).isEqualTo(withoutCategory.components)
        assertThat(withCategory.legs).isEqualTo(withoutCategory.legs)
        assertThat(withoutCategory.category).isNull()
        assertThat(withCategory.category!!.categoryId).isEqualTo("cat-groceries")
        assertThat(withCategory.category!!.basis).isEqualTo(CategoryBasis.RULE_DERIVED)
    }

    @Test
    fun `unresolved posted kind captures money without inventing earnings or legs`() {
        val obs = observation(kind = EconomicKind.UNRESOLVED_POSTED_MOVEMENT, direction = null)
        val graph = created(FinancialGraphFactory.build(purchaseDraft(obs = obs, components = emptyList())))

        assertThat(graph.kind).isEqualTo(EconomicKind.UNRESOLVED_POSTED_MOVEMENT)
        assertThat(graph.kindResolved).isFalse()
        assertThat(graph.bookedAmount).isEqualTo(Money.of("84.50"))
        assertThat(graph.components).isEmpty()
        assertThat(graph.routing).isEqualTo(com.athar.core.domain.model.financialgraph.RoutingAllocation.UNALLOCATED)
    }

    @Test
    fun `every legacy status is preserved verbatim and never produces a replacement graph`() {
        val statuses = listOf("PENDING", "CONFIRMED", "DISMISSED", null)
        for (status in statuses) {
            // Even a reparsed INCOME kind plus an explicit user declaration of
            // INCOME must never turn existing history into a new posted graph.
            val obs = observation(
                kind = EconomicKind.INCOME,
                direction = MovementDirection.IN,
                legacy = LegacyFacts(status, "EXPENSE", "-84.50"),
                declaration = EconomicKind.INCOME,
            )
            val outcome = FinancialGraphFactory.build(purchaseDraft(obs = obs, components = emptyList()))

            val notEligible = notEligibleReason(outcome)
            assertThat(notEligible.reason)
                .isEqualTo(com.athar.core.domain.model.financialgraph.NoGraphReason.LEGACY_PRESERVED)
            // The typed decision — with the whole verbatim observation — is retained.
            assertThat(notEligible.decision).isInstanceOf(com.athar.core.domain.calc.financial.RecordingDecision.PreservedLegacy::class.java)
            assertThat(notEligible.decision.observation).isEqualTo(obs)
            assertThat((notEligible.decision.observation.legacy)).isEqualTo(LegacyFacts(status, "EXPENSE", "-84.50"))
        }

        // LEGACY_UNVERIFIED kind without legacy facts selects the same path.
        val kindOnly = observation(kind = EconomicKind.LEGACY_UNVERIFIED, direction = null)
        val outcome = FinancialGraphFactory.build(purchaseDraft(obs = kindOnly, components = emptyList()))
        assertThat(notEligibleReason(outcome).reason)
            .isEqualTo(com.athar.core.domain.model.financialgraph.NoGraphReason.LEGACY_PRESERVED)
    }

    @Test
    fun `non posted and unreliable observations are rejected with typed reasons and no graph`() {
        val cases = listOf(
            PostingEvidence(PostingState.AUTHORIZED, PostingSupport.EXPLICIT_POSTED) to
                com.athar.core.domain.model.financialgraph.NoGraphReason.NON_POSTED,
            PostingEvidence(PostingState.DECLINED, PostingSupport.EXPLICIT_POSTED) to
                com.athar.core.domain.model.financialgraph.NoGraphReason.NON_POSTED,
            PostingEvidence(PostingState.SCHEDULED, PostingSupport.EXPLICIT_POSTED) to
                com.athar.core.domain.model.financialgraph.NoGraphReason.NON_POSTED,
            PostingEvidence(PostingState.POSTED, PostingSupport.HEURISTIC_ONLY) to
                com.athar.core.domain.model.financialgraph.NoGraphReason.UNRELIABLE_POSTING_EVIDENCE,
        )
        for ((posting, expectedReason) in cases) {
            val outcome = FinancialGraphFactory.build(purchaseDraft(obs = observation(posting = posting)))
            assertThat(notEligibleReason(outcome).reason).isEqualTo(expectedReason)
        }

        // Heuristic-only money: posted reliably, but the number is not proven.
        val heuristicMoney = observation(
            original = SupportedMoney(Money.of("84.50"), AmountSupport.HEURISTIC_ONLY),
        )
        assertThat(
            notEligibleReason(FinancialGraphFactory.build(purchaseDraft(obs = heuristicMoney))).reason,
        ).isEqualTo(com.athar.core.domain.model.financialgraph.NoGraphReason.UNRELIABLE_AMOUNT_SUPPORT)

        // No money at all — never a zero placeholder.
        val noMoney = observation(original = null)
        assertThat(
            notEligibleReason(FinancialGraphFactory.build(purchaseDraft(obs = noMoney))).reason,
        ).isEqualTo(com.athar.core.domain.model.financialgraph.NoGraphReason.NO_KNOWN_AMOUNT)
    }

    @Test
    fun `policy-level invalid and conflicting observations keep their typed decisions`() {
        // Unsupported precision (USD 10.005) is a genuine policy invalid.
        val overPrecise = observation(
            original = SupportedMoney(Money.of("10.005", "USD"), AmountSupport.EXPLICIT_BOOKED),
        )
        val invalid = notEligibleReason(FinancialGraphFactory.build(purchaseDraft(obs = overPrecise)))
        assertThat(invalid.reason)
            .isEqualTo(com.athar.core.domain.model.financialgraph.NoGraphReason.INVALID_INPUT_MONEY)
        assertThat(invalid.decision).isInstanceOf(com.athar.core.domain.calc.financial.RecordingDecision.InvalidObservation::class.java)

        // PURCHASE kind with an explicit IN direction is a genuine conflict.
        val conflicted = observation(direction = MovementDirection.IN)
        val conflict = notEligibleReason(FinancialGraphFactory.build(purchaseDraft(obs = conflicted)))
        assertThat(conflict.reason)
            .isEqualTo(com.athar.core.domain.model.financialgraph.NoGraphReason.KIND_DIRECTION_CONFLICT)
        assertThat(conflict.decision).isInstanceOf(com.athar.core.domain.calc.financial.RecordingDecision.Conflict::class.java)
    }

    // ---------------------------------------------------------------------
    // Currency precision: SAR / USD / JPY / KWD, exact values, no rounding.
    // ---------------------------------------------------------------------

    @Test
    fun `precise SAR USD JPY and KWD values pass with currency-exact arithmetic`() {
        val usdGraph = created(
            FinancialGraphFactory.build(
                purchaseDraft(
                    obs = observation(
                        id = "obs-usd",
                        original = SupportedMoney(Money.of("10.00", "USD"), AmountSupport.EXPLICIT_BOOKED),
                    ),
                    eventId = "evt-usd",
                    components = listOf(ComponentSpec("c-usd", ComponentType.PRINCIPAL, Money.of("10.00", "USD"))),
                ),
            ),
        )
        assertThat(usdGraph.bookedAmount).isEqualTo(Money.of("10.00", "USD"))

        val jpyGraph = created(
            FinancialGraphFactory.build(
                purchaseDraft(
                    obs = observation(
                        id = "obs-jpy",
                        original = SupportedMoney(Money.of("1000", "JPY"), AmountSupport.EXPLICIT_BOOKED),
                    ),
                    eventId = "evt-jpy",
                    components = listOf(ComponentSpec("c-jpy", ComponentType.PRINCIPAL, Money.of("1000", "JPY"))),
                ),
            ),
        )
        assertThat(jpyGraph.bookedAmount).isEqualTo(Money.of("1000", "JPY"))

        // KWD three-fraction digits; trailing-zero scale-insensitivity holds.
        val kwdGraph = created(
            FinancialGraphFactory.build(
                purchaseDraft(
                    obs = observation(
                        id = "obs-kwd",
                        original = SupportedMoney(Money.of("10.1230", "KWD"), AmountSupport.EXPLICIT_BOOKED),
                    ),
                    eventId = "evt-kwd",
                    components = listOf(ComponentSpec("c-kwd", ComponentType.PRINCIPAL, Money.of("10.123", "KWD"))),
                ),
            ),
        )
        assertThat(kwdGraph.bookedAmount).isEqualTo(Money.of("10.1230", "KWD"))
        assertThat(kwdGraph.bookedAmount.amount.stripTrailingZeros().scale()).isEqualTo(3)
    }

    // ---------------------------------------------------------------------
    // Fee composition (design §3): INCLUDED / ADDITIONAL / UNKNOWN / linked.
    // ---------------------------------------------------------------------

    @Test
    fun `included fee decomposes principal inside the booked total with one aggregate leg`() {
        // Booked 39.50 including an explicit 1.50 fee → principal 38.00 +
        // fee 1.50, aggregate account delta −39.50, never −41.00.
        val obs = observation(id = "obs-incl", original = SupportedMoney(Money.of("39.50"), AmountSupport.EXPLICIT_BOOKED))
        val graph = created(
            FinancialGraphFactory.build(
                purchaseDraft(
                    obs = obs,
                    eventId = "evt-incl",
                    components = listOf(
                        ComponentSpec("c-principal", ComponentType.PRINCIPAL, Money.of("38.00")),
                        ComponentSpec(
                            "c-fee",
                            ComponentType.FEE,
                            Money.of("1.50"),
                            feeRelation = FeeRelation.INCLUDED,
                            feeEvidenceRef = "sms-span-1",
                        ),
                    ),
                    legs = listOf(
                        LegSpec("l-bank", "bank:snb:main", Money.of("-39.50")),
                    ),
                ),
            ),
        )

        assertThat(graph.bookedAmount).isEqualTo(Money.of("39.50"))
        assertThat(graph.components).hasSize(2)
        assertThat(graph.legs.single().delta).isEqualTo(Money.of("-39.50"))
        assertThat(graph.routing).isEqualTo(com.athar.core.domain.model.financialgraph.RoutingAllocation.ROUTED)
    }

    @Test
    fun `included fee composition that does not sum to the booked total is rejected`() {
        val obs = observation(id = "obs-bad-incl", original = SupportedMoney(Money.of("39.50"), AmountSupport.EXPLICIT_BOOKED))
        val issues = rejectedIssues(
            FinancialGraphFactory.build(
                purchaseDraft(
                    obs = obs,
                    eventId = "evt-bad-incl",
                    components = listOf(
                        ComponentSpec("c-principal", ComponentType.PRINCIPAL, Money.of("39.50")),
                        ComponentSpec(
                            "c-fee",
                            ComponentType.FEE,
                            Money.of("1.50"),
                            feeRelation = FeeRelation.INCLUDED,
                            feeEvidenceRef = "sms-span-1",
                        ),
                    ),
                ),
            ),
        )
        assertThat(issues).contains(GraphBuildIssue.COMPOSITION_CONFLICT)
    }

    @Test
    fun `additional fee aggregates beyond the booked principal with its own leg`() {
        // Explicit principal booking 39.50 plus a separately debited 1.50 fee
        // → aggregate debit 41.00; both legs are required for full coverage.
        val obs = observation(id = "obs-addl", original = SupportedMoney(Money.of("39.50"), AmountSupport.EXPLICIT_BOOKED))
        val graph = created(
            FinancialGraphFactory.build(
                purchaseDraft(
                    obs = obs,
                    eventId = "evt-addl",
                    components = listOf(
                        ComponentSpec("c-principal", ComponentType.PRINCIPAL, Money.of("39.50")),
                        ComponentSpec(
                            "c-fee",
                            ComponentType.FEE,
                            Money.of("1.50"),
                            feeRelation = FeeRelation.ADDITIONAL,
                            feeEvidenceRef = "sms-span-2",
                        ),
                    ),
                    legs = listOf(
                        LegSpec("l-principal", "bank:snb:main", Money.of("-39.50")),
                        LegSpec("l-fee", "bank:snb:main", Money.of("-1.50")),
                    ),
                ),
            ),
        )

        assertThat(graph.bookedAmount).isEqualTo(Money.of("39.50"))
        val total = graph.legs.map { it.delta.amount }.fold(java.math.BigDecimal.ZERO) { a, b -> a + b }
        assertThat(total.compareTo(java.math.BigDecimal("-41.00"))).isEqualTo(0)
        assertThat(graph.routing).isEqualTo(com.athar.core.domain.model.financialgraph.RoutingAllocation.ROUTED)
    }

    @Test
    fun `no supplied fee preserves principal without inventing a fee`() {
        val obs = observation(id = "obs-unk", original = SupportedMoney(Money.of("39.50"), AmountSupport.EXPLICIT_BOOKED))
        val graph = created(
            FinancialGraphFactory.build(
                purchaseDraft(
                    obs = obs,
                    eventId = "evt-unk",
                    components = listOf(ComponentSpec("c-principal", ComponentType.PRINCIPAL, Money.of("39.50"))),
                    legs = listOf(LegSpec("l-bank", "bank:snb:main", Money.of("-39.50"))),
                ),
            ),
        )

        assertThat(graph.bookedAmount).isEqualTo(Money.of("39.50"))
        assertThat(graph.components.map { it.type }).containsExactly(ComponentType.PRINCIPAL)
        assertThat(graph.routing).isEqualTo(com.athar.core.domain.model.financialgraph.RoutingAllocation.ROUTED)
    }

    @Test
    fun `linked already-represented fee is referenced once and never charged twice`() {
        val obs = observation(id = "obs-link", original = SupportedMoney(Money.of("39.50"), AmountSupport.EXPLICIT_BOOKED))

        // Linked representation: principal stays the whole booking, no new fee
        // component and no fee leg — the fee lives on the linked event once.
        val graph = created(
            FinancialGraphFactory.build(
                purchaseDraft(
                    obs = obs,
                    eventId = "evt-link",
                    components = listOf(ComponentSpec("c-principal", ComponentType.PRINCIPAL, Money.of("39.50"))),
                    legs = listOf(LegSpec("l-bank", "bank:snb:main", Money.of("-39.50"))),
                    linkedRepresentedFeeRef = "fee-event-77",
                ),
            ),
        )
        assertThat(graph.components.map { it.type }).containsExactly(ComponentType.PRINCIPAL)
        assertThat(graph.legs.single().delta).isEqualTo(Money.of("-39.50"))

        // Supplying a NEW fee component as well would double-charge it.
        val duplicate = rejectedIssues(
            FinancialGraphFactory.build(
                purchaseDraft(
                    obs = obs,
                    eventId = "evt-link-dup",
                    components = listOf(
                        ComponentSpec("c-principal", ComponentType.PRINCIPAL, Money.of("39.50")),
                        ComponentSpec(
                            "c-fee",
                            ComponentType.FEE,
                            Money.of("1.50"),
                            feeRelation = FeeRelation.ADDITIONAL,
                            feeEvidenceRef = "sms-span-3",
                        ),
                    ),
                    linkedRepresentedFeeRef = "fee-event-77",
                ),
            ),
        )
        assertThat(duplicate).contains(GraphBuildIssue.DUPLICATE_FEE_REPRESENTATION)
    }

    // ---------------------------------------------------------------------
    // Signed net-worth legs: transfers/repayments, coverage, no invented legs.
    // ---------------------------------------------------------------------

    @Test
    fun `repayment posts both signed legs once with zero consumption components`() {
        // Bank −200 (liability down), card +200 (debt down): positive increases
        // the signed position; no consumption/income component may appear.
        val obs = observation(
            id = "obs-repay",
            kind = EconomicKind.CARD_REPAYMENT,
            direction = MovementDirection.OUT,
            original = SupportedMoney(Money.of("200.00"), AmountSupport.EXPLICIT_BOOKED),
        )
        val graph = created(
            FinancialGraphFactory.build(
                purchaseDraft(
                    obs = obs,
                    eventId = "evt-repay",
                    components = emptyList(),
                    legs = listOf(
                        LegSpec("l-bank", "bank:snb:main", Money.of("-200.00")),
                        LegSpec("l-card", "card:snb:gold", Money.of("200.00")),
                    ),
                ),
            ),
        )

        assertThat(graph.components).isEmpty()
        assertThat(graph.legs).hasSize(2)
        assertThat(graph.kind).isEqualTo(EconomicKind.CARD_REPAYMENT)
        assertThat(graph.routing).isEqualTo(com.athar.core.domain.model.financialgraph.RoutingAllocation.ROUTED)
    }

    @Test
    fun `own transfer with cross-currency legs posts each known currency without invented rates`() {
        val obs = observation(
            id = "obs-fx",
            kind = EconomicKind.OWN_TRANSFER,
            direction = null,
            original = SupportedMoney(Money.of("750.00"), AmountSupport.EXPLICIT_BOOKED),
        )
        val graph = created(
            FinancialGraphFactory.build(
                purchaseDraft(
                    obs = obs,
                    eventId = "evt-fx",
                    components = emptyList(),
                    legs = listOf(
                        LegSpec("l-sar", "bank:snb:main", Money.of("-750.00")),
                        LegSpec("l-usd", "bank:emigrate:usd", Money.of("100.00", "USD")),
                    ),
                ),
            ),
        )

        assertThat(graph.legs).hasSize(2)
        assertThat(graph.legs.map { it.delta.currency }).containsExactly("SAR", "USD").inOrder()
        assertThat(graph.routing).isEqualTo(com.athar.core.domain.model.financialgraph.RoutingAllocation.ROUTED)
    }

    @Test
    fun `same-currency unequal transfer legs are a typed conflict`() {
        val obs = observation(
            id = "obs-xfer",
            kind = EconomicKind.OWN_TRANSFER,
            direction = null,
            original = SupportedMoney(Money.of("200.00"), AmountSupport.EXPLICIT_BOOKED),
        )
        val issues = rejectedIssues(
            FinancialGraphFactory.build(
                purchaseDraft(
                    obs = obs,
                    eventId = "evt-xfer",
                    components = emptyList(),
                    legs = listOf(
                        LegSpec("l-out", "bank:snb:main", Money.of("-200.00")),
                        LegSpec("l-in", "card:snb:gold", Money.of("150.00")),
                    ),
                ),
            ),
        )
        assertThat(issues).contains(GraphBuildIssue.CONFLICTING_LEG_AMOUNTS)
    }

    @Test
    fun `missing counterpart and missing routing stay explicit partial coverage`() {
        // Repayment with only the bank leg: counterpart is outstanding, so the
        // transfer is honestly PARTIAL — no invented card leg.
        val repayObs = observation(
            id = "obs-repay-part",
            kind = EconomicKind.CARD_REPAYMENT,
            direction = MovementDirection.OUT,
            original = SupportedMoney(Money.of("200.00"), AmountSupport.EXPLICIT_BOOKED),
        )
        val partial = created(
            FinancialGraphFactory.build(
                purchaseDraft(
                    obs = repayObs,
                    eventId = "evt-repay-part",
                    components = emptyList(),
                    legs = listOf(LegSpec("l-bank", "bank:snb:main", Money.of("-200.00"))),
                ),
            ),
        )
        assertThat(partial.routing).isEqualTo(com.athar.core.domain.model.financialgraph.RoutingAllocation.PARTIAL)

        // Purchase with no legs at all: known posted money stays available,
        // explicitly UNALLOCATED — no manual-account leg is invented.
        val unallocated = created(FinancialGraphFactory.build(purchaseDraft(eventId = "evt-unalloc")))
        assertThat(unallocated.routing).isEqualTo(com.athar.core.domain.model.financialgraph.RoutingAllocation.UNALLOCATED)

        // Partial purchase leg: covered 20.00 of 84.50.
        val partialPurchase = created(
            FinancialGraphFactory.build(
                purchaseDraft(
                    eventId = "evt-part-purchase",
                    legs = listOf(LegSpec("l-bank", "bank:snb:main", Money.of("-20.00"))),
                ),
            ),
        )
        assertThat(partialPurchase.routing).isEqualTo(com.athar.core.domain.model.financialgraph.RoutingAllocation.PARTIAL)
    }

    @Test
    fun `leg overshoot and wrong-direction legs are typed conflicts`() {
        val overshoot = rejectedIssues(
            FinancialGraphFactory.build(
                purchaseDraft(
                    eventId = "evt-over",
                    legs = listOf(
                        LegSpec("l-1", "bank:snb:main", Money.of("-30.00")),
                        LegSpec("l-2", "bank:snb:main", Money.of("-60.00")),
                    ),
                ),
            ),
        )
        assertThat(overshoot).contains(GraphBuildIssue.LEG_OVERSHOOT)

        val wrongSign = rejectedIssues(
            FinancialGraphFactory.build(
                purchaseDraft(
                    eventId = "evt-sign",
                    legs = listOf(LegSpec("l-1", "bank:snb:main", Money.of("84.50"))),
                ),
            ),
        )
        assertThat(wrongSign).contains(GraphBuildIssue.LEG_DIRECTION_CONFLICT)
    }

    // ---------------------------------------------------------------------
    // Identity separation and structural rejections.
    // ---------------------------------------------------------------------

    @Test
    fun `observation identity and economic identity stay independent`() {
        val sharedEconomic = EconomicIdentity.IssuerReference("SNB", "txn-ref-9")

        val first = created(
            FinancialGraphFactory.build(
                purchaseDraft(obs = observation(id = "obs-a"), eventId = "evt-a", economicIdentity = sharedEconomic),
            ),
        )
        val second = created(
            FinancialGraphFactory.build(
                purchaseDraft(obs = observation(id = "obs-b"), eventId = "evt-b", economicIdentity = sharedEconomic),
            ),
        )

        // Distinct source observations, same proven economic reference: both
        // graphs are created independently; similarity is never a merge key
        // and the factory never deduplicates money.
        assertThat(first.observationIdentity.sourceItemId).isEqualTo("obs-a")
        assertThat(second.observationIdentity.sourceItemId).isEqualTo("obs-b")
        assertThat(first.observationIdentity).isNotEqualTo(second.observationIdentity)
        assertThat(first.economicIdentity).isEqualTo(second.economicIdentity)
        assertThat(first.bookedAmount).isEqualTo(second.bookedAmount)

        // A blank issuer reference is invalid identity input, never repaired.
        val blankRef = rejectedIssues(
            FinancialGraphFactory.build(
                purchaseDraft(
                    eventId = "evt-bad-id",
                    economicIdentity = EconomicIdentity.IssuerReference("SNB", " "),
                ),
            ),
        )
        assertThat(blankRef).contains(GraphBuildIssue.INVALID_ECONOMIC_IDENTITY)
    }

    @Test
    fun `unmatched refund remains a valid posted event without invented attribution`() {
        val obs = observation(
            id = "obs-refund",
            kind = EconomicKind.REFUND,
            direction = MovementDirection.IN,
            original = SupportedMoney(Money.of("50.00"), AmountSupport.EXPLICIT_BOOKED),
        )
        val graph = created(
            FinancialGraphFactory.build(
                purchaseDraft(
                    obs = obs,
                    eventId = "evt-refund",
                    components = listOf(ComponentSpec("c-refund", ComponentType.REFUND, Money.of("50.00"))),
                    legs = listOf(LegSpec("l-card", "card:snb:gold", Money.of("50.00"))),
                ),
            ),
        )

        assertThat(graph.kind).isEqualTo(EconomicKind.REFUND)
        assertThat(graph.components.single().type).isEqualTo(ComponentType.REFUND)
        assertThat(graph.routing).isEqualTo(com.athar.core.domain.model.financialgraph.RoutingAllocation.ROUTED)
    }

    @Test
    fun `structural defects accumulate typed rejections without partial success`() {
        val obs = observation(id = "obs-defect", original = SupportedMoney(Money.of("84.50"), AmountSupport.EXPLICIT_BOOKED))
        val issues = rejectedIssues(
            FinancialGraphFactory.build(
                purchaseDraft(
                    obs = obs,
                    eventId = " ",
                    sourceNamespace = " ",
                    components = listOf(
                        ComponentSpec(" ", ComponentType.PRINCIPAL, Money.of("84.50")),
                        ComponentSpec("c-principal", ComponentType.PRINCIPAL, Money.of("84.50"), feeRelation = FeeRelation.INCLUDED),
                        ComponentSpec("c-fee", ComponentType.FEE, Money.of("1.50")),
                        ComponentSpec("c-zero", ComponentType.REFUND, Money.of("0.00")),
                        ComponentSpec("c-cross", ComponentType.REFUND, Money.of("5.00", "USD")),
                    ),
                    legs = listOf(
                        LegSpec("l-1", " ", Money.of("-1.00")),
                        LegSpec("l-2", "bank:snb:main", Money.of("0.00", "USD")),
                        LegSpec("l-3", "bank:snb:main", Money.of("-5.00"), componentRef = "nope"),
                    ),
                    category = CategoryAssignment(" ", CategoryBasis.USER_DECLARED),
                    linkedRepresentedFeeRef = " ",
                ),
            ),
        )

        assertThat(issues).containsExactly(
            GraphBuildIssue.BLANK_EVENT_ID,
            GraphBuildIssue.BLANK_SOURCE_NAMESPACE,
            GraphBuildIssue.BLANK_COMPONENT_ID,
            GraphBuildIssue.FEE_RELATION_FORBIDDEN,
            GraphBuildIssue.FEE_RELATION_REQUIRED,
            GraphBuildIssue.FEE_EVIDENCE_REQUIRED,
            GraphBuildIssue.NONPOSITIVE_COMPONENT_AMOUNT,
            GraphBuildIssue.COMPONENT_CURRENCY_MISMATCH,
            GraphBuildIssue.COMPONENT_NOT_ALLOWED_FOR_KIND,
            GraphBuildIssue.DUPLICATE_LINKED_FEE_REFERENCE,
            GraphBuildIssue.BLANK_ACCOUNT_REF,
            GraphBuildIssue.ZERO_LEG_DELTA,
            GraphBuildIssue.UNKNOWN_LEG_COMPONENT_REFERENCE,
            GraphBuildIssue.DUPLICATE_FEE_REPRESENTATION,
        )
    }
}
