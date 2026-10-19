package com.athar.core.data.repo

import com.athar.core.data.db.dao.CategoryUsageCount
import com.athar.core.data.db.dao.RecurringRuleDao
import com.athar.core.data.db.dao.TransactionDao
import com.athar.core.data.db.entity.AccountBalanceRow
import com.athar.core.data.db.entity.RecurringRuleEntity
import com.athar.core.data.db.entity.TransactionEntity
import com.athar.core.domain.model.IngestSource
import com.athar.core.domain.model.TxStatus
import com.athar.core.domain.model.TxType
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import kotlinx.datetime.Clock
import kotlinx.datetime.Instant
import kotlinx.datetime.LocalDate
import org.junit.jupiter.api.Test

class RecurringSuggestionRepositoryImplTest {

    @Test
    fun `suggestions include repeated specific merchant history`() = runTest {
        val repo = RecurringSuggestionRepositoryImpl(
            transactionDao = FakeTransactionDao(
                listOf(
                    tx(id = "gym-1", merchant = "Gym Club", amountMinor = 9900, month = 1, day = 5),
                    tx(id = "gym-2", merchant = "Gym Club", amountMinor = 9900, month = 2, day = 6),
                    tx(id = "gym-3", merchant = "Gym Club", amountMinor = 9900, month = 3, day = 5),
                ),
            ),
            ruleDao = FakeRecurringRuleDao(),
            clock = FixedClock,
        )

        val suggestions = repo.observeSuggestions().first()

        assertThat(suggestions).hasSize(1)
        assertThat(suggestions.single().merchantNormalized).isEqualTo("gym club")
        assertThat(suggestions.single().occurrenceCount).isEqualTo(3)
        assertThat(suggestions.single().suggestedAccountId).isEqualTo("manual")
        assertThat(suggestions.single().suggestedCategoryId).isNull()
    }

    @Test
    fun `suggestions carry category when every occurrence has the same category`() = runTest {
        val repo = RecurringSuggestionRepositoryImpl(
            transactionDao = FakeTransactionDao(
                listOf(
                    tx(
                        id = "gym-1",
                        merchant = "Gym Club",
                        amountMinor = 9900,
                        month = 1,
                        day = 5,
                        categoryId = "cat-gym",
                    ),
                    tx(
                        id = "gym-2",
                        merchant = "Gym Club",
                        amountMinor = 9900,
                        month = 2,
                        day = 6,
                        categoryId = "cat-gym",
                    ),
                    tx(
                        id = "gym-3",
                        merchant = "Gym Club",
                        amountMinor = 9900,
                        month = 3,
                        day = 5,
                        categoryId = "cat-gym",
                    ),
                ),
            ),
            ruleDao = FakeRecurringRuleDao(),
            clock = FixedClock,
        )

        val suggestions = repo.observeSuggestions().first()

        assertThat(suggestions).hasSize(1)
        assertThat(suggestions.single().suggestedCategoryId).isEqualTo("cat-gym")
    }

    @Test
    fun `suggestions carry account when every occurrence has the same account`() = runTest {
        val repo = RecurringSuggestionRepositoryImpl(
            transactionDao = FakeTransactionDao(
                listOf(
                    tx(
                        id = "gym-1",
                        merchant = "Gym Club",
                        amountMinor = 9900,
                        month = 1,
                        day = 5,
                        accountId = "acc-checking",
                    ),
                    tx(
                        id = "gym-2",
                        merchant = "Gym Club",
                        amountMinor = 9900,
                        month = 2,
                        day = 6,
                        accountId = "acc-checking",
                    ),
                    tx(
                        id = "gym-3",
                        merchant = "Gym Club",
                        amountMinor = 9900,
                        month = 3,
                        day = 5,
                        accountId = "acc-checking",
                    ),
                ),
            ),
            ruleDao = FakeRecurringRuleDao(),
            clock = FixedClock,
        )

        val suggestions = repo.observeSuggestions().first()

        assertThat(suggestions).hasSize(1)
        assertThat(suggestions.single().suggestedAccountId).isEqualTo("acc-checking")
    }

    @Test
    fun `suggestions omit account when occurrence accounts conflict`() = runTest {
        val repo = RecurringSuggestionRepositoryImpl(
            transactionDao = FakeTransactionDao(
                listOf(
                    tx(
                        id = "gym-1",
                        merchant = "Gym Club",
                        amountMinor = 9900,
                        month = 1,
                        day = 5,
                        accountId = "acc-checking",
                    ),
                    tx(
                        id = "gym-2",
                        merchant = "Gym Club",
                        amountMinor = 9900,
                        month = 2,
                        day = 6,
                        accountId = "acc-credit",
                    ),
                    tx(
                        id = "gym-3",
                        merchant = "Gym Club",
                        amountMinor = 9900,
                        month = 3,
                        day = 5,
                        accountId = "acc-checking",
                    ),
                ),
            ),
            ruleDao = FakeRecurringRuleDao(),
            clock = FixedClock,
        )

        val suggestions = repo.observeSuggestions().first()

        assertThat(suggestions).hasSize(1)
        assertThat(suggestions.single().suggestedAccountId).isNull()
    }

    @Test
    fun `suggestions omit category when occurrence categories conflict or are incomplete`() = runTest {
        val repo = RecurringSuggestionRepositoryImpl(
            transactionDao = FakeTransactionDao(
                listOf(
                    tx(
                        id = "gym-1",
                        merchant = "Gym Club",
                        amountMinor = 9900,
                        month = 1,
                        day = 5,
                        categoryId = "cat-gym",
                    ),
                    tx(
                        id = "gym-2",
                        merchant = "Gym Club",
                        amountMinor = 9900,
                        month = 2,
                        day = 6,
                        categoryId = "cat-health",
                    ),
                    tx(
                        id = "gym-3",
                        merchant = "Gym Club",
                        amountMinor = 9900,
                        month = 3,
                        day = 5,
                        categoryId = "cat-gym",
                    ),
                    tx(
                        id = "cloud-1",
                        merchant = "Cloud App",
                        amountMinor = 3900,
                        month = 1,
                        day = 11,
                        categoryId = "cat-software",
                    ),
                    tx(id = "cloud-2", merchant = "Cloud App", amountMinor = 3900, month = 2, day = 12),
                    tx(
                        id = "cloud-3",
                        merchant = "Cloud App",
                        amountMinor = 3900,
                        month = 3,
                        day = 11,
                        categoryId = "cat-software",
                    ),
                ),
            ),
            ruleDao = FakeRecurringRuleDao(),
            clock = FixedClock,
        )

        val suggestions = repo.observeSuggestions().first()
        val byMerchant = suggestions.associateBy { it.merchantNormalized }

        assertThat(suggestions.map { it.merchantNormalized }).containsAtLeast("gym club", "cloud app")
        assertThat(byMerchant.getValue("gym club").suggestedCategoryId).isNull()
        assertThat(byMerchant.getValue("cloud app").suggestedCategoryId).isNull()
    }

    @Test
    fun `suggestions ignore generic merchant labels`() = runTest {
        val repo = RecurringSuggestionRepositoryImpl(
            transactionDao = FakeTransactionDao(
                listOf(
                    tx(id = "payment-1", merchant = "Payment", amountMinor = 4200, month = 1, day = 10),
                    tx(id = "payment-2", merchant = "Payment", amountMinor = 4200, month = 2, day = 10),
                    tx(id = "payment-3", merchant = "Payment", amountMinor = 4200, month = 3, day = 10),
                    tx(id = "cash-1", merchant = "كاش", amountMinor = 1200, month = 1, day = 12),
                    tx(id = "cash-2", merchant = "كاش", amountMinor = 1200, month = 2, day = 12),
                    tx(id = "cash-3", merchant = "كاش", amountMinor = 1200, month = 3, day = 12),
                ),
            ),
            ruleDao = FakeRecurringRuleDao(),
            clock = FixedClock,
        )

        assertThat(repo.observeSuggestions().first()).isEmpty()
    }
}

private class FakeTransactionDao(
    private val rows: List<TransactionEntity>,
) : TransactionDao {
    override fun observeByPeriod(start: LocalDate, endExclusive: LocalDate, status: String?): Flow<List<TransactionEntity>> =
        flowOf(emptyList())

    override fun observePending(): Flow<List<TransactionEntity>> = flowOf(emptyList())

    override suspend fun get(id: String): TransactionEntity? = rows.firstOrNull { it.id == id }

    override suspend fun all(): List<TransactionEntity> = rows

    override fun observeAll(): Flow<List<TransactionEntity>> = flowOf(rows)

    override fun observeConfirmedSince(since: LocalDate): Flow<List<TransactionEntity>> =
        flowOf(rows.filter { it.status == TxStatus.CONFIRMED.name && it.date >= since })

    override suspend fun confirmedCategoryCountsForMerchant(merchantNormalized: String): List<CategoryUsageCount> =
        emptyList()

    override suspend fun firstId(): String? = rows.firstOrNull()?.id

    override suspend fun clear() = Unit

    override suspend fun clearPending(): Int = 0

    override suspend fun confirmAllConfident(minConfidence: Float, now: Instant): Int = 0

    override suspend fun dismissAllLowConfidence(maxConfidence: Float, now: Instant): Int = 0

    override suspend fun dismissAllPending(now: Instant): Int = 0

    override suspend fun recoverDismissedToPending(now: Instant): Int = 0

    override suspend fun applyCategoryToMatching(pattern: String, categoryId: String, now: Instant): Int = 0

    override suspend fun upsert(entity: TransactionEntity) = Unit

    override suspend fun delete(id: String) = Unit

    override suspend fun setStatus(id: String, status: String, now: Instant) = Unit

    override fun observeBalancesByAccount(): Flow<List<AccountBalanceRow>> = flowOf(emptyList())
}

private class FakeRecurringRuleDao(
    private val rows: List<RecurringRuleEntity> = emptyList(),
) : RecurringRuleDao {
    override fun observeAll(): Flow<List<RecurringRuleEntity>> = flowOf(rows)

    override fun observeActive(): Flow<List<RecurringRuleEntity>> = flowOf(rows.filter { it.isActive })

    override suspend fun get(id: String): RecurringRuleEntity? = rows.firstOrNull { it.id == id }

    override suspend fun dueOn(today: String): List<RecurringRuleEntity> =
        rows.filter { it.isActive && it.nextRunDate <= today }

    override suspend fun upsert(entity: RecurringRuleEntity) = Unit

    override suspend fun setActive(id: String, active: Boolean, ts: Long) = Unit

    override suspend fun delete(id: String) = Unit

    override suspend fun clear() = Unit
}

private object FixedClock : Clock {
    override fun now(): Instant = Instant.parse("2026-06-15T12:00:00Z")
}

private fun tx(
    id: String,
    merchant: String,
    amountMinor: Long,
    month: Int,
    day: Int,
    accountId: String = "manual",
    categoryId: String? = null,
): TransactionEntity =
    TransactionEntity(
        id = id,
        accountId = accountId,
        type = TxType.EXPENSE.name,
        amountMinor = amountMinor,
        currency = "SAR",
        date = LocalDate(2026, month, day),
        occurredAt = Instant.parse("2026-${month.toString().padStart(2, '0')}-${day.toString().padStart(2, '0')}T12:00:00Z"),
        merchant = merchant,
        merchantNormalized = merchant.lowercase().trim(),
        categoryId = categoryId,
        notes = null,
        source = IngestSource.MANUAL.name,
        sourceRefId = null,
        status = TxStatus.CONFIRMED.name,
        confidence = 1f,
        createdAt = Instant.parse("2026-01-01T00:00:00Z"),
        updatedAt = Instant.parse("2026-01-01T00:00:00Z"),
        categorySource = null,
    )
