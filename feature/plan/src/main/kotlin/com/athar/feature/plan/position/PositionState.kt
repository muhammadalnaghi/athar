package com.athar.feature.plan.position

import androidx.compose.runtime.Immutable
import kotlinx.collections.immutable.ImmutableList
import kotlinx.collections.immutable.persistentListOf

@Immutable
data class PositionState(
    /** Saved dated-position documents (newest update first); cash-scenario siblings are excluded. */
    val rows: ImmutableList<PositionRow> = persistentListOf(),
    /** The opened document with its recomputed totals, when its payload decodes cleanly. */
    val open: PositionOpen? = null,
    /** The inline editor, when open (create when [PositionEditorUi.planId] is null). */
    val editor: PositionEditorUi? = null,
    val confirmDelete: PositionRow? = null,
    /** True while a repository write is in flight; repeated writes are refused synchronously. */
    val isBusy: Boolean = false,
    /** UI receipt for a successful persisted save; not a stored financial value. */
    val saveSerial: Long = 0L,
    /** Safe constant problem token from the last refused action (null when clean). */
    val problem: PositionProblem? = null,
) {
    companion object {
        fun initial(): PositionState = PositionState()
    }
}

/** One saved position document row and whether its stored payload is usable right now. */
@Immutable
data class PositionRow(
    val planId: String,
    val title: String,
    val revision: Long,
    val currency: String,
    val entryCount: Int,
    /** Stored payload is retained but unloadable at the supported version. */
    val unavailable: Boolean = false,
)

@Immutable
data class PositionOpen(
    val planId: String,
    val title: String,
    val revision: Long,
    val document: PositionDocument,
    val totals: PositionTotals,
)

@Immutable
data class PositionEntryDraft(
    val label: String = "",
    val side: PositionSide = PositionSide.ASSET,
    val currency: String = "",
    val amount: String = "",
    val ownerScope: String = "",
)

/**
 * Authoring draft for one position document. [planId] + [expectedRevision] null
 * means create-only; an update must match the stored revision exactly.
 */
@Immutable
data class PositionEditorUi(
    val planId: String?,
    val expectedRevision: Long?,
    val title: String = "",
    val asOfDate: String = "",
    val note: String = "",
    val entries: ImmutableList<PositionEntryDraft> = persistentListOf(),
)

enum class PositionProblem { INVALID_INPUT, UNSUPPORTED_DOCUMENT, CONFLICT, STORAGE }

enum class PositionTextField { TITLE, AS_OF_DATE, NOTE }

enum class PositionEntryField { LABEL, SIDE, CURRENCY, AMOUNT, OWNER_SCOPE }

sealed interface PositionEvent {
    data object OpenCreateEditor : PositionEvent
    data class OpenUpdateEditor(val planId: String) : PositionEvent
    data object DismissEditor : PositionEvent
    data class EditField(val field: PositionTextField, val value: String) : PositionEvent
    data object AddEntry : PositionEvent
    data class RemoveEntry(val index: Int) : PositionEvent
    data class EditEntry(val index: Int, val field: PositionEntryField, val value: String) : PositionEvent
    data object SaveEditor : PositionEvent
    data class OpenDoc(val planId: String) : PositionEvent
    data object CloseDoc : PositionEvent
    data class RequestDelete(val planId: String) : PositionEvent
    data object ConfirmDelete : PositionEvent
    data object DismissDelete : PositionEvent
    data object DismissProblem : PositionEvent
}
