package com.athar.core.data.repo

import com.athar.core.data.db.dao.IngestTransactionWrite
import com.athar.core.data.db.dao.TransactionDao
import com.athar.core.data.db.entity.TransactionEntity
import com.athar.core.data.mapper.toDomain
import com.athar.core.data.rules.LocalCategoryRuleLearner
import com.athar.core.domain.repo.ActivityAction
import com.athar.core.domain.repo.ActivityLogRepository
import com.athar.core.domain.repo.WidgetRefresher
import com.google.common.truth.Truth.assertThat
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.test.runTest
import kotlinx.datetime.Clock
import kotlinx.datetime.Instant
import kotlinx.datetime.LocalDate
import org.junit.jupiter.api.Test

/** Coordination only; native Room tests separately prove the atomic DAO operation. */
class TransactionIngestRepositoryTest {
    private val now = Instant.fromEpochMilliseconds(1)
    private val dao = mockk<TransactionDao>()
    private val activity = mockk<ActivityLogRepository>(relaxed = true)
    private val learner = mockk<LocalCategoryRuleLearner>(relaxed = true)
    private val widgets = mockk<WidgetRefresher>(relaxed = true)
    private val repository = TransactionRepositoryImpl(
        dao, activity, learner, object : Clock { override fun now(): Instant = now }, widgets,
    )

    @Test
    fun `replay returns stable stored identity without another creation or learning`() = runTest {
        val original = entity()
        coEvery { dao.insertIngestIfAbsent(any()) } returns IngestTransactionWrite(original, inserted = false)
        val id = repository.upsertIngest(original.copy(id = "fresh-replay", amountMinor = 99999L).toDomain())
        assertThat(id).isEqualTo(original.id)
        coVerify(exactly = 0) { activity.record(any()) }
        coVerify(exactly = 0) { learner.maybeLearnFrom(any()) }
        verify(exactly = 0) { widgets.requestRefresh() }
        coVerify(exactly = 0) { dao.upsert(any()) }
    }

    @Test
    fun `new ingest records one creation for the actual stored identity without learning`() = runTest {
        val stored = entity()
        coEvery { dao.insertIngestIfAbsent(any()) } returns IngestTransactionWrite(stored, inserted = true)
        assertThat(repository.upsertIngest(stored.toDomain())).isEqualTo(stored.id)
        coVerify(exactly = 1) {
            activity.record(match { it.entityId == stored.id && it.action == ActivityAction.CREATE })
        }
        coVerify(exactly = 0) { learner.maybeLearnFrom(any()) }
        verify(exactly = 1) { widgets.requestRefresh() }
    }

    private fun entity() = TransactionEntity(
        id = "stored-owner", accountId = "account", type = "EXPENSE", amountMinor = 101L, currency = "SAR",
        date = LocalDate(2026, 10, 21), occurredAt = null, merchant = "Synthetic", merchantNormalized = "synthetic",
        categoryId = null, notes = "User note", source = "SMS", sourceRefId = "source-owned", status = "DISMISSED",
        confidence = null, createdAt = now, updatedAt = now, categorySource = "USER",
    )
}
