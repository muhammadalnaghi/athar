package com.athar.ingestion.smsparser.barq

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
 * WM031 regression: the two already-validated Barq completed-card-purchase templates must carry
 * explicit posted evidence from their exact bank headings ("Online Purchases:" plural heading and
 * "POS International Purchase" two-prefix heading) — without weakening any contradictory
 * evidence (declined / released / scheduled / authorized incl. *PEND shapes / instruction), and
 * without granting payment authority to generic, transfer, ATM, or other-template bodies.
 */
class BarqCompletedPurchaseEvidenceTest {

    /** Synthetic public WM031 fixtures — structurally identical to the observed Barq shapes. */
    object Fixtures {
        const val SENDER = "Barq"
        val receivedAt = Instant.parse("2026-10-20T09:30:00Z")

        const val ONLINE_POSTED = """Online Purchases:
Visa card: **9803
Amount: 6 CNY (3.14 SAR)
Wallet Balance: 2496.86
At: ZUOMENDUNCANYIN
On: 2025-07-11 03:24"""

        const val POS_POSTED = """POS International Purchase
Visa card: **4217
Amount: 45.00 USD (168.75 SAR)
At: STARBUCKS RIYADH
Country: US
On: 2025-08-02 14:05"""

        const val MIXED_CURRENCY = """Online Purchases:
Visa card: **9803
Amount: 129.00 AED (131.87 SAR)
Wallet Balance: 2365.00
At: NoonCom
On: 2025-09-01 20:12"""

        const val MISSING_SOURCE_DATE = """Online Purchases:
Visa card: **9803
Amount: 250.00 SAR
Wallet Balance: 2246.86
At: HungerStation"""

        val pendDescriptors = listOf("RIDE*PEND", "RIDE* PEND", "RIDE*PENDING")

        fun onlineWithAt(descriptor: String) = """Online Purchases:
Visa card: **9803
Amount: 6 CNY (3.14 SAR)
At: $descriptor
On: 2025-07-11 03:24"""

        const val DECLINED = """Online Purchases:
Visa card: **9803
Amount: 99.00 SAR
At: SomeShop
Transaction declined"""

        const val RELEASED = """Online Purchases:
Visa card: **9803
Amount: 99.00 SAR
At: SomeShop
Temporary hold was released"""

        const val SCHEDULED = """Online Purchases:
Visa card: **9803
Amount: 99.00 SAR
At: SomeShop
Amount will be charged on 2025-08-01 09:00"""

        const val INSTRUCTION = """Online Purchases:
Visa card: **9803
Amount: 99.00 SAR
At: SomeShop
Enter the verification code to confirm your purchase"""

        const val ATM_WITHDRAWAL = """ATM Withdrawal
Amount: 500.00 SAR
at: Riyadh ATM
On: 2025-07-12 01:00"""

        const val DEBIT_TRANSFER = """Debit Transfer Internal
Amount: 300.00 SAR
To: My Other Wallet
On: 2025-07-13 10:00"""
    }

    @Test fun `online purchases heading is explicit posted evidence`() {
        val result = parse(Fixtures.ONLINE_POSTED)
        assertThat(result.templateId).isEqualTo("barq-online-purchase")
        assertThat(result.posting)
            .isEqualTo(PostingEvidence(PostingState.POSTED, PostingSupport.EXPLICIT_POSTED))
        // SAR settlement, never the foreign amount.
        assertThat(result.amount).isEqualTo(Money.of("3.14"))
        assertThat(result.type).isEqualTo(TxType.EXPENSE)
        assertThat(result.merchant).isEqualTo("ZUOMENDUNCANYIN")
        assertThat(result.bankEventDate).isEqualTo(LocalDate.parse("2025-07-11"))
        assertThat(result.occurredAt).isNotEqualTo(Fixtures.receivedAt)
    }

    @Test fun `pos international heading is explicit posted evidence`() {
        val result = parse(Fixtures.POS_POSTED)
        assertThat(result.templateId).isEqualTo("barq-pos-international")
        assertThat(result.posting)
            .isEqualTo(PostingEvidence(PostingState.POSTED, PostingSupport.EXPLICIT_POSTED))
        assertThat(result.amount).isEqualTo(Money.of("168.75"))
        assertThat(result.merchant).isEqualTo("STARBUCKS RIYADH (US)")
        assertThat(result.bankEventDate).isEqualTo(LocalDate.parse("2025-08-02"))
    }

    @Test fun `mixed currency keeps SAR settlement and explicit posted evidence`() {
        val result = parse(Fixtures.MIXED_CURRENCY)
        assertThat(result.amount).isEqualTo(Money.of("131.87"))
        assertThat(result.posting)
            .isEqualTo(PostingEvidence(PostingState.POSTED, PostingSupport.EXPLICIT_POSTED))
    }

    @Test fun `missing source date never becomes receive date but heading still posts`() {
        val result = parse(Fixtures.MISSING_SOURCE_DATE)
        assertThat(result.bankEventDate).isNull()
        assertThat(result.occurredAt).isEqualTo(Fixtures.receivedAt)
        assertThat(result.amount).isEqualTo(Money.of("250.00"))
        assertThat(result.posting)
            .isEqualTo(PostingEvidence(PostingState.POSTED, PostingSupport.EXPLICIT_POSTED))
    }

    @Test fun `pend descriptors under purchase heading stay authorized heuristic`() {
        for (descriptor in Fixtures.pendDescriptors) {
            val result = parse(Fixtures.onlineWithAt(descriptor))
            assertThat(result.templateId).isEqualTo("barq-online-purchase")
            assertThat(result.posting)
                .isEqualTo(PostingEvidence(PostingState.AUTHORIZED, PostingSupport.HEURISTIC_ONLY))
        }
    }

    @Test fun `declined under purchase heading is never posted`() {
        val result = parse(Fixtures.DECLINED)
        assertThat(result.posting)
            .isEqualTo(PostingEvidence(PostingState.DECLINED, PostingSupport.HEURISTIC_ONLY))
    }

    @Test fun `released under purchase heading is never posted`() {
        val result = parse(Fixtures.RELEASED)
        assertThat(result.posting)
            .isEqualTo(PostingEvidence(PostingState.RELEASED, PostingSupport.HEURISTIC_ONLY))
    }

    @Test fun `scheduled under purchase heading is never posted`() {
        val result = parse(Fixtures.SCHEDULED)
        assertThat(result.posting)
            .isEqualTo(PostingEvidence(PostingState.SCHEDULED, PostingSupport.HEURISTIC_ONLY))
    }

    @Test fun `instruction under purchase heading is never posted`() {
        // The production registry ignores OTP instructions before purchase templates.
        assertThat(TemplateBasedSmsParser(BuiltInSmsTemplateRegistry.templates()).parse(
            RawIngestEvent("wm031-instruction", IngestSource.SMS, Fixtures.SENDER, Fixtures.INSTRUCTION,
                Fixtures.receivedAt, "wm031-instruction"),
        )).isEqualTo(ParseResult.Ignored)
    }

    @Test fun `barq ATM withdrawal gains no payment authority`() {
        val result = parse(Fixtures.ATM_WITHDRAWAL)
        assertThat(result.templateId).isEqualTo("barq-atm-withdrawal")
        assertThat(result.posting)
            .isEqualTo(PostingEvidence(PostingState.POSTED, PostingSupport.HEURISTIC_ONLY))
    }

    @Test fun `barq debit transfer gains no payment authority`() {
        val result = parse(Fixtures.DEBIT_TRANSFER)
        assertThat(result.templateId).isEqualTo("barq-debit-transfer")
        assertThat(result.posting)
            .isEqualTo(PostingEvidence(PostingState.POSTED, PostingSupport.HEURISTIC_ONLY))
    }

    @Test fun `non-barq template id cannot borrow the barq heading`() {
        val template = object : BankTemplate {
            override val id = "public-proof"
            override val senderMatcher = SenderMatcher.Exact("PublicBank")
            override fun tryParse(body: String, receivedAt: Instant) = ParseResult.Success(
                TxType.EXPENSE, Money.of("25.00"), "Public merchant", null, null, null, .95f, id,
            )
        }
        val result = TemplateBasedSmsParser(listOf(template)).parse(
            RawIngestEvent("wm031-event", IngestSource.SMS, "PublicBank", Fixtures.ONLINE_POSTED, Fixtures.receivedAt, "wm031-source"),
        ) as ParseResult.Success
        assertThat(result.posting)
            .isEqualTo(PostingEvidence(PostingState.POSTED, PostingSupport.HEURISTIC_ONLY))
    }

    @Test fun `forged result template id is not an actual validated bank template`() {
        val template = object : BankTemplate {
            override val id = "barq-online-purchase"
            override val senderMatcher = SenderMatcher.Exact("PublicBank")
            override fun tryParse(body: String, receivedAt: Instant) = ParseResult.Success(
                TxType.EXPENSE, Money.of("25.00"), "Public merchant", null, null, null, .99f, id,
            )
        }
        val result = TemplateBasedSmsParser(listOf(template)).parse(RawIngestEvent(
            "forged-proof", IngestSource.SMS, "PublicBank", Fixtures.ONLINE_POSTED,
            Fixtures.receivedAt, "forged-proof",
        )) as ParseResult.Success
        assertThat(result.posting.support).isEqualTo(PostingSupport.HEURISTIC_ONLY)
    }

    private fun parse(body: String): ParseResult.Success =
        TemplateBasedSmsParser(BuiltInSmsTemplateRegistry.templates()).parse(
            RawIngestEvent("wm031-event", IngestSource.SMS, Fixtures.SENDER, body, Fixtures.receivedAt, "wm031-source"),
        ) as ParseResult.Success
}
