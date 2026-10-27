package com.athar.core.domain.model.financial

import com.athar.core.common.money.Money
import kotlinx.datetime.LocalDate

/**
 * Stable, source-agnostic identity of one observed financial event
 * (WM028-07). Distinct from any cross-source economic identity: equality here
 * means "same observed event", never "same economic movement".
 */
@JvmInline
value class StableObservationId(val value: String) {
    init {
        require(value.isNotBlank()) { "StableObservationId must not be blank" }
    }

    companion object {
        /** Blank/whitespace identities are invalid input, never auto-generated. */
        fun of(raw: String): StableObservationId? = if (raw.isBlank()) null else StableObservationId(raw)
    }
}

/** Lifecycle posting state of the observed event. */
enum class PostingState { POSTED, AUTHORIZED, RELEASED, DECLINED, NOTICE, SCHEDULED }

/**
 * How strongly the posting state itself is supported. A parse Success, a
 * merchant rule, a catalog/model suggestion or a numeric confidence is
 * [HEURISTIC_ONLY] — it is never proof that funds moved (ADR-010).
 */
enum class PostingSupport { EXPLICIT_POSTED, HEURISTIC_ONLY }

data class PostingEvidence(val state: PostingState, val support: PostingSupport)

/**
 * How strongly one observed money value is supported as a transaction or
 * booked amount (WM028-07 M1B). Explicit posting alone never proves that a
 * number is the transaction amount rather than a balance — support is typed
 * per amount, never a numeric/category confidence.
 */
enum class AmountSupport {
    /** Bank-stated booked/posted amount for this movement. */
    EXPLICIT_BOOKED,

    /** Explicitly extracted as the transaction amount of the movement. */
    EXPLICIT_TRANSACTION,

    /** Found by heuristic only (e.g. could be a balance) — never auto-recorded. */
    HEURISTIC_ONLY,
    ;

    /** Explicit transaction/booked money may auto-record; heuristic-only may not. */
    val isExplicit: Boolean
        get() = this != HEURISTIC_ONLY
}

/** One observed money value together with its own typed support. */
data class SupportedMoney(
    val amount: Money,
    val support: AmountSupport,
)

/**
 * Income nature of an economic kind (WM028-07 M1B). [UNCLASSIFIED] is the
 * default — the policy never infers salary or any other specific source.
 */
enum class IncomeNature { EARNED, INVESTMENT, OTHER, UNCLASSIFIED }

/**
 * Economic-kind vocabulary from the approved financial-events design.
 * [UNRESOLVED_POSTED_MOVEMENT] and [LEGACY_UNVERIFIED] are explicit
 * non-guessing labels; the policy never promotes them to a concrete kind.
 */
enum class EconomicKind {
    PURCHASE,
    INCOME,
    BORROWING,
    OWN_TRANSFER,
    CARD_REPAYMENT,
    REFUND,
    REVERSAL,
    FEE,
    RECONCILIATION,
    UNRESOLVED_POSTED_MOVEMENT,
    LEGACY_UNVERIFIED,
}

/** Observed direction of money movement, when the evidence states one. */
enum class MovementDirection { IN, OUT }

/**
 * Observed account routing. [Unresolved] is an explicit coverage limitation —
 * it is never replaced by an invented manual-account leg.
 */
sealed interface RoutingCoverage {
    data class Resolved(val accountRef: String) : RoutingCoverage
    data object Unresolved : RoutingCoverage
}

/**
 * Facts from an existing historical row, retained verbatim. A non-null value
 * always selects legacy preservation, even when a parser supplies a concrete
 * new kind. It is not generic source metadata for a new payment. Historical
 * corrections require the separate preview/apply/conditional-undo boundary.
 */
data class LegacyFacts(
    val status: String?,
    val type: String?,
    val value: String?,
)

/**
 * An explicit user statement about a financial fact. Its user basis is
 * distinct evidence — separate from any merchant-side correction.
 */
data class ExplicitFinancialDeclaration(
    val kind: EconomicKind,
    val note: String? = null,
)

/**
 * A merchant-side category correction. It belongs to the categorization
 * boundary and is deliberately NOT part of [FinancialObservation]: a
 * correction must never gate, enable or reshape a recording decision
 * (WM028-07 / ADR-010).
 */
data class MerchantCategoryCorrection(
    val merchantNormalized: String,
    val categoryId: String,
)

/**
 * One observed financial event as independent evidence dimensions: posting
 * state/support, economic kind, per-amount support, routing and date coverage.
 * By construction this type carries no category, rule, catalog or model-score
 * field — those are not recording-policy inputs.
 *
 * @param original the actual original money when observed (may be a foreign
 *   currency), with its own typed [AmountSupport]
 * @param settlement the settlement money when observed, with its own typed
 *   [AmountSupport]; null means absent — never a zero placeholder — and no FX
 *   conversion is ever synthesized
 * @param incomeNature observed income nature; defaults to [IncomeNature.UNCLASSIFIED],
 *   never salary
 */
data class FinancialObservation(
    val stableId: StableObservationId,
    val posting: PostingEvidence,
    val economicKind: EconomicKind?,
    val direction: MovementDirection?,
    val original: SupportedMoney?,
    val settlement: SupportedMoney?,
    val routing: RoutingCoverage,
    val eventDate: LocalDate?,
    val legacy: LegacyFacts?,
    val userDeclaration: ExplicitFinancialDeclaration?,
    val incomeNature: IncomeNature = IncomeNature.UNCLASSIFIED,
)
