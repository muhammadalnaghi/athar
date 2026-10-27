package com.athar.feature.plan.portfolio

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import com.athar.core.designsystem.component.AtharText
import com.athar.core.designsystem.theme.AtharTheme
import com.athar.core.domain.calc.portfolio.ReturnUndefinedReason
import com.athar.feature.plan.R

/**
 * Optional evidence section under the opened portfolio's performance results:
 * collapsed by default behind one 48dp bordered chip. When opened it explains, in
 * Arabic-first plain copy, that results are recomputed from the saved dated inputs
 * at every open with no external prices or estimates; what the accounting money
 * gain is (and is not); what XIRR and TWR require; and that an undefined result
 * means missing dated inputs, never substituted values. Evidence lines show input
 * counts and the dated span only — no new money math, ever. Undefined/unavailable
 * states surface exactly the security ids the engines reported.
 */
@Composable
internal fun PortfolioPerformanceEvidence040(evidence: PerformanceEvidence040) {
    val theme = AtharTheme
    var expanded by remember { mutableStateOf(false) }
    Column(modifier = Modifier, verticalArrangement = Arrangement.spacedBy(theme.spacing.s)) {
        PortfolioTextAction(
            stringResource(
                if (expanded) R.string.plan_portfolio_evidence_hide
                else R.string.plan_portfolio_evidence_toggle,
            ),
        ) { expanded = !expanded }
        if (!expanded) return@Column
        EvidenceLine(stringResource(R.string.plan_portfolio_evidence_recomputed))
        EvidenceLine(stringResource(R.string.plan_portfolio_evidence_money_gain))
        EvidenceLine(stringResource(R.string.plan_portfolio_evidence_xirr_requires))
        EvidenceLine(stringResource(R.string.plan_portfolio_evidence_twr_requires))
        EvidenceLine(stringResource(R.string.plan_portfolio_evidence_undefined_note))
        AtharText(
            stringResource(R.string.plan_portfolio_evidence_inputs_title),
            style = theme.typography.caption,
        )
        if (!evidence.hasDatedInputs) {
            // Honest empty state: nothing dated is stored, so nothing is listed.
            EvidenceLine(stringResource(R.string.plan_portfolio_evidence_empty))
        } else {
            val eventCount = evidence.eventCountByKind.values.sum()
            EvidenceLine(
                stringResource(R.string.plan_portfolio_evidence_counts, eventCount, evidence.quoteCount),
            )
            val earliest = evidence.earliestDate
            val latest = evidence.latestDate
            if (earliest != null && latest != null) {
                EvidenceLine(
                    stringResource(
                        R.string.plan_portfolio_evidence_span,
                        earliest.toString(),
                        latest.toString(),
                    ),
                )
            }
            kindBreakdownLine(evidence)?.let { EvidenceLine(it) }
        }
        val unpriced = linkedSetOf<String>()
        listOf(evidence.xirr, evidence.twr).forEach { flow ->
            if (flow is ReturnFlow040.Undefined &&
                flow.reason == ReturnUndefinedReason.MISSING_BOUNDARY_VALUATION
            ) {
                unpriced += flow.unpricedSecurityIds
            }
        }
        if (unpriced.isNotEmpty()) {
            EvidenceLine(
                stringResource(R.string.plan_portfolio_evidence_unpriced, unpriced.joinToString("، ")),
                caution = true,
            )
        }
        when (val gain = evidence.moneyGain) {
            is MoneyGainFlow040.Unavailable -> EvidenceLine(
                stringResource(
                    R.string.plan_portfolio_evidence_unpriced,
                    gain.unavailableBecause.joinToString("، "),
                ),
                caution = true,
            )
            is MoneyGainFlow040.NotReplayable -> if (evidence.hasDatedInputs) {
                EvidenceLine(
                    stringResource(R.string.plan_portfolio_evidence_gain_not_replayable),
                    caution = true,
                )
            }
            MoneyGainFlow040.Available -> Unit
        }
    }
}

/** Secondary evidence caption: muted by default, caution color only for stored refusals. */
@Composable
private fun EvidenceLine(text: String, caution: Boolean = false) {
    val theme = AtharTheme
    AtharText(
        text,
        style = theme.typography.caption,
        color = if (caution) theme.colors.dust else theme.colors.muted,
    )
}

/** Counts per stored kind, using the exact kind labels already shown in the event log. */
@Composable
private fun kindBreakdownLine(evidence: PerformanceEvidence040): String? {
    if (evidence.eventCountByKind.isEmpty()) return null
    val labels = mapOf(
        PortfolioEntryKind.BUY to stringResource(R.string.plan_portfolio_kind_BUY),
        PortfolioEntryKind.SELL to stringResource(R.string.plan_portfolio_kind_SELL),
        PortfolioEntryKind.CONTRIBUTION to stringResource(R.string.plan_portfolio_kind_CONTRIBUTION),
        PortfolioEntryKind.WITHDRAWAL to stringResource(R.string.plan_portfolio_kind_WITHDRAWAL),
        PortfolioEntryKind.FEE to stringResource(R.string.plan_portfolio_kind_FEE),
        PortfolioEntryKind.CASH_DIVIDEND to stringResource(R.string.plan_portfolio_kind_CASH_DIVIDEND),
        PortfolioEntryKind.REINVESTMENT to stringResource(R.string.plan_portfolio_kind_REINVESTMENT),
    )
    return evidence.eventCountByKind.entries.joinToString(" · ") { (kind, count) ->
        "${labels[kind].orEmpty()} $count"
    }
}
