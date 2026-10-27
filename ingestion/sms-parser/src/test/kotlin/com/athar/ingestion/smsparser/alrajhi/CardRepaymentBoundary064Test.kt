package com.athar.ingestion.smsparser.alrajhi

import com.athar.core.common.money.Money
import com.athar.core.domain.model.IngestSource
import com.athar.core.domain.model.RawIngestEvent
import com.athar.core.domain.model.TxType
import com.athar.core.domain.model.financial.PostingEvidence
import com.athar.core.domain.model.financial.PostingState
import com.athar.core.domain.model.financial.PostingSupport
import com.athar.ingestion.smsparser.BuiltInSmsTemplateRegistry
import com.athar.ingestion.smsparser.ParseResult
import com.athar.ingestion.smsparser.TemplateBasedSmsParser
import com.google.common.truth.Truth.assertThat
import kotlinx.datetime.Instant
import org.junit.jupiter.api.Test

class CardRepaymentBoundary064Test {
    private val positive = "Credit Card:Payment\nAmount:SR 125.50\nCard:9999\n20/10/2026 12:00"
    private fun parse(body: String) = TemplateBasedSmsParser(BuiltInSmsTemplateRegistry.templates()).parse(
        RawIngestEvent("public", IngestSource.SMS, "AlRajhiBank", body,
            Instant.parse("2026-10-21T10:00:00Z"), "public-card-source"),
    ) as ParseResult.Success
    private fun assertUncertified(body: String) {
        assertThat(parse(body).posting).isEqualTo(PostingEvidence(PostingState.POSTED, PostingSupport.HEURISTIC_ONLY))
    }

    @Test fun `fee on payment line cannot hide behind primary amount`() {
        assertUncertified(positive.replace("Amount:SR 125.50", "Amount:SR 125.50 Fee:SR 15.00"))
    }
    @Test fun `fee on balance line cannot hide behind balance exemption`() {
        assertUncertified(positive + "\nBalance:SR 500.00 Fee:SR 15.00")
    }
    @Test fun `lowercase foreign amount is still an additional movement`() {
        assertUncertified(positive + "\nSettlement:usd 2.00")
    }
    @Test fun `mixed currency tail refuses single amount certification`() {
        assertUncertified(positive.replace("Amount:SR 125.50", "Amount:SR 125.50 USD"))
    }
    @Test fun `foreign suffix stays literal USD and transfer without certification`() {
        val result = parse(positive.replace("Amount:SR 125.50", "Amount:99.00 USD"))
        assertThat(result.type).isEqualTo(TxType.TRANSFER)
        assertThat(result.amount).isEqualTo(Money.of("99.00", "USD"))
        assertThat(result.posting.support).isEqualTo(PostingSupport.HEURISTIC_ONLY)
    }
    @Test fun `foreign prefix stays literal USD and transfer without fallback relabeling`() {
        val result = parse(positive.replace("Amount:SR 125.50", "Amount:USD 99.00"))
        assertThat(result.type).isEqualTo(TxType.TRANSFER)
        assertThat(result.amount).isEqualTo(Money.of("99.00", "USD"))
        assertThat(result.posting.support).isEqualTo(PostingSupport.HEURISTIC_ONLY)
    }
}
