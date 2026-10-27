package com.athar.core.domain.calc.cashforecast

import com.athar.core.domain.model.cashforecast.CashForecastOutcome
import com.athar.core.domain.model.cashforecast.CashForecastRejectionReason
import com.athar.core.domain.model.cashforecast.CashForecastRequest
import com.athar.core.domain.model.cashforecast.CashForecastResult
import com.athar.core.domain.model.cashforecast.PartitionStatus
import kotlinx.collections.immutable.toPersistentList

/** Pure conditional end-of-day paths. No account selection, clock, FX or live-balance claim. */
object CashForecastCalculator {
    fun forecast(request: CashForecastRequest): CashForecastOutcome = try {
        calculate(request)
    } catch (_: ArithmeticException) {
        CashForecastOutcome.Rejected(
            listOf(CashForecastRejectionReason.ARITHMETIC_UNSUPPORTED).toPersistentList(),
            emptyList<String>().toPersistentList(),
        )
    }

    private fun calculate(request: CashForecastRequest): CashForecastOutcome {
        val context = ForecastContext()
        CashForecastValidation.validate(request, context)?.let { return it }
        val facts = CashForecastIdentity.resolve(request, context)
        val cash = CashForecastSettlement.resolve(facts, context)
        val anchors = request.anchors.associateBy { it.currency }
        val partitions = context.currencies.map { currency ->
            CashForecastPartition.build(currency, anchors[currency], cash, facts.reservations, request, context)
        }.toPersistentList()
        return CashForecastOutcome.Forecast(
            CashForecastResult(
                partitions,
                context.disclosures.distinct().sortedBy { it.sortKey }.toPersistentList(),
                partitions.isNotEmpty() && partitions.all { it.status == PartitionStatus.CONFIDENT },
            ),
        )
    }
}