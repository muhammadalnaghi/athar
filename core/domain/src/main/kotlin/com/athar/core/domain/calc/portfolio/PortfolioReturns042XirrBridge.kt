package com.athar.core.domain.calc.portfolio

import com.athar.core.common.money.Money
import com.athar.core.common.result.Outcome
import com.athar.core.domain.calc.performance.XirrCalc
import com.athar.core.domain.model.performance.DatedCashFlow
import com.athar.core.domain.model.performance.DatedValuation
import com.athar.core.domain.model.performance.XirrInput
import com.athar.core.domain.model.performance.XirrRejection
import com.athar.core.domain.model.performance.XirrResult as EngineXirrResult
import com.athar.core.domain.model.portfolio.CashDividend
import com.athar.core.domain.model.portfolio.DividendDestination
import com.athar.core.domain.model.portfolio.ExternalContribution
import com.athar.core.domain.model.portfolio.ExternalWithdrawal
import com.athar.core.domain.model.portfolio.PortfolioLedger
import com.athar.core.domain.model.portfolio.SecurityQuote
import java.math.BigDecimal
import java.math.RoundingMode
import kotlinx.datetime.LocalDate

/**
 * WM042 bridge: maps the portfolio ledger document onto the reviewed
 * [XirrCalc] engine (`calc.performance`, spec WM028-08) and translates its typed
 * outcomes onto the facade's result types. This file owns NO numerical method —
 * no NPV, no root scan, no bisection; every rate is computed by the engine.
 *
 * Sign conventions (preserved from the facade contract, never re-signed):
 * contributions are money IN (negative), withdrawals and dividends paid to an
 * `EXTERNAL_ACCOUNT` are money OUT (positive). A nonzero undated opening balance
 * withholds XIRR: capital must not disappear from the series or acquire an invented
 * first-trade date. Explicitly dated opening capital enters the reviewed engine
 * exactly once as its opening valuation, alongside later external flows.
 * Currency is carried literally in
 * [com.athar.core.common.money.Money]; nothing is converted or relabeled.
 */
internal object PortfolioReturns042XirrBridge {

    private const val PERCENT_SCALE = 8

    /** Engine outcome → facade outcome; ambiguity is honestly undefined, never a picked root. */
    fun translate(result: EngineXirrResult): XirrResult = when (result) {
        is EngineXirrResult.Rate -> XirrResult.Available(percent(result.annualRateRatio))
        is EngineXirrResult.Ambiguous -> XirrResult.Undefined(ReturnUndefinedReason.NO_CONVERGENCE)
        is EngineXirrResult.Rejected -> when (result.reason) {
            XirrRejection.EMPTY_INPUT -> XirrResult.Undefined(ReturnUndefinedReason.NO_DATED_FLOWS)
            else -> XirrResult.Undefined(ReturnUndefinedReason.NO_CONVERGENCE)
        }
    }

    fun xirr(ledger: PortfolioLedger, quotes: List<SecurityQuote>, asOf: LocalDate): XirrResult {
        val openingDate = ledger.openingCashDate
        val hasOpeningCapital = ledger.openingCash.amount.signum() != 0
        if (openingDate != null && hasOpeningCapital && asOf < openingDate) {
            return XirrResult.Undefined(ReturnUndefinedReason.AS_OF_BEFORE_OPENING_DATE)
        }
        val accounting = when (val outcome = PortfolioAccountingCalc.account(ledger, quotes, asOf)) {
            is Outcome.Success -> outcome.value
            is Outcome.Failure ->
                return XirrResult.Undefined(ReturnUndefinedReason.MISSING_BOUNDARY_VALUATION)
        }
        val terminal = accounting.valuation.total
            ?: return XirrResult.Undefined(
                ReturnUndefinedReason.MISSING_BOUNDARY_VALUATION,
                accounting.valuation.unpricedSecurityIds,
            )

        // Legacy documents do not date their capital. Do not omit that money or
        // invent a first-trade date; an explicit date uses the engine valuation input.
        if (hasOpeningCapital && openingDate == null) {
            return XirrResult.Undefined(ReturnUndefinedReason.MISSING_BOUNDARY_VALUATION)
        }
        val openingValuation = if (hasOpeningCapital) DatedValuation(openingDate!!, ledger.openingCash) else null
        val flows = externalFlows(ledger, asOf)
        if (flows.isEmpty() && openingValuation == null) {
            return XirrResult.Undefined(ReturnUndefinedReason.NO_DATED_FLOWS)
        }

        val engineResult = XirrCalc.annualRate(
            XirrInput(
                flows = flows.map { (id, date, money) -> DatedCashFlow(id, date, money) },
                openingValuation = openingValuation,
                terminalValuation = DatedValuation(asOf, terminal),
            ),
        )
        return translate(engineResult)
    }

    /** Signed dated external flows up to [asOf]: contributions negative, withdrawals and external dividends positive. */
    private fun externalFlows(
        ledger: PortfolioLedger,
        asOf: LocalDate,
    ): List<Triple<String, LocalDate, Money>> = ledger.orderedEvents
        .takeWhile { it.date <= asOf }
        .mapNotNull { event ->
            when (event) {
                is ExternalContribution -> Triple(event.eventId, event.date, -event.amount)
                is ExternalWithdrawal -> Triple(event.eventId, event.date, event.amount)
                is CashDividend ->
                    if (event.destination == DividendDestination.EXTERNAL_ACCOUNT) {
                        Triple(event.eventId, event.date, event.amount)
                    } else {
                        null
                    }
                else -> null
            }
        }

    private fun percent(fraction: BigDecimal): BigDecimal =
        fraction.multiply(BigDecimal(100)).setScale(PERCENT_SCALE, RoundingMode.HALF_EVEN)
}
