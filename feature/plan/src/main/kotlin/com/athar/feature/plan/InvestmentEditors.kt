package com.athar.feature.plan

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import com.athar.core.common.money.Money
import com.athar.core.designsystem.component.AtharAmountField
import com.athar.core.designsystem.component.AtharText
import com.athar.core.designsystem.component.AtharTextField
import com.athar.core.designsystem.theme.AtharTheme
import com.athar.core.domain.model.InvestmentPool
import com.athar.feature.plan.investments.InvestmentEntryRules
import java.math.BigDecimal
import java.util.UUID

@Composable
internal fun InvestmentReturnPercentEditor(currentPercent: BigDecimal, onDismiss: () -> Unit, onSave: (BigDecimal) -> Unit) {
    var text by remember { mutableStateOf(currentPercent.stripTrailingZeros().toPlainString()) }
    var invalid by remember { mutableStateOf(false) }
    InvestmentEditorSheet(stringResource(R.string.plan_investments_return_editor_title), onDismiss) {
        AtharText(stringResource(R.string.plan_investments_declared_percent_hint), style = AtharTheme.typography.caption, color = AtharTheme.colors.muted)
        AtharAmountField(
            value = text, onValueChange = { text = it; invalid = false },
            label = stringResource(R.string.plan_investments_return_editor_label), modifier = Modifier.fillMaxWidth(),
            isError = invalid, supportingText = if (invalid) stringResource(R.string.plan_investments_invalid_input) else null,
        )
        InvestmentPrimaryButton(stringResource(R.string.plan_investments_return_editor_save)) {
            val value = InvestmentEntryRules.parseDecimal(text)
            if (value == null) invalid = true else onSave(value)
        }
    }
}

@Composable
internal fun InvestmentPoolEditor(onDismiss: () -> Unit, onSave: (InvestmentPool) -> Unit) {
    var name by remember { mutableStateOf("") }
    var period by remember { mutableStateOf("") }
    var totalReturn by remember { mutableStateOf("0") }
    var invalid by remember { mutableStateOf(false) }
    InvestmentEditorSheet(stringResource(R.string.plan_investments_pool_editor_title), onDismiss) {
        AtharTextField(name, { name = it; invalid = false }, stringResource(R.string.plan_investments_pool_label_name), Modifier.fillMaxWidth())
        AtharTextField(period, { period = it; invalid = false }, stringResource(R.string.plan_investments_pool_label_period), Modifier.fillMaxWidth())
        AtharText(stringResource(R.string.plan_investments_entry_currency, Money.SAR), style = AtharTheme.typography.caption)
        AtharAmountField(
            totalReturn, { totalReturn = it; invalid = false }, label = stringResource(R.string.plan_investments_pool_label_total_return),
            modifier = Modifier.fillMaxWidth(), isError = invalid,
        )
        if (invalid) InvestmentWriteMessage(InvestmentWriteProblem.INVALID_INPUT)
        InvestmentPrimaryButton(stringResource(R.string.plan_investments_pool_action_save)) {
            val value = InvestmentEntryRules.parseDecimal(totalReturn)?.let { Money.of(it, Money.SAR) }
            if (name.isBlank() || period.isBlank() || value == null) invalid = true
            else if (!InvestmentEntryRules.isExactlyStorable(value)) invalid = true
            else onSave(InvestmentPool(id = UUID.randomUUID().toString(), name = name.trim(), period = period.trim(), totalReturn = value))
        }
    }
}

@Composable
internal fun InvestmentContributorEditor(pool: PoolRow, onDismiss: () -> Unit, onSave: (String, Money) -> Unit) {
    var owner by remember { mutableStateOf("") }
    var amount by remember { mutableStateOf("") }
    var invalid by remember { mutableStateOf(false) }
    val currency = pool.pool.totalReturn.currency
    InvestmentEditorSheet(stringResource(R.string.plan_investments_contributor_editor_title, pool.pool.name), onDismiss) {
        AtharTextField(owner, { owner = it; invalid = false }, stringResource(R.string.plan_investments_contributor_label_name), Modifier.fillMaxWidth())
        AtharText(stringResource(R.string.plan_investments_entry_currency, currency), style = AtharTheme.typography.caption)
        AtharAmountField(
            amount, { amount = it; invalid = false }, label = stringResource(R.string.plan_investments_contributor_label_amount),
            modifier = Modifier.fillMaxWidth(), isError = invalid,
        )
        if (invalid) InvestmentWriteMessage(InvestmentWriteProblem.INVALID_INPUT)
        InvestmentPrimaryButton(stringResource(R.string.plan_investments_contributor_action_save)) {
            val value = InvestmentEntryRules.parseDecimal(amount)?.let { Money.of(it, currency) }
            if (owner.isBlank() || value == null || !value.isPositive()) invalid = true
            else if (!InvestmentEntryRules.isExactlyStorable(value)) invalid = true
            else onSave(owner.trim(), value)
        }
    }
}
