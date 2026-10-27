package com.athar.core.domain.calc.cashforecast

import com.athar.core.common.money.Money
import com.athar.core.domain.model.cashforecast.CashForecastDisclosure
import com.athar.core.domain.model.cashforecast.CashForecastOutcome
import com.athar.core.domain.model.cashforecast.CashForecastRejectionReason
import com.athar.core.domain.model.cashforecast.CashForecastRequest
import com.athar.core.domain.model.cashforecast.EffectCertainty
import com.athar.core.domain.model.cashforecast.SubjectKind
import kotlinx.collections.immutable.toPersistentList
import kotlinx.datetime.LocalDate
import java.util.Currency
import kotlin.math.abs

internal object CashForecastValidation {
    private const val MAX_DAYS = 3650L
    private const val MAX_EFFECTS = 10000L
    private const val MAX_DIGITS = 256
    private const val MAX_SCALE = 1024L

    fun validate(request: CashForecastRequest, context: ForecastContext): CashForecastOutcome.Rejected? {
        val validator = Validator(request, context)
        return validator.run()
    }

    private class Validator(val request: CashForecastRequest, val context: ForecastContext) {
        val reasons = linkedSetOf<CashForecastRejectionReason>()
        val offending = sortedSetOf<String>()

        fun reject(reason: CashForecastRejectionReason, id: String? = null) {
            reasons += reason
            if (id != null) offending += id
        }

        fun run(): CashForecastOutcome.Rejected? {
            val span = request.horizonEnd.toEpochDays().toLong() - request.knowledgeAsOf.toEpochDays().toLong()
            if (span < 0) reject(CashForecastRejectionReason.HORIZON_END_BEFORE_KNOWLEDGE_ASOF)
            if (span > MAX_DAYS) reject(CashForecastRejectionReason.HORIZON_EXCEEDS_LIMIT)
            if (request.effects.size.toLong() + request.reservations.size.toLong() > MAX_EFFECTS) {
                reject(CashForecastRejectionReason.EFFECT_LIMIT_EXCEEDED)
                return rejection()
            }
            if (request.anchors.map { it.currency }.distinct().size != request.anchors.size) {
                reject(CashForecastRejectionReason.DUPLICATE_ANCHOR_CURRENCY)
            }
            for (anchor in request.anchors) {
                val id = "anchor:${anchor.currency}"
                context.currencies += anchor.currency
                if (anchor.asOf > request.knowledgeAsOf) reject(CashForecastRejectionReason.ANCHOR_AFTER_KNOWLEDGE_ASOF, id)
                money(anchor.cash, id)
                anchor.reserved?.let {
                    if (it.currency != anchor.currency) reject(CashForecastRejectionReason.INITIAL_RESERVE_CURRENCY_MISMATCH, id)
                    if (it.isNegative()) reject(CashForecastRejectionReason.NEGATIVE_RESERVE_MAGNITUDE, id)
                    money(it, "reserve:$id")
                }
            }
            for (effect in request.effects) {
                identity(effect.effectId)
                if (effect.settlesExpectationId?.isBlank() == true) {
                    reject(CashForecastRejectionReason.BLANK_EFFECT_IDENTITY, effect.effectId)
                }
                fact(effect.effectId, effect.amount, effect.currencyHint, effect.valueDate, effect.certainty, SubjectKind.CASH_EFFECT)
            }
            for (effect in request.reservations) {
                identity(effect.reservationId)
                if (effect.amount?.isNegative() == true) reject(CashForecastRejectionReason.NEGATIVE_RESERVE_MAGNITUDE, effect.reservationId)
                fact(effect.reservationId, effect.amount, effect.currencyHint, effect.valueDate, effect.certainty, SubjectKind.RESERVATION)
            }
            if (!request.attestation.scopeComplete || !request.attestation.anchorFresh) {
                context.disclosures += CashForecastDisclosure.AttestationIncomplete(
                    request.attestation.scopeComplete, request.attestation.anchorFresh,
                )
                context.demote(context.currencies)
            }
            return rejection()
        }

        private fun identity(id: String) {
            if (id.isBlank()) reject(CashForecastRejectionReason.BLANK_EFFECT_IDENTITY, id)
        }

        private fun fact(id: String, amount: Money?, hint: String?, date: LocalDate?, certainty: EffectCertainty, kind: SubjectKind) {
            if (amount != null && hint != null && hint != amount.currency) {
                reject(CashForecastRejectionReason.CURRENCY_HINT_CONTRADICTION, id)
            }
            val currency = amount?.currency ?: hint
            if (currency == null) {
                reject(CashForecastRejectionReason.UNAVAILABLE_AMOUNT_WITHOUT_CURRENCY, id)
                return
            }
            if (amount == null && (currency.length != 3 || currency.any { it !in 'A'..'Z' })) {
                reject(CashForecastRejectionReason.UNRESOLVABLE_CURRENCY_HINT, id)
                return
            }
            context.currencies += currency
            if (amount == null) {
                support(currency)
                context.disclosures += CashForecastDisclosure.UnavailableAmount(kind, id, currency)
                context.partial += currency
            } else money(amount, id)
            if (date == null) {
                context.disclosures += CashForecastDisclosure.UnavailableDate(kind, id, currency)
                context.partial += currency
            } else if (certainty == EffectCertainty.POSTED && date > request.knowledgeAsOf) {
                reject(CashForecastRejectionReason.POSTED_EFFECT_BEYOND_KNOWLEDGE_ASOF, id)
            }
        }

        private fun support(currency: String): Int? {
            val digits = if (currency.length == 3 && currency.all { it in 'A'..'Z' }) {
                try { Currency.getInstance(currency).defaultFractionDigits.takeIf { it >= 0 } }
                catch (_: IllegalArgumentException) { null }
            } else null
            if (digits == null) {
                context.withheld += currency
                context.disclosures += CashForecastDisclosure.UnsupportedCurrency(currency)
            }
            return digits
        }

        private fun money(value: Money, id: String) {
            if (value.amount.precision() > MAX_DIGITS || abs(value.amount.scale().toLong()) > MAX_SCALE) {
                reject(CashForecastRejectionReason.ARITHMETIC_UNSUPPORTED, id)
                return
            }
            val digits = support(value.currency) ?: return
            val scale = value.amount.stripTrailingZeros().scale().coerceAtLeast(0)
            if (scale > digits) {
                context.withheld += value.currency
                context.disclosures += CashForecastDisclosure.PrecisionExceeded(id, value.currency, scale, digits)
            }
        }

        private fun rejection(): CashForecastOutcome.Rejected? = if (reasons.isEmpty()) null else {
            CashForecastOutcome.Rejected(reasons.sortedBy { it.ordinal }.toPersistentList(), offending.toPersistentList())
        }
    }
}
