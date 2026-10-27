package com.athar.core.domain.calc.cashforecast

import com.athar.core.domain.model.cashforecast.CashEffect
import com.athar.core.domain.model.cashforecast.CashForecastDisclosure
import com.athar.core.domain.model.cashforecast.EffectCertainty
import com.athar.core.domain.model.cashforecast.SettlementIncompatibility
import kotlinx.collections.immutable.toPersistentList

internal object CashForecastSettlement {
    fun resolve(facts: ForecastFacts, context: ForecastContext): List<CashEffect> {
        val accepted = facts.cash.associateBy { it.effectId }
        val suppressed = mutableSetOf<String>()
        // Inspect original claim variants too: a conflicting claimant must not revive its expectation.
        val claims = facts.rawCashById.values.flatten().filter { it.settlesExpectationId != null }
            .groupBy { checkNotNull(it.settlesExpectationId) }.toSortedMap()
        for ((targetId, rawClaims) in claims) {
            val claimants = rawClaims.groupBy { it.effectId }.toSortedMap()
            val targetVariants = facts.rawCashById[targetId].orEmpty()
            val target = accepted[targetId]
            if (targetVariants.any { it.certainty == EffectCertainty.EXPECTED }) suppressed += targetId
            rawClaims.filter { it.certainty == EffectCertainty.EXPECTED }.forEach { suppressed += it.effectId }
            val affected = (targetVariants + rawClaims).map { it.literalCurrency() }
            if (target == null) {
                context.demote(affected)
                for (id in claimants.keys) context.disclosures += if (targetVariants.isEmpty()) {
                    CashForecastDisclosure.UnresolvedSettlementReference(id, targetId)
                } else CashForecastDisclosure.IncompatibleSettlementLink(targetId, id, SettlementIncompatibility.CONFLICTING_IDENTITY)
                continue
            }
            if (target.certainty != EffectCertainty.EXPECTED) {
                context.demote(affected)
                for (id in claimants.keys) context.disclosures += CashForecastDisclosure.IncompatibleSettlementLink(
                    targetId, id, SettlementIncompatibility.TARGET_NOT_EXPECTED,
                )
                continue
            }
            if (claimants.size > 1) {
                context.demote(affected)
                val postedIds = claimants.filterValues { group -> group.any { it.certainty == EffectCertainty.POSTED } }.keys.toList()
                if (postedIds.size > 1) context.disclosures += CashForecastDisclosure.AmbiguousSettlementClaimants(
                    targetId, postedIds.toPersistentList(),
                )
                for ((id, variants) in claimants) {
                    val reason = when {
                        accepted[id] == null -> SettlementIncompatibility.CONFLICTING_IDENTITY
                        variants.any { it.certainty != EffectCertainty.POSTED } -> SettlementIncompatibility.CLAIMANT_NOT_POSTED
                        postedIds.size <= 1 -> SettlementIncompatibility.DOUBLE_SETTLEMENT
                        else -> null
                    }
                    if (reason != null) context.disclosures += CashForecastDisclosure.IncompatibleSettlementLink(targetId, id, reason)
                }
                continue
            }
            val id = claimants.keys.single()
            val claimant = accepted[id]
            val reason = if (claimant == null) SettlementIncompatibility.CONFLICTING_IDENTITY else incompatible(target, claimant)
            if (reason == null) context.disclosures += CashForecastDisclosure.VerifiedSettlement(targetId, id)
            else {
                context.demote(affected)
                context.disclosures += CashForecastDisclosure.IncompatibleSettlementLink(targetId, id, reason)
            }
        }
        return facts.cash.filterNot { it.certainty == EffectCertainty.EXPECTED && it.effectId in suppressed }
    }

    private fun incompatible(target: CashEffect, claimant: CashEffect): SettlementIncompatibility? {
        val expected = target.amount
        val posted = claimant.amount
        return when {
            claimant.certainty != EffectCertainty.POSTED -> SettlementIncompatibility.CLAIMANT_NOT_POSTED
            expected == null || posted == null -> SettlementIncompatibility.AMOUNT_UNAVAILABLE
            target.valueDate == null || claimant.valueDate == null -> SettlementIncompatibility.DATE_UNAVAILABLE
            expected.currency != posted.currency -> SettlementIncompatibility.CURRENCY_MISMATCH
            expected.amount.signum() != posted.amount.signum() -> SettlementIncompatibility.SIGN_MISMATCH
            posted.amount.abs() < expected.amount.abs() -> SettlementIncompatibility.INSUFFICIENT_AMOUNT
            else -> null
        }
    }
}
