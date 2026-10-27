package com.athar.core.domain.calc.cashforecast

import com.athar.core.domain.model.cashforecast.CashEffect
import com.athar.core.domain.model.cashforecast.CashForecastDisclosure
import com.athar.core.domain.model.cashforecast.ReservationEffect

/** Per-invocation mutable accumulators; none escape the immutable result. */
internal class ForecastContext {
    val currencies = sortedSetOf<String>()
    val partial = mutableSetOf<String>()
    val withheld = mutableSetOf<String>()
    val disclosures = mutableListOf<CashForecastDisclosure>(CashForecastDisclosure.IntradayMinimaUnknown)

    fun demote(currencies: Iterable<String>) { partial += currencies }
}

internal fun CashEffect.literalCurrency(): String = amount?.currency ?: checkNotNull(currencyHint)
internal fun ReservationEffect.literalCurrency(): String = amount?.currency ?: checkNotNull(currencyHint)
