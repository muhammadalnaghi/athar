package com.athar.core.domain.calc.cashforecast

import com.athar.core.common.money.Money
import com.athar.core.domain.model.cashforecast.CashAnchor
import com.athar.core.domain.model.cashforecast.CashEffect
import com.athar.core.domain.model.cashforecast.CashForecastOutcome
import com.athar.core.domain.model.cashforecast.CashForecastRequest
import com.athar.core.domain.model.cashforecast.CompletenessAttestation
import com.athar.core.domain.model.cashforecast.EffectCertainty
import com.athar.core.domain.model.cashforecast.ReservationDirection
import com.athar.core.domain.model.cashforecast.ReservationEffect
import kotlinx.datetime.LocalDate

/** Explicit synthetic defaults only; production has no currency/date defaults. */
internal object CashForecastFixtures {
    fun day(value: String) = LocalDate.parse(value)
    fun money(value: String, currency: String = "SAR") = Money.of(value, currency)
    fun anchor(currency: String = "SAR", reserved: String? = "0") =
        CashAnchor(day("2026-01-31"), money("1000", currency), reserved?.let { money(it, currency) })
    fun cash(id: String, amount: String? = "-100", date: String? = "2026-02-05",
        currency: String = "SAR", certainty: EffectCertainty = EffectCertainty.EXPECTED, link: String? = null) =
        CashEffect(id, amount?.let { money(it, currency) }, date?.let(::day), certainty, link, if (amount == null) currency else null)
    fun reserve(id: String, amount: String? = "100", date: String? = "2026-02-05",
        direction: ReservationDirection = ReservationDirection.INCREASE) =
        ReservationEffect(id, amount?.let { money(it) }, direction, date?.let(::day), EffectCertainty.EXPECTED, if (amount == null) "SAR" else null)
    fun request(effects: List<CashEffect> = emptyList(), reservations: List<ReservationEffect> = emptyList(),
        anchors: List<CashAnchor> = listOf(anchor())) =
        CashForecastRequest(anchors, effects, reservations, day("2026-02-28"), day("2026-02-28"), CompletenessAttestation(true, true))
    fun result(request: CashForecastRequest) = (CashForecastCalculator.forecast(request) as CashForecastOutcome.Forecast).result
}
