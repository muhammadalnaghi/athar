package com.athar.core.domain.model.cashforecast

import com.athar.core.common.money.Money
import kotlinx.collections.immutable.ImmutableList
import kotlinx.collections.immutable.toPersistentList
import kotlinx.datetime.LocalDate

/** Caller-declared certainty of a dated effect; the calculator never upgrades certainty. */
enum class EffectCertainty { POSTED, EXPECTED }

/** Reservation movement; increases and releases alter availability, never bank cash. */
enum class ReservationDirection { INCREASE, RELEASE }

/**
 * Dated end-of-day cash anchor for exactly one literal currency (taken from [cash]).
 * [reserved] is the initial reserved amount; `null` means unknown — never zero.
 * Negative known cash is a valid overdraft.
 */
data class CashAnchor(
    val asOf: LocalDate,
    val cash: Money,
    val reserved: Money?,
) {
    val currency: String get() = cash.currency
}

/**
 * A caller-scoped cash movement under a stable economic-effect identity.
 * [amount] is signed: positive inflow, negative outflow. [settlesExpectationId] is an
 * explicit link from a POSTED effect to the EXPECTED effect identity it settles; the
 * calculator never infers identity from amounts, text, or dates.
 */
data class CashEffect(
    val effectId: String,
    /** Signed known amount; `null` means the amount is unavailable and is never invented. */
    val amount: Money?,
    /** Dated value date; `null` means the date is unavailable, never defaulted. */
    val valueDate: LocalDate?,
    val certainty: EffectCertainty,
    val settlesExpectationId: String? = null,
    /**
     * Explicit literal currency honored only when [amount] is null. A hint contradicting
     * the observed [Money] currency is rejected; there is no default currency.
     */
    val currencyHint: String? = null,
)

/**
 * An independent reservation increase or release under a stable identity. [amount] is a
 * nonnegative magnitude; direction is explicit. Reservations reduce available-after-reserves
 * only and are never double-counted as expenses. Null amount/date means unavailable.
 */
data class ReservationEffect(
    val reservationId: String,
    val amount: Money?,
    val direction: ReservationDirection,
    val valueDate: LocalDate?,
    val certainty: EffectCertainty,
    val currencyHint: String? = null,
)

/** Caller attestation over input scope and freshness; false values demote all headlines. */
data class CompletenessAttestation(
    val scopeComplete: Boolean,
    val anchorFresh: Boolean,
)

/**
 * Full request for the conditional dated cash-path forecast. Inputs are defensively
 * copied into persistent lists; currency partitions come from literal [Money] currencies.
 */
class CashForecastRequest(
    anchors: List<CashAnchor>,
    effects: List<CashEffect>,
    reservations: List<ReservationEffect>,
    val knowledgeAsOf: LocalDate,
    val horizonEnd: LocalDate,
    val attestation: CompletenessAttestation,
) {
    val anchors: ImmutableList<CashAnchor> = anchors.toPersistentList()
    val effects: ImmutableList<CashEffect> = effects.toPersistentList()
    val reservations: ImmutableList<ReservationEffect> = reservations.toPersistentList()
}
