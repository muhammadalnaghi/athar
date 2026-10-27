package com.athar.feature.plan

import com.athar.core.common.money.Money
import com.athar.core.domain.model.Cadence
import com.athar.core.domain.model.IngestSource
import com.athar.core.domain.model.RecurringRule
import com.athar.core.domain.model.Transaction
import com.athar.core.domain.model.TxStatus
import com.athar.core.domain.model.TxType
import com.google.common.truth.Truth.assertThat
import kotlinx.datetime.Instant
import kotlinx.datetime.LocalDate
import org.junit.jupiter.api.Test

/**
 * Matcher-level tests for the as-of bound and the bounded guard universe. VM-level
 * behavior lives in [BillsViewModelTest]; these pin the shared matching invariants
 * directly.
 */
class BillsMatchingTest {

    private val today = LocalDate(2026, 6, 15)

    @Test
    fun `evidence dated after asOf never settles an occurrence`() {
        val rule = ruleFixture(dayOfMonth = 10, nextRunDate = LocalDate(2026, 6, 10))
        val occurrence = listOf(rule to LocalDate(2026, 6, 10))

        val matches = resolveObservedMatches(
            occurrences = occurrence,
            evidence = listOf(
                txFixture(id = "tx-past", date = LocalDate(2026, 6, 12)),
                txFixture(id = "tx-future", date = LocalDate(2026, 6, 20)),
            ),
            asOf = today,
        )

        // The past charge binds; the future-dated one does not exist as of today, even
        // though it sits inside the occurrence's payment window.
        assertThat(matches).hasSize(1)
        assertThat(matches.values.single().id).isEqualTo("tx-past")

        assertThat(
            resolveObservedMatches(
                occurrences = occurrence,
                evidence = listOf(txFixture(id = "tx-future", date = LocalDate(2026, 6, 20))),
                asOf = today,
            ),
        ).isEmpty()

        // An early payment already on record as of today may settle a later occurrence.
        val early = resolveObservedMatches(
            occurrences = listOf(rule to LocalDate(2026, 6, 17)),
            evidence = listOf(txFixture(id = "tx-today", date = today)),
            asOf = today,
        )
        assertThat(early).hasSize(1)
        assertThat(early.values.single().id).isEqualTo("tx-today")
    }

    @Test
    fun `guard universe unions and dedupes ui and hidden occurrences`() {
        val rule = ruleFixture(
            id = "rule-weekly-guard",
            cadence = Cadence.WEEKLY,
            dayOfMonth = null,
            dayOfWeek = 1,
            nextRunDate = LocalDate(2026, 6, 15),
            createdAt = Instant.parse("2024-01-01T00:00:00Z"),
        )

        val universe = buildMatchingUniverse(rules = listOf(rule), today = today, uiHorizonDays = 60)

        val uiKeys = universe.uiOccurrences.map { (r, d) -> occurrenceKey(r, d) }
        val matchKeys = universe.matchOccurrences.map { (r, d) -> occurrenceKey(r, d) }

        // The union is keyed by occurrenceKey: no duplicates, every UI node present.
        assertThat(matchKeys).containsNoDuplicates()
        assertThat(matchKeys).containsAtLeastElementsIn(uiKeys)
        assertThat(universe.matchOccurrences.size).isGreaterThan(universe.uiOccurrences.size)

        // Guard coverage is separate from UI coverage and reaches past the UI walk.
        assertThat(universe.matchGuardWindowStart).isEqualTo(LocalDate(2026, 2, 15))
        assertThat(universe.matchGuardComplete).isTrue()
        // Hidden pre-anchor history joins the graph: last Monday exists for matching even
        // though the UI walk started at the rule's anchor.
        assertThat(matchKeys).contains("recurring:rule-weekly-guard:2026-06-08")
    }

    @Test
    fun `clamped implicit monthly anchor keeps the true 31st grid in ui and guard walks`() {
        val rule = ruleFixture(
            id = "rule-monthly-31",
            dayOfMonth = null, // implicit anchor: the effective 31st comes from nextRunDate = May 31
            nextRunDate = LocalDate(2026, 5, 31),
            createdAt = Instant.parse("2024-01-01T00:00:00Z"),
        )

        val universe = buildMatchingUniverse(rules = listOf(rule), today = today, uiHorizonDays = 60)
        val matchKeys = universe.matchOccurrences.map { (r, d) -> occurrenceKey(r, d) }
        val uiKeys = universe.uiOccurrences.map { (r, d) -> occurrenceKey(r, d) }

        // The guard seed lands on a clamped grid point (Feb 28), but the re-anchored
        // copy must keep the anchor's effective 31st: Mar 31 / Apr 30 / May 31 / Jul 31
        // — never the seed's day 28, and no day-30 drift into July.
        assertThat(matchKeys).contains("recurring:rule-monthly-31:2026-03-31")
        assertThat(matchKeys).contains("recurring:rule-monthly-31:2026-04-30")
        assertThat(matchKeys).contains("recurring:rule-monthly-31:2026-05-31")
        assertThat(matchKeys).contains("recurring:rule-monthly-31:2026-07-31")
        assertThat(matchKeys).containsNoneOf(
            "recurring:rule-monthly-31:2026-03-28",
            "recurring:rule-monthly-31:2026-04-28",
            "recurring:rule-monthly-31:2026-05-28",
            "recurring:rule-monthly-31:2026-07-30",
        )
        assertThat(uiKeys).contains("recurring:rule-monthly-31:2026-07-31")

        // A payment on the true grid settles its occurrence uniquely — the drifted
        // grid would have bound it to a shifted key or left it contested.
        val matches = resolveObservedMatches(
            occurrences = universe.matchOccurrences,
            evidence = listOf(txFixture(id = "tx-jul-31", date = LocalDate(2026, 7, 31))),
            asOf = LocalDate(2026, 8, 1),
        )
        assertThat(matches.keys).containsExactly("recurring:rule-monthly-31:2026-07-31")

        // The guard never truncated even though the walk re-anchored across a clamp.
        assertThat(universe.matchGuardComplete).isTrue()
    }

    @Test
    fun `explicit quarterly day survives an already clamped next run date`() {
        val rule = ruleFixture(
            id = "rule-quarterly-31",
            cadence = Cadence.QUARTERLY,
            dayOfMonth = 31, // Original day remains explicitly known despite the Apr-30 cursor.
            nextRunDate = LocalDate(2026, 4, 30),
            createdAt = Instant.parse("2024-01-01T00:00:00Z"),
        )

        val universe = buildMatchingUniverse(rules = listOf(rule), today = today, uiHorizonDays = 60)
        val matchKeys = universe.matchOccurrences.map { (r, d) -> occurrenceKey(r, d) }

        // True grid: Jan 31, Apr 30, Jul 31, Oct 31. The guard seed re-lands on the
        // clamped Apr 30, but the next quarter must be Jul 31 — not Jul 30 inherited
        // from the clamp (the old copy leaked the anchor day and produced both nodes).
        assertThat(matchKeys).contains("recurring:rule-quarterly-31:2026-04-30")
        assertThat(matchKeys).contains("recurring:rule-quarterly-31:2026-07-31")
        assertThat(matchKeys).doesNotContain("recurring:rule-quarterly-31:2026-07-30")

        val matches = resolveObservedMatches(
            occurrences = universe.matchOccurrences,
            evidence = listOf(txFixture(id = "tx-jul-31-q", date = LocalDate(2026, 7, 31))),
            asOf = LocalDate(2026, 8, 1),
        )
        assertThat(matches.keys).containsExactly("recurring:rule-quarterly-31:2026-07-31")
        assertThat(universe.matchGuardComplete).isTrue()
    }

    @Test
    fun `explicit leap day survives an already clamped yearly next run date`() {
        val rule = ruleFixture(
            id = "rule-yearly-feb29",
            cadence = Cadence.YEARLY,
            dayOfMonth = 29, // Original leap day is explicit; it cannot be inferred from Feb 28.
            nextRunDate = LocalDate(2026, 2, 28),
            createdAt = Instant.parse("2024-01-01T00:00:00Z"),
        )
        val january2028 = LocalDate(2028, 1, 15)

        val universe = buildMatchingUniverse(rules = listOf(rule), today = january2028, uiHorizonDays = 60)
        val matchKeys = universe.matchOccurrences.map { (r, d) -> occurrenceKey(r, d) }

        // True grid: …, Feb 28 2026 (clamped), Feb 28 2027, Feb 29 2028. A clamped
        // anchor must not drag every later year down to the 28th — 2028 is a leap year.
        assertThat(matchKeys).contains("recurring:rule-yearly-feb29:2028-02-29")
        assertThat(matchKeys).doesNotContain("recurring:rule-yearly-feb29:2028-02-28")

        val matches = resolveObservedMatches(
            occurrences = universe.matchOccurrences,
            evidence = listOf(txFixture(id = "tx-feb-29", date = LocalDate(2028, 2, 29))),
            asOf = LocalDate(2028, 3, 1),
        )
        assertThat(matches.keys).containsExactly("recurring:rule-yearly-feb29:2028-02-29")
        assertThat(universe.matchGuardComplete).isTrue()
    }

    @Test
    fun `already clamped anchors without original day evidence never invent a later day`() {
        val quarterly = ruleFixture(
            id = "rule-quarterly-unknown",
            cadence = Cadence.QUARTERLY,
            dayOfMonth = null,
            nextRunDate = LocalDate(2026, 4, 30),
            createdAt = Instant.parse("2024-01-01T00:00:00Z"),
        )
        val quarterlyKeys = buildMatchingUniverse(listOf(quarterly), today).matchOccurrences
            .map { (rule, date) -> occurrenceKey(rule, date) }
        assertThat(quarterlyKeys).contains("recurring:rule-quarterly-unknown:2026-07-30")
        assertThat(quarterlyKeys).doesNotContain("recurring:rule-quarterly-unknown:2026-07-31")

        val yearly = ruleFixture(
            id = "rule-yearly-unknown",
            cadence = Cadence.YEARLY,
            dayOfMonth = null,
            nextRunDate = LocalDate(2026, 2, 28),
            createdAt = Instant.parse("2024-01-01T00:00:00Z"),
        )
        val yearlyKeys = buildMatchingUniverse(listOf(yearly), LocalDate(2028, 1, 15)).matchOccurrences
            .map { (rule, date) -> occurrenceKey(rule, date) }
        assertThat(yearlyKeys).contains("recurring:rule-yearly-unknown:2028-02-28")
        assertThat(yearlyKeys).doesNotContain("recurring:rule-yearly-unknown:2028-02-29")
    }

    @Test
    fun `a charge competing with a hidden guard occurrence settles neither`() {
        val rule = ruleFixture(
            id = "rule-weekly-amb",
            cadence = Cadence.WEEKLY,
            dayOfMonth = null,
            dayOfWeek = 1,
            nextRunDate = LocalDate(2026, 6, 15),
            createdAt = Instant.parse("2024-01-01T00:00:00Z"),
        )
        val universe = buildMatchingUniverse(rules = listOf(rule), today = today, uiHorizonDays = 60)
        assertThat(universe.matchOccurrences.map { (r, d) -> occurrenceKey(r, d) })
            .contains("recurring:rule-weekly-amb:2026-06-08")

        // Jun 14 sits inside the Jun 8 (guard-only, hidden from the UI walk) and the
        // Jun 15 payment windows at once: the mutual-uniqueness fixpoint binds neither,
        // so an incomplete-looking UI list can never manufacture a false paid.
        val matches = resolveObservedMatches(
            occurrences = universe.matchOccurrences,
            evidence = listOf(txFixture(id = "tx-contested", date = LocalDate(2026, 6, 14))),
            asOf = today,
        )

        assertThat(matches).isEmpty()
    }

    private fun ruleFixture(
        id: String = "rule-gym",
        cadence: Cadence = Cadence.MONTHLY,
        dayOfMonth: Int? = 10,
        dayOfWeek: Int? = null,
        nextRunDate: LocalDate,
        createdAt: Instant = Instant.parse("2026-06-01T12:00:00Z"),
    ): RecurringRule {
        val now = Instant.parse("2026-06-01T12:00:00Z")
        return RecurringRule(
            id = id,
            displayName = "Gym Club",
            merchant = "Gym Club",
            amount = Money.ofMinor(9900, "SAR"),
            type = TxType.EXPENSE,
            accountId = "acc-checking",
            categoryId = "cat-gym",
            cadence = cadence,
            dayOfMonth = dayOfMonth,
            dayOfWeek = dayOfWeek,
            monthOfYear = null,
            nextRunDate = nextRunDate,
            lastRunDate = null,
            isActive = true,
            notes = null,
            createdAt = createdAt,
            updatedAt = now,
        )
    }

    private fun txFixture(
        id: String,
        date: LocalDate,
    ): Transaction =
        Transaction(
            id = id,
            accountId = "acc-checking",
            type = TxType.EXPENSE,
            amount = Money.ofMinor(9900, "SAR"),
            date = date,
            occurredAt = null,
            merchant = "Gym Club",
            merchantNormalized = "gym club",
            categoryId = "cat-gym",
            notes = null,
            source = IngestSource.MANUAL,
            sourceRefId = null,
            status = TxStatus.CONFIRMED,
            confidence = 1f,
            createdAt = Instant.parse("2026-01-01T00:00:00Z"),
            updatedAt = Instant.parse("2026-01-01T00:00:00Z"),
        )
}
