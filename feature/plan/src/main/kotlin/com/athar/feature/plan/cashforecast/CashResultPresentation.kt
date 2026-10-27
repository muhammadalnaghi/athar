package com.athar.feature.plan.cashforecast

import com.athar.core.domain.model.cashforecast.CurrencyPartitionForecast
import com.athar.core.domain.model.cashforecast.DatedCashDay
import com.athar.core.domain.model.cashforecast.PartitionHeadline
import com.athar.core.domain.model.cashforecast.PartitionStatus

internal const val KnownPathPointLimit = 3

/** Partial/withheld knowledge never acquires a decision headline. */
internal fun cashDisplayHeadline(partition: CurrencyPartitionForecast): PartitionHeadline? =
    partition.headline.takeIf { partition.status == PartitionStatus.CONFIDENT }

/** Explicitly bounded prefix only; retain the original dated points and literal money. */
internal fun knownCashPathPrefix(partition: CurrencyPartitionForecast): List<DatedCashDay> =
    if (partition.status == PartitionStatus.WITHHELD) emptyList() else partition.path.take(KnownPathPointLimit)
