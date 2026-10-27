package com.athar.core.domain.calc.portfolio

import com.athar.core.common.money.Money
import com.athar.core.common.result.Outcome
import com.athar.core.domain.model.portfolio.AssetClass
import com.athar.core.domain.model.portfolio.ExternalContribution
import com.athar.core.domain.model.portfolio.LedgerRejection
import com.athar.core.domain.model.portfolio.PortfolioLedger
import com.athar.core.domain.model.portfolio.PortfolioSecurity
import com.athar.core.domain.model.portfolio.SecurityBuy
import com.athar.core.domain.model.portfolio.SecurityQuote
import com.google.common.truth.Truth.assertThat
import kotlinx.datetime.LocalDate
import org.junit.jupiter.api.Test
import java.math.BigDecimal

/**
 * Allocation-oracle tests: class fractions computed from actual priced market values
 * (holdings + portfolio cash), gaps vs point targets, range positions, unknown-class
 * bucket, partial-valuation disclosure, and goal validation with self-explaining errors.
 * Fractions are 8dp HALF_EVEN.
 */
class AllocationCalcTest {

    private val equity = PortfolioSecurity("SEC-A", "صندوق أ", AssetClass.EQUITY)
    private val sukuk = PortfolioSecurity("SEC-B", "صندوق ب", AssetClass.SUKUK)
    private val unclassified = PortfolioSecurity("SEC-C", "صندوق ج", null)

    /** cash 200 + A 800 + B 700 + C 600 → total measured 2300. Fractions: EQUITY 800/2300, SUKUK 700/2300, CASH 200/2300, unknown 600/2300. */
    private fun accountingWithCleanValues(): PortfolioAccounting {
        val ledger = when (
            val o = PortfolioLedger.of(
                baseCurrency = "SAR",
                openingCash = Money.zero(),
                securities = listOf(equity, sukuk, unclassified),
                events = listOf(
                    ExternalContribution("c1", LocalDate(2026, 1, 5), 1, Money.of("1700")),
                    SecurityBuy(
                        "b1", LocalDate(2026, 1, 6), 1, "SEC-A",
                        BigDecimal("100"), Money.of("5.00"), Money.of("0"),
                    ),
                    SecurityBuy(
                        "b2", LocalDate(2026, 1, 6), 2, "SEC-B",
                        BigDecimal("100"), Money.of("5.00"), Money.of("0"),
                    ),
                    SecurityBuy(
                        "b3", LocalDate(2026, 1, 6), 3, "SEC-C",
                        BigDecimal("100"), Money.of("5.00"), Money.of("0"),
                    ),
                ),
            )
        ) {
            is Outcome.Success -> o.value
            is Outcome.Failure -> throw AssertionError("unexpected ${o.error}")
        }
        val accounting = when (
            val r = PortfolioAccountingCalc.account(
                ledger = ledger,
                quotes = listOf(
                    SecurityQuote("SEC-A", LocalDate(2026, 3, 31), Money.of("8.00")), // MV 800
                    SecurityQuote("SEC-B", LocalDate(2026, 3, 31), Money.of("7.00")), // MV 700
                    SecurityQuote("SEC-C", LocalDate(2026, 3, 31), Money.of("6.00")), // MV 600
                ),
                asOf = LocalDate(2026, 3, 31),
            )
        ) {
            is Outcome.Success -> r.value
            is Outcome.Failure -> throw AssertionError("unexpected ${r.error}")
        }
        // cash = 1700 − 3×500 = 200
        return accounting
    }

    private fun <T> success(outcome: Outcome<T, LedgerRejection>): T = when (outcome) {
        is Outcome.Success -> outcome.value
        is Outcome.Failure -> throw AssertionError("unexpected rejection: ${outcome.error}")
    }

    private fun <T> successOf(outcome: Outcome<T, AllocationGoalError>): T = when (outcome) {
        is Outcome.Success -> outcome.value
        is Outcome.Failure -> throw AssertionError("unexpected error: ${outcome.error}")
    }

    private fun goalErrorOf(outcome: Outcome<AllocationReport, AllocationGoalError>): AllocationGoalError =
        when (outcome) {
            is Outcome.Success -> throw AssertionError("expected rejection, got ${outcome.value}")
            is Outcome.Failure -> outcome.error
        }

    @Test
    fun `fractions come from actual market values including cash and unknown class`() {
        val report = successOf(
            AllocationCalc.evaluate(
                accounting = accountingWithCleanValues(),
                goals = mapOf(
                    AssetClass.EQUITY to AllocationGoal.PointTarget(BigDecimal("0.50")),
                    AssetClass.CASH to AllocationGoal.PointTarget(BigDecimal("0.50")),
                ),
            ),
        )
        // total measured = 800 + 700 + 600 + 200 cash = 2300
        assertThat(report.totalMeasured).isEquivalentAccordingToCompareTo(Money.of("2300"))
        assertThat(report.isPartial).isFalse()
        assertThat(report.fractions[AssetClass.EQUITY]!!)
            .isEquivalentAccordingToCompareTo(BigDecimal("800").divide(BigDecimal("2300"), 8, java.math.RoundingMode.HALF_EVEN))
        assertThat(report.fractions[AssetClass.CASH]!!)
            .isEquivalentAccordingToCompareTo(BigDecimal("200").divide(BigDecimal("2300"), 8, java.math.RoundingMode.HALF_EVEN))
        // unknown-class holding is priced and counted in the total, reported separately
        assertThat(report.unknownClassValue).isEquivalentAccordingToCompareTo(Money.of("600"))
        assertThat(report.unknownSecurityIds).containsExactly("SEC-C")
        assertThat(report.explanation).contains("SEC-C")
    }

    @Test
    fun `point target gap is actual minus target`() {
        val report = successOf(
            AllocationCalc.evaluate(
                accounting = accountingWithCleanValues(),
                goals = mapOf(
                    AssetClass.EQUITY to AllocationGoal.PointTarget(BigDecimal("0.50")),
                    AssetClass.CASH to AllocationGoal.PointTarget(BigDecimal("0.50")),
                ),
            ),
        )
        val equityStatus = report.statuses[AssetClass.EQUITY] as GoalStatus.PointGap
        val actualEquity = BigDecimal("800").divide(BigDecimal("2300"), 8, java.math.RoundingMode.HALF_EVEN)
        assertThat(equityStatus.gapFraction)
            .isEquivalentAccordingToCompareTo(actualEquity - BigDecimal("0.50"))
    }

    @Test
    fun `range target reports below within and above`() {
        // Craft values: total 1000 (A 300 EQUITY, cash 700) via a dedicated small portfolio
        val accounting = singleHoldingAccounting(mvA = "300", cash = "700")
        val report = successOf(
            AllocationCalc.evaluate(
                accounting = accounting,
                goals = emptyMap(), // range assertions use explicit goals below
            ),
        )
        assertThat(report.fractions[AssetClass.EQUITY]!!)
            .isEquivalentAccordingToCompareTo(BigDecimal("0.30"))

        fun positionFor(min: String, max: String): RangePosition {
            val r = successOf(
                AllocationCalc.evaluate(
                    accounting = accounting,
                    goals = mapOf(
                        AssetClass.EQUITY to AllocationGoal.RangeTarget(BigDecimal(min), BigDecimal(max)),
                    ),
                ),
            )
            return (r.statuses[AssetClass.EQUITY] as GoalStatus.RangeStatus).position
        }
        assertThat(positionFor("0.10", "0.25")).isEqualTo(RangePosition.ABOVE)
        assertThat(positionFor("0.30", "0.35")).isEqualTo(RangePosition.WITHIN)
        assertThat(positionFor("0.40", "0.60")).isEqualTo(RangePosition.BELOW)
    }

    @Test
    fun `unpriced holding makes allocation partial with explanation`() {
        val ledger = when (
            val o = PortfolioLedger.of(
                baseCurrency = "SAR",
                openingCash = Money.zero(),
                securities = listOf(equity, sukuk),
                events = listOf(
                    ExternalContribution("c1", LocalDate(2026, 1, 5), 1, Money.of("2000")),
                    SecurityBuy(
                        "b1", LocalDate(2026, 1, 6), 1, "SEC-A",
                        BigDecimal("100"), Money.of("5.00"), Money.of("0"),
                    ),
                    SecurityBuy(
                        "b2", LocalDate(2026, 1, 6), 2, "SEC-B",
                        BigDecimal("100"), Money.of("5.00"), Money.of("0"),
                    ),
                ),
            )
        ) {
            is Outcome.Success -> o.value
            is Outcome.Failure -> throw AssertionError("unexpected ${o.error}")
        }
        val accounting = success(
            // only SEC-A priced; SEC-B has no usable quote
            PortfolioAccountingCalc.account(
                ledger = ledger,
                quotes = listOf(SecurityQuote("SEC-A", LocalDate(2026, 3, 31), Money.of("8.00"))),
                asOf = LocalDate(2026, 3, 31),
            ),
        )
        val report = successOf(
            AllocationCalc.evaluate(
                accounting = accounting,
                goals = mapOf(
                    AssetClass.EQUITY to AllocationGoal.PointTarget(BigDecimal("0.50")),
                    AssetClass.CASH to AllocationGoal.PointTarget(BigDecimal("0.50")),
                ),
            ),
        )
        assertThat(report.isPartial).isTrue()
        assertThat(report.unpricedSecurityIds).containsExactly("SEC-B")
        assertThat(report.explanation).contains("SEC-B")
    }

    // ---- goal validation with explanations ----

    @Test
    fun `point targets must sum to one`() {
        val error = goalErrorOf(
            AllocationCalc.evaluate(
                accounting = accountingWithCleanValues(),
                goals = mapOf(
                    AssetClass.EQUITY to AllocationGoal.PointTarget(BigDecimal("0.50")),
                    AssetClass.CASH to AllocationGoal.PointTarget(BigDecimal("0.60")),
                ),
            ),
        )
        val sumError = error as AllocationGoalError.TargetsDoNotSumToOne
        assertThat(sumError.sum).isEquivalentAccordingToCompareTo(BigDecimal("1.10"))
    }

    @Test
    fun `nonpositive point target is rejected with the class named`() {
        val error = goalErrorOf(
            AllocationCalc.evaluate(
                accounting = accountingWithCleanValues(),
                goals = mapOf(
                    AssetClass.EQUITY to AllocationGoal.PointTarget(BigDecimal("0")),
                    AssetClass.CASH to AllocationGoal.PointTarget(BigDecimal("1.00")),
                ),
            ),
        )
        assertThat(error).isEqualTo(AllocationGoalError.NonPositiveTarget(AssetClass.EQUITY))
    }

    @Test
    fun `range with min above max is rejected`() {
        val error = goalErrorOf(
            AllocationCalc.evaluate(
                accounting = accountingWithCleanValues(),
                goals = mapOf(
                    AssetClass.EQUITY to AllocationGoal.RangeTarget(BigDecimal("0.60"), BigDecimal("0.20")),
                    AssetClass.CASH to AllocationGoal.PointTarget(BigDecimal("1.00")),
                ),
            ),
        )
        assertThat(error).isEqualTo(
            AllocationGoalError.InvalidRange(AssetClass.EQUITY, BigDecimal("0.60"), BigDecimal("0.20")),
        )
    }

    @Test
    fun `fraction rounding is 8dp HALF_EVEN`() {
        // 1/3 → 0.33333333 (9th digit 3 rounds down); 2/3 → 0.66666667 (9th digit 6 rounds up)
        val accounting = singleHoldingAccounting(mvA = "2", cash = "1")
        val report = successOf(AllocationCalc.evaluate(accounting, emptyMap()))
        assertThat(report.fractions[AssetClass.EQUITY]!!.toPlainString()).isEqualTo("0.66666667")
        assertThat(report.fractions[AssetClass.CASH]!!.toPlainString()).isEqualTo("0.33333333")
    }

    /** EQUITY holding A of 1 unit bought @1.00; contribution sized so cash ends at [cash]; A quoted at [mvA]. */
    private fun singleHoldingAccounting(mvA: String, cash: String): PortfolioAccounting {
        val ledger2 = when (
            val o = PortfolioLedger.of(
                baseCurrency = "SAR",
                openingCash = Money.zero(),
                securities = listOf(equity),
                events = listOf(
                    ExternalContribution(
                        "c1", LocalDate(2026, 1, 5), 1,
                        Money.of(BigDecimal(cash).add(BigDecimal.ONE).toPlainString()),
                    ),
                    SecurityBuy(
                        "b1", LocalDate(2026, 1, 6), 1, "SEC-A",
                        BigDecimal("1"), Money.of("1.00"), Money.of("0"),
                    ),
                ),
            )
        ) {
            is Outcome.Success -> o.value
            is Outcome.Failure -> throw AssertionError("unexpected ${o.error}")
        }
        val accounting = success(
            PortfolioAccountingCalc.account(
                ledger = ledger2,
                quotes = listOf(SecurityQuote("SEC-A", LocalDate(2026, 3, 31), Money.of(mvA))),
                asOf = LocalDate(2026, 3, 31),
            ),
        )
        assertThat(accounting.cash).isEquivalentAccordingToCompareTo(Money.of(cash))
        return accounting
    }
}
