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
import kotlinx.datetime.LocalDate
import org.junit.jupiter.api.Test

/**
 * WM062 regression: the already-validated Al Rajhi credit-card repayment template
 * must carry explicit posted evidence from its exact bank heading
 * ("Credit Card:Payment" / "بطاقة ائتمانية:سداد") — without weakening any
 * contradictory evidence (declined / released / scheduled / authorized incl.
 * literal *PEND shapes / instruction), and without granting repayment authority to
 * marker-less or foreign amounts, composite bodies, generic, transfer,
 * universal-amount, wrong-template or wrong-institution bodies. The recorded
 * movement stays TRANSFER to "Credit Card Payment" — never expense or income.
 */
class AlRajhiCardRepaymentEvidence062Test {

    /** Synthetic public WM062 fixtures — structurally identical to observed Al Rajhi shapes. */
    object Fixtures {
        const val SENDER = "AlRajhiBank"
        val receivedAt = Instant.parse("2026-10-21T10:00:00Z")

        const val EN_POSTED = """Credit Card:Payment
Amount:SR 125.50
Card:9999
20/10/2026 12:00"""

        val EN_UPPER = EN_POSTED.replace("Credit Card:Payment", "CREDIT CARD:PAYMENT")

        const val EN_SUFFIX_SAR = """Credit Card:Payment
Amount: 200.00 SAR
Card:9999
20/10/2026 12:00"""

        const val AR_POSTED = """بطاقة ائتمانية:سداد
بطاقة:1234 ;فيزا
مبلغ:SAR 1500.00
رصيد:SAR 12000.00
20/10/2026 12:00"""

        const val AR_COMPACT = """بطاقة فيزا:سداد بـSR 100
عبر:فيزا;1234
رصيد:12000.0 SR
20/10/2026 12:00"""

        const val AR_SUFFIX_RAS = """بطاقة ائتمانية:سداد
مبلغ: 500.50 ر.س
20/10/2026 12:00"""

        const val NO_DATE = """Credit Card:Payment
Amount:SR 125.50
Card:9999"""

        const val NO_MARKER = """Credit Card:Payment
Amount:125.50
Card:9999
20/10/2026 12:00"""

        const val FOREIGN_SUFFIX = """Credit Card:Payment
Amount: 99.00 USD
Card:9999
20/10/2026 12:00"""

        const val FOREIGN_PREFIX = """Credit Card:Payment
Amount:USD 99.00
Card:9999
20/10/2026 12:00"""

        val pendDescriptors = listOf("DISPLAY*PEND", "DISPLAY* PEND", "DISPLAY*PENDING")

        fun withTrailingLine(line: String) = EN_POSTED + "\n$line"

        const val TRANSFER = """Debit Internal Transfer
Amount: 300.00 SAR
To: Old Friend
20/10/2026 12:00"""

        const val UNIVERSAL_GENERIC = """Credit Card:Payment
125.50 SAR
At: Public Shop"""

        const val DOMESTIC_POS = """PoS purchase
Card:9999 ;Visa
Amount:25.25 SAR
At: PUBLIC SHOP
Balance:900.00 SAR
20/10/2026 12:00"""
    }

    @Test fun `english repayment heading is explicit posted evidence`() {
        val result = parse(Fixtures.EN_POSTED)
        assertThat(result.templateId).isEqualTo("al-rajhi-credit-card-payment")
        assertThat(result.posting)
            .isEqualTo(PostingEvidence(PostingState.POSTED, PostingSupport.EXPLICIT_POSTED))
        assertThat(result.type).isEqualTo(TxType.TRANSFER)
        assertThat(result.merchant).isEqualTo("Credit Card Payment")
        assertThat(result.amount).isEqualTo(Money.of("125.50"))
        assertThat(result.bankEventDate).isEqualTo(LocalDate.parse("2026-10-20"))
    }

    @Test fun `upper case heading variant is also explicit posted evidence`() {
        val result = parse(Fixtures.EN_UPPER)
        assertThat(result.templateId).isEqualTo("al-rajhi-credit-card-payment")
        assertThat(result.posting)
            .isEqualTo(PostingEvidence(PostingState.POSTED, PostingSupport.EXPLICIT_POSTED))
        assertThat(result.amount).isEqualTo(Money.of("125.50"))
    }

    @Test fun `suffixed sar amount marker is explicit posted evidence`() {
        val result = parse(Fixtures.EN_SUFFIX_SAR)
        assertThat(result.posting)
            .isEqualTo(PostingEvidence(PostingState.POSTED, PostingSupport.EXPLICIT_POSTED))
        assertThat(result.amount).isEqualTo(Money.of("200.00"))
    }

    @Test fun `arabic repayment heading with balance line is explicit posted evidence`() {
        val result = parse(Fixtures.AR_POSTED)
        assertThat(result.templateId).isEqualTo("al-rajhi-credit-card-payment")
        assertThat(result.posting)
            .isEqualTo(PostingEvidence(PostingState.POSTED, PostingSupport.EXPLICIT_POSTED))
        assertThat(result.type).isEqualTo(TxType.TRANSFER)
        assertThat(result.amount).isEqualTo(Money.of("1500.00"))
        assertThat(result.bankEventDate).isEqualTo(LocalDate.parse("2026-10-20"))
    }

    @Test fun `arabic compact inline amount is explicit posted evidence`() {
        val result = parse(Fixtures.AR_COMPACT)
        assertThat(result.templateId).isEqualTo("al-rajhi-credit-card-payment")
        assertThat(result.posting)
            .isEqualTo(PostingEvidence(PostingState.POSTED, PostingSupport.EXPLICIT_POSTED))
        assertThat(result.amount).isEqualTo(Money.of("100"))
    }

    @Test fun `arabic riyal symbol marker is explicit posted evidence`() {
        val result = parse(Fixtures.AR_SUFFIX_RAS)
        assertThat(result.posting)
            .isEqualTo(PostingEvidence(PostingState.POSTED, PostingSupport.EXPLICIT_POSTED))
        assertThat(result.amount).isEqualTo(Money.of("500.50"))
    }

    @Test fun `missing source bank date stays null while heading still posts`() {
        val result = parse(Fixtures.NO_DATE)
        assertThat(result.bankEventDate).isNull()
        assertThat(result.occurredAt).isEqualTo(Fixtures.receivedAt)
        assertThat(result.posting)
            .isEqualTo(PostingEvidence(PostingState.POSTED, PostingSupport.EXPLICIT_POSTED))
    }

    @Test fun `gate validates only the exact repayment headings and marked single amount`() {
        assertThat(AlRajhiCardRepaymentEvidence062.validatesCardRepaymentContext(Fixtures.EN_POSTED)).isTrue()
        assertThat(AlRajhiCardRepaymentEvidence062.validatesCardRepaymentContext(Fixtures.EN_UPPER)).isTrue()
        assertThat(AlRajhiCardRepaymentEvidence062.validatesCardRepaymentContext(Fixtures.AR_POSTED)).isTrue()
        assertThat(AlRajhiCardRepaymentEvidence062.validatesCardRepaymentContext(Fixtures.AR_COMPACT)).isTrue()
        assertThat(AlRajhiCardRepaymentEvidence062.validatesCardRepaymentContext(Fixtures.NO_MARKER)).isFalse()
        assertThat(AlRajhiCardRepaymentEvidence062.validatesCardRepaymentContext(Fixtures.FOREIGN_SUFFIX)).isFalse()
        assertThat(AlRajhiCardRepaymentEvidence062.validatesCardRepaymentContext(Fixtures.DOMESTIC_POS)).isFalse()
        assertThat(AlRajhiCardRepaymentEvidence062.validatesCardRepaymentContext(Fixtures.TRANSFER)).isFalse()
    }

    @Test fun `marker-less payment amount keeps template parse but gains no authority`() {
        val result = parse(Fixtures.NO_MARKER)
        assertThat(result.templateId).isEqualTo("al-rajhi-credit-card-payment")
        assertThat(result.amount).isEqualTo(Money.of("125.50"))
        assertThat(result.posting)
            .isEqualTo(PostingEvidence(PostingState.POSTED, PostingSupport.HEURISTIC_ONLY))
    }

    @Test fun `foreign currency payment amount gains no authority`() {
        val result = parse(Fixtures.FOREIGN_SUFFIX)
        assertThat(result.templateId).isEqualTo("al-rajhi-credit-card-payment")
        assertThat(result.amount).isEqualTo(Money.of("99.00", "USD"))
        assertThat(result.posting)
            .isEqualTo(PostingEvidence(PostingState.POSTED, PostingSupport.HEURISTIC_ONLY))
    }

    @Test fun `foreign currency prefix amount cannot gain authority from any template`() {
        val result = parse(Fixtures.FOREIGN_PREFIX)
        assertThat(result.templateId).isEqualTo("al-rajhi-credit-card-payment")
        assertThat(result.type).isEqualTo(TxType.TRANSFER)
        assertThat(result.amount).isEqualTo(Money.of("99.00", "USD"))
        assertThat(result.posting.support).isEqualTo(PostingSupport.HEURISTIC_ONLY)
    }

    @Test fun `fee line under the heading refuses composite certification`() {
        for (line in listOf("Fee: SAR 15.00", "Fee: 15.00", "رسوم: 15.00 ر.س")) {
            val result = parse(Fixtures.withTrailingLine(line))
            assertThat(result.templateId).isEqualTo("al-rajhi-credit-card-payment")
            assertThat(result.posting)
                .isEqualTo(PostingEvidence(PostingState.POSTED, PostingSupport.HEURISTIC_ONLY))
        }
    }

    @Test fun `second monetary movement line refuses composite certification`() {
        for (line in listOf("Payment: 25.00 SAR", "Amount: SAR 25.00")) {
            val result = parse(Fixtures.withTrailingLine(line))
            assertThat(result.posting)
                .isEqualTo(PostingEvidence(PostingState.POSTED, PostingSupport.HEURISTIC_ONLY))
        }
    }

    @Test fun `pend descriptors under the heading stay authorized heuristic`() {
        for (descriptor in Fixtures.pendDescriptors) {
            val result = parse(Fixtures.withTrailingLine("Display: $descriptor"))
            assertThat(result.templateId).isEqualTo("al-rajhi-credit-card-payment")
            assertThat(result.posting)
                .isEqualTo(PostingEvidence(PostingState.AUTHORIZED, PostingSupport.HEURISTIC_ONLY))
        }
    }

    @Test fun `pend marker in raw display evidence stays authorized heuristic`() {
        val result = parse(Fixtures.withTrailingLine("Display: 125.50 SAR * PEND"))
        assertThat(result.posting)
            .isEqualTo(PostingEvidence(PostingState.AUTHORIZED, PostingSupport.HEURISTIC_ONLY))
    }

    @Test fun `hold under the heading stays authorized heuristic`() {
        val result = parse(Fixtures.withTrailingLine("Amount on hold"))
        assertThat(result.posting)
            .isEqualTo(PostingEvidence(PostingState.AUTHORIZED, PostingSupport.HEURISTIC_ONLY))
    }

    @Test fun `declined under the heading is never posted`() {
        val result = parse(Fixtures.withTrailingLine("Purchase was declined"))
        assertThat(result.posting)
            .isEqualTo(PostingEvidence(PostingState.DECLINED, PostingSupport.HEURISTIC_ONLY))
    }

    @Test fun `released under the heading is never posted`() {
        val result = parse(Fixtures.withTrailingLine("The hold was released"))
        assertThat(result.posting)
            .isEqualTo(PostingEvidence(PostingState.RELEASED, PostingSupport.HEURISTIC_ONLY))
    }

    @Test fun `reversed and cancelled under the heading is never posted`() {
        val result = parse(Fixtures.withTrailingLine("The authorization was reversed and the hold cancelled"))
        assertThat(result.posting)
            .isEqualTo(PostingEvidence(PostingState.RELEASED, PostingSupport.HEURISTIC_ONLY))
    }

    @Test fun `future payment under the heading is scheduled not completed`() {
        val result = parse(Fixtures.withTrailingLine("Amount will be charged on 2026-11-01"))
        assertThat(result.posting)
            .isEqualTo(PostingEvidence(PostingState.SCHEDULED, PostingSupport.HEURISTIC_ONLY))
    }

    @Test fun `instruction under the heading is never posted`() {
        val result = parse(Fixtures.withTrailingLine("To purchase at Public Shop pay SAR 25.00"))
        assertThat(result.posting)
            .isEqualTo(PostingEvidence(PostingState.NOTICE, PostingSupport.HEURISTIC_ONLY))
    }

    @Test fun `otp verification body is ignored before repayment authority`() {
        // The production registry ignores verification codes before repayment templates.
        assertThat(
            TemplateBasedSmsParser(BuiltInSmsTemplateRegistry.templates()).parse(
                RawIngestEvent(
                    "wm062-otp", IngestSource.SMS, Fixtures.SENDER,
                    Fixtures.withTrailingLine("Your verification code is 123456. Do not share it."),
                    Fixtures.receivedAt, "wm062-otp",
                ),
            ),
        ).isEqualTo(ParseResult.Ignored)
    }

    @Test fun `unrelated debit transfer gains no repayment authority`() {
        val result = parse(Fixtures.TRANSFER)
        assertThat(result.templateId).isEqualTo("al-rajhi-debit-internal-transfer")
        assertThat(result.posting)
            .isEqualTo(PostingEvidence(PostingState.POSTED, PostingSupport.HEURISTIC_ONLY))
    }

    @Test fun `universal amount generic success gains no repayment authority`() {
        val result = parse(Fixtures.UNIVERSAL_GENERIC)
        assertThat(result.templateId).isNotEqualTo("al-rajhi-credit-card-payment")
        assertThat(result.posting)
            .isEqualTo(PostingEvidence(PostingState.POSTED, PostingSupport.HEURISTIC_ONLY))
    }

    @Test fun `same heading under a different institution gains no repayment authority`() {
        val result = TemplateBasedSmsParser(BuiltInSmsTemplateRegistry.templates()).parse(
            RawIngestEvent("wm062-event", IngestSource.SMS, "RiyadBank", Fixtures.EN_POSTED,
                Fixtures.receivedAt, "wm062-source"),
        ) as ParseResult.Success
        assertThat(result.templateId).isNotEqualTo("al-rajhi-credit-card-payment")
        assertThat(result.posting.support).isEqualTo(PostingSupport.HEURISTIC_ONLY)
    }

    @Test fun `non alrajhi template cannot borrow the heading`() {
        val template = object : BankTemplate {
            override val id = "public-proof"
            override val senderMatcher = SenderMatcher.Exact("PublicBank")
            override fun tryParse(body: String, receivedAt: Instant) = ParseResult.Success(
                TxType.TRANSFER, Money.of("125.50"), "Credit Card Payment", null, null, null, .95f, id,
            )
        }
        val result = TemplateBasedSmsParser(listOf(template)).parse(
            RawIngestEvent("wm062-event", IngestSource.SMS, "PublicBank", Fixtures.EN_POSTED,
                Fixtures.receivedAt, "wm062-source"),
        ) as ParseResult.Success
        assertThat(result.posting)
            .isEqualTo(PostingEvidence(PostingState.POSTED, PostingSupport.HEURISTIC_ONLY))
    }

    @Test fun `forged alrajhi template id under another sender is not validated`() {
        val template = object : BankTemplate {
            override val id = "al-rajhi-credit-card-payment"
            override val senderMatcher = SenderMatcher.Exact("PublicBank")
            override fun tryParse(body: String, receivedAt: Instant) = ParseResult.Success(
                TxType.TRANSFER, Money.of("125.50"), "Credit Card Payment", null, null, null, .99f, id,
            )
        }
        val result = TemplateBasedSmsParser(listOf(template)).parse(
            RawIngestEvent("forged-proof", IngestSource.SMS, "PublicBank", Fixtures.EN_POSTED,
                Fixtures.receivedAt, "forged-proof"),
        ) as ParseResult.Success
        assertThat(result.posting.support).isEqualTo(PostingSupport.HEURISTIC_ONLY)
    }

    private fun parse(body: String): ParseResult.Success =
        TemplateBasedSmsParser(BuiltInSmsTemplateRegistry.templates()).parse(
            RawIngestEvent("wm062-event", IngestSource.SMS, Fixtures.SENDER, body, Fixtures.receivedAt, "wm062-source"),
        ) as ParseResult.Success
}
