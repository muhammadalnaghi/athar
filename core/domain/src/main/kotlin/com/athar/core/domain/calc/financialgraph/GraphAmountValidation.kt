package com.athar.core.domain.calc.financialgraph

import com.athar.core.common.money.Money
import com.athar.core.domain.model.financial.FinancialAmountValidator
import com.athar.core.domain.model.financial.FinancialInputIssue
import com.athar.core.domain.model.financial.ValidatedFinancialInput
import com.athar.core.domain.model.financialgraph.GraphBuildIssue

internal enum class GraphMoneyRole(
    val currencyIssue: GraphBuildIssue,
    val precisionIssue: GraphBuildIssue,
    val signIssue: GraphBuildIssue,
) {
    COMPONENT(GraphBuildIssue.INVALID_COMPONENT_CURRENCY, GraphBuildIssue.COMPONENT_UNSUPPORTED_PRECISION,
        GraphBuildIssue.NONPOSITIVE_COMPONENT_AMOUNT),
    LEG(GraphBuildIssue.INVALID_LEG_CURRENCY, GraphBuildIssue.LEG_UNSUPPORTED_PRECISION,
        GraphBuildIssue.ZERO_LEG_DELTA),
}

/** Validate signed leg magnitude via the same exact ISO validator as recording. */
internal fun validateGraphMoney(
    money: Money,
    observationId: String,
    role: GraphMoneyRole,
    issues: MutableSet<GraphBuildIssue>,
) {
    val value = if (role == GraphMoneyRole.LEG) money.amount.abs() else money.amount
    val result = FinancialAmountValidator.validateNewAmount(observationId, value, money.currency)
    if (result is ValidatedFinancialInput.Invalid) {
        for (issue in result.issues) {
            issues += when (issue) {
                FinancialInputIssue.UNKNOWN_CURRENCY -> role.currencyIssue
                FinancialInputIssue.UNSUPPORTED_PRECISION -> role.precisionIssue
                FinancialInputIssue.NEGATIVE_AMOUNT, FinancialInputIssue.ZERO_AMOUNT -> role.signIssue
                FinancialInputIssue.BLANK_OBSERVATION_ID, FinancialInputIssue.NO_KNOWN_AMOUNT ->
                    error("Typed graph input must retain its observation identity and money")
            }
        }
    }
}
