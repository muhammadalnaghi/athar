package com.athar.core.domain.calc.cashforecast

import com.athar.core.common.money.Money
import com.athar.core.domain.model.cashforecast.CashAnchor
import com.athar.core.domain.model.cashforecast.CashEffect
import com.athar.core.domain.model.cashforecast.CashForecastDisclosure
import com.athar.core.domain.model.cashforecast.CashForecastRequest
import com.athar.core.domain.model.cashforecast.CurrencyPartitionForecast
import com.athar.core.domain.model.cashforecast.DatedCashDay
import com.athar.core.domain.model.cashforecast.ForecastEffectReference
import com.athar.core.domain.model.cashforecast.PartitionHeadline
import com.athar.core.domain.model.cashforecast.PartitionStatus
import com.athar.core.domain.model.cashforecast.ReservationDirection
import com.athar.core.domain.model.cashforecast.ReservationEffect
import com.athar.core.domain.model.cashforecast.SubjectKind
import kotlinx.collections.immutable.toPersistentList
import kotlinx.datetime.LocalDate
import java.math.BigDecimal

internal object CashForecastPartition {
    fun build(
        currency: String, anchor: CashAnchor?, allCash: List<CashEffect>, allReserves: List<ReservationEffect>,
        request: CashForecastRequest, context: ForecastContext,
    ): CurrencyPartitionForecast {
        if (anchor == null) context.disclosures += CashForecastDisclosure.MissingAnchor(currency)
        if (anchor == null || currency in context.withheld) {
            return CurrencyPartitionForecast(currency, PartitionStatus.WITHHELD, null, emptyList<DatedCashDay>().toPersistentList(), null)
        }
        val cashFacts = allCash.filter { it.literalCurrency() == currency }
        val reserveFacts = allReserves.filter { it.literalCurrency() == currency }
        val cashByDay = cashFacts.filter { inWindow(it.valueDate, anchor, request) }.groupBy { checkNotNull(it.valueDate) }
        val reservesByDay = reserveFacts.filter { inWindow(it.valueDate, anchor, request) }.groupBy { checkNotNull(it.valueDate) }
        val unknownReserveTiming = reserveFacts.any { it.valueDate == null }
        var cash = anchor.cash
        var reserved = anchor.reserved
        if (reserved == null) {
            context.partial += currency
            context.disclosures += CashForecastDisclosure.UnknownInitialReserves(currency)
        }
        val opening = point(anchor.asOf, cash, reserved, emptyList())
        val path = mutableListOf(opening)
        for (date in (cashByDay.keys + reservesByDay.keys).toSortedSet()) {
            val cashToday = cashByDay[date].orEmpty()
            val reservesToday = reservesByDay[date].orEmpty()
            val delta = cashToday.mapNotNull { it.amount }.fold(BigDecimal.ZERO) { total, m -> total.add(m.amount) }
            cash = Money.of(cash.amount.add(delta), currency)
            val knownReserved = reserved
            if (knownReserved != null) {
                reserved = if (unknownReserveTiming || reservesToday.any { it.amount == null }) null else {
                    val reserveDelta = reservesToday.fold(BigDecimal.ZERO) { total, item ->
                        val magnitude = checkNotNull(item.amount).amount
                        total.add(if (item.direction == ReservationDirection.INCREASE) magnitude else magnitude.negate())
                    }
                    val next = knownReserved.amount.add(reserveDelta)
                    if (next.signum() < 0) {
                        for (item in reservesToday.filter { it.direction == ReservationDirection.RELEASE }) {
                            context.disclosures += CashForecastDisclosure.ReserveUnderflowUnknown(currency, item.reservationId)
                        }
                        null
                    } else Money.of(next, currency)
                }
                if (reserved == null) context.partial += currency
            }
            val refs = cashToday.map { ForecastEffectReference(SubjectKind.CASH_EFFECT, it.effectId) } +
                reservesToday.map { ForecastEffectReference(SubjectKind.RESERVATION, it.reservationId) }
            path += point(date, cash, reserved, refs)
        }
        val status = if (currency in context.partial) PartitionStatus.PARTIAL else PartitionStatus.CONFIDENT
        val headline = if (status == PartitionStatus.CONFIDENT) headline(path) else null
        return CurrencyPartitionForecast(currency, status, opening, path.toPersistentList(), headline)
    }

    private fun inWindow(date: LocalDate?, anchor: CashAnchor, request: CashForecastRequest): Boolean =
        date != null && date > anchor.asOf && date <= request.horizonEnd

    private fun point(date: LocalDate, cash: Money, reserved: Money?, refs: List<ForecastEffectReference>) =
        DatedCashDay(date, cash, reserved, reserved?.let { cash - it },
            refs.sortedWith(compareBy({ it.kind.ordinal }, { it.id })).toPersistentList())

    private fun headline(path: List<DatedCashDay>): PartitionHeadline {
        val minimum = path.minBy { checkNotNull(it.available).amount }
        val available = checkNotNull(minimum.available)
        return PartitionHeadline(available, minimum.date, if (available.isNegative()) -available else null,
            path.firstOrNull { checkNotNull(it.available).isNegative() }?.date, path.last().cash)
    }
}
