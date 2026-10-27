package com.athar.core.domain.calc.performance

import com.athar.core.common.money.Money
import com.athar.core.domain.model.performance.DatedCashFlow
import com.athar.core.domain.model.performance.DatedValuation
import com.athar.core.domain.model.performance.XirrAmbiguity
import com.athar.core.domain.model.performance.XirrInput
import com.athar.core.domain.model.performance.XirrResult
import com.google.common.truth.Truth.assertThat
import kotlinx.datetime.LocalDate
import org.junit.jupiter.api.Test
import java.math.BigDecimal

/**
 * WM047 evidence harness: measures production [XirrCalc.annualRate] calls on fixed public
 * fixtures, before and after the conventional fast path, with identical methodology.
 *
 * It deliberately asserts RESULT SHAPES only (never wall-clock thresholds — timing is recorded
 * evidence, not a pass/fail gate) and prints raw per-iteration nanos as `WM047_TIMING` lines,
 * which the run harness extracts from the JUnit XML into `build/qa/wealth-047/`.
 *
 * Fixtures (all public, all conventional unless stated):
 *  - A `wm047-native-mirror`: the exact production-observed 2-flow series
 *    (-1000 SAR on 2026-01-01, +1200 on 2026-04-01), reviewed oracle ratio 1.0947101751.
 *  - B `conventional-61-flows`: 60 monthly -100 contributions (2021-01 .. 2025-12) plus a
 *    terminal valuation 8000 on 2026-01-01 — one sign variation, realistic import size.
 *  - C `non-conventional-control`: -100, +230, -132 — two sign variations; must keep the
 *    4096-point scan path and its MULTIPLE_SIGN_CHANGES ambiguity in both phases.
 */
class Xirr047JvmTimingTest {

    private fun flow(id: String, date: String, amount: String) =
        DatedCashFlow(id, LocalDate.parse(date), Money.of(amount))

    private fun nativeMirror(): XirrInput = XirrInput(
        flows = listOf(
            flow("wm047-in", "2026-01-01", "-1000"),
            flow("wm047-out", "2026-04-01", "1200"),
        ),
    )

    private fun conventional61Flows(): XirrInput {
        val flows = (0 until 60).map { i ->
            val month = i % 12 + 1
            val year = 2021 + i / 12
            val date = "%04d-%02d-01".format(year, month)
            flow("sub-%02d".format(i + 1), date, "-100")
        }
        return XirrInput(
            flows = flows,
            terminalValuation = DatedValuation(LocalDate.parse("2026-01-01"), Money.of("8000")),
        )
    }

    private fun nonConventionalControl(): XirrInput = XirrInput(
        flows = listOf(
            flow("in", "2022-01-01", "-100"),
            flow("out", "2023-01-01", "230"),
            flow("rein", "2024-01-01", "-132"),
        ),
    )

    private fun assertNativeMirror(result: XirrResult) {
        assertThat(result).isInstanceOf(XirrResult.Rate::class.java)
        val rate = (result as XirrResult.Rate).annualRateRatio
        assertThat((rate - BigDecimal("1.0947101751")).abs() <= BigDecimal("1e-9")).isTrue()
    }

    private fun assertConventional(result: XirrResult) {
        assertThat(result).isInstanceOf(XirrResult.Rate::class.java)
        val rate = (result as XirrResult.Rate).annualRateRatio
        assertThat(rate.signum() > 0 && rate <= BigDecimal(10)).isTrue()
    }

    private fun assertNonConventional(result: XirrResult) {
        assertThat(result).isInstanceOf(XirrResult.Ambiguous::class.java)
        val ambiguous = result as XirrResult.Ambiguous
        assertThat(ambiguous.reason).isEqualTo(XirrAmbiguity.MULTIPLE_SIGN_CHANGES)
        assertThat(ambiguous.candidateAnnualRateRatios).hasSize(2)
    }

    private fun measure(fixture: String, input: XirrInput, warmup: Int, samples: Int, assertShape: (XirrResult) -> Unit) {
        var blackhole = 0
        repeat(warmup) { assertShape(XirrCalc.annualRate(input)) }
        var min = Long.MAX_VALUE
        var max = Long.MIN_VALUE
        val all = ArrayList<Long>(samples)
        repeat(samples) { i ->
            val start = System.nanoTime()
            val result = XirrCalc.annualRate(input)
            val elapsed = System.nanoTime() - start
            assertShape(result)
            blackhole += result.hashCode()
            all.add(elapsed)
            if (elapsed < min) min = elapsed
            if (elapsed > max) max = elapsed
            println("WM047_TIMING fixture=$fixture iteration=${i + 1} nanos=$elapsed")
        }
        val sorted = all.sorted()
        val median = if (samples % 2 == 1) sorted[samples / 2] else (sorted[samples / 2 - 1] + sorted[samples / 2]) / 2
        println(
            "WM047_TIMING_SUMMARY fixture=$fixture samples=$samples median_nanos=$median " +
                "min_nanos=$min max_nanos=$max blackhole=$blackhole",
        )
    }

    @Test
    fun `fixture A - wm047 native mirror production calls`() {
        measure("A_wm047_native_mirror", nativeMirror(), warmup = 20, samples = 100, assertShape = ::assertNativeMirror)
    }

    @Test
    fun `fixture B - conventional 61 flow production calls`() {
        measure("B_conventional_61_flows", conventional61Flows(), warmup = 1, samples = 3, assertShape = ::assertConventional)
    }

    @Test
    fun `fixture C - non conventional control keeps the scanned ambiguity`() {
        measure("C_non_conventional_control", nonConventionalControl(), warmup = 20, samples = 100, assertShape = ::assertNonConventional)
    }
}
