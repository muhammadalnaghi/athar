package com.athar.feature.plan

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.res.stringResource
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.athar.core.designsystem.component.AtharCard
import com.athar.core.designsystem.component.AtharText
import com.athar.core.designsystem.theme.AtharTheme
import com.athar.feature.plan.investments.PoolBreakdown
import com.athar.feature.plan.portfolio.PortfolioCreateSheet
import com.athar.feature.plan.portfolio.PortfolioImportPreviewSheet
import com.athar.feature.plan.portfolio.PortfolioRejectMessage
import com.athar.feature.plan.portfolio.PortfolioTextAction
import com.athar.feature.plan.portfolio.PortfolioWorkbenchResult
import com.athar.feature.plan.position.PositionSection
import androidx.compose.ui.Modifier

/** Initial query absence is not evidence that the user's stored portfolio list is empty. */
internal fun portfolioListMessage(isLoading: Boolean, hasPortfolios: Boolean): Int? = when {
    hasPortfolios -> null
    isLoading -> R.string.plan_portfolio_loading
    else -> R.string.plan_portfolio_empty_hint
}

@Composable
fun InvestmentsContent(viewModel: InvestmentsViewModel = hiltViewModel(), onPositionChanged: () -> Unit = {}) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val theme = AtharTheme
    var creatingPool by remember { mutableStateOf(false) }
    var addContributorTo by remember { mutableStateOf<PoolRow?>(null) }
    var creatingPortfolio by remember { mutableStateOf(false) }
    var deletingPortfolio by remember { mutableStateOf<PortfolioRow?>(null) }
    val openedPortfolioId = state.portfolioOpen?.planId
    LaunchedEffect(openedPortfolioId) {
        if (openedPortfolioId != null) onPositionChanged()
    }
    Column(verticalArrangement = Arrangement.spacedBy(theme.spacing.l)) {
        if (state.portfolioOpen == null) {
            AtharCard {
                Column(verticalArrangement = Arrangement.spacedBy(theme.spacing.s)) {
                    AtharText(stringResource(R.string.plan_investments_title), style = theme.typography.headline)
                    AtharText(stringResource(R.string.plan_investments_subtitle), style = theme.typography.body, color = theme.colors.muted)
                    InvestmentPrimaryButton(stringResource(R.string.plan_investments_add_pool)) { creatingPool = true }
                }
            }
            PositionSection(onPositionChanged = onPositionChanged)
            state.writeProblem?.let { InvestmentWriteMessage(it) }
            if (state.pools.isEmpty() && !state.isLoading) {
                AtharText(stringResource(R.string.plan_investments_empty), color = theme.colors.muted)
            }
            state.pools.forEach { row ->
                InvestmentPoolCard(
                    row = row,
                    breakdown = state.breakdowns.firstOrNull { it.poolId == row.pool.id } ?: PoolBreakdown.empty(row.pool),
                    onAddContributor = { addContributorTo = row },
                    onDeletePool = { viewModel.onEvent(InvestmentsEvent.DeletePool(row.pool.id)) },
                    onDeleteContributor = { viewModel.onEvent(InvestmentsEvent.DeleteContribution(it)) },
                    onUpdatePercent = { viewModel.onEvent(InvestmentsEvent.UpdatePoolReturnPercent(row.pool.id, it)) },
                )
            }
        }
        PortfolioSection(
            state = state,
            onCreateRequest = { creatingPortfolio = true },
            onDeleteRequest = { deletingPortfolio = it },
            onEvent = viewModel::onEvent,
        )
    }
    if (creatingPool) {
        InvestmentPoolEditor(onDismiss = { creatingPool = false }, onSave = {
            viewModel.onEvent(InvestmentsEvent.SavePool(it)); creatingPool = false
        })
    }
    addContributorTo?.let { pool ->
        InvestmentContributorEditor(pool, onDismiss = { addContributorTo = null }, onSave = { owner, amount ->
            viewModel.onEvent(InvestmentsEvent.SaveContribution(pool.pool.id, owner, amount)); addContributorTo = null
        })
    }
    if (creatingPortfolio) {
        PortfolioCreateSheet(
            onDismiss = { creatingPortfolio = false },
            onCreate = { title, currency, opening, openingDate ->
                creatingPortfolio = false
                viewModel.onEvent(InvestmentsEvent.CreatePortfolio(title, currency, opening, openingDate))
            },
        )
    }
    deletingPortfolio?.let { row ->
        InvestmentDeleteConfirm(
            title = stringResource(R.string.plan_portfolio_delete_title),
            body = stringResource(R.string.plan_portfolio_delete_body),
            onConfirm = {
                deletingPortfolio = null
                viewModel.onEvent(InvestmentsEvent.DeletePortfolio(row.planId))
            },
            onDismiss = { deletingPortfolio = null },
        )
    }
    state.importPreview?.let { previewUi ->
        PortfolioImportPreviewSheet(
            previewUi = previewUi,
            onApply = { viewModel.onEvent(InvestmentsEvent.ConfirmPortfolioImport) },
            onCancel = { viewModel.onEvent(InvestmentsEvent.CancelPortfolioImport) },
        )
    }
}

/** Dated-securities portfolios (saved PORTFOLIO documents) under the untouched family pools. */
@Composable
private fun PortfolioSection(
    state: InvestmentsState,
    onCreateRequest: () -> Unit,
    onDeleteRequest: (PortfolioRow) -> Unit,
    onEvent: (InvestmentsEvent) -> Unit,
) {
    val theme = AtharTheme
    val open = state.portfolioOpen
    if (open != null) {
        PortfolioWorkbenchResult(open = open, problem = state.portfolioProblem, onEvent = onEvent, allocationWrite = state.allocationWrite, writeProblem = state.writeProblem)
        return
    }
    AtharCard {
        Column(verticalArrangement = Arrangement.spacedBy(theme.spacing.s)) {
            AtharText(stringResource(R.string.plan_portfolio_section_title), style = theme.typography.headline)
            InvestmentPrimaryButton(stringResource(R.string.plan_portfolio_section_create)) { onCreateRequest() }
            state.portfolioProblem?.let { PortfolioRejectMessage(it) }
            portfolioListMessage(state.isLoading, state.portfolios.isNotEmpty())?.let { message ->
                AtharText(stringResource(message), style = theme.typography.caption, color = theme.colors.muted)
            }
            state.portfolios.forEach { row ->
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        AtharText(row.title, style = theme.typography.body)
                        AtharText(
                            text = when (val docState = row.state) {
                                is PortfolioDocState.Ready -> stringResource(R.string.plan_portfolio_state_ready)
                                is PortfolioDocState.Rejected -> stringResource(R.string.plan_portfolio_state_rejected)
                                PortfolioDocState.Unavailable -> stringResource(R.string.plan_portfolio_state_unavailable)
                            },
                            style = theme.typography.caption,
                            color = when (row.state) {
                                is PortfolioDocState.Ready -> theme.colors.olive
                                is PortfolioDocState.Rejected -> theme.colors.ember
                                PortfolioDocState.Unavailable -> theme.colors.dust
                            },
                        )
                        AtharText(
                            stringResource(R.string.plan_portfolio_row_hint, row.currency, row.revision),
                            style = theme.typography.caption,
                            color = theme.colors.muted,
                        )
                    }
                    if (row.state is PortfolioDocState.Ready) {
                        PortfolioTextAction(stringResource(R.string.plan_portfolio_open)) {
                            onEvent(InvestmentsEvent.OpenPortfolio(row.planId))
                        }
                    }
                    PortfolioTextAction(stringResource(R.string.plan_portfolio_delete)) { onDeleteRequest(row) }
                }
            }
        }
    }
}
