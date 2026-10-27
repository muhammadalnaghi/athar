package com.athar.ingestion.smsparser

import com.athar.core.domain.model.IngestSource
import com.athar.core.domain.model.RawIngestEvent
import com.google.common.truth.Truth.assertThat
import kotlinx.datetime.Instant
import org.junit.jupiter.api.Test

class PaymentServiceNoticesTest {
    private val parser = TemplateBasedSmsParser(BuiltInSmsTemplateRegistry.templates())
    private fun parse(body: String) = parser.parse(RawIngestEvent(
        "synthetic-notice", IngestSource.SMS, "AlRajhiBank", body,
        Instant.parse("2026-10-21T10:00:00Z"), "synthetic-notice",
    ))
    private val statement = """
        Credit Card: Monthly Statement
        Card: 9999
        Total amount due: SAR 800.00
        Minimum amount due: SAR 40.00
        Due date: 25-10-2026
    """.trimIndent()

    @Test fun `monthly statement amounts are not a payment`() {
        assertThat(parse(statement)).isEqualTo(ParseResult.Ignored)
    }
    @Test fun `statement amount case and whitespace variations remain notices`() {
        assertThat(parse(statement.lowercase().replace(": ", ":   "))).isEqualTo(ParseResult.Ignored)
    }
    @Test fun `hyphenated one time password service code is not money`() {
        assertThat(parse("Please use this code: 111111\nTo: create one-time Password")).isEqualTo(ParseResult.Ignored)
    }
    @Test fun `hyphenated password request without literal code amount is not money`() {
        assertThat(parse("One-time password code: 222222\nTo: create a password")).isEqualTo(ParseResult.Ignored)
    }
    @Test fun `completed purchase is retained despite appended statement wording`() {
        val body = "Online Purchase\nCard:9999\nAmount:25.00SAR\nAt:Public Shop\n20/10/2026 12:00\n" + statement
        assertThat(parse(body)).isInstanceOf(ParseResult.Success::class.java)
    }
    @Test fun `completed purchase with hyphenated password footer is retained`() {
        assertThat(parse("Online Purchase\nCard:9999\nAmount:25.00SAR\nAt:Public Shop\nNever disclose a one-time password")).isInstanceOf(ParseResult.Success::class.java)
    }
}
