package com.athar.ingestion.smsparser

import com.athar.core.common.money.Money
import com.athar.core.domain.model.IngestSource
import com.athar.core.domain.model.RawIngestEvent
import com.athar.core.domain.model.financial.PostingState
import com.google.common.truth.Truth.assertThat
import kotlinx.datetime.Instant
import org.junit.jupiter.api.Test

class StcCardPurchaseMoneyTest {
    private fun parse(sender: String, body: String) = TemplateBasedSmsParser(BuiltInSmsTemplateRegistry.templates()).parse(
        RawIngestEvent("stc-synthetic", IngestSource.SMS, sender, body,
            Instant.parse("2026-10-21T10:00:00Z"), "stc-synthetic"),
    ) as ParseResult.Success

    @Test fun `STC Bank colon purchase uses explicit total including fees rather than foreign amount`() {
        val result = parse("STC Bank", "Online Purchase\nVia:Card 9999\nAmount:20.00 USD\nFrom:Public Shop\nExchange rate:3.75\nVAT:0.15\nFees:1.00\nTotal due amount:76.15 SAR\nAt:20/10/2026 12:00")
        assertThat(result.amount).isEqualTo(Money.of("76.15"))
        assertThat(result.templateId).isEqualTo("stc-bank-online-purchase")
    }
    @Test fun `STC Pay card number cannot become the payment amount`() {
        val result = parse("STCPAY", "Online Purchase\nCard:9999\nAmount:25.50 SAR\nAt:Public Shop\nAccount:8888\nDate:20/10/2026 12:00")
        assertThat(result.amount).isEqualTo(Money.of("25.50"))
        assertThat(result.templateId).isEqualTo("stcpay-online-purchase")
        assertThat(result.merchant).isEqualTo("Public Shop")
    }
    @Test fun `STC Pay held descriptor is still ambiguous`() {
        assertThat(parse("STCPAY", "Online Purchase\nCard:9999\nAmount:25.50 SAR\nAt:RIDE*PEND").posting.state)
            .isEqualTo(PostingState.AUTHORIZED)
    }
    @Test fun `legacy inline STC Bank transaction syntax remains supported`() {
        val result = parse("STC Bank", "Online Purchase Transaction Amount 25.50 From:Public Shop")
        assertThat(result.amount).isEqualTo(Money.of("25.50"))
        assertThat(result.templateId).isEqualTo("stc-bank-online-purchase")
    }
}
