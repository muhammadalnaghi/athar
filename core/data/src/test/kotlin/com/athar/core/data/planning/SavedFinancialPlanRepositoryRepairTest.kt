package com.athar.core.data.planning

import com.athar.core.domain.repo.SavedFinancialPlan
import com.athar.core.domain.repo.SavedFinancialPlanConflictException
import com.athar.core.domain.repo.SavedFinancialPlanKind
import com.athar.core.domain.repo.SavedFinancialPlanValidationException
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlinx.datetime.Clock
import kotlinx.datetime.Instant
import org.junit.jupiter.api.Test

/**
 * WM028 fast-store repair oracle (behavioral, deterministic): the recovered
 * repository non-transactional precheck -> CAS -> re-read shape is replaced
 * by a real atomic boundary. These cases pin:
 *  - precheck-read, CAS write and the returned object share ONE transaction
 *    (the fake runner serializes transactions exactly like a real Room
 *    transaction serializes concurrent writers);
 *  - the returned object is exactly this successful write - never a separate
 *    post-CAS re-read that could observe another writer commit;
 *  - duplicate concurrent creates resolve to one winner and a typed conflict;
 *  - the payload guard is bounded, object-only and shared by every save.
 */
class SavedFinancialPlanRepositoryRepairTest {

    private class FakeClock(startMillis: Long) : Clock {
        var nowMillis = startMillis
        override fun now(): Instant = Instant.fromEpochMilliseconds(nowMillis)
    }

    /** Mirrors SQL ABORT/CAS semantics with a competitor-commit hook. */
    private class FakeSavedFinancialPlanDao : SavedFinancialPlanDao {
        val rows = LinkedHashMap<String, SavedFinancialPlanEntity>()
        var writeCount = 0
        var afterUpdateHook: (() -> Unit)? = null

        override fun observeByKind(kind: String) =
            throw UnsupportedOperationException("not used in repair tests")

        override suspend fun getById(id: String): SavedFinancialPlanEntity? = rows[id]

        override suspend fun insert(entity: SavedFinancialPlanEntity) {
            writeCount++
            check(!rows.containsKey(entity.id)) {
                "UNIQUE constraint failed: saved_financial_plan.id"
            }
            rows[entity.id] = entity
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
            afterUpdateHook?.invoke()
            return 1
        }

        override suspend fun deleteIfRevisionMatches(id: String, expectedRevision: Long): Int {
            writeCount++
            val current = rows[id] ?: return 0
            if (current.revision != expectedRevision) return 0
            rows.remove(id)
            return 1
        }

        override suspend fun all(): List<SavedFinancialPlanEntity> = rows.values.toList()

        override suspend fun count(): Long = rows.size.toLong()

        override suspend fun clear() {
            rows.clear()
        }

        override suspend fun insertAll(entities: List<SavedFinancialPlanEntity>) {
            entities.forEach { rows[it.id] = it }
        }
    }

    /** Direct execution: statements run in order, no extra atomicity added. */
    private fun directTx() = object : SavedFinancialPlanTransactionRunner {
        override suspend fun <T> inTransaction(block: suspend () -> T): T = block()
    }

    private var clock = FakeClock(1_760_000_000_000)
    private val dao = FakeSavedFinancialPlanDao()
    private val repo = SavedFinancialPlanRepositoryImpl(dao, clock, directTx(), JvmSavedFinancialPlanPayloadGuard)

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
    fun `concurrent create resolves to exactly one winner and a typed conflict`() = runTest {
        // A real Room transaction serializes concurrent writers; the fake
        // runner reproduces exactly that guarantee so the loser in-transaction
        // precheck observes the winner committed row.
        val txLock = Mutex()
        val serializedTx = object : SavedFinancialPlanTransactionRunner {
            override suspend fun <T> inTransaction(block: suspend () -> T): T = txLock.withLock { block() }
        }
        val racing = SavedFinancialPlanRepositoryImpl(dao, clock, serializedTx, JvmSavedFinancialPlanPayloadGuard)
        val scheduler = StandardTestDispatcher(testScheduler)
        val first = async(scheduler) {
            runCatching { racing.save(plan(id = "race", title = "first"), expectedRevision = null) }
        }
        val second = async(scheduler) {
            runCatching { racing.save(plan(id = "race", title = "second"), expectedRevision = null) }
        }
        testScheduler.advanceUntilIdle()

        val results = listOf(first.await(), second.await())
        val winners = results.filter { it.isSuccess }
        val losers = results.filter { it.isFailure }

        assertThat(winners).hasSize(1)
        assertThat(losers).hasSize(1)
        assertThat(losers.single().exceptionOrNull())
            .isInstanceOf(SavedFinancialPlanConflictException::class.java)
        val winner = winners.single().getOrThrow()
        assertThat(winner.revision).isEqualTo(1)
        val stored = dao.rows.getValue("race")
        assertThat(stored.title).isEqualTo(winner.title)
        assertThat(stored.payloadJson).isEqualTo(winner.payloadJson)
        assertThat(dao.writeCount).isEqualTo(1)
    }

    @Test
    fun `update returns exactly the caller write even when another commit lands after`() =
        runTest {
            val created = repo.save(plan(), expectedRevision = null)
            dao.afterUpdateHook = {
                dao.rows["plan-1"] = dao.rows.getValue("plan-1").copy(
                    title = "المنافس",
                    payloadJson = """{"monthlyAmount":"999.999"}""",
                    revision = 99,
                    updatedAtEpochMillis = clock.nowMillis + 999,
                )
            }
            val mine = """{"monthlyAmount":"340.750"}"""

            val updated = repo.save(
                plan(title = "لي", payloadJson = mine),
                expectedRevision = created.revision,
            )

            assertThat(updated.title).isEqualTo("لي")
            assertThat(updated.payloadJson).isEqualTo(mine)
            assertThat(updated.revision).isEqualTo(2)
            assertThat(updated.updatedAtEpochMillis).isEqualTo(clock.nowMillis)
            assertThat(updated.createdAtEpochMillis).isEqualTo(created.createdAtEpochMillis)
            assertThat(updated.kind).isEqualTo(SavedFinancialPlanKind.GOAL)
        }

    @Test
    fun `payload must be a JSON object`() = runTest {
        val nonObjects = listOf(
            """["12.500"]""",
            """"42"""",
            "7",
            "null",
        )

        nonObjects.forEach { candidate ->
            val outcome = runCatching {
                repo.save(plan(payloadJson = candidate), expectedRevision = null)
            }
            assertThat(outcome.exceptionOrNull())
                .isInstanceOf(SavedFinancialPlanValidationException::class.java)
        }
        assertThat(dao.rows).isEmpty()
    }

    @Test
    fun `payload depth token and text budgets are enforced before any write`() = runTest {
        val deepArrays = "[".repeat(30) + "1" + "]".repeat(30)
        val tokenFlood = buildString {
            append("""{"k0":"1"""")
            for (i in 1 until 20_000) append(""","k$i":"1"""")
            append("}")
        }
        val longText = """{"note":"${"x".repeat(5_000)}"}"""
        val candidates = listOf(deepArrays, tokenFlood, longText)

        // The budgets themselves must be the refusing mechanism.
        assertThat(SavedFinancialPlanRepositoryImpl.MAX_PAYLOAD_DEPTH).isLessThan(30)
        assertThat(SavedFinancialPlanRepositoryImpl.MAX_PAYLOAD_MEMBERS).isLessThan(20_000)
        assertThat(SavedFinancialPlanRepositoryImpl.MAX_PAYLOAD_STRING_CHARS).isLessThan(5_000)

        candidates.forEach { candidate ->
            assertThat(candidate.length).isLessThan(SavedFinancialPlanRepositoryImpl.MAX_PAYLOAD_CHARS)
            val outcome = runCatching {
                repo.save(plan(payloadJson = candidate), expectedRevision = null)
            }
            assertThat(outcome.exceptionOrNull())
                .isInstanceOf(SavedFinancialPlanValidationException::class.java)
        }
        assertThat(dao.writeCount).isEqualTo(0)
    }

    @Test
    fun `future positive payload versions stay opaque and storable`() = runTest {
        val saved = repo.save(
            plan(payloadVersion = 97, payloadJson = """{"future":"3.333"}"""),
            expectedRevision = null,
        )

        assertThat(saved.payloadVersion).isEqualTo(97)
        assertThat(dao.rows.getValue("plan-1").payloadJson).isEqualTo("""{"future":"3.333"}""")
    }

    @Test
    fun `validation of the inbound object precedes any Room work on update`() = runTest {
        repo.save(plan(), expectedRevision = null)
        val before = dao.rows.getValue("plan-1")

        val outcome = runCatching {
            repo.save(plan(payloadJson = "[1,2,3]"), expectedRevision = 1)
        }

        assertThat(outcome.exceptionOrNull())
            .isInstanceOf(SavedFinancialPlanValidationException::class.java)
        assertThat(dao.rows.getValue("plan-1")).isEqualTo(before)
    }

    @Test
    fun `cancelled save writes nothing`() = runTest {
        val job = launch(StandardTestDispatcher(testScheduler)) {
            repo.save(plan(), expectedRevision = null)
        }
        job.cancel()
        testScheduler.advanceUntilIdle()

        assertThat(dao.writeCount).isEqualTo(0)
        assertThat(dao.rows).isEmpty()
    }
}
