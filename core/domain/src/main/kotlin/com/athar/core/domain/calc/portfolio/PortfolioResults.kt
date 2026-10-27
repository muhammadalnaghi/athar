package com.athar.core.domain.calc.portfolio

import com.athar.core.common.money.Money
import com.athar.core.domain.model.portfolio.PriceStatus
import kotlinx.datetime.LocalDate
import java.math.BigDecimal

/** A holding's quantity and weighted-average cost state after replaying the ledger. */
data class HoldingState(
    val securityId: String,
    val quantity: BigDecimal,
    /** Average cost per unit, reported at 8dp HALF_EVEN; internal cost math is exact. */
    val averageCostPerUnit: Money,
    val costBasis: Money,
)

/** Gain realized by one sell event, measured against weighted-average cost. */
data class RealizedGain(
    val securityId: String,
    val eventId: String,
    val date: LocalDate,
    val quantity: BigDecimal,
    /** Sale quantity × unit price − sell fee. */
    val proceeds: Money,
    /** Weighted-average cost released by this sale (exact; conserved against the basis). */
    val costOfSoldUnits: Money,
    val gain: Money,
)

/** Mark-to-market gain of a priced holding at the as-of quote. */
data class UnrealizedGain(
    val securityId: String,
    val quantity: BigDecimal,
    val marketValue: Money,
    val costBasis: Money,
    val gain: Money,
)

/** One holding's as-of valuation; [marketValue] is null exactly when [status] is [PriceStatus.Unavailable]. */
data class HoldingValuation(
    val securityId: String,
    val quantity: BigDecimal,
    val status: PriceStatus,
    val marketValue: Money?,
)

/**
 * As-of portfolio valuation = portfolio cash + priced holdings.
 *
 * Holdings without a usable dated quote are listed in [unpricedSecurityIds] and are
 * **never valued at zero**: [total] is null (and [isPartial] true) rather than a
 * fabricated complete number.
 */
data class PortfolioValuation(
    val asOf: LocalDate,
    val cash: Money,
    val pricedHoldingsValue: Money,
    /** cash + priced holdings, or null when any holding is unpriced. */
    val total: Money?,
    val holdings: List<HoldingValuation>,
    val unpricedSecurityIds: List<String>,
    val isPartial: Boolean,
)

/**
 * Economic money gain relative to the portfolio's opening cash and external flows:
 * `closing − openingCash − contributions + withdrawals + dividends paid to external
 * accounts`.
 *
 * This is **not** IRR, **not** a time-weighted return, and **not** annualized — those
 * need genuine boundary valuations at every external flow and are explicitly remaining
 * work. [amount] is null (with [unavailableBecause] naming the unpriced securities)
 * when the closing valuation is partial.
 */
data class MoneyGain(
    val amount: Money?,
    val unavailableBecause: List<String>,
)

/**
 * Full accounting result of replaying a portfolio ledger up to an as-of date.
 *
 * Boundary recap: the portfolio is its cash plus its holdings. Contributions add cash;
 * withdrawals and portfolio fees remove it; a cash dividend with the
 * `EXTERNAL_ACCOUNT` destination has already left the boundary (counted as gain, not
 * as cash, to avoid double counting). Trade fees are embedded in cost bases and
 * proceeds (thus in realized/unrealized gains); [portfolioFees] are separate.
 */
data class PortfolioAccounting(
    val asOf: LocalDate,
    val baseCurrency: String,
    val openingCash: Money,
    val cash: Money,
    val holdings: List<HoldingState>,
    val securities: Map<String, com.athar.core.domain.model.portfolio.PortfolioSecurity>,
    val valuation: PortfolioValuation,
    val realizedGains: List<RealizedGain>,
    val unrealizedGains: List<UnrealizedGain>,
    val contributions: Money,
    val withdrawals: Money,
    val dividendsToCash: Money,
    val dividendsToExternal: Money,
    val dividendsReinvested: Money,
    /** Fees charged against portfolio cash (excludes trade fees). */
    val portfolioFees: Money,
    /** Buy/sell fees (embedded in bases and proceeds, hence in gains). */
    val tradeFees: Money,
    val moneyGain: MoneyGain,
)
