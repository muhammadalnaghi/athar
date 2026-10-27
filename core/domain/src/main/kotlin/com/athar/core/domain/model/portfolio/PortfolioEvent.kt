package com.athar.core.domain.model.portfolio

import com.athar.core.common.money.Money
import kotlinx.datetime.LocalDate
import java.math.BigDecimal

/**
 * A dated, typed portfolio ledger event.
 *
 * Events are totally ordered by [date] then [sequence]; [eventId] is a stable
 * user-assigned identifier (never regenerated, safe to persist). This ledger is
 * **single-currency**: every monetary field of every event must be denominated in the
 * ledger's base currency until explicit FX quotes exist — mixed values are rejected,
 * never relabeled.
 */
sealed interface PortfolioEvent {
    val eventId: String
    val date: LocalDate
    val sequence: Int

    /** Every currency used by this event's monetary fields (for the currency boundary check). */
    val currencies: Set<String>

    /** The security this event touches, or null for portfolio-level cash events. */
    val securityRef: String? get() = null
}

/**
 * Where a cash dividend lands.
 *
 * - [PORTFOLIO_CASH]: the money stays inside the portfolio boundary and is added to
 *   portfolio cash (counted once, in the closing valuation).
 * - [EXTERNAL_ACCOUNT]: the money left the portfolio (e.g. paid to a bank account tracked
 *   elsewhere). It is *not* added to portfolio cash — counting it there too would double
 *   count — but it still counts as realized economic money gain.
 */
enum class DividendDestination { PORTFOLIO_CASH, EXTERNAL_ACCOUNT }

/** External money entering the portfolio boundary (adds cash; not economic gain). */
data class ExternalContribution(
    override val eventId: String,
    override val date: LocalDate,
    override val sequence: Int,
    val amount: Money,
    val note: String = "",
) : PortfolioEvent {
    override val currencies: Set<String> get() = setOf(amount.currency)
}

/** External money leaving the portfolio boundary (removes cash; not an economic loss). */
data class ExternalWithdrawal(
    override val eventId: String,
    override val date: LocalDate,
    override val sequence: Int,
    val amount: Money,
    val note: String = "",
) : PortfolioEvent {
    override val currencies: Set<String> get() = setOf(amount.currency)
}

/** Buy [quantity] units at [unitPrice]; [fee] adds to the cost basis. Total cash out: quantity×price + fee. */
data class SecurityBuy(
    override val eventId: String,
    override val date: LocalDate,
    override val sequence: Int,
    override val securityRef: String,
    val quantity: BigDecimal,
    val unitPrice: Money,
    val fee: Money,
) : PortfolioEvent {
    override val currencies: Set<String> get() = setOf(unitPrice.currency, fee.currency)
}

/** Sell [quantity] units at [unitPrice]; [fee] reduces proceeds. Selling more than held is unsupported (no shorting). */
data class SecuritySell(
    override val eventId: String,
    override val date: LocalDate,
    override val sequence: Int,
    override val securityRef: String,
    val quantity: BigDecimal,
    val unitPrice: Money,
    val fee: Money,
) : PortfolioEvent {
    override val currencies: Set<String> get() = setOf(unitPrice.currency, fee.currency)
}

/**
 * A cash distribution from [securityRef]. See [DividendDestination] for the
 * inside-vs-outside-the-boundary rule that prevents double counting.
 */
data class CashDividend(
    override val eventId: String,
    override val date: LocalDate,
    override val sequence: Int,
    override val securityRef: String,
    val amount: Money,
    val destination: DividendDestination,
) : PortfolioEvent {
    override val currencies: Set<String> get() = setOf(amount.currency)
}

/**
 * A distribution of [amount] reinvested into [quantity] additional units of [securityRef]
 * at [unitPrice]. No cash moves: the units (and their cost) appear directly in the holding.
 */
data class DividendReinvestment(
    override val eventId: String,
    override val date: LocalDate,
    override val sequence: Int,
    override val securityRef: String,
    val amount: Money,
    val unitPrice: Money,
    val quantity: BigDecimal,
) : PortfolioEvent {
    override val currencies: Set<String> get() = setOf(amount.currency, unitPrice.currency)
}

/** A fee charged against portfolio cash (custody, management, …). Trade fees live on buy/sell events. */
data class PortfolioFee(
    override val eventId: String,
    override val date: LocalDate,
    override val sequence: Int,
    val amount: Money,
    val reason: String = "",
) : PortfolioEvent {
    override val currencies: Set<String> get() = setOf(amount.currency)
}
