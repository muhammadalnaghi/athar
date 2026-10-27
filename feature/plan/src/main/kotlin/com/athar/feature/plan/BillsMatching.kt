package com.athar.feature.plan

import com.athar.core.domain.calc.RecurringSchedule
import com.athar.core.domain.model.Cadence
import com.athar.core.domain.model.RecurringRule
import com.athar.core.domain.model.Transaction
import com.athar.core.domain.model.specificMerchantKey
import kotlinx.datetime.DatePeriod
import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone
import kotlinx.datetime.minus
import kotlinx.datetime.plus
import kotlinx.datetime.toLocalDateTime
import java.math.BigDecimal
import java.time.YearMonth

/**
 * Stable identity of one scheduled occurrence — the same string used as [BillItem.id],
 * so matched payment evidence stays traceable from matcher to UI.
 */
internal fun occurrenceKey(rule: RecurringRule, dueDate: LocalDate): String =
    "recurring:${rule.id}:${dueDate}"

/**
 * Match-first status: an occurrence backed by globally-unique payment evidence is
 * [BillStatus.PAID] regardless of its calendar position (early and due-today payments
 * included). Without evidence the calendar decides; ambiguity never becomes a paid
 * claim — Athar never asserts that a charge was missed or left unpaid.
 */
internal fun billStatusFor(dueDate: LocalDate, today: LocalDate, match: Transaction?): BillStatus =
    when {
        match != null -> BillStatus.PAID
        dueDate > today -> BillStatus.UPCOMING
        dueDate == today -> BillStatus.DUE_TODAY
        else -> BillStatus.UNCONFIRMED
    }

/**
 * Bounded matching universe: the occurrences the UI displays ([uiOccurrences]) plus the
 * complete guard set that paid matching runs over ([matchOccurrences]).
 *
 * The guard adds every schedule occurrence that could compete for the same confirmed
 * charge as an emitted occurrence — including occurrences hidden from the UI list by
 * the anchor-anchored walk (an up-to-date rule's missed history) or by the bounded
 * window. Both node sets are deduped by [occurrenceKey]. UI display coverage and
 * match-guard coverage are reported separately; neither kind of truncation ever
 * disables matching or implies a bill was missed or left unpaid.
 */
internal data class MatchingUniverse(
    val uiOccurrences: List<Pair<RecurringRule, LocalDate>>,
    val matchOccurrences: List<Pair<RecurringRule, LocalDate>>,
    /** The displayed history is a bounded window; older occurrences exist beyond it. */
    val uiHistoryTruncated: Boolean,
    /** More occurrences exist inside the UI horizon than the displayed future cap allowed. */
    val uiFutureTruncated: Boolean,
    val uiHistoryWindowStart: LocalDate?,
    /** Start of the window the paid-matching guard universe actually accounted for. */
    val matchGuardWindowStart: LocalDate?,
    /**
     * True when the guard enumeration provably covered every potential competitor.
     * Guard caps are sized so they cannot truncate for the supported cadences; a false
     * value would surface a real overflow instead of silently narrowing matching.
     */
    val matchGuardComplete: Boolean,
)

/**
 * Completeness argument (supported cadences weekly/monthly/quarterly/yearly):
 *
 * 1. Every UI occurrence lies within [today − UI_LOOKBACK, today + uiHorizonDays], with
 *    UI_LOOKBACK = 92 (the `projectBounded` history default the VM displays).
 * 2. A charge can only be a candidate of occurrences whose due dates lie within the
 *    −3/+10 window widths of each other, so every potential competitor of a UI
 *    occurrence lies within [today − 92 − 13, today + 60 + 10] = [today − 105, today + 70].
 * 3. The guard walks the full schedule grid over
 *    [today − GUARD_HISTORY_LOOKBACK_DAYS, today + uiHorizonDays + PAID_WINDOW_FORWARD_DAYS]
 *    = [today − 120, today + 70], additionally bounded below by the rule's creation
 *    date (occurrences before it never existed). 120 ≥ 92 + 13, so the guard band
 *    strictly contains the competitor band.
 * 4. Guard caps cannot bind: the shortest cadence (weekly) yields at most
 *    (120 + 70) / 7 + 2 = 29 occurrences, far below the cap of 120 per side.
 */
internal const val GUARD_HISTORY_LOOKBACK_DAYS = 120
internal const val GUARD_MAX_OCCURRENCES = 120

internal fun buildMatchingUniverse(
    rules: List<RecurringRule>,
    today: LocalDate,
    uiHorizonDays: Int = 60,
): MatchingUniverse {
    // Both walks consume the canonical copy: the frozen projector re-derives the
    // effective day from its cursor when anchor fields are implicit, so an uncorrected
    // clamped step would rewrite the grid (anchor-31 seeded into June → day 30
    // forever) — and correcting only the guard walk would desync UI/guard keys.
    val canonicalRules = rules.map { rule -> rule to canonicalScheduleRule(rule) }
    val uiProjections = canonicalRules.map { (rule, canonical) ->
        rule to RecurringSchedule.projectBounded(canonical, today = today, horizonDays = uiHorizonDays)
    }
    val guardProjections = canonicalRules.map { (rule, canonical) ->
        // The guard walk must include occurrences hidden before the rule's current
        // nextRunDate anchor, so it is seeded on the rule's own schedule grid at (or
        // after) the guard window start; the frozen domain projector then walks it.
        val guardWindowStart = today.minus(DatePeriod(days = GUARD_HISTORY_LOOKBACK_DAYS))
        val creationDate = rule.createdAt.toLocalDateTime(TimeZone.currentSystemDefault()).date
        val seed = guardSeed(canonical, base = maxOf(guardWindowStart, creationDate))
        rule to RecurringSchedule.projectBounded(
            canonical.copy(nextRunDate = seed),
            today = today,
            horizonDays = uiHorizonDays + PAID_WINDOW_FORWARD_DAYS,
            historyLookbackDays = GUARD_HISTORY_LOOKBACK_DAYS,
            maxFutureOccurrences = GUARD_MAX_OCCURRENCES,
            maxMissedOccurrences = GUARD_MAX_OCCURRENCES,
        )
    }

    val uiOccurrences = uiProjections.flatMap { (rule, projection) ->
        projection.occurrences.map { occurrence -> rule to occurrence.dueDate }
    }
    val guardOccurrences = guardProjections.flatMap { (rule, projection) ->
        projection.occurrences.map { occurrence -> rule to occurrence.dueDate }
    }

    // Union UI and guard nodes keyed by occurrenceKey: guard-only nodes join the
    // matching graph; nodes the UI already emits are never duplicated.
    val union = LinkedHashMap<String, Pair<RecurringRule, LocalDate>>()
    fun putAll(entries: List<Pair<RecurringRule, LocalDate>>) {
        for (entry in entries) union.putIfAbsent(occurrenceKey(entry.first, entry.second), entry)
    }
    putAll(uiOccurrences)
    putAll(guardOccurrences)

    return MatchingUniverse(
        uiOccurrences = uiOccurrences,
        matchOccurrences = union.values.toList(),
        uiHistoryTruncated = uiProjections.any { it.second.historyTruncated },
        uiFutureTruncated = uiProjections.any { it.second.futureTruncated },
        uiHistoryWindowStart = uiProjections.minOfOrNull { it.second.historyWindowStart },
        matchGuardWindowStart = guardProjections.minOfOrNull { it.second.historyWindowStart },
        matchGuardComplete = guardProjections.all {
            !it.second.historyTruncated && !it.second.futureTruncated
        },
    )
}

/**
 * Adapter-side canonical form of [rule]: the schedule fields the frozen domain
 * projector would otherwise re-derive from whatever date it last stepped from are
 * materialized as explicit fields. With an implicit anchor (`dayOfMonth = null`,
 * etc.) the projector falls back to the cursor's own day, so a single clamped step
 * — a monthly anchor on the 31st seeded into June, a quarterly 31st clamped to
 * Apr 30, a yearly Feb 29 clamped to Feb 28 — permanently rewrites the grid to the
 * clamped day and loses the true July-31 / Feb-29 schedule. Rules authored with
 * explicit fields pass through unchanged. Already-lost intent cannot be recovered:
 * a null day with an Apr-30 or Feb-28 anchor means that observed day, not a guessed
 * 31st or leap day. Materialization only preserves fields actually available.
 */
internal fun canonicalScheduleRule(rule: RecurringRule): RecurringRule {
    val anchor = rule.nextRunDate
    return when (rule.cadence) {
        Cadence.WEEKLY -> rule.copy(dayOfWeek = rule.dayOfWeek ?: anchor.dayOfWeek.value)
        Cadence.MONTHLY -> rule.copy(dayOfMonth = rule.dayOfMonth ?: anchor.dayOfMonth)
        Cadence.QUARTERLY -> rule.copy(dayOfMonth = rule.dayOfMonth ?: anchor.dayOfMonth)
        Cadence.YEARLY -> rule.copy(
            dayOfMonth = rule.dayOfMonth ?: anchor.dayOfMonth,
            monthOfYear = rule.monthOfYear ?: anchor.monthNumber,
        )
    }
}

/**
 * First occurrence of [rule]'s schedule grid on or after [base], using the same
 * anchor-derived effective fields [RecurringSchedule] uses (explicit dayOfMonth /
 * dayOfWeek / monthOfYear, falling back to the anchor). Mirrors the frozen domain grid
 * arithmetic so [RecurringSchedule.nextRunAfter] steps from this seed reproduce the
 * rule's real schedule — the seed only has to land on the grid at or before the guard
 * window start; the domain projector re-anchors from there.
 */
private fun guardSeed(rule: RecurringRule, base: LocalDate): LocalDate {
    val anchor = rule.nextRunDate
    val javaBase = java.time.LocalDate.of(base.year, base.monthNumber, base.dayOfMonth)
    return when (rule.cadence) {
        Cadence.WEEKLY -> {
            val targetDow = (rule.dayOfWeek ?: anchor.dayOfWeek.value).coerceIn(1, 7)
            val delta = Math.floorMod(targetDow - base.dayOfWeek.value, 7)
            base.plus(DatePeriod(days = delta))
        }
        Cadence.MONTHLY -> {
            val targetDay = (rule.dayOfMonth ?: anchor.dayOfMonth).coerceIn(1, 31)
            var month = YearMonth.of(base.year, base.monthNumber)
            if (clampedDay(month, targetDay) < javaBase) month = month.plusMonths(1)
            clampedDay(month, targetDay).toKotlinDate()
        }
        Cadence.QUARTERLY -> {
            val targetDay = (rule.dayOfMonth ?: anchor.dayOfMonth).coerceIn(1, 31)
            val anchorMonth = YearMonth.of(anchor.year, anchor.monthNumber)
            var months = (base.year - anchor.year) * 12 + (base.monthNumber - anchor.monthNumber)
            months -= Math.floorMod(months, 3)
            var month = anchorMonth.plusMonths(months.toLong())
            if (clampedDay(month, targetDay) < javaBase) month = month.plusMonths(3)
            clampedDay(month, targetDay).toKotlinDate()
        }
        Cadence.YEARLY -> {
            val targetMonth = (rule.monthOfYear ?: anchor.monthNumber).coerceIn(1, 12)
            val targetDay = (rule.dayOfMonth ?: anchor.dayOfMonth).coerceIn(1, 31)
            var month = YearMonth.of(base.year, targetMonth)
            if (clampedDay(month, targetDay) < javaBase) month = month.plusYears(1)
            clampedDay(month, targetDay).toKotlinDate()
        }
    }
}

private fun clampedDay(month: YearMonth, targetDay: Int): java.time.LocalDate =
    month.atDay(targetDay.coerceAtMost(month.lengthOfMonth()))

private fun java.time.LocalDate.toKotlinDate(): LocalDate =
    LocalDate(year, monthValue, dayOfMonth)

/**
 * Global candidate-graph paid matching across ALL rules and occurrences at once.
 *
 * A confirmed transaction may settle at most one occurrence, ever. Candidate edges
 * require exact compatibility — same account, type, currency, the reviewed ±10%
 * amount tolerance, the real [specificMerchantKey] normalizer on BOTH sides (generic
 * keys like "purchase"/"تحويل" never bind), the charge date inside the occurrence's
 * −3/+10-day window, and as-of safety: the charge dated on or before [asOf]. Evidence
 * dated after [asOf] has not happened yet as of the evaluation date (future-dated
 * entries, pre-recorded scheduled charges) and can never settle an occurrence; an early
 * payment already recorded on or before [asOf] may still settle a later-dated
 * occurrence inside its window. Callers must pass the complete guard universe from
 * [buildMatchingUniverse], never the UI-capped occurrences alone.
 *
 * An edge binds only when it is mutually unique: its occurrence has
 * exactly one candidate AND its transaction has exactly one candidate across the whole
 * graph, iterated to a fixpoint. Contested candidates (one payment inside two windows,
 * two rules claiming one charge) stay unbound — zero PAID is an acceptable outcome and
 * is never greedily forced into a false payment claim.
 */
internal fun resolveObservedMatches(
    occurrences: List<Pair<RecurringRule, LocalDate>>,
    evidence: List<Transaction>,
    asOf: LocalDate,
): Map<String, Transaction> {
    data class CandidateEdge(val occurrenceKey: String, val transaction: Transaction)

    val settledEvidence = evidence.filter { it.date <= asOf }

    val pending = occurrences
        .flatMap { (rule, dueDate) ->
            val windowStart = dueDate.minus(DatePeriod(days = PAID_WINDOW_BACK_DAYS))
            val windowEnd = dueDate.plus(DatePeriod(days = PAID_WINDOW_FORWARD_DAYS))
            settledEvidence
                .filter { isCompatibleCharge(rule, tx = it, windowStart = windowStart, windowEnd = windowEnd) }
                .map { CandidateEdge(occurrenceKey = occurrenceKey(rule, dueDate), transaction = it) }
        }

    val bound = LinkedHashMap<String, Transaction>()
    var contested = pending
    while (contested.isNotEmpty()) {
        val perOccurrence = contested.groupBy { it.occurrenceKey }
        val perTransaction = contested.groupBy { it.transaction.id }
        val unambiguous = contested.filter { edge ->
            perOccurrence.getValue(edge.occurrenceKey).size == 1 &&
                perTransaction.getValue(edge.transaction.id).size == 1
        }
        if (unambiguous.isEmpty()) break
        val boundKeys = unambiguous.map { it.occurrenceKey }.toSet()
        val boundTransactionIds = unambiguous.map { it.transaction.id }.toSet()
        unambiguous.forEach { edge -> bound[edge.occurrenceKey] = edge.transaction }
        contested = contested.filter { it.occurrenceKey !in boundKeys && it.transaction.id !in boundTransactionIds }
    }
    return bound
}

private fun isCompatibleCharge(
    rule: RecurringRule,
    tx: Transaction,
    windowStart: LocalDate,
    windowEnd: LocalDate,
): Boolean {
    val ruleKey = specificMerchantKey(merchantNormalized = rule.merchant, merchant = rule.merchant) ?: return false
    val txKey = specificMerchantKey(merchantNormalized = tx.merchantNormalized, merchant = tx.merchant) ?: return false
    if (ruleKey != txKey) return false
    if (tx.type != rule.type) return false
    if (tx.accountId != rule.accountId) return false
    if (tx.amount.currency != rule.amount.currency) return false
    if (!amountWithinVariation(tx.amount.amount, rule.amount.amount)) return false
    return tx.date >= windowStart && tx.date <= windowEnd
}

private fun amountWithinVariation(observed: BigDecimal, expected: BigDecimal): Boolean {
    if (expected.signum() <= 0) return observed.compareTo(expected) == 0
    val deviation = observed.subtract(expected).abs()
    return deviation.compareTo(expected.multiply(MAX_AMOUNT_VARIATION)) <= 0
}

internal const val PAID_WINDOW_BACK_DAYS = 3
internal const val PAID_WINDOW_FORWARD_DAYS = 10
private val MAX_AMOUNT_VARIATION = BigDecimal("0.10")
