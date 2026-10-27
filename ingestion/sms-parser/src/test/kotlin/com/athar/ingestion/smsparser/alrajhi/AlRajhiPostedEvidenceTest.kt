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
 * WM038 regression: the already-validated Al Rajhi real PoS International purchase
 * template must carry explicit posted evidence from its exact bank heading
 * ("PoS International purchase") — without weakening any contradictory evidence
 * (declined / released / scheduled / authorized incl. literal *PEND shapes /
 * instruction), and without granting purchase authority to generic, transfer,
 * universal-amount, wrong-template or wrong-institution bodies.
 */
class AlRajhiPostedEvidenceTest {

    /** Synthetic public WM038 fixtures — structurally identical to the observed Al Rajhi shapes. */
    object Fixtures {
        const val SENDER = "AlRajhiBank"
        val receivedAt = Instant.parse("2026-10-21T10:00:00Z")

        const val POS_INTL_POSTED = """PoS International purchase
Card:9999 ;Visa
Amount:20.00USD(75.50 SAR)
At: PUBLIC SHOP
Balance:900.00 SAR
20/10/2026 12:00"""

        val pendDescriptors = listOf("PUBLIC*PEND", "PUBLIC* PEND", "PUBLIC*PENDING")

        fun posIntlWithAt(descriptor: String) = POS_INTL_POSTED
            .replace("At: PUBLIC SHOP", "At: $descriptor")

        fun posIntlWithTrailingLine(line: String) = POS_INTL_POSTED
            .replace("Balance:900.00 SAR", "Balance:900.00 SAR\n$line")

        const val MISSING_SOURCE_DATE = """PoS International purchase
Card:9999 ;Visa
Amount:20.00USD(75.50 SAR)
At: PUBLIC SHOP
Balance:900.00 SAR"""

        const val MIXED_CURRENCY = """PoS International purchase
Card:9999 ;Visa
Amount:129.00AED(131.87 SAR)
At: PUBLIC SHOP
Balance:900.00 SAR
20/10/2026 12:00"""

        const val FOREIGN_ONLY = """PoS International purchase
Card:9999 ;Visa
Amount:5.25 USD
At: PUBLIC SHOP
Balance:900.00 SAR
20/10/2026 12:00"""

        const val DOMESTIC = """PoS purchase
Card:9999 ;Visa
Amount:25.25 SAR
At: PUBLIC SHOP
Balance:900.00 SAR
20/10/2026 12:00"""

        const val TRANSFER = """Debit Internal Transfer
Amount: 300.00 SAR
To: Old Friend
20/10/2026 12:00"""

        const val UNIVERSAL_GENERIC = """PoS International purchase
150.00 SAR
At: Public Shop"""
    }

    @Test fun `pos international purchase heading is explicit posted evidence`() {
        val result = parse(Fixtures.POS_INTL_POSTED)
        assertThat(result.templateId).isEqualTo("al-rajhi-pos-purchase-real")
        assertThat(result.posting)
            .isEqualTo(PostingEvidence(PostingState.POSTED, PostingSupport.EXPLICIT_POSTED))
        // SAR settlement, never the foreign principal number.
        assertThat(result.amount).isEqualTo(Money.of("75.50"))
        assertThat(result.type).isEqualTo(TxType.EXPENSE)
        assertThat(result.merchant).isEqualTo("PUBLIC SHOP")
        assertThat(result.bankEventDate).isEqualTo(LocalDate.parse("2026-10-20"))
    }

    @Test fun `upper case heading variant is also explicit posted evidence`() {
        val result = parse(Fixtures.POS_INTL_POSTED.replace("PoS International purchase", "POS INTERNATIONAL PURCHASE"))
        assertThat(result.templateId).isEqualTo("al-rajhi-pos-purchase-real")
        assertThat(result.posting)
            .isEqualTo(PostingEvidence(PostingState.POSTED, PostingSupport.EXPLICIT_POSTED))
        assertThat(result.amount).isEqualTo(Money.of("75.50"))
    }

    @Test fun `mixed principal and settlement currency keeps explicit posted evidence and literal settlement`() {
        val result = parse(Fixtures.MIXED_CURRENCY)
        assertThat(result.amount).isEqualTo(Money.of("131.87"))
        assertThat(result.posting)
            .isEqualTo(PostingEvidence(PostingState.POSTED, PostingSupport.EXPLICIT_POSTED))
    }

    @Test fun `foreign principal without settlement keeps literal currency and explicit posted evidence`() {
        val result = parse(Fixtures.FOREIGN_ONLY)
        assertThat(result.amount).isEqualTo(Money.of("5.25", "USD"))
        assertThat(result.posting)
            .isEqualTo(PostingEvidence(PostingState.POSTED, PostingSupport.EXPLICIT_POSTED))
    }

    @Test fun `missing source bank date stays null while heading still posts`() {
        val result = parse(Fixtures.MISSING_SOURCE_DATE)
        assertThat(result.bankEventDate).isNull()
        assertThat(result.occurredAt).isEqualTo(Fixtures.receivedAt)
        assertThat(result.amount).isEqualTo(Money.of("75.50"))
        assertThat(result.posting)
            .isEqualTo(PostingEvidence(PostingState.POSTED, PostingSupport.EXPLICIT_POSTED))
    }

    @Test fun `gate validates only the exact international pos heading`() {
        assertThat(AlRajhiPostedEvidence.validatesPosInternationalPurchaseContext(Fixtures.POS_INTL_POSTED)).isTrue()
        assertThat(
            AlRajhiPostedEvidence.validatesPosInternationalPurchaseContext(
                Fixtures.POS_INTL_POSTED.replace("PoS International purchase", "POS INTERNATIONAL PURCHASE"),
            ),
        ).isTrue()
        assertThat(
            AlRajhiPostedEvidence.validatesPosInternationalPurchaseContext(Fixtures.DOMESTIC),
        ).isFalse()
        assertThat(
            AlRajhiPostedEvidence.validatesPosInternationalPurchaseContext(Fixtures.TRANSFER),
        ).isFalse()
    }

    @Test fun `pend descriptors under the positive heading stay authorized heuristic`() {
        for (descriptor in Fixtures.pendDescriptors) {
            val result = parse(Fixtures.posIntlWithAt(descriptor))
            assertThat(result.templateId).isEqualTo("al-rajhi-pos-purchase-real")
            assertThat(result.posting)
                .isEqualTo(PostingEvidence(PostingState.AUTHORIZED, PostingSupport.HEURISTIC_ONLY))
        }
    }

    @Test fun `pend marker in raw display evidence stays authorized heuristic`() {
        val result = parse(Fixtures.posIntlWithTrailingLine("Display: 75.50 SAR * PEND"))
        assertThat(result.templateId).isEqualTo("al-rajhi-pos-purchase-real")
        assertThat(result.posting)
            .isEqualTo(PostingEvidence(PostingState.AUTHORIZED, PostingSupport.HEURISTIC_ONLY))
    }

    @Test fun `hold under the positive heading stays authorized heuristic`() {
        val result = parse(Fixtures.posIntlWithTrailingLine("Amount on hold"))
        assertThat(result.posting)
            .isEqualTo(PostingEvidence(PostingState.AUTHORIZED, PostingSupport.HEURISTIC_ONLY))
    }

    @Test fun `declined under the positive heading is never posted`() {
        val result = parse(Fixtures.posIntlWithTrailingLine("Purchase was declined"))
        assertThat(result.posting)
            .isEqualTo(PostingEvidence(PostingState.DECLINED, PostingSupport.HEURISTIC_ONLY))
    }

    @Test fun `released under the positive heading is never posted`() {
        val result = parse(Fixtures.posIntlWithTrailingLine("Temporary hold was released"))
        assertThat(result.posting)
            .isEqualTo(PostingEvidence(PostingState.RELEASED, PostingSupport.HEURISTIC_ONLY))
    }

    @Test fun `reversed and cancelled under the positive heading is never posted`() {
        val result = parse(Fixtures.posIntlWithTrailingLine("The authorization was reversed and the hold cancelled"))
        assertThat(result.posting)
            .isEqualTo(PostingEvidence(PostingState.RELEASED, PostingSupport.HEURISTIC_ONLY))
    }

    @Test fun `scheduled under the positive heading is never posted`() {
        val result = parse(Fixtures.posIntlWithTrailingLine("Amount will be charged on 2026-11-01"))
        assertThat(result.posting)
            .isEqualTo(PostingEvidence(PostingState.SCHEDULED, PostingSupport.HEURISTIC_ONLY))
    }

    @Test fun `instruction under the positive heading is never posted`() {
        val result = parse(Fixtures.posIntlWithTrailingLine("Continue in the app to purchase."))
        assertThat(result.posting)
            .isEqualTo(PostingEvidence(PostingState.NOTICE, PostingSupport.HEURISTIC_ONLY))
    }

    @Test fun `otp verification body is ignored before purchase authority`() {
        // The production registry ignores verification codes before purchase templates.
        assertThat(
            TemplateBasedSmsParser(BuiltInSmsTemplateRegistry.templates()).parse(
                RawIngestEvent(
                    "wm038-otp", IngestSource.SMS, Fixtures.SENDER,
                    Fixtures.posIntlWithTrailingLine("Your verification code is 123456. Do not share it."),
                    Fixtures.receivedAt, "wm038-otp",
                ),
            ),
        ).isEqualTo(ParseResult.Ignored)
    }

    @Test fun `alrajhi transfer gains no purchase authority`() {
        val result = parse(Fixtures.TRANSFER)
        assertThat(result.templateId).isEqualTo("al-rajhi-debit-internal-transfer")
        assertThat(result.posting)
            .isEqualTo(PostingEvidence(PostingState.POSTED, PostingSupport.HEURISTIC_ONLY))
    }

    @Test fun `universal amount generic success gains no purchase authority`() {
        val result = parse(Fixtures.UNIVERSAL_GENERIC)
        assertThat(result.templateId).isEqualTo("universal-amount")
        assertThat(result.posting)
            .isEqualTo(PostingEvidence(PostingState.POSTED, PostingSupport.HEURISTIC_ONLY))
    }

    @Test fun `same heading under a different institution gains no purchase authority`() {
        val result = TemplateBasedSmsParser(BuiltInSmsTemplateRegistry.templates()).parse(
            RawIngestEvent("wm038-event", IngestSource.SMS, "RiyadBank", Fixtures.POS_INTL_POSTED,
                Fixtures.receivedAt, "wm038-source"),
        ) as ParseResult.Success
        assertThat(result.templateId).isNotEqualTo("al-rajhi-pos-purchase-real")
        assertThat(result.posting.support).isEqualTo(PostingSupport.HEURISTIC_ONLY)
    }

    @Test fun `non alrajhi template cannot borrow the heading`() {
        val template = object : BankTemplate {
            override val id = "public-proof"
            override val senderMatcher = SenderMatcher.Exact("PublicBank")
            override fun tryParse(body: String, receivedAt: Instant) = ParseResult.Success(
                TxType.EXPENSE, Money.of("75.50"), "PUBLIC SHOP", null, null, null, .95f, id,
            )
        }
        val result = TemplateBasedSmsParser(listOf(template)).parse(
            RawIngestEvent("wm038-event", IngestSource.SMS, "PublicBank", Fixtures.POS_INTL_POSTED,
                Fixtures.receivedAt, "wm038-source"),
        ) as ParseResult.Success
        assertThat(result.posting)
            .isEqualTo(PostingEvidence(PostingState.POSTED, PostingSupport.HEURISTIC_ONLY))
    }

    @Test fun `forged alrajhi template id under another sender is not validated`() {
        val template = object : BankTemplate {
            override val id = "al-rajhi-pos-purchase-real"
            override val senderMatcher = SenderMatcher.Exact("PublicBank")
            override fun tryParse(body: String, receivedAt: Instant) = ParseResult.Success(
                TxType.EXPENSE, Money.of("75.50"), "PUBLIC SHOP", null, null, null, .99f, id,
            )
        }
        val result = TemplateBasedSmsParser(listOf(template)).parse(
            RawIngestEvent("forged-proof", IngestSource.SMS, "PublicBank", Fixtures.POS_INTL_POSTED,
                Fixtures.receivedAt, "forged-proof"),
        ) as ParseResult.Success
        assertThat(result.posting.support).isEqualTo(PostingSupport.HEURISTIC_ONLY)
    }

    @Test fun `domestic pos heading scope control is unchanged`() {
        // The domestic heading keeps its pre-existing public completed-verb evidence;
        // this hook neither grants nor removes anything for it.
        val result = parse(Fixtures.DOMESTIC)
        assertThat(result.templateId).isEqualTo("al-rajhi-pos-purchase-real")
        assertThat(result.posting)
            .isEqualTo(PostingEvidence(PostingState.POSTED, PostingSupport.EXPLICIT_POSTED))
        assertThat(result.amount).isEqualTo(Money.of("25.25"))
    }

    private fun parse(body: String): ParseResult.Success =
        TemplateBasedSmsParser(BuiltInSmsTemplateRegistry.templates()).parse(
            RawIngestEvent("wm038-event", IngestSource.SMS, Fixtures.SENDER, body, Fixtures.receivedAt, "wm038-source"),
        ) as ParseResult.Success
}
