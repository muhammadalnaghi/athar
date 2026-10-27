package com.athar.feature.plan.investments

import com.athar.core.common.money.Money
import com.athar.core.domain.calc.InvestmentsCalc
import com.athar.core.domain.model.InvestmentContribution
import com.athar.core.domain.model.InvestmentPool
import java.math.BigDecimal
import java.util.Currency
import kotlinx.collections.immutable.toImmutableList

/** Undated family records cannot establish these market/performance facts. */
enum class PoolMetricKey {
    CONTRIBUTION_DATES, TRADE_DATES, UNIT_PRICES, MARKET_VALUATIONS,
    FEES, XIRR, TWR, EXPOSURE_ALLOCATION,
}

data class CorpusByCurrency(
    val currency: String,
    val corpus: Money,
    val contributionCount: Int,
    val ownerNames: List<String>,
    val isPoolReturnCurrency: Boolean,
)

/** Contribution composition inside one currency is not whole-pool ownership. */
data class ContributorShare(
    val id: String,
    val name: String,
    val amount: Money,
    val compositionFraction: BigDecimal?,
    val shareOfDeclaredReturn: Money?,
    val netHolding: Money?,
) {
    /** Dimensionless display compatibility only; never feeds a monetary calculation. */
    val sharePercent: Double get() = compositionFraction?.movePointRight(2)?.toDouble() ?: 0.0
}

data class PoolBreakdown(
    val poolId: String,
    val currencyGroups: List<CorpusByCurrency>,
    val contributors: List<ContributorShare>,
    val contributionCount: Int,
    val mixedCurrency: Boolean,
    val allocationAvailable: Boolean,
    /** Explicitly reconciles independently rounded shares; null when allocation is unsupported. */
    val roundingRemainder: Money?,
) {
    companion object {
        fun empty(pool: InvestmentPool): PoolBreakdown = InvestmentsBreakdown.fromRecords(pool, emptyList())
    }
}

object InvestmentsBreakdown {
    val unavailableMetrics: List<PoolMetricKey> = PoolMetricKey.entries.toImmutableList()

    fun fromRecords(pools: Map<InvestmentPool, List<InvestmentContribution>>): List<PoolBreakdown> =
        pools.entries.sortedWith(compareBy({ it.key.name }, { it.key.id }))
            .map { fromRecords(it.key, it.value) }.toImmutableList()

    fun fromRecords(pool: InvestmentPool, contributions: List<InvestmentContribution>): PoolBreakdown {
        val byCurrency = contributions.groupBy { it.amount.currency }
        val currencies = (byCurrency.keys + pool.totalReturn.currency).sorted()
        val groups = currencies.map { currency ->
            val records = byCurrency[currency].orEmpty().sortedWith(compareBy({ it.ownerName }, { it.id }))
            CorpusByCurrency(
                currency = currency,
                corpus = records.fold(Money.zero(currency)) { sum, row -> sum + row.amount },
                contributionCount = records.size,
                ownerNames = records.map { it.ownerName }.toImmutableList(),
                isPoolReturnCurrency = currency == pool.totalReturn.currency,
            )
        }
        val groupsByCurrency = groups.associateBy { it.currency }
        val corpus = groupsByCurrency.getValue(pool.totalReturn.currency).corpus
        val supportedCurrency = runCatching {
            Currency.getInstance(pool.totalReturn.currency).defaultFractionDigits >= 0
        }.getOrDefault(false)
        val allocationAvailable = supportedCurrency && corpus.isPositive() &&
            contributions.all {
                it.poolId == pool.id && it.amount.currency == pool.totalReturn.currency && !it.amount.isNegative()
            } && contributions.map { it.id }.distinct().size == contributions.size
        val rows = contributions.sortedWith(compareBy({ it.ownerName }, { it.id })).map { record ->
            val groupRecords = byCurrency.getValue(record.amount.currency)
            val groupCorpus = groupsByCurrency.getValue(record.amount.currency).corpus
            val fraction = if (groupCorpus.isPositive() && groupRecords.none { it.amount.isNegative() }) {
                InvestmentsCalc.shareFraction(record.amount, groupCorpus)
            } else null
            // A matching currency subset is NOT the denominator for the whole pool.
            val shareReturn = if (allocationAvailable) {
                InvestmentsCalc.shareReturn(pool.totalReturn, record.amount, corpus)
            } else null
            ContributorShare(
                id = record.id,
                name = record.ownerName,
                amount = record.amount,
                compositionFraction = fraction,
                shareOfDeclaredReturn = shareReturn,
                netHolding = shareReturn?.let { record.amount + it },
            )
        }
        val remainder = if (allocationAvailable) {
            pool.totalReturn - rows.fold(Money.zero(pool.totalReturn.currency)) { sum, row ->
                sum + requireNotNull(row.shareOfDeclaredReturn)
            }
        } else null
        return PoolBreakdown(
            poolId = pool.id,
            currencyGroups = groups.toImmutableList(),
            contributors = rows.toImmutableList(),
            contributionCount = contributions.size,
            mixedCurrency = currencies.size > 1,
            allocationAvailable = allocationAvailable,
            roundingRemainder = remainder,
        )
    }
}
