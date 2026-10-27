package com.athar.ingestion.smsparser

import com.athar.core.domain.model.IngestSource
import com.athar.core.domain.model.RawIngestEvent
import com.google.common.truth.Truth.assertThat
import kotlinx.datetime.Instant
import org.junit.jupiter.api.Test

/**
 * WM053 — a positively identified single declined payment attempt is a notice, not
 * money. BSF has no bank-specific decline template, so these bodies reach the
 * universal amount fallback and become false pending expenses. Controls keep real
 * subsequent fees/debits/refunds/retries and legacy prose coverage parseable.
 */
class SingleDeclinedNotice053Test {
    private val parser = TemplateBasedSmsParser(BuiltInSmsTemplateRegistry.templates())

    @Test fun reasonDeclinedInsufficientBalanceAttemptIsIgnoredNotPending() {
        assertThat(parse("Reason:Declined due to insufficient balance\nTransaction:شراء\nCard:9999\nAmount:12.50SAR\nAt:QA Market\nDate:2026-10-25"))
            .isEqualTo(ParseResult.Ignored)
    }

    @Test fun statusDeclinedAttemptIsIgnoredNotPending() {
        assertThat(parse("Status:Declined\nTransaction:Purchase\nCard:9999\nAmount:12.50 SAR\nAt:QA Market\nDate:2026-10-25"))
            .isEqualTo(ParseResult.Ignored)
    }

    @Test fun arabicIndicDigitsInDeclinedAttemptDoNotBecomeMoney() {
        assertThat(parse("Reason:Declined due to insufficient balance\nTransaction:شراء\nAmount:١٢٫٥٠ SAR\nDate:2026-10-25"))
            .isEqualTo(ParseResult.Ignored)
    }

    @Test fun conservativeArabicDeclinedHeadersAreIgnored() {
        assertThat(parse("الحالة:مرفوضة\nالعملية:شراء\nالمبلغ:12.50 ريال\nالتاريخ:2026-10-25"))
            .isEqualTo(ParseResult.Ignored)
        assertThat(parse("السبب:عدم كفاية الرصيد\nالعملية:شراء\nالمبلغ:12.50\nالتاريخ:2026-10-25"))
            .isEqualTo(ParseResult.Ignored)
    }

    @Test fun separatelyDebitedFeeAfterDeclineStaysParseable() {
        val result = parse("Reason:Declined due to insufficient balance\nTransaction:شراء\nAmount:12.50SAR\nA fee of SAR 1.00 was debited")
        assertThat(result).isNotInstanceOf(ParseResult.Ignored::class.java)
    }

    @Test fun laterCompletedPaymentStaysParseable() {
        val result = parse("Status:Declined\nTransaction:Purchase\nAmount:12.50SAR\nDate:2026-10-25\npayment completed\nAmount:100 SAR")
        assertThat(result).isNotInstanceOf(ParseResult.Ignored::class.java)
    }

    @Test fun refundAfterDeclinedAttemptStaysParseable() {
        val result = parse("Status:Declined\nTransaction:Purchase\nAmount:12.50SAR\nRefund of SAR 12.50 issued")
        assertThat(result).isNotInstanceOf(ParseResult.Ignored::class.java)
    }

    @Test fun pendingStatusAloneRemainsATypedPendingRow() {
        val result = parse("Status:Pending\nTransaction:Purchase\nCard:9999\nAmount:12.50 SAR\nAt:QA Market\nDate:2026-10-25")
        assertThat(result).isInstanceOf(ParseResult.Success::class.java)
    }

    @Test fun authenticationReasonAloneIsNotIgnored() {
        val result = parse("Reason:Authentication required\nTransaction:Purchase\nAmount:12.50SAR")
        assertThat(result).isInstanceOf(ParseResult.Success::class.java)
    }

    @Test fun merchantNamedDeclinedIsNotIgnored() {
        val result = parse("Transaction:Purchase\nAt:DECLINED Coffee\nAmount:10 SAR\nDate:2026-10-25")
        assertThat(result).isInstanceOf(ParseResult.Success::class.java)
    }

    @Test fun legacyProseDeclineKeepsExistingCoverage() {
        val result = parse("Purchase of SAR 25.00 at Jarir was declined")
        assertThat(result).isInstanceOf(ParseResult.Success::class.java)
        val row = result as ParseResult.Success
        assertThat(row.amount.amount.compareTo(java.math.BigDecimal("25.00"))).isEqualTo(0)
        assertThat(row.amount.currency).isEqualTo("SAR")
    }

    private fun parse(body: String) = parser.parse(RawIngestEvent(
        id = "wm053-synthetic-declined", rawId = "wm053-synthetic-declined",
        source = IngestSource.SMS, sender = "BSF", body = body,
        receivedAt = Instant.parse("2026-10-25T12:00:00Z"),
    ))

    @Test fun completedStatusAfterDeclinedReasonStaysParseable() {
        assertThat(parse("Reason:Declined due to insufficient balance\nStatus:Completed\nAmount:12.50 SAR\nTransaction:Purchase"))
            .isNotEqualTo(ParseResult.Ignored)
    }

    @Test fun feeDebitedOnDeclineHeaderLineStaysParseable() {
        assertThat(parse("Status:Declined; a fee of SAR 1.00 was debited\nAmount:12.50 SAR\nTransaction:Purchase"))
            .isNotEqualTo(ParseResult.Ignored)
    }
}
