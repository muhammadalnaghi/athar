package com.athar.core.data.repo

import com.athar.core.common.money.Money
import com.athar.core.data.db.dao.RecurringRuleDao
import com.athar.core.data.db.dao.TransactionDao
import com.athar.core.data.db.entity.TransactionEntity
import com.athar.core.domain.calc.RecurringSchedule
import com.athar.core.domain.model.Cadence
import com.athar.core.domain.model.RecurringSuggestion
import com.athar.core.domain.model.TxType
import com.athar.core.domain.model.specificMerchantKey
import com.athar.core.domain.repo.RecurringSuggestionRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.datetime.Clock
import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone
import kotlinx.datetime.isoDayNumber
import kotlinx.datetime.toLocalDateTime
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.abs

/**
 * Detects recurring patterns in confirmed transaction history.
 *
 * Grouping is (specific merchant, currency, type) — generic merchant labels never form
 * groups, and distinct services never collapse because the merchant key is exact. Within
 * a group, amounts are clustered with a bounded variation (≤ [MAX_AMOUNT_VARIATION] of
 * the cluster's median) inside one currency; no cross-currency or FX claims are made.
 *
 * A cluster qualifies only when its distinct dates align with one cadence, simulated with
 * the same clamping math as [RecurringSchedule] (month-end, February, leap years):
 * - WEEKLY: ≥ 4 distinct dates, each next occurrence within ±2 days of the same weekday
 * - MONTHLY: ≥ 3 distinct dates, gaps within ±4 days of one month
 * - QUARTERLY: ≥ 3 distinct dates, gaps within ±6 days of three months
 * - YEARLY: ≥ 2 distinct dates, gaps within ±7 days of one year
 * Three or more charges on the same date are a one-off batch and reject the cluster.
 *
 * Looks back [LOOKBACK_YEARS] years so yearly cadences can gather their minimum evidence.
 * Suggestions are proposals only — the user always confirms before a rule is created.
 */
@Singleton
internal class RecurringSuggestionRepositoryImpl @Inject constructor(
    private val transactionDao: TransactionDao,
    private val ruleDao: RecurringRuleDao,
    private val clock: Clock,
) : RecurringSuggestionRepository {

    override fun observeSuggestions(): Flow<List<RecurringSuggestion>> {
        val today = clock.now().toLocalDateTime(TimeZone.currentSystemDefault()).date
        val lookbackStart = LocalDate(today.year - LOOKBACK_YEARS, today.monthNumber, 1)
        return transactionDao.observeConfirmedSince(lookbackStart)
            .combine(ruleDao.observeAll()) { txs, existingRules ->
                buildSuggestions(
                    txs = txs,
                    today = today,
                    existingKeys = existingRules.mapNotNull { rule ->
                        val cadence = runCatching { Cadence.valueOf(rule.cadence) }.getOrNull()
                            ?: return@mapNotNull null
                        val type = runCatching { TxType.valueOf(rule.type) }.getOrNull()
                            ?: return@mapNotNull null
                        SuppressionKey(
                            merchantNormalized = rule.merchant.lowercase().trim(),
                            amountMinor = rule.amountMinor,
                            currency = rule.amountCurrency,
                            type = type,
                            cadence = cadence,
                        )
                    }.toSet(),
                )
            }
    }

    private data class GroupKey(
        val merchantNormalized: String,
        val currency: String,
        val type: TxType,
    )

    /**
     * Literal identity of one recurring pattern: exact merchant key, exact minor-unit
     * amount, exact currency, exact transaction type, and cadence. Mirrors the
     * [GroupKey] partition (merchant, currency, type) so a rule suppresses a suggestion
     * only within the same currency and type — never across them. Typed fields cannot
     * collide via separator-bearing merchant text.
     */
    private data class SuppressionKey(
        val merchantNormalized: String,
        val amountMinor: Long,
        val currency: String,
        val type: TxType,
        val cadence: Cadence,
    )

    private data class Anchors(
        val dayOfMonth: Int,
        val dayOfWeek: Int?,
        val monthOfYear: Int?,
    )

    private fun buildSuggestions(
        txs: List<TransactionEntity>,
        today: LocalDate,
        existingKeys: Set<SuppressionKey>,
    ): List<RecurringSuggestion> {
        val groups: Map<GroupKey, List<TransactionEntity>> = txs.mapNotNull { tx ->
            val merchantKey = specificMerchantKey(
                merchantNormalized = tx.merchantNormalized,
                merchant = tx.merchant,
            ) ?: return@mapNotNull null
            val type = runCatching { TxType.valueOf(tx.type) }.getOrNull() ?: return@mapNotNull null
            GroupKey(merchantKey, tx.currency, type) to tx
        }.groupBy(
            keySelector = { (key, _) -> key },
            valueTransform = { (_, tx) -> tx },
        )

        return groups.flatMap { (key, list) ->
            amountClusters(list).mapNotNull { cluster ->
                suggestionFor(key, cluster, today, existingKeys)
            }
        }
            .sortedWith(compareByDescending<RecurringSuggestion> { it.occurrenceCount }.thenByDescending { it.lastSeen })
            .take(MAX_SUGGESTIONS)
    }

    /** Greedy ascending clusters, bounded against each tentative cluster's exact median. */
    private fun amountClusters(list: List<TransactionEntity>): List<List<TransactionEntity>> {
        val sorted = list.sortedBy { it.amountMinor }
        val clusters = mutableListOf<MutableList<TransactionEntity>>(mutableListOf())
        for (tx in sorted) {
            val current = clusters.last()
            if (current.isNotEmpty() && variationExceedsMedian(current, tx.amountMinor)) {
                clusters.add(mutableListOf())
            }
            clusters.last().add(tx)
        }
        return clusters.filter { it.size >= MIN_OCCURRENCES_FLOOR }
    }

    /** Exact even/odd median bounds; indexed reads avoid copying a growing cluster. */
    private fun variationExceedsMedian(cluster: List<TransactionEntity>, candidate: Long): Boolean {
        val size = cluster.size + 1
        fun amountAt(index: Int): Long = if (index == cluster.size) candidate else cluster[index].amountMinor
        val medianDoubled =
            amountAt((size - 1) / 2).toBigDecimal() + amountAt(size / 2).toBigDecimal()
        val allowance = medianDoubled * MAX_AMOUNT_VARIATION.toBigDecimal()
        fun breaches(amount: Long): Boolean =
            (amount.toBigDecimal() * 2.toBigDecimal() - medianDoubled).abs().movePointRight(2) > allowance
        return breaches(cluster.first().amountMinor) || breaches(candidate)
    }

    private fun suggestionFor(
        key: GroupKey,
        cluster: List<TransactionEntity>,
        today: LocalDate,
        existingKeys: Set<SuppressionKey>,
    ): RecurringSuggestion? {
        // Same-date batches (e.g., bulk gift-card purchases) are one-off spending, not rhythm.
        val byDate = cluster.groupBy { it.date }
        if (byDate.any { it.value.size >= SAME_DATE_BATCH }) return null
        // One row per date: a duplicated charge is one occurrence of the pattern.
        val dated = byDate.map { (_, sameDay) -> sameDay.minBy { it.createdAt } }.sortedBy { it.date }
        if (dated.size < MIN_OCCURRENCES_FLOOR) return null

        val dates = dated.map { it.date }
        val cadence = detectCadence(dates) ?: return null
        val suggestionKey = SuppressionKey(
            merchantNormalized = key.merchantNormalized,
            amountMinor = medianAmount(dated),
            currency = key.currency,
            type = key.type,
            cadence = cadence,
        )
        if (suggestionKey in existingKeys) return null

        val anchors = anchorsFor(cadence, dates.first())
        val latest = dated.last()
        return RecurringSuggestion(
            merchant = latest.merchant,
            merchantNormalized = key.merchantNormalized,
            amount = Money.ofMinor(medianAmount(dated), key.currency),
            type = key.type,
            suggestedAccountId = unambiguousAccountId(dated),
            suggestedCategoryId = unambiguousCategoryId(dated),
            occurrenceCount = dated.size,
            typicalDayOfMonth = anchors.dayOfMonth,
            cadence = cadence,
            dayOfWeek = anchors.dayOfWeek,
            monthOfYear = anchors.monthOfYear,
            lastSeen = latest.date,
            suggestedNextRun = nextRunAfterLast(latest.date, cadence, anchors, today),
        )
    }

    private fun detectCadence(dates: List<LocalDate>): Cadence? {
        for (candidate in CADENCE_PRIORITY) {
            if (dates.size < candidate.minObservations()) continue
            if (alignsWithCadence(dates, candidate)) return candidate
        }
        return null
    }

    /**
     * Walk the dates simulating each next expected occurrence with the shared schedule
     * math (so month-end and leap dates clamp identically), re-anchoring on each observed
     * date. Every gap must land inside the cadence tolerance.
     */
    private fun alignsWithCadence(dates: List<LocalDate>, cadence: Cadence): Boolean {
        val anchors = anchorsFor(cadence, dates.first())
        val tolerance = cadence.toleranceDays()
        var expected = dates.first()
        for (date in dates.drop(1)) {
            expected = RecurringSchedule.nextRunAfter(
                fromDate = expected,
                cadence = cadence,
                dayOfMonth = anchors.dayOfMonth,
                dayOfWeek = anchors.dayOfWeek,
                monthOfYear = anchors.monthOfYear,
            )
            if (absDays(date, expected) > tolerance) return false
            expected = date
        }
        return true
    }

    private fun anchorsFor(cadence: Cadence, first: LocalDate): Anchors = Anchors(
        dayOfMonth = first.dayOfMonth,
        dayOfWeek = if (cadence == Cadence.WEEKLY) first.dayOfWeek.isoDayNumber else null,
        monthOfYear = if (cadence == Cadence.YEARLY) first.monthNumber else null,
    )

    /** The suggested next run: one cadence step after [lastSeen], always strictly after [today]. */
    private fun nextRunAfterLast(
        lastSeen: LocalDate,
        cadence: Cadence,
        anchors: Anchors,
        today: LocalDate,
    ): LocalDate {
        var next = RecurringSchedule.nextRunAfter(
            fromDate = lastSeen,
            cadence = cadence,
            dayOfMonth = anchors.dayOfMonth,
            dayOfWeek = anchors.dayOfWeek,
            monthOfYear = anchors.monthOfYear,
        )
        var guard = 0
        while (next <= today && guard < NEXT_RUN_CATCH_UP_STEPS) {
            next = RecurringSchedule.nextRunAfter(
                fromDate = next,
                cadence = cadence,
                dayOfMonth = anchors.dayOfMonth,
                dayOfWeek = anchors.dayOfWeek,
                monthOfYear = anchors.monthOfYear,
            )
            guard++
        }
        return next
    }

    private fun medianAmount(dated: List<TransactionEntity>): Long {
        val amounts = dated.map { it.amountMinor }.sorted()
        return amounts[amounts.size / 2]
    }

    private fun absDays(a: LocalDate, b: LocalDate): Int =
        abs(java.time.temporal.ChronoUnit.DAYS.between(a.toJava(), b.toJava())).toInt()

    private fun LocalDate.toJava(): java.time.LocalDate =
        java.time.LocalDate.of(year, monthNumber, dayOfMonth)

    private fun unambiguousAccountId(list: List<TransactionEntity>): String? =
        unambiguousNonBlankValue(list) { it.accountId }

    private fun unambiguousCategoryId(list: List<TransactionEntity>): String? =
        unambiguousNonBlankValue(list) { it.categoryId }

    private fun unambiguousNonBlankValue(
        list: List<TransactionEntity>,
        value: (TransactionEntity) -> String?,
    ): String? {
        val values = list.map { tx ->
            value(tx)?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        }
        return values.distinct().singleOrNull()
    }


    private fun Cadence.minObservations(): Int = when (this) {
        Cadence.WEEKLY -> MIN_WEEKLY_OBSERVATIONS
        Cadence.MONTHLY -> MIN_MONTHLY_OBSERVATIONS
        Cadence.QUARTERLY -> MIN_QUARTERLY_OBSERVATIONS
        Cadence.YEARLY -> MIN_YEARLY_OBSERVATIONS
    }

    private fun Cadence.toleranceDays(): Int = when (this) {
        Cadence.WEEKLY -> TOLERANCE_DAYS_WEEKLY
        Cadence.MONTHLY -> TOLERANCE_DAYS_MONTHLY
        Cadence.QUARTERLY -> TOLERANCE_DAYS_QUARTERLY
        Cadence.YEARLY -> TOLERANCE_DAYS_YEARLY
    }

    companion object {
        private val CADENCE_PRIORITY = listOf(Cadence.WEEKLY, Cadence.MONTHLY, Cadence.QUARTERLY, Cadence.YEARLY)
        private const val MIN_WEEKLY_OBSERVATIONS = 4
        private const val MIN_MONTHLY_OBSERVATIONS = 3
        private const val MIN_QUARTERLY_OBSERVATIONS = 3
        private const val MIN_YEARLY_OBSERVATIONS = 2

        private const val TOLERANCE_DAYS_WEEKLY = 2
        private const val TOLERANCE_DAYS_MONTHLY = 4
        private const val TOLERANCE_DAYS_QUARTERLY = 6
        private const val TOLERANCE_DAYS_YEARLY = 7

        /** Max relative amount drift inside one cluster, in percent of the cluster's median. */
        private const val MAX_AMOUNT_VARIATION = 10

        /** Any date with this many same-day charges in a cluster marks a one-off batch. */
        private const val SAME_DATE_BATCH = 3

        private const val MIN_OCCURRENCES_FLOOR = 2
        private const val MAX_SUGGESTIONS = 20
        private const val NEXT_RUN_CATCH_UP_STEPS = 40
        private const val LOOKBACK_YEARS = 2
    }
}
