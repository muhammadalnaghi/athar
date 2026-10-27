package com.athar.ingestion

import com.athar.core.data.ledger.api.CanonicalPurchaseRecorder
import com.athar.core.data.ledger.api.FinancialRecordingSession
import com.athar.core.data.ledger.api.NewPurchaseProjection
import com.athar.core.domain.calc.financialgraph.FinancialGraphFactory
import com.athar.core.domain.model.IngestSource
import com.athar.core.domain.model.RawIngestEvent
import com.athar.core.domain.model.TxType
import com.athar.core.domain.model.financial.AmountSupport
import com.athar.core.domain.model.financial.EconomicKind
import com.athar.core.domain.model.financial.FinancialObservation
import com.athar.core.domain.model.financial.MovementDirection
import com.athar.core.domain.model.financial.PostingState
import com.athar.core.domain.model.financial.PostingSupport
import com.athar.core.domain.model.financial.RoutingCoverage
import com.athar.core.domain.model.financial.StableObservationId
import com.athar.core.domain.model.financial.SupportedMoney
import com.athar.core.domain.model.financialgraph.ComponentSpec
import com.athar.core.domain.model.financialgraph.ComponentType
import com.athar.core.domain.model.financialgraph.FinancialGraphDraft
import com.athar.core.domain.model.financialgraph.FinancialGraphOutcome
import com.athar.ingestion.smsparser.ParseResult
import kotlinx.datetime.Instant
import javax.inject.Inject
import javax.inject.Singleton

/** Mapping seam only: generic extracted amounts and unresolved transfers never certify purchases. */
@Singleton
class FinancialRecordingAdapter @Inject constructor(
    private val recorder: CanonicalPurchaseRecorder,
    private val rollout: FinancialRecordingRollout,
) {
    fun beginSessionIfEnabled(): FinancialRecordingSession? = if (rollout.isEnabled()) recorder.beginSession() else null

    suspend fun recordNewPurchase(
        event: RawIngestEvent,
        parsed: ParseResult.Success,
        transactionId: String,
        createdAt: Instant,
        session: FinancialRecordingSession,
    ) {
        val draft = supportedPurchaseDraft(event, parsed) ?: supportedCardRepaymentDraft(event, parsed) ?: return
        val built = FinancialGraphFactory.build(draft) as? FinancialGraphOutcome.Created ?: return
        val bytes = event.body.toByteArray(Charsets.UTF_8)
        val evidenceRef = "ingest:${draft.sourceNamespace}:${event.rawId}"
        val projection = NewPurchaseProjection(transactionId, createdAt, createdAt, session)
        if (built.graph.kind == EconomicKind.CARD_REPAYMENT) {
            recorder.recordCardRepayment(built.graph, bytes, evidenceRef, projection)
        } else {
            recorder.record(built.graph, bytes, evidenceRef, projection)
        }
    }
}

internal fun supportedPurchaseDraft(event: RawIngestEvent, parsed: ParseResult.Success): FinancialGraphDraft? {
    val namespace = when (event.source) {
        IngestSource.SMS -> "sms"
        IngestSource.NOTIFICATION -> "notification"
        else -> return null
    }
    // These bank-specific templates extract the PURCHASE amount, not the first numeric amount.
    val supported = setOf(
        "al-rajhi-online-purchase-real", "al-rajhi-pos-purchase-real",
        "d360-online-purchase", "d360-local-purchase", "d360-international-purchase",
        "stc-bank-online-purchase", "barq-online-purchase", "barq-pos-international",
        "stcpay-online-purchase",
    )
    if (parsed.templateId !in supported || parsed.type != TxType.EXPENSE ||
        parsed.posting.state != PostingState.POSTED || parsed.posting.support != PostingSupport.EXPLICIT_POSTED ||
        parsed.bankEventDate == null || !parsed.amount.isPositive()) return null
    val observation = FinancialObservation(
        stableId = StableObservationId(event.rawId), posting = parsed.posting,
        economicKind = EconomicKind.PURCHASE, direction = MovementDirection.OUT,
        original = SupportedMoney(parsed.amount, AmountSupport.EXPLICIT_TRANSACTION), settlement = null,
        routing = RoutingCoverage.Unresolved, eventDate = parsed.bankEventDate, legacy = null, userDeclaration = null,
    )
    return FinancialGraphDraft(
        eventId = "evt:$namespace:${event.rawId}", observation = observation, sourceNamespace = namespace,
        components = listOf(ComponentSpec("principal:${event.rawId}", ComponentType.PRINCIPAL, parsed.amount,
            null, null, AmountSupport.EXPLICIT_TRANSACTION, "ingest:$namespace:${event.rawId}:amount")),
        legs = emptyList(), category = null,
    )
}
