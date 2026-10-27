package com.athar.feature.plan.portfolio

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.athar.core.designsystem.component.AtharText
import com.athar.core.designsystem.component.AtharTextField
import com.athar.core.designsystem.theme.AtharTheme
import com.athar.feature.plan.InvestmentEditorSheet
import com.athar.feature.plan.InvestmentPrimaryButton
import com.athar.feature.plan.PortfolioImportPreviewUi
import com.athar.feature.plan.R
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Local statement import: paste text or choose a local file (SAF, no network). The
 * exact 12-column CSV (header row required, exact order) or a JSON array of row
 * objects with the same keys. Explicit preview, then an explicit apply/cancel — the
 * ViewModel persists only after the user confirms, and nothing here ever touches
 * the network or invents a quote.
 */
@Composable
internal fun PortfolioImportInputSheet(onDismiss: () -> Unit, onPreview: (String) -> Unit) {
    val theme = AtharTheme
    val context = androidx.compose.ui.platform.LocalContext.current
    var text by remember { mutableStateOf("") }
    var fileProblemRes by remember { mutableStateOf<Int?>(null) }
    var readingFile by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val currentPreview by rememberUpdatedState(onPreview)
    val fileLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri == null || readingFile) return@rememberLauncherForActivityResult
        readingFile = true
        scope.launch {
            try {
                when (val read = withContext(Dispatchers.IO) {
                    readPortfolioStatementText { context.contentResolver.openInputStream(uri) }
                }) {
                    is StatementFileText.Text -> currentPreview(read.value)
                    StatementFileText.TooLarge -> fileProblemRes = R.string.plan_portfolio_import_file_too_large
                    StatementFileText.Unreadable -> fileProblemRes = R.string.plan_portfolio_import_no_file
                }
            } finally {
                readingFile = false
            }
        }
    }
    InvestmentEditorSheet(stringResource(R.string.plan_portfolio_import), onDismiss) {
        AtharText(
            stringResource(R.string.plan_portfolio_import_columns),
            style = theme.typography.caption,
            color = theme.colors.muted,
        )
        AtharTextField(
            value = text,
            onValueChange = { text = it },
            label = stringResource(R.string.plan_portfolio_import_title),
            modifier = Modifier.fillMaxWidth().heightIn(min = 96.dp),
        )
        fileProblemRes?.let { res ->
            AtharText(stringResource(res), style = theme.typography.caption, color = theme.colors.ember)
        }
        if (readingFile) {
            AtharText(stringResource(R.string.plan_portfolio_import_reading), style = theme.typography.caption)
        } else {
            Column(modifier = Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(theme.spacing.s)) {
                InvestmentPrimaryButton(stringResource(R.string.plan_portfolio_import_title)) {
                    if (text.isNotBlank()) onPreview(text)
                }
                PortfolioTextAction(stringResource(R.string.plan_portfolio_import_choose_file)) {
                    fileProblemRes = null
                    fileLauncher.launch(arrayOf("text/*", "application/json"))
                }
            }
        }
    }
}

/** Maximum evidence rows rendered before switching to a remainder count. */
private const val MAX_EVIDENCE_ROWS = 8

/**
 * Parsed-but-not-applied import preview: counts, exact per-row evidence, skipped
 * duplicates and the statement/engine rejection are shown before anything is
 * stored; Apply exists only when the merged document replayed cleanly, and Cancel
 * discards the whole preview without writing.
 */
@Composable
internal fun PortfolioImportPreviewSheet(
    previewUi: PortfolioImportPreviewUi,
    onApply: () -> Unit,
    onCancel: () -> Unit,
) {
    val theme = AtharTheme
    val preview = previewUi.preview
    InvestmentEditorSheet(
        stringResource(R.string.plan_portfolio_import_title) + " · " + previewUi.title,
        onDismiss = onCancel,
    ) {
        AtharText(
            stringResource(R.string.plan_portfolio_import_valid, preview.validEventCount, preview.validQuoteCount),
            style = theme.typography.body,
        )
        if (preview.newSecurities.isNotEmpty()) {
            AtharText(
                stringResource(R.string.plan_portfolio_import_new_securities, preview.newSecurities.size),
                style = theme.typography.caption,
            )
        }
        val duplicates = preview.duplicateEventIds + preview.duplicateQuoteKeys
        if (duplicates.isNotEmpty()) {
            AtharText(
                stringResource(R.string.plan_portfolio_import_duplicates, duplicates.size),
                style = theme.typography.caption,
                color = theme.colors.muted,
            )
            duplicates.take(MAX_EVIDENCE_ROWS).forEach { key ->
                AtharText(key, style = theme.typography.caption, color = theme.colors.muted)
            }
            if (duplicates.size > MAX_EVIDENCE_ROWS) {
                AtharText(
                    stringResource(R.string.plan_portfolio_import_evidence_rest, duplicates.size - MAX_EVIDENCE_ROWS),
                    style = theme.typography.caption,
                    color = theme.colors.muted,
                )
            }
        }
        if (preview.errors.isNotEmpty()) {
            AtharText(
                stringResource(R.string.plan_portfolio_import_errors, preview.errors.size),
                style = theme.typography.caption,
                color = theme.colors.ember,
            )
            preview.errors.take(MAX_EVIDENCE_ROWS).forEach { error ->
                AtharText(
                    stringResource(
                        R.string.plan_portfolio_import_row_error,
                        error.lineNumber,
                        rowProblemLabel(error.problem),
                    ),
                    style = theme.typography.caption,
                    color = theme.colors.ember,
                )
            }
            if (preview.errors.size > MAX_EVIDENCE_ROWS) {
                AtharText(
                    stringResource(R.string.plan_portfolio_import_evidence_rest, preview.errors.size - MAX_EVIDENCE_ROWS),
                    style = theme.typography.caption,
                    color = theme.colors.muted,
                )
            }
        }
        preview.rejection?.let { token ->
            if (token in parseProblemLabels) {
                AtharText(stringResource(parseProblemLabels.getValue(token)), style = theme.typography.caption, color = theme.colors.ember)
            } else {
                PortfolioRejectMessage(token)
            }
        }
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(theme.spacing.s)) {
            PortfolioTextAction(stringResource(R.string.plan_portfolio_import_cancel)) { onCancel() }
        }
        if (preview.canApply) {
            InvestmentPrimaryButton(stringResource(R.string.plan_portfolio_import_apply)) { onApply() }
        }
    }
}

/** Statement-level parse problems (safe `StatementProblem` names surfaced through preview). */
private val parseProblemLabels = mapOf(
    "UNSUPPORTED_EVENT_FIELDS" to R.string.plan_portfolio_import_invalid_fields,
    "EMPTY_INPUT" to R.string.plan_portfolio_parse_EMPTY_INPUT,
    "BAD_HEADER" to R.string.plan_portfolio_parse_BAD_HEADER,
    "BAD_FIELD_TYPE" to R.string.plan_portfolio_parse_BAD_FIELD_TYPE,
)

/** Exact localized evidence for one refused/skipped row; copy lives in resources only. */
@Composable
internal fun rowProblemLabel(problem: RowProblem): String = stringResource(
    when (problem) {
        RowProblem.BAD_COLUMN_COUNT -> R.string.plan_portfolio_row_problem_BAD_COLUMN_COUNT
        RowProblem.BAD_QUOTING -> R.string.plan_portfolio_row_problem_BAD_QUOTING
        RowProblem.UNKNOWN_TYPE -> R.string.plan_portfolio_row_problem_UNKNOWN_TYPE
        RowProblem.MISSING_EVENT_ID -> R.string.plan_portfolio_row_problem_MISSING_EVENT_ID
        RowProblem.MISSING_DATE -> R.string.plan_portfolio_row_problem_MISSING_DATE
        RowProblem.BAD_DATE -> R.string.plan_portfolio_row_problem_BAD_DATE
        RowProblem.MISSING_SECURITY -> R.string.plan_portfolio_row_problem_MISSING_SECURITY
        RowProblem.MISSING_POSITIVE_NUMBER -> R.string.plan_portfolio_row_problem_MISSING_POSITIVE_NUMBER
        RowProblem.BAD_DESTINATION -> R.string.plan_portfolio_row_problem_BAD_DESTINATION
        RowProblem.UNKNOWN_SECURITY -> R.string.plan_portfolio_row_problem_UNKNOWN_SECURITY
        RowProblem.BAD_ASSET_CLASS -> R.string.plan_portfolio_row_problem_BAD_ASSET_CLASS
        RowProblem.DUPLICATE_EVENT_ID -> R.string.plan_portfolio_row_problem_DUPLICATE_EVENT_ID
        RowProblem.DUPLICATE_QUOTE -> R.string.plan_portfolio_row_problem_DUPLICATE_QUOTE
    },
)
