package com.athar.core.domain.calc.financial

import com.athar.core.common.money.Money
import com.athar.core.domain.model.financial.EconomicKind
import com.athar.core.domain.model.financial.FinancialAmountValidator
import com.athar.core.domain.model.financial.FinancialInputIssue
import com.athar.core.domain.model.financial.FinancialObservation
import com.athar.core.domain.model.financial.IncomeNature
import com.athar.core.domain.model.financial.LegacyFacts
import com.athar.core.domain.model.financial.PostingState
import com.athar.core.domain.model.financial.RoutingCoverage
import com.athar.core.domain.model.financial.StableObservationId
import com.athar.core.domain.model.financial.SupportedMoney
import com.athar.core.domain.model.financial.ValidatedFinancialInput
import kotlinx.datetime.LocalDate

/** Why a posted observation stays in review instead of being guessed. */
enum class UnresolvedReason {
    /** Parse/heuristic success only — never proof that funds posted. */
    UNRELIABLE_POSTING_EVIDENCE,

    /** Money is present but heuristic-only — never proof of a transaction amount. */
    UNRELIABLE_AMOUNT_SUPPORT,

    /** No original and no settlement money — never a zero placeholder. */
    NO_KNOWN_AMOUNT,
}

/** Why [RecordingDecision.Recorded.economicKind] is trusted. */
enum class KindBasis { OBSERVED, USER_DECLARED }

/** Which observed money backs the booked amount of a retained movement. */
enum class BookedBasis { ORIGINAL, SETTLEMENT }

/** Typed genuine financial conflicts that must never be silently recorded. */
enum class FinancialConflict {
    /** A supported economic kind contradicts the explicitly observed direction. */
    KIND_DIRECTION_MISMATCH,
}

/**
 * Typed recording decisions (WM028-07, M1B/M1C): reliably recorded posted
 * money, captured unknown-kind money, typed invalid input, genuine financial
 * ambiguity, genuine conflicts, non-posted observations, or preserved legacy
 * information. Every decision retains the complete unmodified observation —
 * nothing is truncated, zero-filled or dropped. There is deliberately no
 * batch entry point: this seam decides one observation at a time and never
 * queues history for processing.
 */
sealed interface RecordingDecision {
    /** The complete, unmodified observation behind this decision. */
    val observation: FinancialObservation

    val observationId: StableObservationId
        get() = observation.stableId

    /**
     * Reliable posted money recorded with its actual currency. [bookedAmount]
     * is a supported settlement when present, otherwise the actual original
     * currency ([bookedBasis]); the other money stays retained on
     * [observation]. No FX rate or settlement conversion is ever synthesized.
     */
    data class Recorded(
        override val observation: FinancialObservation,
        val bookedAmount: Money,
        val bookedBasis: BookedBasis,
        val economicKind: EconomicKind,
        val kindBasis: KindBasis,
        val incomeNature: IncomeNature,
    ) : RecordingDecision {
        /** Certified valid original money; unsupported/invalid evidence stays raw on [observation]. */
        val originalAmount: Money?
            get() = observation.validatedObservedMoney(observation.original)

        /** Certified valid settlement money; unsupported/invalid evidence stays raw on [observation]. */
        val settlementAmount: Money?
            get() = observation.validatedObservedMoney(observation.settlement)

        val routing: RoutingCoverage
            get() = observation.routing

        val eventDate: LocalDate?
            get() = observation.eventDate

        val legacy: LegacyFacts?
            get() = observation.legacy

        /** Recorded money always has a resolved concrete kind. */
        val kindResolved: Boolean
            get() = true
    }

    /**
     * Reliably posted known money whose economic kind is unresolved: the money
     * is retained with kind [economicKind] = UNRESOLVED_POSTED_MOVEMENT and an
     * explicit [kindResolved]=false flag. It is never earnings and never an
     * invented leg; routing/date gaps stay disclosed on [observation].
     */
    data class CapturedUnresolvedKind(
        override val observation: FinancialObservation,
        val bookedAmount: Money,
        val bookedBasis: BookedBasis,
        val economicKind: EconomicKind,
        val kindResolved: Boolean,
    ) : RecordingDecision {
        /** Certified valid original money; unsupported/invalid evidence stays raw on [observation]. */
        val originalAmount: Money?
            get() = observation.validatedObservedMoney(observation.original)

        /** Certified valid settlement money; unsupported/invalid evidence stays raw on [observation]. */
        val settlementAmount: Money?
            get() = observation.validatedObservedMoney(observation.settlement)

        val routing: RoutingCoverage
            get() = observation.routing

        val eventDate: LocalDate?
            get() = observation.eventDate
    }

    /**
     * Typed failure for invalid new-input money (unknown currency, unsupported
     * precision, negative/zero amount). The complete observation is kept —
     * never truncated, never quantized, never replaced by a zero.
     */
    data class InvalidObservation(
        override val observation: FinancialObservation,
        val issues: Set<FinancialInputIssue>,
    ) : RecordingDecision

    /** Posted observation retained as genuine financial ambiguity for review. */
    data class Unresolved(
        override val observation: FinancialObservation,
        val reason: UnresolvedReason,
    ) : RecordingDecision

    /** A genuine financial conflict (e.g. purchase kind + IN direction) for review. */
    data class Conflict(
        override val observation: FinancialObservation,
        val conflict: FinancialConflict,
    ) : RecordingDecision

    /** Authorization/release/decline/notice/schedule — retained, never consumption. */
    data class NonPosted(
        override val observation: FinancialObservation,
        val state: PostingState,
    ) : RecordingDecision {
        val legacy: LegacyFacts?
            get() = observation.legacy
    }

    /**
     * Legacy-unverified evidence preserved verbatim — decided before any
     * new-input validation. Never re-verified, requeued or rewritten; repairing
     * existing history is a later explicit preview/apply seam, not this policy.
     */
    data class PreservedLegacy(
        override val observation: FinancialObservation,
    ) : RecordingDecision {
        val legacy: LegacyFacts?
            get() = observation.legacy
    }
}

/** A retained secondary value is not certified solely because its source claimed support. */
private fun FinancialObservation.validatedObservedMoney(candidate: SupportedMoney?): Money? {
    if (candidate == null || !candidate.support.isExplicit) return null
    return when (
        val validation = FinancialAmountValidator.validateNewAmount(
            stableId.value,
            candidate.amount.amount,
            candidate.amount.currency,
        )
    ) {
        is ValidatedFinancialInput.Valid -> validation.amount
        is ValidatedFinancialInput.Invalid -> null
    }
}
