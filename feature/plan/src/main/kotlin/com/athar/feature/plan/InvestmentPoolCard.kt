package com.athar.feature.plan

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import com.athar.core.designsystem.component.AtharCard
import com.athar.core.designsystem.component.AtharNumber
import com.athar.core.designsystem.component.AtharText
import com.athar.core.designsystem.display.currentUiLocale
import com.athar.core.designsystem.theme.AtharTheme
import com.athar.feature.plan.investments.PoolBreakdown
import java.math.BigDecimal
import java.text.NumberFormat

@Composable
internal fun InvestmentPoolCard(
    row: PoolRow,
    breakdown: PoolBreakdown,
    onAddContributor: () -> Unit,
    onDeletePool: () -> Unit,
    onDeleteContributor: (String) -> Unit,
    onUpdatePercent: (BigDecimal) -> Unit,
) {
    val theme = AtharTheme
    var editingReturn by remember(row.pool.id) { mutableStateOf(false) }
    var confirmingDelete by remember(row.pool.id) { mutableStateOf(false) }
    AtharCard {
        Column(verticalArrangement = Arrangement.spacedBy(theme.spacing.s)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                AtharText(row.pool.name, style = theme.typography.headline, modifier = Modifier.weight(1f))
                AtharText(
                    text = stringResource(R.string.plan_investments_pool_delete),
                    style = theme.typography.caption,
                    color = theme.colors.muted,
                    modifier = Modifier.heightIn(min = 48.dp).clickable(role = Role.Button) { confirmingDelete = true }
                        .padding(theme.spacing.s),
                )
            }
            AtharText(
                stringResource(R.string.plan_investments_pool_period, row.pool.period),
                style = theme.typography.caption, color = theme.colors.muted,
            )
            if (!breakdown.mixedCurrency) {
                AtharText(stringResource(R.string.plan_investments_pool_total_corpus), style = theme.typography.caption)
                AtharNumber(money = row.totalCorpus)
            }
            AtharText(stringResource(R.string.plan_investments_declared_return), style = theme.typography.caption)
            AtharNumber(money = row.pool.totalReturn, color = if (row.pool.totalReturn.isNegative()) theme.colors.ink else theme.colors.olive)
            if (row.declaredReturnPercent != null) {
                AtharText(
                    text = stringResource(R.string.plan_investments_edit_return_percent),
                    style = theme.typography.caption, color = theme.colors.ember,
                    modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)
                        .clickable(role = Role.Button) { editingReturn = true }.padding(vertical = theme.spacing.s),
                )
            }
            InvestmentRecordsPanel(breakdown)
            row.contributors.forEach { contributor ->
                InvestmentContributorRow(contributor) { onDeleteContributor(contributor.id) }
            }
            breakdown.roundingRemainder?.takeUnless { it.isZero() }?.let { remainder ->
                AtharText(stringResource(R.string.plan_investments_rounding_remainder), style = theme.typography.caption)
                AtharNumber(money = remainder)
            }
            InvestmentPrimaryButton(stringResource(R.string.plan_investments_add_contributor), onAddContributor)
        }
    }
    if (editingReturn) {
        InvestmentReturnPercentEditor(
            currentPercent = requireNotNull(row.declaredReturnPercent), onDismiss = { editingReturn = false },
            onSave = { onUpdatePercent(it); editingReturn = false },
        )
    }
    if (confirmingDelete) {
        InvestmentDeleteConfirm(
            title = stringResource(R.string.plan_investments_delete_pool_title, row.pool.name),
            body = stringResource(R.string.plan_investments_delete_pool_body),
            onConfirm = { confirmingDelete = false; onDeletePool() }, onDismiss = { confirmingDelete = false },
        )
    }
}

@Composable
private fun InvestmentRecordsPanel(evidence: PoolBreakdown) {
    val theme = AtharTheme
    var showLimits by remember(evidence.poolId) { mutableStateOf(false) }
    AtharText(
        stringResource(R.string.plan_investments_records_evidence, evidence.contributionCount, evidence.currencyGroups.joinToString(" · ") { it.currency }),
        style = theme.typography.caption, color = theme.colors.muted,
    )
    if (evidence.mixedCurrency) {
        evidence.currencyGroups.forEach { group ->
            Column(verticalArrangement = Arrangement.spacedBy(theme.spacing.xs)) {
                AtharText(stringResource(R.string.plan_investments_corpus_in_currency, group.currency), style = theme.typography.caption)
                AtharNumber(money = group.corpus)
            }
        }
    }
    if (!evidence.allocationAvailable && evidence.contributionCount > 0) {
        AtharText(stringResource(R.string.plan_investments_allocation_unavailable), style = theme.typography.caption, color = theme.colors.muted)
    }
    AtharText(
        text = stringResource(R.string.plan_investments_record_limits), style = theme.typography.caption,
        color = theme.colors.muted, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)
            .clickable(role = Role.Button) { showLimits = !showLimits }.padding(vertical = theme.spacing.s),
    )
    if (showLimits) {
        AtharText(stringResource(R.string.plan_investments_unavailable_body), style = theme.typography.caption, color = theme.colors.muted)
    }
}

@Composable
private fun InvestmentContributorRow(record: ContributorRow, onDelete: () -> Unit) {
    val theme = AtharTheme
    val locale = currentUiLocale()
    var confirmingDelete by remember(record.id) { mutableStateOf(false) }
    Column(modifier = Modifier.fillMaxWidth().padding(vertical = theme.spacing.s), verticalArrangement = Arrangement.spacedBy(theme.spacing.xs)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            AtharText(record.name, style = theme.typography.body, modifier = Modifier.weight(1f))
            AtharText(
                stringResource(R.string.plan_investments_contributor_remove), style = theme.typography.caption,
                color = theme.colors.muted, modifier = Modifier.heightIn(min = 48.dp)
                    .clickable(role = Role.Button) { confirmingDelete = true }.padding(theme.spacing.s),
            )
        }
        AtharText(stringResource(R.string.plan_investments_contribution_amount), style = theme.typography.caption, color = theme.colors.muted)
        AtharNumber(money = record.amount)
        if (record.hasComposition) {
            val percent = NumberFormat.getNumberInstance(locale).apply { maximumFractionDigits = 1 }.format(record.sharePercent)
            AtharText(stringResource(R.string.plan_investments_currency_composition, record.amount.currency, percent), style = theme.typography.caption, color = theme.colors.muted)
        }
        record.netTotal?.let { total ->
            AtharText(stringResource(R.string.plan_investments_contribution_plus_return), style = theme.typography.caption, color = theme.colors.muted)
            AtharNumber(money = total)
        }
        record.shareReturn?.let { value ->
            AtharText(stringResource(R.string.plan_investments_contributor_return_label), style = theme.typography.caption, color = theme.colors.muted)
            AtharNumber(money = value, color = if (value.isNegative()) theme.colors.ink else theme.colors.olive)
        }
    }
    if (confirmingDelete) {
        InvestmentDeleteConfirm(
            stringResource(R.string.plan_investments_remove_contribution_title),
            stringResource(R.string.plan_investments_remove_contribution_body),
            onConfirm = { confirmingDelete = false; onDelete() }, onDismiss = { confirmingDelete = false },
        )
    }
}
