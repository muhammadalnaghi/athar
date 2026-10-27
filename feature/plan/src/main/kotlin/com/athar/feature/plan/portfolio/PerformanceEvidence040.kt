package com.athar.feature.plan.portfolio

import com.athar.core.domain.calc.portfolio.MoneyGain
import com.athar.core.domain.calc.portfolio.ReturnUndefinedReason
import com.athar.core.domain.calc.portfolio.TwrResult
import com.athar.core.domain.calc.portfolio.XirrResult
import kotlinx.datetime.LocalDate

/**
 * Flow state of one return-engine result, propagated exactly as the engine stored it —
 * never reinterpreted, never defaulted to a number. `Undefined` keeps the engine's
 * reason and the unpriced-security ids it reported so the UI can disclose them.
 */
sealed interface ReturnFlow040 {
    data object Defined : ReturnFlow040

    data class Undefined(
        val reason: ReturnUndefinedReason,
        val unpricedSecurityIds: List<String>,
    ) : ReturnFlow040
}

/**
 * State of the accounting money gain: available, unavailable (partial closing
 * valuation, with the ids the engine named), or no replay at all (empty document
 * or a rejected input document) — the honest empty state, not a zero.
 */
sealed interface MoneyGainFlow040 {
    data object Available : MoneyGainFlow040

    data class Unavailable(val unavailableBecause: List<String>) : MoneyGainFlow040

    data object NotReplayable : MoneyGainFlow040
}

/**
 * Pure, unit-testable evidence for the opened portfolio's performance results, built
 * ONLY from already-stored inputs: the typed `EventInput`/`QuoteInput` document rows,
 * the replay boundary (`asOf`) and the existing engine outputs. It counts inputs by
 * kind, derives the earliest→latest dated span of the stored literals, and carries
 * the flow state of each result. No money arithmetic happens here — counts, dates
 * and stored states only — so the UI can explain results without recomputing them.
 */
data class PerformanceEvidence040(
    val eventCountByKind: Map<PortfolioEntryKind, Int>,
    val quoteCount: Int,
    val earliestDate: LocalDate?,
    val latestDate: LocalDate?,
    val asOf: LocalDate?,
    val hasDatedInputs: Boolean,
    val xirr: ReturnFlow040,
    val twr: ReturnFlow040,
    val moneyGain: MoneyGainFlow040,
) {
    companion object {

        fun of(
            events: List<EventInput>,
            quotes: List<QuoteInput>,
            asOf: LocalDate?,
            xirr: XirrResult,
            twr: TwrResult,
            moneyGain: MoneyGain?,
            openingCashDate: String? = null,
        ): PerformanceEvidence040 {
            val counts = PortfolioEntryKind.entries
                .associateWith { kind -> events.count { it.kind == kind } }
                .filterValues { it > 0 }
            // Stored literals are strict ISO by the codec contract; parsing is still
            // guarded so a malformed stored row can never crash the evidence model —
            // it simply stays outside the derived span, never inside a fabricated one.
            val dates = (events.map { it.date } + quotes.map { it.date } + listOfNotNull(openingCashDate))
                .mapNotNull { raw -> runCatching { LocalDate.parse(raw) }.getOrNull() }
            val xirrFlow = when (xirr) {
                is XirrResult.Available -> ReturnFlow040.Defined
                is XirrResult.Undefined -> ReturnFlow040.Undefined(xirr.reason, xirr.unpricedSecurityIds)
            }
            val twrFlow = when (twr) {
                is TwrResult.Available -> ReturnFlow040.Defined
                is TwrResult.Undefined -> ReturnFlow040.Undefined(twr.reason, twr.unpricedSecurityIds)
            }
            val gainFlow = when {
                moneyGain == null -> MoneyGainFlow040.NotReplayable
                moneyGain.amount != null -> MoneyGainFlow040.Available
                else -> MoneyGainFlow040.Unavailable(moneyGain.unavailableBecause)
            }
            return PerformanceEvidence040(
                eventCountByKind = counts,
                quoteCount = quotes.size,
                earliestDate = dates.minOrNull(),
                latestDate = dates.maxOrNull(),
                asOf = asOf,
                hasDatedInputs = events.isNotEmpty() || quotes.isNotEmpty() || openingCashDate != null,
                xirr = xirrFlow,
                twr = twrFlow,
                moneyGain = gainFlow,
            )
        }
    }
}
