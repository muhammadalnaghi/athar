package com.athar.core.designsystem.display

import java.math.BigDecimal
import java.text.NumberFormat
import java.util.Locale

/** Exact presentation only: no FX, fixed two-decimal rounding, or floating-point amounts. */
object MoneyTextFormat {
    fun format(amount: BigDecimal, currency: String, locale: Locale): String {
        val formatter = NumberFormat.getNumberInstance(locale).apply {
            maximumFractionDigits = amount.stripTrailingZeros().scale().coerceAtLeast(0)
            minimumFractionDigits = 0
            isGroupingUsed = true
        }
        val sign = if (amount.signum() < 0) "-" else ""
        val number = formatter.format(amount.abs())
        return "\u2066$sign$number\u2069\u00A0${CurrencyCatalog.symbolOf(currency)}"
    }
}
