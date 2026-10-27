package com.athar.feature.today

import android.content.ContentResolver
import android.net.Uri
import android.provider.OpenableColumns
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.athar.core.common.money.Money
import com.athar.core.domain.model.CategoryKind
import com.athar.core.domain.model.CategorySource
import com.athar.core.domain.model.IngestSource
import com.athar.core.domain.model.MANUAL_ACCOUNT_ID
import com.athar.core.domain.model.ReceiptAttachment
import com.athar.core.domain.model.Transaction
import com.athar.core.domain.model.TxStatus
import com.athar.core.domain.model.TxType
import com.athar.core.domain.repo.CategoryRepository
import com.athar.core.domain.repo.ReceiptAttachmentRepository
import com.athar.core.domain.repo.TransactionRepository
import com.athar.core.domain.repo.UserPreferencesRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.collections.immutable.toImmutableList
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.datetime.Clock
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.math.BigDecimal
import java.util.UUID
import javax.inject.Inject

@HiltViewModel
class AddTransactionViewModel @Inject constructor(
    private val transactions: TransactionRepository,
    private val receipts: ReceiptAttachmentRepository,
    private val categories: CategoryRepository,
    private val prefs: UserPreferencesRepository,
    private val receiptTextRecognizer: ReceiptImageTextRecognizer,
    private val clock: Clock,
) : ViewModel() {

    private val _state = MutableStateFlow(AddTransactionState.initial(today()))
    val state: StateFlow<AddTransactionState> = _state.asStateFlow()

    private val _events = MutableSharedFlow<AddTransactionResult>(extraBufferCapacity = 1)
    val events: SharedFlow<AddTransactionResult> = _events.asSharedFlow()

    private var pendingReceipts: List<PendingReceipt> = emptyList()

    init {
        viewModelScope.launch {
            categories.observeAll().collect { all ->
                val expense = all.filter { it.kind == CategoryKind.EXPENSE }.toImmutableList()
                val income = all.filter { it.kind == CategoryKind.INCOME }.toImmutableList()
                _state.update { it.copy(expenseCategories = expense, incomeCategories = income) }
            }
        }
        viewModelScope.launch {
            combine(transactions.observeAll(), prefs.displayCurrency()) { all, currency ->
                ManualEntrySuggestionBuilder.build(all, displayCurrency = currency).toImmutableList()
            }.collect { suggestions ->
                _state.update { it.copy(merchantSuggestions = suggestions) }
            }
        }
    }

    fun onEvent(event: AddTransactionEvent) {
        when (event) {
            is AddTransactionEvent.SetAmount -> _state.update { it.copy(amount = event.value, validationError = null) }
            is AddTransactionEvent.SetMerchant -> _state.update { it.copy(merchant = event.value, validationError = null) }
            is AddTransactionEvent.SetNotes -> _state.update { it.copy(notes = event.value) }
            is AddTransactionEvent.SetQuickEntry -> _state.update {
                it.copy(quickEntry = event.value, quickEntryError = null)
            }
            AddTransactionEvent.ApplyQuickEntry -> _state.update {
                ManualEntryPhraseApplier.apply(it, it.quickEntry)
            }
            is AddTransactionEvent.ApplyVoiceTranscript -> _state.update {
                ManualEntryPhraseApplier.apply(it.copy(quickEntry = event.value), event.value)
            }
            AddTransactionEvent.VoiceUnavailable -> _state.update {
                it.copy(quickEntryError = QuickEntryError.VOICE_UNAVAILABLE)
            }
            is AddTransactionEvent.RemoveReceipt -> {
                pendingReceipts = pendingReceipts.filterNot { it.id == event.id }
                _state.update { current ->
                    val nextReceipts = current.receipts
                        .filterNot { receipt -> receipt.id == event.id }
                        .toImmutableList()
                    current.copy(
                        receipts = nextReceipts,
                        isReceiptLoading = false,
                        receiptError = null,
                        isReceiptOcrRunning = current.isReceiptOcrRunning && nextReceipts.isNotEmpty(),
                        receiptOcrStatus = current.receiptOcrStatus.takeIf { nextReceipts.isNotEmpty() },
                    )
                }
            }
            is AddTransactionEvent.SetType -> _state.update {
                it.copy(type = event.type, selectedCategoryId = null, validationError = null)
            }
            is AddTransactionEvent.SetDate -> _state.update { it.copy(date = event.date) }
            is AddTransactionEvent.SelectCategory -> _state.update {
                it.copy(selectedCategoryId = event.categoryId, validationError = null)
            }
            is AddTransactionEvent.ApplySuggestion -> _state.update {
                it.copy(
                    amount = event.suggestion.amountInput ?: it.amount,
                    merchant = event.suggestion.merchant,
                    type = event.suggestion.type,
                    selectedCategoryId = event.suggestion.categoryId,
                    validationError = null,
                )
            }
            AddTransactionEvent.Save -> save()
        }
    }

    fun attachReceipt(resolver: ContentResolver, uri: Uri) {
        viewModelScope.launch {
            _state.update {
                it.copy(
                    isReceiptLoading = true,
                    receiptError = null,
                    isReceiptOcrRunning = false,
                    receiptOcrStatus = null,
                )
            }
            val result = runCatching {
                withContext(Dispatchers.IO) { readReceipt(resolver, uri) }
            }
            result.onSuccess { receipt ->
                pendingReceipts = pendingReceipts + receipt
                _state.update {
                    it.copy(
                        receipts = (it.receipts + receipt.toUi()).toImmutableList(),
                        isReceiptLoading = false,
                        receiptError = null,
                        receiptOcrStatus = null,
                    )
                }
                prefillFromReceiptImage(receipt.id, uri)
            }.onFailure { throwable ->
                if (throwable is kotlinx.coroutines.CancellationException) throw throwable
                _state.update {
                    it.copy(
                        isReceiptLoading = false,
                        isReceiptOcrRunning = false,
                        receiptOcrStatus = null,
                        receiptError = (throwable as? ReceiptReadFailure)?.error
                            ?: ReceiptAttachmentError.READ_FAILED,
                    )
                }
            }
        }
    }

    private suspend fun prefillFromReceiptImage(receiptId: String, uri: Uri) {
        _state.update { it.copy(isReceiptOcrRunning = true, receiptOcrStatus = null) }
        val result = runCatching {
            withContext(Dispatchers.IO) { receiptTextRecognizer.recognize(uri) }
        }
        result.onSuccess { recognizedText ->
            _state.update { current ->
                if (pendingReceipts.none { it.id == receiptId }) {
                    current.copy(isReceiptOcrRunning = false, receiptOcrStatus = null)
                } else {
                    val prefill = ReceiptOcrPrefillApplier.apply(current, recognizedText)
                    prefill.state.copy(
                        isReceiptOcrRunning = false,
                        receiptOcrStatus = prefill.status,
                        receiptError = null,
                    )
                }
            }
        }.onFailure { throwable ->
            if (throwable is kotlinx.coroutines.CancellationException) throw throwable
            _state.update { current ->
                current.copy(
                    isReceiptOcrRunning = false,
                    receiptOcrStatus = ReceiptOcrStatus.FAILED
                        .takeIf { pendingReceipts.any { receipt -> receipt.id == receiptId } },
                )
            }
        }
    }

    private fun save() {
        val s = _state.value
        val validation = validate(s)
        if (validation != null) {
            _state.update { it.copy(validationError = validation) }
            return
        }
        viewModelScope.launch {
            _state.update { it.copy(isSaving = true) }
            val now = clock.now()
            val currency = prefs.displayCurrency().first()
            val txId = UUID.randomUUID().toString()
            val tx = Transaction(
                id = txId,
                accountId = MANUAL_ACCOUNT_ID,
                type = s.type,
                amount = Money.of(BigDecimal(s.amount), currency),
                date = s.date,
                occurredAt = now,
                // Blank merchant stays empty — never an invented payee or bank alias.
                // Unassigned category keeps honest unassigned provenance (null source,
                // per Transaction.categorySource KDoc); USER only when user assigned.
                merchant = s.merchant.trim(),
                merchantNormalized = s.merchant.lowercase().trim(),
                categoryId = s.selectedCategoryId,
                categorySource = s.selectedCategoryId?.let { CategorySource.USER },
                notes = s.notes.takeIf { it.isNotBlank() },
                source = IngestSource.MANUAL,
                sourceRefId = null,
                status = TxStatus.CONFIRMED,
                confidence = 1.0f,
                createdAt = now,
                updatedAt = now,
            )
            val receiptsToSave = pendingReceipts
            val result = runCatching {
                transactions.upsert(tx)
                receiptsToSave.forEach { receipt ->
                    receipts.upsert(
                        ReceiptAttachment(
                            id = receipt.id,
                            transactionId = txId,
                            mimeType = receipt.mimeType,
                            originalName = receipt.originalName,
                            sizeBytes = receipt.bytes.size.toLong(),
                            payload = receipt.bytes,
                            createdAt = now,
                        ),
                    )
                }
            }
            result.onSuccess {
                pendingReceipts = emptyList()
                _events.tryEmit(AddTransactionResult.Saved)
                _state.update {
                    AddTransactionState.initial(today()).copy(
                        expenseCategories = s.expenseCategories,
                        incomeCategories = s.incomeCategories,
                        merchantSuggestions = s.merchantSuggestions,
                    )
                }
            }.onFailure {
                _state.update { it.copy(isSaving = false, receiptError = ReceiptAttachmentError.SAVE_FAILED) }
            }
        }
    }

    private fun validate(s: AddTransactionState): ValidationError? {
        if (s.amount.isBlank()) return ValidationError.AMOUNT_REQUIRED
        val amt = runCatching { BigDecimal(s.amount) }.getOrNull() ?: return ValidationError.AMOUNT_INVALID
        if (amt.signum() <= 0) return ValidationError.AMOUNT_INVALID
        // Per ADR-010 / Master Brief §2.2 amendment (2026-10-20): an explicit manual
        // financial declaration is not gated on category assignment or merchant name.
        // Amount > 0 is the only financial Save gate.
        return null
    }

    private fun today() = clock.now().toLocalDateTime(TimeZone.currentSystemDefault()).date

    private fun readReceipt(resolver: ContentResolver, uri: Uri): PendingReceipt {
        val mimeType = resolver.getType(uri)?.lowercase()?.takeIf { it.isNotBlank() } ?: "image/*"
        if (!mimeType.startsWith("image/")) {
            throw ReceiptReadFailure(ReceiptAttachmentError.UNSUPPORTED_TYPE)
        }
        val bytes = resolver.openInputStream(uri)?.use { it.readReceiptBytes() }
            ?: throw ReceiptReadFailure(ReceiptAttachmentError.READ_FAILED)
        if (bytes.isEmpty()) throw ReceiptReadFailure(ReceiptAttachmentError.READ_FAILED)
        return PendingReceipt(
            id = UUID.randomUUID().toString(),
            bytes = bytes,
            mimeType = mimeType,
            originalName = queryDisplayName(resolver, uri),
        )
    }

    private fun PendingReceipt.toUi(): PendingReceiptUi = PendingReceiptUi(
        id = id,
        name = originalName ?: RECEIPT_FALLBACK_NAME,
        sizeBytes = bytes.size.toLong(),
    )

    private fun InputStream.readReceiptBytes(): ByteArray {
        val out = ByteArrayOutputStream()
        val buffer = ByteArray(8 * 1024)
        var total = 0
        while (true) {
            val read = read(buffer)
            if (read == -1) break
            total += read
            if (total > MAX_RECEIPT_BYTES) {
                throw ReceiptReadFailure(ReceiptAttachmentError.TOO_LARGE)
            }
            out.write(buffer, 0, read)
        }
        return out.toByteArray()
    }

    private fun queryDisplayName(resolver: ContentResolver, uri: Uri): String? =
        resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
            val index = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
            if (index >= 0 && cursor.moveToFirst()) cursor.getString(index) else null
        }?.takeIf { it.isNotBlank() }

    private class ReceiptReadFailure(val error: ReceiptAttachmentError) : RuntimeException()

    private data class PendingReceipt(
        val id: String,
        val bytes: ByteArray,
        val mimeType: String,
        val originalName: String?,
    )

    private companion object {
        const val MAX_RECEIPT_BYTES = 5 * 1024 * 1024
        const val RECEIPT_FALLBACK_NAME = "receipt"
    }
}

sealed interface AddTransactionResult {
    data object Saved : AddTransactionResult
}
