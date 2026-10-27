package com.athar.core.domain.model.financial

import com.athar.core.common.money.Money
import java.math.BigDecimal
import java.util.Currency

/**
 * Explicit typed outcomes for invalid new financial inputs (WM028-07).
 * Nothing is silently quantized, repaired or reinterpreted — including legacy
 * universal-scale minor units, which this validator never touches.
 */
enum class FinancialInputIssue {
    BLANK_OBSERVATION_ID,
    NO_KNOWN_AMOUNT,
    NEGATIVE_AMOUNT,
    ZERO_AMOUNT,
    UNKNOWN_CURRENCY,
    UNSUPPORTED_PRECISION,
}

sealed interface ValidatedFinancialInput {
    data class Valid(val observationId: StableObservationId, val amount: Money) : ValidatedFinancialInput
    data class Invalid(val issues: Set<FinancialInputIssue>) : ValidatedFinancialInput
}

/**
 * Validates NEW observation inputs: amount + currency against proven JDK
 * currency metadata ([Currency.getDefaultFractionDigits]); e.g. JPY 0, SAR/USD 2,
 * KWD 3 fractional digits. Reuses [Money]; no parallel money engine.
 *
 * Trailing zeros are scale-insensitive: "1000.00" JPY and "10.1230" KWD are
 * valid because the exact value is representable at the currency precision
 * (checked via [BigDecimal.stripTrailingZeros], never applied to the stored
 * value). A genuinely over-precision amount such as 10.005 USD is rejected.
 * Currency codes are used exactly as given: "usd" is unknown, not repaired.
 */
object FinancialAmountValidator {

    fun validateNewAmount(
        observationId: String?,
        amount: BigDecimal?,
        currencyCode: String?,
    ): ValidatedFinancialInput {
        val issues = linkedSetOf<FinancialInputIssue>()
        val id = StableObservationId.of(observationId ?: "")
        if (id == null) issues += FinancialInputIssue.BLANK_OBSERVATION_ID

        val fractionDigits: Int? = currencyCode?.let { code ->
            try {
                Currency.getInstance(code).defaultFractionDigits
            } catch (_: IllegalArgumentException) {
                null
            }
        }
        when {
            currencyCode == null -> issues += FinancialInputIssue.UNKNOWN_CURRENCY
            fractionDigits == null -> issues += FinancialInputIssue.UNKNOWN_CURRENCY
            fractionDigits < 0 -> issues += FinancialInputIssue.UNSUPPORTED_PRECISION
        }

        when {
            amount == null -> issues += FinancialInputIssue.NO_KNOWN_AMOUNT
            amount.signum() < 0 -> issues += FinancialInputIssue.NEGATIVE_AMOUNT
            amount.signum() == 0 -> issues += FinancialInputIssue.ZERO_AMOUNT
            fractionDigits != null && fractionDigits >= 0 &&
                amount.stripTrailingZeros().scale() > fractionDigits ->
                issues += FinancialInputIssue.UNSUPPORTED_PRECISION
        }

        return if (issues.isEmpty() && id != null && amount != null && currencyCode != null) {
            ValidatedFinancialInput.Valid(id, Money.of(amount, currencyCode))
        } else {
            ValidatedFinancialInput.Invalid(issues)
        }
    }
}
