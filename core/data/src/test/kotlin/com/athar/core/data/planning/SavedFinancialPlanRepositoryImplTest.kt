package com.athar.core.data.planning

import com.athar.core.domain.repo.SavedFinancialPlan
import com.athar.core.domain.repo.SavedFinancialPlanConflictException
import com.athar.core.domain.repo.SavedFinancialPlanKind
import com.athar.core.domain.repo.SavedFinancialPlanValidationException
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.test.runTest
import kotlinx.datetime.Clock
import kotlinx.datetime.Instant
import org.junit.jupiter.api.Test

/**
 * JVM regression for the WM028 fast-store saved-input repository. The fake DAO
 * mirrors the real SQL semantics statement-for-statement (ABORT insert,
 * revision-guarded UPDATE/DELETE returning affected-row counts) so the
 * repository's refusal/conflict decisions are exercised without a device.
 */
class SavedFinancialPlanRepositoryImplTest {

    private class FakeClock(startMillis: Long) : Clock {
        var nowMillis = startMillis
        override fun now(): Instant = Instant.fromEpochMilliseconds(nowMillis)
    }

    private class FakeSavedFinancialPlanDao : SavedFinancialPlanDao {
        val rows = LinkedHashMap<String, SavedFinancialPlanEntity>()
        var writeCount = 0
        private val changed = MutableStateFlow(0)

        override fun observeByKind(kind: String): Flow<List<SavedFinancialPlanEntity>> =
            changed.map {
                rows.values
                    .filter { it.kind == kind }
                    .sortedWith(
                        compareByDescending<SavedFinancialPlanEntity> { it.updatedAtEpochMillis }.thenBy { it.id },
                    )
            }

        override suspend fun getById(id: String): SavedFinancialPlanEntity? = rows[id]

        override suspend fun insert(entity: SavedFinancialPlanEntity) {
            writeCount++
            check(!rows.containsKey(entity.id)) { "UNIQUE constraint failed: saved_financial_plan.id" }
            rows[entity.id] = entity
            changed.value++
        }

        override suspend fun updateIfRevisionMatches(
            id: String,
            title: String,
            currency: String,
            payloadVersion: Int,
            payloadJson: String,
            expectedRevision: Long,
            newRevision: Long,
            updatedAtEpochMillis: Long,
        ): Int {
            writeCount++
            val current = rows[id] ?: return 0
            if (current.revision != expectedRevision) return 0
            rows[id] = current.copy(
                title = title,
                currency = currency,
                payloadVersion = payloadVersion,
                payloadJson = payloadJson,
                revision = newRevision,
                updatedAtEpochMillis = updatedAtEpochMillis,
            )
            changed.value++
            return 1
        }

        override suspend fun deleteIfRevisionMatches(id: String, expectedRevision: Long): Int {
            writeCount++
            val current = rows[id] ?: return 0
            if (current.revision != expectedRevision) return 0
            rows.remove(id)
            changed.value++
            return 1
        }

        override suspend fun all(): List<SavedFinancialPlanEntity> = rows.values.toList()

        override suspend fun count(): Long = rows.size.toLong()

        override suspend fun clear() {
            writeCount++
            rows.clear()
            changed.value++
        }

        override suspend fun insertAll(entities: List<SavedFinancialPlanEntity>) {
            writeCount++
            entities.forEach { rows[it.id] = it }
            changed.value++
        }
    }

    private var clock = FakeClock(1_760_000_000_000)
    private val dao = FakeSavedFinancialPlanDao()

    /** Direct execution emulates the real Room transaction's statement order. */
    private val directTx = object : SavedFinancialPlanTransactionRunner {
        override suspend fun <T> inTransaction(block: suspend () -> T): T = block()
    }

    private val repo = SavedFinancialPlanRepositoryImpl(dao, clock, directTx, JvmSavedFinancialPlanPayloadGuard)

    private fun plan(
        id: String = "plan-1",
        kind: SavedFinancialPlanKind = SavedFinancialPlanKind.GOAL,
        title: String = "صندوق الطوارئ",
        currency: String = "SAR",
        payloadVersion: Int = 1,
        payloadJson: String = """{"monthlyAmount":"12.500","years":3}""",
        revision: Long = 0,
    ): SavedFinancialPlan = SavedFinancialPlan(
        id = id,
        kind = kind,
        title = title,
        currency = currency,
        payloadVersion = payloadVersion,
        payloadJson = payloadJson,
        revision = revision,
        createdAtEpochMillis = 0,
        updatedAtEpochMillis = 0,
    )

    @Test
    fun `create-only save stores revision 1 and ignores caller revision and timestamps`() = runTest {
        val saved = repo.save(plan(revision = 999), expectedRevision = null)

        assertThat(saved.revision).isEqualTo(1)
        assertThat(saved.createdAtEpochMillis).isEqualTo(clock.nowMillis)
        assertThat(saved.updatedAtEpochMillis).isEqualTo(clock.nowMillis)
        assertThat(saved.kind).isEqualTo(SavedFinancialPlanKind.GOAL)
        val stored = dao.rows.getValue("plan-1")
        assertThat(stored.revision).isEqualTo(1)
        assertThat(stored.createdAtEpochMillis).isEqualTo(clock.nowMillis)
    }

    @Test
    fun `duplicate id on create-only save conflicts without changing storage`() = runTest {
        repo.save(plan(), expectedRevision = null)
        val before = dao.rows.getValue("plan-1")

        val outcome = runCatching { repo.save(plan(title = "overwrite"), expectedRevision = null) }

        assertThat(outcome.exceptionOrNull()).isInstanceOf(SavedFinancialPlanConflictException::class.java)
        assertThat(dao.rows.getValue("plan-1")).isEqualTo(before)
    }

    @Test
    fun `update with expected revision on a missing id conflicts`() = runTest {
        val outcome = runCatching { repo.save(plan(), expectedRevision = 3) }

        assertThat(outcome.exceptionOrNull()).isInstanceOf(SavedFinancialPlanConflictException::class.java)
        assertThat(dao.rows).isEmpty()
    }

    @Test
    fun `exact-revision update bumps revision and preserves identity and creation time`() = runTest {
        val created = repo.save(plan(currency = "USD"), expectedRevision = null)
        val createdAt = created.createdAtEpochMillis
        clock.nowMillis += 5_000

        val updated = repo.save(
            plan(
                currency = "USD",
                title = "تحديث",
                payloadJson = """{"monthlyAmount":"340.750"}""",
                revision = 42,
            ),
            expectedRevision = created.revision,
        )

        assertThat(updated.revision).isEqualTo(2)
        assertThat(updated.createdAtEpochMillis).isEqualTo(createdAt)
        assertThat(updated.updatedAtEpochMillis).isEqualTo(clock.nowMillis)
        assertThat(updated.title).isEqualTo("تحديث")
        assertThat(updated.payloadJson).isEqualTo("""{"monthlyAmount":"340.750"}""")
        assertThat(updated.currency).isEqualTo("USD")
    }

    @Test
    fun `stale revision update refuses and leaves stored document untouched`() = runTest {
        val created = repo.save(plan(), expectedRevision = null)

        val outcome = runCatching { repo.save(plan(title = "متأخر"), expectedRevision = created.revision - 1) }

        assertThat(outcome.exceptionOrNull()).isInstanceOf(SavedFinancialPlanConflictException::class.java)
        val stored = dao.rows.getValue("plan-1")
        assertThat(stored.revision).isEqualTo(1)
        assertThat(stored.title).isEqualTo("صندوق الطوارئ")
    }

    @Test
    fun `kind change on update is refused as validation without any write`() = runTest {
        val created = repo.save(plan(), expectedRevision = null)
        val writesBefore = dao.writeCount

        val outcome = runCatching {
            repo.save(plan(kind = SavedFinancialPlanKind.CASH), expectedRevision = created.revision)
        }

        assertThat(outcome.exceptionOrNull()).isInstanceOf(SavedFinancialPlanValidationException::class.java)
        assertThat(dao.writeCount).isEqualTo(writesBefore)
        assertThat(dao.rows.getValue("plan-1").kind).isEqualTo("GOAL")
    }

    @Test
    fun `revision overflow refuses before any write`() = runTest {
        repo.save(plan(), expectedRevision = null)
        dao.rows["plan-1"] = dao.rows.getValue("plan-1").copy(revision = Long.MAX_VALUE)
        val writesBefore = dao.writeCount

        val outcome = runCatching { repo.save(plan(), expectedRevision = Long.MAX_VALUE) }

        assertThat(outcome.exceptionOrNull()).isInstanceOf(SavedFinancialPlanValidationException::class.java)
        assertThat(dao.writeCount).isEqualTo(writesBefore)
        assertThat(dao.rows.getValue("plan-1").revision).isEqualTo(Long.MAX_VALUE)
    }

    @Test
    fun `delete requires the exact revision and stale attempts keep the row`() = runTest {
        val created = repo.save(plan(), expectedRevision = null)

        val stale = runCatching { repo.delete("plan-1", created.revision - 1) }
        assertThat(stale.exceptionOrNull()).isInstanceOf(SavedFinancialPlanConflictException::class.java)
        assertThat(dao.rows).containsKey("plan-1")

        repo.delete("plan-1", created.revision)
        assertThat(dao.rows).doesNotContainKey("plan-1")
    }

    @Test
    fun `bounded and malformed documents are refused before any write`() = runTest {
        val invalid = listOf(
            plan(id = " "),
            plan(title = ""),
            plan(title = "x".repeat(201)),
            plan(currency = ""),
            plan(currency = "x".repeat(17)),
            plan(payloadVersion = 0),
            plan(payloadJson = ""),
            plan(payloadJson = "{not-json"),
            plan(payloadJson = "\"".repeat(262_145)),
            plan(revision = -1),
        )

        invalid.forEach { candidate ->
            val outcome = runCatching { repo.save(candidate, expectedRevision = null) }
            assertThat(outcome.exceptionOrNull())
                .isInstanceOf(SavedFinancialPlanValidationException::class.java)
        }
        assertThat(dao.writeCount).isEqualTo(0)
        assertThat(dao.rows).isEmpty()
    }

    @Test
    fun `literal currency and original decimal strings survive unchanged`() = runTest {
        val document = """{"target":"17500.000","rate":"3.7500","note":"ر.س"}"""
        val saved = repo.save(
            plan(currency = "ر.س", payloadJson = document),
            expectedRevision = null,
        )

        val reloaded = repo.get("plan-1")
        assertThat(reloaded).isEqualTo(saved)
        assertThat(reloaded?.currency).isEqualTo("ر.س")
        assertThat(reloaded?.payloadJson).isEqualTo(document)
    }

    @Test
    fun `observe isolates kinds and get returns the persisted snapshot`() = runTest {
        repo.save(plan(id = "goal-1"), expectedRevision = null)
        repo.save(plan(id = "cash-1", kind = SavedFinancialPlanKind.CASH), expectedRevision = null)

        val goals = repo.observe(SavedFinancialPlanKind.GOAL).first()
        val cash = repo.observe(SavedFinancialPlanKind.CASH).first()

        assertThat(goals.map { it.id }).containsExactly("goal-1")
        assertThat(cash.map { it.id }).containsExactly("cash-1")
        assertThat(repo.get("cash-1")?.kind).isEqualTo(SavedFinancialPlanKind.CASH)
        assertThat(repo.get("missing")).isNull()
    }
}
