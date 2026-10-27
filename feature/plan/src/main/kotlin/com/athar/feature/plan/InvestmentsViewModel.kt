package com.athar.feature.plan

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.athar.core.domain.calc.InvestmentsCalc
import com.athar.core.domain.model.InvestmentContribution
import com.athar.core.domain.model.InvestmentPool
import com.athar.core.domain.model.portfolio.AssetClass
import com.athar.core.domain.repo.InvestmentRepository
import com.athar.core.domain.repo.SavedFinancialPlan
import com.athar.core.domain.repo.SavedFinancialPlanConflictException
import com.athar.core.domain.repo.SavedFinancialPlanKind
import com.athar.core.domain.repo.SavedFinancialPlanRepository
import com.athar.core.domain.repo.SavedFinancialPlanValidationException
import com.athar.feature.plan.investments.InvestmentEntryRules
import com.athar.feature.plan.investments.InvestmentsBreakdown
import com.athar.feature.plan.portfolio.AllocationWriteProblem
import com.athar.feature.plan.portfolio.AllocationTargetResult
import com.athar.feature.plan.portfolio.PortfolioAllocationWrite
import com.athar.feature.plan.portfolio.validateAllocationDraft
import com.athar.feature.plan.portfolio.EventInput
import com.athar.feature.plan.portfolio.PortfolioDecodeResult
import com.athar.feature.plan.portfolio.PortfolioEntryKind
import com.athar.feature.plan.portfolio.PortfolioInputText
import com.athar.feature.plan.portfolio.PortfolioPlanCodec
import com.athar.feature.plan.portfolio.PortfolioPlanDocument
import com.athar.feature.plan.portfolio.PortfolioStatementImport
import com.athar.feature.plan.portfolio.PortfolioWorkbench
import com.athar.feature.plan.portfolio.QuoteInput
import com.athar.feature.plan.portfolio.SecurityInput
import com.athar.feature.plan.portfolio.encodeLoadablePortfolio
import dagger.hilt.android.lifecycle.HiltViewModel
import java.math.MathContext
import java.util.UUID
import javax.inject.Inject
import kotlinx.collections.immutable.toImmutableList
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@HiltViewModel
class InvestmentsViewModel @Inject constructor(
    private val investments: InvestmentRepository,
    private val savedPlans: SavedFinancialPlanRepository,
) : ViewModel() {

    /**
     * Test seam; production keeps the JSON codec, workbench replay and statement
     * parsing off Main (project idiom, cf. GoalsViewModel).
     */
    internal constructor(
        investments: InvestmentRepository,
        savedPlans: SavedFinancialPlanRepository,
        workDispatcher: CoroutineDispatcher,
    ) : this(investments, savedPlans) {
        this.workDispatcher = workDispatcher
    }

    /** Codec/replay/import dispatcher: no JSON parse or engine replay on Main. */
    private var workDispatcher: CoroutineDispatcher = Dispatchers.Default

    private val writeProblem = MutableStateFlow<InvestmentWriteProblem?>(null)
    private val portfolioProblem = MutableStateFlow<String?>(null)
    private val openPlanId = MutableStateFlow<String?>(null)
    private val allocationWrite = MutableStateFlow<PortfolioAllocationWrite?>(null)
    private val pendingImport = MutableStateFlow<PortfolioImportPreviewUi?>(null)
    private val pendingEntryPreview = MutableStateFlow<PortfolioEntryPreviewUi?>(null)
    private val restoredEntryDraft = MutableStateFlow<PortfolioRestoredEntryDraft?>(null)
    private val pendingOpeningDatePreview = MutableStateFlow<PortfolioOpeningDatePreviewUi?>(null)
    private var openingDateRequest = 0L

    /**
     * Revision this session last created/observed/wrote per portfolio document.
     * Mutations fence on exactly this captured value — never a fresh re-read — so a
     * write racing an external edit is refused as CONFLICT instead of overwriting.
     */
    private val sessionRevisions = HashMap<String, Long>()

    /**
     * The exact reviewed write captured at preview time: plan snapshot, corrected
     * document and session revision. The final save writes THIS document under THIS
     * revision CAS — it never re-reads or silently rebuilds against newer money.
     */
    private data class PendingEntrySave(
        val plan: SavedFinancialPlan,
        val document: PortfolioPlanDocument,
        val expectedRevision: Long,
    )

    private var pendingEntrySave: PendingEntrySave? = null
    private var pendingOpeningDateSave: PendingEntrySave? = null

    private data class PortfolioUi(
        val openPlanId: String? = null,
        val importPreview: PortfolioImportPreviewUi? = null,
        val entryPreview: PortfolioEntryPreviewUi? = null,
        val restoredEntryDraft: PortfolioRestoredEntryDraft? = null,
        val allocationWrite: PortfolioAllocationWrite? = null,
        val openingDatePreview: PortfolioOpeningDatePreviewUi? = null,
    )

    val state: StateFlow<InvestmentsState> = combine(
        investments.observePoolsWithContributions(),
        savedPlans.observe(SavedFinancialPlanKind.PORTFOLIO),
        writeProblem,
        portfolioProblem,
        combine(openPlanId, pendingImport, pendingEntryPreview, restoredEntryDraft, allocationWrite) { id, imported, entry, restored, allocation ->
            PortfolioUi(id, imported, entry, restored, allocation)
        }.combine(pendingOpeningDatePreview) { ui, date -> ui.copy(openingDatePreview = date) },
    ) { data, plans, problem, rejection, ui ->
        withContext(workDispatcher) {
            derive(data).copy(
                writeProblem = problem,
                portfolios = portfolioRows(plans).toImmutableList(),
                portfolioOpen = readyOpen(plans, ui.openPlanId, ui.entryPreview, ui.restoredEntryDraft, ui.openingDatePreview),
                importPreview = ui.importPreview,
                portfolioProblem = rejection,
                allocationWrite = ui.allocationWrite,
            )
        }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), InvestmentsState.initial())

    fun onEvent(event: InvestmentsEvent) {
        // Invalidate suspended previews synchronously, before queued coroutine work.
        if (event !is InvestmentsEvent.ConfirmPortfolioOpeningDatePreview) clearOpeningDateReview()
        val dateRequest = openingDateRequest
        if (event is InvestmentsEvent.SavePortfolioAllocation) {
            if (allocationWrite.value?.saving == true) return
            allocationWrite.value = PortfolioAllocationWrite(event.token, planId = event.planId, saving = true)
        }
        viewModelScope.launch {
            writeProblem.value = null
            portfolioProblem.value = null
            try {
                when (event) {
                    is InvestmentsEvent.SavePool -> {
                        if (event.pool.name.isBlank() || event.pool.period.isBlank()) {
                            writeProblem.value = InvestmentWriteProblem.INVALID_INPUT
                        } else if (!InvestmentEntryRules.isExactlyStorable(event.pool.totalReturn)) {
                            writeProblem.value = InvestmentWriteProblem.UNSUPPORTED_AMOUNT
                        } else investments.upsertPool(event.pool)
                    }
                    is InvestmentsEvent.SaveContribution -> {
                        if (event.owner.isBlank() || !event.amount.isPositive()) {
                            writeProblem.value = InvestmentWriteProblem.INVALID_INPUT
                        } else if (!InvestmentEntryRules.isExactlyStorable(event.amount)) {
                            writeProblem.value = InvestmentWriteProblem.UNSUPPORTED_AMOUNT
                        } else investments.upsertContribution(
                            InvestmentContribution(id = UUID.randomUUID().toString(), poolId = event.poolId, ownerName = event.owner, amount = event.amount),
                        )
                    }
                    is InvestmentsEvent.DeletePool -> investments.deletePool(event.id)
                    is InvestmentsEvent.DeleteContribution -> investments.deleteContribution(event.id)
                    is InvestmentsEvent.UpdatePoolReturnPercent -> updatePercent(event)

                    is InvestmentsEvent.CreatePortfolio -> createPortfolio(event)
                    is InvestmentsEvent.OpenPortfolio -> {
                        if (allocationWrite.value?.saving != true) allocationWrite.value = null
                        clearEntryReview()
                        state.value.portfolios.firstOrNull { it.planId == event.planId }
                            ?.let { sessionRevisions[it.planId] = it.revision }
                        openPlanId.value = event.planId
                    }
                    InvestmentsEvent.ClosePortfolio -> {
                        clearEntryReview()
                        openPlanId.value = null
                        pendingImport.value = null
                    }
                    is InvestmentsEvent.DeletePortfolio -> deletePortfolio(event.planId)
                    is InvestmentsEvent.AddPortfolioSecurity -> addSecurity(event)
                    is InvestmentsEvent.AddPortfolioEntry -> addEntry(event)
                    is InvestmentsEvent.UpdatePortfolioEntry -> editEntry(event)
                    is InvestmentsEvent.PreviewPortfolioEntry -> previewEntry(event)
                    is InvestmentsEvent.ConfirmPortfolioEntryPreview -> confirmEntryPreview(event.token)
                    InvestmentsEvent.CancelPortfolioEntryPreview -> cancelEntryPreview()
                    is InvestmentsEvent.PreviewPortfolioOpeningDate -> previewOpeningDate(event, dateRequest)
                    is InvestmentsEvent.ConfirmPortfolioOpeningDatePreview -> confirmOpeningDatePreview(event.token)
                    InvestmentsEvent.CancelPortfolioOpeningDatePreview -> clearOpeningDateReview()
                    is InvestmentsEvent.SavePortfolioAllocation -> saveAllocation(event)
                    is InvestmentsEvent.AddPortfolioQuote -> addQuote(event)
                    is InvestmentsEvent.DeletePortfolioEvent -> deleteEntry(event)
                    is InvestmentsEvent.ImportPortfolioStatement -> importStatement(event)
                    InvestmentsEvent.ConfirmPortfolioImport -> confirmImport()
                    InvestmentsEvent.CancelPortfolioImport -> pendingImport.value = null
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                writeProblem.value = InvestmentWriteProblem.STORAGE
            }
        }
    }

    // ---- legacy family pools (unchanged behavior) ----

    private suspend fun updatePercent(event: InvestmentsEvent.UpdatePoolReturnPercent) {
        val current = state.value
        val row = current.pools.firstOrNull { it.pool.id == event.poolId } ?: return
        val evidence = current.breakdowns.firstOrNull { it.poolId == event.poolId }
        if (evidence?.allocationAvailable != true) {
            writeProblem.value = InvestmentWriteProblem.UNSUPPORTED_ALLOCATION
            return
        }
        val value = InvestmentsCalc.returnFromPercent(row.totalCorpus, event.percent)
        if (!InvestmentEntryRules.isExactlyStorable(value)) {
            writeProblem.value = InvestmentWriteProblem.UNSUPPORTED_AMOUNT
            return
        }
        investments.upsertPool(row.pool.copy(totalReturn = value))
    }

    private fun derive(data: Map<InvestmentPool, List<InvestmentContribution>>): InvestmentsState {
        val breakdowns = InvestmentsBreakdown.fromRecords(data)
        val poolsById = data.keys.associateBy { it.id }
        val rows = breakdowns.map { evidence ->
            val pool = poolsById.getValue(evidence.poolId)
            val corpus = evidence.currencyGroups.single { it.isPoolReturnCurrency }.corpus
            val contributors = evidence.contributors.map { record ->
                ContributorRow(
                    id = record.id,
                    name = record.name,
                    amount = record.amount,
                    sharePercent = record.sharePercent,
                    shareReturn = record.shareOfDeclaredReturn,
                    netTotal = record.netHolding,
                    hasComposition = record.compositionFraction != null,
                )
            }.toImmutableList()
            // Display-only declared ratio; not annualized performance and never an FX base.
            val percent = if (evidence.allocationAvailable) {
                pool.totalReturn.amount.divide(corpus.amount, MathContext.DECIMAL128).movePointRight(2)
            } else null
            PoolRow(pool, corpus, contributors, percent)
        }.toImmutableList()
        return InvestmentsState(pools = rows, isLoading = false, breakdowns = breakdowns.toImmutableList())
    }

    // ---- dated securities portfolio: durable PORTFOLIO documents ----

    private suspend fun createPortfolio(event: InvestmentsEvent.CreatePortfolio) {
        val currency = event.currency.trim()
        val openingCash = PortfolioInputText.decimal(event.openingCashText)?.takeIf { it.signum() >= 0 }
        val openingDate = event.openingCashDateText.takeIf { it.isNotBlank() }?.let(PortfolioInputText::date)
        if (event.title.isBlank() || !Regex("[A-Z]{3}").matches(currency) || openingCash == null ||
            (event.openingCashDateText.isNotBlank() && openingDate == null)
        ) {
            writeProblem.value = InvestmentWriteProblem.INVALID_INPUT
            return
        }
        val now = System.currentTimeMillis()
        val payloadJson = withContext(workDispatcher) {
            PortfolioPlanCodec.encode(
                PortfolioPlanDocument(
                    currency = currency,
                    openingCashAmount = openingCash.toPlainString(),
                    openingCashDate = openingDate?.toString(),
                ),
            )
        }
        val plan = SavedFinancialPlan(
            id = UUID.randomUUID().toString(),
            kind = SavedFinancialPlanKind.PORTFOLIO,
            title = event.title.trim(),
            currency = currency,
            payloadVersion = PortfolioPlanCodec.VERSION,
            payloadJson = payloadJson,
            revision = 0,
            createdAtEpochMillis = now,
            updatedAtEpochMillis = now,
        )
        val saved = savedPlans.save(plan, expectedRevision = null)
        sessionRevisions[saved.id] = saved.revision
        openPlanId.value = saved.id
    }

    private suspend fun deletePortfolio(planId: String) {
        val plan = savedPlans.get(planId) ?: return
        savedPlans.delete(planId, plan.revision)
        if (openPlanId.value == planId) openPlanId.value = null
        if (pendingImport.value?.planId == planId) pendingImport.value = null
        if (pendingEntryPreview.value?.planId == planId || restoredEntryDraft.value?.planId == planId) {
            clearEntryReview()
        }
    }

    private suspend fun addSecurity(event: InvestmentsEvent.AddPortfolioSecurity) {
        val plan = savedPlans.get(event.planId) ?: return
        val document = decoded(plan) ?: return unsupportedDocument()
        val securityId = event.securityId.trim()
        val name = event.name.trim()
        val classToken = event.assetClass?.trim()?.ifEmpty { null }
        val assetClass = classToken?.let { token -> AssetClass.entries.firstOrNull { it.name == token } }
        if (classToken != null && assetClass == null) {
            writeProblem.value = InvestmentWriteProblem.INVALID_INPUT
            return
        }
        if (securityId.isEmpty() || name.isEmpty()) {
            writeProblem.value = InvestmentWriteProblem.INVALID_INPUT
            return
        }
        if (document.securities.any { it.securityId == securityId }) {
            portfolioProblem.value = PortfolioRejectKey.DUPLICATE_SECURITY_ID
            return
        }
        mutate(plan, document.copy(securities = document.securities + SecurityInput(securityId, name, assetClass?.name)))
    }

    private suspend fun addEntry(event: InvestmentsEvent.AddPortfolioEntry) {
        val plan = savedPlans.get(event.planId) ?: return
        val document = decoded(plan) ?: return unsupportedDocument()
        val input = entryInput(event.entry, document) ?: run {
            writeProblem.value = InvestmentWriteProblem.INVALID_INPUT
            return
        }
        mutate(plan, document.copy(events = document.events + input))
    }

    /**
     * In-place correction of one stored event: the corrected [EventInput] replaces the
     * event at its ORIGINAL index with the SAME stable event ID — no delete+recreate.
     * The whole corrected document is re-validated by the reviewed engines and saved
     * under the session revision fence; anything refused writes nothing.
     */
    private suspend fun editEntry(event: InvestmentsEvent.UpdatePortfolioEntry) {
        val plan = savedPlans.get(event.planId) ?: return
        val document = decoded(plan) ?: return unsupportedDocument()
        val index = document.events.indexOfFirst { it.eventId == event.eventId }
        if (index < 0) {
            writeProblem.value = InvestmentWriteProblem.INVALID_INPUT
            return
        }
        val corrected = editedEventInput(event.entry, document.events[index], document) ?: run {
            writeProblem.value = InvestmentWriteProblem.INVALID_INPUT
            return
        }
        mutate(plan, document.copy(events = document.events.toMutableList().apply { this[index] = corrected }))
    }

    /**
     * Review step of the edit seam: recomputes the exact before/after picture through
     * the SAME reviewed engines that will judge the final write — entirely off Main,
     * never in a composable — and captures the corrected document, event id and
     * session revision. NOTHING is written here; the write happens only when the user
     * explicitly confirms, bound to exactly this capture.
     */
    private suspend fun previewEntry(event: InvestmentsEvent.PreviewPortfolioEntry) {
        val plan = savedPlans.get(event.planId) ?: return
        val document = decoded(plan) ?: return unsupportedDocument()
        val index = document.events.indexOfFirst { it.eventId == event.eventId }
        if (index < 0) {
            writeProblem.value = InvestmentWriteProblem.INVALID_INPUT
            return
        }
        val expectedRevision = sessionRevisions[plan.id] ?: plan.revision
        if (plan.revision != expectedRevision) {
            writeProblem.value = InvestmentWriteProblem.CONFLICT
            return
        }
        val corrected = editedEventInput(event.entry, document.events[index], document) ?: run {
            writeProblem.value = InvestmentWriteProblem.INVALID_INPUT
            return
        }
        val correctedDocument = document.copy(events = document.events.toMutableList().apply { this[index] = corrected })
        val benches = withContext(workDispatcher) {
            PortfolioWorkbench.build(document) to PortfolioWorkbench.build(correctedDocument)
        }
        val rejection = benches.second.rejection ?: benches.first.rejection
        if (rejection != null) {
            portfolioProblem.value = rejection
            return
        }
        pendingEntrySave = PendingEntrySave(plan, correctedDocument, expectedRevision)
        restoredEntryDraft.value = null
        pendingEntryPreview.value = PortfolioEntryPreviewUi(
            token = java.util.UUID.randomUUID().toString(),
            planId = plan.id,
            eventId = event.eventId,
            draft = event.entry,
            currency = plan.currency,
            before = previewTotals(benches.first),
            after = previewTotals(benches.second),
        )
    }

    /**
     * Final explicit save: consumes the review exactly once (a repeated confirm is a
     * no-op), refuses a stale/superseded preview as CONFLICT without writing, then
     * saves the CAPTURED corrected document under the captured revision CAS. The
     * repository CAS stays the last guard; nothing is rebuilt against newer state.
     */
    private suspend fun confirmEntryPreview(token: String) {
        if (pendingEntryPreview.value?.token != token) return
        val pending = pendingEntrySave ?: return
        pendingEntrySave = null
        pendingEntryPreview.value = null
        restoredEntryDraft.value = null
        val stored = savedPlans.get(pending.plan.id)
        if (stored == null || stored.revision != pending.expectedRevision) {
            writeProblem.value = InvestmentWriteProblem.CONFLICT
            return
        }
        try {
            val encoded = withContext(workDispatcher) { encodeLoadablePortfolio(pending.document) }
                ?: return unsupportedDocument()
            val saved = savedPlans.save(
                pending.plan.copy(
                    payloadVersion = PortfolioPlanCodec.VERSION,
                    payloadJson = encoded,
                    revision = pending.expectedRevision,
                    updatedAtEpochMillis = System.currentTimeMillis(),
                ),
                expectedRevision = pending.expectedRevision,
            )
            sessionRevisions[pending.plan.id] = saved.revision
        } catch (conflict: SavedFinancialPlanConflictException) {
            writeProblem.value = InvestmentWriteProblem.CONFLICT
        } catch (validation: SavedFinancialPlanValidationException) {
            writeProblem.value = InvestmentWriteProblem.UNSUPPORTED_DOCUMENT
        }
    }

    /**
     * Cancels the pending review: writes nothing and hands the exact reviewed draft
     * back so the editor can reopen with the input preserved. When no review is
     * pending (the editor was dismissed after a cancel) it just drops the preserved
     * draft. Either way storage is untouched.
     */
    private fun cancelEntryPreview() {
        val pending = pendingEntryPreview.value
        pendingEntrySave = null
        pendingEntryPreview.value = null
        restoredEntryDraft.value = pending?.let {
            PortfolioRestoredEntryDraft(planId = it.planId, eventId = it.eventId, draft = it.draft)
        }
    }

    private fun clearEntryReview() {
        pendingEntrySave = null
        pendingEntryPreview.value = null
        restoredEntryDraft.value = null
    }

    /** Review only; preserve every unrelated input literal and capture the exact revision. */
    private suspend fun previewOpeningDate(event: InvestmentsEvent.PreviewPortfolioOpeningDate, request: Long) {
        if (request != openingDateRequest || openPlanId.value != event.planId) return
        val plan = savedPlans.get(event.planId) ?: return
        val document = decoded(plan) ?: return unsupportedDocument()
        val expectedRevision = sessionRevisions[plan.id] ?: plan.revision
        if (plan.revision != expectedRevision || pendingEntryPreview.value != null) {
            writeProblem.value = InvestmentWriteProblem.CONFLICT
            return
        }
        val edited = event.dateText.takeIf { it.isNotEmpty() }
            ?.let { PortfolioInputText.date(it)?.toString()?.takeIf { parsed -> parsed == it } }
        if ((event.dateText.isNotEmpty() && edited == null) || edited == document.openingCashDate) {
            writeProblem.value = InvestmentWriteProblem.INVALID_INPUT
            return
        }
        val corrected = document.copy(openingCashDate = edited)
        val benches = withContext(workDispatcher) {
            PortfolioWorkbench.build(document) to PortfolioWorkbench.build(corrected)
        }
        if (request != openingDateRequest || openPlanId.value != event.planId) return
        val rejection = benches.second.rejection ?: benches.first.rejection
        if (rejection != null) {
            portfolioProblem.value = rejection
            return
        }
        pendingOpeningDateSave = PendingEntrySave(plan, corrected, expectedRevision)
        pendingOpeningDatePreview.value = PortfolioOpeningDatePreviewUi(
            token = UUID.randomUUID().toString(), planId = plan.id,
            currentText = document.openingCashDate, draftText = event.dateText,
            currency = plan.currency, before = previewTotals(benches.first), after = previewTotals(benches.second),
        )
    }

    /** Consume once; save only the immutable document and revision the user reviewed. */
    private suspend fun confirmOpeningDatePreview(token: String) {
        if (pendingOpeningDatePreview.value?.token != token) return
        val pending = pendingOpeningDateSave ?: return
        clearOpeningDateReview()
        val stored = savedPlans.get(pending.plan.id)
        if (stored == null || stored.revision != pending.expectedRevision) {
            writeProblem.value = InvestmentWriteProblem.CONFLICT
            return
        }
        try {
            val encoded = withContext(workDispatcher) { encodeLoadablePortfolio(pending.document) }
                ?: return unsupportedDocument()
            val saved = savedPlans.save(
                pending.plan.copy(
                    payloadVersion = PortfolioPlanCodec.VERSION, payloadJson = encoded,
                    revision = pending.expectedRevision, updatedAtEpochMillis = System.currentTimeMillis(),
                ),
                expectedRevision = pending.expectedRevision,
            )
            sessionRevisions[pending.plan.id] = saved.revision
        } catch (_: SavedFinancialPlanConflictException) {
            writeProblem.value = InvestmentWriteProblem.CONFLICT
        } catch (_: SavedFinancialPlanValidationException) {
            writeProblem.value = InvestmentWriteProblem.UNSUPPORTED_DOCUMENT
        }
    }

    private fun clearOpeningDateReview() {
        openingDateRequest += 1
        pendingOpeningDateSave = null
        pendingOpeningDatePreview.value = null
    }

    /** Totals exactly as the engines replayed the document; null accounting = honest empty state. */
    private fun previewTotals(bench: PortfolioWorkbench): PortfolioPreviewTotals {
        val accounting = bench.accounting ?: return PortfolioPreviewTotals()
        return PortfolioPreviewTotals(
            cashAmount = accounting.cash.amount,
            pricedHoldingsAmount = accounting.valuation.pricedHoldingsValue.amount,
            totalAmount = accounting.valuation.total?.amount,
        )
    }

    /**
     * Builds the corrected [EventInput] keeping the original's stable event ID. Every
     * value comes verbatim from the prefilled draft; any field left untouched (same
     * kind) keeps the EXACT stored literal — date, quantity, unit price, amount,
     * securityId (never trimmed), fee, note; absent stays absent. Nothing is invented,
     * reformatted, or silently dropped. Genuinely changed fields still go through the
     * parsers, so validation never weakens.
     */
    private fun editedEventInput(
        draft: PortfolioEntryDraft,
        original: EventInput,
        document: PortfolioPlanDocument,
    ): EventInput? {
        val sameKind = draft.kind == original.kind
        val date = if (draft.dateText == original.date) {
            original.date
        } else {
            PortfolioInputText.date(draft.dateText)?.toString() ?: return null
        }
        val securityId = when {
            sameKind && draft.securityId == original.securityId.orEmpty() -> original.securityId
            else -> draft.securityId.trim()
        }
        val knownSecurity = securityId != null && securityId.isNotEmpty() &&
            document.securities.any { it.securityId == securityId }
        val quantity = when {
            sameKind && draft.quantityText == original.quantity.orEmpty() -> original.quantity
            else -> PortfolioInputText.positiveDecimal(draft.quantityText)?.toPlainString()
        }
        val price = when {
            sameKind && draft.priceText == original.unitPrice.orEmpty() -> original.unitPrice
            else -> PortfolioInputText.positiveDecimal(draft.priceText)?.toPlainString()
        }
        val amount = when {
            sameKind && draft.amountText == original.amount.orEmpty() -> original.amount
            else -> PortfolioInputText.positiveDecimal(draft.amountText)?.toPlainString()
        }
        val fee = when {
            sameKind && draft.feeText == original.fee.orEmpty() -> original.fee
            draft.feeText.isBlank() -> if (sameKind) original.fee else null
            else -> PortfolioInputText.nonNegativeDecimal(draft.feeText)?.toPlainString() ?: return null
        }
        val note = when {
            sameKind && draft.note == original.note.orEmpty() -> original.note
            draft.note.isBlank() -> if (sameKind) original.note else null
            else -> draft.note.trim()
        }
        return when (draft.kind) {
            PortfolioEntryKind.BUY, PortfolioEntryKind.SELL ->
                if (!knownSecurity || quantity == null || price == null) {
                    null
                } else {
                    EventInput(
                        kind = draft.kind, eventId = original.eventId, date = date, securityId = securityId,
                        quantity = quantity, unitPrice = price, fee = fee, note = note,
                    )
                }
            PortfolioEntryKind.CONTRIBUTION, PortfolioEntryKind.WITHDRAWAL, PortfolioEntryKind.FEE ->
                if (amount == null) {
                    null
                } else {
                    EventInput(kind = draft.kind, eventId = original.eventId, date = date, amount = amount, note = note)
                }
            PortfolioEntryKind.CASH_DIVIDEND ->
                if (!knownSecurity || amount == null) {
                    null
                } else {
                    EventInput(
                        kind = draft.kind, eventId = original.eventId, date = date, securityId = securityId,
                        amount = amount, note = note,
                        destination = if (draft.externalDestination) "EXTERNAL_ACCOUNT" else "PORTFOLIO_CASH",
                    )
                }
            PortfolioEntryKind.REINVESTMENT ->
                if (!knownSecurity || quantity == null || price == null || amount == null) {
                    null
                } else {
                    EventInput(
                        kind = draft.kind, eventId = original.eventId, date = date, securityId = securityId,
                        quantity = quantity, unitPrice = price, amount = amount, note = note,
                    )
                }
        }
    }

    private suspend fun addQuote(event: InvestmentsEvent.AddPortfolioQuote) {
        val plan = savedPlans.get(event.planId) ?: return
        val document = decoded(plan) ?: return unsupportedDocument()
        val securityId = event.securityId.trim()
        val date = PortfolioInputText.date(event.dateText)
        val price = PortfolioInputText.nonNegativeDecimal(event.priceText)
        if (securityId.isEmpty() || !document.securities.any { it.securityId == securityId } ||
            date == null || price == null
        ) {
            writeProblem.value = InvestmentWriteProblem.INVALID_INPUT
            return
        }
        val quote = QuoteInput(securityId, date.toString(), price.toPlainString())
        if (document.quotes.any { it.securityId == quote.securityId && it.date == quote.date }) {
            portfolioProblem.value = PortfolioRejectKey.AMBIGUOUS_QUOTES
            return
        }
        mutate(plan, document.copy(quotes = document.quotes + quote))
    }

    private suspend fun deleteEntry(event: InvestmentsEvent.DeletePortfolioEvent) {
        val plan = savedPlans.get(event.planId) ?: return
        val document = decoded(plan) ?: return unsupportedDocument()
        mutate(plan, document.copy(events = document.events.filterNot { it.eventId == event.eventId }))
    }

    private suspend fun importStatement(event: InvestmentsEvent.ImportPortfolioStatement) {
        val plan = savedPlans.get(event.planId) ?: return
        val document = decoded(plan) ?: return unsupportedDocument()
        val text = event.text
        pendingImport.value = withContext(workDispatcher) {
            val parse = if (text.trim().startsWith("[")) {
                PortfolioStatementImport.parseJsonRows(text)
            } else {
                PortfolioStatementImport.parseCsv(text)
            }
            PortfolioImportPreviewUi(
                planId = plan.id,
                title = plan.title,
                preview = PortfolioStatementImport.preview(document, parse),
            )
        }
    }

    private suspend fun confirmImport() {
        val preview = pendingImport.value ?: return
        val merged = preview.preview.merged ?: return
        val plan = savedPlans.get(preview.planId) ?: return
        mutate(plan, merged)
        pendingImport.value = null
    }

    /** Builds a typed [EventInput] from the draft, or null when any field is invalid/unknown. */
    private fun entryInput(draft: PortfolioEntryDraft, document: PortfolioPlanDocument): EventInput? {
        val date = PortfolioInputText.date(draft.dateText)?.toString() ?: return null
        val id = "evt-" + UUID.randomUUID().toString()
        val securityId = draft.securityId.trim()
        val knownSecurity = securityId.isNotEmpty() && document.securities.any { it.securityId == securityId }
        val quantity = PortfolioInputText.positiveDecimal(draft.quantityText)?.toPlainString()
        val price = PortfolioInputText.positiveDecimal(draft.priceText)?.toPlainString()
        val amount = PortfolioInputText.positiveDecimal(draft.amountText)?.toPlainString()
        val fee = draft.feeText.trim().ifEmpty { "0" }.let { PortfolioInputText.nonNegativeDecimal(it)?.toPlainString() }
        val note = draft.note.trim().ifEmpty { null }
        return when (draft.kind) {
            PortfolioEntryKind.BUY, PortfolioEntryKind.SELL ->
                if (!knownSecurity) {
                    null
                } else {
                    EventInput(
                        kind = draft.kind, eventId = id, date = date, securityId = securityId,
                        quantity = quantity, unitPrice = price, fee = fee,
                        note = note,
                    )
                }
            PortfolioEntryKind.CONTRIBUTION, PortfolioEntryKind.WITHDRAWAL, PortfolioEntryKind.FEE ->
                EventInput(kind = draft.kind, eventId = id, date = date, amount = amount, note = note)
            PortfolioEntryKind.CASH_DIVIDEND ->
                if (!knownSecurity) {
                    null
                } else {
                    EventInput(
                        kind = draft.kind, eventId = id, date = date, securityId = securityId,
                        amount = amount,
                        note = note,
                        destination = if (draft.externalDestination) "EXTERNAL_ACCOUNT" else "PORTFOLIO_CASH",
                    )
                }
            PortfolioEntryKind.REINVESTMENT ->
                if (!knownSecurity) {
                    null
                } else {
                    EventInput(
                        kind = draft.kind, eventId = id, date = date, securityId = securityId,
                        quantity = quantity, unitPrice = price, amount = amount,
                        note = note,
                    )
                }
        }
    }

    private suspend fun saveAllocation(event: InvestmentsEvent.SavePortfolioAllocation) {
        fun fail(problem: AllocationWriteProblem) {
            allocationWrite.value = PortfolioAllocationWrite(event.token, planId = event.planId, problem = problem)
        }
        try {
            val targets = when (val parsed = validateAllocationDraft(event.draft)) {
                is AllocationTargetResult.Invalid -> return fail(parsed.problem)
                is AllocationTargetResult.Valid -> parsed.targets
            }
            val plan = savedPlans.get(event.planId) ?: return fail(AllocationWriteProblem.UNSUPPORTED)
            if (plan.revision != event.expectedRevision || pendingEntryPreview.value != null) {
                return fail(AllocationWriteProblem.CONFLICT)
            }
            val document = decoded(plan) ?: return fail(AllocationWriteProblem.UNSUPPORTED)
            val updated = document.copy(allocationTargets = targets)
            val encoded = withContext(workDispatcher) {
                val bench = PortfolioWorkbench.build(updated)
                if (bench.rejection != null || bench.allocationProblem != null) null else encodeLoadablePortfolio(updated)
            } ?: return fail(AllocationWriteProblem.INVALID)
            val saved = savedPlans.save(
                plan.copy(payloadJson = encoded, updatedAtEpochMillis = System.currentTimeMillis()),
                expectedRevision = event.expectedRevision,
            )
            sessionRevisions[plan.id] = saved.revision
            allocationWrite.value = PortfolioAllocationWrite(event.token, planId = event.planId, saved = true)
        } catch (cancelled: CancellationException) {
            fail(AllocationWriteProblem.STORAGE)
            throw cancelled
        } catch (_: SavedFinancialPlanConflictException) {
            fail(AllocationWriteProblem.CONFLICT)
        } catch (_: Exception) {
            fail(AllocationWriteProblem.STORAGE)
        }
    }

    /** Validates the whole updated document through the reviewed engines BEFORE any write. */
    private suspend fun mutate(plan: SavedFinancialPlan, document: PortfolioPlanDocument) {
        // Revision fence FIRST: a snapshot that already lost the race to an external
        // writer is refused as CONFLICT without replaying, so the newer external edit
        // always survives and nothing of ours is ever validated against stale state.
        val expectedRevision = sessionRevisions[plan.id] ?: plan.revision
        if (plan.revision != expectedRevision) {
            writeProblem.value = InvestmentWriteProblem.CONFLICT
            return
        }
        val rejection = withContext(workDispatcher) { PortfolioWorkbench.build(document).rejection }
        if (rejection != null) {
            portfolioProblem.value = rejection
            return
        }
        try {
            val encoded = withContext(workDispatcher) { encodeLoadablePortfolio(document) }
                ?: return unsupportedDocument()
            val saved = savedPlans.save(
                plan.copy(
                    payloadVersion = PortfolioPlanCodec.VERSION,
                    payloadJson = encoded,
                    revision = plan.revision,
                    updatedAtEpochMillis = System.currentTimeMillis(),
                ),
                expectedRevision = expectedRevision,
            )
            sessionRevisions[plan.id] = saved.revision
        } catch (conflict: SavedFinancialPlanConflictException) {
            writeProblem.value = InvestmentWriteProblem.CONFLICT
        } catch (validation: SavedFinancialPlanValidationException) {
            writeProblem.value = InvestmentWriteProblem.UNSUPPORTED_DOCUMENT
        }
    }

    /**
     * Decodes only documents this build supports AND whose payload currency agrees
     * with the outer stored row; any disagreement stays retained but unloadable.
     */
    private fun decoded(plan: SavedFinancialPlan): PortfolioPlanDocument? =
        when (val result = PortfolioPlanCodec.decode(plan.payloadJson)) {
            is PortfolioDecodeResult.Success -> result.document.takeIf { it.currency == plan.currency }
            is PortfolioDecodeResult.UnsupportedVersion, is PortfolioDecodeResult.Malformed -> null
        }

    private fun unsupportedDocument() {
        writeProblem.value = InvestmentWriteProblem.UNSUPPORTED_DOCUMENT
    }

    private fun portfolioRows(plans: List<SavedFinancialPlan>) = plans
        .sortedWith(compareByDescending<SavedFinancialPlan> { it.updatedAtEpochMillis }.thenBy { it.id })
        .map { plan ->
            val document = decoded(plan)
            val docState = when {
                document == null -> PortfolioDocState.Unavailable
                else -> {
                    val bench = PortfolioWorkbench.build(document)
                    if (bench.rejection != null) PortfolioDocState.Rejected(bench.rejection) else PortfolioDocState.Ready(bench)
                }
            }
            PortfolioRow(plan.id, plan.title, plan.currency, plan.revision, plan.updatedAtEpochMillis, docState)
        }

    private fun readyOpen(
        plans: List<SavedFinancialPlan>,
        openId: String?,
        entryPreview: PortfolioEntryPreviewUi?,
        restoredEntryDraft: PortfolioRestoredEntryDraft?,
        openingDatePreview: PortfolioOpeningDatePreviewUi?,
    ): PortfolioOpen? {
        if (openId == null) return null
        val plan = plans.firstOrNull { it.id == openId } ?: return null
        val document = decoded(plan) ?: return null
        val bench = PortfolioWorkbench.build(document)
        if (bench.rejection != null) return null
        return PortfolioOpen(
            planId = plan.id,
            title = plan.title,
            currency = plan.currency,
            revision = plan.revision,
            workbench = bench,
            entryPreview = entryPreview?.takeIf { it.planId == plan.id },
            restoredEntryDraft = restoredEntryDraft?.takeIf { it.planId == plan.id },
            openingDatePreview = openingDatePreview?.takeIf { it.planId == plan.id },
        )
    }
}

/** Stable safe tokens mirrored from the engine layer for UI mapping. */
private object PortfolioRejectKey {
    const val DUPLICATE_SECURITY_ID = "DUPLICATE_SECURITY_ID"
    const val AMBIGUOUS_QUOTES = "AMBIGUOUS_QUOTES"
}
