package com.athar.feature.plan.position

import java.math.BigDecimal
import kotlinx.datetime.LocalDate
import kotlinx.datetime.daysUntil

/**
 * Pure presentation totals over ONE saved dated-position document.
 *
 * Sums exact BigDecimal amounts per literal currency only — no cross-currency
 * conversion, no combined "wealth" figure, no owner-scope aggregation. The user
 * gets per-currency assets / liabilities / net plus the document's own
 * disclosures; unknown integration scopes are omitted, never fabricated.
 */
data class PositionCurrencyTotal(
    val currency: String,
    val assets: BigDecimal,
    val liabilities: BigDecimal,
    val net: BigDecimal,
)

data class PositionTotals(
    val asOfDate: String,
    /** Days between the declared as-of date and the viewing date (0 = today). */
    val ageDays: Long,
    /** One row per distinct declared currency, in first-appearance order. */
    val perCurrency: List<PositionCurrencyTotal>,
    val entryCount: Int,
)

internal object PositionTotalsCalc {

    fun compute(document: PositionDocument, today: LocalDate): PositionTotals {
        val asOf = PositionDocumentCodec.dateOrNull(document.asOfDate)
        val ageDays = if (asOf == null) -1L else asOf.daysUntil(today).toLong()
        val order = ArrayList<String>()
        val assets = HashMap<String, BigDecimal>()
        val liabilities = HashMap<String, BigDecimal>()
        document.entries.forEach { entry ->
            val amount = PositionDocumentCodec.decimalOrNull(entry.amount) ?: BigDecimal.ZERO
            if (entry.currency !in assets) {
                order.add(entry.currency)
                assets[entry.currency] = BigDecimal.ZERO
                liabilities[entry.currency] = BigDecimal.ZERO
            }
            when (entry.side) {
                PositionSide.ASSET -> assets[entry.currency] = assets.getValue(entry.currency).add(amount)
                PositionSide.LIABILITY ->
                    liabilities[entry.currency] = liabilities.getValue(entry.currency).add(amount)
            }
        }
        val rows = order.map { currency ->
            val a = assets.getValue(currency)
            val l = liabilities.getValue(currency)
            PositionCurrencyTotal(currency, a, l, a.subtract(l))
        }
        return PositionTotals(
            asOfDate = document.asOfDate,
            ageDays = ageDays,
            perCurrency = rows,
            entryCount = document.entries.size,
        )
    }
}
