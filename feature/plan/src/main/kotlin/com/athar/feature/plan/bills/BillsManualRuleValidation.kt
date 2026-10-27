package com.athar.feature.plan.bills

import com.athar.core.domain.model.Cadence
import com.athar.feature.plan.BillCadenceLabel
import com.athar.feature.plan.BillsManualRuleError
import com.athar.feature.plan.ManualCommitmentDraft
import kotlinx.datetime.LocalDate
import java.math.BigDecimal

/**
 * Pure validation of the manual commitment draft. Runs synchronously in the ViewModel
 * before any repository call; a refusal never touches storage.
 *
 * Precision contract: `RecurringRuleEntity` persists `amountMinor: Long` and reads back
 * through `Money.ofMinor(minor, currency)` — a scale-2 round trip (`Money.toMinor` uses
 * `BigDecimal.toLong()`, which truncates). An amount whose meaningful scale exceeds
 * [PERSISTED_MINOR_SCALE] therefore cannot survive persistence and is refused with
 * [BillsManualRuleError.AMOUNT_PRECISION_UNSUPPORTED] instead of being truncated.
 */
internal const val PERSISTED_MINOR_SCALE = 2

/** Plain decimal digits only — no exponent, no sign, no separators. */
private val plainDecimal = Regex("\\d+(\\.\\d+)?")

internal sealed interface ManualRuleValidation {
    data class Valid(
        val displayName: String,
        val amount: BigDecimal,
        val currency: String,
        val cadence: Cadence,
        val nextRunDate: LocalDate,
    ) : ManualRuleValidation

    data class Invalid(val error: BillsManualRuleError) : ManualRuleValidation
}

internal fun validateManualRuleDraft(draft: ManualCommitmentDraft): ManualRuleValidation {
    val name = draft.name.trim()
    if (name.isEmpty()) return ManualRuleValidation.Invalid(BillsManualRuleError.NAME_REQUIRED)

    val amountText = draft.amountText.trim()
    if (amountText.length > 128 || !plainDecimal.matches(amountText)) {
        return ManualRuleValidation.Invalid(BillsManualRuleError.AMOUNT_INVALID)
    }
    val amount = BigDecimal(amountText)
    if (amount.signum() <= 0) {
        return ManualRuleValidation.Invalid(BillsManualRuleError.AMOUNT_NOT_POSITIVE)
    }
    if (amount.stripTrailingZeros().scale() > PERSISTED_MINOR_SCALE) {
        return ManualRuleValidation.Invalid(BillsManualRuleError.AMOUNT_PRECISION_UNSUPPORTED)
    }

    if (runCatching { amount.movePointRight(PERSISTED_MINOR_SCALE).longValueExact() }.isFailure) {
        return ManualRuleValidation.Invalid(BillsManualRuleError.AMOUNT_INVALID)
    }

    val currency = draft.currency.trim().uppercase()
    if (currency.length != 3 || currency.any { it !in 'A'..'Z' }) {
        return ManualRuleValidation.Invalid(BillsManualRuleError.CURRENCY_INVALID)
    }

    val nextDate = runCatching { LocalDate.parse(draft.nextDateText.trim()) }.getOrNull()
        ?: return ManualRuleValidation.Invalid(BillsManualRuleError.DATE_INVALID)

    return ManualRuleValidation.Valid(
        displayName = name,
        amount = amount,
        currency = currency,
        cadence = draft.cadence.toCadence(),
        nextRunDate = nextDate,
    )
}

internal fun BillCadenceLabel.toCadence(): Cadence = when (this) {
    BillCadenceLabel.MONTHLY -> Cadence.MONTHLY
    BillCadenceLabel.WEEKLY -> Cadence.WEEKLY
    BillCadenceLabel.QUARTERLY -> Cadence.QUARTERLY
    BillCadenceLabel.YEARLY -> Cadence.YEARLY
}
