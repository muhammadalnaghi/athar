package com.athar.feature.plan.debt

import androidx.compose.runtime.Composable
import androidx.compose.ui.tooling.preview.Preview
import com.athar.core.common.money.Money
import com.athar.core.designsystem.theme.AtharTheme
import com.athar.core.domain.model.debt.DebtId
import com.athar.core.domain.model.debt.DebtPayoffOutcome
import com.athar.core.domain.model.debt.DebtStrategy
import com.athar.core.domain.model.debt.DebtTermRejection
import com.athar.core.domain.model.debt.StrategyRun
import com.athar.core.domain.model.debt.UnsupportedTermReason
import kotlinx.datetime.LocalDate

internal fun syntheticSettledRun(strategy: DebtStrategy): StrategyRun = StrategyRun(
    strategy = strategy,
    ledger = emptyList(),
    summaries = emptyList(),
    totalInterest = Money.of("1430.00"),
    totalFees = Money.of("230.00"),
    totalPaid = Money.of("9660.00"),
    allSettled = true,
    finalPayoffDate = LocalDate(year = 2028, monthNumber = 6, dayOfMonth = 1),
)

internal fun syntheticPartialRun(strategy: DebtStrategy): StrategyRun = StrategyRun(
    strategy = strategy,
    ledger = emptyList(),
    summaries = emptyList(),
    totalInterest = Money.of("812.50"),
    totalFees = Money.of("115.00"),
    totalPaid = Money.of("5427.50"),
    allSettled = false,
    finalPayoffDate = null,
)

@Preview(name = "Debt result — compared, settled (synthetic)", widthDp = 360, locale = "ar")
@Composable
internal fun PreviewDebtResultCompared() {
    AtharTheme {
        DebtScenarioResult(
            outcome = DebtPayoffOutcome.Compared(
                avalanche = syntheticSettledRun(DebtStrategy.AVALANCHE),
                snowball = StrategyRun(
                    strategy = DebtStrategy.SNOWBALL,
                    ledger = emptyList(),
                    summaries = emptyList(),
                    totalInterest = Money.of("1618.00"),
                    totalFees = Money.of("230.00"),
                    totalPaid = Money.of("9848.00"),
                    allSettled = true,
                    finalPayoffDate = LocalDate(year = 2028, monthNumber = 8, dayOfMonth = 1),
                ),
            ),
            onOpenSchedule = {},
        )
    }
}

@Preview(name = "Debt result — horizon exhausted (synthetic)", widthDp = 360, locale = "ar")
@Composable
internal fun PreviewDebtResultHorizonExhausted() {
    AtharTheme {
        DebtScenarioResult(
            outcome = DebtPayoffOutcome.HorizonExhausted(
                horizonCycles = 24,
                avalanche = syntheticPartialRun(DebtStrategy.AVALANCHE),
                snowball = syntheticPartialRun(DebtStrategy.SNOWBALL),
            ),
            onOpenSchedule = {},
        )
    }
}

@Preview(name = "Debt result — unsupported terms (synthetic)", widthDp = 360, locale = "ar")
@Composable
internal fun PreviewDebtResultUnsupportedTerms() {
    AtharTheme {
        DebtScenarioResult(
            outcome = DebtPayoffOutcome.UnsupportedTerms(
                rejections = listOf(
                    DebtTermRejection(DebtId("قرض-1"), UnsupportedTermReason.FLAT_PROFIT_TERMS),
                    DebtTermRejection(DebtId("قرض-2"), UnsupportedTermReason.PREPAYMENT_RESTRICTED),
                ),
            ),
            onOpenSchedule = {},
        )
    }
}
