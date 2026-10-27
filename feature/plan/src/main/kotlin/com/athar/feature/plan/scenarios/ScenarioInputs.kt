package com.athar.feature.plan.scenarios

import java.math.BigDecimal
import java.util.Currency
import kotlinx.datetime.LocalDate

/** One pure adapter result: only validated drafts may carry a calculator outcome. */
internal data class ScenarioDraftResult<T>(val errors: Set<String> = emptySet(), val outcome: T? = null)

/** Bounded plain decimal input; malformed grouping/exponents/inner whitespace never become money. */
internal fun parseScenarioDecimal(raw: String): BigDecimal? {
    if (raw.length > 80) return null
    val value = normalizeScenarioDigits(raw.trim())
    if (!DECIMAL.matches(value)) return null
    return value.replace(",", "").toBigDecimalOrNull()?.takeIf { it.scale() <= 12 }
}

internal fun parseScenarioDate(raw: String): LocalDate? {
    if (raw.length > 10) return null
    val value = normalizeScenarioDigits(raw)
    if (!ISO_DATE.matches(value)) return null
    return try {
        LocalDate.parse(value).takeIf { it.year in 1..9999 }
    } catch (_: IllegalArgumentException) {
        null
    }
}

internal fun validScenarioCurrency(raw: String): Boolean {
    if (!Regex("[A-Z]{3}").matches(raw)) return false
    return try {
        Currency.getInstance(raw).defaultFractionDigits >= 0
    } catch (_: IllegalArgumentException) {
        false
    }
}

private fun normalizeScenarioDigits(raw: String): String = buildString(raw.length) {
    raw.forEach { ch ->
        append(when (ch) {
            in '٠'..'٩' -> '0' + (ch - '٠')
            in '۰'..'۹' -> '0' + (ch - '۰')
            '٫' -> '.'
            '٬' -> ','
            else -> ch
        })
    }
}

private val DECIMAL = Regex("[+-]?(?:[0-9]+|[0-9]{1,3}(?:,[0-9]{3})+)(?:\\.[0-9]+)?")
private val ISO_DATE = Regex("[0-9]{4}-[0-9]{2}-[0-9]{2}")
