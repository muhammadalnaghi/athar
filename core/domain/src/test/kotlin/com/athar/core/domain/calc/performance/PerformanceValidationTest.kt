package com.athar.core.domain.calc.performance

import com.athar.core.common.money.Money
import com.athar.core.domain.model.performance.DatedCashFlow
import com.athar.core.domain.model.performance.DatedValuation
import com.athar.core.domain.model.performance.ExternalFlowBoundary
import com.athar.core.domain.model.performance.TwrInput
import com.athar.core.domain.model.performance.TwrRejection
import com.athar.core.domain.model.performance.TwrResult
import com.athar.core.domain.model.performance.XirrAmbiguity
import com.athar.core.domain.model.performance.XirrInput
import com.athar.core.domain.model.performance.XirrRejection
import com.athar.core.domain.model.performance.XirrResult
import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import kotlinx.datetime.LocalDate
import org.junit.jupiter.api.Test
import java.math.BigDecimal

/**
 * Focused P1B validation-contract regressions for [XirrCalc] and [TwrCalc], kept out of the
 * original test classes to respect the 300-line file limit (spec WM028-08 as amended).
 *
 * Pinned contracts:
 * - XIRR valuations are NONNEGATIVE observed STATE: negative is rejected ([XirrRejection.NEGATIVE_VALUATION])
 *   before folding; a genuine zero valuation is a known state that folds through without
 *   distortion — it is never [XirrRejection.ZERO_AMOUNT] and never missing data.
 * - A pure total loss (zero terminal, no positive payout) has no admissible XIRR root and is a
 *   typed bounded [XirrRejection.NO_SIGN_CHANGE], never `ZERO_AMOUNT`; a zero terminal after real
 *   withdrawals still solves.
 * - An all-zero state is documented undefined ambiguity ([XirrAmbiguity.EVERY_RATE_IS_A_ROOT],
 *   no grid candidates); caller-supplied zero flows stay rejected (validity is decided by input
 *   classification, never by reserved flow ids).
 * - TWR never chains a negative valuation base into a factor (no fabricated positive returns).
 * - Same-date boundaries carry independently observed before/after values: equal dates do NOT
 *   imply a flat market, so both a real same-date market move and a flat same-date flow split
 *   are legitimate and must stay green (no `b1.valueAfter == b2.valueBefore` guard).
 */
class PerformanceValidationTest {

    private fun flow(id: String, date: String, amount: String) =
        DatedCashFlow(id, LocalDate.parse(date), Money.of(amount))

    private fun valuation(date: String, amount: String) =
        DatedValuation(LocalDate.parse(date), Money.of(amount))

    private fun boundary(id: String, date: String, seq: Int, before: String, ext: String, after: String) =
        ExternalFlowBoundary(id, LocalDate.parse(date), seq, Money.of(before), Money.of(ext), Money.of(after))

    private fun twr(initial: String, closing: String, boundaries: List<ExternalFlowBoundary>) =
        TwrInput(
            LocalDate.parse("2024-01-01"),
            Money.of(initial),
            LocalDate.parse("2024-12-31"),
            Money.of(closing),
            boundaries,
        )

    private fun assertXirrRate(result: XirrResult, expected: String) {
        assertWithMessage("expected a Rate but got $result")
            .that(result).isInstanceOf(XirrResult.Rate::class.java)
        val rate = (result as XirrResult.Rate).annualRateRatio
        val diff = (rate - BigDecimal(expected)).abs()
        assertWithMessage("rate $rate vs oracle $expected, diff $diff")
            .that(diff <= BigDecimal("1e-12")).isTrue()
    }

    private fun assertXirrRejected(result: XirrResult, reason: XirrRejection) {
        assertWithMessage("expected Rejected($reason) but got $result")
            .that(result).isInstanceOf(XirrResult.Rejected::class.java)
        assertThat((result as XirrResult.Rejected).reason).isEqualTo(reason)
    }

    private fun assertTwrRejected(result: TwrResult, reason: TwrRejection) {
        assertWithMessage("expected Rejected($reason) but got $result")
            .that(result).isInstanceOf(TwrResult.Rejected::class.java)
        assertThat((result as TwrResult.Rejected).reason).isEqualTo(reason)
    }

    // --- XIRR: valuations are nonnegative state, never signed flows ---

    @Test
    fun `negative opening valuation is rejected before folding - not flipped into a signed flow`() {
        // A sign-bugged caller (opening -1000) must surface as NEGATIVE_VALUATION, never as a
        // flipped +1000 withdrawal that fakes NO_SIGN_CHANGE or a Rate.
        val result = XirrCalc.annualRate(
            XirrInput(
                flows = emptyList(),
                openingValuation = valuation("2023-01-01", "-1000"),
                terminalValuation = valuation("2024-01-01", "1100"),
            ),
        )
        assertXirrRejected(result, XirrRejection.NEGATIVE_VALUATION)
    }

    @Test
    fun `negative terminal valuation is rejected before folding`() {
        val result = XirrCalc.annualRate(
            XirrInput(
                flows = emptyList(),
                openingValuation = valuation("2024-01-01", "1000"),
                terminalValuation = valuation("2024-12-31", "-200"),
            ),
        )
        assertXirrRejected(result, XirrRejection.NEGATIVE_VALUATION)
    }

    @Test
    fun `genuine zero opening valuation is a known state and folds through to the exact oracle`() {
        // 0 @2022-12-31 (known empty book), -1000 @2023-01-01, +1100 @2024-01-01:
        // NPV = -1000*B^(-1/365) + 1100*B^(-366/365) = 0 -> B = 1.1 -> r = 0.1 exactly.
        // The zero state entry contributes nothing and must not trigger ZERO_AMOUNT.
        val result = XirrCalc.annualRate(
            XirrInput(
                flows = listOf(flow("in", "2023-01-01", "-1000")),
                openingValuation = valuation("2022-12-31", "0"),
                terminalValuation = valuation("2024-01-01", "1100"),
            ),
        )
        assertXirrRate(result, "0.1")
    }

    // --- XIRR: total loss vs solvable zero terminal ---

    @Test
    fun `pure total loss with zero terminal valuation is a typed no-root - never zero-amount`() {
        // NPV(r) = -1000 < 0 for every rate in the bounded window: no admissible
        // root exists. Honest outcome is NO_SIGN_CHANGE (the known -100% presentation convention
        // lives in TWR), NOT ZERO_AMOUNT pretending the caller supplied bad data.
        val result = XirrCalc.annualRate(
            XirrInput(
                flows = listOf(flow("in", "2024-01-01", "-1000")),
                terminalValuation = valuation("2024-12-31", "0"),
            ),
        )
        assertXirrRejected(result, XirrRejection.NO_SIGN_CHANGE)
    }

    @Test
    fun `zero terminal after a real withdrawal still solves - no total-loss short circuit`() {
        // -1000 @t0, +500 @t1 (365 d), 0 @t2: NPV = -1000 + 500*B^(-1) = 0 -> B = 0.5, r = -0.5.
        // The zero terminal contributes nothing and must not turn this into "no root".
        val result = XirrCalc.annualRate(
            XirrInput(
                flows = listOf(
                    flow("in", "2023-01-01", "-1000"),
                    flow("out", "2024-01-01", "500"),
                ),
                terminalValuation = valuation("2025-01-01", "0"),
            ),
        )
        assertXirrRate(result, "-0.5")
    }

    // --- XIRR: all-zero state and caller zero flows ---

    @Test
    fun `all-zero valuation state is documented undefined ambiguity with no grid candidates`() {
        // Zero opening + zero terminal, no flows: the portfolio never held capital, NPV(r) = 0
        // for every rate — undefined, not a Rate. The grid scan must be skipped so the result
        // does not carry thousands of spurious candidates.
        val result = XirrCalc.annualRate(
            XirrInput(
                flows = emptyList(),
                openingValuation = valuation("2024-01-01", "0"),
                terminalValuation = valuation("2024-12-31", "0"),
            ),
        )
        assertThat(result).isInstanceOf(XirrResult.Ambiguous::class.java)
        val ambiguous = result as XirrResult.Ambiguous
        assertThat(ambiguous.reason).isEqualTo(XirrAmbiguity.EVERY_RATE_IS_A_ROOT)
        assertThat(ambiguous.candidateAnnualRateRatios).isEmpty()
    }

    @Test
    fun `caller supplied zero flow stays rejected even though zero valuations are allowed`() {
        // Validity is decided by input classification (caller flow vs folded valuation), never
        // by matching reserved synthetic flow ids.
        val result = XirrCalc.annualRate(
            XirrInput(
                flows = listOf(
                    flow("in", "2024-01-01", "-1000"),
                    flow("z", "2024-06-01", "0"),
                    flow("out", "2024-12-31", "1100"),
                ),
            ),
        )
        assertXirrRejected(result, XirrRejection.ZERO_AMOUNT)
    }

    // --- TWR: negative valuation bases are corrupt state, never factors ---

    @Test
    fun `negative to negative valuation base is rejected - no fabricated positive return`() {
        // (-200)/(-100) = +2 must NOT chain into Rate(+100%): deepening negative equity is
        // upstream data corruption, not a return.
        val result = TwrCalc.timeWeightedReturn(twr("-100", "-200", emptyList()))
        assertTwrRejected(result, TwrRejection.ZERO_OR_SIGN_CHANGING_BASE)
    }

    @Test
    fun `negative valueAfter poisons the final sub period base and is rejected`() {
        // 100 -> boundary(150, -250, after -100) -> closing -50: the final base is negative, so
        // the (-50)/(-100) = 0.5 "recovery" must not chain (previously faked Rate(-0.25)).
        val result = TwrCalc.timeWeightedReturn(
            twr("100", "-50", listOf(boundary("w1", "2024-06-30", 1, "150", "-250", "-100"))),
        )
        assertTwrRejected(result, TwrRejection.ZERO_OR_SIGN_CHANGING_BASE)
    }

    @Test
    fun `negative initial base is rejected at the first sub period`() {
        val result = TwrCalc.timeWeightedReturn(
            twr("-100", "-140", listOf(boundary("w1", "2024-06-30", 1, "-150", "10", "-140"))),
        )
        assertTwrRejected(result, TwrRejection.ZERO_OR_SIGN_CHANGING_BASE)
    }

    // --- TWR: exact same-date boundary semantics (pinned, both legitimate behaviors) ---

    @Test
    fun `same date boundaries with a real observed market move chain the actual factors`() {
        // Same-date boundaries carry independently observed before/after values; equal dates do
        // NOT imply a flat market. b1(100,+100,200), real market move to 100, b2(100,+100,200),
        // closing 300: factors 1 * 0.5 * 1.5 = 0.75 -> -25% is CORRECT for these observations.
        // No b1.valueAfter == b2.valueBefore guard may exist.
        val result = TwrCalc.timeWeightedReturn(
            twr(
                "100", "300",
                listOf(
                    boundary("b1", "2024-06-30", 1, "100", "100", "200"),
                    boundary("b2", "2024-06-30", 2, "100", "100", "200"),
                ),
            ),
        )
        assertWithMessage("expected a Rate but got $result")
            .that(result).isInstanceOf(TwrResult.Rate::class.java)
        val rate = result as TwrResult.Rate
        val diff = (rate.periodReturnRatio - BigDecimal("-0.25")).abs()
        assertWithMessage("ratio ${rate.periodReturnRatio} vs oracle -0.25, diff $diff")
            .that(diff <= BigDecimal("1e-9")).isTrue()
        assertThat(rate.subPeriodCount).isEqualTo(3)
    }

    @Test
    fun `same date flat market split of two contributions stays a neutral chain`() {
        // Flat intraday market, two same-date contributions: 100 ->(+100)-> 200 ->(+100)-> 300.
        // Factors 1 * 1 * 1 -> exactly 0% over 3 sub-periods.
        val result = TwrCalc.timeWeightedReturn(
            twr(
                "100", "300",
                listOf(
                    boundary("b1", "2024-06-30", 1, "100", "100", "200"),
                    boundary("b2", "2024-06-30", 2, "200", "100", "300"),
                ),
            ),
        )
        assertWithMessage("expected a Rate but got $result")
            .that(result).isInstanceOf(TwrResult.Rate::class.java)
        val rate = result as TwrResult.Rate
        val diff = (rate.periodReturnRatio - BigDecimal("0")).abs()
        assertWithMessage("ratio ${rate.periodReturnRatio} vs oracle 0, diff $diff")
            .that(diff <= BigDecimal("1e-9")).isTrue()
        assertThat(rate.subPeriodCount).isEqualTo(3)
    }
}
