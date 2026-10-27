package com.athar.feature.plan.debt

import com.athar.core.common.money.Money
import com.athar.core.domain.model.debt.DebtStrategy
import com.athar.core.domain.model.debt.StrategyRun
import com.google.common.truth.Truth.assertThat
import kotlinx.datetime.LocalDate
import org.junit.jupiter.api.Test

class DebtResultPresentationTest {
    private val date = LocalDate(2026, 12, 21)

    private fun run(
        strategy: DebtStrategy = DebtStrategy.AVALANCHE,
        amount: String = "100.00",
        currency: String = "SAR",
        settled: Boolean = true,
        payoff: LocalDate? = date,
    ) = StrategyRun(
        strategy, emptyList(), emptyList(), Money.of("0", currency), Money.of("0", currency),
        Money.of(amount, currency), settled, payoff,
    )

    @Test fun `exact large near-equal totals rank without floating point loss`() {
        val avalanche = run(amount = "100000001.01")
        val snowball = run(DebtStrategy.SNOWBALL, "100000001.02")
        assertThat(debtCheaperStrategy(avalanche, snowball)).isEqualTo(DebtStrategy.AVALANCHE)
    }

    @Test fun `lower snowball total ranks the actual snowball strategy`() {
        assertThat(debtCheaperStrategy(run(amount = "100.01"), run(DebtStrategy.SNOWBALL)))
            .isEqualTo(DebtStrategy.SNOWBALL)
    }

    @Test fun `numerically equal differently scaled totals have no winner`() {
        assertThat(debtCheaperStrategy(run(amount = "100.0"), run(DebtStrategy.SNOWBALL, "100.00"))).isNull()
    }

    @Test fun `unlike literal currencies are never ranked one to one`() {
        assertThat(debtCheaperStrategy(run(amount = "1", currency = "KWD"), run(DebtStrategy.SNOWBALL, "2", "SAR")))
            .isNull()
    }

    @Test fun `unsettled avalanche partial total cannot be cheaper`() {
        assertThat(debtCheaperStrategy(run(amount = "1", settled = false), run(DebtStrategy.SNOWBALL))).isNull()
    }

    @Test fun `unsettled snowball partial total cannot be cheaper`() {
        assertThat(debtCheaperStrategy(run(), run(DebtStrategy.SNOWBALL, "1", settled = false))).isNull()
    }

    @Test fun `settled run exposes its exact observed payoff date`() {
        assertThat(debtPayoffDate(run())).isEqualTo(date)
    }

    @Test fun `missing payoff date remains missing even on a settled model`() {
        assertThat(debtPayoffDate(run(payoff = null))).isNull()
    }

    @Test fun `a date on an unsettled model is withheld rather than extrapolated`() {
        assertThat(debtPayoffDate(run(settled = false))).isNull()
    }
}
