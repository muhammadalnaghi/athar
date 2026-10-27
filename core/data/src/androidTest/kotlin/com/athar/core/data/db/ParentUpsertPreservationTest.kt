package com.athar.core.data.db

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.athar.core.common.money.Money
import com.athar.core.data.db.entity.AccountEntity
import com.athar.core.data.db.entity.CategoryEntity
import com.athar.core.data.db.entity.CategoryRuleEntity
import com.athar.core.data.db.entity.TransactionEntity
import com.athar.core.data.db.entity.TransactionReceiptEntity
import com.athar.core.data.repo.InvestmentRepositoryImpl
import com.athar.core.domain.model.InvestmentContribution
import com.athar.core.domain.model.InvestmentPool
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.datetime.Instant
import kotlinx.datetime.LocalDate
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/** Real production Room/DAO semantics; only this harness's synthetic database is opened. */
@RunWith(AndroidJUnit4::class)
class ParentUpsertPreservationTest {
    private lateinit var db: AtharDatabase
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private val name = "athar-wm028-parent-upsert-synthetic"
    private val now = Instant.fromEpochMilliseconds(1)

    @Before
    fun open() {
        context.deleteDatabase(name)
        db = atharDatabaseBuilder(context, name).build()
    }

    @After
    fun close() {
        db.close()
        context.deleteDatabase(name)
    }

    @Test
    fun poolReturnAndMetadataUpdatePreserveBothContributions() = runBlocking {
        val repo = InvestmentRepositoryImpl(db.investmentDao())
        val pool = InvestmentPool(id = "pool", name = "Synthetic pool", period = "2026-10", totalReturn = Money.of("100"))
        repo.upsertPool(pool)
        repo.upsertContribution(InvestmentContribution(id = "a", poolId = "pool", ownerName = "A", amount = Money.of("300")))
        repo.upsertContribution(InvestmentContribution(id = "b", poolId = "pool", ownerName = "B", amount = Money.of("100", "USD")))
        val before = db.investmentDao().allContributions().sortedBy { it.id }
        assertThat(before).hasSize(2)
        repo.upsertPool(pool.copy(name = "Renamed pool", totalReturn = Money.of("120.04")))
        assertThat(db.investmentDao().allContributions().sortedBy { it.id }).isEqualTo(before)
        assertThat(db.investmentDao().allPools().single().totalReturnMinor).isEqualTo(12004L)
        assertThat(db.investmentDao().allPools().single().name).isEqualTo("Renamed pool")
    }

    @Test
    fun explicitPoolDeletionStillCascades() = runBlocking {
        val repo = InvestmentRepositoryImpl(db.investmentDao())
        repo.upsertPool(InvestmentPool(id = "pool", name = "Synthetic", period = "Manual", totalReturn = Money.zero()))
        repo.upsertContribution(InvestmentContribution(id = "a", poolId = "pool", ownerName = "A", amount = Money.of("1")))
        assertThat(db.investmentDao().allContributions()).hasSize(1)
        repo.deletePool("pool")
        assertThat(db.investmentDao().allPools()).isEmpty()
        assertThat(db.investmentDao().allContributions()).isEmpty()
    }

    @Test
    fun accountUpdateSucceedsWithRestrictedLinkedTransaction() = runBlocking {
        seedTransaction()
        val failure = runCatching { db.accountDao().upsert(account().copy(name = "Renamed account")) }.exceptionOrNull()
        assertThat(failure).isNull()
        assertThat(db.accountDao().get("account")!!.name).isEqualTo("Renamed account")
        assertThat(db.transactionDao().get("tx")!!.accountId).isEqualTo("account")
    }

    @Test
    fun categoryUpsertPreservesTransactionLinkAndLearnedRule() = runBlocking {
        seedTransactionAndRule()
        db.categoryDao().upsert(category().copy(name = "Renamed category"))
        assertCategoryReferences()
        assertThat(db.categoryDao().get("category")!!.name).isEqualTo("Renamed category")
    }

    @Test
    fun categoryBulkUpsertPreservesTransactionLinkAndLearnedRule() = runBlocking {
        seedTransactionAndRule()
        db.categoryDao().upsertAll(listOf(category().copy(name = "Bulk rename"), category().copy(id = "new-category")))
        assertCategoryReferences()
        assertThat(db.categoryDao().all()).hasSize(2)
    }

    @Test
    fun transactionUpdatePreservesExactReceiptBytes() = runBlocking {
        seedTransaction()
        val receipt = receipt()
        db.transactionReceiptDao().upsert(receipt)
        db.transactionDao().upsert(transaction().copy(notes = "Edited note", status = "PENDING"))
        val restored = db.transactionReceiptDao().get(receipt.id)
        assertThat(restored).isNotNull()
        assertThat(restored!!.transactionId).isEqualTo("tx")
        assertThat(restored.payload.toList()).containsExactlyElementsIn(receipt.payload.toList()).inOrder()
        assertThat(db.transactionDao().get("tx")!!.notes).isEqualTo("Edited note")
    }

    @Test
    fun conflictingSourceReferenceCannotReplaceAnotherTransactionOrReceipt() = runBlocking {
        seedTransaction(sourceRef = "synthetic-source")
        db.transactionReceiptDao().upsert(receipt())
        val original = db.transactionDao().get("tx")!!
        val failure = runCatching {
            db.transactionDao().upsert(original.copy(id = "different-tx", amountMinor = 99999L))
        }.exceptionOrNull()
        assertThat(failure).isNotNull()
        assertThat(db.transactionDao().get("tx")).isEqualTo(original)
        assertThat(db.transactionDao().get("different-tx")).isNull()
        assertThat(db.transactionReceiptDao().get("receipt")!!.payload.toList())
            .containsExactlyElementsIn(receipt().payload.toList()).inOrder()
    }

    @Test
    fun ingestReplayPreservesEveryStoredFactAndReceipt() = runBlocking {
        seedTransaction(sourceRef = "source-owned")
        val original = transaction().copy(
            sourceRefId = "source-owned", notes = "User correction", status = "DISMISSED",
        )
        db.transactionDao().upsert(original)
        db.transactionReceiptDao().upsert(receipt())
        val replay = db.transactionDao().insertIngestIfAbsent(
            original.copy(id = "fresh-replay-id", amountMinor = 99999L, currency = "USD", notes = "parser text", status = "PENDING"),
        )
        assertThat(replay.inserted).isFalse()
        assertThat(replay.entity).isEqualTo(original)
        assertThat(db.transactionDao().all()).containsExactly(original)
        assertThat(db.transactionReceiptDao().get("receipt")!!.payload.toList())
            .containsExactlyElementsIn(receipt().payload.toList()).inOrder()
    }

    @Test
    fun concurrentIngestCreatesOneStableSourceOwner() = runBlocking {
        db.accountDao().upsert(account())
        db.categoryDao().upsert(category())
        val gate = CompletableDeferred<Unit>()
        val results = coroutineScope {
            val jobs = (1..2).map { index ->
                async(Dispatchers.IO) {
                    gate.await()
                    db.transactionDao().insertIngestIfAbsent(
                        transaction().copy(id = "arrival-$index", sourceRefId = "simultaneous-source"),
                    )
                }
            }
            gate.complete(Unit)
            jobs.awaitAll()
        }
        assertThat(results.count { it.inserted }).isEqualTo(1)
        assertThat(results.map { it.entity.id }.distinct()).hasSize(1)
        assertThat(db.transactionDao().all()).hasSize(1)
        assertThat(db.transactionDao().all().single().id).isEqualTo(results.first().entity.id)
    }

    @Test
    fun categoryLearningCannotPostOrRevivePayments() = runBlocking {
        db.accountDao().upsert(account())
        db.categoryDao().upsertAll(listOf(category(), category().copy(id = "other-category")))
        val before = listOf(
            transaction().copy(id = "pending", categoryId = null, status = "PENDING", notes = "Synthetic *PEND hold"),
            transaction().copy(id = "recorded", categoryId = null),
            transaction().copy(id = "dismissed", categoryId = null, status = "DISMISSED"),
            transaction().copy(id = "personal", categoryId = "other-category", status = "PENDING"),
            transaction().copy(id = "income", categoryId = null, status = "PENDING", type = "INCOME"),
            transaction().copy(id = "transfer", categoryId = null, status = "PENDING", type = "TRANSFER"),
        ).associateBy { it.id }
        before.values.forEach { db.transactionDao().upsert(it) }
        val changedAt = Instant.fromEpochMilliseconds(2)
        assertThat(db.transactionDao().applyCategoryToMatching("synthetic", "category", changedAt)).isEqualTo(2)
        val after = db.transactionDao().all().associateBy { it.id }
        before.forEach { (id, original) ->
            val expected = if (id in setOf("pending", "recorded")) {
                original.copy(categoryId = "category", categorySource = "USER", updatedAt = changedAt)
            } else original
            assertThat(after[id]).isEqualTo(expected)
        }
        val late = transaction().copy(id = "late", categoryId = null, status = "PENDING")
        db.transactionDao().upsert(late)
        // A literal merchant substring is not SQL LIKE wildcard syntax.
        assertThat(db.transactionDao().applyCategoryToMatching("synthet%", "category", changedAt)).isEqualTo(0)
        db.categoryDao().upsert(category().copy(archived = true))
        assertThat(db.transactionDao().applyCategoryToMatching("synthetic", "category", changedAt)).isEqualTo(0)
        assertThat(db.transactionDao().get("late")).isEqualTo(late)
    }

    private suspend fun seedTransaction(sourceRef: String? = null) {
        db.accountDao().upsert(account())
        db.categoryDao().upsert(category())
        db.transactionDao().upsert(transaction().copy(sourceRefId = sourceRef))
    }

    private suspend fun seedTransactionAndRule() {
        seedTransaction()
        db.categoryRuleDao().upsert(CategoryRuleEntity(
            id = "rule", pattern = "synthetic", patternType = "EXACT", categoryId = "category",
            priority = 1, learnedFromUser = true, createdAt = now,
        ))
    }

    private suspend fun assertCategoryReferences() {
        assertThat(db.transactionDao().get("tx")!!.categoryId).isEqualTo("category")
        assertThat(db.categoryRuleDao().all().map { it.id }).containsExactly("rule")
    }

    private fun account() = AccountEntity(
        id = "account", name = "Synthetic cash", type = "CASH", currency = "SAR",
        openingBalanceMinor = 0, openingBalanceCurrency = "SAR", smsSenders = "[]", notes = null,
        sortOrder = 0, active = true, archivedAt = null, createdAt = now, updatedAt = now,
    )

    private fun category() = CategoryEntity(
        id = "category", name = "Synthetic", nameAr = "Synthetic", kind = "EXPENSE", icon = null,
        monthlyTargetMinor = null, currency = "SAR", archived = false, sortOrder = 0,
    )

    private fun transaction() = TransactionEntity(
        id = "tx", accountId = "account", type = "EXPENSE", amountMinor = 101L, currency = "SAR",
        date = LocalDate(2026, 10, 21), occurredAt = null, merchant = "Synthetic", merchantNormalized = "synthetic",
        categoryId = "category", notes = null, source = "MANUAL", sourceRefId = null, status = "CONFIRMED",
        confidence = null, createdAt = now, updatedAt = now, categorySource = "USER",
    )

    private fun receipt() = TransactionReceiptEntity(
        id = "receipt", transactionId = "tx", mimeType = "image/png", originalName = "synthetic.png",
        sizeBytes = 4, payload = byteArrayOf(0, 1, 127, -1), createdAt = now,
    )
}
