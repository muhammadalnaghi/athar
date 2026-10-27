package com.athar.feature.plan.scenarios

import com.athar.core.common.money.Money
import com.athar.core.domain.calc.cashforecast.CashForecastCalculator
import com.athar.core.domain.model.cashforecast.CashAnchor
import com.athar.core.domain.model.cashforecast.CashEffect
import com.athar.core.domain.model.cashforecast.CashForecastOutcome
import com.athar.core.domain.model.cashforecast.CashForecastRequest
import com.athar.core.domain.model.cashforecast.CompletenessAttestation
import com.athar.core.domain.model.cashforecast.EffectCertainty
import java.math.BigDecimal
import kotlinx.datetime.DateTimeUnit
import kotlinx.datetime.LocalDate
import kotlinx.datetime.plus

/** Sign the user chose for one planned cash movement. */
internal enum class CashFlowDirection { INFLOW, OUTFLOW }

/** One manual, free-text planned row. [amount] is a positive magnitude; [direction] signs it. */
internal data class CashFlowDraft(
    val id: Int,
    val date: String = "",
    val amount: String = "",
    val direction: CashFlowDirection = CashFlowDirection.OUTFLOW,
)

/**
 * Draft form state for the cash scenario. All amounts/dates are raw text until
 * [evaluateCashDraft] validates them; attestations are caller declarations and are
 * passed through verbatim — never defaulted to true.
 */
internal data class CashScenarioDraft(
    val currency: String = "SAR",
    val openingDate: String = "",
    val openingCash: String = "",
    val reserve: String = "",
    val horizonEnd: String = "",
    val scopeComplete: Boolean = false,
    val anchorFresh: Boolean = false,
    val flows: List<CashFlowDraft> = emptyList(),
)

/** Row cap for manual planned flows; beyond this the draft is rejected, not truncated. */
private const val MAX_FLOW_ROWS = 24

/** Calculator horizon ceiling (CashForecastValidation.MAX_DAYS, read from core/domain). */
private const val CALCULATOR_MAX_SPAN_DAYS = 3650L

/** Product rule: a scenario horizon may not exceed five years from the opening date. */
private const val MAX_HORIZON_YEARS = 5

/**
 * Pure draft adapter: validates the free-text scenario and, only when fully valid, builds
 * a [CashForecastRequest] (all rows EXPECTED, knowledge as of the opening date, single
 * opening anchor, no reservations, verbatim attestations) and returns the real calculator
 * outcome. Invalid drafts carry only their error keys and never a calculator outcome.
 */
internal fun evaluateCashDraft(draft: CashScenarioDraft): ScenarioDraftResult<CashForecastOutcome> {
    val errors = linkedSetOf<String>()
    val validated = validate(draft, errors)
    if (validated == null || errors.isNotEmpty()) {
        return ScenarioDraftResult(errors = errors)
    }
    val currency = validated.currency
    val request = CashForecastRequest(
        anchors = listOf(
            CashAnchor(
                asOf = validated.openingDate,
                cash = Money.of(validated.openingCash, currency),
                reserved = validated.reserve?.let { Money.of(it, currency) },
            ),
        ),
        effects = validated.flows.map { flow ->
            CashEffect(
                effectId = flowEffectId(flow.id),
                amount = Money.of(flow.signedAmount, currency),
                valueDate = flow.date,
                certainty = EffectCertainty.EXPECTED,
            )
        },
        reservations = emptyList(),
        knowledgeAsOf = validated.openingDate,
        horizonEnd = validated.horizonEnd,
        attestation = CompletenessAttestation(
            scopeComplete = draft.scopeComplete,
            anchorFresh = draft.anchorFresh,
        ),
    )
    return ScenarioDraftResult(outcome = CashForecastCalculator.forecast(request))
}

/** Stable, typed identity of a planned row inside the forecast evidence. */
internal fun flowEffectId(id: Int): String = "flow-$id"

private fun validate(draft: CashScenarioDraft, errors: MutableSet<String>): ValidatedCashDraft? {
    if (!validScenarioCurrency(draft.currency)) errors += "currency"

    val openingCash = parseScenarioDecimal(draft.openingCash)
    if (openingCash == null) errors += "openingCash"

    val openingDate = parseScenarioDate(draft.openingDate)
    if (openingDate == null) errors += "openingDate"

    var horizonEnd = parseScenarioDate(draft.horizonEnd)
    if (horizonEnd == null) {
        errors += "horizonEnd"
    } else if (openingDate != null && !horizonWithinLimits(openingDate, horizonEnd)) {
        errors += "horizonEnd"
        horizonEnd = null
    }

    val reserve = when {
        draft.reserve.isBlank() -> null
        else -> parseScenarioDecimal(draft.reserve).also { parsed ->
            if (parsed == null || parsed.signum() < 0) errors += "reserve"
        }
    }

    if (draft.flows.size > MAX_FLOW_ROWS) errors += "cashFlows"
    val flowIds = draft.flows.map { it.id }
    if (flowIds.size != flowIds.distinct().size) errors += "cashFlows"

    val validatedFlows = ArrayList<ValidatedFlow>(draft.flows.size)
    for (flow in draft.flows) {
        val amount = parseScenarioDecimal(flow.amount)
        if (amount == null || amount.signum() <= 0) errors += "flow-${flow.id}-amount"

        val date = parseScenarioDate(flow.date)
        val outOfWindow = date == null ||
            (openingDate != null && date <= openingDate) ||
            (horizonEnd != null && date > horizonEnd)
        if (outOfWindow) errors += "flow-${flow.id}-date"

        if (amount != null && amount.signum() > 0 && date != null && !outOfWindow) {
            val signed = when (flow.direction) {
                CashFlowDirection.INFLOW -> amount
                CashFlowDirection.OUTFLOW -> amount.negate()
            }
            validatedFlows += ValidatedFlow(id = flow.id, date = date, signedAmount = signed)
        }
    }

    if (errors.isNotEmpty()) return null
    return ValidatedCashDraft(
        currency = draft.currency,
        openingDate = openingDate ?: return null,
        openingCash = openingCash ?: return null,
        reserve = reserve,
        horizonEnd = horizonEnd ?: return null,
        flows = validatedFlows,
    )
}

private fun horizonWithinLimits(openingDate: LocalDate, horizonEnd: LocalDate): Boolean {
    val spanDays = horizonEnd.toEpochDays().toLong() - openingDate.toEpochDays().toLong()
    if (spanDays < 0 || spanDays > CALCULATOR_MAX_SPAN_DAYS) return false
    return horizonEnd <= openingDate.plus(MAX_HORIZON_YEARS, DateTimeUnit.YEAR)
}

/** Fully validated draft values; constructed only when every error key is absent. */
private class ValidatedCashDraft(
    val currency: String,
    val openingDate: LocalDate,
    val openingCash: BigDecimal,
    val reserve: BigDecimal?,
    val horizonEnd: LocalDate,
    val flows: List<ValidatedFlow>,
)

private class ValidatedFlow(
    val id: Int,
    val date: LocalDate,
    val signedAmount: BigDecimal,
)
