package com.athar.feature.plan.scenarios

import com.athar.core.common.money.Money
import com.athar.core.domain.model.cashforecast.CashForecastOutcome
import com.athar.core.domain.model.cashforecast.PartitionStatus
import com.google.common.truth.Truth.assertThat
import java.math.BigDecimal
import org.junit.jupiter.api.Test

/**
 * Focused JVM tests for the pure cash scenario draft adapter. Every valid-draft case runs
 * the real [com.athar.core.domain.calc.cashforecast.CashForecastCalculator]; no fakes, no
 * fabricated headlines.
 */
class CashScenarioDraftTest {

    @Test
    fun `missing required fields surface exact keys and never an outcome`() {
        val result = evaluateCashDraft(CashScenarioDraft())

        assertThat(result.outcome).isNull()
        assertThat(result.errors).containsExactly("openingDate", "openingCash", "horizonEnd")
    }

    @Test
    fun `invalid currency is rejected with the currency key`() {
        assertThat(evaluateCashDraft(draft(currency = "usd")).errors).containsExactly("currency")
        assertThat(evaluateCashDraft(draft(currency = "XX")).errors).containsExactly("currency")
        // ISO special code with no minor units: not a usable literal currency here.
        assertThat(evaluateCashDraft(draft(currency = "XXX")).errors).containsExactly("currency")
    }

    @Test
    fun `malformed grouping, exponents and non-positive amounts never become money`() {
        assertThat(evaluateCashDraft(draft(openingCash = "1,00.00")).errors)
            .containsExactly("openingCash")
        assertThat(evaluateCashDraft(draft(openingCash = "1E5")).errors)
            .containsExactly("openingCash")

        val result = evaluateCashDraft(
            draft(
                flows = listOf(
                    CashFlowDraft(id = 7, date = "2026-03-01", amount = "1E5"),
                    CashFlowDraft(id = 8, date = "2026-03-01", amount = "0"),
                ),
            ),
        )
        assertThat(result.outcome).isNull()
        assertThat(result.errors).containsExactly("flow-7-amount", "flow-8-amount")
    }

    @Test
    fun `blank reserve stays unknown and keeps the partition partial`() {
        val forecast = forecastOf(draft(reserve = "", scopeComplete = true, anchorFresh = true))
        val partition = forecast.result.partitions.single()

        assertThat(partition.status).isEqualTo(PartitionStatus.PARTIAL)
        assertThat(forecast.result.decisionReady).isFalse()
        assertThat(partition.opening?.reserved).isNull()
    }

    @Test
    fun `false attestations keep a known-reserve path partial`() {
        val forecast = forecastOf(draft(reserve = "100.00", scopeComplete = false, anchorFresh = false))
        val partition = forecast.result.partitions.single()

        assertThat(partition.status).isEqualTo(PartitionStatus.PARTIAL)
        assertThat(forecast.result.decisionReady).isFalse()
        assertThat(partition.opening?.reserved).isNotNull()
    }

    @Test
    fun `explicit zero reserve with both attestations is confidently known zero`() {
        val forecast = forecastOf(draft(reserve = "0"))
        val partition = forecast.result.partitions.single()

        assertThat(partition.status).isEqualTo(PartitionStatus.CONFIDENT)
        assertThat(forecast.result.decisionReady).isTrue()
        assertThat(partition.headline).isNotNull()
        assertThat(partition.opening?.reserved?.isZero()).isTrue()
        assertThat(partition.opening?.available).isEqualTo(partition.opening?.cash)
    }

    @Test
    fun `KWD fils precision is retained exactly`() {
        val forecast = forecastOf(
            draft(
                currency = "KWD",
                openingCash = "10.123",
                reserve = "0",
                flows = listOf(
                    CashFlowDraft(id = 1, date = "2026-02-10", amount = "0.500", direction = CashFlowDirection.INFLOW),
                ),
            ),
        )
        val partition = forecast.result.partitions.single()

        assertThat(partition.currency).isEqualTo("KWD")
        assertThat(partition.status).isEqualTo(PartitionStatus.CONFIDENT)
        assertThat(partition.headline?.finalBalance)
            .isEqualTo(Money.of(BigDecimal("10.623"), "KWD"))
    }

    @Test
    fun `one fils of over-spending leaves a one fils gap`() {
        val forecast = forecastOf(
            draft(
                currency = "KWD",
                openingCash = "10.000",
                reserve = "0",
                flows = listOf(
                    CashFlowDraft(id = 1, date = "2026-02-10", amount = "10.001"),
                ),
            ),
        )
        val partition = forecast.result.partitions.single()

        assertThat(partition.headline?.firstNegativeAvailableDate).isNotNull()
        assertThat(partition.headline?.finalBalance)
            .isEqualTo(Money.of(BigDecimal("-0.001"), "KWD"))
    }

    @Test
    fun `direction signs the flow outflow subtracts and inflow adds`() {
        val outflow = forecastOf(
            draft(
                reserve = "0",
                flows = listOf(CashFlowDraft(id = 1, date = "2026-02-10", amount = "40.00")),
            ),
        )
        val inflow = forecastOf(
            draft(
                reserve = "0",
                flows = listOf(
                    CashFlowDraft(id = 1, date = "2026-02-10", amount = "40.00", direction = CashFlowDirection.INFLOW),
                ),
            ),
        )

        assertThat(outflow.result.partitions.single().headline?.finalBalance)
            .isEqualTo(Money.of(BigDecimal("60.00"), "SAR"))
        assertThat(inflow.result.partitions.single().headline?.finalBalance)
            .isEqualTo(Money.of(BigDecimal("140.00"), "SAR"))
    }

    @Test
    fun `same-day, before-opening and after-horizon flow dates are rejected`() {
        // Opening is end-of-day: a same-day flow would risk double counting.
        assertThat(evaluateCashDraft(draft(flows = listOf(flowOn("2026-01-31")))).errors)
            .containsExactly("flow-1-date")
        assertThat(evaluateCashDraft(draft(flows = listOf(flowOn("2026-01-30")))).errors)
            .containsExactly("flow-1-date")
        assertThat(evaluateCashDraft(draft(flows = listOf(flowOn("2027-02-01")))).errors)
            .containsExactly("flow-1-date")

        // Horizon ordering itself is enforced against the opening date.
        assertThat(evaluateCashDraft(draft(horizonEnd = "2026-01-30")).errors)
            .containsExactly("horizonEnd")
    }

    @Test
    fun `multiple dated rows land as stable expected evidence identities`() {
        val forecast = forecastOf(
            draft(
                reserve = "0",
                flows = listOf(
                    CashFlowDraft(id = 1, date = "2026-02-10", amount = "10.00"),
                    CashFlowDraft(id = 2, date = "2026-03-10", amount = "5.00", direction = CashFlowDirection.INFLOW),
                ),
            ),
        )
        val partition = forecast.result.partitions.single()

        assertThat(partition.status).isEqualTo(PartitionStatus.CONFIDENT)
        assertThat(forecast.result.decisionReady).isTrue()
        assertThat(partition.path).hasSize(3)
        assertThat(partition.path[1].effectIds).containsExactly("flow-1")
        assertThat(partition.path[2].effectIds).containsExactly("flow-2")
        assertThat(partition.headline?.finalBalance)
            .isEqualTo(Money.of(BigDecimal("95.00"), "SAR"))
    }

    @Test
    fun `row cap and duplicate ids are rejected without truncation`() {
        val capped = evaluateCashDraft(
            draft(flows = (1..25).map { CashFlowDraft(id = it, date = "2026-02-10", amount = "1.00") }),
        )
        assertThat(capped.outcome).isNull()
        assertThat(capped.errors).containsExactly("cashFlows")

        val atCap = evaluateCashDraft(
            draft(flows = (1..24).map { CashFlowDraft(id = it, date = "2026-02-10", amount = "1.00") }),
        )
        assertThat(atCap.errors).isEmpty()
        assertThat(atCap.outcome).isInstanceOf(CashForecastOutcome.Forecast::class.java)

        val duplicated = evaluateCashDraft(
            draft(
                flows = listOf(
                    CashFlowDraft(id = 3, date = "2026-02-10", amount = "1.00"),
                    CashFlowDraft(id = 3, date = "2026-03-10", amount = "2.00"),
                ),
            ),
        )
        assertThat(duplicated.outcome).isNull()
        assertThat(duplicated.errors).containsExactly("cashFlows")
    }

    @Test fun `negative reserve is not silently changed to zero`() {
        val result = evaluateCashDraft(draft(reserve = "-0.001"))
        assertThat(result.errors).containsExactly("reserve")
        assertThat(result.outcome).isNull()
    }

    @Test fun `JPY whole yen cash is exact without FX`() {
        val forecast = forecastOf(draft(currency = "JPY", openingCash = "100", reserve = "0",
            flows = listOf(CashFlowDraft(1, "2026-02-10", "1", CashFlowDirection.INFLOW))))
        assertThat(forecast.result.partitions.single().headline?.finalBalance).isEqualTo(Money.of(BigDecimal("101"), "JPY"))
    }

    @Test fun `signed opening cash remains a real opening shortfall`() {
        val partition = forecastOf(draft(currency = "KWD", openingCash = "-0.001", reserve = "0")).result.partitions.single()
        assertThat(partition.opening?.cash?.amount?.toPlainString()).isEqualTo("-0.001")
        assertThat(partition.headline?.firstNegativeAvailableDate).isEqualTo(partition.opening?.date)
    }

    @Test fun `five year horizon boundary is inclusive but not exceeded`() {
        assertThat(evaluateCashDraft(draft(openingDate = "2025-02-28", horizonEnd = "2030-02-28")).errors).isEmpty()
        assertThat(evaluateCashDraft(draft(openingDate = "2025-02-28", horizonEnd = "2030-03-01")).errors).containsExactly("horizonEnd")
    }

    @Test fun `unsupported minor unit precision is withheld and never rounded`() {
        for ((currency, value) in listOf("SAR" to "0.001", "JPY" to "0.1", "KWD" to "0.0001")) {
            val partition = forecastOf(draft(currency = currency, openingCash = value, reserve = "0")).result.partitions.single()
            assertThat(partition.status).isEqualTo(PartitionStatus.WITHHELD)
            assertThat(partition.opening).isNull()
            assertThat(partition.headline).isNull()
            assertThat(partition.path).isEmpty()
        }
    }

    /** Valid base draft: SAR, known signed opening cash, one-year horizon, attestations on. */
    private fun draft(
        currency: String = "SAR",
        openingDate: String = "2026-01-31",
        openingCash: String = "100.00",
        reserve: String = "",
        horizonEnd: String = "2027-01-31",
        scopeComplete: Boolean = true,
        anchorFresh: Boolean = true,
        flows: List<CashFlowDraft> = emptyList(),
    ): CashScenarioDraft = CashScenarioDraft(
        currency = currency,
        openingDate = openingDate,
        openingCash = openingCash,
        reserve = reserve,
        horizonEnd = horizonEnd,
        scopeComplete = scopeComplete,
        anchorFresh = anchorFresh,
        flows = flows,
    )

    private fun flowOn(date: String): CashFlowDraft =
        CashFlowDraft(id = 1, date = date, amount = "10.00")

    private fun forecastOf(draft: CashScenarioDraft): CashForecastOutcome.Forecast {
        val result = evaluateCashDraft(draft)
        assertThat(result.errors).isEmpty()
        assertThat(result.outcome).isInstanceOf(CashForecastOutcome.Forecast::class.java)
        return result.outcome as CashForecastOutcome.Forecast
    }
}
