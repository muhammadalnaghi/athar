package com.athar.core.domain.calc

import com.athar.core.common.money.Money
import java.math.BigDecimal
import java.math.MathContext
import java.math.RoundingMode
import java.util.Currency

/** Family-pool contribution math; no FX, prices, or market ownership are inferred. */
object InvestmentsCalc {

    /** Dimensionless display compatibility API; monetary endpoints never become Double. */
    fun sharePercent(amount: Money, totalCorpus: Money): Double =
        shareFraction(amount, totalCorpus).toDouble()

    /** Same-currency contribution composition, not a certificate of market ownership. */
    fun shareFraction(amount: Money, totalCorpus: Money): BigDecimal {
        require(amount.currency == totalCorpus.currency) { "Contribution and corpus currencies differ" }
        if (totalCorpus.amount.signum() <= 0) return BigDecimal.ZERO
        require(amount.amount.signum() >= 0 && amount.amount <= totalCorpus.amount) {
            "Contribution must be nonnegative and no greater than the corpus"
        }
        return amount.amount.divide(totalCorpus.amount, MathContext.DECIMAL128)
    }

    /**
     * Legacy dimensionless-ratio API. Decimal valueOf avoids binary-constructor noise.
     * Financial allocation from recorded contributions must use the three-Money overload
     * so an intermediate display ratio cannot round a monetary endpoint.
     */
    fun shareReturn(totalReturn: Money, sharePct: Double): Money {
        require(sharePct.isFinite()) { "Share must be finite" }
        return shareReturn(totalReturn, BigDecimal.valueOf(sharePct))
    }

    fun shareReturn(totalReturn: Money, sharePct: BigDecimal): Money {
        require(sharePct >= BigDecimal.ZERO && sharePct <= BigDecimal.ONE) { "Share must be between zero and one" }
        val amount = totalReturn.amount.multiply(sharePct)
            .setScale(returnScale(totalReturn), RoundingMode.HALF_EVEN)
        return Money.of(amount, totalReturn.currency)
    }

    /** Multiply first and divide once, rounding only the final currency amount. */
    fun shareReturn(totalReturn: Money, contribution: Money, totalCorpus: Money): Money {
        require(totalReturn.currency == contribution.currency && contribution.currency == totalCorpus.currency) {
            "Return allocation requires one literal currency"
        }
        require(totalCorpus.amount.signum() > 0) { "Return allocation requires a positive corpus" }
        require(contribution.amount.signum() >= 0 && contribution.amount <= totalCorpus.amount) {
            "Contribution must be nonnegative and no greater than the corpus"
        }
        val amount = totalReturn.amount.multiply(contribution.amount)
            .divide(totalCorpus.amount, returnScale(totalReturn), RoundingMode.HALF_EVEN)
        return Money.of(amount, totalReturn.currency)
    }

    /** Declared percentage input, not an inferred investment-performance rate. */
    fun returnFromPercent(totalCorpus: Money, percent: BigDecimal): Money {
        require(totalCorpus.amount.signum() > 0) { "Percentage editing requires a positive corpus" }
        val amount = totalCorpus.amount.multiply(percent.movePointLeft(2))
            .setScale(returnScale(totalCorpus), RoundingMode.HALF_EVEN)
        return Money.of(amount, totalCorpus.currency)
    }

    private fun returnScale(money: Money): Int {
        val fractionDigits = Currency.getInstance(money.currency).defaultFractionDigits
        require(fractionDigits >= 0) { "Currency has no supported fractional-unit definition" }
        // Preserve meaningful observed precision beyond standard minor units as well.
        return maxOf(fractionDigits, money.amount.stripTrailingZeros().scale().coerceAtLeast(0))
    }
}
