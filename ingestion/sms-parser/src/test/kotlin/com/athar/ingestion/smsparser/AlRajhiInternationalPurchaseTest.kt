package com.athar.ingestion.smsparser

import com.athar.core.common.money.Money
import com.athar.core.domain.model.IngestSource
import com.athar.core.domain.model.RawIngestEvent
import com.athar.core.domain.model.financial.PostingState
import com.athar.core.domain.model.financial.PostingSupport
import com.google.common.truth.Truth.assertThat
import kotlinx.datetime.Instant
import kotlinx.datetime.LocalDate
import org.junit.jupiter.api.Test

class AlRajhiInternationalPurchaseTest {
    private fun body(amount: String, extra: String = "", merchant: String = "Public Shop", date: Boolean = true) =
        "PoS International purchase\nCard:9999 ;Visa\nAmount:$amount\nAt: $merchant\n$extra\nBalance:900.00 SAR" +
            if (date) "\n20/10/2026 12:00" else ""
    private fun parse(body: String) = TemplateBasedSmsParser(BuiltInSmsTemplateRegistry.templates()).parse(
        RawIngestEvent("synthetic-international", IngestSource.SMS, "AlRajhiBank", body,
            Instant.parse("2026-10-21T10:00:00Z"), "synthetic-international"),
    ) as ParseResult.Success

    @Test fun `international purchase uses stated SAR settlement not foreign number`() {
        val parsed = parse(body("20.00USD(75.50 SAR)"))
        assertThat(parsed.amount).isEqualTo(Money.of("75.50"))
        assertThat(parsed.templateId).isEqualTo("al-rajhi-pos-purchase-real")
        assertThat(parsed.merchant).isEqualTo("Public Shop")
        assertThat(parsed.bankEventDate).isEqualTo(LocalDate.parse("2026-10-20"))
    }
    @Test fun `explicit total due contains fees once without estimated exchange arithmetic`() {
        assertThat(parse(body("20.00USD(75.50 SAR)", "Fees &VAT: 1.50\nExchange rate~ 3.775\nTotal due amount: 77.00")).amount)
            .isEqualTo(Money.of("77.00"))
    }
    @Test fun `SAR before settlement amount is supported`() {
        assertThat(parse(body("20.00USD(SAR 75.50)")).amount).isEqualTo(Money.of("75.50"))
    }
    @Test fun `foreign only purchase retains literal currency`() {
        assertThat(parse(body("5.25 USD")).amount).isEqualTo(Money.of("5.25", "USD"))
    }
    @Test fun `online sibling also retains foreign literal currency`() {
        assertThat(parse(body("5.25 USD").replace("PoS International purchase", "Online Purchase")).amount)
            .isEqualTo(Money.of("5.25", "USD"))
    }
    @Test fun `online sibling includes explicitly stated total without double counting fees`() {
        assertThat(parse(body("20.00USD(75.50 SAR)", "Fees &VAT:1.50\nTotal due amount:77.00")
            .replace("PoS International purchase", "Online Purchase")).amount).isEqualTo(Money.of("77.00"))
    }
    @Test fun `literal three decimal currency is not truncated to cents`() {
        assertThat(parse(body("5.251 KWD")).amount).isEqualTo(Money.of("5.251", "KWD"))
    }
    @Test fun `bank SR alias remains SAR within the specific online template`() {
        val parsed = parse(body("25.25 SR").replace("PoS International purchase", "Online Purchase"))
        assertThat(parsed.amount).isEqualTo(Money.of("25.25"))
        assertThat(parsed.templateId).isEqualTo("al-rajhi-online-purchase-real")
    }
    @Test fun `bank SR settlement alias never becomes foreign principal`() {
        assertThat(parse(body("20.00USD(75.50 SR)")).amount).isEqualTo(Money.of("75.50"))
    }
    @Test fun `domestic purchase remains a supported exact money shape`() {
        assertThat(parse(body("25.25 SAR").replace("PoS International purchase", "PoS purchase")).amount)
            .isEqualTo(Money.of("25.25"))
    }
    @Test fun `missing source date stays unknown after specific format match`() {
        assertThat(parse(body("20.00USD(75.50 SAR)", date = false)).bankEventDate).isNull()
    }
    @Test fun `international pending descriptor remains held and not certified`() {
        val parsed = parse(body("20.00USD(75.50 SAR)", merchant = "PUBLIC*PEND"))
        assertThat(parsed.posting.state).isEqualTo(PostingState.AUTHORIZED)
        assertThat(parsed.posting.support).isEqualTo(PostingSupport.HEURISTIC_ONLY)
    }
}
