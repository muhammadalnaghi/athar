package com.athar.ingestion

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
import com.athar.ingestion.smsparser.ParseResult

/** Bank-bound SR/SAR repayment evidence, not a generic transfer or an expense. */
internal fun supportedCardRepaymentDraft(
    event: RawIngestEvent,
    parsed: ParseResult.Success,
): FinancialGraphDraft? {
    val namespace = when (event.source) {
        IngestSource.SMS -> "sms"
        IngestSource.NOTIFICATION -> "notification"
        else -> return null
    }
    if (parsed.templateId != "al-rajhi-credit-card-payment" || parsed.type != TxType.TRANSFER ||
        parsed.posting.state != PostingState.POSTED || parsed.posting.support != PostingSupport.EXPLICIT_POSTED ||
        parsed.bankEventDate == null || parsed.amount.currency != "SAR" || !parsed.amount.isPositive()
    ) return null
    val observation = FinancialObservation(
        stableId = StableObservationId(event.rawId),
        posting = parsed.posting,
        economicKind = EconomicKind.CARD_REPAYMENT,
        direction = MovementDirection.OUT,
        original = SupportedMoney(parsed.amount, AmountSupport.EXPLICIT_TRANSACTION),
        settlement = null,
        routing = RoutingCoverage.Unresolved,
        eventDate = parsed.bankEventDate,
        legacy = null,
        userDeclaration = null,
    )
    return FinancialGraphDraft(
        eventId = "evt:$namespace:${event.rawId}",
        observation = observation,
        sourceNamespace = namespace,
        components = listOf(ComponentSpec(
            "principal:${event.rawId}", ComponentType.PRINCIPAL, parsed.amount,
            null, null, AmountSupport.EXPLICIT_TRANSACTION, "ingest:$namespace:${event.rawId}:amount",
        )),
        legs = emptyList(),
        category = null,
    )
}
