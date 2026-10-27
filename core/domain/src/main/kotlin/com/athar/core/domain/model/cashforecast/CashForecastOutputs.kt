package com.athar.core.domain.model.cashforecast

import com.athar.core.common.money.Money
import kotlinx.collections.immutable.ImmutableList
import kotlinx.collections.immutable.toPersistentList
import kotlinx.datetime.LocalDate

/** Confidence label of one currency partition's computed path. */
enum class PartitionStatus {
    /** Complete attestation, known anchors and reserves, no withholding disclosures. */
    CONFIDENT,

    /** Known-only path: computed, but labelled partial/conditional; no decision headline. */
    PARTIAL,

    /** No trustworthy path can be produced for this partition. */
    WITHHELD,
}

/** Typed identity prevents a cash effect and reservation with the same ID from aliasing. */
data class ForecastEffectReference(val kind: SubjectKind, val id: String)

/** Known-only end-of-day state; null reserves/availability disclose unresolved reserve state. */
data class DatedCashDay(
    val date: LocalDate,
    val cash: Money,
    val reserved: Money?,
    val available: Money?,
    /** Known changes or dated uncertainty, never evidence that an unknown amount was applied. */
    val supportingEffects: ImmutableList<ForecastEffectReference>,
) {
    /** Compatibility view; typed [supportingEffects] retains identities across both families. */
    val effectIds: ImmutableList<String> get() = supportingEffects.map { it.id }.sorted().toPersistentList()
}

/**
 * Decision headline for a CONFIDENT partition: the minimum available amount and its date
 * (opening state included), the gap below zero when negative, the first negative date,
 * and the end-of-horizon cash balance. Intraday minima are knowingly absent.
 */
data class PartitionHeadline(
    val minimumAvailable: Money,
    val minimumAvailableDate: LocalDate,
    val shortfall: Money?,
    val firstNegativeAvailableDate: LocalDate?,
    val finalBalance: Money,
)

/** Per-currency forecast: one dated path computed only from that partition's own facts. */
data class CurrencyPartitionForecast(
    val currency: String,
    val status: PartitionStatus,
    /** Anchor-date state; null when the partition is withheld. */
    val opening: DatedCashDay?,
    /** Opening point followed by dated net-effect/uncertainty points; empty when withheld. */
    val path: ImmutableList<DatedCashDay>,
    val headline: PartitionHeadline?,
)

/** Complete forecast result across non-merging currency partitions, with full disclosure. */
data class CashForecastResult(
    /** Sorted by currency code; partitions never combine amounts across currencies. */
    val partitions: ImmutableList<CurrencyPartitionForecast>,
    /** Deterministically sorted typed disclosures, including the intraday-minima note. */
    val disclosures: ImmutableList<CashForecastDisclosure>,
    /**
     * True only when the attestation is complete and fresh and every partition is
     * CONFIDENT. Any missing fact, unsupported currency, or unresolved link keeps this
     * false so no confident headline is issued from incomplete knowledge.
     */
    val decisionReady: Boolean,
)
