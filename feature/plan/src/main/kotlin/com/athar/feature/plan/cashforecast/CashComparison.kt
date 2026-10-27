package com.athar.feature.plan.cashforecast

import com.athar.core.common.money.Money
import com.athar.core.domain.model.cashforecast.CashForecastOutcome
import com.athar.core.domain.model.cashforecast.PartitionStatus
import com.athar.feature.plan.scenarios.ScenarioDraftResult
import kotlinx.datetime.LocalDate

/** One computed row of a saved-scenario comparison; money is the real recomputed outcome. */
internal data class CashComparisonRow(
    val id: String,
    val title: String,
    val rejected: Boolean = false,
    val status: PartitionStatus? = null,
    /** Confident headline only: lowest available balance and its dated position. */
    val minimum: Money? = null,
    val minimumDate: LocalDate? = null,
    /** Dated expected shortfall; never shown for partial knowledge. */
    val shortfall: Money? = null,
    val shortfallDate: LocalDate? = null,
    /** Partial-knowledge known end: the last dated known point, never a promise. */
    val knownEnd: Money? = null,
    val knownEndDate: LocalDate? = null,
    val flowCount: Int = 0,
)

internal data class CashComparisonEntry(
    val id: String,
    val title: String,
    val flowCount: Int,
    val result: ScenarioDraftResult<CashForecastOutcome>,
)

/**
 * Builds comparison rows from freshly recomputed outcomes. Headline figures follow the
 * same visibility rule as the single-scenario renderer: partial and withheld knowledge
 * contribute only their known dated end, never an invented minimum or promised balance.
 */
internal fun buildCashComparisonRows(entries: List<CashComparisonEntry>): List<CashComparisonRow> =
    entries.map { entry ->
        val outcome = entry.result.outcome
        when (outcome) {
            null -> CashComparisonRow(id = entry.id, title = entry.title, rejected = true)
            is CashForecastOutcome.Rejected ->
                CashComparisonRow(id = entry.id, title = entry.title, rejected = true)
            is CashForecastOutcome.Forecast -> {
                // A saved scenario has exactly one opening anchor, hence one currency partition.
                val partition = outcome.result.partitions.singleOrNull()
                val headline = partition?.let { cashDisplayHeadline(it) }
                val knownEnd = partition?.path?.lastOrNull()
                CashComparisonRow(
                    id = entry.id,
                    title = entry.title,
                    status = partition?.status,
                    minimum = headline?.minimumAvailable,
                    minimumDate = headline?.minimumAvailableDate,
                    shortfall = headline?.shortfall,
                    shortfallDate = headline?.firstNegativeAvailableDate,
                    knownEnd = knownEnd?.cash?.takeIf { partition.status != PartitionStatus.CONFIDENT },
                    knownEndDate = knownEnd?.date,
                    flowCount = entry.flowCount,
                )
            }
        }
    }
