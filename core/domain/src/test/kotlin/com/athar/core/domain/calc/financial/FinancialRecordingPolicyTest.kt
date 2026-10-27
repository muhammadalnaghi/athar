package com.athar.core.domain.calc.financial

import com.athar.core.common.money.Money
import com.athar.core.domain.model.financial.AmountSupport
import com.athar.core.domain.model.financial.EconomicKind
import com.athar.core.domain.model.financial.ExplicitFinancialDeclaration
import com.athar.core.domain.model.financial.FinancialInputIssue
import com.athar.core.domain.model.financial.FinancialObservation
import com.athar.core.domain.model.financial.IncomeNature
import com.athar.core.domain.model.financial.LegacyFacts
import com.athar.core.domain.model.financial.MovementDirection
import com.athar.core.domain.model.financial.PostingEvidence
import com.athar.core.domain.model.financial.PostingState
import com.athar.core.domain.model.financial.PostingSupport
import com.athar.core.domain.model.financial.RoutingCoverage
import com.athar.core.domain.model.financial.StableObservationId
import com.athar.core.domain.model.financial.SupportedMoney
import com.google.common.truth.Truth.assertThat
import kotlinx.datetime.LocalDate
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

/**
 * Behavior-first tests for the evidence-based recording policy (WM028-07 /
 * ADR-010, M1B amendment): decisions act only on validated inputs, every
 * decision retains the complete observation, dual amounts stay one economic
 * observation, unknown-kind posted money is captured (never earnings), legacy
 * evidence is preserved verbatim before validation, and contradictory
 * explicit direction is a genuine typed conflict.
 *
 * Legacy preservation is keyed on existing legacy facts or LEGACY_UNVERIFIED;
 * concrete explicit user declarations for new observations override
 * machine inference without bypassing any guard, reversals legitimately move
 * in either direction, and heuristic secondary money never poisons a valid
 * booked primary nor surfaces through certified accessors.
 */
class FinancialRecordingPolicyTest {

    @Test
    fun `reliable posted purchase records known money without any category`() {
        val decision = FinancialRecordingPolicy.decide(observation(routing = RoutingCoverage.Unresolved))

        val recorded = decision as RecordingDecision.Recorded
        assertThat(recorded.bookedAmount).isEqualTo(Money.of("84.50"))
        assertThat(recorded.bookedAmount.currency).isEqualTo("SAR")
        assertThat(recorded.bookedBasis).isEqualTo(BookedBasis.ORIGINAL)
        assertThat(recorded.economicKind).isEqualTo(EconomicKind.PURCHASE)
        assertThat(recorded.kindBasis).isEqualTo(KindBasis.OBSERVED)
        assertThat(recorded.incomeNature).isEqualTo(IncomeNature.UNCLASSIFIED)
        assertThat(recorded.kindResolved).isTrue()
        assertThat(recorded.observationId.value).isEqualTo("obs-1")
    }

    @Test
    fun `missing routing and date stay disclosed coverage limitations with no invented leg`() {
        val decision = FinancialRecordingPolicy.decide(
            observation(routing = RoutingCoverage.Unresolved, eventDate = null),
        )

        val recorded = decision as RecordingDecision.Recorded
        assertThat(recorded.routing).isEqualTo(RoutingCoverage.Unresolved)
        assertThat(recorded.routing).isNotInstanceOf(RoutingCoverage.Resolved::class.java)
        assertThat(recorded.eventDate).isNull()
    }

    @Test
    fun `heuristic only posting evidence never records consumption`() {
        val observation = observation(
            posting = PostingEvidence(PostingState.POSTED, PostingSupport.HEURISTIC_ONLY),
        )
        val decision = FinancialRecordingPolicy.decide(observation)

        val unresolved = decision as RecordingDecision.Unresolved
        assertThat(unresolved.reason).isEqualTo(UnresolvedReason.UNRELIABLE_POSTING_EVIDENCE)
        assertThat(unresolved.observation).isEqualTo(observation)
    }

    @Test
    fun `every non posted state is retained with its complete observation`() {
        val states = listOf(
            PostingState.AUTHORIZED,
            PostingState.RELEASED,
            PostingState.DECLINED,
            PostingState.NOTICE,
            PostingState.SCHEDULED,
        )

        states.forEach { state ->
            val observation = observation(
                posting = PostingEvidence(state, PostingSupport.EXPLICIT_POSTED),
                original = explicit(Money.of("999.00")),
            )
            val decision = FinancialRecordingPolicy.decide(observation)

            val nonPosted = decision as RecordingDecision.NonPosted
            assertThat(nonPosted.state).isEqualTo(state)
            assertThat(nonPosted.observation).isEqualTo(observation)
        }
    }

    @Test
    fun `heuristic only amount cannot auto record even when posting is explicit`() {
        val observation = observation(
            original = SupportedMoney(Money.of("500.00"), AmountSupport.HEURISTIC_ONLY),
        )
        val decision = FinancialRecordingPolicy.decide(observation)

        val unresolved = decision as RecordingDecision.Unresolved
        assertThat(unresolved.reason).isEqualTo(UnresolvedReason.UNRELIABLE_AMOUNT_SUPPORT)
        // The heuristic money stays visible in the retained observation —
        // never silently dropped, converted or zero-filled.
        assertThat(unresolved.observation.original?.support).isEqualTo(AmountSupport.HEURISTIC_ONLY)
        assertThat(unresolved.observation.original?.amount).isEqualTo(Money.of("500.00"))
    }

    @Test
    fun `posted movement with no known money is unresolved and never a zero placeholder`() {
        val decision = FinancialRecordingPolicy.decide(
            observation(original = null, settlement = null),
        )

        val unresolved = decision as RecordingDecision.Unresolved
        assertThat(unresolved.reason).isEqualTo(UnresolvedReason.NO_KNOWN_AMOUNT)
    }

    @Test
    fun `original only USD records USD with no settlement or rate`() {
        val decision = FinancialRecordingPolicy.decide(
            observation(original = explicit(Money.of("12.75", "USD")), settlement = null),
        )

        val recorded = decision as RecordingDecision.Recorded
        assertThat(recorded.bookedAmount).isEqualTo(Money.of("12.75", "USD"))
        assertThat(recorded.bookedBasis).isEqualTo(BookedBasis.ORIGINAL)
        assertThat(recorded.bookedAmount.currency).isEqualTo("USD")
        assertThat(recorded.bookedAmount.currency).isNotEqualTo("SAR")
        assertThat(recorded.settlementAmount).isNull()
    }

    @Test
    fun `dual currency observation retains both amounts booked on the supported settlement`() {
        val decision = FinancialRecordingPolicy.decide(
            observation(
                original = explicit(Money.of("10", "USD")),
                settlement = explicit(Money.of("39.50", "SAR")),
            ),
        )

        val recorded = decision as RecordingDecision.Recorded
        // Booked basis is the supported settlement; the original stays retained
        // on the same single economic observation. No FX inference, no double count.
        assertThat(recorded.bookedAmount).isEqualTo(Money.of("39.50", "SAR"))
        assertThat(recorded.bookedBasis).isEqualTo(BookedBasis.SETTLEMENT)
        assertThat(recorded.originalAmount).isEqualTo(Money.of("10", "USD"))
        assertThat(recorded.settlementAmount).isEqualTo(Money.of("39.50", "SAR"))
    }

    @Test
    fun `settlement money is booked only when it is explicitly supported`() {
        val decision = FinancialRecordingPolicy.decide(
            observation(
                original = explicit(Money.of("84.50")),
                settlement = SupportedMoney(Money.of("22.50", "USD"), AmountSupport.HEURISTIC_ONLY),
            ),
        )

        val recorded = decision as RecordingDecision.Recorded
        assertThat(recorded.bookedAmount).isEqualTo(Money.of("84.50"))
        assertThat(recorded.bookedBasis).isEqualTo(BookedBasis.ORIGINAL)
        // The unsupported settlement value stays retained verbatim on the
        // observation but is never certified by the decision accessors.
        assertThat(recorded.settlementAmount).isNull()
        assertThat(recorded.observation.settlement?.amount).isEqualTo(Money.of("22.50", "USD"))
        assertThat(recorded.observation.settlement?.support).isEqualTo(AmountSupport.HEURISTIC_ONLY)
    }

    @Test
    fun `reliably posted known money with unresolved kind is captured never earnings`() {
        val observation = observation(
            economicKind = null,
            direction = MovementDirection.IN,
            original = explicit(Money.of("15000")),
            userDeclaration = null,
        )
        val decision = FinancialRecordingPolicy.decide(observation)

        val captured = decision as RecordingDecision.CapturedUnresolvedKind
        assertThat(captured.bookedAmount).isEqualTo(Money.of("15000"))
        assertThat(captured.economicKind).isEqualTo(EconomicKind.UNRESOLVED_POSTED_MOVEMENT)
        assertThat(captured.kindResolved).isFalse()
        assertThat(captured.economicKind).isNotEqualTo(EconomicKind.INCOME)
        // The retained observation discloses the observed direction/routing/date.
        assertThat(captured.observation).isEqualTo(observation)
        assertThat(captured.observation.direction).isEqualTo(MovementDirection.IN)
    }

    @Test
    fun `explicitly labeled unresolved posted movement stays captured with known money`() {
        val decision = FinancialRecordingPolicy.decide(
            observation(
                economicKind = EconomicKind.UNRESOLVED_POSTED_MOVEMENT,
                original = explicit(Money.of("39.50")),
            ),
        )

        val captured = decision as RecordingDecision.CapturedUnresolvedKind
        assertThat(captured.bookedAmount).isEqualTo(Money.of("39.50"))
        assertThat(captured.economicKind).isEqualTo(EconomicKind.UNRESOLVED_POSTED_MOVEMENT)
        assertThat(captured.kindResolved).isFalse()
    }

    @Test
    fun `legacy unverified is preserved verbatim before any new input validation`() {
        val legacy = LegacyFacts(status = "PENDING", type = "EXPENSE", value = "12500")
        val observation = observation(
            economicKind = EconomicKind.LEGACY_UNVERIFIED,
            original = SupportedMoney(Money.of("10.005", "USD"), AmountSupport.HEURISTIC_ONLY),
            legacy = legacy,
        )
        val decision = FinancialRecordingPolicy.decide(observation)

        val preserved = decision as RecordingDecision.PreservedLegacy
        assertThat(preserved.legacy).isEqualTo(legacy)
        assertThat(preserved.observation).isEqualTo(observation)
        // Preservation happens before validation: no Invalid/Unresolved outcome,
        // no re-verification, no requeue, no rewrite.
        assertThat(preserved).isNotInstanceOf(RecordingDecision.InvalidObservation::class.java)
        assertThat(preserved).isNotInstanceOf(RecordingDecision.Unresolved::class.java)
    }

    @Test
    fun `legacy facts are retained verbatim without recording history again`() {
        val legacy = LegacyFacts(status = "OK", type = "SPEND", value = "8450")

        val preserved = FinancialRecordingPolicy.decide(observation(legacy = legacy)) as RecordingDecision.PreservedLegacy
        assertThat(preserved.legacy).isEqualTo(legacy)
    }

    @Test
    fun `policy rejects over precision money with typed failure keeping the observation`() {
        val observation = observation(original = explicit(Money.of("10.005", "USD")))
        val decision = FinancialRecordingPolicy.decide(observation)

        val invalid = decision as RecordingDecision.InvalidObservation
        assertThat(invalid.issues).containsExactly(FinancialInputIssue.UNSUPPORTED_PRECISION)
        // The complete observation is kept — nothing truncated or zeroed.
        assertThat(invalid.observation).isEqualTo(observation)
    }

    @Test
    fun `zero amount is an explicit outcome never a fake flow`() {
        val decision = FinancialRecordingPolicy.decide(observation(original = explicit(Money.of("0.00"))))

        val invalid = decision as RecordingDecision.InvalidObservation
        assertThat(invalid.issues).containsExactly(FinancialInputIssue.ZERO_AMOUNT)
    }

    @Test
    fun `negative amount is rejected instead of recorded`() {
        val decision = FinancialRecordingPolicy.decide(observation(original = explicit(Money.of("-0.01"))))

        val invalid = decision as RecordingDecision.InvalidObservation
        assertThat(invalid.issues).containsExactly(FinancialInputIssue.NEGATIVE_AMOUNT)
    }

    @Test
    fun `explicit user declaration resolves kind while merchant correction is not a policy input`() {
        val declared = FinancialRecordingPolicy.decide(
            observation(
                economicKind = null,
                direction = MovementDirection.IN,
                original = explicit(Money.of("15000")),
                userDeclaration = ExplicitFinancialDeclaration(EconomicKind.INCOME),
            ),
        )

        val recorded = declared as RecordingDecision.Recorded
        assertThat(recorded.economicKind).isEqualTo(EconomicKind.INCOME)
        assertThat(recorded.kindBasis).isEqualTo(KindBasis.USER_DECLARED)
        assertThat(recorded.kindResolved).isTrue()
        // By construction FinancialObservation carries no merchant/category
        // field: a MerchantCategoryCorrection cannot even be attached here.
    }

    @Test
    fun `income nature is disclosed as given and defaults to unclassified never salary`() {
        val earned = FinancialRecordingPolicy.decide(
            observation(
                economicKind = EconomicKind.INCOME,
                direction = MovementDirection.IN,
                original = explicit(Money.of("5000")),
                incomeNature = IncomeNature.EARNED,
            ),
        ) as RecordingDecision.Recorded
        assertThat(earned.incomeNature).isEqualTo(IncomeNature.EARNED)

        val invested = FinancialRecordingPolicy.decide(
            observation(
                economicKind = EconomicKind.INCOME,
                direction = MovementDirection.IN,
                original = explicit(Money.of("700")),
                incomeNature = IncomeNature.INVESTMENT,
            ),
        ) as RecordingDecision.Recorded
        assertThat(invested.incomeNature).isEqualTo(IncomeNature.INVESTMENT)

        val defaulted = FinancialRecordingPolicy.decide(
            observation(
                economicKind = EconomicKind.INCOME,
                direction = MovementDirection.IN,
                original = explicit(Money.of("300")),
            ),
        ) as RecordingDecision.Recorded
        assertThat(defaulted.incomeNature).isEqualTo(IncomeNature.UNCLASSIFIED)
    }

    @Test
    fun `contradictory explicit direction is a genuine conflict never silently recorded`() {
        val purchaseIn = observation(direction = MovementDirection.IN)
        val conflict1 = FinancialRecordingPolicy.decide(purchaseIn)

        val purchaseConflict = conflict1 as RecordingDecision.Conflict
        assertThat(purchaseConflict.conflict).isEqualTo(FinancialConflict.KIND_DIRECTION_MISMATCH)
        assertThat(purchaseConflict.observation).isEqualTo(purchaseIn)

        val incomeOut = observation(
            economicKind = EconomicKind.INCOME,
            direction = MovementDirection.OUT,
            original = explicit(Money.of("15000")),
        )
        val conflict2 = FinancialRecordingPolicy.decide(incomeOut) as RecordingDecision.Conflict
        assertThat(conflict2.conflict).isEqualTo(FinancialConflict.KIND_DIRECTION_MISMATCH)
    }

    @Test
    fun `blank stable observation identity cannot be constructed`() {
        assertThrows<IllegalArgumentException> { StableObservationId("") }
        assertThrows<IllegalArgumentException> { StableObservationId("   ") }
        assertThat(StableObservationId("obs-1").value).isEqualTo("obs-1")
        assertThat(StableObservationId.of("")).isNull()
    }

    // ---- Existing history cannot become a new posted event by reparsing ----

    @Test
    fun `dismissed legacy facts with a newly parsed supported purchase stay preserved`() {
        val legacy = LegacyFacts(status = "DISMISSED", type = "EXPENSE", value = "8450")
        val observation = observation(legacy = legacy)
        val decision = FinancialRecordingPolicy.decide(observation)

        val preserved = decision as RecordingDecision.PreservedLegacy
        assertThat(preserved.legacy).isEqualTo(legacy)
        assertThat(preserved.observation).isEqualTo(observation)
    }

    @Test
    fun `legacy pending row with an explicit user declaration stays preserved verbatim`() {
        val legacy = LegacyFacts(status = "PENDING", type = "INCOME", value = "15000")
        val observation = observation(
            economicKind = EconomicKind.LEGACY_UNVERIFIED,
            direction = MovementDirection.IN,
            settlement = explicit(Money.of("15000")),
            legacy = legacy,
            userDeclaration = ExplicitFinancialDeclaration(EconomicKind.INCOME),
        )
        val decision = FinancialRecordingPolicy.decide(observation)

        val preserved = decision as RecordingDecision.PreservedLegacy
        assertThat(preserved.legacy).isEqualTo(legacy)
        assertThat(preserved.observation).isEqualTo(observation)
    }

    // ---- M1C: concrete explicit declarations override machine inference ----

    @Test
    fun `user declaration resolves machine unresolved movement to own transfer`() {
        val recorded = FinancialRecordingPolicy.decide(
            observation(
                economicKind = EconomicKind.UNRESOLVED_POSTED_MOVEMENT,
                direction = MovementDirection.OUT,
                original = explicit(Money.of("250.00")),
                userDeclaration = ExplicitFinancialDeclaration(EconomicKind.OWN_TRANSFER),
            ),
        ) as RecordingDecision.Recorded

        assertThat(recorded.economicKind).isEqualTo(EconomicKind.OWN_TRANSFER)
        assertThat(recorded.kindBasis).isEqualTo(KindBasis.USER_DECLARED)
        assertThat(recorded.kindResolved).isTrue()
    }

    @Test
    fun `explicit borrowing declaration overrides misinferred income when direction is compatible`() {
        val recorded = FinancialRecordingPolicy.decide(
            observation(
                economicKind = EconomicKind.INCOME,
                direction = MovementDirection.IN,
                original = explicit(Money.of("15000")),
                userDeclaration = ExplicitFinancialDeclaration(EconomicKind.BORROWING),
            ),
        ) as RecordingDecision.Recorded

        assertThat(recorded.economicKind).isEqualTo(EconomicKind.BORROWING)
        assertThat(recorded.kindBasis).isEqualTo(KindBasis.USER_DECLARED)
    }

    @Test
    fun `placeholder unresolved declaration never promotes to a definite kind`() {
        val captured = FinancialRecordingPolicy.decide(
            observation(
                economicKind = null,
                original = explicit(Money.of("39.50")),
                userDeclaration = ExplicitFinancialDeclaration(EconomicKind.UNRESOLVED_POSTED_MOVEMENT),
            ),
        ) as RecordingDecision.CapturedUnresolvedKind

        assertThat(captured.economicKind).isEqualTo(EconomicKind.UNRESOLVED_POSTED_MOVEMENT)
        assertThat(captured.kindResolved).isFalse()
    }

    @Test
    fun `user declaration cannot bypass money validation`() {
        val invalid = FinancialRecordingPolicy.decide(
            observation(
                economicKind = null,
                original = explicit(Money.of("-5.00")),
                userDeclaration = ExplicitFinancialDeclaration(EconomicKind.INCOME),
            ),
        ) as RecordingDecision.InvalidObservation

        assertThat(invalid.issues).containsExactly(FinancialInputIssue.NEGATIVE_AMOUNT)
        assertThat(invalid.observation.userDeclaration?.kind).isEqualTo(EconomicKind.INCOME)
    }

    // ---- M1C: reversals legitimately move either way ----

    @Test
    fun `reversal observed out is a legitimate debit reversal and records without conflict`() {
        val recorded = FinancialRecordingPolicy.decide(
            observation(
                economicKind = EconomicKind.REVERSAL,
                direction = MovementDirection.OUT,
                original = explicit(Money.of("84.50")),
            ),
        ) as RecordingDecision.Recorded

        assertThat(recorded.economicKind).isEqualTo(EconomicKind.REVERSAL)
        assertThat(recorded.kindBasis).isEqualTo(KindBasis.OBSERVED)
    }

    @Test
    fun `reversal observed in records without invented conflict`() {
        val recorded = FinancialRecordingPolicy.decide(
            observation(
                economicKind = EconomicKind.REVERSAL,
                direction = MovementDirection.IN,
                original = explicit(Money.of("84.50")),
            ),
        ) as RecordingDecision.Recorded

        assertThat(recorded.economicKind).isEqualTo(EconomicKind.REVERSAL)
    }

    // ---- M1C: heuristic or invalid secondary money never poisons a valid booked primary ----

    @Test
    fun `heuristic invalid original never poisons a valid booked settlement`() {
        val observation = observation(
            original = SupportedMoney(Money.of("-5.005", "USD"), AmountSupport.HEURISTIC_ONLY),
            settlement = explicit(Money.of("50.00")),
        )
        val recorded = FinancialRecordingPolicy.decide(observation) as RecordingDecision.Recorded

        assertThat(recorded.bookedAmount).isEqualTo(Money.of("50.00"))
        assertThat(recorded.bookedBasis).isEqualTo(BookedBasis.SETTLEMENT)
        // The invalid heuristic original is never certified — only retained raw.
        assertThat(recorded.originalAmount).isNull()
        assertThat(recorded.observation.original?.amount).isEqualTo(Money.of("-5.005", "USD"))
        assertThat(recorded.observation.original?.support).isEqualTo(AmountSupport.HEURISTIC_ONLY)
    }

    @Test
    fun `valid booked settlement survives an invalid explicit secondary original`() {
        val observation = observation(
            original = explicit(Money.of("-5.00")),
            settlement = explicit(Money.of("39.50")),
        )
        val recorded = FinancialRecordingPolicy.decide(observation) as RecordingDecision.Recorded

        assertThat(recorded.bookedAmount).isEqualTo(Money.of("39.50"))
        assertThat(recorded.bookedBasis).isEqualTo(BookedBasis.SETTLEMENT)
        // The invalid secondary stays retained verbatim on the observation.
        assertThat(recorded.observation.original?.amount).isEqualTo(Money.of("-5.00"))
        assertThat(recorded.originalAmount).isNull()
    }

    @Test
    fun `every existing legacy status wins over reparsing declarations and new input validation`() {
        listOf("PENDING", "CONFIRMED", "DISMISSED").forEach { status ->
            val legacy = LegacyFacts(status = status, type = "EXPENSE", value = "8450")
            val original = observation(
                economicKind = EconomicKind.INCOME,
                direction = MovementDirection.IN,
                original = explicit(Money.of("10.005", "USD")),
                legacy = legacy,
                userDeclaration = ExplicitFinancialDeclaration(EconomicKind.BORROWING),
            )
            val preserved = FinancialRecordingPolicy.decide(original) as RecordingDecision.PreservedLegacy
            assertThat(preserved.legacy).isEqualTo(legacy)
            assertThat(preserved.observation).isEqualTo(original)
        }
    }

    @Test
    fun `invalid explicit original is not certified for either resolved or unresolved capture`() {
        listOf(EconomicKind.PURCHASE, EconomicKind.UNRESOLVED_POSTED_MOVEMENT).forEach { kind ->
            val original = observation(
                economicKind = kind,
                original = explicit(Money.of("-5.00", "USD")),
                settlement = explicit(Money.of("39.50")),
            )
            when (val decision = FinancialRecordingPolicy.decide(original)) {
                is RecordingDecision.Recorded -> {
                    assertThat(decision.originalAmount).isNull()
                    assertThat(decision.settlementAmount).isEqualTo(Money.of("39.50"))
                }
                is RecordingDecision.CapturedUnresolvedKind -> {
                    assertThat(decision.originalAmount).isNull()
                    assertThat(decision.settlementAmount).isEqualTo(Money.of("39.50"))
                }
                else -> error("Expected known money capture, got $decision")
            }
        }
    }

    @Test
    fun `invalid explicit settlement is not certified for either resolved or unresolved capture`() {
        listOf(EconomicKind.PURCHASE, EconomicKind.UNRESOLVED_POSTED_MOVEMENT).forEach { kind ->
            val original = observation(
                economicKind = kind,
                original = explicit(Money.of("39.50")),
                settlement = explicit(Money.of("10.005", "USD")),
            )
            when (val decision = FinancialRecordingPolicy.decide(original)) {
                is RecordingDecision.Recorded -> {
                    assertThat(decision.originalAmount).isEqualTo(Money.of("39.50"))
                    assertThat(decision.settlementAmount).isNull()
                }
                is RecordingDecision.CapturedUnresolvedKind -> {
                    assertThat(decision.originalAmount).isEqualTo(Money.of("39.50"))
                    assertThat(decision.settlementAmount).isNull()
                }
                else -> error("Expected known money capture, got $decision")
            }
        }
    }

    private fun explicit(amount: Money) = SupportedMoney(amount, AmountSupport.EXPLICIT_BOOKED)

    private fun observation(
        posting: PostingEvidence = PostingEvidence(PostingState.POSTED, PostingSupport.EXPLICIT_POSTED),
        economicKind: EconomicKind? = EconomicKind.PURCHASE,
        direction: MovementDirection? = MovementDirection.OUT,
        original: SupportedMoney? = explicit(Money.of("84.50")),
        settlement: SupportedMoney? = null,
        routing: RoutingCoverage = RoutingCoverage.Unresolved,
        eventDate: LocalDate? = LocalDate(2026, 10, 20),
        legacy: LegacyFacts? = null,
        userDeclaration: ExplicitFinancialDeclaration? = null,
        incomeNature: IncomeNature = IncomeNature.UNCLASSIFIED,
    ) = FinancialObservation(
        stableId = StableObservationId.of("obs-1")!!,
        posting = posting,
        economicKind = economicKind,
        direction = direction,
        original = original,
        settlement = settlement,
        routing = routing,
        eventDate = eventDate,
        legacy = legacy,
        userDeclaration = userDeclaration,
        incomeNature = incomeNature,
    )
}
