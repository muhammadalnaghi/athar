package com.athar.feature.today

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Intent
import android.speech.RecognizerIntent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts.StartActivityForResult
import androidx.activity.result.contract.ActivityResultContracts.PickVisualMedia
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
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
import com.athar.core.designsystem.display.currentUiLocale
import com.athar.core.designsystem.theme.AtharTheme
import com.athar.core.designsystem.theme.MinTouchTarget
import com.athar.core.domain.model.TxType
import kotlinx.coroutines.flow.filterIsInstance
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AddTransactionSheet(
    onDismiss: () -> Unit,
    onSaved: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: AddTransactionViewModel = hiltViewModel(),
) {
    val context = LocalContext.current
    val uiLocale = currentUiLocale()
    val state by viewModel.state.collectAsStateWithLifecycle()
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val receiptLauncher = rememberLauncherForActivityResult(PickVisualMedia()) { uri ->
        uri?.let { viewModel.attachReceipt(context.contentResolver, it) }
    }
    val voiceLauncher = rememberLauncherForActivityResult(StartActivityForResult()) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            result.data
                ?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)
                ?.firstOrNull()
                ?.let { viewModel.onEvent(AddTransactionEvent.ApplyVoiceTranscript(it)) }
        }
    }

    LaunchedEffect(viewModel) {
        viewModel.events
            .filterIsInstance<AddTransactionResult.Saved>()
            .collect { onSaved() }
    }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = AtharTheme.colors.parchment,
        contentColor = AtharTheme.colors.ink,
        modifier = modifier,
    ) {
        AddTransactionForm(
            state = state,
            onEvent = viewModel::onEvent,
            onReceiptClick = {
                receiptLauncher.launch(PickVisualMediaRequest(PickVisualMedia.ImageOnly))
            },
            onVoiceClick = {
                val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
                    .putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                    .putExtra(RecognizerIntent.EXTRA_LANGUAGE, uiLocale.toLanguageTag())
                    .putExtra(RecognizerIntent.EXTRA_PROMPT, "")
                try {
                    voiceLauncher.launch(intent)
                } catch (_: ActivityNotFoundException) {
                    viewModel.onEvent(AddTransactionEvent.VoiceUnavailable)
                }
            },
            contentPadding = PaddingValues(
                start = AtharTheme.spacing.m,
                end = AtharTheme.spacing.m,
                bottom = AtharTheme.spacing.l,
            ),
        )
    }
}

@Composable
private fun AddTransactionForm(
    state: AddTransactionState,
    onEvent: (AddTransactionEvent) -> Unit,
    onReceiptClick: () -> Unit,
    onVoiceClick: () -> Unit,
    contentPadding: PaddingValues,
) {
    val theme = AtharTheme
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .imePadding()
            .padding(contentPadding),
        verticalArrangement = Arrangement.spacedBy(theme.spacing.m),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f, fill = false)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(theme.spacing.m),
        ) {
            AtharText(text = stringResource(R.string.add_tx_title), style = theme.typography.headline)

            AtharSegmentedControl(
                segments = listOf(
                    AtharSegment(TxType.EXPENSE, stringResource(R.string.add_tx_segment_expense)),
                    AtharSegment(TxType.INCOME, stringResource(R.string.add_tx_segment_income)),
                ),
                selected = state.type,
                onSelect = { onEvent(AddTransactionEvent.SetType(it)) },
            )

            QuickEntryRow(
                value = state.quickEntry,
                error = state.quickEntryError,
                onValueChange = { onEvent(AddTransactionEvent.SetQuickEntry(it)) },
                onApply = { onEvent(AddTransactionEvent.ApplyQuickEntry) },
                onVoiceClick = onVoiceClick,
            )

            AtharAmountField(
                value = state.amount,
                onValueChange = { onEvent(AddTransactionEvent.SetAmount(it)) },
                modifier = Modifier.fillMaxWidth(),
                isError = state.validationError == ValidationError.AMOUNT_REQUIRED ||
                    state.validationError == ValidationError.AMOUNT_INVALID,
                supportingText = when (state.validationError) {
                    ValidationError.AMOUNT_REQUIRED -> stringResource(R.string.add_tx_error_amount_required)
                    ValidationError.AMOUNT_INVALID -> stringResource(R.string.add_tx_error_amount_invalid)
                    else -> null
                },
            )

            AtharTextField(
                value = state.merchant,
                onValueChange = { onEvent(AddTransactionEvent.SetMerchant(it)) },
                label = stringResource(R.string.add_tx_label_merchant),
                placeholder = stringResource(R.string.add_tx_placeholder_merchant),
                modifier = Modifier.fillMaxWidth(),
            )

            SuggestionStrip(
                suggestions = state.visibleMerchantSuggestions,
                onSelect = { onEvent(AddTransactionEvent.ApplySuggestion(it)) },
            )

            AtharText(
                text = stringResource(R.string.add_tx_label_category_optional),
                style = theme.typography.caption,
                color = theme.colors.muted,
            )

            AtharCategoryPicker(
                items = state.categoriesForType.map {
                    AtharPickerItem(key = it.id, labelEn = it.name, labelAr = it.nameAr)
                },
                selectedKey = state.selectedCategoryId,
                onSelect = { onEvent(AddTransactionEvent.SelectCategory(it)) },
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 200.dp, max = 320.dp),
            )

            AtharTextField(
                value = state.notes,
                onValueChange = { onEvent(AddTransactionEvent.SetNotes(it)) },
                label = stringResource(R.string.add_tx_label_notes),
                modifier = Modifier.fillMaxWidth(),
                singleLine = false,
            )

            ReceiptAttachmentRow(
                receipts = state.receipts,
                isLoading = state.isReceiptLoading,
                error = state.receiptError,
                isOcrRunning = state.isReceiptOcrRunning,
                ocrStatus = state.receiptOcrStatus,
                onAttachClick = onReceiptClick,
                onRemoveClick = { onEvent(AddTransactionEvent.RemoveReceipt(it)) },
            )
        }

        SaveButton(
            isSaving = state.isSaving,
            onClick = { onEvent(AddTransactionEvent.Save) },
        )
    }
}

@Composable
private fun ReceiptAttachmentRow(
    receipts: List<PendingReceiptUi>,
    isLoading: Boolean,
    error: ReceiptAttachmentError?,
    isOcrRunning: Boolean,
    ocrStatus: ReceiptOcrStatus?,
    onAttachClick: () -> Unit,
    onRemoveClick: (String) -> Unit,
) {
    val theme = AtharTheme
    Column(verticalArrangement = Arrangement.spacedBy(theme.spacing.xs)) {
        AtharText(
            text = stringResource(R.string.add_tx_receipt_title),
            style = theme.typography.caption,
            color = theme.colors.muted,
        )
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(theme.spacing.s),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            val status = when {
                isLoading -> stringResource(R.string.add_tx_receipt_loading)
                isOcrRunning -> stringResource(R.string.add_tx_receipt_ocr_loading)
                receipts.isNotEmpty() -> stringResource(R.string.add_tx_receipt_count, receipts.size)
                else -> stringResource(R.string.add_tx_receipt_empty)
            }
            Box(
                modifier = Modifier
                    .weight(1f)
                    .heightIn(min = MinTouchTarget)
                    .clip(RoundedCornerShape(theme.spacing.s))
                    .background(theme.colors.surface)
                    .padding(horizontal = theme.spacing.m, vertical = theme.spacing.s),
                contentAlignment = Alignment.CenterStart,
            ) {
                AtharText(
                    text = status,
                    style = theme.typography.caption,
                    color = if (receipts.isNotEmpty()) theme.colors.ink else theme.colors.muted,
                    maxLines = 1,
                )
            }
            CompactActionButton(
                text = stringResource(R.string.add_tx_receipt_attach),
                enabled = !isLoading && !isOcrRunning,
                onClick = onAttachClick,
            )
        }
        receipts.forEach { receipt ->
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(theme.spacing.s),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .heightIn(min = MinTouchTarget)
                        .clip(RoundedCornerShape(theme.spacing.s))
                        .background(theme.colors.surface)
                        .padding(horizontal = theme.spacing.m, vertical = theme.spacing.s),
                    contentAlignment = Alignment.CenterStart,
                ) {
                    AtharText(
                        text = stringResource(
                            R.string.add_tx_receipt_attached,
                            receipt.name,
                            formatReceiptSize(receipt.sizeBytes),
                        ),
                        style = theme.typography.caption,
                        color = theme.colors.ink,
                        maxLines = 1,
                    )
                }
                CompactActionButton(
                    text = stringResource(R.string.add_tx_receipt_remove),
                    enabled = !isLoading && !isOcrRunning,
                    onClick = { onRemoveClick(receipt.id) },
                )
            }
        }
        val ocrText = when (ocrStatus) {
            ReceiptOcrStatus.FILLED -> stringResource(R.string.add_tx_receipt_ocr_filled)
            ReceiptOcrStatus.NO_TEXT -> stringResource(R.string.add_tx_receipt_ocr_no_text)
            ReceiptOcrStatus.FAILED -> stringResource(R.string.add_tx_receipt_ocr_failed)
            null -> null
        }
        ocrText?.let {
            val color = when (ocrStatus) {
                ReceiptOcrStatus.FILLED -> theme.colors.olive
                ReceiptOcrStatus.NO_TEXT -> theme.colors.muted
                ReceiptOcrStatus.FAILED -> theme.colors.crimson
                null -> theme.colors.muted
            }
            AtharText(text = it, style = theme.typography.caption, color = color)
        }
        val errorText = when (error) {
            ReceiptAttachmentError.READ_FAILED -> stringResource(R.string.add_tx_receipt_error_read)
            ReceiptAttachmentError.TOO_LARGE -> stringResource(R.string.add_tx_receipt_error_too_large)
            ReceiptAttachmentError.UNSUPPORTED_TYPE -> stringResource(R.string.add_tx_receipt_error_unsupported)
            ReceiptAttachmentError.SAVE_FAILED -> stringResource(R.string.add_tx_receipt_error_save)
            null -> null
        }
        errorText?.let {
            AtharText(text = it, style = theme.typography.caption, color = theme.colors.crimson)
        }
    }
}

private fun formatReceiptSize(bytes: Long): String =
    if (bytes >= 1024 * 1024) {
        String.format(Locale.getDefault(), "%.1f MB", bytes / 1024f / 1024f)
    } else {
        "${((bytes + 1023) / 1024).coerceAtLeast(1)} KB"
    }

@Composable
private fun QuickEntryRow(
    value: String,
    error: QuickEntryError?,
    onValueChange: (String) -> Unit,
    onApply: () -> Unit,
    onVoiceClick: () -> Unit,
) {
    val theme = AtharTheme
    Column(verticalArrangement = Arrangement.spacedBy(theme.spacing.xs)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(theme.spacing.s),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            AtharTextField(
                value = value,
                onValueChange = onValueChange,
                label = stringResource(R.string.add_tx_label_quick_entry),
                placeholder = stringResource(R.string.add_tx_placeholder_quick_entry),
                modifier = Modifier
                    .weight(1f)
                    .heightIn(max = 96.dp),
                singleLine = false,
                isError = error == QuickEntryError.PARSE_FAILED,
            )
            CompactActionButton(
                text = stringResource(R.string.add_tx_quick_apply),
                enabled = value.isNotBlank(),
                onClick = onApply,
            )
            CompactActionButton(
                text = stringResource(R.string.add_tx_voice),
                enabled = true,
                onClick = onVoiceClick,
            )
        }
        val errorText = when (error) {
            QuickEntryError.PARSE_FAILED -> stringResource(R.string.add_tx_error_quick_entry)
            QuickEntryError.VOICE_UNAVAILABLE -> stringResource(R.string.add_tx_error_voice_unavailable)
            null -> null
        }
        errorText?.let {
            AtharText(text = it, style = theme.typography.caption, color = theme.colors.crimson)
        }
    }
}

@Composable
private fun CompactActionButton(
    text: String,
    enabled: Boolean,
    onClick: () -> Unit,
) {
    val theme = AtharTheme
    Box(
        modifier = Modifier
            .width(72.dp)
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
        )
    }
}

@Composable
private fun SuggestionStrip(
    suggestions: List<ManualEntrySuggestion>,
    onSelect: (ManualEntrySuggestion) -> Unit,
) {
    if (suggestions.isEmpty()) return
    val theme = AtharTheme
    LazyRow(
        horizontalArrangement = Arrangement.spacedBy(theme.spacing.s),
        modifier = Modifier.fillMaxWidth(),
    ) {
        items(
            items = suggestions,
            key = { it.key },
        ) { suggestion ->
            SuggestionChip(
                suggestion = suggestion,
                onClick = { onSelect(suggestion) },
            )
        }
    }
}

@Composable
private fun SuggestionChip(
    suggestion: ManualEntrySuggestion,
    onClick: () -> Unit,
) {
    val theme = AtharTheme
    Box(
        modifier = Modifier
            .heightIn(min = MinTouchTarget)
            .clip(RoundedCornerShape(theme.spacing.s))
            .background(theme.colors.surface)
            .clickable(onClick = onClick)
            .padding(horizontal = theme.spacing.m, vertical = theme.spacing.s),
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
            AtharText(
                text = suggestion.merchant,
                style = theme.typography.body,
                color = theme.colors.ink,
            )
            suggestion.amountInput?.let { amount ->
                AtharText(
                    text = amount,
                    style = theme.typography.caption,
                    color = if (suggestion.type == TxType.INCOME) theme.colors.olive else theme.colors.ember,
                )
            }
        }
    }
}

@Composable
private fun SaveButton(
    isSaving: Boolean,
    onClick: () -> Unit,
) {
    val theme = AtharTheme
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = MinTouchTarget)
            .clip(RoundedCornerShape(theme.spacing.m))
            .background(theme.colors.ember)
            .clickable(enabled = !isSaving, onClick = onClick)
            .padding(theme.spacing.m),
        contentAlignment = Alignment.Center,
    ) {
        AtharText(
            text = if (isSaving) stringResource(R.string.add_tx_saving) else stringResource(R.string.add_tx_save),
            style = theme.typography.headline,
            color = theme.colors.parchment,
        )
    }
}
