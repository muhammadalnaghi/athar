package com.athar.feature.plan.portfolio

import com.athar.feature.plan.investments.InvestmentEntryRules
import java.math.BigDecimal
import kotlinx.datetime.LocalDate

/**
 * Shared strict input parsing for the portfolio editors: decimals accept Arabic
 * decimal separators and grouping exactly like the legacy family-pool entries
 * ([InvestmentEntryRules]); dates accept only strict ISO `YYYY-MM-DD`. Nothing is
 * repaired, defaulted, or date-coerced.
 */
internal object PortfolioInputText {

    fun decimal(raw: String): BigDecimal? = InvestmentEntryRules.parseDecimal(raw)

    fun positiveDecimal(raw: String): BigDecimal? = decimal(raw)?.takeIf { it.signum() > 0 }

    fun nonNegativeDecimal(raw: String): BigDecimal? = decimal(raw)?.takeIf { it.signum() >= 0 }

    fun date(raw: String): LocalDate? = try {
        LocalDate.parse(raw.trim())
    } catch (_: IllegalArgumentException) {
        null
    }
}
