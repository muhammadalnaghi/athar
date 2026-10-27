package com.athar.ingestion.smsparser

import com.athar.core.domain.model.IngestSource
import com.athar.core.domain.model.RawIngestEvent
import com.google.common.truth.Truth.assertThat
import kotlinx.datetime.Instant
import org.junit.jupiter.api.Test

class TransferAuthentication051Test {
    private val parser = TemplateBasedSmsParser(BuiltInSmsTemplateRegistry.templates())

    @Test fun purposeQualifiedAuthenticationCodeWithProposedMoneyIsNotATransfer() {
        assertThat(parse("رمز المصادقة هو:123456\nلإتمام عملية التحويل\nمبلغ:100.00\nفي:2026-10-25\nيرجى عدم مشاركته"))
            .isEqualTo(ParseResult.Ignored)
    }

    @Test fun arabicDigitsInAuthenticationInstructionDoNotBecomeMoney() {
        assertThat(parse("رمز المصادقة هو:١٢٣٤٥٦\nلإتمام عملية التحويل\nمبلغ:١٠٠٫٠٠"))
            .isEqualTo(ParseResult.Ignored)
    }

    @Test fun appendedRealCompletedDebitMustRemainParseable() {
        val result = parse("رمز المصادقة هو:123456\nلإتمام عملية التحويل\nAmount:100 SAR\nتم خصم المبلغ من حسابك")
        assertThat(result).isInstanceOf(ParseResult.Success::class.java)
        val row = result as ParseResult.Success
        assertThat(row.amount.amount.compareTo(java.math.BigDecimal("100"))).isEqualTo(0)
        assertThat(row.amount.currency).isEqualTo("SAR")
    }

    @Test fun appendedPostedPurchaseHeadingIsNotSilentlyDiscarded() {
        val result = parse("رمز المصادقة هو:123456\nلإتمام عملية التحويل\nPoS purchase\nAmount:100 SAR")
        assertThat(result).isInstanceOf(ParseResult.Success::class.java)
    }

    private fun parse(body: String) = parser.parse(RawIngestEvent(
        id = "wm051-synthetic-authentication", rawId = "wm051-synthetic-authentication",
        source = IngestSource.SMS, sender = "BSF", body = body,
        receivedAt = Instant.parse("2026-10-25T12:00:00Z"),
    ))
}
