package com.athar.ingestion

import com.athar.core.common.money.Money
import com.athar.core.domain.model.IngestSource
import com.athar.core.domain.model.RawIngestEvent
import com.athar.core.domain.model.TxType
import com.athar.core.domain.model.financial.PostingEvidence
import com.athar.core.domain.model.financial.PostingState
import com.athar.core.domain.model.financial.PostingSupport
import com.athar.ingestion.smsparser.ParseResult
import com.google.common.truth.Truth.assertThat
import kotlinx.datetime.Instant
import kotlinx.datetime.LocalDate
import org.junit.jupiter.api.Test

class SupportedPurchaseDraftTest {
    private val event = RawIngestEvent("event", IngestSource.SMS, "PublicBank", "Public completed purchase", Instant.parse("2026-10-20T10:00:00Z"), "source")
    private val parsed = ParseResult.Success(TxType.EXPENSE, Money.of("25.00"), "Public shop", null, null, null,
        .1f, "al-rajhi-pos-purchase-real", PostingEvidence(PostingState.POSTED, PostingSupport.EXPLICIT_POSTED), LocalDate.parse("2026-10-20"))

    @Test fun `supported completed purchase needs no category or confidence`() {
        val draft = checkNotNull(supportedPurchaseDraft(event, parsed))
        assertThat(draft.category).isNull()
        assertThat(draft.observation.original!!.amount).isEqualTo(parsed.amount)
        assertThat(draft.observation.eventDate).isEqualTo(parsed.bankEventDate)
    }
    @Test fun `stcpay online purchase with full evidence is admissible`() {
        // WM073: the two actual corpus rows are EXPENSE/POSTED/EXPLICIT_POSTED with a
        // stated date — same strict guards, one more template admitted.
        val draft = checkNotNull(supportedPurchaseDraft(event, parsed.copy(templateId = "stcpay-online-purchase")))
        assertThat(draft.observation.eventDate).isEqualTo(parsed.bankEventDate)
    }
    @Test fun `generic amount never becomes certified purchase`() {
        for (id in listOf("universal-amount", "al-rajhi-generic-amount", "unknown"))
            assertThat(supportedPurchaseDraft(event, parsed.copy(templateId = id))).isNull()
    }
    @Test fun `hold decline schedule or heuristic never becomes posted money`() {
        for (state in PostingState.entries.filter { it != PostingState.POSTED })
            assertThat(supportedPurchaseDraft(event, parsed.copy(posting = PostingEvidence(state, PostingSupport.HEURISTIC_ONLY)))).isNull()
        assertThat(supportedPurchaseDraft(event, parsed.copy(posting = PostingEvidence(PostingState.POSTED, PostingSupport.HEURISTIC_ONLY)))).isNull()
    }
    @Test fun `missing date is not silently replaced by receipt date`() {
        assertThat(supportedPurchaseDraft(event, parsed.copy(bankEventDate = null))).isNull()
    }
    @Test fun `income and transfers are not relabeled purchases`() {
        for (type in listOf(TxType.INCOME, TxType.TRANSFER))
            assertThat(supportedPurchaseDraft(event, parsed.copy(type = type))).isNull()
    }
    @Test fun `zero or negative amount is not posted`() {
        for (amount in listOf("0", "-25"))
            assertThat(supportedPurchaseDraft(event, parsed.copy(amount = Money.of(amount)))).isNull()
    }
    @Test fun `literal currency and source namespace are preserved without conversion`() {
        val draft = checkNotNull(supportedPurchaseDraft(event.copy(source = IngestSource.NOTIFICATION), parsed.copy(amount = Money.of("25.00", "USD"))))
        assertThat(draft.sourceNamespace).isEqualTo("notification")
        assertThat(draft.observation.original!!.amount.currency).isEqualTo("USD")
        assertThat(supportedPurchaseDraft(event.copy(source = IngestSource.MANUAL), parsed)).isNull()
    }
}
