package com.athar.core.domain.calc.portfolio

import com.athar.core.common.money.Money
import com.athar.core.common.result.Outcome
import com.athar.core.domain.model.portfolio.CashDividend
import com.athar.core.domain.model.portfolio.DividendDestination
import com.athar.core.domain.model.portfolio.DividendReinvestment
import com.athar.core.domain.model.portfolio.ExternalContribution
import com.athar.core.domain.model.portfolio.ExternalWithdrawal
import com.athar.core.domain.model.portfolio.LedgerRejection
import com.athar.core.domain.model.portfolio.PortfolioEvent
import com.athar.core.domain.model.portfolio.PortfolioLedger
import com.athar.core.domain.model.portfolio.PortfolioSecurity
import com.athar.core.domain.model.portfolio.PriceStatus
import com.athar.core.domain.model.portfolio.SecurityBuy
import com.athar.core.domain.model.portfolio.SecurityQuote
import com.athar.core.domain.model.portfolio.SecuritySell
import com.athar.core.domain.model.portfolio.PortfolioFee
import kotlinx.datetime.DatePeriod
import kotlinx.datetime.LocalDate
import kotlinx.datetime.plus
import java.math.BigDecimal
import java.math.MathContext
import java.math.RoundingMode

/**
 * Replays a validated [PortfolioLedger] up to [asOf] and produces full accounting:
 * cash, holdings with weighted-average cost, realized/unrealized gains, as-of
 * valuation from user-known dated quotes, and economic money gain.
 *
 * Cost-basis convention: **weighted average**. Cost is tracked exactly; on a partial
 * sale the released cost is `basis × sold/held` (exact when the ratio terminates,
 * DECIMAL64 otherwise) and is subtracted from the basis exactly, so released costs plus
 * the remaining basis always equal the accumulated basis — the money-gain reconciliation
 * in the tests holds by construction, not by tuning. Average cost *per unit* is a
 * reporting projection at 8dp HALF_EVEN.
 *
 * Explicitly out of scope (typed as remaining work, never faked): corporate actions
 * (splits/mergers — a split would need explicit user events), FX conversion (strict
 * single-currency boundary), time-weighted/annualized returns (they require boundary
 * valuations at every external flow).
 */
object PortfolioAccountingCalc {

    private val DIVISION_CONTEXT = MathContext.DECIMAL64
    private const val AVG_COST_SCALE = 8

    fun account(
        ledger: PortfolioLedger,
        quotes: List<SecurityQuote>,
        asOf: LocalDate,
        staleAfterDays: Int = 7,
    ): Outcome<PortfolioAccounting, LedgerRejection> {
        val base = ledger.baseCurrency

        // A negative freshness window would arbitrarily flip which quotes count as
        // stale; it is rejected outright instead of being interpreted.
        if (staleAfterDays < 0) {
            return Outcome.Failure(LedgerRejection.NegativeStaleAfterDays(staleAfterDays))
        }

        // Quote book: currency boundary + price sign + same-date ambiguity + per-security date order.
        val quotesBySecurity = sortedMapOf<String, MutableList<SecurityQuote>>()
        for (quote in quotes) {
            if (quote.unitPrice.currency != base) {
                return Outcome.Failure(
                    LedgerRejection.CrossCurrencyEvent(
                        eventId = "quote:${quote.securityId}@${quote.date}",
                        eventCurrencies = setOf(quote.unitPrice.currency),
                        baseCurrency = base,
                    ),
                )
            }
            // A negative price does not exist and is rejected. An explicitly supplied
            // zero is kept: it is a dated user-known observation that the security is
            // worthless — distinct from a missing quote, which leaves the holding
            // unpriced (never valued at zero, never synthesized).
            if (quote.unitPrice.amount.signum() < 0) {
                return Outcome.Failure(
                    LedgerRejection.NegativeQuotePrice(quote.securityId, quote.date, quote.unitPrice),
                )
            }
            val forSecurity = quotesBySecurity.getOrPut(quote.securityId) { mutableListOf() }
            if (forSecurity.any { it.date == quote.date }) {
                return Outcome.Failure(LedgerRejection.AmbiguousQuotes(quote.securityId, quote.date))
            }
            forSecurity += quote
        }
        quotesBySecurity.values.forEach { it.sortBy(SecurityQuote::date) }

        // Replay state.
        var cash = ledger.openingCash.amount
        val holdings = LinkedHashMap<String, Position>()
        val realizedGains = mutableListOf<RealizedGain>()
        var contributions = BigDecimal.ZERO
        var withdrawals = BigDecimal.ZERO
        var dividendsToCash = BigDecimal.ZERO
        var dividendsToExternal = BigDecimal.ZERO
        var dividendsReinvested = BigDecimal.ZERO
        var portfolioFees = BigDecimal.ZERO
        var tradeFees = BigDecimal.ZERO

        fun reject(rejection: LedgerRejection): Outcome.Failure<LedgerRejection> = Outcome.Failure(rejection)

        for (event in ledger.orderedEvents) {
            if (event.date > asOf) break // ordered: every remaining event is after the as-of date
            when (event) {
                is ExternalContribution -> {
                    cash = cash.add(event.amount.amount)
                    contributions = contributions.add(event.amount.amount)
                }

                is ExternalWithdrawal -> {
                    if (cash.compareTo(event.amount.amount) < 0) {
                        return reject(
                            LedgerRejection.InsufficientCash(event.eventId, event.amount, Money.of(cash, base)),
                        )
                    }
                    cash = cash.subtract(event.amount.amount)
                    withdrawals = withdrawals.add(event.amount.amount)
                }

                is SecurityBuy -> {
                    val totalCost = event.quantity.multiply(event.unitPrice.amount).add(event.fee.amount)
                    if (cash.compareTo(totalCost) < 0) {
                        return reject(
                            LedgerRejection.InsufficientCash(
                                event.eventId, Money.of(totalCost, base), Money.of(cash, base),
                            ),
                        )
                    }
                    cash = cash.subtract(totalCost)
                    tradeFees = tradeFees.add(event.fee.amount)
                    val position = holdings.getOrPut(event.securityRef) { Position() }
                    position.quantity = position.quantity.add(event.quantity)
                    position.basis = position.basis.add(totalCost)
                }

                is SecuritySell -> {
                    val held = holdings[event.securityRef]?.quantity ?: BigDecimal.ZERO
                    if (event.quantity.compareTo(held) > 0) {
                        return reject(
                            LedgerRejection.UnsupportedShortSale(
                                event.eventId, event.securityRef, event.quantity, held,
                            ),
                        )
                    }
                    val position = holdings.getValue(event.securityRef)
                    val releasedBasis = if (event.quantity.compareTo(held) == 0) {
                        position.basis // full liquidation: release the exact remaining basis
                    } else {
                        val soldRatio = event.quantity.divide(held, DIVISION_CONTEXT)
                        position.basis.multiply(soldRatio, DIVISION_CONTEXT)
                    }
                    val proceeds = event.quantity.multiply(event.unitPrice.amount).subtract(event.fee.amount)
                    cash = cash.add(proceeds)
                    tradeFees = tradeFees.add(event.fee.amount)
                    realizedGains += RealizedGain(
                        securityId = event.securityRef,
                        eventId = event.eventId,
                        date = event.date,
                        quantity = event.quantity,
                        proceeds = Money.of(proceeds, base),
                        costOfSoldUnits = Money.of(releasedBasis, base),
                        gain = Money.of(proceeds.subtract(releasedBasis), base),
                    )
                    position.quantity = held.subtract(event.quantity)
                    position.basis = position.basis.subtract(releasedBasis)
                    if (position.quantity.signum() == 0) holdings.remove(event.securityRef)
                }

                is CashDividend -> when (event.destination) {
                    DividendDestination.PORTFOLIO_CASH -> {
                        cash = cash.add(event.amount.amount)
                        dividendsToCash = dividendsToCash.add(event.amount.amount)
                    }

                    DividendDestination.EXTERNAL_ACCOUNT -> {
                        // Already outside the boundary — gain, not cash (no double count).
                        dividendsToExternal = dividendsToExternal.add(event.amount.amount)
                    }
                }

                is DividendReinvestment -> {
                    val position = holdings.getOrPut(event.securityRef) { Position() }
                    position.quantity = position.quantity.add(event.quantity)
                    position.basis = position.basis.add(event.amount.amount)
                    dividendsReinvested = dividendsReinvested.add(event.amount.amount)
                }

                is PortfolioFee -> {
                    if (cash.compareTo(event.amount.amount) < 0) {
                        return reject(
                            LedgerRejection.InsufficientCash(
                                event.eventId, event.amount, Money.of(cash, base),
                            ),
                        )
                    }
                    cash = cash.subtract(event.amount.amount)
                    portfolioFees = portfolioFees.add(event.amount.amount)
                }
            }
        }

        // As-of valuation: latest quote dated on or before asOf; missing → unavailable, never 0.
        val unpricedSecurityIds = mutableListOf<String>()
        val holdingValuations = mutableListOf<HoldingValuation>()
        var pricedValue = BigDecimal.ZERO
        val sortedHoldings = holdings.toSortedMap()
        for ((securityId, position) in sortedHoldings) {
            val quote = quotesBySecurity[securityId]?.lastOrNull { it.date <= asOf }
            val status: PriceStatus
            val marketValue: Money?
            if (quote == null) {
                status = PriceStatus.Unavailable
                marketValue = null
                unpricedSecurityIds += securityId
            } else {
                // kotlinx-datetime 0.6.1 LocalDate has plus(DatePeriod); negate for minus.
                val stale = quote.date < asOf.plus(DatePeriod(days = -staleAfterDays))
                status = PriceStatus.Priced(quote.unitPrice, quote.date, stale)
                marketValue = Money.of(position.quantity.multiply(quote.unitPrice.amount), base)
                pricedValue = pricedValue.add(marketValue.amount)
            }
            holdingValuations += HoldingValuation(securityId, position.quantity, status, marketValue)
        }

        val cashMoney = Money.of(cash, base)
        val pricedHoldingsValue = Money.of(pricedValue, base)
        val isPartial = unpricedSecurityIds.isNotEmpty()
        val valuation = PortfolioValuation(
            asOf = asOf,
            cash = cashMoney,
            pricedHoldingsValue = pricedHoldingsValue,
            total = if (isPartial) null else Money.of(cash.add(pricedValue), base),
            holdings = holdingValuations,
            unpricedSecurityIds = unpricedSecurityIds.toList(),
            isPartial = isPartial,
        )

        val unrealizedGains = holdingValuations.mapNotNull { holding ->
            val marketValue = holding.marketValue ?: return@mapNotNull null
            val basis = Money.of(sortedHoldings.getValue(holding.securityId).basis, base)
            UnrealizedGain(
                securityId = holding.securityId,
                quantity = holding.quantity,
                marketValue = marketValue,
                costBasis = basis,
                gain = Money.of(marketValue.amount.subtract(basis.amount), base),
            )
        }

        val holdingStates = sortedHoldings.map { (securityId, position) ->
            val averageCost = position.basis
                .divide(position.quantity, DIVISION_CONTEXT)
                .setScale(AVG_COST_SCALE, RoundingMode.HALF_EVEN)
            HoldingState(
                securityId = securityId,
                quantity = position.quantity,
                averageCostPerUnit = Money.of(averageCost, base),
                costBasis = Money.of(position.basis, base),
            )
        }

        // closing − openingCash − contributions + withdrawals + external dividends
        val moneyGainAmount = valuation.total?.let { total ->
            Money.of(
                total.amount
                    .subtract(ledger.openingCash.amount)
                    .subtract(contributions)
                    .add(withdrawals)
                    .add(dividendsToExternal),
                base,
            )
        }

        return Outcome.Success(
            PortfolioAccounting(
                asOf = asOf,
                baseCurrency = base,
                openingCash = ledger.openingCash,
                cash = cashMoney,
                holdings = holdingStates,
                securities = ledger.securities,
                valuation = valuation,
                realizedGains = realizedGains.toList(),
                unrealizedGains = unrealizedGains,
                contributions = Money.of(contributions, base),
                withdrawals = Money.of(withdrawals, base),
                dividendsToCash = Money.of(dividendsToCash, base),
                dividendsToExternal = Money.of(dividendsToExternal, base),
                dividendsReinvested = Money.of(dividendsReinvested, base),
                portfolioFees = Money.of(portfolioFees, base),
                tradeFees = Money.of(tradeFees, base),
                moneyGain = MoneyGain(
                    amount = moneyGainAmount,
                    unavailableBecause = unpricedSecurityIds.toList(),
                ),
            ),
        )
    }

    /** Mutable replay position for one security: exact quantity and exact cost basis. */
    private class Position {
        var quantity: BigDecimal = BigDecimal.ZERO
        var basis: BigDecimal = BigDecimal.ZERO
    }
}
