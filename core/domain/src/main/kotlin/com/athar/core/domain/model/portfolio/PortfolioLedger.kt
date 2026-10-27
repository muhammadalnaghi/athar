package com.athar.core.domain.model.portfolio

import com.athar.core.common.money.Money
import com.athar.core.common.result.Outcome
import kotlinx.datetime.LocalDate
import java.math.BigDecimal
import java.math.RoundingMode

/**
 * A validated, immutable portfolio ledger: known securities plus dated events in
 * explicit (date, sequence) order, all in a single base currency.
 *
 * Structural validation (ids, ordering ambiguity, currency boundary, positivity,
 * known securities) happens in [of]. *Economic* feasibility (short sales, cash
 * sufficiency) is validated when the ledger is replayed by
 * `com.athar.core.domain.calc.portfolio.PortfolioAccountingCalc`, because it depends
 * on event order and as-of date.
 */
class PortfolioLedger private constructor(
    val baseCurrency: String,
    val openingCash: Money,
    /** Explicit capital date, or null when no opening-date evidence was supplied. */
    val openingCashDate: LocalDate?,
    val securities: Map<String, PortfolioSecurity>,
    /** Events sorted by (date, sequence) ascending — the authoritative replay order. */
    val orderedEvents: List<PortfolioEvent>,
) {
    companion object {
        /**
         * Validates and orders [events] against [securities] and the single-currency
         * [baseCurrency] boundary. Returns a typed [LedgerRejection] instead of throwing.
         */
        fun of(
            baseCurrency: String,
            openingCash: Money,
            securities: List<PortfolioSecurity>,
            events: List<PortfolioEvent>,
            openingCashDate: LocalDate? = null,
        ): Outcome<PortfolioLedger, LedgerRejection> {
            if (openingCash.currency != baseCurrency) {
                return Outcome.Failure(
                    LedgerRejection.CrossCurrencyEvent(
                        eventId = "openingCash",
                        eventCurrencies = setOf(openingCash.currency),
                        baseCurrency = baseCurrency,
                    ),
                )
            }
            // Synthetic negative cash is unsupported at the boundary too, not just mid-replay.
            if (openingCash.amount.signum() < 0) {
                return Outcome.Failure(LedgerRejection.NonPositiveAmount("openingCash", openingCash))
            }

            // Security identity is validated before the id→security map is built:
            // associateBy alone would silently let a duplicate id win last, and a blank
            // id could never be referenced unambiguously. Supplied non-blank ids are
            // kept exactly — no trimming or normalization "repairs" them.
            val seenSecurityIds = HashSet<String>()
            for (security in securities) {
                if (security.securityId.isBlank()) {
                    return Outcome.Failure(LedgerRejection.InvalidBlankSecurityId(security.securityId))
                }
                if (!seenSecurityIds.add(security.securityId)) {
                    return Outcome.Failure(LedgerRejection.DuplicateSecurityId(security.securityId))
                }
            }

            val knownSecurities = securities.associateBy { it.securityId }
            val seenIds = HashSet<String>()
            val seenOrderKeys = HashSet<Pair<LocalDate, Int>>()

            for (event in events) {
                if (event.eventId.isBlank()) {
                    return Outcome.Failure(LedgerRejection.InvalidBlankEventId(event.eventId))
                }
                if (!seenIds.add(event.eventId)) {
                    return Outcome.Failure(LedgerRejection.DuplicateEventId(event.eventId))
                }
                val orderKey = event.date to event.sequence
                if (!seenOrderKeys.add(orderKey)) {
                    return Outcome.Failure(LedgerRejection.AmbiguousOrdering(event.date, event.sequence))
                }
                val foreignCurrencies = event.currencies.filter { it != baseCurrency }
                if (foreignCurrencies.isNotEmpty()) {
                    return Outcome.Failure(
                        LedgerRejection.CrossCurrencyEvent(
                            eventId = event.eventId,
                            eventCurrencies = foreignCurrencies.toSet(),
                            baseCurrency = baseCurrency,
                        ),
                    )
                }
                event.securityRef?.let { ref ->
                    if (ref !in knownSecurities) {
                        return Outcome.Failure(LedgerRejection.UnknownSecurity(event.eventId, ref))
                    }
                }
                validatePositivity(event)?.let { return Outcome.Failure(it) }
            }

            return Outcome.Success(
                PortfolioLedger(
                    baseCurrency = baseCurrency,
                    openingCash = openingCash,
                    openingCashDate = openingCashDate,
                    securities = knownSecurities,
                    orderedEvents = events.sortedWith(
                        compareBy({ it.date }, { it.sequence }),
                    ),
                ),
            )
        }

        private fun validatePositivity(event: PortfolioEvent): LedgerRejection? = when (event) {
            is ExternalContribution -> requirePositiveAmount(event.eventId, event.amount)
            is ExternalWithdrawal -> requirePositiveAmount(event.eventId, event.amount)
            is CashDividend -> requirePositiveAmount(event.eventId, event.amount)
            is PortfolioFee -> requirePositiveAmount(event.eventId, event.amount)
            is DividendReinvestment -> {
                if (event.quantity.signum() <= 0) {
                    LedgerRejection.NonPositiveQuantity(event.eventId, event.quantity)
                } else if (event.unitPrice.amount.signum() <= 0) {
                    LedgerRejection.NonPositiveUnitPrice(event.eventId, event.unitPrice)
                } else {
                    requirePositiveAmount(event.eventId, event.amount)
                        ?: checkReinvestmentConsideration(event)
                }
            }
            is SecurityBuy -> validateTrade(event.eventId, event.quantity, event.unitPrice, event.fee)
            is SecuritySell -> validateTrade(event.eventId, event.quantity, event.unitPrice, event.fee)
        }

        private fun validateTrade(
            eventId: String,
            quantity: BigDecimal,
            unitPrice: Money,
            fee: Money,
        ): LedgerRejection? = when {
            quantity.signum() <= 0 -> LedgerRejection.NonPositiveQuantity(eventId, quantity)
            unitPrice.amount.signum() <= 0 -> LedgerRejection.NonPositiveUnitPrice(eventId, unitPrice)
            fee.amount.signum() < 0 -> LedgerRejection.NonPositiveAmount(eventId, fee)
            else -> null
        }

        private fun requirePositiveAmount(eventId: String, amount: Money): LedgerRejection? =
            if (amount.amount.signum() <= 0) LedgerRejection.NonPositiveAmount(eventId, amount) else null

        /**
         * Settlement rounding convention: a reinvestment's declared amount must equal
         * quantity × unitPrice rounded to 2 fractional digits with banker's rounding
         * (HALF_EVEN) — the [Money.rounded] contract — compared by numeric equality
         * (scale-insensitive), with no tolerance band. This accepts legitimate
         * fractional-share settlement rounding and rejects a unitPrice that contradicts
         * the booked amount. Runs after the currency boundary, so amount and unitPrice
         * are already in the base currency; fees are separate events and never folded in.
         */
        private fun checkReinvestmentConsideration(event: DividendReinvestment): LedgerRejection? {
            val expected = event.quantity
                .multiply(event.unitPrice.amount)
                .setScale(2, RoundingMode.HALF_EVEN)
            return if (event.amount.amount.compareTo(expected) == 0) {
                null
            } else {
                LedgerRejection.InconsistentReinvestmentConsideration(
                    eventId = event.eventId,
                    declaredAmount = event.amount,
                    expectedAmount = Money.of(expected, event.unitPrice.currency),
                )
            }
        }
    }
}
