package com.athar.core.domain.calc

import com.athar.core.common.money.Money
import java.math.BigDecimal
import java.math.RoundingMode
import java.util.Currency

object GoalCalc {

    fun averageMonthly(total: Money, months: Int): Money {
        require(months > 0) { "months must be positive" }
        val scale = standardMinorUnitScale(total.currency)
        return Money.of(
            total.amount.divide(BigDecimal(months), scale, RoundingMode.HALF_EVEN),
            total.currency,
        )
    }

    fun savingsRatePercent(monthlyIncome: Money, monthlyExpense: Money): BigDecimal? {
        require(monthlyIncome.currency == monthlyExpense.currency) {
            "income/expense currency mismatch: ${monthlyIncome.currency} vs ${monthlyExpense.currency}"
        }
        if (monthlyIncome.amount.signum() <= 0) return null
        return monthlyIncome.amount
            .subtract(monthlyExpense.amount)
            .multiply(ONE_HUNDRED)
            .divide(monthlyIncome.amount, PERCENT_SCALE, RoundingMode.HALF_EVEN)
    }

    fun savingsRateProgress(actualPercent: BigDecimal?, targetPercent: Int): BigDecimal {
        require(targetPercent in 0..100) { "targetPercent must be in 0..100" }
        if (actualPercent == null) return BigDecimal.ZERO
        if (targetPercent == 0) return BigDecimal.ONE
        return actualPercent
            .divide(BigDecimal(targetPercent), PROGRESS_SCALE, RoundingMode.HALF_EVEN)
            .coerceRatio()
    }

    fun emergencyTargetAmount(monthlyExpense: Money, targetMonths: Int): Money {
        require(targetMonths > 0) { "targetMonths must be positive" }
        val safeExpense = monthlyExpense.coerceAtLeastZero()
        return Money.of(safeExpense.amount.multiply(BigDecimal(targetMonths)), safeExpense.currency)
    }

    fun emergencyMonthsCovered(liquidBalance: Money, monthlyExpense: Money): BigDecimal? {
        require(liquidBalance.currency == monthlyExpense.currency) {
            "liquid/expense currency mismatch: ${liquidBalance.currency} vs ${monthlyExpense.currency}"
        }
        if (monthlyExpense.amount.signum() <= 0) return null
        return liquidBalance
            .coerceAtLeastZero()
            .amount
            .divide(monthlyExpense.amount, PERCENT_SCALE, RoundingMode.HALF_EVEN)
    }

    fun emergencyProgress(monthsCovered: BigDecimal?, targetMonths: Int): BigDecimal {
        require(targetMonths > 0) { "targetMonths must be positive" }
        if (monthsCovered == null) return BigDecimal.ONE
        return monthsCovered
            .divide(BigDecimal(targetMonths), PROGRESS_SCALE, RoundingMode.HALF_EVEN)
            .coerceRatio()
    }

    private fun Money.coerceAtLeastZero(): Money =
        if (amount.signum() < 0) Money.zero(currency) else this

    private fun BigDecimal.coerceRatio(): BigDecimal =
        when {
            this < BigDecimal.ZERO -> BigDecimal.ZERO
            this > BigDecimal.ONE -> BigDecimal.ONE
            else -> this
        }

    private val ONE_HUNDRED = BigDecimal("100")

    /**
     * Standard minor-unit scale for [currency]: the ISO-4217 default fraction digits
     * (SAR 2, KWD/BHD 3, JPY 0, ...). For unknown or pseudo-currency codes (no
     * fraction-digit definition) this falls back to [MONEY_SCALE] — a conservative,
     * documented precision default only. The literal currency code is always
     * preserved; no FX conversion or relabeling ever occurs here.
     */
    private fun standardMinorUnitScale(currency: String): Int {
        val fractionDigits = runCatching { Currency.getInstance(currency).defaultFractionDigits }
            .getOrDefault(MONEY_SCALE)
        return if (fractionDigits >= 0) fractionDigits else MONEY_SCALE
    }

    private const val MONEY_SCALE = 2
    private const val PERCENT_SCALE = 1
    private const val PROGRESS_SCALE = 4
}
