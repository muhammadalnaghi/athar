package com.athar.feature.plan.investments

import com.athar.core.common.money.Money
import com.athar.core.domain.calc.InvestmentsCalc
import com.athar.core.domain.model.InvestmentContribution
import com.athar.core.domain.model.InvestmentPool
import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.Test
import java.math.BigDecimal

/**
 * Synthetic-record tests for the family-pool breakdown adapter. Only the fields the
 * real `InvestmentPool`/`InvestmentContribution` records actually carry are used —
 * no dates, prices or valuations exist on those records and none are invented here.
 */
class InvestmentsBreakdownTest {

    private val pool = InvestmentPool(
        id = "p1",
        name = "Marble fund",
        period = "3 months",
        totalReturn = Money.of("100.00"),
    )

    private fun contribution(id: String, owner: String, amount: String, currency: String = "SAR") =
        InvestmentContribution(id = id, poolId = pool.id, ownerName = owner, amount = Money.of(amount, currency))

    @Test
    fun `single-currency shares match the accepted InvestmentsCalc engine`() {
        val breakdown = InvestmentsBreakdown.fromRecords(
            pool,
            listOf(contribution("c1", "Ammar", "300.00"), contribution("c2", "Sara", "100.00")),
        )

        val ammar = breakdown.contributors.first { it.id == "c1" }
        val sara = breakdown.contributors.first { it.id == "c2" }
        val expectedAmmarShare = InvestmentsCalc.shareReturn(
            pool.totalReturn,
            InvestmentsCalc.sharePercent(Money.of("300.00"), Money.of("400.00")),
        )
        assertThat(ammar.sharePercent).isWithin(1e-9).of(75.0)
        assertThat(ammar.shareOfDeclaredReturn).isEqualTo(expectedAmmarShare)
        assertThat(ammar.netHolding).isEqualTo(Money.of("300.00").plus(expectedAmmarShare))
        assertThat(sara.sharePercent).isWithin(1e-9).of(25.0)
        assertThat(sara.shareOfDeclaredReturn).isEqualTo(Money.of("25.00"))
    }

    @Test
    fun `share of declared return is rounded to 2dp banker's by the engine`() {
        val breakdown = InvestmentsBreakdown.fromRecords(
            pool,
            listOf(
                contribution("c1", "Ammar", "1.00"),
                contribution("c2", "Sara", "1.00"),
                contribution("c3", "Layla", "1.00"),
            ),
        )

        breakdown.contributors.forEach {
            // 100.00 × (1/3) = 33.333… → HALF_EVEN 2dp = 33.33 for all three.
            assertThat(it.shareOfDeclaredReturn).isEqualTo(Money.of("33.33"))
        }
        assertThat(breakdown.roundingRemainder!!.amount.compareTo(BigDecimal("0.01"))).isEqualTo(0)
    }

    @Test
    fun `zero corpus yields zero shares, not a division failure`() {
        val zeroContribution = contribution("c1", "Ammar", "0.00")
        val breakdown = InvestmentsBreakdown.fromRecords(pool, listOf(zeroContribution))

        val share = breakdown.contributors.single()
        assertThat(share.sharePercent).isWithin(1e-9).of(0.0)
        assertThat(share.compositionFraction).isNull()
        assertThat(share.shareOfDeclaredReturn).isNull()
        assertThat(breakdown.allocationAvailable).isFalse()
    }

    @Test
    fun `empty pool keeps a zero corpus group in the pool currency`() {
        val breakdown = InvestmentsBreakdown.fromRecords(pool, emptyList())

        assertThat(breakdown.contributionCount).isEqualTo(0)
        assertThat(breakdown.contributors).isEmpty()
        assertThat(breakdown.mixedCurrency).isFalse()
        val group = breakdown.currencyGroups.single()
        assertThat(group.currency).isEqualTo("SAR")
        assertThat(group.corpus.amount.compareTo(BigDecimal("0"))).isEqualTo(0)
        assertThat(group.isPoolReturnCurrency).isTrue()
    }

    @Test
    fun `mixed currencies are partitioned, never merged, and never crash`() {
        val breakdown = InvestmentsBreakdown.fromRecords(
            pool,
            listOf(
                contribution("c1", "Ammar", "300.00"),
                contribution("c2", "Sara", "100.00"),
                contribution("c3", "Omar", "50.00", currency = "USD"),
            ),
        )

        assertThat(breakdown.mixedCurrency).isTrue()
        val sar = breakdown.currencyGroups.first { it.currency == "SAR" }
        val usd = breakdown.currencyGroups.first { it.currency == "USD" }
        assertThat(sar.corpus.amount.compareTo(BigDecimal("400"))).isEqualTo(0)
        assertThat(sar.contributionCount).isEqualTo(2)
        assertThat(usd.corpus.amount.compareTo(BigDecimal("50"))).isEqualTo(0)
        assertThat(usd.contributionCount).isEqualTo(1)
        // No cross-currency total is ever produced.
        assertThat(breakdown.currencyGroups).hasSize(2)
    }

    @Test
    fun `foreign-currency contribution gets no share of a foreign-currency return`() {
        val breakdown = InvestmentsBreakdown.fromRecords(
            pool,
            listOf(
                contribution("c1", "Ammar", "300.00"),
                contribution("c2", "Omar", "50.00", currency = "USD"),
            ),
        )

        val omar = breakdown.contributors.first { it.id == "c2" }
        assertThat(omar.shareOfDeclaredReturn).isNull()
        assertThat(omar.netHolding).isNull()
        val ammar = breakdown.contributors.first { it.id == "c1" }
        // The SAR subset is not the whole mixed-currency pool. Neither owner gets an allocation.
        assertThat(ammar.shareOfDeclaredReturn).isNull()
        assertThat(ammar.netHolding).isNull()
        assertThat(breakdown.allocationAvailable).isFalse()
    }

    @Test
    fun `contributions-only in a foreign currency still expose the pool-currency base`() {
        val breakdown = InvestmentsBreakdown.fromRecords(
            pool,
            listOf(contribution("c1", "Omar", "50.00", currency = "USD")),
        )

        assertThat(breakdown.mixedCurrency).isTrue()
        val sar = breakdown.currencyGroups.single { it.isPoolReturnCurrency }
        assertThat(sar.currency).isEqualTo("SAR")
        assertThat(sar.contributionCount).isEqualTo(0)
    }

    @Test
    fun `ordering is deterministic even though DAO order is not`() {
        val breakdown = InvestmentsBreakdown.fromRecords(
            pool,
            listOf(
                contribution("c3", "Layla", "1.00"),
                contribution("c1", "Ammar", "1.00"),
                contribution("c2", "Sara", "1.00", currency = "USD"),
            ),
        )
        val multi = InvestmentsBreakdown.fromRecords(
            mapOf(
                pool to emptyList(),
                pool.copy(id = "p0", name = "Marble fund") to emptyList(),
                pool.copy(id = "p2", name = "Aqiq fund") to emptyList(),
            ),
        )

        assertThat(breakdown.contributors.map { it.name }).containsExactly("Ammar", "Layla", "Sara").inOrder()
        assertThat(breakdown.currencyGroups.map { it.currency }).containsExactly("SAR", "USD").inOrder()
        assertThat(multi.map { it.poolId }).containsExactly("p2", "p0", "p1").inOrder()
    }

    @Test
    fun `every metric these records cannot supply is explicitly listed, in order`() {
        assertThat(InvestmentsBreakdown.unavailableMetrics).containsExactly(
            PoolMetricKey.CONTRIBUTION_DATES,
            PoolMetricKey.TRADE_DATES,
            PoolMetricKey.UNIT_PRICES,
            PoolMetricKey.MARKET_VALUATIONS,
            PoolMetricKey.FEES,
            PoolMetricKey.XIRR,
            PoolMetricKey.TWR,
            PoolMetricKey.EXPOSURE_ALLOCATION,
        ).inOrder()
    }

    @Test
    fun `evidence records counts and owner names`() {
        val breakdown = InvestmentsBreakdown.fromRecords(
            pool,
            listOf(contribution("c1", "Ammar", "300.00"), contribution("c2", "Sara", "100.00")),
        )

        assertThat(breakdown.contributionCount).isEqualTo(2)
        val sar = breakdown.currencyGroups.single()
        assertThat(sar.ownerNames).containsExactly("Ammar", "Sara").inOrder()
        assertThat(sar.isPoolReturnCurrency).isTrue()
    }

    @Test
    fun `headline corpus and breakdown corpus come from the same records`() {
        val contributions = listOf(
            contribution("c1", "Ammar", "300.00"),
            contribution("c2", "Sara", "400.00"),
        )
        val breakdown = InvestmentsBreakdown.fromRecords(pool, contributions)
        val headlineCorpus = breakdown.currencyGroups
            .first { it.isPoolReturnCurrency }.corpus
        val recordSum = contributions.fold(Money.zero("SAR")) { acc, c -> acc + c.amount }

        assertThat(headlineCorpus.amount.compareTo(recordSum.amount)).isEqualTo(0)
    }

    @Test
    fun `empty factory equals fromRecords with no contributions`() {
        assertThat(PoolBreakdown.empty(pool)).isEqualTo(InvestmentsBreakdown.fromRecords(pool, emptyList()))
    }
}
