package com.athar.core.domain.calc.cashforecast

import com.athar.core.domain.model.cashforecast.CashEffect
import com.athar.core.domain.model.cashforecast.CashForecastDisclosure
import com.athar.core.domain.model.cashforecast.CashForecastRequest
import com.athar.core.domain.model.cashforecast.ReservationEffect
import com.athar.core.domain.model.cashforecast.SubjectKind

internal data class ForecastFacts(
    val cash: List<CashEffect>,
    val reservations: List<ReservationEffect>,
    val rawCashById: Map<String, List<CashEffect>>,
)

internal object CashForecastIdentity {
    fun resolve(request: CashForecastRequest, context: ForecastContext): ForecastFacts {
        val cash = collapse(request.effects, SubjectKind.CASH_EFFECT, context,
            { it.effectId }, { it.literalCurrency() },
            { listOf(it.literalCurrency(), it.amount?.amount?.stripTrailingZeros(), it.valueDate, it.certainty, it.settlesExpectationId) },
            { it.amount?.amount?.scale() ?: Int.MIN_VALUE },
        )
        val reservations = collapse(request.reservations, SubjectKind.RESERVATION, context,
            { it.reservationId }, { it.literalCurrency() },
            { listOf(it.literalCurrency(), it.amount?.amount?.stripTrailingZeros(), it.valueDate, it.certainty, it.direction) },
            { it.amount?.amount?.scale() ?: Int.MIN_VALUE },
        )
        return ForecastFacts(cash, reservations, request.effects.groupBy { it.effectId })
    }

    private fun <T> collapse(
        items: List<T>, kind: SubjectKind, context: ForecastContext,
        id: (T) -> String, currency: (T) -> String, facts: (T) -> List<Any?>, scale: (T) -> Int,
    ): List<T> = buildList {
        for ((identity, group) in items.groupBy(id).toSortedMap()) {
            if (group.distinctBy(facts).size > 1) {
                context.disclosures += CashForecastDisclosure.ContradictoryWithheld(kind, identity, group.size)
                context.demote(group.map(currency))
            } else {
                // Preserve a supplied representation, including all trailing zeros; never quantize.
                add(group.maxBy(scale))
                if (group.size > 1) context.disclosures += CashForecastDisclosure.DuplicateCollapsed(kind, identity, group.size)
            }
        }
    }
}
