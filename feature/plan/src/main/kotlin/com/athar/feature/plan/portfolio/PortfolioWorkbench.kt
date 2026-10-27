package com.athar.feature.plan.portfolio

import com.athar.core.common.money.Money
import com.athar.core.common.result.Outcome
import com.athar.core.domain.calc.portfolio.AllocationCalc
import com.athar.core.domain.calc.portfolio.AllocationGoal
import com.athar.core.domain.calc.portfolio.AllocationGoalError
import com.athar.core.domain.calc.portfolio.AllocationReport
import com.athar.core.domain.calc.portfolio.PortfolioAccounting
import com.athar.core.domain.calc.portfolio.PortfolioAccountingCalc
import com.athar.core.domain.calc.portfolio.PortfolioReturnsCalc
import com.athar.core.domain.calc.portfolio.TwrResult
import com.athar.core.domain.calc.portfolio.XirrResult
import com.athar.core.domain.model.portfolio.AssetClass
import com.athar.core.domain.model.portfolio.CashDividend
import com.athar.core.domain.model.portfolio.DividendDestination
import com.athar.core.domain.model.portfolio.DividendReinvestment
import com.athar.core.domain.model.portfolio.ExternalContribution
import com.athar.core.domain.model.portfolio.ExternalWithdrawal
import com.athar.core.domain.model.portfolio.LedgerRejection
import com.athar.core.domain.model.portfolio.PortfolioEvent
import com.athar.core.domain.model.portfolio.PortfolioFee
import com.athar.core.domain.model.portfolio.PortfolioLedger
import com.athar.core.domain.model.portfolio.PortfolioSecurity
import com.athar.core.domain.model.portfolio.SecurityBuy
import com.athar.core.domain.model.portfolio.SecurityQuote
import com.athar.core.domain.model.portfolio.SecuritySell
import java.math.BigDecimal
import kotlinx.datetime.LocalDate

/**
 * Why a document failed to build/replay, as stable safe tokens (never payload text).
 * The UI maps each token to a localized explanation; unknown tokens fall back to a
 * generic line so a future engine rejection can never crash or leak data.
 */
enum class PortfolioRejectToken {
    MALFORMED_NUMBER,
    MALFORMED_OPENING_CASH,
    MALFORMED_OPENING_DATE,
    MALFORMED_ASSET_CLASS,
    MALFORMED_DESTINATION,
    MALFORMED_TARGET,
    DUPLICATE_EVENT_ID,
    INVALID_EVENT_ID,
    DUPLICATE_SECURITY_ID,
    INVALID_SECURITY_ID,
    AMBIGUOUS_ORDERING,
    CROSS_CURRENCY,
    UNKNOWN_SECURITY,
    NON_POSITIVE_QUANTITY,
    NON_POSITIVE_UNIT_PRICE,
    NON_POSITIVE_AMOUNT,
    INCONSISTENT_REINVESTMENT,
    UNSUPPORTED_SHORT_SALE,
    INSUFFICIENT_CASH,
    AMBIGUOUS_QUOTES,
    NEGATIVE_QUOTE_PRICE,
    NEGATIVE_STALE_AFTER_DAYS,
    TARGETS_DO_NOT_SUM_TO_ONE,
    NON_POSITIVE_TARGET,
    INVALID_POINT_TARGET,
    INVALID_RANGE,
}

/**
 * Pure adapter: a decoded [PortfolioPlanDocument] replayed through the reviewed
 * engines (`PortfolioLedger` → `PortfolioAccountingCalc` → `AllocationCalc` +
 * `PortfolioReturnsCalc`). Adds no financial assumptions of its own; every refusal is
 * a typed engine rejection surfaced as a safe token. A null [accounting] with a null
 * [rejection] means "no dated inputs yet" (only opening cash) — an honest empty state,
 * not a zero valuation.
 */
data class PortfolioWorkbench(
    val document: PortfolioPlanDocument,
    val asOf: LocalDate?,
    val accounting: PortfolioAccounting?,
    val allocation: AllocationReport?,
    val allocationProblem: String?,
    val xirr: XirrResult,
    val twr: TwrResult,
    val rejection: String?,
) {
    val isEmpty: Boolean get() = accounting == null && rejection == null

    companion object {

        private const val STALE_AFTER_DAYS = 7

        fun build(document: PortfolioPlanDocument): PortfolioWorkbench {
            val currency = document.currency

            val openingCash = decimal(document.openingCashAmount)
                ?: return rejected(document, PortfolioRejectToken.MALFORMED_OPENING_CASH)
            val openingDate = document.openingCashDate?.let {
                date(it) ?: return rejected(document, PortfolioRejectToken.MALFORMED_OPENING_DATE)
            }

            val securities = mutableListOf<PortfolioSecurity>()
            for (security in document.securities) {
                val assetClass = when {
                    security.assetClass == null -> null
                    AssetClass.entries.any { it.name == security.assetClass } -> AssetClass.valueOf(security.assetClass!!)
                    else -> return rejected(document, PortfolioRejectToken.MALFORMED_ASSET_CLASS)
                }
                securities += PortfolioSecurity(security.securityId, security.name, assetClass)
            }

            val events = mutableListOf<PortfolioEvent>()
            document.events.forEachIndexed { index, input ->
                val date = date(input.date) ?: return rejected(document, PortfolioRejectToken.MALFORMED_NUMBER)
                val typed: PortfolioEvent = when (input.kind) {
                    PortfolioEntryKind.BUY -> SecurityBuy(
                        eventId = input.eventId, date = date, sequence = index,
                        securityRef = input.securityId.orEmpty(),
                        quantity = decimal(input.quantity)
                            ?: return rejected(document, PortfolioRejectToken.MALFORMED_NUMBER),
                        unitPrice = money(input.unitPrice, currency)
                            ?: return rejected(document, PortfolioRejectToken.MALFORMED_NUMBER),
                        fee = money(input.fee ?: "0", currency)
                            ?: return rejected(document, PortfolioRejectToken.MALFORMED_NUMBER),
                    )
                    PortfolioEntryKind.SELL -> SecuritySell(
                        eventId = input.eventId, date = date, sequence = index,
                        securityRef = input.securityId.orEmpty(),
                        quantity = decimal(input.quantity)
                            ?: return rejected(document, PortfolioRejectToken.MALFORMED_NUMBER),
                        unitPrice = money(input.unitPrice, currency)
                            ?: return rejected(document, PortfolioRejectToken.MALFORMED_NUMBER),
                        fee = money(input.fee ?: "0", currency)
                            ?: return rejected(document, PortfolioRejectToken.MALFORMED_NUMBER),
                    )
                    PortfolioEntryKind.CONTRIBUTION -> ExternalContribution(
                        eventId = input.eventId, date = date, sequence = index,
                        amount = money(input.amount, currency)
                            ?: return rejected(document, PortfolioRejectToken.MALFORMED_NUMBER),
                        note = input.note.orEmpty(),
                    )
                    PortfolioEntryKind.WITHDRAWAL -> ExternalWithdrawal(
                        eventId = input.eventId, date = date, sequence = index,
                        amount = money(input.amount, currency)
                            ?: return rejected(document, PortfolioRejectToken.MALFORMED_NUMBER),
                        note = input.note.orEmpty(),
                    )
                    PortfolioEntryKind.FEE -> PortfolioFee(
                        eventId = input.eventId, date = date, sequence = index,
                        amount = money(input.amount, currency)
                            ?: return rejected(document, PortfolioRejectToken.MALFORMED_NUMBER),
                        reason = input.note.orEmpty(),
                    )
                    PortfolioEntryKind.CASH_DIVIDEND -> {
                        val destination = DividendDestination.entries.firstOrNull { it.name == input.destination }
                            ?: return rejected(document, PortfolioRejectToken.MALFORMED_DESTINATION)
                        CashDividend(
                            eventId = input.eventId, date = date, sequence = index,
                            securityRef = input.securityId.orEmpty(),
                            amount = money(input.amount, currency)
                                ?: return rejected(document, PortfolioRejectToken.MALFORMED_NUMBER),
                            destination = destination,
                        )
                    }
                    PortfolioEntryKind.REINVESTMENT -> DividendReinvestment(
                        eventId = input.eventId, date = date, sequence = index,
                        securityRef = input.securityId.orEmpty(),
                        amount = money(input.amount, currency)
                            ?: return rejected(document, PortfolioRejectToken.MALFORMED_NUMBER),
                        unitPrice = money(input.unitPrice, currency)
                            ?: return rejected(document, PortfolioRejectToken.MALFORMED_NUMBER),
                        quantity = decimal(input.quantity)
                            ?: return rejected(document, PortfolioRejectToken.MALFORMED_NUMBER),
                    )
                }
                events += typed
            }

            val quotes = document.quotes.map { quote ->
                val date = date(quote.date) ?: return rejected(document, PortfolioRejectToken.MALFORMED_NUMBER)
                val price = money(quote.unitPrice, currency)
                    ?: return rejected(document, PortfolioRejectToken.MALFORMED_NUMBER)
                SecurityQuote(quote.securityId, date, price)
            }

            // Securities alone carry no dated facts: with no events/quotes there is
            // nothing to replay, so this is the honest empty state, not a rejection.
            if (document.events.isEmpty() && document.quotes.isEmpty() && openingDate == null) {
                return PortfolioWorkbench(
                    document = document, asOf = null, accounting = null, allocation = null,
                    allocationProblem = null,
                    xirr = XirrResult.Undefined(com.athar.core.domain.calc.portfolio.ReturnUndefinedReason.NO_DATED_FLOWS),
                    twr = TwrResult.Undefined(com.athar.core.domain.calc.portfolio.ReturnUndefinedReason.NO_DATED_FLOWS),
                    rejection = null,
                )
            }

            if (openingDate != null && events.any { it.date < openingDate }) {
                return rejected(document, PortfolioRejectToken.MALFORMED_OPENING_DATE)
            }
            val asOf = (document.events.map { it.date } + document.quotes.map { it.date } + listOfNotNull(document.openingCashDate))
                .mapNotNull { date(it) }.maxOrNull()
                ?: return rejected(document, PortfolioRejectToken.MALFORMED_NUMBER)

            val ledger = when (val built = PortfolioLedger.of(currency, Money.of(openingCash, currency), securities, events, openingCashDate = openingDate)) {
                is Outcome.Success -> built.value
                is Outcome.Failure -> return rejected(document, safeName(built.error))
            }
            val accounting = when (
                val replayed = PortfolioAccountingCalc.account(ledger, quotes, asOf, STALE_AFTER_DAYS)
            ) {
                is Outcome.Success -> replayed.value
                is Outcome.Failure -> return rejected(document, safeName(replayed.error))
            }

            var allocation: AllocationReport? = null
            var allocationProblem: String? = null
            if (document.allocationTargets.isNotEmpty()) {
                val goals = linkedMapOf<AssetClass, AllocationGoal>()
                for (target in document.allocationTargets) {
                    val assetClass = AssetClass.entries.firstOrNull { it.name == target.assetClass }
                    if (assetClass == null) {
                        allocationProblem = PortfolioRejectToken.MALFORMED_ASSET_CLASS.name
                        break
                    }
                    val goal: AllocationGoal? = when (target.kind) {
                        "POINT" -> decimal(target.fraction)?.let(AllocationGoal::PointTarget)
                        "RANGE" -> {
                            val min = decimal(target.minFraction)
                            val max = decimal(target.maxFraction)
                            if (min == null || max == null) null else AllocationGoal.RangeTarget(min, max)
                        }
                        else -> null
                    }
                    if (goal == null) {
                        allocationProblem = PortfolioRejectToken.MALFORMED_TARGET.name
                        break
                    }
                    goals[assetClass] = goal
                }
                if (allocationProblem == null) {
                    when (val evaluated = AllocationCalc.evaluate(accounting, goals)) {
                        is Outcome.Success -> allocation = evaluated.value
                        is Outcome.Failure -> allocationProblem = safeGoalName(evaluated.error).name
                    }
                }
            }

            return PortfolioWorkbench(
                document = document,
                asOf = asOf,
                accounting = accounting,
                allocation = allocation,
                allocationProblem = allocationProblem,
                xirr = PortfolioReturnsCalc.xirr(ledger, quotes, asOf),
                twr = PortfolioReturnsCalc.timeWeighted(ledger, quotes, asOf),
                rejection = null,
            )
        }

        fun safeName(rejection: LedgerRejection): PortfolioRejectToken = when (rejection) {
            is LedgerRejection.DuplicateEventId -> PortfolioRejectToken.DUPLICATE_EVENT_ID
            is LedgerRejection.InvalidBlankEventId -> PortfolioRejectToken.INVALID_EVENT_ID
            is LedgerRejection.DuplicateSecurityId -> PortfolioRejectToken.DUPLICATE_SECURITY_ID
            is LedgerRejection.InvalidBlankSecurityId -> PortfolioRejectToken.INVALID_SECURITY_ID
            is LedgerRejection.AmbiguousOrdering -> PortfolioRejectToken.AMBIGUOUS_ORDERING
            is LedgerRejection.CrossCurrencyEvent -> PortfolioRejectToken.CROSS_CURRENCY
            is LedgerRejection.UnknownSecurity -> PortfolioRejectToken.UNKNOWN_SECURITY
            is LedgerRejection.NonPositiveQuantity -> PortfolioRejectToken.NON_POSITIVE_QUANTITY
            is LedgerRejection.NonPositiveUnitPrice -> PortfolioRejectToken.NON_POSITIVE_UNIT_PRICE
            is LedgerRejection.NonPositiveAmount -> PortfolioRejectToken.NON_POSITIVE_AMOUNT
            is LedgerRejection.InconsistentReinvestmentConsideration -> PortfolioRejectToken.INCONSISTENT_REINVESTMENT
            is LedgerRejection.UnsupportedShortSale -> PortfolioRejectToken.UNSUPPORTED_SHORT_SALE
            is LedgerRejection.InsufficientCash -> PortfolioRejectToken.INSUFFICIENT_CASH
            is LedgerRejection.AmbiguousQuotes -> PortfolioRejectToken.AMBIGUOUS_QUOTES
            is LedgerRejection.NegativeQuotePrice -> PortfolioRejectToken.NEGATIVE_QUOTE_PRICE
            is LedgerRejection.NegativeStaleAfterDays -> PortfolioRejectToken.NEGATIVE_STALE_AFTER_DAYS
        }

        fun safeGoalName(error: AllocationGoalError): PortfolioRejectToken = when (error) {
            is AllocationGoalError.TargetsDoNotSumToOne -> PortfolioRejectToken.TARGETS_DO_NOT_SUM_TO_ONE
            is AllocationGoalError.NonPositiveTarget -> PortfolioRejectToken.NON_POSITIVE_TARGET
            is AllocationGoalError.InvalidPointTarget -> PortfolioRejectToken.INVALID_POINT_TARGET
            is AllocationGoalError.InvalidRange -> PortfolioRejectToken.INVALID_RANGE
        }

        private fun rejected(document: PortfolioPlanDocument, token: PortfolioRejectToken): PortfolioWorkbench =
            PortfolioWorkbench(
                document = document, asOf = null, accounting = null, allocation = null,
                allocationProblem = null,
                xirr = XirrResult.Undefined(com.athar.core.domain.calc.portfolio.ReturnUndefinedReason.NO_DATED_FLOWS),
                twr = TwrResult.Undefined(com.athar.core.domain.calc.portfolio.ReturnUndefinedReason.NO_DATED_FLOWS),
                rejection = token.name,
            )

        private fun decimal(raw: String?): BigDecimal? {
            val trimmed = raw?.trim().orEmpty()
            if (trimmed.isEmpty() || trimmed.length > 128) return null
            return trimmed.toBigDecimalOrNull()
        }

        private fun money(raw: String?, currency: String): Money? =
            decimal(raw)?.let { Money.of(it, currency) }

        private fun date(raw: String): LocalDate? = try {
            LocalDate.parse(raw)
        } catch (_: IllegalArgumentException) {
            null
        }
    }
}
