package com.athar.core.domain.model.cashforecast

import kotlinx.collections.immutable.ImmutableList

/** Which input family an identity disclosure refers to. */
enum class SubjectKind { CASH_EFFECT, RESERVATION }

/** Typed reasons a posted effect fails to compatibly settle an expectation. */
enum class SettlementIncompatibility {
    CURRENCY_MISMATCH,
    SIGN_MISMATCH,
    INSUFFICIENT_AMOUNT,
    TARGET_NOT_EXPECTED,
    DOUBLE_SETTLEMENT,
    CLAIMANT_NOT_POSTED,
    AMOUNT_UNAVAILABLE,
    DATE_UNAVAILABLE,
    CONFLICTING_IDENTITY,
}

/** Typed rejection reasons. The calculator rejects rather than truncates. */
enum class CashForecastRejectionReason {
    HORIZON_END_BEFORE_KNOWLEDGE_ASOF,
    HORIZON_EXCEEDS_LIMIT,
    EFFECT_LIMIT_EXCEEDED,
    ANCHOR_AFTER_KNOWLEDGE_ASOF,
    POSTED_EFFECT_BEYOND_KNOWLEDGE_ASOF,
    BLANK_EFFECT_IDENTITY,
    DUPLICATE_ANCHOR_CURRENCY,
    UNAVAILABLE_AMOUNT_WITHOUT_CURRENCY,
    UNRESOLVABLE_CURRENCY_HINT,
    CURRENCY_HINT_CONTRADICTION,
    NEGATIVE_RESERVE_MAGNITUDE,
    INITIAL_RESERVE_CURRENCY_MISMATCH,
    ARITHMETIC_UNSUPPORTED,
}

/**
 * Typed, structured disclosures. Withholding disclosures (contradictions, unresolved or
 * incompatible settlement links, unknown reserves, missing facts) demote confidence;
 * [DuplicateCollapsed] and [VerifiedSettlement] are benign provenance records.
 */
sealed interface CashForecastDisclosure {
    /** Stable sort key so disclosure order is deterministic without clocks or randomness. */
    val sortKey: String

    /** Intraday order of same-day movements is never observable here; only end-of-day nets are. */
    object IntradayMinimaUnknown : CashForecastDisclosure {
        override val sortKey: String get() = "0-intraday"
    }

    /** Exact duplicate identity collapsed to a single occurrence (benign). */
    data class DuplicateCollapsed(val kind: SubjectKind, val id: String, val occurrences: Int) : CashForecastDisclosure {
        override val sortKey: String get() = "1-duplicate-${kind.name}-$id"
    }

    /** Same identity with contradictory representations: every occurrence withheld. */
    data class ContradictoryWithheld(val kind: SubjectKind, val id: String, val occurrences: Int) : CashForecastDisclosure {
        override val sortKey: String get() = "2-contradictory-${kind.name}-$id"
    }

    /** Expectation suppressed by an explicit, compatible posted link (benign). */
    data class VerifiedSettlement(val expectationId: String, val postedEffectId: String) : CashForecastDisclosure {
        override val sortKey: String get() = "3-verified-$expectationId-$postedEffectId"
    }

    /** Posted effect links to an expectation identity that was not supplied. */
    data class UnresolvedSettlementReference(val postedEffectId: String, val referencedExpectationId: String) :
        CashForecastDisclosure {
        override val sortKey: String get() = "4-unresolved-$postedEffectId-$referencedExpectationId"
    }

    /** Link present but not compatible; no paid status is fabricated. */
    data class IncompatibleSettlementLink(
        val expectationId: String,
        val postedEffectId: String,
        val reason: SettlementIncompatibility,
    ) : CashForecastDisclosure {
        override val sortKey: String get() = "5-incompatible-$expectationId-$postedEffectId-${reason.name}"
    }

    data class UnknownInitialReserves(val currency: String) : CashForecastDisclosure {
        override val sortKey: String get() = "6-reserves-$currency"
    }

    data class MissingAnchor(val currency: String) : CashForecastDisclosure {
        override val sortKey: String get() = "7-anchor-$currency"
    }

    data class UnsupportedCurrency(val currency: String) : CashForecastDisclosure {
        override val sortKey: String get() = "8-unsupported-$currency"
    }

    /** Input money carries more fractional digits than the currency's minor units. */
    data class PrecisionExceeded(val sourceId: String, val currency: String, val scale: Int, val maxScale: Int) :
        CashForecastDisclosure {
        override val sortKey: String get() = "9-precision-$currency-$sourceId"
    }

    data class AttestationIncomplete(val scopeComplete: Boolean, val anchorFresh: Boolean) : CashForecastDisclosure {
        override val sortKey: String get() = "a-attestation"
    }

    /** Known-identity fact whose amount is unavailable; it can never move invented money. */
    data class UnavailableAmount(val kind: SubjectKind, val id: String, val currency: String) : CashForecastDisclosure {
        override val sortKey: String get() = "b-unavailable-amount-${kind.name}-$id"
    }

    /** Known-identity fact whose date is unavailable; it cannot be placed on the path. */
    data class UnavailableDate(val kind: SubjectKind, val id: String, val currency: String) : CashForecastDisclosure {
        override val sortKey: String get() = "b-unavailable-date-${kind.name}-$id"
    }

    /** Several distinct posted claimants reference one expectation; aggregation is not guessed. */
    data class AmbiguousSettlementClaimants(
        val expectationId: String,
        val claimantIds: ImmutableList<String>,
    ) : CashForecastDisclosure {
        override val sortKey: String get() = "b-ambiguous-$expectationId"
    }

    /** A release would drive known reserves below zero; reserve/available become unknown. */
    data class ReserveUnderflowUnknown(val currency: String, val reservationId: String) : CashForecastDisclosure {
        override val sortKey: String get() = "b-underflow-$currency-$reservationId"
    }
}

/** Typed terminal outcomes: a computed forecast or a rejection. Never a truncated guess. */
sealed interface CashForecastOutcome {
    data class Forecast(val result: CashForecastResult) : CashForecastOutcome

    data class Rejected(
        val reasons: ImmutableList<CashForecastRejectionReason>,
        val offendingIds: ImmutableList<String>,
    ) : CashForecastOutcome
}
