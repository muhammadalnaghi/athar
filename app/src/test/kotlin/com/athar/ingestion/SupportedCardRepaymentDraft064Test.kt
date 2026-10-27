package com.athar.ingestion

import com.athar.core.common.money.Money
import com.athar.core.domain.calc.financialgraph.FinancialGraphFactory
import com.athar.core.domain.model.IngestSource
import com.athar.core.domain.model.RawIngestEvent
import com.athar.core.domain.model.TxType
import com.athar.core.domain.model.financial.EconomicKind
import com.athar.core.domain.model.financial.MovementDirection
import com.athar.core.domain.model.financial.PostingEvidence
import com.athar.core.domain.model.financial.PostingState
import com.athar.core.domain.model.financial.PostingSupport
import com.athar.core.domain.model.financialgraph.FinancialGraphOutcome
import com.athar.ingestion.smsparser.ParseResult
import com.google.common.truth.Truth.assertThat
import kotlinx.datetime.Instant
import kotlinx.datetime.LocalDate
import org.junit.jupiter.api.Test

class SupportedCardRepaymentDraft064Test {
    private val event = RawIngestEvent(
        "public-event", IngestSource.SMS, "AlRajhiBank", "Public card repayment",
        Instant.parse("2026-10-20T10:00:00Z"), "public-source",
    )
    private val parsed = ParseResult.Success(
        TxType.TRANSFER, Money.of("125.50", "SAR"), "Credit Card Payment", null, null, null,
        0f, "al-rajhi-credit-card-payment",
        PostingEvidence(PostingState.POSTED, PostingSupport.EXPLICIT_POSTED), LocalDate.parse("2026-10-20"),
    )

    @Test
    fun `explicit repayment becomes card repayment never purchase or income`() {
        val draft = checkNotNull(supportedCardRepaymentDraft(event, parsed))
        assertThat(draft.observation.economicKind).isEqualTo(EconomicKind.CARD_REPAYMENT)
        assertThat(draft.observation.direction).isEqualTo(MovementDirection.OUT)
        assertThat(draft.observation.original!!.amount).isEqualTo(parsed.amount)
        assertThat(draft.observation.eventDate).isEqualTo(parsed.bankEventDate)
        assertThat(draft.legs).isEmpty()
        assertThat(draft.category).isNull()
        assertThat(draft.components).hasSize(1)
        val result = FinancialGraphFactory.build(draft)
        assertThat(result).isInstanceOf(FinancialGraphOutcome.Created::class.java)
        assertThat((result as FinancialGraphOutcome.Created).graph.kind).isEqualTo(EconomicKind.CARD_REPAYMENT)
        assertThat(supportedPurchaseDraft(event, parsed)).isNull()
    }

    @Test
    fun `all nonposted states and heuristic evidence stay unresolved`() {
        for (state in PostingState.entries.filter { it != PostingState.POSTED }) {
            assertThat(supportedCardRepaymentDraft(event, parsed.copy(
                posting = PostingEvidence(state, PostingSupport.HEURISTIC_ONLY),
            ))).isNull()
        }
        assertThat(supportedCardRepaymentDraft(event, parsed.copy(
            posting = PostingEvidence(PostingState.POSTED, PostingSupport.HEURISTIC_ONLY),
        ))).isNull()
    }

    @Test
    fun `missing bank date never uses received time`() {
        assertThat(supportedCardRepaymentDraft(event, parsed.copy(bankEventDate = null))).isNull()
    }

    @Test
    fun `unrelated template and nontransfer types are not repayments`() {
        for (id in listOf("universal-amount", "al-rajhi-generic-amount", "al-rajhi-pos-purchase-real")) {
            assertThat(supportedCardRepaymentDraft(event, parsed.copy(templateId = id))).isNull()
        }
        for (type in listOf(TxType.EXPENSE, TxType.INCOME)) {
            assertThat(supportedCardRepaymentDraft(event, parsed.copy(type = type))).isNull()
        }
    }

    @Test
    fun `zero negative and foreign amounts do not enter this SR scoped path`() {
        for (amount in listOf(Money.of("0"), Money.of("-125.50"), Money.of("125.50", "USD"))) {
            assertThat(supportedCardRepaymentDraft(event, parsed.copy(amount = amount))).isNull()
        }
    }

    @Test
    fun `notification namespace and exact identifiers are preserved`() {
        val draft = checkNotNull(supportedCardRepaymentDraft(event.copy(source = IngestSource.NOTIFICATION), parsed))
        assertThat(draft.sourceNamespace).isEqualTo("notification")
        assertThat(draft.eventId).isEqualTo("evt:notification:public-source")
        assertThat(draft.observation.stableId.value).isEqualTo("public-source")
        assertThat(supportedCardRepaymentDraft(event.copy(source = IngestSource.MANUAL), parsed)).isNull()
    }
}
