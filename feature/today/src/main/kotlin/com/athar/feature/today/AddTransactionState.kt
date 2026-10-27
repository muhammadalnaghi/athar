package com.athar.feature.today

import com.athar.core.domain.model.Category
import com.athar.core.domain.model.TxType
import kotlinx.collections.immutable.ImmutableList
import kotlinx.collections.immutable.persistentListOf
import kotlinx.datetime.LocalDate

data class AddTransactionState(
    val amount: String,
    val merchant: String,
    val notes: String,
    val type: TxType,
    val date: LocalDate,
    val selectedCategoryId: String?,
    val expenseCategories: ImmutableList<Category>,
    val incomeCategories: ImmutableList<Category>,
    val merchantSuggestions: ImmutableList<ManualEntrySuggestion>,
    val quickEntry: String,
    val quickEntryError: QuickEntryError?,
    val receipts: ImmutableList<PendingReceiptUi>,
    val isReceiptLoading: Boolean,
    val receiptError: ReceiptAttachmentError?,
    val isReceiptOcrRunning: Boolean,
    val receiptOcrStatus: ReceiptOcrStatus?,
    val isSaving: Boolean,
    val validationError: ValidationError?,
) {
    val categoriesForType: ImmutableList<Category>
        get() = if (type == TxType.INCOME) incomeCategories else expenseCategories

    val visibleMerchantSuggestions: List<ManualEntrySuggestion>
        get() {
            val query = merchant.trim().lowercase()
            val sameType = merchantSuggestions.filter { it.type == type }
            return (if (query.isBlank()) {
                sameType
            } else {
                sameType.filter {
                    it.merchant.lowercase().contains(query) ||
                        it.merchantNormalized.contains(query)
                }
            }).take(if (query.isBlank()) QUICK_ADD_LIMIT else AUTOCOMPLETE_LIMIT)
        }

    companion object {
        fun initial(today: LocalDate): AddTransactionState = AddTransactionState(
            amount = "",
            merchant = "",
            notes = "",
            type = TxType.EXPENSE,
            date = today,
            selectedCategoryId = null,
            expenseCategories = persistentListOf(),
            incomeCategories = persistentListOf(),
            merchantSuggestions = persistentListOf(),
            quickEntry = "",
            quickEntryError = null,
            receipts = persistentListOf(),
            isReceiptLoading = false,
            receiptError = null,
            isReceiptOcrRunning = false,
            receiptOcrStatus = null,
            isSaving = false,
            validationError = null,
        )

        private const val QUICK_ADD_LIMIT = 6
        private const val AUTOCOMPLETE_LIMIT = 8
    }
}

data class PendingReceiptUi(
    val id: String,
    val name: String,
    val sizeBytes: Long,
)

enum class ValidationError {
    AMOUNT_REQUIRED,
    AMOUNT_INVALID,
}

enum class QuickEntryError {
    PARSE_FAILED,
    VOICE_UNAVAILABLE,
}

enum class ReceiptAttachmentError {
    READ_FAILED,
    TOO_LARGE,
    UNSUPPORTED_TYPE,
    SAVE_FAILED,
}

enum class ReceiptOcrStatus {
    FILLED,
    NO_TEXT,
    FAILED,
}

sealed interface AddTransactionEvent {
    data class SetAmount(val value: String) : AddTransactionEvent
    data class SetMerchant(val value: String) : AddTransactionEvent
    data class SetNotes(val value: String) : AddTransactionEvent
    data class SetQuickEntry(val value: String) : AddTransactionEvent
    data object ApplyQuickEntry : AddTransactionEvent
    data class ApplyVoiceTranscript(val value: String) : AddTransactionEvent
    data object VoiceUnavailable : AddTransactionEvent
    data class RemoveReceipt(val id: String) : AddTransactionEvent
    data class SetType(val type: TxType) : AddTransactionEvent
    data class SetDate(val date: LocalDate) : AddTransactionEvent
    data class SelectCategory(val categoryId: String) : AddTransactionEvent
    data class ApplySuggestion(val suggestion: ManualEntrySuggestion) : AddTransactionEvent
    data object Save : AddTransactionEvent
}
