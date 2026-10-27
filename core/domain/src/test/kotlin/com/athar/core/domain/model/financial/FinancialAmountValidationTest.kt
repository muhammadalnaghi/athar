package com.athar.core.domain.model.financial

import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.Test
import java.math.BigDecimal

/**
 * Behavior-first tests for new-input money validation (WM028-07): exact
 * currency precision from proven JDK metadata, explicit typed rejection
 * outcomes, and never quantizing a new input into validity.
 */
class FinancialAmountValidationTest {

    private fun validate(
        id: String? = "obs-1",
        amount: BigDecimal? = BigDecimal("84.50"),
        currency: String? = "SAR",
    ): ValidatedFinancialInput = FinancialAmountValidator.validateNewAmount(id, amount, currency)

    @Test
    fun `accepts exact two decimal SAR and USD amounts`() {
        assertThat(validate(currency = "SAR")).isInstanceOf(ValidatedFinancialInput.Valid::class.java)
        assertThat(validate(amount = BigDecimal("12.75"), currency = "USD"))
            .isInstanceOf(ValidatedFinancialInput.Valid::class.java)
    }

    @Test
    fun `accepts scale insensitive trailing zeros`() {
        assertThat(validate(amount = BigDecimal("84.5"))).isInstanceOf(ValidatedFinancialInput.Valid::class.java)
        assertThat(validate(amount = BigDecimal("84.500"))).isInstanceOf(ValidatedFinancialInput.Valid::class.java)
        assertThat(validate(amount = BigDecimal("1000.00"), currency = "JPY"))
            .isInstanceOf(ValidatedFinancialInput.Valid::class.java)
        assertThat(validate(amount = BigDecimal("10.1230"), currency = "KWD"))
            .isInstanceOf(ValidatedFinancialInput.Valid::class.java)
    }

    @Test
    fun `enforces zero fractional digits for JPY`() {
        val valid = validate(amount = BigDecimal("1000"), currency = "JPY")
        assertThat(valid).isInstanceOf(ValidatedFinancialInput.Valid::class.java)

        val invalid = validate(amount = BigDecimal("1000.5"), currency = "JPY")
        assertThat((invalid as ValidatedFinancialInput.Invalid).issues)
            .containsExactly(FinancialInputIssue.UNSUPPORTED_PRECISION)
    }

    @Test
    fun `enforces three fractional digits for KWD`() {
        assertThat(validate(amount = BigDecimal("10.123"), currency = "KWD"))
            .isInstanceOf(ValidatedFinancialInput.Valid::class.java)

        val invalid = validate(amount = BigDecimal("10.1234"), currency = "KWD")
        assertThat((invalid as ValidatedFinancialInput.Invalid).issues)
            .containsExactly(FinancialInputIssue.UNSUPPORTED_PRECISION)
    }

    @Test
    fun `never quantizes an over precision new amount into validity`() {
        val invalid = validate(amount = BigDecimal("10.005"), currency = "USD")
        assertThat((invalid as ValidatedFinancialInput.Invalid).issues)
            .containsExactly(FinancialInputIssue.UNSUPPORTED_PRECISION)
    }

    @Test
    fun `rejects negative zero and missing amounts with explicit outcomes`() {
        assertThat((validate(amount = BigDecimal("-0.01")) as ValidatedFinancialInput.Invalid).issues)
            .containsExactly(FinancialInputIssue.NEGATIVE_AMOUNT)
        assertThat((validate(amount = BigDecimal.ZERO) as ValidatedFinancialInput.Invalid).issues)
            .containsExactly(FinancialInputIssue.ZERO_AMOUNT)
        assertThat((validate(amount = null) as ValidatedFinancialInput.Invalid).issues)
            .containsExactly(FinancialInputIssue.NO_KNOWN_AMOUNT)
    }

    @Test
    fun `rejects unknown currencies without repairing them`() {
        assertThat((validate(currency = "ABC") as ValidatedFinancialInput.Invalid).issues)
            .containsExactly(FinancialInputIssue.UNKNOWN_CURRENCY)
        assertThat((validate(currency = "usd") as ValidatedFinancialInput.Invalid).issues)
            .containsExactly(FinancialInputIssue.UNKNOWN_CURRENCY)
    }

    @Test
    fun `rejects currencies without JDK precision metadata`() {
        // XXX is an ISO pseudo-currency: Currency.getDefaultFractionDigits() == -1.
        val invalid = validate(currency = "XXX")
        assertThat((invalid as ValidatedFinancialInput.Invalid).issues)
            .containsExactly(FinancialInputIssue.UNSUPPORTED_PRECISION)
    }

    @Test
    fun `blank stable identity produces an explicit outcome`() {
        assertThat((validate(id = "") as ValidatedFinancialInput.Invalid).issues)
            .containsExactly(FinancialInputIssue.BLANK_OBSERVATION_ID)
        assertThat((validate(id = "   ") as ValidatedFinancialInput.Invalid).issues)
            .containsExactly(FinancialInputIssue.BLANK_OBSERVATION_ID)
        assertThat((validate(id = null) as ValidatedFinancialInput.Invalid).issues)
            .containsExactly(FinancialInputIssue.BLANK_OBSERVATION_ID)
        assertThat(StableObservationId.of("obs-1")).isNotNull()
        assertThat(StableObservationId.of("")).isNull()
    }

    @Test
    fun `valid input keeps the exact amount and identity`() {
        val valid = validate(id = "obs-9", amount = BigDecimal("84.50"), currency = "SAR")
            as ValidatedFinancialInput.Valid
        assertThat(valid.observationId.value).isEqualTo("obs-9")
        assertThat(valid.amount.currency).isEqualTo("SAR")
        assertThat(valid.amount.amount).isEquivalentAccordingToCompareTo(BigDecimal("84.50"))
    }
}
