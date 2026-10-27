package com.athar.ingestion.smsparser.alrajhi

import com.athar.core.common.money.Money
import com.athar.core.domain.model.IngestSource
import com.athar.core.domain.model.RawIngestEvent
import com.athar.core.domain.model.TxType
import com.athar.core.domain.model.financial.PostingEvidence
import com.athar.core.domain.model.financial.PostingState
import com.athar.core.domain.model.financial.PostingSupport
import com.athar.ingestion.smsparser.BankTemplate
import com.athar.ingestion.smsparser.BuiltInSmsTemplateRegistry
import com.athar.ingestion.smsparser.ParseResult
import com.athar.ingestion.smsparser.SenderMatcher
import com.athar.ingestion.smsparser.TemplateBasedSmsParser
import com.google.common.truth.Truth.assertThat
import kotlinx.datetime.Instant
import org.junit.jupiter.api.Test

class CardRepaymentAuthority064Test {
    private val received = Instant.parse("2026-10-21T10:00:00Z")
    private val body = "Credit Card:Payment\nAmount:SR 125.50\nCard:9999\n20/10/2026 12:00\nPayment completed"
    private fun event(text: String, sender: String = "AlRajhiBank") =
        RawIngestEvent("public", IngestSource.SMS, sender, text, received, "public-source")
    private fun parse(text: String) = TemplateBasedSmsParser(BuiltInSmsTemplateRegistry.templates())
        .parse(event(text)) as ParseResult.Success
    private fun assertUncertified(text: String) {
        assertThat(parse(text).posting).isEqualTo(PostingEvidence(PostingState.POSTED, PostingSupport.HEURISTIC_ONLY))
    }

    @Test fun `generic completed language cannot certify an additional fee`() {
        assertUncertified(body + "\nFee:SR 5.00")
    }
    @Test fun `generic completed language cannot replace missing currency evidence`() {
        assertUncertified(body.replace("Amount:SR 125.50", "Amount:125.50"))
    }
    @Test fun `generic completed language cannot certify unsupported foreign repayment`() {
        val foreign = body.replace("Amount:SR 125.50", "Amount:USD 125.50")
        assertUncertified(foreign)
        assertThat(parse(foreign).amount).isEqualTo(Money.of("125.50", "USD"))
    }
    @Test fun `forged template identifier cannot borrow generic completion authority`() {
        val fake = object : BankTemplate {
            override val id = "al-rajhi-credit-card-payment"
            override val senderMatcher = SenderMatcher.Exact("PublicBank")
            override fun tryParse(body: String, receivedAt: Instant) = ParseResult.Success(
                TxType.TRANSFER, Money.of("125.50"), "Public payment", null, null, null, 1f, id,
            )
        }
        val result = TemplateBasedSmsParser(listOf(fake)).parse(event(body, "PublicBank")) as ParseResult.Success
        assertThat(result.posting).isEqualTo(PostingEvidence(PostingState.POSTED, PostingSupport.HEURISTIC_ONLY))
    }
    @Test fun `typed negative states still override valid completion language`() {
        for ((suffix, state) in listOf(
            "Display: PUBLIC*PEND" to PostingState.AUTHORIZED,
            "Payment was declined" to PostingState.DECLINED,
            "The hold was released" to PostingState.RELEASED,
            "Amount will be charged" to PostingState.SCHEDULED,
        )) {
            assertThat(parse(body + "\n" + suffix).posting)
                .isEqualTo(PostingEvidence(state, PostingSupport.HEURISTIC_ONLY))
        }
    }
}
