package com.athar.feature.plan

import androidx.compose.runtime.Immutable
import com.athar.core.common.money.Money
import com.athar.core.domain.model.InvestmentPool
import com.athar.feature.plan.investments.PoolBreakdown
import com.athar.feature.plan.portfolio.EventInput
import com.athar.feature.plan.portfolio.ImportPreview
import com.athar.feature.plan.portfolio.PortfolioEntryKind
import com.athar.feature.plan.portfolio.PortfolioWorkbench
import java.math.BigDecimal
import kotlinx.collections.immutable.ImmutableList
import kotlinx.collections.immutable.persistentListOf

@Immutable
data class InvestmentsState(
    val pools: ImmutableList<PoolRow>,
    val isLoading: Boolean,
    val breakdowns: ImmutableList<PoolBreakdown> = persistentListOf(),
    val writeProblem: InvestmentWriteProblem? = null,
    /** Saved dated-securities portfolios (PORTFOLIO documents), newest first. */
    val portfolios: ImmutableList<PortfolioRow> = persistentListOf(),
    /** The portfolio opened in the workbench, when its document decodes and replays cleanly. */
    val portfolioOpen: PortfolioOpen? = null,
    /** A parsed-but-not-applied statement import awaiting explicit apply/cancel. */
    val importPreview: PortfolioImportPreviewUi? = null,
    /** Safe rejection/problem token from the last refused portfolio write (null when clean). */
    val portfolioProblem: String? = null,
    val allocationWrite: com.athar.feature.plan.portfolio.PortfolioAllocationWrite? = null,
) {
    companion object {
        fun initial(): InvestmentsState = InvestmentsState(
            pools = persistentListOf(),
            isLoading = true,
        )
    }
}

data class PoolRow(
    val pool: InvestmentPool,
    val totalCorpus: Money,
    val contributors: ImmutableList<ContributorRow>,
    val declaredReturnPercent: BigDecimal? = null,
)

data class ContributorRow(
    val id: String,
    val name: String,
    val amount: Money,
    val sharePercent: Double,
    val shareReturn: Money?,
    val netTotal: Money?,
    val hasComposition: Boolean = true,
)

enum class InvestmentWriteProblem { INVALID_INPUT, UNSUPPORTED_AMOUNT, UNSUPPORTED_ALLOCATION, STORAGE, CONFLICT, UNSUPPORTED_DOCUMENT }

/** One saved PORTFOLIO document row and whether its stored payload is usable right now. */
data class PortfolioRow(
    val planId: String,
    val title: String,
    val currency: String,
    val revision: Long,
    val updatedAtEpochMillis: Long,
    val state: PortfolioDocState,
)

sealed interface PortfolioDocState {
    /** Decoded at the supported version and replayed through the reviewed engines. */
    data class Ready(val workbench: PortfolioWorkbench) : PortfolioDocState

    /** Decoded, but the stored inputs are refused by the typed engines (kept retained). */
    data class Rejected(val token: String) : PortfolioDocState

    /** Malformed or future-version payload: retained but unloadable, never deleted silently. */
    data object Unavailable : PortfolioDocState
}

data class PortfolioOpen(
    val planId: String,
    val title: String,
    val currency: String,
    val revision: Long,
    val workbench: PortfolioWorkbench,
    /** A reviewed-engine before/after correction awaiting the explicit final save. */
    val entryPreview: PortfolioEntryPreviewUi? = null,
    /** Input preserved after preview cancel so the editor can reopen exactly as left. */
    val restoredEntryDraft: PortfolioRestoredEntryDraft? = null,
    val openingDatePreview: PortfolioOpeningDatePreviewUi? = null,
)

/** Exact engine-recomputed totals for one side of a correction preview; currency is carried separately so value and literal currency stay visually separate. */
data class PortfolioPreviewTotals(
    val cashAmount: BigDecimal? = null,
    val pricedHoldingsAmount: BigDecimal? = null,
    val totalAmount: BigDecimal? = null,
)

/**
 * The reviewed preview of one pending in-place correction: before/after totals come
 * from the same engines that will validate the final write, and [draft] keeps the
 * user's exact input so cancelling loses nothing. No write has happened yet.
 */
data class PortfolioEntryPreviewUi(
    val token: String,
    val planId: String,
    val eventId: String,
    val draft: PortfolioEntryDraft,
    val currency: String,
    val before: PortfolioPreviewTotals,
    val after: PortfolioPreviewTotals,
)

/** The exact draft the user was reviewing, handed back to the editor after cancel. */
data class PortfolioRestoredEntryDraft(
    val planId: String,
    val eventId: String,
    val draft: PortfolioEntryDraft,
)

/** An immutable reviewed date change; no storage write occurs before confirmation. */
data class PortfolioOpeningDatePreviewUi(
    val token: String,
    val planId: String,
    val currentText: String?,
    val draftText: String,
    val currency: String,
    val before: PortfolioPreviewTotals,
    val after: PortfolioPreviewTotals,
)

data class PortfolioImportPreviewUi(
    val planId: String,
    val title: String,
    val preview: ImportPreview,
)

/** Manual dated-entry draft (UI fields); parsed and validated in the ViewModel before any write. */
data class PortfolioEntryDraft(
    val kind: PortfolioEntryKind,
    val securityId: String = "",
    val dateText: String = "",
    val quantityText: String = "",
    val priceText: String = "",
    val feeText: String = "",
    val amountText: String = "",
    val externalDestination: Boolean = false,
    val note: String = "",
)

/**
 * Exact prefill of one stored event for in-place correction: every stored literal is
 * carried verbatim — no reformatting, no rounding, no invented defaults — so untouched
 * fields re-save byte-identical. [PortfolioEntryDraft.externalDestination] maps only the
 * two known destination tokens (`EXTERNAL_ACCOUNT`, anything else → portfolio cash).
 */
fun editDraftOf(event: EventInput): PortfolioEntryDraft = PortfolioEntryDraft(
    kind = event.kind,
    securityId = event.securityId.orEmpty(),
    dateText = event.date,
    quantityText = event.quantity.orEmpty(),
    priceText = event.unitPrice.orEmpty(),
    feeText = event.fee.orEmpty(),
    amountText = event.amount.orEmpty(),
    externalDestination = event.destination == "EXTERNAL_ACCOUNT",
    note = event.note.orEmpty(),
)

sealed interface InvestmentsEvent {
    data class SavePool(val pool: InvestmentPool) : InvestmentsEvent
    data class SaveContribution(val poolId: String, val owner: String, val amount: Money) : InvestmentsEvent
    data class DeletePool(val id: String) : InvestmentsEvent
    data class DeleteContribution(val id: String) : InvestmentsEvent
    /**
     * Updates a pool's total return given as a percentage of the corpus, instead of an
     * absolute same-currency amount. The ViewModel computes `corpus × pct/100` and writes the
     * resulting absolute return back to the pool.
     */
    data class UpdatePoolReturnPercent(val poolId: String, val percent: BigDecimal) : InvestmentsEvent

    // ---- dated securities portfolio (PORTFOLIO saved documents) ----
    data class CreatePortfolio(
        val title: String,
        val currency: String,
        val openingCashText: String,
        val openingCashDateText: String = "",
    ) : InvestmentsEvent
    data class OpenPortfolio(val planId: String) : InvestmentsEvent
    data object ClosePortfolio : InvestmentsEvent
    data class DeletePortfolio(val planId: String) : InvestmentsEvent
    data class AddPortfolioSecurity(
        val planId: String,
        val securityId: String,
        val name: String,
        val assetClass: String?,
    ) : InvestmentsEvent

    data class AddPortfolioEntry(val planId: String, val entry: PortfolioEntryDraft) : InvestmentsEvent

    /**
     * Corrects one stored event IN PLACE: same stable [eventId], same list position,
     * never delete+recreate. Persisted only after the whole corrected document replays
     * cleanly through the reviewed engines and the revision CAS agrees.
     */
    data class UpdatePortfolioEntry(val planId: String, val eventId: String, val entry: PortfolioEntryDraft) : InvestmentsEvent

    /**
     * Reviews one pending correction through the engines WITHOUT writing: the VM
     * recomputes exact before/after totals off-main and captures the corrected
     * document, event id and session revision for the explicit final save. The UI must
     * dispatch this instead of [UpdatePortfolioEntry]; that event stays only for
     * existing callers/tests.
     */
    data class PreviewPortfolioEntry(val planId: String, val eventId: String, val entry: PortfolioEntryDraft) : InvestmentsEvent

    /** Final explicit save of the reviewed preview, bound to its captured revision via CAS. */
    data class ConfirmPortfolioEntryPreview(val token: String) : InvestmentsEvent

    /** Cancels the pending preview: writes nothing and preserves the reviewed input. */
    data object CancelPortfolioEntryPreview : InvestmentsEvent
    data class PreviewPortfolioOpeningDate(val planId: String, val dateText: String) : InvestmentsEvent
    data class ConfirmPortfolioOpeningDatePreview(val token: String) : InvestmentsEvent
    data object CancelPortfolioOpeningDatePreview : InvestmentsEvent
    data class SavePortfolioAllocation(
        val planId: String,
        val expectedRevision: Long,
        val token: String,
        val draft: com.athar.feature.plan.portfolio.PortfolioAllocationDraft,
    ) : InvestmentsEvent
    data class AddPortfolioQuote(val planId: String, val securityId: String, val dateText: String, val priceText: String) : InvestmentsEvent
    data class DeletePortfolioEvent(val planId: String, val eventId: String) : InvestmentsEvent
    data class ImportPortfolioStatement(val planId: String, val text: String) : InvestmentsEvent
    data object ConfirmPortfolioImport : InvestmentsEvent
    data object CancelPortfolioImport : InvestmentsEvent
}
