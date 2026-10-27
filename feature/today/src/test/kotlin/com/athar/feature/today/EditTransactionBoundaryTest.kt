package com.athar.feature.today

import com.athar.core.common.money.Money
import com.athar.core.domain.model.IngestSource
import com.athar.core.domain.model.Transaction
import com.athar.core.domain.model.TxStatus
import com.athar.core.domain.model.TxType
import com.google.common.truth.Truth.assertThat
import kotlinx.collections.immutable.persistentListOf
import kotlinx.datetime.Instant
import kotlinx.datetime.LocalDate
import org.junit.jupiter.api.Test

class EditTransactionBoundaryTest {
    @Test
    fun `category edit keeps original foreign currency and pending financial state`() {
        val original = original(Money.of("5.25", "USD"))
        val result = save(state(original).copy(selectedCategoryId = "food"))
        assertThat(result.amount).isEqualTo(original.amount)
        assertThat(result.categoryId).isEqualTo("food")
        assertThat(result.status).isEqualTo(TxStatus.PENDING)
        assertThat(result.sourceRefId).isEqualTo(original.sourceRefId)
        assertThat(result.accountId).isEqualTo(original.accountId)
        assertThat(result.date).isEqualTo(original.date)
    }

    @Test
    fun `edited value preserves literal currency and meaningful decimal precision`() {
        val original = original(Money.of("5.251", "KWD"))
        val result = save(state(original).copy(amount = "7.125"))
        assertThat(result.amount).isEqualTo(Money.of("7.125", "KWD"))
    }

    @Test
    fun `invalid amount fallback never relabels the original money`() {
        val original = original(Money.of("250", "JPY"))
        assertThat(save(state(original).copy(amount = "invalid")).amount).isEqualTo(original.amount)
    }

    @Test
    fun `held processor category edit does not prompt merchant learning`() {
        for (merchant in listOf("PUBLIC*PEND", "PUBLIC* PEND", "PUBLIC*PENDING")) {
            val original = original(Money.of("5.25")).copy(merchant = merchant, merchantNormalized = merchant.lowercase())
            assertThat(state(original).copy(selectedCategoryId = "food").canPromptCategoryLearning).isFalse()
        }
    }

    @Test
    fun `specific real merchant category edit can still offer optional learning`() {
        val original = original(Money.of("5.25")).copy(merchant = "PEND*BOOKS", merchantNormalized = "pend*books")
        assertThat(state(original).copy(selectedCategoryId = "books").canPromptCategoryLearning).isTrue()
    }

    @Test
    fun `same category and SAR money preserve existing edit behavior`() {
        val original = original(Money.of("5.25"))
        assertThat(state(original).canPromptCategoryLearning).isFalse()
        assertThat(save(state(original)).amount).isEqualTo(original.amount)
    }

    private fun save(state: EditTransactionState): Transaction {
        var saved: Transaction? = null
        commit(state, learnRule = false) { transaction, learn ->
            assertThat(learn).isFalse()
            saved = transaction
        }
        return requireNotNull(saved)
    }

    private fun state(original: Transaction) = EditTransactionState(
        original = original, amount = original.amount.amount.toPlainString(),
        merchant = original.merchant, notes = original.notes.orEmpty(), type = original.type,
        selectedCategoryId = original.categoryId, expenseCategories = persistentListOf(),
        incomeCategories = persistentListOf(), receiptMetas = persistentListOf(),
        receiptPreview = null, isReceiptWorking = false, receiptStatus = null,
    )

    private fun original(money: Money) = Transaction(
        id = "synthetic-edit", accountId = "synthetic-account", type = TxType.EXPENSE,
        amount = money, date = LocalDate(2026, 10, 24), occurredAt = null,
        merchant = "Public Shop", merchantNormalized = "public shop", categoryId = null,
        notes = null, source = IngestSource.SMS, sourceRefId = "synthetic-source",
        status = TxStatus.PENDING, confidence = null,
        createdAt = Instant.fromEpochMilliseconds(1), updatedAt = Instant.fromEpochMilliseconds(1),
    )
}
