package com.athar.core.domain.calc

import java.math.BigDecimal
import java.util.Currency

/** Output granularity only: never converts, normalizes currency identity, or rewrites source money. */
internal object WealthMoneyPrecision {
    fun scale(currency: String, vararg sourceAmounts: BigDecimal): Int {
        val standard = try {
            Currency.getInstance(currency).defaultFractionDigits.takeIf { it >= 0 } ?: 2
        } catch (_: IllegalArgumentException) {
            2
        }
        val meaningful = sourceAmounts.maxOfOrNull { it.stripTrailingZeros().scale().coerceAtLeast(0) } ?: 0
        return maxOf(standard, meaningful)
    }
}
