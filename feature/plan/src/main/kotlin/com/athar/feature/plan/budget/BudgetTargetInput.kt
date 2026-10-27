package com.athar.feature.plan.budget

import java.math.BigDecimal
import java.util.Currency

/**
 * WM032 target-editor input parsing — pure, exact, no Double anywhere.
 *
 * The raw string is validated against the target's LITERAL currency (not the display
 * currency): supported minor units come from the actual ISO-4217 fraction digits of that
 * currency. Malformed, negative, fractional-minor-unit and overflowing input are all
 * rejected with a reason, so the caller can show an error and leave the stored target
 * untouched — a failed Save must never degrade into a clear.
 */
sealed interface TargetInput {
    data class Valid(val minor: Long) : TargetInput
    data class Invalid(val reason: Reason) : TargetInput

    enum class Reason { MALFORMED, NEGATIVE, FRACTIONAL, OVERFLOW }
}

object BudgetTargetInput {

    /** Actual ISO-4217 minor-unit digits for [currency] (e.g. SAR/USD → 2, JPY → 0). */
    fun fractionDigits(currency: String): Int =
        runCatching { Currency.getInstance(currency).defaultFractionDigits }
            .getOrNull()
            ?.coerceAtLeast(0)
            ?: 2

    fun parse(raw: String, currency: String): TargetInput {
        val text = raw.trim().withLatinDigits()
        if (text.isEmpty() || text.length > 128 || text.contains('e', ignoreCase = true))
            return TargetInput.Invalid(TargetInput.Reason.MALFORMED)
        val parsed = runCatching { BigDecimal(text) }.getOrNull()
            ?: return TargetInput.Invalid(TargetInput.Reason.MALFORMED)
        if (parsed.signum() < 0) return TargetInput.Invalid(TargetInput.Reason.NEGATIVE)
        val minor = parsed.movePointRight(fractionDigits(currency))
        if (minor.stripTrailingZeros().scale() > 0) {
            return TargetInput.Invalid(TargetInput.Reason.FRACTIONAL)
        }
        val exact = try {
            minor.longValueExact()
        } catch (_: ArithmeticException) {
            return TargetInput.Invalid(TargetInput.Reason.OVERFLOW)
        }
        return TargetInput.Valid(exact)
    }

    /** Accepts Arabic-Indic (٠-٩) and Eastern Arabic-Indic (۰-۹) digits from native keyboards. */
    private fun String.withLatinDigits(): String = buildString {
        for (ch in this@withLatinDigits) {
            when (ch) {
                in '٠'..'٩' -> append('0' + (ch - '٠'))
                in '۰'..'۹' -> append('0' + (ch - '۰'))
                '٫' -> append('.')
                else -> append(ch)
            }
        }
    }
}
