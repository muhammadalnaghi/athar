package com.athar.core.domain.calc.financial

import com.athar.core.common.money.Money
import com.athar.core.domain.model.financial.EconomicKind
import com.athar.core.domain.model.financial.FinancialAmountValidator
import com.athar.core.domain.model.financial.FinancialInputIssue
import com.athar.core.domain.model.financial.FinancialObservation
import com.athar.core.domain.model.financial.MovementDirection
import com.athar.core.domain.model.financial.PostingState
import com.athar.core.domain.model.financial.PostingSupport
import com.athar.core.domain.model.financial.SupportedMoney
import com.athar.core.domain.model.financial.ValidatedFinancialInput

/**
 * Pure evidence-based recording policy (WM028-07, ADR-010; reconciled M1C
 * truth table in docs/specs/WM028-07). Category/rule/catalog/model confidence
 * is not an input by construction: the observation type carries none, and a
 * merchant category correction is a different type on a different boundary.
 * Missing category or account alias never blocks reliable known-currency
 * money; original-only foreign money stays in its real currency.
 *
 * Decision order (first match wins): preserve legacy → skip non-posted → skip
 * unreliable posting → admit explicit-support money only → validate admitted
 * money → pick a valid booked basis → resolve kind (concrete user declaration
 * overrides inference; placeholders never promote) → detect genuine conflicts
 * (reversals move either way) → record. Typed decisions live in
 * FinancialRecordingDecisions.kt (same package).
 */
object FinancialRecordingPolicy {

    fun decide(observation: FinancialObservation): RecordingDecision {
        // 1. Legacy evidence is preserved verbatim BEFORE any new-input logic,
        //    regardless of reparsed kinds or user declarations.
        if (observation.legacy != null || observation.economicKind == EconomicKind.LEGACY_UNVERIFIED) {
            return RecordingDecision.PreservedLegacy(observation)
        }
        if (observation.posting.state != PostingState.POSTED) {
            return RecordingDecision.NonPosted(observation, observation.posting.state)
        }
        if (observation.posting.support == PostingSupport.HEURISTIC_ONLY) {
            return RecordingDecision.Unresolved(observation, UnresolvedReason.UNRELIABLE_POSTING_EVIDENCE)
        }

        // 2. Admitted monetary facts are explicit-support money only. Heuristic
        //    money is untrusted retained evidence — never validated, never
        //    booked, never certified.
        val explicitOriginal = observation.original?.takeIf { it.support.isExplicit }
        val explicitSettlement = observation.settlement?.takeIf { it.support.isExplicit }
        if (explicitOriginal == null && explicitSettlement == null) {
            return RecordingDecision.Unresolved(
                observation,
                if (observation.original == null && observation.settlement == null) {
                    UnresolvedReason.NO_KNOWN_AMOUNT
                } else {
                    UnresolvedReason.UNRELIABLE_AMOUNT_SUPPORT
                },
            )
        }

        // 3. Validate admitted money (exact ISO currency/precision; identity is
        //    type-enforced). A heuristic or invalid secondary never poisons a
        //    valid booked primary; if no valid admitted money remains, this is
        //    a typed genuine invalid — never rounded or dropped.
        val issues = linkedSetOf<FinancialInputIssue>()
        val validSettlement = explicitSettlement?.let { admittedAmount(it, observation, issues) }
        val validOriginal = explicitOriginal?.let { admittedAmount(it, observation, issues) }
        val bookedAmount = validSettlement ?: validOriginal
        if (bookedAmount == null) {
            return RecordingDecision.InvalidObservation(observation, issues)
        }
        val bookedBasis = if (validSettlement != null) BookedBasis.SETTLEMENT else BookedBasis.ORIGINAL

        // 4. Kind resolution: a concrete explicit financial user declaration
        //    overrides machine inference; placeholder labels are typed
        //    non-resolution and never promote to a definite kind.
        val declaredKind = observation.userDeclaration?.kind?.takeIf { it.isConcrete() }
        val kind = declaredKind ?: observation.economicKind
        if (kind == null || kind == EconomicKind.UNRESOLVED_POSTED_MOVEMENT) {
            return RecordingDecision.CapturedUnresolvedKind(
                observation = observation,
                bookedAmount = bookedAmount,
                bookedBasis = bookedBasis,
                economicKind = EconomicKind.UNRESOLVED_POSTED_MOVEMENT,
                kindResolved = false,
            )
        }

        // 5. A supported kind contradicting the explicit direction is a genuine
        //    conflict — never silently recorded as spend or income.
        val expectedDirection = expectedDirectionOf(kind)
        if (expectedDirection != null &&
            observation.direction != null &&
            observation.direction != expectedDirection
        ) {
            return RecordingDecision.Conflict(observation, FinancialConflict.KIND_DIRECTION_MISMATCH)
        }

        return RecordingDecision.Recorded(
            observation = observation,
            bookedAmount = bookedAmount,
            bookedBasis = bookedBasis,
            economicKind = kind,
            kindBasis = if (declaredKind != null) KindBasis.USER_DECLARED else KindBasis.OBSERVED,
            incomeNature = observation.incomeNature,
        )
    }

    /**
     * Validates one admitted (explicit-support) money value; failures are
     * accumulated and the value is not bookable.
     */
    private fun admittedAmount(
        supported: SupportedMoney,
        observation: FinancialObservation,
        issues: MutableSet<FinancialInputIssue>,
    ): Money? = when (
        val validated = FinancialAmountValidator.validateNewAmount(
            observation.stableId.value,
            supported.amount.amount,
            supported.amount.currency,
        )
    ) {
        is ValidatedFinancialInput.Valid -> validated.amount
        is ValidatedFinancialInput.Invalid -> {
            issues += validated.issues - FinancialInputIssue.BLANK_OBSERVATION_ID
            null
        }
    }

    private fun EconomicKind.isConcrete(): Boolean =
        this != EconomicKind.UNRESOLVED_POSTED_MOVEMENT && this != EconomicKind.LEGACY_UNVERIFIED

    /**
     * Single-direction kinds with an unambiguous expected flow. REVERSAL is
     * deliberately absent: a debit reversal of prior income legitimately moves
     * OUT and an income reversal moves IN, and absent an original link both are
     * legitimate — no conflict is ever invented. OWN_TRANSFER, RECONCILIATION,
     * UNRESOLVED_POSTED_MOVEMENT and LEGACY_UNVERIFIED also return null.
     */
    private fun expectedDirectionOf(kind: EconomicKind): MovementDirection? = when (kind) {
        EconomicKind.PURCHASE,
        EconomicKind.FEE,
        EconomicKind.CARD_REPAYMENT,
        -> MovementDirection.OUT

        EconomicKind.INCOME,
        EconomicKind.REFUND,
        EconomicKind.BORROWING,
        -> MovementDirection.IN

        EconomicKind.REVERSAL,
        EconomicKind.OWN_TRANSFER,
        EconomicKind.RECONCILIATION,
        EconomicKind.UNRESOLVED_POSTED_MOVEMENT,
        EconomicKind.LEGACY_UNVERIFIED,
        -> null
    }
}
