package com.athar.feature.plan.investments

import com.athar.core.common.money.Money
import java.math.BigDecimal

/** Small input adapter; no changes to original records, currency codes, or persistence conventions. */
internal object InvestmentEntryRules {
    private val decimal = Regex("[+-]?(?:[0-9]+|[0-9]{1,3}(?:,[0-9]{3})+)(?:\\.[0-9]+)?")

    fun parseDecimal(raw: String): BigDecimal? {
        val trimmed = raw.trim()
        if (trimmed.length > 128) return null
        val normalized = trimmed.map { char ->
            val digit = Character.digit(char, 10)
            when {
                digit >= 0 -> '0' + digit
                char == '٫' -> '.'
                char == '٬' -> ','
                else -> char
            }
        }.joinToString("")
        if (!decimal.matches(normalized)) return null
        return normalized.replace(",", "").toBigDecimalOrNull()
    }

    /** Legacy family tables store signed Long minor units at scale2, regardless of currency. */
    fun isExactlyStorable(money: Money): Boolean = try {
        money.amount.movePointRight(2).longValueExact()
        true
    } catch (_: ArithmeticException) {
        false
    }
}
