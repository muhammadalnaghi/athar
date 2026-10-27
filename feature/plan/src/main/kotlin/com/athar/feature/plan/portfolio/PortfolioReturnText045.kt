package com.athar.feature.plan.portfolio

import com.athar.core.domain.calc.portfolio.ReturnUndefinedReason
import com.athar.feature.plan.R
import java.math.BigDecimal
import java.math.RoundingMode
import java.text.DecimalFormatSymbols
import java.text.NumberFormat
import java.util.Locale

/**
 * WM045 presentation-only helpers for portfolio return rates and undefined states.
 * Pure and JVM-testable: no Compose, no Android types, no money arithmetic. The
 * engines' BigDecimal results are rendered, never rescaled, never multiplied, and
 * never replaced with an invented value.
 */
internal object PortfolioReturnText045 {

    /**
     * Renders an already-percent engine rate (109.47101751 means +109.47%) with at most
     * two fraction digits, explicit sign, locale grouping, the locale's own digits and
     * decimal separator, and the locale's own percent sign. Mirrors the exact-presentation
     * rules of core design-system number formatting: BigDecimal in, deterministic
     * HALF_UP rounding for display only, no Float/Double anywhere.
     */
    fun formatPercent(ratePercent: BigDecimal, locale: Locale): String {
        val display = ratePercent.setScale(2, RoundingMode.HALF_UP)
        val formatter = NumberFormat.getNumberInstance(locale).apply {
            maximumFractionDigits = 2
            minimumFractionDigits = 0
            isGroupingUsed = true
        }
        val sign = if (ratePercent.signum() < 0) "-" else ""
        val number = formatter.format(display.abs())
        val percent = DecimalFormatSymbols.getInstance(locale).percent
        // Bidi isolates keep the signed number run readable inside Arabic sentences,
        // exactly like the shared money formatting.
        return "\u2066$sign$number$percent\u2069"
    }

    /**
     * Chooses the string resource for an undefined XIRR/TWR result. A structural
     * missing-boundary reason with an empty id list must not render the per-security
     * sentence (its named target would be empty), so it gets a fixed no-evidence
     * sentence. Named securities keep the existing per-security disclosure, and every
     * other reason — ambiguity and solver uncertainty included — stays honestly
     * generic. Never resolves to a zero claim.
     */
    fun undefinedTextRes(reason: ReturnUndefinedReason, unpricedSecurityIds: List<String>): Int = when {
        reason == ReturnUndefinedReason.MISSING_BOUNDARY_VALUATION &&
            unpricedSecurityIds.isEmpty() -> R.string.plan_portfolio_return045_boundary_missing_no_evidence
        reason == ReturnUndefinedReason.MISSING_BOUNDARY_VALUATION ->
            R.string.plan_portfolio_return_undefined_missing
        else -> R.string.plan_portfolio_return_undefined_generic
    }
}
