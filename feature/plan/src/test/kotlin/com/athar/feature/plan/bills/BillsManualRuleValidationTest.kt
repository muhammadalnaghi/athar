package com.athar.feature.plan.bills

import com.athar.core.domain.model.Cadence
import com.athar.feature.plan.BillCadenceLabel
import com.athar.feature.plan.BillsManualRuleError
import com.athar.feature.plan.ManualCommitmentDraft
import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.Test
import java.math.BigDecimal

/**
 * Pure validation of the manual commitment draft (WM033). The precision contract:
 * `RecurringRuleEntity.amountMinor: Long` round-trips through scale-2 `Money.ofMinor`,
 * so a draft amount whose meaningful scale exceeds [PERSISTED_MINOR_SCALE] is refused
 * with AMOUNT_PRECISION_UNSUPPORTED — never silently truncated.
 */
class BillsManualRuleValidationTest {

    private fun draft(
        name: String = "Home Rent",
        amountText: String = "1450.50",
        currency: String = "SAR",
        cadence: BillCadenceLabel = BillCadenceLabel.MONTHLY,
        nextDateText: String = "2026-07-01",
        notes: String = "",
    ): ManualCommitmentDraft = ManualCommitmentDraft(
        name = name,
        amountText = amountText,
        currency = currency,
        cadence = cadence,
        nextDateText = nextDateText,
        notes = notes,
    )

    @Test
    fun `amount cannot overflow or exceed the bounded persisted decimal input`() {
        val maximum = BigDecimal.valueOf(Long.MAX_VALUE).movePointLeft(PERSISTED_MINOR_SCALE)
        assertThat(validateManualRuleDraft(draft(amountText = maximum.toPlainString())))
            .isInstanceOf(ManualRuleValidation.Valid::class.java)
        for (value in listOf(maximum.add(BigDecimal("0.01")).toPlainString(), "9".repeat(129))) {
            assertThat(validateManualRuleDraft(draft(amountText = value)))
                .isEqualTo(ManualRuleValidation.Invalid(BillsManualRuleError.AMOUNT_INVALID))
        }
    }

    @Test
    fun `blank name is refused`() {
        val result = validateManualRuleDraft(draft(name = "   "))

        assertThat(result).isEqualTo(ManualRuleValidation.Invalid(BillsManualRuleError.NAME_REQUIRED))
    }

    @Test
    fun `unparseable amount text is refused`() {
        listOf("", "abc", "12,50", "12.3.4", "SAR 12").forEach { amountText ->
            val result = validateManualRuleDraft(draft(amountText = amountText))

            assertThat(result).isEqualTo(ManualRuleValidation.Invalid(BillsManualRuleError.AMOUNT_INVALID))
        }
    }

    @Test
    fun `zero amount is refused as not positive`() {
        listOf("0", "0.00", "0.000").forEach { amountText ->
            val result = validateManualRuleDraft(draft(amountText = amountText))

            // Positivity is checked before precision: an unparsable-as-positive zero with
            // extra decimals reports the meaningful refusal, not the precision one.
            assertThat(result).isEqualTo(ManualRuleValidation.Invalid(BillsManualRuleError.AMOUNT_NOT_POSITIVE))
        }
    }

    @Test
    fun `negative amount text is refused as invalid`() {
        val result = validateManualRuleDraft(draft(amountText = "-12.50"))

        // The amount field accepts plain decimals only; sign belongs to the type, and an
        // expense commitment is never negative.
        assertThat(result).isEqualTo(ManualRuleValidation.Invalid(BillsManualRuleError.AMOUNT_INVALID))
    }

    @Test
    fun `amount beyond the persisted minor scale is refused not truncated`() {
        listOf("12.345", "0.001", "1450.50001").forEach { amountText ->
            val result = validateManualRuleDraft(draft(amountText = amountText))

            assertThat(result).isEqualTo(
                ManualRuleValidation.Invalid(BillsManualRuleError.AMOUNT_PRECISION_UNSUPPORTED),
            )
        }
    }

    @Test
    fun `amounts within the persisted scale are valid regardless of trailing zeros`() {
        listOf("1450.50", "12.3", "12", "0.01").forEach { amountText ->
            val result = validateManualRuleDraft(draft(amountText = amountText))

            assertThat(result).isInstanceOf(ManualRuleValidation.Valid::class.java)
        }
    }

    @Test
    fun `currency must be exactly three letters`() {
        listOf("US", "US12", "SARR", "S R").forEach { currency ->
            val result = validateManualRuleDraft(draft(currency = currency))

            assertThat(result).isEqualTo(ManualRuleValidation.Invalid(BillsManualRuleError.CURRENCY_INVALID))
        }
    }

    @Test
    fun `lowercase three letter currency is normalized to upper case`() {
        val result = validateManualRuleDraft(draft(currency = "usd"))

        val valid = result as ManualRuleValidation.Valid
        assertThat(valid.currency).isEqualTo("USD")
    }

    @Test
    fun `date must parse as an ISO local date`() {
        listOf("01/12/2026", "2026-13-40", "20260701", "", "2026-7-1").forEach { dateText ->
            val result = validateManualRuleDraft(draft(nextDateText = dateText))

            assertThat(result).isEqualTo(ManualRuleValidation.Invalid(BillsManualRuleError.DATE_INVALID))
        }
    }

    @Test
    fun `valid draft is normalized without inventing anything`() {
        val result = validateManualRuleDraft(
            draft(
                name = "  Home Rent  ",
                amountText = "1450.5",
                currency = " sar ",
                cadence = BillCadenceLabel.WEEKLY,
                nextDateText = " 2026-07-01 ",
                notes = "  annual renewal  ",
            ),
        )

        val valid = result as ManualRuleValidation.Valid
        assertThat(valid.displayName).isEqualTo("Home Rent")
        assertThat(valid.amount).isEqualTo(BigDecimal("1450.5"))
        assertThat(valid.currency).isEqualTo("SAR")
        assertThat(valid.cadence).isEqualTo(Cadence.WEEKLY)
        assertThat(valid.nextRunDate).isEqualTo(kotlinx.datetime.LocalDate(2026, 7, 1))
    }

    @Test
    fun `every cadence label maps to its domain cadence`() {
        assertThat(BillCadenceLabel.MONTHLY.toCadence()).isEqualTo(Cadence.MONTHLY)
        assertThat(BillCadenceLabel.WEEKLY.toCadence()).isEqualTo(Cadence.WEEKLY)
        assertThat(BillCadenceLabel.QUARTERLY.toCadence()).isEqualTo(Cadence.QUARTERLY)
        assertThat(BillCadenceLabel.YEARLY.toCadence()).isEqualTo(Cadence.YEARLY)
    }
}
