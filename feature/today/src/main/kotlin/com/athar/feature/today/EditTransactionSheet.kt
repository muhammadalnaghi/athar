package com.athar.feature.today

import android.content.ContentResolver
import android.graphics.BitmapFactory
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts.CreateDocument
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.athar.core.designsystem.component.AtharAmountField
import com.athar.core.designsystem.component.AtharCategoryPicker
import com.athar.core.designsystem.component.AtharPickerItem
import com.athar.core.designsystem.component.AtharSegment
import com.athar.core.designsystem.component.AtharSegmentedControl
import com.athar.core.designsystem.component.AtharText
import com.athar.core.designsystem.component.AtharTextField
import com.athar.core.designsystem.theme.AtharTheme
import com.athar.core.designsystem.theme.MinTouchTarget
import com.athar.core.domain.model.Category
import com.athar.core.domain.model.CategoryKind
import com.athar.core.domain.model.ReceiptAttachment
import com.athar.core.domain.model.ReceiptAttachmentMeta
import com.athar.core.domain.model.Transaction
import com.athar.core.domain.model.TxType
import com.athar.core.domain.model.specificMerchantKey
import com.athar.core.domain.repo.CategoryRepository
import com.athar.core.domain.repo.ReceiptAttachmentRepository
import kotlinx.collections.immutable.ImmutableList
import kotlinx.collections.immutable.persistentListOf
import kotlinx.collections.immutable.toImmutableList
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import java.math.BigDecimal
import java.util.Locale
import javax.inject.Inject
import androidx.lifecycle.ViewModel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * State for the edit-mode sheet. The original transaction is captured so we can detect
 * category changes for the "always categorize X as Y?" learning prompt (Master Brief §4.7).
 */
data class EditTransactionState(
    val original: Transaction,
    val amount: String,
    val merchant: String,
    val notes: String,
    val type: TxType,
    val selectedCategoryId: String?,
    val expenseCategories: ImmutableList<Category>,
    val incomeCategories: ImmutableList<Category>,
    val receiptMetas: ImmutableList<ReceiptAttachmentMeta>,
    val receiptPreview: ReceiptAttachment?,
    val isReceiptWorking: Boolean,
    val receiptStatus: EditReceiptStatus?,
) {
    val categoriesForType: ImmutableList<Category>
        get() = when (type) {
            TxType.INCOME -> incomeCategories
            TxType.EXPENSE -> expenseCategories
            TxType.TRANSFER -> persistentListOf()
        }

    val categoryChanged: Boolean
        get() = original.categoryId != selectedCategoryId

    val canPromptCategoryLearning: Boolean
        get() = categoryChanged &&
            selectedCategoryId != null &&
            specificMerchantKey(
                merchantNormalized = merchant.lowercase().trim(),
                merchant = merchant,
            ) != null
}

enum class EditReceiptStatus {
    Exported,
    Deleted,
    ExportFailed,
    DeleteFailed,
    ViewFailed,
}

@HiltViewModel
class EditTransactionViewModel @Inject constructor(
    private val categories: CategoryRepository,
    private val receipts: ReceiptAttachmentRepository,
) : ViewModel() {

    private val _state = MutableStateFlow<EditTransactionState?>(null)
    val state: StateFlow<EditTransactionState?> = _state.asStateFlow()

    private var loadJob: kotlinx.coroutines.Job? = null

    /**
     * Hydrate the editor with [tx]'s data. Called from a LaunchedEffect keyed on tx.id.
     *
     * Cancels any in-flight load so re-entering the sheet for a different transaction
     * doesn't race with the previous tx's category-stream collector. Resets _state
     * synchronously so the first frame after open shows the correct row instead of
     * the previously-edited one (regression #stale-edit-sheet).
     */
    fun load(tx: Transaction) {
        loadJob?.cancel()
        _state.value = EditTransactionState(
            original = tx,
            amount = tx.amount.amount.toPlainString(),
            merchant = tx.merchant,
            notes = tx.notes.orEmpty(),
            type = tx.type,
            selectedCategoryId = tx.categoryId,
            expenseCategories = persistentListOf(),
            incomeCategories = persistentListOf(),
            receiptMetas = persistentListOf(),
            receiptPreview = null,
            isReceiptWorking = false,
            receiptStatus = null,
        )
        loadJob = viewModelScope.launch {
            refreshReceiptMetas(tx.id, clearStatus = true)
            categories.observeAll().collect { all ->
                val expense = all.filter { it.kind == CategoryKind.EXPENSE }.toImmutableList()
                val income = all.filter { it.kind == CategoryKind.INCOME }.toImmutableList()
                _state.update { it?.copy(expenseCategories = expense, incomeCategories = income) }
            }
        }
    }

    fun setAmount(v: String) = _state.update { it?.copy(amount = v) }
    fun setMerchant(v: String) = _state.update { it?.copy(merchant = v) }
    fun setNotes(v: String) = _state.update { it?.copy(notes = v) }
    fun setType(t: TxType) = _state.update { it?.copy(type = t, selectedCategoryId = null) }
    fun selectCategory(id: String) = _state.update { it?.copy(selectedCategoryId = id) }

    fun openReceiptViewer(receiptId: String) {
        val txId = _state.value?.original?.id ?: return
        viewModelScope.launch {
            _state.update { it?.copy(isReceiptWorking = true, receiptStatus = null) }
            val result = runCatching {
                receipts.get(receiptId)
            }
            _state.update { state ->
                if (state?.original?.id != txId) {
                    state
                } else {
                    val receipt = result.getOrNull()
                    if (receipt == null) {
                        state.copy(isReceiptWorking = false, receiptStatus = EditReceiptStatus.ViewFailed)
                    } else {
                        state.copy(
                            receiptMetas = mergeReceiptMeta(state.receiptMetas, receipt.toMeta()),
                            receiptPreview = receipt,
                            isReceiptWorking = false,
                            receiptStatus = null,
                        )
                    }
                }
            }
        }
    }

    fun dismissReceiptViewer() {
        _state.update { it?.copy(receiptPreview = null) }
    }

    fun exportReceipt(receiptId: String, resolver: ContentResolver, uri: Uri) {
        val txId = _state.value?.original?.id ?: return
        viewModelScope.launch {
            _state.update { it?.copy(isReceiptWorking = true, receiptStatus = null) }
            val result = runCatching {
                withContext(Dispatchers.IO) {
                    val receipt = receipts.get(receiptId) ?: error("Receipt missing")
                    resolver.openOutputStream(uri)?.use { out ->
                        out.write(receipt.payload)
                    } ?: error("Receipt destination unavailable")
                    receipt
                }
            }
            _state.update { state ->
                if (state?.original?.id != txId) {
                    state
                } else {
                    result.fold(
                        onSuccess = { receipt ->
                            state.copy(
                                receiptMetas = mergeReceiptMeta(state.receiptMetas, receipt.toMeta()),
                                isReceiptWorking = false,
                                receiptStatus = EditReceiptStatus.Exported,
                            )
                        },
                        onFailure = {
                            state.copy(
                                isReceiptWorking = false,
                                receiptStatus = EditReceiptStatus.ExportFailed,
                            )
                        },
                    )
                }
            }
        }
    }

    fun deleteReceipt(receiptId: String) {
        val txId = _state.value?.original?.id ?: return
        viewModelScope.launch {
            _state.update { it?.copy(isReceiptWorking = true, receiptStatus = null) }
            val result = runCatching {
                receipts.delete(receiptId)
            }
            _state.update { state ->
                if (state?.original?.id != txId) {
                    state
                } else if (result.isSuccess) {
                    state.copy(
                        receiptMetas = state.receiptMetas.filterNot { it.id == receiptId }.toImmutableList(),
                        receiptPreview = state.receiptPreview?.takeUnless { it.id == receiptId },
                        isReceiptWorking = false,
                        receiptStatus = EditReceiptStatus.Deleted,
                    )
                } else {
                    state.copy(
                        isReceiptWorking = false,
                        receiptStatus = EditReceiptStatus.DeleteFailed,
                    )
                }
            }
        }
    }

    fun clearReceiptStatus() {
        _state.update { it?.copy(receiptStatus = null) }
    }

    private suspend fun refreshReceiptMetas(transactionId: String, clearStatus: Boolean) {
        val metas = runCatching {
            receipts.metadataListForTransaction(transactionId).toImmutableList()
        }.getOrDefault(persistentListOf())
        _state.update { state ->
            if (state?.original?.id == transactionId) {
                state.copy(
                    receiptMetas = metas,
                    receiptPreview = state.receiptPreview?.takeIf { preview ->
                        metas.any { it.id == preview.id }
                    },
                    receiptStatus = if (clearStatus) null else state.receiptStatus,
                )
            } else {
                state
            }
        }
    }

    private fun mergeReceiptMeta(
        current: ImmutableList<ReceiptAttachmentMeta>,
        updated: ReceiptAttachmentMeta,
    ): ImmutableList<ReceiptAttachmentMeta> =
        (current.filterNot { it.id == updated.id } + updated)
            .sortedByDescending { it.createdAt }
            .toImmutableList()
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun EditTransactionSheet(
    transaction: Transaction,
    onDismiss: () -> Unit,
    onSave: (Transaction, learnRule: Boolean) -> Unit,
    onDelete: (String) -> Unit,
    viewModel: EditTransactionViewModel = hiltViewModel(),
) {
    val context = LocalContext.current
    val theme = AtharTheme
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    var pendingLearn by remember { mutableStateOf<EditTransactionState?>(null) }
    var pendingReceiptExport by remember { mutableStateOf<ReceiptAttachmentMeta?>(null) }
    val receiptExportLauncher = rememberLauncherForActivityResult(CreateDocument("image/*")) { uri ->
        val meta = pendingReceiptExport
        if (uri != null && meta != null) {
            viewModel.exportReceipt(meta.id, context.contentResolver, uri)
        }
        pendingReceiptExport = null
    }

    LaunchedEffect(transaction.id) { viewModel.load(transaction) }

    val s = viewModel.state.collectAsStateWithLifecycle().value ?: return

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = theme.colors.parchment,
        contentColor = theme.colors.ink,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(theme.spacing.m),
            verticalArrangement = Arrangement.spacedBy(theme.spacing.m),
        ) {
            AtharText(text = stringResource(R.string.edit_tx_title), style = theme.typography.headline)

            AtharSegmentedControl(
                segments = listOf(
                    AtharSegment(TxType.EXPENSE, stringResource(R.string.add_tx_segment_expense)),
                    AtharSegment(TxType.INCOME, stringResource(R.string.add_tx_segment_income)),
                    AtharSegment(TxType.TRANSFER, stringResource(R.string.edit_tx_segment_transfer)),
                ),
                selected = s.type,
                onSelect = { viewModel.setType(it) },
            )

            AtharAmountField(
                value = s.amount,
                onValueChange = { viewModel.setAmount(it) },
                label = stringResource(R.string.editor035_amount_currency, s.original.amount.currency),
                modifier = Modifier.fillMaxWidth(),
            )

            AtharTextField(
                value = s.merchant,
                onValueChange = { viewModel.setMerchant(it) },
                label = stringResource(R.string.add_tx_label_merchant),
                modifier = Modifier.fillMaxWidth(),
            )

            if (s.type == TxType.TRANSFER) {
                AtharText(
                    text = stringResource(R.string.edit_tx_transfer_hint),
                    style = theme.typography.body,
                    color = theme.colors.muted,
                )
            } else {
                AtharText(text = stringResource(R.string.add_tx_label_category), style = theme.typography.caption, color = theme.colors.muted)
                AtharCategoryPicker(
                    items = s.categoriesForType.map {
                        AtharPickerItem(key = it.id, labelEn = it.name, labelAr = it.nameAr)
                    },
                    selectedKey = s.selectedCategoryId,
                    onSelect = { viewModel.selectCategory(it) },
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 180.dp, max = 280.dp),
                )
            }

            AtharTextField(
                value = s.notes,
                onValueChange = { viewModel.setNotes(it) },
                label = stringResource(R.string.add_tx_label_notes),
                modifier = Modifier.fillMaxWidth(),
                singleLine = false,
            )

            EditReceiptRow(
                metas = s.receiptMetas,
                isWorking = s.isReceiptWorking,
                status = s.receiptStatus,
                onView = viewModel::openReceiptViewer,
                onExport = { meta ->
                    pendingReceiptExport = meta
                    receiptExportLauncher.launch(receiptExportFileName(meta))
                },
                onDelete = viewModel::deleteReceipt,
                onClearStatus = viewModel::clearReceiptStatus,
            )

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(theme.spacing.s),
            ) {
                SheetButton(
                    text = stringResource(R.string.edit_tx_delete),
                    background = theme.colors.crimson,
                    textColor = theme.colors.parchment,
                    onClick = { onDelete(s.original.id) },
                    modifier = Modifier.weight(1f),
                )
                SheetButton(
                    text = stringResource(R.string.edit_tx_save),
                    background = theme.colors.ember,
                    textColor = theme.colors.parchment,
                    onClick = {
                        if (s.canPromptCategoryLearning) {
                            pendingLearn = s
                        } else {
                            commit(s, learnRule = false, onSave)
                        }
                    },
                    modifier = Modifier.weight(1f),
                )
            }
        }
    }

    pendingLearn?.let { stateForLearn ->
        val newCatLabel = stateForLearn.categoriesForType
            .firstOrNull { it.id == stateForLearn.selectedCategoryId }
            ?.nameAr
            .orEmpty()
        AlertDialog(
            onDismissRequest = { pendingLearn = null },
            title = { AtharText(text = stringResource(R.string.edit_tx_learn_title), style = theme.typography.headline) },
            text = {
                AtharText(
                    text = stringResource(R.string.edit_tx_learn_message, stateForLearn.merchant, newCatLabel),
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    commit(stateForLearn, learnRule = true, onSave)
                    pendingLearn = null
                }) { AtharText(text = stringResource(R.string.edit_tx_learn_always), color = theme.colors.ember) }
            },
            dismissButton = {
                TextButton(onClick = {
                    commit(stateForLearn, learnRule = false, onSave)
                    pendingLearn = null
                }) { AtharText(text = stringResource(R.string.edit_tx_learn_once), color = theme.colors.muted) }
            },
            containerColor = theme.colors.parchment,
        )
    }

    s.receiptPreview?.let { receipt ->
        ReceiptPreviewDialog(
            receipt = receipt,
            onDismiss = viewModel::dismissReceiptViewer,
            onExport = {
                val meta = receipt.toMeta()
                pendingReceiptExport = meta
                receiptExportLauncher.launch(receiptExportFileName(meta))
            },
        )
    }
}

internal fun commit(
    s: EditTransactionState,
    learnRule: Boolean,
    onSave: (Transaction, Boolean) -> Unit,
) {
    val amount = runCatching { BigDecimal(s.amount) }.getOrNull() ?: s.original.amount.amount
    val updated = s.original.copy(
        amount = com.athar.core.common.money.Money.of(amount, s.original.amount.currency),
        merchant = s.merchant.trim(),
        merchantNormalized = s.merchant.lowercase().trim(),
        notes = s.notes.takeIf { it.isNotBlank() },
        type = s.type,
        categoryId = s.selectedCategoryId,
    )
    onSave(updated, learnRule)
}

@Composable
private fun EditReceiptRow(
    metas: List<ReceiptAttachmentMeta>,
    isWorking: Boolean,
    status: EditReceiptStatus?,
    onView: (String) -> Unit,
    onExport: (ReceiptAttachmentMeta) -> Unit,
    onDelete: (String) -> Unit,
    onClearStatus: () -> Unit,
) {
    val theme = AtharTheme
    Column(verticalArrangement = Arrangement.spacedBy(theme.spacing.xs)) {
        AtharText(
            text = stringResource(R.string.add_tx_receipt_title),
            style = theme.typography.caption,
            color = theme.colors.muted,
        )
        val statusText = when {
            isWorking -> stringResource(R.string.add_tx_receipt_loading)
            metas.isNotEmpty() -> stringResource(R.string.add_tx_receipt_count, metas.size)
            else -> stringResource(R.string.edit_tx_receipt_missing)
        }
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = MinTouchTarget)
                .clip(RoundedCornerShape(theme.spacing.s))
                .background(theme.colors.surface)
                .padding(horizontal = theme.spacing.m, vertical = theme.spacing.s),
            contentAlignment = Alignment.CenterStart,
        ) {
            AtharText(
                text = statusText,
                style = theme.typography.caption,
                color = if (metas.isEmpty()) theme.colors.muted else theme.colors.ink,
                maxLines = 1,
            )
        }
        metas.forEach { meta ->
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = MinTouchTarget)
                    .clip(RoundedCornerShape(theme.spacing.s))
                    .background(theme.colors.surface)
                    .padding(horizontal = theme.spacing.m, vertical = theme.spacing.s),
                contentAlignment = Alignment.CenterStart,
            ) {
                AtharText(
                    text = stringResource(
                        R.string.add_tx_receipt_attached,
                        meta.originalName ?: receiptExportFileName(meta),
                        formatReceiptSize(meta.sizeBytes),
                    ),
                    style = theme.typography.caption,
                    color = theme.colors.ink,
                    maxLines = 1,
                )
            }
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(theme.spacing.s),
            ) {
                ReceiptActionButton(
                    text = stringResource(R.string.edit_tx_receipt_view),
                    enabled = !isWorking,
                    onClick = { onView(meta.id) },
                    modifier = Modifier.weight(1f),
                )
                ReceiptActionButton(
                    text = stringResource(R.string.edit_tx_receipt_export),
                    enabled = !isWorking,
                    onClick = { onExport(meta) },
                    modifier = Modifier.weight(1f),
                )
                ReceiptActionButton(
                    text = stringResource(R.string.add_tx_receipt_remove),
                    enabled = !isWorking,
                    onClick = { onDelete(meta.id) },
                    modifier = Modifier.weight(1f),
                )
            }
        }
        val message = when (status) {
            EditReceiptStatus.Exported -> stringResource(R.string.edit_tx_receipt_exported)
            EditReceiptStatus.Deleted -> stringResource(R.string.edit_tx_receipt_deleted)
            EditReceiptStatus.ExportFailed -> stringResource(R.string.edit_tx_receipt_export_failed)
            EditReceiptStatus.DeleteFailed -> stringResource(R.string.edit_tx_receipt_delete_failed)
            EditReceiptStatus.ViewFailed -> stringResource(R.string.edit_tx_receipt_view_failed)
            null -> null
        }
        message?.let {
            AtharText(
                text = it,
                style = theme.typography.caption,
                color = when (status) {
                    EditReceiptStatus.Exported,
                    EditReceiptStatus.Deleted -> theme.colors.olive
                    else -> theme.colors.crimson
                },
                modifier = Modifier.clickable(onClick = onClearStatus),
            )
        }
    }
}

@Composable
private fun ReceiptPreviewDialog(
    receipt: ReceiptAttachment,
    onDismiss: () -> Unit,
    onExport: () -> Unit,
) {
    val theme = AtharTheme
    val image = remember(receipt.id, receipt.sizeBytes) {
        BitmapFactory.decodeByteArray(receipt.payload, 0, receipt.payload.size)?.asImageBitmap()
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            AtharText(
                text = receipt.originalName ?: stringResource(R.string.edit_tx_receipt_preview_title),
                style = theme.typography.headline,
            )
        },
        text = {
            if (image == null) {
                AtharText(
                    text = stringResource(R.string.edit_tx_receipt_preview_error),
                    style = theme.typography.body,
                    color = theme.colors.muted,
                )
            } else {
                Image(
                    bitmap = image,
                    contentDescription = stringResource(R.string.edit_tx_receipt_preview_title),
                    contentScale = ContentScale.Fit,
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 160.dp, max = 420.dp),
                )
            }
        },
        confirmButton = {
            TextButton(onClick = onExport) {
                AtharText(text = stringResource(R.string.edit_tx_receipt_export), color = theme.colors.ember)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                AtharText(text = stringResource(R.string.edit_tx_receipt_close), color = theme.colors.muted)
            }
        },
        containerColor = theme.colors.parchment,
    )
}

@Composable
private fun ReceiptActionButton(
    text: String,
    enabled: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val theme = AtharTheme
    Box(
        modifier = modifier
            .heightIn(min = MinTouchTarget)
            .clip(RoundedCornerShape(theme.spacing.s))
            .background(if (enabled) theme.colors.ink else theme.colors.surface)
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = theme.spacing.s, vertical = theme.spacing.s),
        contentAlignment = Alignment.Center,
    ) {
        AtharText(
            text = text,
            style = theme.typography.caption,
            color = if (enabled) theme.colors.parchment else theme.colors.muted,
            maxLines = 1,
        )
    }
}

@Composable
private fun SheetButton(
    text: String,
    background: androidx.compose.ui.graphics.Color,
    textColor: androidx.compose.ui.graphics.Color,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val theme = AtharTheme
    Box(
        modifier = modifier
            .heightIn(min = MinTouchTarget)
            .clip(RoundedCornerShape(theme.spacing.s))
            .background(background)
            .clickable(onClick = onClick)
            .padding(theme.spacing.m),
        contentAlignment = Alignment.Center,
    ) {
        AtharText(text = text, style = theme.typography.headline, color = textColor)
    }
}

internal fun receiptExportFileName(meta: ReceiptAttachmentMeta): String {
    val extension = receiptExtension(meta.mimeType)
    val base = meta.originalName
        ?.trim()
        ?.substringAfterLast('/')
        ?.substringAfterLast('\\')
        ?.takeIf { it.isNotBlank() }
        ?: "athar-receipt-${meta.transactionId.take(8)}.$extension"
    val withoutUnsafeChars = base.replace(Regex("""[\\/:*?"<>|]"""), "_")
    return if (withoutUnsafeChars.substringAfterLast('.', missingDelimiterValue = "").isBlank()) {
        "$withoutUnsafeChars.$extension"
    } else {
        withoutUnsafeChars
    }
}

private fun receiptExtension(mimeType: String): String = when (mimeType.lowercase(Locale.US)) {
    "image/jpeg",
    "image/jpg",
    -> "jpg"
    "image/png" -> "png"
    "image/webp" -> "webp"
    else -> "img"
}

private fun formatReceiptSize(bytes: Long): String =
    if (bytes >= 1024 * 1024) {
        String.format(Locale.getDefault(), "%.1f MB", bytes / 1024f / 1024f)
    } else {
        "${((bytes + 1023) / 1024).coerceAtLeast(1)} KB"
    }

private fun ReceiptAttachment.toMeta(): ReceiptAttachmentMeta = ReceiptAttachmentMeta(
    id = id,
    transactionId = transactionId,
    mimeType = mimeType,
    originalName = originalName,
    sizeBytes = sizeBytes,
    createdAt = createdAt,
)
